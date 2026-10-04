package com.callagent.gateway.sip

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * How SIP messages get on and off the wire.
 *
 * The two transports differ in more than encryption.  UDP is connectionless
 * and message-oriented: one datagram is exactly one SIP message, and the peer
 * address arrives with it.  TLS is a single long-lived byte stream to one
 * server, where message boundaries exist only because the headers say how long
 * the body is.  Everything above this interface is written once against the
 * datagram model, so the stream transport is responsible for handing back
 * whole messages and nothing else.
 */
interface SipTransport {

    /** Local port in Via and Contact.  Fixed for UDP, ephemeral for TLS. */
    val localPort: Int

    /** Token for the Via header: `SIP/2.0/<this>`. */
    val viaTransport: String

    /** True once usable.  A dropped TLS connection turns this false. */
    val isOpen: Boolean

    /** Bind (UDP) or connect and handshake (TLS).  Throws on failure. */
    fun open()

    /**
     * Send one message.  [host] and [port] are honoured by UDP; TLS has a
     * single peer and writes to its connection regardless.
     */
    fun send(data: String, host: String, port: Int)

    /**
     * One complete SIP message and the peer it came from, or null when the
     * read timed out with nothing to report.  Blocks up to the socket timeout.
     */
    fun receive(): Pair<String, Pair<String, Int>>?

    fun close()
}

/** The original datagram transport: one packet in, one message out. */
class UdpSipTransport(
    override val localPort: Int,
    private val soTimeoutMs: Int = 5000
) : SipTransport {

    override val viaTransport = "UDP"

    private var socket: DatagramSocket? = null
    private val buf = ByteArray(4096)

    override val isOpen: Boolean get() = socket?.isClosed == false

    override fun open() {
        socket?.close()
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(localPort))
        s.soTimeout = soTimeoutMs
        s.receiveBufferSize = 65535
        s.sendBufferSize = 65535
        socket = s
    }

    override fun send(data: String, host: String, port: Int) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), port)
        socket?.send(packet)
    }

    /** Pre-resolved server address, so sending never blocks on DNS. */
    fun send(data: String, addr: InetAddress, port: Int) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        socket?.send(DatagramPacket(bytes, bytes.size, addr, port))
    }

    override fun receive(): Pair<String, Pair<String, Int>>? {
        val s = socket ?: return null
        return try {
            val packet = DatagramPacket(buf, buf.size)
            s.receive(packet)
            val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
            data to (packet.address.hostAddress.orEmpty() to packet.port)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override fun close() {
        socket?.close()
        socket = null
    }
}

/**
 * SIP over TLS (RFC 3261 §18). This protects signalling and SDES keys; the
 * separate RTP socket is protected by SRTP negotiated by SipClient.
 *
 * One connection carries everything in both directions, including requests the
 * server originates: an inbound INVITE arrives on the same socket this opened,
 * which is why nothing here listens for connections and why Contact does not
 * have to be reachable from outside.  That also makes the NAT problem go away
 * -- a TCP mapping is held open by the connection itself, so the 25s datagram
 * heartbeat is unnecessary here.
 *
 * Certificates are checked against the system trust store, and the hostname
 * against the certificate, before the handshake is allowed to finish.  There
 * is deliberately no option to skip either: a SIP registration carries the
 * account password, and an unverified TLS connection would hand it to whoever
 * answered.
 */
class TlsSipTransport(
    private val host: String,
    private val port: Int,
    private val soTimeoutMs: Int = 5000,
    private val socketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val log: (String) -> Unit = {}
) : SipTransport {

    override val viaTransport = "TLS"

    private var socket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** Byte framing preserves UTF-8 bodies split at arbitrary read boundaries. */
    private val framer = SipStreamFramer()
    private val completeMessages = ArrayDeque<ByteArray>()

    override val isOpen: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    override val localPort: Int get() = socket?.localPort ?: 0

    /** Peer, reported with every message so the caller's API is unchanged. */
    private val peer: Pair<String, Int> get() = host to port

    override fun open() {
        close()
        val s = socketFactory
            .createSocket() as SSLSocket
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.soTimeout = soTimeoutMs
        s.tcpNoDelay = true
        // A bare SSLSocket validates the chain but NOT the hostname -- that
        // check lives in HttpsURLConnection, not in the socket, so without
        // this any certificate signed by any trusted CA would be accepted for
        // any name.  Asking for "HTTPS" endpoint identification puts the
        // SAN/CN check back where it belongs, before the handshake completes.
        s.sslParameters = s.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
            // SNI, set explicitly.  A socket created without a hostname and
            // then connected to an InetSocketAddress may send no server_name
            // at all, and a multi-domain host answers that with whatever
            // certificate it considers default -- which is then rejected for
            // the wrong reason.  This server's certificate has callagent.pro
            // as a SAN behind a CN of badrenovo.de, so the name must arrive.
            serverNames = if (host.contains(':') || host.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))) {
                emptyList()
            } else listOf(SNIHostName(host))
        }
        // Without this the handshake is deferred until the first read, so a
        // certificate failure would surface as a mysterious read error on
        // another thread rather than here where it can be reported.
        try {
            s.startHandshake()
        } catch (e: java.net.SocketTimeoutException) {
            // Almost always TLS pointed at a plaintext port: nothing answers
            // the handshake, so it stalls rather than refusing. Say which port
            // it was, because the raw message names neither cause nor cure.
            throw java.io.IOException(
                "TLS handshake timed out to $host:$port — is $port the TLS port? " +
                    "(plaintext SIP is usually 5060, TLS 5061)", e
            )
        }
        socket = s
        input = s.inputStream
        output = s.outputStream
        framer.reset()
        completeMessages.clear()
        log("TLS connected to $host:$port (${s.session.protocol}, ${s.session.cipherSuite})")
    }

    override fun send(data: String, host: String, port: Int) {
        val out = output ?: throw IllegalStateException("TLS transport not open")
        synchronized(this) {
            out.write(data.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    override fun receive(): Pair<String, Pair<String, Int>>? {
        // A single TLS read can complete several messages. Return queued
        // frames before reading again so a later partial message cannot delay
        // one that was already complete.
        completeMessages.pollFirst()?.let { return decodeFrame(it) to peer }

        val ins = input ?: return null
        val buf = ByteArray(4096)
        return try {
            val n = ins.read(buf)
            if (n < 0) {
                // Orderly close by the peer.  Report it as a drop so the
                // caller reconnects rather than spinning on a dead stream.
                throw java.io.EOFException("TLS connection closed by server")
            }
            completeMessages.addAll(framer.append(buf, 0, n))
            completeMessages.pollFirst()?.let { decodeFrame(it) to peer }
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    private fun decodeFrame(frame: ByteArray): String = String(frame, Charsets.UTF_8)

    override fun close() {
        try { socket?.close() } catch (e: Exception) { Log.d(TAG, "TLS close: ${e.message}") }
        socket = null
        input = null
        output = null
        framer.reset()
        completeMessages.clear()
    }

    private companion object {
        const val TAG = "TlsSipTransport"
        const val CONNECT_TIMEOUT_MS = 10_000

    }
}
