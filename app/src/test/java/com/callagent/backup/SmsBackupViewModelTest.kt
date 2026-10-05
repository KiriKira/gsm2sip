package com.callagent.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmsBackupViewModelTest {
    private lateinit var context: Context
    private val stores = ArrayList<SmsArchiveStore>()
    private val cleanupViewModelStore = ViewModelStore()
    private var nextModelKey = 0

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("sms-archive.db")
    }

    @After
    fun tearDown() {
        cleanupViewModelStore.clear()
        stores.forEach(SmsArchiveStore::close)
        context.deleteDatabase("sms-archive.db")
    }

    @Test
    fun rapidSecondActionDoesNotReplaceOrOverlapCancellationInFlight() {
        val blockingInput = BlockingInputStream()
        val documents = FakeDocuments(blockingInput)
        val viewModel = newViewModel(documents)
        awaitState(viewModel) { !it.busy }

        viewModel.inspectImport(Uri.parse("content://backup/first"))
        assertTrue("inspection should enter its blocking SAF read", blockingInput.entered.await(3, TimeUnit.SECONDS))
        viewModel.inspectImport(Uri.parse("content://backup/second"))
        viewModel.cancelCurrentOperation()

        awaitState(viewModel) { !it.busy && it.message.orEmpty().contains("取消") }
        assertEquals(1, documents.inputOpens)
        assertTrue(viewModel.state.value?.message.orEmpty().contains("取消"))
        assertEquals(0L, stores.single().count())
    }

    @Test
    fun retainedViewModelKeepsOperationUiAcrossActivityRecreationWithoutSavingSecrets() {
        val viewModelStore = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                newViewModel(FakeDocuments(BlockingInputStream()), registerForCleanup = false) as T
        }
        val firstActivityModel = ViewModelProvider(viewModelStore, factory)[SmsBackupViewModel::class.java]
        awaitState(firstActivityModel) { !it.busy }
        firstActivityModel.beginEncryptedExport()
        idleMain()

        val recreatedActivityModel = ViewModelProvider(viewModelStore, factory)[SmsBackupViewModel::class.java]
        assertSame(firstActivityModel, recreatedActivityModel)
        assertEquals(PasswordPromptKind.ENCRYPTED_EXPORT, recreatedActivityModel.state.value?.passwordPrompt)

        val password = "portable secret".toCharArray()
        assertTrue(recreatedActivityModel.submitEncryptedExportPassword(password))
        assertTrue(password.all { it == '\u0000' })
        idleMain()
        val visibleState = recreatedActivityModel.state.value.toString()
        assertFalse(visibleState.contains("portable secret"))
        viewModelStore.clear()
    }

    @Test
    fun recreatedPickerResultRequestsPasswordAndReusesItsDestinationOnce() {
        val output = java.io.ByteArrayOutputStream()
        var outputOpens = 0
        val documents = object : SmsBackupDocumentAccess {
            override fun openInput(context: Context, uri: Uri): InputStream = error("import not expected")
            override fun openOutput(context: Context, uri: Uri): OutputStream {
                assertEquals("content://backup/already-chosen", uri.toString())
                outputOpens++
                return output
            }
        }
        val model = newViewModel(documents)
        awaitState(model) { !it.busy }
        model.export(Uri.parse("content://backup/already-chosen"), SmsBackupExportFormat.ENCRYPTED)
        idleMain()
        assertTrue(model.hasPendingExportDestination())
        assertEquals(PasswordPromptKind.ENCRYPTED_EXPORT, model.state.value?.passwordPrompt)
        assertTrue(model.submitEncryptedExportPassword("portable password".toCharArray()))
        assertFalse(model.hasPendingExportDestination())
        awaitState(model) { !it.busy && it.message == "备份已导出。" }
        assertEquals(1, outputOpens)
        assertTrue(SmsArchiveCodec.isEncrypted(java.io.ByteArrayInputStream(output.toByteArray())))
    }

    private fun newViewModel(
        documents: SmsBackupDocumentAccess,
        registerForCleanup: Boolean = true,
    ): SmsBackupViewModel = SmsBackupViewModel(
            context = context,
            store = SmsArchiveStore(context).also(stores::add),
            recordProvider = EmptySmsArchiveRecordProvider,
            documents = documents,
        ).also { model ->
            if (registerForCleanup) cleanupViewModelStore.put("direct-${nextModelKey++}", model)
        }

    private var awaitedState: SmsBackupUiState? = null

    private fun awaitState(viewModel: SmsBackupViewModel, predicate: (SmsBackupUiState) -> Boolean) {
        val observer = androidx.lifecycle.Observer<SmsBackupUiState> { awaitedState = it }
        viewModel.state.observeForever(observer)
        try {
            val timeoutAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < timeoutAt) {
                idleMain()
                if (awaitedState?.let(predicate) == true) return
                Thread.sleep(10)
            }
        } finally {
            viewModel.state.removeObserver(observer)
        }
        throw AssertionError("ViewModel state did not reach the expected condition")
    }

    private fun idleMain() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private class FakeDocuments(private val input: InputStream) : SmsBackupDocumentAccess {
        @Volatile var inputOpens = 0
            private set

        override fun openInput(context: Context, uri: Uri): InputStream {
            inputOpens++
            return input
        }

        override fun openOutput(context: Context, uri: Uri): OutputStream = error("export not expected in this test")
    }

    private class BlockingInputStream : InputStream() {
        val entered = CountDownLatch(1)
        override fun read(): Int = read(ByteArray(1), 0, 1)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            entered.countDown()
            try {
                CountDownLatch(1).await()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("test stream interrupted")
            }
            return -1
        }
    }
}
