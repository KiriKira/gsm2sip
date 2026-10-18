package com.callagent.rtcmedia

/** Candidates and completion must be observed together. A separate queue drain
 * followed by a completion read can enqueue END ahead of the final candidates. */
internal class LocalIceQueue<T> {
    data class Batch<T>(val candidates: List<T>, val complete: Boolean)
    private val candidates = ArrayDeque<T>()
    private var complete = false

    @Synchronized fun add(candidate: T) { candidates.addLast(candidate) }
    @Synchronized fun complete() { complete = true }
    @Synchronized fun hasCandidates(): Boolean = candidates.isNotEmpty()
    @Synchronized fun drain(): Batch<T> {
        val batch = Batch(candidates.toList(), complete)
        candidates.clear()
        return batch
    }
}
