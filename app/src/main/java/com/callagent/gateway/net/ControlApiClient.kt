package com.callagent.gateway.net

import android.content.Context
import com.callagent.gateway.BuildConfig
import com.callagent.gateway.data.CredentialStore
import com.callagent.gateway.data.GatewayDatabase
import com.callagent.gateway.data.SessionSource
import com.callagent.gateway.sim.RadioSnapshot
import com.callagent.gateway.sim.RadioTelemetry
import com.callagent.gateway.sim.SimRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

data class PairResult(val gatewayId: String, val role: String)
data class ServerMapping(val simId: String, val slotIndex: Int, val state: String, val mappingRevision: Long)
data class MappingProposal(val operationId: String, val mappingRevision: Long, val mappings: List<ServerMapping>)
data class ServerCommand(
    val commandId: String, val messageId: String, val simId: String, val mappingRevision: Long,
    val to: String, val text: String, val expiresAt: Long, val expiresAtWire: String,
    val payloadSha256: String, val state: String, val gatewayId: String
)
data class HeartbeatSim(
    val simId: String,
    val mappingRevision: Long,
    val serviceState: String,
    val identityVerified: Boolean,
    /**
     * Sent only when the server advertised [RadioTelemetry.EXTENSION]; its
     * country_iso only when it also advertised [RadioTelemetry.COUNTRY_EXTENSION].
     */
    val radio: RadioSnapshot? = null
)
data class HeartbeatResult(
    val mappingRevision: Long,
    val invalidatedSimIds: List<String>,
    val extensions: Set<String> = emptySet()
)
data class ServerSimSnapshot(val mappingRevision: Long, val simIds: Set<String>)

class ControlApiException(
    val code: String,
    message: String,
    val retryable: Boolean = false,
    val httpStatus: Int = 0
) : Exception(message)

/** Client for the frozen v1 control/data plane over HTTPS (recommended) or plain
 * HTTP for a self-hosted node on a trusted LAN/tailnet. Calls are blocking;
 * callers run them on their IO executor. It never logs request or response data. */
