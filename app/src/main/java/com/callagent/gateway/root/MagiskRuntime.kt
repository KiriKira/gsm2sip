package com.callagent.gateway.root

import android.content.ComponentName
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.telecom.PhoneAccountHandle
import com.callagent.gateway.BuildConfig
import com.callagent.gateway.RootShell
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64 as JavaBase64

/** App-side client for the module's fixed, read-only Telephony broker command. */
internal object MagiskRuntime {
    private const val CONTROL = "/data/adb/modules/sip-gsm-gateway/bin/gsm2sipctl"
    private const val MAX_OUTPUT_CHARS = 32 * 1024
    private const val COMMAND_TIMEOUT_MS = 12_000L
    private const val SNAPSHOT_TTL_MS = 350L
    private const val PER_USER_RANGE = 100_000

    private val cacheLock = Any()
    private var cachedAtMs = Long.MIN_VALUE
    private var cachedForUserId = -1
    private var cachedAccounts: Map<Int, PhoneAccountHandle>? = null
    private var cachePopulated = false

    /** Returns null when the broker is unavailable or its response is invalid. */
    fun phoneAccounts(forceRefresh: Boolean = false): Map<Int, PhoneAccountHandle>? = synchronized(cacheLock) {
        val appUid = Process.myUid()
        val userId = appUid / PER_USER_RANGE
        val now = SystemClock.elapsedRealtime()
        if (!forceRefresh && cachePopulated && userId == cachedForUserId && now - cachedAtMs in 0..SNAPSHOT_TTL_MS) {
            return@synchronized cachedAccounts
        }

        // The isolated system-context broker deliberately serves only user 0.
        // Other app users must resolve voice accounts in their own process.
        if (userId != 0 || appUid < 0) {
            return@synchronized null.also { remember(userId, null, now) }
        }
        val expectedUser = UserHandle.getUserHandleForUid(appUid)
        val command = "$CONTROL accounts $userId"
        val output = RootShell.execForOutput(
            command,
            timeoutMs = COMMAND_TIMEOUT_MS,
            maxOutputChars = MAX_OUTPUT_CHARS
        )
        val records = MagiskRuntimeProtocol.parse(
            output = output,
            expectedUserId = userId,
            expectedAppVersion = BuildConfig.VERSION_CODE
        )
        val result = records?.let { rows ->
            val resolved = LinkedHashMap<Int, PhoneAccountHandle>()
            for (row in rows) {
                val handleUid = row.userId * PER_USER_RANGE
                if (row.userId != userId || handleUid < 0) return@let null
                val handleUser = UserHandle.getUserHandleForUid(handleUid)
                if (handleUser != expectedUser) return@let null
                val component = ComponentName.unflattenFromString(row.componentName)
                    ?: return@let null
                if (component.flattenToString() != row.componentName) return@let null
                val handle = PhoneAccountHandle(component, row.id, handleUser)
                if (resolved.put(row.subscriptionId, handle) != null) return@let null
            }
            resolved
        }
        remember(userId, result, SystemClock.elapsedRealtime())
        result
    }

    fun invalidatePhoneAccounts() = synchronized(cacheLock) {
        cachePopulated = false
        cachedAccounts = null
        cachedAtMs = Long.MIN_VALUE
        cachedForUserId = -1
    }

    private fun remember(userId: Int, accounts: Map<Int, PhoneAccountHandle>?, atMs: Long) {
        cachedForUserId = userId
        cachedAccounts = accounts
        cachedAtMs = atMs
        cachePopulated = true
    }
}

/** Pure, bounded parser for the module/broker key=value wire protocol. */
internal object MagiskRuntimeProtocol {
    private const val PROTOCOL_VERSION = 1
    private const val BROKER_VERSION = 1
    private const val BROKER_UID = 1000
    private const val SOURCE = "magisk-system-telephony"
    private const val MAX_OUTPUT_CHARS = 32 * 1024
    private const val MAX_ROWS = 32
    private const val MAX_USER_ID = 21474
    private const val MAX_COMPONENT_BYTES = 256
    private const val MAX_ID_BYTES = 256

    internal data class AccountRecord(
        val subscriptionId: Int,
        val componentName: String,
        val id: String,
        val userId: Int
    )

