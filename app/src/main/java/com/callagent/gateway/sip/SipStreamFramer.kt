package com.callagent.gateway.sip

import java.util.ArrayDeque

/** Byte-oriented RFC 3261 framing for SIP over TCP/TLS. */
class SipStreamFramer(
    private val maxHeaderBytes: Int = DEFAULT_MAX_HEADER_BYTES,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES
) {
    private val maxBufferedBytes = maxHeaderBytes + 4 + maxBodyBytes + 2
    private var buffer = ByteArray(minOf(INITIAL_CAPACITY, maxBufferedBytes))
    private var size = 0

    init {
        require(maxHeaderBytes > 0)
        require(maxBodyBytes >= 0)
    }

    /** Add stream bytes and return every complete SIP frame they finish. */
    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<ByteArray> {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        val frames = ArrayList<ByteArray>()
        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            drainFrames(frames)
            val room = maxBufferedBytes - size
            if (room <= 0) {
                throw SipFrameException("SIP stream frame exceeds configured limit")
            }
            val count = minOf(room, end - cursor)
            ensureCapacity(size + count)
            System.arraycopy(bytes, cursor, buffer, size, count)
            size += count
            cursor += count
            drainFrames(frames)
        }
        return frames
    }

    fun reset() {
        size = 0
    }

    private fun drainFrames(out: MutableList<ByteArray>) {
        while (true) {
            skipKeepalivePairs()
            if (size == 0) return

            val headerEnd = findHeaderEnd()
            if (headerEnd < 0) {
                if (size > maxHeaderBytes) {
                    throw SipFrameException("SIP header exceeds $maxHeaderBytes bytes")
                }
                return
            }
            if (headerEnd > maxHeaderBytes) {
                throw SipFrameException("SIP header exceeds $maxHeaderBytes bytes")
            }

            val bodyLength = parseContentLength(headerEnd)
            val frameLength = headerEnd.toLong() + HEADER_TERMINATOR_BYTES + bodyLength
            if (frameLength > maxBufferedBytes) {
                throw SipFrameException("SIP frame exceeds configured limit")
            }
            if (size < frameLength) return

            out.add(buffer.copyOfRange(0, frameLength.toInt()))
            consume(frameLength.toInt())
        }
    }

    /** A SIP keepalive is an exact CRLF pair. A lone LF is malformed. */
    private fun skipKeepalivePairs() {
        while (size > 0) {
            if (buffer[0] == LF) throw SipFrameException("Bare LF before SIP message")
            if (buffer[0] != CR) return
            if (size == 1) return
            if (buffer[1] != LF) throw SipFrameException("Invalid CR before SIP message")
            consume(2)
        }
    }

    private fun parseContentLength(headerEnd: Int): Int {
        val headers = String(buffer, 0, headerEnd, Charsets.ISO_8859_1)
        var contentLength: Long? = null
        for (line in headers.split("\r\n").drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            if (!name.equals("Content-Length", ignoreCase = true) &&
                !name.equals("l", ignoreCase = true)
            ) continue

            val value = line.substring(colon + 1).trim()
            if (value.isEmpty() || value.any { it !in '0'..'9' }) {
                throw SipFrameException("Invalid SIP Content-Length")
            }
            val parsed = value.toLongOrNull()
                ?: throw SipFrameException("Invalid SIP Content-Length")
            if (parsed > maxBodyBytes) {
                throw SipFrameException("SIP body exceeds $maxBodyBytes bytes")
            }
            if (contentLength != null && contentLength != parsed) {
                throw SipFrameException("Conflicting SIP Content-Length headers")
            }
            contentLength = parsed
        }
        return (contentLength
            ?: throw SipFrameException("SIP stream message is missing Content-Length")).toInt()
    }

    private fun findHeaderEnd(): Int {
        for (i in 0 until size - 3) {
            if (buffer[i] == CR && buffer[i + 1] == LF &&
                buffer[i + 2] == CR && buffer[i + 3] == LF
            ) return i
        }
        return -1
    }

    private fun consume(count: Int) {
        if (count >= size) {
            size = 0
            return
        }
        System.arraycopy(buffer, count, buffer, 0, size - count)
        size -= count
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var capacity = buffer.size
        while (capacity < required) {
            capacity = (capacity * 2).coerceAtMost(maxBufferedBytes)
            if (capacity < required && capacity == maxBufferedBytes) {
                throw SipFrameException("SIP stream frame exceeds configured limit")
            }
        }
        buffer = buffer.copyOf(capacity)
    }

    companion object {
        const val DEFAULT_MAX_HEADER_BYTES = 16 * 1024
        const val DEFAULT_MAX_BODY_BYTES = 1024 * 1024
        private const val INITIAL_CAPACITY = 4096
        private const val HEADER_TERMINATOR_BYTES = 4
        private const val CR: Byte = 13
        private const val LF: Byte = 10
    }
}

class SipFrameException(message: String) : IllegalArgumentException(message)
