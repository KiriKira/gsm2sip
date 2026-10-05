package com.callagent.gateway.net

import com.callagent.gateway.data.CredentialStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Credential identity used to fence WebSocket callbacks across pairing/token changes. */
internal data class WakeSessionIdentity(
    val gatewayId: String,
    val controlBaseUrl: String,
    val tokenGeneration: String
) {
    companion object {
        fun from(session: CredentialStore.Session): WakeSessionIdentity = WakeSessionIdentity(
            gatewayId = session.gatewayId,
            controlBaseUrl = session.controlBaseUrl.trimEnd('/'),
            // The digest stays in memory and is never logged or sent. Raw bearer tokens are not
            // retained as connection metadata; the WebSocket request carries it only in a header.
            tokenGeneration = MessageDigest.getInstance("SHA-256")
                .digest(session.accessToken.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        )
    }
}

internal object WakeConnectionPolicy {
    fun callbackIsCurrent(
        callbackGeneration: Long,
        currentGeneration: Long,
        callbackSession: WakeSessionIdentity,
        currentSession: WakeSessionIdentity?
    ): Boolean = callbackGeneration == currentGeneration && callbackSession == currentSession

    fun requiresHttpsRefresh(httpStatus: Int?, closeCode: Int?): Boolean =
        httpStatus == 401 || closeCode == 1008

    fun webSocketUrl(base: String): String {
        val parsed = base.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid control endpoint")
        require(parsed.scheme == "https" && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.query == null && parsed.fragment == null) { "Wake endpoint requires an HTTPS origin" }
        val currentPath = parsed.encodedPath.trimEnd('/')
        val wakePath = when {
            currentPath.isEmpty() -> "/v1/ws"
            currentPath == "/v1" || currentPath.endsWith("/v1") -> "$currentPath/ws"
            else -> "$currentPath/v1/ws"
        }
        return parsed.newBuilder()
            .encodedPath(wakePath)
            .build()
            .toString()
            .replaceFirst("https://", "wss://")
    }
}

/** The single authenticated server wake socket. It never sends application data frames. */
internal class WakeConnection(
    session: CredentialStore.Session,
    private val isCurrent: () -> Boolean,
    private val listener: Listener
) {
    val identity: WakeSessionIdentity = WakeSessionIdentity.from(session)

    private val socket: WebSocket

    interface Listener {
        fun onOpen()
        fun onSyncRequired()
        fun onClosed(code: Int)
        fun onFailure(httpStatus: Int?)
        fun onInvalidFrame()
    }

    init {
        val url = WakeConnectionPolicy.webSocketUrl(session.controlBaseUrl)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Accept", "application/json")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent()) {
                    webSocket.cancel()
                    return
                }
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent()) return
                val valid = try {
                    if (text.toByteArray(Charsets.UTF_8).size > MAX_WAKE_FRAME_BYTES) {
                        false
                    } else {
                        val message = JSONObject(text)
                        message.length() == 2 && (message.opt("protocol_version") as? Number)?.toInt() == 1 &&
                            message.optString("type") == "sync_required"
                    }
                } catch (_: Exception) {
                    false
                }
                if (valid) listener.onSyncRequired() else listener.onInvalidFrame()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isCurrent()) return
                listener.onInvalidFrame()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                if (isCurrent()) listener.onClosed(code)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (isCurrent()) listener.onClosed(code)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (isCurrent()) listener.onFailure(response?.code)
            }
        })
    }

    fun close() {
        socket.cancel()
    }

    companion object {
        private val client: OkHttpClient by lazy {
            // No custom trust manager or hostname verifier: OkHttp uses the platform's
            // default CA validation and HTTPS hostname checks.
            OkHttpClient.Builder()
                .pingInterval(25, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
        }

        private const val MAX_WAKE_FRAME_BYTES = 4 * 1024

    }
}
