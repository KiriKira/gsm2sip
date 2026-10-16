package com.callagent.rtc

/** Version-1 leg identity. The authenticated server snapshot supplies this scope.
 * This component neither selects a node nor authorizes cellular execution. */
data class RtcScope(val nodeId: String, val callId: String, val legId: String, val attemptId: String)
data class RtcGeneration(val scope: RtcScope, val generation: Int)
data class RtcIce(val candidate: String, val sdpMid: String, val sdpMLineIndex: Int)

/** A null ICE value represents end-of-candidates, never an empty candidate. */
data class RemoteIceFrame(
    val key: RtcGeneration,
    val sequence: Long,
    val senderId: String,
    val expiresAtMillis: Long,
    val ice: RtcIce?
)

class RtcProtocolException(message: String) : IllegalStateException(message)

/** SDK-independent guard for the remote side of one PeerConnection.
 *
 * Only call descriptionApplied after native setRemoteDescription succeeds.
 * Execute returned frames on the same serial PeerConnection executor as SDP and
 * generation changes, rechecking the current key before each native operation.
 * No method calls native code or opens audio. A violation closes this buffer;
 * callers must terminate the leg, never recover by redialling the cellular call.
 *
 * Kept byte-identical in both Android repositories until a shared package is
 * published. Server docs/rtc-signaling-v1.md defines the matching limits.
 */
