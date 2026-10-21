package com.callagent.rtcmedia

/** Freeze trickle at successful ICE/DTLS connection, not a transient gathering
 * COMPLETE callback. Android can discover an interface and gather again after
 * reporting COMPLETE with no candidates. END is irreversible for a generation. */
internal class LocalIceQueue<T> {
    data class Batch<T>(val candidates: List<T>, val complete: Boolean)
    private val candidates = ArrayDeque<T>()
    private var complete = false

    @Synchronized fun add(candidate: T): Boolean {
        if (complete) return false
        candidates.addLast(candidate)
        return true
    }
    @Synchronized fun seal() { complete = true }
    @Synchronized fun hasCandidates(): Boolean = candidates.isNotEmpty()
    @Synchronized fun drain(): Batch<T> {
        val batch = Batch(candidates.toList(), complete)
        candidates.clear()
        return batch
    }
}