class ControlApiClient(
    context: Context,
    baseUrl: String,
    /** Session store for this server; the primary pairing unless a mesh node vault is given. */
    private val sessions: SessionSource = CredentialStore,
    private val ioTimeoutMillis: Int? = null
) {
    private val app = context.applicationContext
    private val base = normalizeBase(baseUrl)

    fun pair(pairingCode: String, deviceName: String): PairResult {
        val response = request("POST", "/v1/pairings/claim", claimBody(pairingCode, deviceName), authenticated = false)
        val role = response.optString("role")
        if (role != "gateway") throw ControlApiException("PAIRING_ROLE_MISMATCH", "该配对码不是网关角色")
        val id = response.optString("device_id")
        if (id.isBlank()) throw ControlApiException("INVALID_RESPONSE", "服务器未返回设备 ID")
        val priorSession = CredentialStore.load(app)
        val identityChanged = priorSession?.gatewayId != id || priorSession?.controlBaseUrl != base
        CredentialStore.save(app, CredentialStore.Session(
            id, base, response.getString("access_token"), response.getString("access_expires_at"),
            response.getString("refresh_token"), response.getString("refresh_expires_at")
        ))
        val changedOwner = CredentialStore.withCurrentIdentity(app,id,base) {
            GatewayDatabase.get(app).adoptGatewayId(id)
        } ?: throw ControlApiException("SESSION_CHANGED","配对身份已变化")
        if (changedOwner || identityChanged) SimRegistry.resetForGatewayChange(app,id,base)
        rememberExtensions(id)
        return PairResult(id, role)
    }

    /** A join code pairs either phone, so the claim names this one's role. */
    private fun claimBody(pairingCode: String, deviceName: String) = JSONObject()
        .put("pairing_code", pairingCode).put("device_name", clampDeviceName(deviceName)).put("role", "gateway")

    /**
     * The first heartbeat already carries what the server supports, notably
     * the install ID: the main phone checks it before it accepts this server
     * as a node, so it must not wait for a later heartbeat round.
     */
    fun rememberExtensions(gatewayId: String, extensions: Set<String>? = null) {
        val known = extensions ?: runCatching { capabilities() }.getOrNull() ?: return
        ControlExtensions.record(app, gatewayId, base, known)
    }

    /**
     * Claim a gateway pairing code on an additional mesh node and return its
     * session without touching the primary pairing; the caller stores it in
     * that node's own vault.
     */
    fun pairMeshNode(pairingCode: String, deviceName: String): CredentialStore.Session {
        val response = request("POST", "/v1/pairings/claim", claimBody(pairingCode, deviceName), authenticated = false)
        if (response.optString("role") != "gateway") throw ControlApiException("PAIRING_ROLE_MISMATCH", "该配对码不是网关角色")
        val id = response.optString("device_id").lowercase()
        if (id.isBlank()) throw ControlApiException("INVALID_RESPONSE", "服务器未返回设备 ID")
        return CredentialStore.Session(
            id, base, response.getString("access_token"), response.getString("access_expires_at"),
            response.getString("refresh_token"), response.getString("refresh_expires_at")
        )
    }

    /** Public extension list; also the cheapest reachability probe for a node. */
    fun capabilities(): Set<String> {
        val response = request("GET", "/v1/capabilities", authenticated = false)
        val array = response.optJSONArray("extensions") ?: JSONArray()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
    }

    fun rtcControlAvailable(): Boolean = request("GET", "/v1/capabilities", authenticated = false).optBoolean("rtc_call_control", false)

    fun revoke() {
        synchronized(refreshLock) {
            val session = sessionOrThrow()
            request("POST", "/v1/auth/revoke", JSONObject().put("refresh_token", session.refreshToken),
                authenticated = false, allowRefresh = false)
            sessions.clearIfCurrent(app, session)
        }
    }

    fun proposeSimBindings(operationId: String, mappings: JSONArray): MappingProposal {
        val gatewayId = gatewayId()
        val response = request("POST", "/v1/gateways/$gatewayId/sim-bindings", JSONObject()
            .put("operation_id", operationId).put("phase", "propose").put("mappings", mappings))
        if (response.optString("phase") != "proposed") throw ControlApiException("INVALID_RESPONSE", "服务器未返回 SIM 待确认列表")
        return MappingProposal(operationId, response.getLong("mapping_revision"), parseMappings(response.optJSONArray("mappings")))
    }

    fun confirmSimBindings(operationId: String, confirmations: JSONArray): List<ServerMapping> {
        val gatewayId = gatewayId()
        val response = request("POST", "/v1/gateways/$gatewayId/sim-bindings", JSONObject()
            .put("operation_id", operationId).put("phase", "confirm").put("confirmations", confirmations))
        return parseMappings(response.optJSONArray("mappings"))
    }

    fun heartbeatBody(sequence: Long, root: Boolean, batteryPercent: Int?,
                      charging: Boolean, sims: List<HeartbeatSim>, installId: String? = null): JSONObject {
        val simArray = JSONArray()
        sims.forEach { sim ->
            val item = JSONObject().put("sim_id", sim.simId)
                .put("mapping_revision", sim.mappingRevision).put("service_state", sim.serviceState)
                .put("identity_verified", sim.identityVerified)
            sim.radio?.let { item.put("radio", it.toJson()) }
            simArray.put(item)
        }
        val body = JSONObject().put("sequence", sequence).put("protocol_version", 1)
            .put("app_version", BuildConfig.VERSION_NAME).put("root", root)
            .put("charging", charging)
            .put("sim_states", simArray)
        if (batteryPercent != null) body.put("battery_percent", batteryPercent)
        if (installId != null) body.put("install_id", installId)
        return body
    }

    fun heartbeat(body: JSONObject): HeartbeatResult {
        val session = sessionOrThrow()
        val response = request("POST", "/v1/gateways/${session.gatewayId}/heartbeat", body)
        val revision = response.optLong("mapping_revision", -1L)
        if (revision < 1) throw ControlApiException("INVALID_HEARTBEAT_RESPONSE", "服务器未返回有效 SIM revision")
        val invalidated = response.optJSONArray("invalidated_sim_ids") ?: JSONArray()
        val extensions = response.optJSONArray("extensions") ?: JSONArray()
        return HeartbeatResult(revision, (0 until invalidated.length()).map { invalidated.getString(it) },
            (0 until extensions.length()).mapNotNull { extensions.optString(it).takeIf(String::isNotBlank) }.toSet())
    }

    fun serverSimSnapshot(): ServerSimSnapshot {
        val session = sessionOrThrow()
        val response = request("GET", "/v1/gateways/${session.gatewayId}/sims")
        val revision = response.optLong("mapping_revision", -1L)
        if (revision < 0) throw ControlApiException("INVALID_SIM_SNAPSHOT", "服务器未返回 SIM revision")
        val sims = response.optJSONArray("sims") ?: JSONArray()
        val ids = (0 until sims.length()).map { sims.getJSONObject(it).getString("sim_id") }.toSet()
        return ServerSimSnapshot(revision, ids)
    }

    fun listCommands(): List<ServerCommand> {
        val session = sessionOrThrow()
        val result = mutableListOf<ServerCommand>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val suffix = cursor?.let { "?cursor=${URLEncoder.encode(it, "UTF-8")}&limit=100" } ?: "?limit=100"
            val response = request("GET", "/v1/gateways/${session.gatewayId}/commands$suffix")
            val commands = response.optJSONArray("commands") ?: JSONArray()
            for (i in 0 until commands.length()) {
                val c = commands.getJSONObject(i)
                if (c.getString("gateway_id") != session.gatewayId) {
                    throw ControlApiException("COMMAND_OWNER_MISMATCH", "服务器返回了其他网关的短信命令")
                }
                val expiresWire = c.getString("expires_at")
                val command = ServerCommand(c.getString("command_id"), c.getString("message_id"), c.getString("sim_id"),
                    c.getLong("mapping_revision"), c.getString("to"), c.getString("text"),
                    Instant.parse(expiresWire).toEpochMilli(), expiresWire,
                    c.optString("payload_sha256"), c.optString("state"), session.gatewayId)
                val localHash = GatewayDatabase.get(app).serverCommandHash(command.commandId, command.messageId,
                    session.gatewayId, command.simId, command.mappingRevision, command.to, command.text, expiresWire)
                if (command.payloadSha256.isBlank() || command.payloadSha256 != localHash) {
                    throw ControlApiException("COMMAND_HASH_MISMATCH", "短信命令校验摘要不匹配")
                }
                result += command
            }
            cursor = response.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
            if (cursor != null && !seenCursors.add(cursor!!)) {
                throw ControlApiException("COMMAND_CURSOR_LOOP", "服务器命令分页游标重复")
            }
        }
        while (cursor != null)
        return result
    }

    fun claimCommand(command: ServerCommand): ServerCommand {
        val gatewayId = gatewayId()
        if (command.gatewayId != gatewayId) {
            throw ControlApiException("COMMAND_OWNER_MISMATCH", "短信命令所属网关与当前配对身份不一致")
        }
        val response = request("POST", "/v1/gateways/$gatewayId/commands/${command.commandId}/claim",
            JSONObject().put("payload_sha256", command.payloadSha256), idempotencyKey = command.commandId)
        val state = response.optString("state")
        val valid = response.optString("command_id") == command.commandId &&
            response.optString("message_id") == command.messageId &&
            response.optString("gateway_id") == command.gatewayId &&
            response.optString("sim_id") == command.simId &&
            response.optLong("mapping_revision", Long.MIN_VALUE) == command.mappingRevision &&
            response.optString("to") == command.to && response.optString("text") == command.text &&
            response.optString("expires_at") == command.expiresAtWire &&
            response.optString("payload_sha256").matches(LOWER_SHA256) &&
            response.optString("payload_sha256") == command.payloadSha256 &&
            state in setOf("accepted_by_gateway", "dispatching")
        if (!valid) throw ControlApiException("INVALID_CLAIM_RESPONSE", "服务器认领响应与原短信命令不一致")
        return command.copy(state = state)
    }

    /** Send one atomic batch and return only event IDs the server durably ACKed. */
    fun uploadEvents(events: List<GatewayDatabase.Event>): Set<String> {
        if (events.isEmpty()) return emptySet()
        val session = sessionOrThrow()
        if (events.any { it.ownerGatewayId != session.gatewayId }) {
            throw ControlApiException("EVENT_OWNER_MISMATCH", "事件所属网关与当前配对身份不一致")
        }
        val array = JSONArray()
        val gatewayEvents = events.take(50).map { e ->
            val event = JSONObject().put("protocol_version", 1).put("event_id", e.eventId)
                .put("gateway_id", session.gatewayId).put("sequence", e.sequence)
                .put("occurred_at", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(e.occurredAt)))
                .put("type", e.type).put("payload", JSONObject(e.payload))
            if (e.simId == null) event.put("sim_id", JSONObject.NULL) else event.put("sim_id", e.simId)
            if (e.mappingRevision == null) event.put("mapping_revision", JSONObject.NULL)
            else event.put("mapping_revision", e.mappingRevision)
            if (e.type == "sms.received" && e.simId == null) event.put("sim_resolution", "unknown")
            event
        }
        var selected = emptyList<JSONObject>()
        for (event in gatewayEvents) {
            val candidate = selected + event
            val serialized = JSONObject().put("events", JSONArray().apply { candidate.forEach(::put) }).toString()
            if (serialized.toByteArray(Charsets.UTF_8).size > MAX_BATCH_BYTES) {
                if (selected.isEmpty()) throw ControlApiException("EVENT_TOO_LARGE", "单条网关事件超过服务器大小限制")
                break
            }
            selected = candidate
        }
        val body = JSONObject().put("events", JSONArray().apply { selected.forEach(::put) })
        val response = request("POST", "/v1/gateways/${session.gatewayId}/events:batch", body)
        val acks = response.optJSONArray("acks") ?: JSONArray()
        val ids = mutableSetOf<String>()
        for (i in 0 until acks.length()) ids += acks.getJSONObject(i).getString("event_id")
        val requested = selected.map { it.getString("event_id") }.toSet()
        if (ids != requested) throw ControlApiException("INCOMPLETE_DURABLE_ACK", "服务器未确认全部事件", retryable = true)
        return ids
    }

    private fun gatewayId(): String = sessionOrThrow().gatewayId

    internal fun rtcRequest(method: String, path: String, body: JSONObject?): JSONObject {
        require(path.matches(Regex("/(devices/self/rtc-capability|rtc/control/(capability|calls(/[a-zA-Z0-9_-]+(/operations)?)?|incoming)|rtc/legs/[a-zA-Z0-9_-]+|calls/[a-zA-Z0-9_-]+/media(/active)?)")))
        val expected = sessionOrThrow()
        return try { request(method, "/v1$path", body, expectedGatewayId = expected.gatewayId) }
        catch (failure: ControlApiException) { throw com.callagent.rtcmedia.MediaApiException(failure.httpStatus, failure.code) }
        catch (_: java.io.IOException) { throw com.callagent.rtcmedia.MediaApiException(503, "NETWORK_UNAVAILABLE") }
    }

    private fun sessionOrThrow(): CredentialStore.Session {
        val session = sessions.load(app)
            ?: throw ControlApiException("PAIRING_REQUIRED", "设备尚未完成服务端配对")
        if (session.controlBaseUrl != base) {
            throw ControlApiException("CONTROL_ORIGIN_MISMATCH", "控制服务器地址与加密凭据绑定地址不匹配")
        }
        return session
    }

    private fun request(method: String, path: String, body: JSONObject? = null,
                        authenticated: Boolean = true, idempotencyKey: String = UUID.randomUUID().toString(),
                        allowRefresh: Boolean = true, expectedGatewayId: String? = null): JSONObject {
        if (authenticated && expectedGatewayId != null && sessionOrThrow().gatewayId != expectedGatewayId) {
            throw ControlApiException("SESSION_CHANGED","Control identity changed before request")
        }
        val session = if (authenticated) accessSession() else null
        if (authenticated && expectedGatewayId != null && session?.gatewayId != expectedGatewayId) {
            throw ControlApiException("SESSION_CHANGED","Control identity changed before request")
        }
        val url = URL(base + path)
        // HTTPS keeps the platform's default certificate and hostname checks.
        val conn = url.openConnection() as? HttpURLConnection
            ?: throw ControlApiException("INVALID_CONTROL_URL", "控制端点必须使用 http:// 或 https://")
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = method
            conn.connectTimeout = ioTimeoutMillis ?: CONNECT_TIMEOUT_MS
            conn.readTimeout = ioTimeoutMillis ?: READ_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            if (session != null) conn.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Idempotency-Key", idempotencyKey)
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            if (code == 401 && authenticated && allowRefresh) {
                readResponse(conn, success = false)
                val rejectedToken = session?.accessToken
                    ?: throw ControlApiException("SESSION_CHANGED", "Control session changed during request")
                refreshSession(rejectedAccessToken = rejectedToken)
                return request(method, path, body, authenticated, idempotencyKey, allowRefresh = false, expectedGatewayId = expectedGatewayId)
            }
            val response = readResponse(conn, success = code in 200..299)
            if (code !in 200..299) {
                val error = response.optJSONObject("error")
                throw ControlApiException(error?.optString("code")?.ifEmpty { null } ?: "HTTP_$code",
                    error?.optString("message")?.ifEmpty { null } ?: "控制服务器返回 HTTP $code",
                    error?.optBoolean("retryable", false) ?: (code >= 500), code)
            }
            return response
        } catch (e: ControlApiException) {
            throw e
        } catch (_: Exception) {
            throw ControlApiException("NETWORK_ERROR", "控制服务器连接失败", retryable = true)
        } finally { conn.disconnect() }
    }

    private fun accessSession(): CredentialStore.Session {
        val session = sessionOrThrow()
        val expiry = try { Instant.parse(session.accessExpiresAt).toEpochMilli() } catch (_: Exception) { 0L }
        if (expiry > System.currentTimeMillis() + 60_000) return session
        return refreshSession()
    }

    @Synchronized
    private fun refreshSession(rejectedAccessToken: String? = null): CredentialStore.Session {
        synchronized(refreshLock) {
            val old = sessionOrThrow()
            if (rejectedAccessToken != null && old.accessToken != rejectedAccessToken) return old
            val expiry = try { Instant.parse(old.accessExpiresAt).toEpochMilli() } catch (_: Exception) { 0L }
            if (rejectedAccessToken == null && expiry > System.currentTimeMillis() + 60_000) return old
            val pending = sessions.beginRefresh(app, old)
                ?: throw ControlApiException("SESSION_CHANGED", "Control session changed during refresh")
            val retryKey = pending.pendingRefreshKey
                ?: throw ControlApiException("REFRESH_RETRY_KEY_MISSING", "Could not persist refresh retry state", retryable = true)
            val response = try {
                request("POST", "/v1/auth/refresh", JSONObject().put("refresh_token", pending.refreshToken),
                    authenticated = false, idempotencyKey = retryKey, allowRefresh = false)
            } catch (e: ControlApiException) {
                if (e.httpStatus == 401 || e.code == "SESSION_REVOKED") {
                    sessions.clearIfCurrent(app, pending)
                }
                throw e
            }
            val rotated = pending.copy(accessToken = response.getString("access_token"),
                accessExpiresAt = response.getString("access_expires_at"),
                refreshToken = response.getString("refresh_token"),
                refreshExpiresAt = response.getString("refresh_expires_at"), pendingRefreshKey = null)
            if (sessions.saveIfCurrent(app, pending, rotated)) return rotated
            val latest = sessions.load(app)
                ?: throw ControlApiException("SESSION_CHANGED", "Control session changed during refresh")
            if (latest.gatewayId != old.gatewayId || latest.controlBaseUrl != old.controlBaseUrl) {
                throw ControlApiException("SESSION_CHANGED", "Control session changed during refresh")
            }
            return latest
        }
    }

    private fun readResponse(conn: HttpURLConnection, success: Boolean): JSONObject {
        val stream = if (success) conn.inputStream else conn.errorStream
        if (stream == null) return JSONObject()
        val out = ByteArrayOutputStream()
        stream.use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_RESPONSE_BYTES) throw ControlApiException("RESPONSE_TOO_LARGE", "服务器响应过大")
                out.write(buffer, 0, n)
            }
        }
        val text = out.toString("UTF-8")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun parseMappings(array: JSONArray?): List<ServerMapping> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { i ->
            val m = array.getJSONObject(i)
            ServerMapping(m.getString("sim_id"), m.getInt("slot_index"), m.getString("state"), m.getLong("mapping_revision"))
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_RESPONSE_BYTES = 512 * 1024
        private const val MAX_BATCH_BYTES = 256 * 1024
        private val LOWER_SHA256 = Regex("^[a-f0-9]{64}$")
        private val IDEMPOTENCY_KEY = Regex("^[\\x21-\\x7e]{16,128}$")
        private val refreshLock = Any()

        private fun validIdempotencyKey(value: String): Boolean = IDEMPOTENCY_KEY.matches(value)

        private fun normalizeBase(input: String): String {
            val value = input.trim().trimEnd('/')
            val url = try { URL(value) } catch (_: Exception) {
                throw ControlApiException("INVALID_CONTROL_URL", "控制服务器地址无效")
            }
            if (url.protocol !in setOf("https", "http") || url.host.isBlank() || url.userInfo != null ||
                url.query != null || url.ref != null)
                throw ControlApiException("INVALID_CONTROL_URL", "请输入有效的 http:// 或 https:// 控制服务器地址")
            return value
        }
    }
}

/** The server allows 100 characters (code points) for a device name. */
internal fun clampDeviceName(name: String): String {
    val trimmed = name.trim()
    return if (trimmed.codePointCount(0, trimmed.length) <= 100) trimmed
    else trimmed.substring(0, trimmed.offsetByCodePoints(0, 100))
}
