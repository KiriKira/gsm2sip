package com.callagent.gateway.root

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.callagent.gateway.BuildConfig
import java.nio.charset.StandardCharsets
import java.lang.reflect.InvocationTargetException
import java.util.Base64

/**
 * Read-only Telephony/Telecom adapter entry point, launched by the Magisk
 * module in an isolated UID_SYSTEM app_process. The module itself must first
 * verify that its caller is root and may invoke only these fixed commands.
 *
 * This process uses Android's system package context and therefore must run as
 * UID 1000. A root UID cannot truthfully attribute calls as package "android".
 */
object RootTelephonyBroker {
    private const val PROTOCOL_VERSION = 1
    private const val BROKER_VERSION = 1
    private const val MAX_ACCOUNTS = 32
    private const val MAX_CALL_CAPABLE_HANDLES = 64
    private const val MAX_OUTPUT_CHARS = 32 * 1024
    private const val MAX_ACCOUNT_TEXT_BYTES = 256
    private const val MAX_USER_ID = 21474
    private const val SOURCE = "magisk-system-telephony"
    private const val LOG_TAG = "RootTelephonyBroker"

    private data class Candidate(val subscriptionId: Int, val handle: PhoneAccountHandle)

    private data class Result(
        val status: String,
        val userId: Int,
        val accounts: List<Candidate> = emptyList(),
        val errorCode: String? = null
    )

    private class DiagnosticState(var stage: String = "entry")

    @JvmStatic
    fun main(args: Array<String>) {
        val diagnostic = DiagnosticState()
        val (result, exitCode) = try {
            execute(args, diagnostic)
        } catch (failure: SecurityException) {
            logDiagnostic(diagnostic.stage, failure)
            Result("unavailable", 0, errorCode = "permission") to 10
        } catch (failure: ReflectiveOperationException) {
            logDiagnostic(diagnostic.stage, failure)
            Result("unavailable", 0, errorCode = "capability") to 10
        } catch (failure: RuntimeException) {
            logDiagnostic(diagnostic.stage, failure)
            Result("unavailable", 0, errorCode = "service") to 10
        } catch (failure: LinkageError) {
            logDiagnostic(diagnostic.stage, failure)
            Result("unavailable", 0, errorCode = "api") to 10
        }

        emit(result)
        System.exit(exitCode)
    }

    private fun execute(args: Array<String>, diagnostic: DiagnosticState): Pair<Result, Int> {
        diagnostic.stage = "process_uid"
        val processUid = Process.myUid()
        if (processUid != Process.SYSTEM_UID) {
            return Result("error", 0, errorCode = "uid") to 20
        }

        diagnostic.stage = "argument_validation"
        if (args.size !in 2..3) {
            return Result("error", 0, errorCode = "arguments") to 20
        }
        val action = args[0]
        val requestedSubscriptionId = when (action) {
            "accounts" -> {
                if (args.size != 2) return Result("error", 0, errorCode = "arguments") to 20
                null
            }
            "resolve-account" -> {
                if (args.size != 3) return Result("error", 0, errorCode = "arguments") to 20
                parseDecimal(args[1], Int.MAX_VALUE)
                    ?: return Result("error", 0, errorCode = "arguments") to 20
            }
            else -> return Result("error", 0, errorCode = "arguments") to 20
        }
        val userArg = if (action == "accounts") args[1] else args[2]
        val expectedUserId = parseDecimal(userArg, MAX_USER_ID)
            ?: return Result("error", 0, errorCode = "arguments") to 20
        diagnostic.stage = "user_guard"
        if (expectedUserId != 0) {
            return Result("unavailable", expectedUserId, errorCode = "user") to 10
        }

        diagnostic.stage = "hidden_api_setup"
        enableProcessLocalHiddenApiAccess()
        diagnostic.stage = "mainline_module_initialization"
        initializeMainlineModules()
        diagnostic.stage = "system_context_setup"
        val context = systemContext()
        diagnostic.stage = "system_context_guard"
        if (!isTrustedSystemContext(context, processUid)) {
            return Result("unavailable", expectedUserId, errorCode = "context") to 10
        }
        diagnostic.stage = "account_user_handle"
        val expectedHandleUser = UserHandle.getUserHandleForUid(Process.SYSTEM_UID)

        diagnostic.stage = "subscription_service_lookup"
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            ?: return Result("unavailable", expectedUserId, errorCode = "subscription") to 10
        diagnostic.stage = "telecom_service_lookup"
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: return Result("unavailable", expectedUserId, errorCode = "telecom") to 10
        diagnostic.stage = "telephony_service_lookup"
        val telephony = context.getSystemService(TelephonyManager::class.java)
            ?: return Result("unavailable", expectedUserId, errorCode = "telephony") to 10

        diagnostic.stage = "phone_permission_check"
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return Result("unavailable", expectedUserId, errorCode = "permission") to 10
        }