class RemoteIceBuffer(
    initialKey: RtcGeneration,
    private val remoteDeviceId: String,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    class DescriptionToken internal constructor(
        internal val owner: Any,
        internal val key: RtcGeneration,
        internal val sequence: Long,
        internal val expiresAtMillis: Long
    )

    private val identity = Any()
    private var key = initialKey
    private var closed = false
    private var description: DescriptionToken? = null
    private var descriptionReady = false
    private val seen = mutableMapOf<Long, RemoteIceFrame>()
    private val pending = mutableListOf<RemoteIceFrame>()
    private var endSequence: Long? = null
    private var lastNow = Long.MIN_VALUE

    init {
        require(validKey(initialKey) && validId(remoteDeviceId)) { "Invalid RTC leg identity" }
    }

    @Synchronized
    fun currentKey(): RtcGeneration? = key.takeUnless { closed }

    /** Advance only after a committed server snapshot; an old callback is fenced.
     * A closed leg cannot be reopened, including by a later generation. */
    @Synchronized
    fun advance(next: RtcGeneration) {
        requireOpen()
        if (!validKey(next) || next.scope != key.scope || next.generation != key.generation + 1) {
            fail("Invalid RTC generation transition")
        }
        key = next
        clearGeneration()
    }

    /** Called when an offer/answer frame is about to enter native code. */
    @Synchronized
    fun beginDescription(frameKey: RtcGeneration, sequence: Long, expiresAtMillis: Long): DescriptionToken? {
        requireOpen()
        if (frameKey != key) return null
        val now = now()
        if (expiresAtMillis <= now) return null
        if (sequence < 1 || expiresAtMillis - now > SIGNAL_LIFETIME_MS || seen.containsKey(sequence)) {
            fail("Invalid RTC description envelope")
        }
        description?.let {
            if (it.sequence == sequence && it.expiresAtMillis == expiresAtMillis) return null
            fail("A generation cannot replace its remote description")
        }
        return DescriptionToken(identity, key, sequence, expiresAtMillis).also { description = it }
    }

    /** An old generation or a token belonging to another buffer is ignored. */
    @Synchronized
    fun descriptionApplied(token: DescriptionToken): List<RemoteIceFrame> {
        if (closed || token.owner !== identity || token !== description || token.key != key || descriptionReady) return emptyList()
        val now = now()
        if (token.expiresAtMillis <= now) fail("Remote description expired before application")
        descriptionReady = true
        val ready = pending.filter { it.expiresAtMillis > now }.sortedBy { it.sequence }
        // A missing expired candidate means this negotiation is incomplete. Do
        // not silently deliver end-of-candidates and claim successful recovery.
        if (ready.size != pending.size) fail("Buffered RTC candidate expired")
        pending.clear()
        return ready
    }

    /** Returns immediately applicable frames, or empty for buffered/old/replayed
     * frames. No candidate can reach native before the matching description. */
    @Synchronized
    fun accept(frame: RemoteIceFrame): List<RemoteIceFrame> {
        requireOpen()
        if (frame.key != key) return emptyList()
        if (frame.senderId != remoteDeviceId) fail("RTC sender does not match the leg")
        val now = now()
        seen[frame.sequence]?.let {
            if (it != frame) fail("RTC sequence was rebound to another payload")
            return emptyList() // replay never extends expiry or repeats native work
        }
        if (frame.expiresAtMillis <= now) fail("RTC candidate expired")
        if (frame.sequence < 1 || frame.expiresAtMillis - now > SIGNAL_LIFETIME_MS ||
            description?.sequence == frame.sequence || !validIce(frame.ice)) {
            fail("Invalid RTC candidate envelope")
        }
        if (seen.size >= MAX_CANDIDATES + 1 || frame.ice != null && seen.values.count { it.ice != null } >= MAX_CANDIDATES) {
            fail("RTC candidate budget exceeded")
        }
        endSequence?.let { if (frame.sequence >= it) fail("RTC candidate follows end-of-candidates") }
        if (frame.ice == null) {
            if (seen.keys.any { it >= frame.sequence }) fail("RTC end-of-candidates precedes a candidate")
            // Arrival may be reordered, but delivering EOC before an earlier
            // candidate is unsafe. The transport must preserve server ordering
            // once SDP/ICE have been separated; before SDP we sort the buffer.
            endSequence = frame.sequence
        }
        if (!descriptionReady && pending.size >= MAX_CANDIDATES + 1) fail("RTC pending queue exceeded")
        seen[frame.sequence] = frame
        if (!descriptionReady) { pending += frame; return emptyList() }
        // Once EOC has reached native, a late earlier candidate also requires a
        // fresh generation rather than silently appending to a completed set.
        if (endSequence != null && frame.ice != null) fail("RTC candidate arrived after delivered end-of-candidates")
        return listOf(frame)
    }

    @Synchronized
    override fun close() { closed = true; clearGeneration() }

    private fun now(): Long {
        val value = nowMillis()
        if (value < lastNow) fail("RTC clock moved backwards")
        lastNow = value
        return value
    }

    private fun clearGeneration() {
        description = null
        descriptionReady = false
        pending.clear()
        seen.clear()
        endSequence = null
    }

    private fun requireOpen() { if (closed) throw RtcProtocolException("RTC leg is closed") }
    private fun fail(message: String): Nothing { close(); throw RtcProtocolException(message) }

    companion object {
        const val MAX_CANDIDATES = 128
        const val MAX_GENERATIONS = 8
        const val SIGNAL_LIFETIME_MS = 15_000L
        const val MAX_CANDIDATE_BYTES = 2048
        private val idPattern = Regex("[A-Za-z0-9_-]{1,128}")
        private fun validId(value: String) = idPattern.matches(value)
        private fun validKey(key: RtcGeneration) = key.generation in 1..MAX_GENERATIONS &&
            listOf(key.scope.nodeId, key.scope.callId, key.scope.legId, key.scope.attemptId).all(::validId)
        private fun validIce(ice: RtcIce?) = ice == null || (
            ice.candidate.isNotEmpty() && ice.candidate.toByteArray(Charsets.UTF_8).size <= MAX_CANDIDATE_BYTES &&
                ice.candidate.none { it == '\r' || it == '\n' || it == '\u0000' } &&
                validId(ice.sdpMid) && ice.sdpMLineIndex in 0..15)
    }
}
