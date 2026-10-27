package com.callagent.gateway.rtc

import com.callagent.rtcmedia.MediaApiException

/** Distinguishes server rejections from POST outcomes that may have committed.
 * NO_INCOMING_SUBSCRIBERS only says no host on that node takes calls now: the
 * offer moves on to the other nodes. */
internal fun mayHaveCommittedCreate(error: Throwable): Boolean = when (error) {
    is MediaApiException -> error.code != "GATEWAY_UNAVAILABLE" && error.code != "NO_INCOMING_SUBSCRIBERS" &&
        (error.status >= 500 || (error.status == 409 && error.code == "CALL_ID_CONFLICT"))
    else -> true
}

/** An incoming call rings the hosts for this long from its first cellular RINGING. */
internal const val INCOMING_RING_MS = 25_000L

/** What is left of the ring window, on the monotonic clock (elapsedRealtime). */
fun remainingRingMs(ringStartedAt: Long, now: Long): Long = INCOMING_RING_MS - (now - ringStartedAt)

/** Coordinates the short window in which one incoming call is being offered
 * sequentially to several RTC nodes. No caller holds this monitor over I/O. */
internal class IncomingForkCoordinator(nodes: Collection<String>) {
    enum class CreateOutcome { CREATED, UNKNOWN, REJECTED }

    data class TerminalDelivery(val nodeGatewayId: String, val kind: String)

    private val lock = Any()
    private val eligibleNodes = nodes.toSet()
    private val attemptedNodes = mutableSetOf<String>()
    private val creatingNodes = mutableSetOf<String>()
    private val createdNodes = mutableSetOf<String>()
    private val uncertainNodes = mutableSetOf<String>()
    private var acceptingOffers = true
    private var terminalKind: String? = null
    private var winningNode: String? = null

    /** Atomically admits one create attempt only while this fork is open. */
    fun beginCreate(nodeGatewayId: String): Boolean = synchronized(lock) {
        if (!acceptingOffers || terminalKind != null || nodeGatewayId !in eligibleNodes ||
            !attemptedNodes.add(nodeGatewayId)) return@synchronized false
        creatingNodes.add(nodeGatewayId)
        true
    }

    /** Called as soon as the synchronous create call returns, including errors. */
    fun completeCreate(
        nodeGatewayId: String,
        outcome: CreateOutcome
    ): List<TerminalDelivery> = synchronized(lock) {
        if (!creatingNodes.remove(nodeGatewayId)) return@synchronized emptyList()
        when (outcome) {
            CreateOutcome.CREATED -> createdNodes.add(nodeGatewayId)
            CreateOutcome.UNKNOWN -> uncertainNodes.add(nodeGatewayId)
            CreateOutcome.REJECTED -> Unit
        }
        terminalDeliveriesLocked()
    }

    /** A committed winner closes the offer gate and ends already-created losers. */
    fun winner(winningNodeGatewayId: String): List<TerminalDelivery> = synchronized(lock) {
        if (terminalKind == "ended") return@synchronized emptyList()
        if (terminalKind == null) {
            terminalKind = "answered_elsewhere"
            winningNode = winningNodeGatewayId
        } else if (winningNode != winningNodeGatewayId) {
            // The durable call_winners CAS is authoritative if a duplicate
            // callback ever arrives with a different node.
            return@synchronized emptyList()
        }
        terminalDeliveriesLocked()
    }

    /** A cellular end takes precedence for creates that are still in flight. */
    fun ended(): List<TerminalDelivery> = synchronized(lock) {
        terminalKind = "ended"
        winningNode = null
        terminalDeliveriesLocked()
    }

    fun finishOffers() = synchronized(lock) {
        acceptingOffers = false
    }

    private fun terminalDeliveriesLocked(): List<TerminalDelivery> {
        val kind = terminalKind ?: return emptyList()
        return (createdNodes + uncertainNodes)
            .asSequence()
            .filter { kind == "ended" || it != winningNode }
            .map { TerminalDelivery(it, kind) }
            .toList()
    }
}
