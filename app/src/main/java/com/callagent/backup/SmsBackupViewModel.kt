package com.callagent.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.io.IOException
import java.io.InterruptedIOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** App-level live ledger access. Implementations must not hold an Activity, View, or Fragment. */
interface SmsArchiveRecordProvider {
    fun records(context: Context): Sequence<SmsArchiveRecord>
}

/** SAF document streams, isolated so ViewModel I/O and cancellation behavior can be exercised. */
interface SmsBackupDocumentAccess {
    fun openInput(context: Context, uri: Uri): InputStream?
    fun openOutput(context: Context, uri: Uri): OutputStream?
}

object ContentResolverSmsBackupDocumentAccess : SmsBackupDocumentAccess {
    override fun openInput(context: Context, uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)
    override fun openOutput(context: Context, uri: Uri): OutputStream? = context.contentResolver.openOutputStream(uri, "w")
}

object EmptySmsArchiveRecordProvider : SmsArchiveRecordProvider {
    override fun records(context: Context): Sequence<SmsArchiveRecord> = emptySequence()
}

enum class SmsBackupExportFormat(val codecFormat: SmsArchiveCodec.Format, val extension: String) {
    ENCRYPTED(SmsArchiveCodec.Format.ENCRYPTED, "smsbackup"),
    JSON(SmsArchiveCodec.Format.JSON, "json"),
    XML(SmsArchiveCodec.Format.XML, "xml"),
}

enum class PasswordPromptKind { IMPORT, ENCRYPTED_EXPORT }

data class SmsBackupUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val progress: Long = 0,
    val archiveCount: Long = 0,
    val archiveRows: List<SmsArchiveRecord> = emptyList(),
    val showArchiveRows: Boolean = false,
    val hasMoreArchiveRows: Boolean = false,
    val preview: ArchivePreview? = null,
    val passwordPrompt: PasswordPromptKind? = null,
)