        diagnostic.stage = "active_subscription_query"
        val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList.orEmpty()
        if (activeSubscriptions.size > MAX_ACCOUNTS) {
            return Result("unavailable", expectedUserId, errorCode = "limit") to 10
        }
        val activeIds = activeSubscriptions.map { it.subscriptionId }
        val activeIdCounts = activeIds.groupingBy { it }.eachCount()
        val uniqueActiveIds = activeIdCounts.filterValues { it == 1 }.keys

        diagnostic.stage = "call_capable_account_query"
        val callCapable = telecom.callCapablePhoneAccounts.orEmpty().distinct()
        if (callCapable.size > MAX_CALL_CAPABLE_HANDLES) {
            return Result("unavailable", expectedUserId, errorCode = "limit") to 10
        }

        val candidates = ArrayList<Candidate>()
        for (handle in callCapable) {
            diagnostic.stage = "account_filter"
            if (handle.userHandle != expectedHandleUser) continue
            if (!isWireSafe(handle)) continue
            diagnostic.stage = "phone_account_query"
            val account = telecom.getPhoneAccount(handle) ?: continue
            if (!account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) continue

            diagnostic.stage = "subscription_mapping"
            val subscriptionId = subscriptionIdForAccount(telephony, account, handle)
                ?: continue
            if (subscriptionId < 0 || subscriptionId !in uniqueActiveIds) continue
            diagnostic.stage = "forward_mapping"
            if (!forwardMappingMatches(telephony, subscriptionId, handle)) continue
            candidates += Candidate(subscriptionId, handle)
        }

        // A subId or opaque PhoneAccountHandle that has more than one inverse
        // is ambiguous. Omit every affected row rather than selecting one.
        diagnostic.stage = "account_deduplication"
        val subCounts = candidates.groupingBy { it.subscriptionId }.eachCount()
        val handleCounts = candidates.groupingBy { it.handle }.eachCount()
        val uniqueCandidates = candidates.filter {
            subCounts[it.subscriptionId] == 1 && handleCounts[it.handle] == 1
        }.sortedBy { it.subscriptionId }
        if (uniqueCandidates.size > MAX_ACCOUNTS) {
            return Result("unavailable", expectedUserId, errorCode = "limit") to 10
        }