    /** Null means unavailable, ambiguous, or malformed; no partial result escapes. */
    fun parse(
        output: String,
        expectedUserId: Int,
        expectedAppVersion: Int,
        requestedSubscriptionId: Int? = null
    ): List<AccountRecord>? {
        if (output.isEmpty() || output.length > MAX_OUTPUT_CHARS || '\r' in output) return null
        if (expectedUserId !in 0..MAX_USER_ID || expectedAppVersion <= 0) return null
        val lines = output.split('\n')
        val normalizedLines = if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
        if (normalizedLines.isEmpty() || normalizedLines.any(String::isEmpty)) return null

        val fields = LinkedHashMap<String, String>()
        for (line in normalizedLines) {
            val separator = line.indexOf('=')
            if (separator <= 0) return null
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            if (value.isEmpty() && !key.endsWith("_b64")) return null
            if (!key.matches(Regex("[a-z0-9._]+")) || fields.put(key, value) != null) return null
        }

        val status = fields["status"] ?: return null
        val count = decimal(fields["count"], MAX_ROWS) ?: return null
        val expectedKeys = buildList {
            addAll(
                listOf(
                    "protocol", "broker_version", "app_version", "broker_uid", "source",
                    "status", "user_id", "count"
                )
            )
            repeat(count) { index ->
                add("account.$index.sub_id")
                add("account.$index.component_b64")
                add("account.$index.id_b64")
                add("account.$index.user_id")
            }
            if (status == "unavailable" || status == "error") add("error")
        }
        if (fields.keys.toList() != expectedKeys) return null
        if (decimal(fields["protocol"], Int.MAX_VALUE) != PROTOCOL_VERSION ||
            decimal(fields["broker_version"], Int.MAX_VALUE) != BROKER_VERSION ||
            decimal(fields["app_version"], Int.MAX_VALUE) != expectedAppVersion ||
            decimal(fields["broker_uid"], Int.MAX_VALUE) != BROKER_UID ||
            fields["source"] != SOURCE ||
            decimal(fields["user_id"], MAX_USER_ID) != expectedUserId) return null

        if (status == "unavailable" || status == "error") {
            if (count != 0 || fields["error"] !in ERROR_CODES) return null
            return null
        }
        if (status != "ok" || "error" in fields) return null

        val accounts = ArrayList<AccountRecord>(count)
        val seenSubscriptionIds = HashSet<Int>()
        val seenHandles = HashSet<Triple<String, String, Int>>()
        repeat(count) { index ->
            val subId = decimal(fields["account.$index.sub_id"], Int.MAX_VALUE) ?: return null
            val userId = decimal(fields["account.$index.user_id"], MAX_USER_ID) ?: return null
            if (subId < 0 || userId != expectedUserId) return null
            val component = decode(fields["account.$index.component_b64"], MAX_COMPONENT_BYTES) ?: return null
            val id = decode(fields["account.$index.id_b64"], MAX_ID_BYTES) ?: return null
            if (component.isEmpty() || component.any(Char::isISOControl) || id.any(Char::isISOControl)) return null
            val parsedComponent = ComponentName.unflattenFromString(component) ?: return null
            if (parsedComponent.flattenToString() != component) return null
            if (!seenSubscriptionIds.add(subId) || !seenHandles.add(Triple(component, id, userId))) return null
            accounts += AccountRecord(subId, component, id, userId)
        }

        if (requestedSubscriptionId != null &&
            (accounts.size != 1 || accounts.single().subscriptionId != requestedSubscriptionId)) return null
        return accounts
    }

    private val ERROR_CODES = setOf(
        "permission", "capability", "service", "api", "context", "subscription", "telecom",
        "telephony", "user", "limit", "uid", "arguments"
    )

    private fun decimal(value: String?, maximum: Int): Int? {
        if (value.isNullOrEmpty() || value.length > 10 || value.any { it !in '0'..'9' }) return null
        val parsed = value.toIntOrNull() ?: return null
        return parsed.takeIf { it in 0..maximum }
    }

    private fun decode(encoded: String?, maxBytes: Int): String? {
        if (encoded == null || encoded.length > maxBytes * 2) return null
        val bytes = try {
            JavaBase64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size > maxBytes ||
            JavaBase64.getUrlEncoder().withoutPadding().encodeToString(bytes) != encoded) return null
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            null
        }
    }
}