/** Holds job state and password arrays in memory only. No SavedState/Bundled password is used. */
class SmsBackupViewModel(
    context: Context,
    private val store: SmsArchiveStore,
    private val recordProvider: SmsArchiveRecordProvider,
    private val documents: SmsBackupDocumentAccess = ContentResolverSmsBackupDocumentAccess,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val operationLock = Any()
    private val initialState = SmsBackupUiState(busy = true, message = "正在读取归档数量…")
    private val mutableState = MutableLiveData(initialState)
    val state: LiveData<SmsBackupUiState> = mutableState
    @Volatile private var currentState = initialState
    private var activeOperation: OperationToken? = null
    private var activeFuture: Future<*>? = null
    private var nextOperationId = 0L
    private var pendingImportUri: Uri? = null
    private var pendingImportPassword: CharArray? = null
    private var encryptedExportPassword: CharArray? = null
    private var pendingExport: PendingExport? = null

    init {
        refreshArchiveCount()
    }

    fun refreshArchiveCount() {
        startOperation("正在读取归档数量…") {
            val count = store.count()
            Completion(transform = { it.copy(archiveCount = count) })
        }
    }

    fun toggleArchiveRows() {
        if (currentState.busy) return
        val shouldShow = !currentState.showArchiveRows
        update { it.copy(showArchiveRows = shouldShow, archiveRows = emptyList(), hasMoreArchiveRows = false) }
        if (shouldShow) loadMoreArchiveRows(reset = true)
    }

    fun loadMoreArchiveRows(reset: Boolean = false) {
        if (currentState.busy) return
        val offset = if (reset) 0 else currentState.archiveRows.size
        startOperation("正在读取归档…") {
            val page = store.latest(limit = PAGE_SIZE, offset = offset)
            val count = store.count()
            Completion(
                transform = { previous ->
                    val combined = if (reset) page else previous.archiveRows + page
                    previous.copy(
                        archiveCount = count,
                        archiveRows = combined,
                        hasMoreArchiveRows = combined.size.toLong() < count,
                    )
                },
            )
        }
    }

    fun beginEncryptedExport() {
        if (currentState.busy) return
        update { it.copy(passwordPrompt = PasswordPromptKind.ENCRYPTED_EXPORT, message = null, messageIsError = false) }
    }

    fun hasPendingExportDestination(): Boolean = synchronized(operationLock) { pendingExport != null }

    fun submitEncryptedExportPassword(password: CharArray): Boolean {
        if (password.size < MIN_PASSWORD_LENGTH) {
            password.fill('\u0000')
            update { it.copy(message = "密码至少需要 8 个字符。", messageIsError = true) }
            return false
        }
        synchronized(operationLock) {
            clearPassword(encryptedExportPassword)
            encryptedExportPassword = password.clone()
        }
        password.fill('\u0000')
        update { it.copy(passwordPrompt = null, message = null, messageIsError = false) }
        val waitingExport = synchronized(operationLock) {
            pendingExport.also { pendingExport = null }
        }
        waitingExport?.let { export(it.uri, it.format) }
        return true
    }

    fun cancelPendingExport() {
        synchronized(operationLock) {
            clearPassword(encryptedExportPassword)
            encryptedExportPassword = null
            pendingExport = null
        }
        update { it.copy(passwordPrompt = null) }
    }

    /** Detects encryption on a throwaway SAF stream; plaintext JSON/XML preview automatically. */
    fun inspectImport(uri: Uri) {
        clearPassword(pendingImportPassword)
        pendingImportPassword = null
        if (!startOperation("正在检查备份文件…") { token ->
                pendingImportUri = uri
                val encrypted = openInput(uri).use { SmsArchiveCodec.isEncrypted(it) }
                if (isCancelled(token)) throw CancellationException("Import inspection cancelled")
                if (encrypted) {
                    Completion(
                        message = "此文件已加密，请输入备份密码。",
                        transform = { it.copy(passwordPrompt = PasswordPromptKind.IMPORT, preview = null) },
                    )
                } else {
                    previewWithinOperation(uri, password = null, token)
                }
            }) {
            update { it.copy(message = "当前操作完成后再选择文件。", messageIsError = false) }
        }
    }

    fun submitImportPassword(password: CharArray) {
        val uri = pendingImportUri
        if (uri == null) {
            password.fill('\u0000')
            update { it.copy(message = "没有待检查的备份文件。", messageIsError = true) }
            return
        }
        val previewPassword = password.clone()
        password.fill('\u0000')
        if (!startOperation("正在读取并验证备份…") { token ->
                previewWithinOperation(uri, previewPassword, token)
            }) {
            clearPassword(previewPassword)
            update { it.copy(message = "当前操作完成后再读取备份。", messageIsError = false) }
        }
    }

    private fun previewWithinOperation(
        uri: Uri,
        password: CharArray?,
        token: OperationToken,
    ): Completion {
        val retainedPassword = password?.clone()
        try {
            val preview = openInput(uri).use { input ->
                store.previewArchive(
                    input = input,
                    password = password,
                    shouldCancel = { isCancelled(token) },
                    onProgress = { count -> updateProgress(token, count) },
                )
            }
            if (isCancelled(token)) throw CancellationException("Import preview cancelled")
            if (retainedPassword != null) {
                synchronized(operationLock) {
                    if (activeOperation === token && !token.cancelRequested) {
                        clearPassword(pendingImportPassword)
                        pendingImportPassword = retainedPassword.clone()
                    }
                }
            }
            return Completion(
                progress = preview.total,
                transform = { it.copy(preview = preview, passwordPrompt = null) },
            )
        } catch (failure: Throwable) {
            val cancelled = isCancelled(token) || failure.isCancellation()
            clearPassword(pendingImportPassword)
            pendingImportPassword = null
            if (cancelled) return Completion(message = "已取消预览。", transform = { it.copy(preview = null) })
            return Completion(
                message = failure.message ?: "无法读取备份文件。",
                isError = true,
                transform = { it.copy(preview = null, passwordPrompt = null) },
            )
        } finally {
            clearPassword(password)
            clearPassword(retainedPassword)
        }
    }

    fun cancelImportPreview() {
        val token = synchronized(operationLock) {
            pendingImportUri = null
            clearPassword(pendingImportPassword)
            pendingImportPassword = null
            activeOperation
        }
        if (token != null) {
            requestCancellation(token)
        } else {
            update {
                it.copy(
                    preview = null,
                    passwordPrompt = null,
                    message = "已取消；归档未写入。",
                    messageIsError = false,
                )
            }
        }
    }

    fun applyImport() {
        val uri = pendingImportUri ?: return
        val preview = currentState.preview ?: return
        val password = synchronized(operationLock) { pendingImportPassword?.clone() }
        if (!startOperation("正在合并归档…") { token ->
                try {
                    val result = openInput(uri).use { input ->
                        store.importArchive(
                            input = input,
                            password = password,
                            expectedSha256 = preview.sha256,
                            shouldCancel = { isCancelled(token) },
                            onProgress = { count -> updateProgress(token, count) },
                        )
                    }
                    val count = runCatching { store.count() }.getOrNull()
                    synchronized(operationLock) {
                        pendingImportUri = null
                        clearPassword(pendingImportPassword)
                        pendingImportPassword = null
                    }
                    Completion(
                        message = "导入完成：新增 ${result.inserted}，更新 ${result.updated}，重复 ${result.duplicates}。",
                        progress = result.total,
                        transform = { state ->
                            state.copy(
                                preview = null,
                                archiveCount = count ?: state.archiveCount + result.inserted,
                            )
                        },
                    )
                } catch (failure: Throwable) {
                    val cancelled = isCancelled(token) || failure.isCancellation()
                    if (cancelled) {
                        Completion(message = "已取消；归档未写入。", transform = { it.copy(preview = null) })
                    } else {
                        Completion(
                            message = "导入失败；归档未写入。 ${failure.message ?: "请检查文件后重试。"}",
                            isError = true,
                        )
                    }
                } finally {
                    clearPassword(password)
                }
            }) {
            clearPassword(password)
            update { it.copy(message = "当前操作完成后再合并归档。", messageIsError = false) }
        }
    }

    fun export(uri: Uri, format: SmsBackupExportFormat) {
        val password = synchronized(operationLock) {
            if (format == SmsBackupExportFormat.ENCRYPTED) encryptedExportPassword?.clone() else null
        }
        if (format == SmsBackupExportFormat.ENCRYPTED && password == null) {
            synchronized(operationLock) { pendingExport = PendingExport(uri, format) }
            update {
                it.copy(
                    passwordPrompt = PasswordPromptKind.ENCRYPTED_EXPORT,
                    message = "请设置加密备份密码。",
                    messageIsError = false,
                )
            }
            return
        }
        if (!startOperation("正在导出本机已同步记录和归档…") { token ->
                try {
                    val output = documents.openOutput(appContext, uri)
                        ?: throw IOException("无法创建备份文件。")
                    output.use {
                        store.exportArchive(
                            output = it,
                            format = format.codecFormat,
                            password = password,
                            extraRecords = recordProvider.records(appContext),
                            shouldCancel = { isCancelled(token) },
                        )
                    }
                    Completion(message = "备份已导出。")
                } catch (failure: Throwable) {
                    val cancelled = isCancelled(token) || failure.isCancellation()
                    if (cancelled) {
                        Completion(message = "已取消；备份文件可能不完整。")
                    } else {
                        Completion(
                            message = "导出失败；未生成可用备份。 ${failure.message ?: "请检查存储空间后重试。"}",
                            isError = true,
                        )
                    }
                } finally {
                    clearPassword(password)
                    if (format == SmsBackupExportFormat.ENCRYPTED) {
                        synchronized(operationLock) {
                            clearPassword(encryptedExportPassword)
                            encryptedExportPassword = null
                        }
                    }
                }
            }) {
            clearPassword(password)
            update { it.copy(message = "当前操作完成后再导出。", messageIsError = false) }
        }
    }

    fun cancelCurrentOperation() {
        val token = synchronized(operationLock) {
            activeOperation?.takeIf { activeFuture != null }
        } ?: return
        requestCancellation(token)
    }

    private fun requestCancellation(token: OperationToken) {
        synchronized(operationLock) {
            if (activeOperation?.id != token.id || activeFuture == null) return
            token.cancelRequested = true
            token.thread?.interrupt()
            update { it.copy(message = "正在取消…", messageIsError = false) }
        }
    }

    private fun startOperation(
        message: String,
        work: (OperationToken) -> Completion,
    ): Boolean = synchronized(operationLock) {
        if (activeOperation != null) return@synchronized false
        val token = OperationToken(++nextOperationId)
        activeOperation = token
        update { it.copy(busy = true, progress = 0, message = message, messageIsError = false) }
        try {
            activeFuture = worker.submit {
                token.thread = Thread.currentThread()
                val completion = try {
                    if (isCancelled(token)) throw CancellationException("Operation cancelled")
                    work(token)
                } catch (failure: Throwable) {
                    val cancelled = isCancelled(token) || failure.isCancellation()
                    Completion(
                        message = if (cancelled) "已取消；操作未提交，导出文件可能不完整。" else failure.message ?: "操作失败。",
                        isError = !cancelled,
                    )
                } finally {
                    token.thread = null
                }
                finishOperation(token, completion)
            }
        } catch (failure: Throwable) {
            activeOperation = null
            activeFuture = null
            update {
                it.copy(
                    busy = false,
                    message = failure.message ?: "无法启动备份操作。",
                    messageIsError = true,
                )
            }
        }
        true
    }

    private fun finishOperation(token: OperationToken, completion: Completion) {
        synchronized(operationLock) {
            if (activeOperation?.id != token.id || activeFuture == null) return
            activeOperation = null
            activeFuture = null
            update { current ->
                completion.transform(current).copy(
                    busy = false,
                    progress = completion.progress,
                    message = completion.message,
                    messageIsError = completion.isError,
                )
            }
        }
    }

    private fun updateProgress(token: OperationToken, count: Long) {
        synchronized(operationLock) {
            if (activeOperation !== token) return
            update { it.copy(progress = count) }
        }
    }

    private fun isCancelled(token: OperationToken): Boolean =
        token.cancelRequested || Thread.currentThread().isInterrupted

    private fun openInput(uri: Uri) = documents.openInput(appContext, uri)
        ?: throw IOException("无法打开所选备份文件。")

    private fun update(transform: (SmsBackupUiState) -> SmsBackupUiState) {
        synchronized(this) {
            currentState = transform(currentState)
            mutableState.postValue(currentState)
        }
    }

    override fun onCleared() {
        synchronized(operationLock) {
            activeOperation?.let {
                it.cancelRequested = true
                it.thread?.interrupt()
            }
            // Queue close behind the current job so a database transaction can finish rolling back.
            runCatching { worker.execute { runCatching { store.close() } } }
            worker.shutdown()
        }
        clearPassword(pendingImportPassword)
        clearPassword(encryptedExportPassword)
        pendingImportPassword = null
        encryptedExportPassword = null
        super.onCleared()
    }

    private data class OperationToken(
        val id: Long,
        @Volatile var cancelRequested: Boolean = false,
        @Volatile var thread: Thread? = null,
    )

    private data class Completion(
        val message: String? = null,
        val isError: Boolean = false,
        val progress: Long = 0,
        val transform: (SmsBackupUiState) -> SmsBackupUiState = { it },
    )

    private data class PendingExport(val uri: Uri, val format: SmsBackupExportFormat)

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
        private const val PAGE_SIZE = 100
    }
}

private fun Throwable.isCancellation(): Boolean =
    this is CancellationException || this is InterruptedException || this is InterruptedIOException ||
        cause?.isCancellation() == true

private fun clearPassword(password: CharArray?) {
    password?.fill('\u0000')
}