        val selected = if (requestedSubscriptionId == null) uniqueCandidates else {
            uniqueCandidates.filter { it.subscriptionId == requestedSubscriptionId }
        }
        return Result("ok", expectedUserId, selected) to 0
    }

    /** Log only fixed stage labels and an exception class; never messages or stacks. */
    private fun logDiagnostic(stage: String, failure: Throwable) {
        val reportedFailure = if (failure is InvocationTargetException) {
            failure.targetException ?: failure
        } else {
            failure
        }
        val exceptionClass = reportedFailure.javaClass.name
            .takeIf { it.length <= 128 && it.all { char -> char.isLetterOrDigit() || char in "._$" } }
            ?: "unknown"
        try {
            Log.e(LOG_TAG, "stage=$stage exception=$exceptionClass")
        } catch (_: Throwable) {
            // Diagnostics must not replace the original broker failure.
        }
    }

    /**
     * systemMain() creates a synthetic LoadedApk for package "android" whose
     * ApplicationInfo uid can be 0. The child process UID is authoritative;
     * only use the system context's package name as its package identity.
     */
    internal fun isTrustedSystemContext(context: Context, processUid: Int): Boolean =
        processUid == Process.SYSTEM_UID && context.packageName == "android"

    /**
     * Use the public reverse association where it exists. Android O through Q
     * exposes the exact Telecom PhoneAccount -> subId relation through this
     * framework method, which is hidden from the app SDK.
     */
    private fun subscriptionIdForAccount(
        telephony: TelephonyManager,
        account: PhoneAccount,
        handle: PhoneAccountHandle
    ): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val publicReverse = findMethod(TelephonyManager::class.java, "getSubscriptionId", PhoneAccountHandle::class.java)
            if (publicReverse != null) {
                val value = publicReverse.invoke(telephony, handle) as? Number
                if (value != null) return value.toInt()
            }
        }

        val reverse = findMethod(TelephonyManager::class.java, "getSubIdForPhoneAccount", PhoneAccount::class.java)
            ?: return null
        return (reverse.invoke(telephony, account) as? Number)?.toInt()
    }

    /** Check the matching forward relation wherever the platform exposes it. */
    private fun forwardMappingMatches(
        telephony: TelephonyManager,
        subscriptionId: Int,
        expected: PhoneAccountHandle
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val forward = telephony.createForSubscriptionId(subscriptionId).phoneAccountHandle
            return forward == expected
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val forwardMethod = findMethod(
                TelephonyManager::class.java,
                "getPhoneAccountHandleForSubscriptionId",
                Int::class.javaPrimitiveType!!
            )
            if (forwardMethod != null) {
                val forward = forwardMethod.invoke(telephony, subscriptionId) as? PhoneAccountHandle
                return forward == expected
            }
        }

        // On O/P, the unique inverse over Telecom's live call-capable accounts
        // is the only framework relation available. Do not infer from handle.id.
        return true
    }

    private fun systemContext(): Context {
        if (Looper.myLooper() == null && Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val systemMain = activityThreadClass.getDeclaredMethod("systemMain")
        val activityThread = systemMain.invoke(null)
        val getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext")
        return getSystemContext.invoke(activityThread) as Context
    }

    /**
     * A custom app_process entry point bypasses ActivityThread.main(), which
     * performs this per-process initialization before attaching the process.
     * TelephonyManager's subscription binder lookup depends on the telephony
     * service manager installed here.
     */
    @Suppress("BlockedPrivateApi")
    private fun initializeMainlineModules() {
        // The initializer and the TelephonyServiceManager dependency were
        // introduced with Android 11; older releases use the legacy lookup.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        activityThreadClass.getDeclaredMethod("initializeMainlineModules").invoke(null)
    }

    /** Hidden API exemptions apply only to this short-lived isolated child. */
    private fun enableProcessLocalHiddenApiAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val runtimeClass = Class.forName("dalvik.system.VMRuntime")
            val runtime = runtimeClass.getDeclaredMethod("getRuntime").invoke(null)
            val exemptions = arrayOf(
                "Landroid/app/ActivityThread;",
                "Landroid/telephony/TelephonyManager;"
            )
            runtimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                .invoke(runtime, exemptions)
        } catch (_: ReflectiveOperationException) {
            // Try the exact hidden framework methods anyway; if the ROM blocks
            // them, that capability is reported unavailable below.
        } catch (_: SecurityException) {
            // Same fail-closed probe behavior as an absent exemption method.
        }
    }

    private fun findMethod(owner: Class<*>, name: String, parameter: Class<*>): java.lang.reflect.Method? =
        try {
            owner.getDeclaredMethod(name, parameter).apply { isAccessible = true }
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private fun emit(result: Result) {
        val rows = buildList {
            add("protocol=$PROTOCOL_VERSION")
            add("broker_version=$BROKER_VERSION")
            add("app_version=${BuildConfig.VERSION_CODE}")
            add("broker_uid=${Process.myUid()}")
            add("source=$SOURCE")
            add("status=${result.status}")
            add("user_id=${result.userId}")
            add("count=${result.accounts.size}")
            result.accounts.forEachIndexed { index, account ->
                val component = account.handle.componentName.flattenToString()
                val component64 = encode(component)
                val id64 = encode(account.handle.id)
                add("account.$index.sub_id=${account.subscriptionId}")
                add("account.$index.component_b64=$component64")
                add("account.$index.id_b64=$id64")
                add("account.$index.user_id=${result.userId}")
            }
            result.errorCode?.let { add("error=$it") }
        }
        val output = rows.joinToString("\n", postfix = "\n")
        if (output.length > MAX_OUTPUT_CHARS) {
            System.out.print(
                "protocol=$PROTOCOL_VERSION\nbroker_version=$BROKER_VERSION\n" +
                    "app_version=${BuildConfig.VERSION_CODE}\nbroker_uid=${Process.myUid()}\n" +
                    "source=$SOURCE\nstatus=unavailable\nuser_id=${result.userId}\ncount=0\nerror=limit\n"
            )
            System.err.println("unavailable:limit")
            return
        }
        System.out.print(output)
        System.out.flush()
        result.errorCode?.let { System.err.println("${result.status}:$it") }
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun parseDecimal(value: String, max: Int): Int? {
        if (value.isEmpty() || value.length > 10 || value.any { it !in '0'..'9' }) return null
        val parsed = value.toIntOrNull() ?: return null
        return parsed.takeIf { it in 0..max }
    }

    private fun isWireSafe(handle: PhoneAccountHandle): Boolean {
        val component = handle.componentName.flattenToString()
        val componentBytes = component.toByteArray(StandardCharsets.UTF_8)
        val idBytes = handle.id.toByteArray(StandardCharsets.UTF_8)
        return componentBytes.size <= MAX_ACCOUNT_TEXT_BYTES && idBytes.size <= MAX_ACCOUNT_TEXT_BYTES &&
            component.none(Char::isISOControl) && handle.id.none(Char::isISOControl)
    }
}
