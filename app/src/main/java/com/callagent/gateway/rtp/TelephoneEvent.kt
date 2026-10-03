package com.callagent.gateway.rtp

/** One Telecom-facing start/stop edge for an RFC 4733 telephone event. */
data class TelephoneToneEdge(val digit: Char, val active: Boolean)

/** Parses RFC 4733 event payloads and suppresses redundant progress/end packets. */
class TelephoneEventReceiver(
    private val payloadType: Int,
    private val onToneEdge: (TelephoneToneEdge) -> Unit,
    private val idleEndTimeoutMs: Long = 1_500L
) {
    private data class EventKey(val ssrc: Long, val timestamp: Long, val event: Int)
    private data class ActiveEvent(val key: EventKey, val digit: Char, val duration: Int, val lastSeenMs: Long)

    private var active: ActiveEvent? = null
    private var mostRecentlyEnded: Pair<EventKey, Int>? = null

    init {
        require(payloadType in 0..127)
        require(idleEndTimeoutMs > 0)
    }

    fun accept(packet: RtpPacket, nowMs: Long = System.currentTimeMillis()): Boolean {
        expire(nowMs)
        if (packet.payloadType != payloadType || packet.payload.size < EVENT_PAYLOAD_BYTES) return false

        val eventByte = packet.payload[0].toInt() and 0xFF
        if ((eventByte and RESERVED_BIT) != 0) return false
        val event = eventByte and EVENT_MASK
        val digit = digitFor(event) ?: return false
        val isEnd = (eventByte and END_BIT) != 0
        val duration = ((packet.payload[2].toInt() and 0xFF) shl 8) or
            (packet.payload[3].toInt() and 0xFF)
        val key = EventKey(packet.ssrc, packet.timestamp, event)
        val current = active

        if (current != null && current.key == key) {
            // Reordered or repeated packets never make the event duration go back.
            if (duration < current.duration) return true
            active = current.copy(duration = duration, lastSeenMs = nowMs)
            if (isEnd) finish(key, duration)
            return true
        }

        if (current != null) finish(current.key, current.duration)
        val ended = mostRecentlyEnded
        if (ended?.first == key && duration <= ended.second) return true

        onToneEdge(TelephoneToneEdge(digit, active = true))
        if (isEnd) {
            // RFC 4733 receivers may first observe a redundant end packet after
            // loss. Emit both edges so the Telecom bridge cannot leave a tone stuck.
            onToneEdge(TelephoneToneEdge(digit, active = false))
            mostRecentlyEnded = key to duration
            active = null
        } else {
            active = ActiveEvent(key, digit, duration, nowMs)
            mostRecentlyEnded = null
        }
        return true
    }

    fun expire(nowMs: Long = System.currentTimeMillis()) {
        val event = active ?: return
        if (nowMs - event.lastSeenMs >= idleEndTimeoutMs) finish(event.key, event.duration)
    }

    fun reset() {
        active?.let { finish(it.key, it.duration) }
        mostRecentlyEnded = null
    }

    private fun finish(key: EventKey, duration: Int) {
        val event = active ?: return
        if (event.key != key) return
        onToneEdge(TelephoneToneEdge(event.digit, active = false))
        mostRecentlyEnded = key to maxOf(duration, event.duration)
        active = null
    }

    private fun digitFor(event: Int): Char? = when (event) {
        in 0..9 -> ('0'.code + event).toChar()
        10 -> '*'
        11 -> '#'
        in 12..15 -> ('A'.code + event - 12).toChar()
        else -> null // Event 16 is Flash; Telecom's DTMF Call API has no equivalent.
    }

    private companion object {
        const val EVENT_PAYLOAD_BYTES = 4
        const val END_BIT = 0x80
        const val RESERVED_BIT = 0x40
        const val EVENT_MASK = 0x3F
    }
}
