package com.callagent.gateway.sip

import com.callagent.gateway.rtp.SrtpCryptoSuite
import com.callagent.gateway.rtp.SrtpKeys

import android.util.Log

/**
 * Represents one SIP call dialog.
 * Tracks dialog state (tags, CSeq, contact) and provides methods to
 * send in-dialog requests (ACK, BYE).
 */
class SipCall(
    val callId: String,
    val direction: Direction,
    private val sipClient: SipClient
) {
    enum class Direction { INBOUND, OUTBOUND }
    enum class State { TRYING, RINGING, ANSWERED, TERMINATED }

    @Volatile var state: State = State.TRYING
        private set

    /** Set when any SIP response is received — stops INVITE retransmission (Timer A) */
    @Volatile var responseReceived = false

    // Dialog identifiers
    var localTag: String = "gw${(100000000..999999999).random()}"
    var remoteTag: String? = null
    var fromHeader: String? = null
    var toHeader: String? = null

    // Remote endpoint
    var remoteContactUri: String? = null
    var remoteContactAddress: Pair<String, Int>? = null

    // CSeq tracking
    var localCseq: Int = 1
    var remoteCseq: Int = 0

    // Auth: prevent re-sending credentials on duplicate/retransmitted 401s.
    // UDP can retransmit the server's 401, causing us to send a SECOND
    // authenticated INVITE (CSeq N+1) which gets 491 (Request Pending)
    // because the server is already processing the first one (CSeq N).
    @Volatile var authHandled = false

    // RTP endpoints
    var localRtpPort: Int = 0
    var remoteRtpPort: Int = 0
    var remoteRtpAddress: String? = null
    var negotiatedPayloadType: Int = 9 // default G.722, updated from SDP
    var negotiatedTelephoneEventPayloadType: Int? = null

    /**
     * For inbound calls this is populated only by SipClient after parsing a
     * complete v1 metadata set from the verified TLS peer. Outbound calls
     * retain the locally validated mapping metadata for auth retries.
     */
    var trustedGsmMetadata: GsmCallMetadata? = null
        internal set
    val requiresSrtp: Boolean get() = sipClient.srtpEnabled

    // ── SRTP (RFC 3711 / RFC 4568) ──────────────────────
    // Two independent keys, one per direction: ours protects what we send,
    // theirs authenticates what we receive.  Both null means plain RTP.

    /** Our keying material, offered or answered in our own SDP. */
    var localSrtpKeys: SrtpKeys? = null

    /** The peer's, taken from their SDP. */
    var remoteSrtpKeys: SrtpKeys? = null

    /** Crypto tag to answer with -- an answer must echo the accepted tag. */
    var srtpTag: Int = 1

    /** True only when both directions are keyed and the profile is SAVP. */
    val srtpActive: Boolean get() = localSrtpKeys != null && remoteSrtpKeys != null

    /**
     * Take the peer's key out of their SDP, if they offered a suite we can do.
     *
     * A crypto line on a non-SAVP stream is ignored deliberately: the profile
     * decides, and answering plain RTP with encrypted audio produces a call
     * where neither side hears anything and nothing looks wrong.
     */
    fun absorbRemoteSrtp(
        msg: SipMessage,
        expectedTag: Int? = null,
        expectedSuite: SrtpCryptoSuite? = null
    ): Boolean {
        if (!msg.sdpIsSavp) return false
        val cryptoLines = msg.sdpCryptoLines
        if (cryptoLines.size != msg.sdpCryptoLineCount) return false
        val tags = cryptoLines.map { it.first }
        if (tags.any { it <= 0 } || tags.toSet().size != tags.size) return false
        for ((tag, suiteName, inlineValue) in cryptoLines) {
            if (expectedTag != null && tag != expectedTag) continue
            val suite = SrtpCryptoSuite.byName(suiteName)
                ?.takeIf { it == SrtpCryptoSuite.offered } ?: continue
            if (expectedSuite != null && suite != expectedSuite) continue
            val keys = SrtpKeys.fromInline(suite, inlineValue) ?: continue
            remoteSrtpKeys = keys
            srtpTag = tag
            return true
        }
        return false
    }

    // Caller info (for inbound and outbound caller-ID)
    var callerNumber: String? = null
    var callerDisplayName: String? = null
    // Outbound caller-ID (preserved for auth re-INVITE)
    var outboundCallerIdNumber: String? = null
    var outboundCallerIdName: String? = null

    // GSM forward target (for outbound from server)
    var gsmForwardNumber: String? = null

    // Original INVITE (for building responses)
    var originalInvite: SipMessage? = null

    /**
     * Serialize the short, final inbound-call dispatch against CANCEL.
     * Slow capability/root preparation must happen before entering this lock.
     * A false action leaves the SIP state unchanged.
     */
    @Synchronized
    fun withPendingInvite(action: () -> Boolean): Boolean {
        if (direction != Direction.INBOUND || state !in setOf(State.TRYING, State.RINGING)) return false
        return action()
    }

    var listener: Listener? = null

    interface Listener {
        fun onCallAnswered(call: SipCall)
        fun onCallTerminated(call: SipCall)
        fun onRtpReady(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int)
    }

    /** Process incoming SIP message for this dialog */
    fun handleMessage(msg: SipMessage): Boolean {
        // Stop INVITE retransmission as soon as any response arrives
        if (msg.isResponse) responseReceived = true

        when {
            // 200 OK to our INVITE
            msg.isResponse && msg.statusCode == 200 && msg.cseq?.contains("INVITE") == true -> {
                remoteTag = msg.toTag
                toHeader = msg.to
                msg.contactUri?.let { remoteContactUri = it }
                msg.contactAddress?.let { remoteContactAddress = it }
                msg.sdpRtpPort?.let { remoteRtpPort = it }
                msg.sdpAddress?.let { remoteRtpAddress = it }
                negotiatedPayloadType = msg.sdpPreferredPayloadType
                negotiatedTelephoneEventPayloadType = msg.sdpTelephoneEventPayloadType

                val srtpAccepted = !requiresSrtp ||
                    (localSrtpKeys != null && absorbRemoteSrtp(
                        msg,
                        expectedTag = srtpTag,
                        expectedSuite = localSrtpKeys?.suite
                    ))

                // ACK must use the same CSeq as the INVITE being acknowledged
                val ackCseq = msg.cseq?.split(" ")?.firstOrNull()?.toIntOrNull() ?: localCseq
                sendAck(ackCseq)

                // A late 200 may arrive after the call was cancelled locally;
                // ACK it but never let it revive a terminated call.
                val answerTransition = synchronized(this) {
                    when (state) {
                        State.ANSWERED -> 1
                        State.TERMINATED -> 2
                        State.TRYING, State.RINGING -> {
                            state = State.ANSWERED
                            0
                        }
                    }
                }
                if (answerTransition != 0) {
                    Log.i(
                        TAG,
                        if (answerTransition == 1) "Duplicate 200 OK for call $callId (already answered), ACKed"
                        else "Late 200 OK for terminated call $callId, ACKed"
                    )
                    return true
                }

                if (!srtpAccepted) {
                    // The remote 200 already established a dialog. ACK then
                    // close it; never remove the keys and continue in RTP.
                    Log.w(TAG, "Peer answered without the required SDES suite; closing call")
                    sipClient.logListener?.invoke("Call rejected: required SRTP negotiation failed")
                    hangup()
                    return true
                }
                if (requiresSrtp) {
                    Log.i(TAG, "SRTP negotiated: ${remoteSrtpKeys?.suite?.sdpName}")
                    sipClient.logListener?.invoke("SRTP active (${remoteSrtpKeys?.suite?.sdpName})")
                }

                Log.i(TAG, "SDP codec: pt=$negotiatedPayloadType codecs=${msg.sdpCodecs}")

                val addr = remoteRtpAddress ?: remoteContactAddress?.first
                Log.i(TAG, "200 OK RTP: addr=$addr port=$remoteRtpPort listener=${listener != null}")
                if (addr != null && remoteRtpPort > 0) {
                    listener?.onRtpReady(this, addr, remoteRtpPort, negotiatedPayloadType)
                } else {
                    Log.w(TAG, "200 OK missing RTP info — addr=$addr port=$remoteRtpPort, cannot bridge")
                    sipClient.logListener?.invoke("200 OK missing RTP: addr=$addr port=$remoteRtpPort")
                }
                listener?.onCallAnswered(this)
                return true
            }

            // 100 Trying
            msg.isResponse && msg.statusCode == 100 -> {
                return true
            }

            // 180 Ringing
            msg.isResponse && msg.statusCode == 180 -> {
                synchronized(this) {
                    if (state == State.TRYING) state = State.RINGING
                }
                remoteTag = msg.toTag
                return true
            }

            // 401/407 Auth required for INVITE
            msg.isResponse && (msg.statusCode == 401 || msg.statusCode == 407) -> {
                if (authHandled) {
                    // UDP retransmission of the original 401 — ignore it.
                    // We already sent an authenticated re-INVITE; sending another
                    // would create a duplicate CSeq that gets 491 (Request Pending).
                    Log.i(TAG, "Ignoring duplicate ${msg.statusCode} — already re-sent with credentials")
                    return true
                }
                val authParams = SipAuth.parseChallenge(msg)
                if (authParams != null) {
                    authHandled = true
                    Log.i(TAG, "INVITE auth challenge, re-sending with credentials")
                    sipClient.resendInviteWithAuth(this, authParams)
                }
                return true
            }

            // CANCEL applies only to the original inbound INVITE transaction.
            msg.isRequest && msg.method == "CANCEL" -> {
                handleCancel(msg)
                return true
            }

            // Incoming BYE
            msg.isRequest && msg.method == "BYE" -> {
                Log.i(TAG, "Received BYE for call $callId")
                // Send 200 OK to BYE
                sipClient.sendResponse(
                    SipBuilder.ok200(msg, sipClient.username, sipClient.publicIp, sipClient.localPort),
                    remoteContactAddress ?: sipClient.serverAddress
                )
                val transitioned = synchronized(this) {
                    if (state == State.TERMINATED) false else {
                        state = State.TERMINATED
                        true
                    }
                }
                if (transitioned) listener?.onCallTerminated(this)
                return true
            }

            // 183 Session Progress (early media)
            msg.isResponse && msg.statusCode == 183 -> {
                synchronized(this) {
                    if (state == State.TRYING) state = State.RINGING
                }
                remoteTag = msg.toTag
                Log.i(TAG, "183 Session Progress for call $callId")
                return true
            }

            // 4xx/5xx/6xx error responses — log and terminate
            msg.isResponse && msg.statusCode != null && msg.statusCode!! >= 300 -> {
                Log.e(TAG, "SIP error ${msg.statusCode} for call $callId (CSeq: ${msg.cseq})")
                // ACK the error response (required by RFC 3261 for INVITE transactions)
                if (msg.cseq?.contains("INVITE") == true) {
                    val ackCseq = msg.cseq?.split(" ")?.firstOrNull()?.toIntOrNull() ?: localCseq
                    val uri = remoteContactUri ?: "sip:${sipClient.serverDomain}:${sipClient.serverPort}"
                    val ack = SipBuilder.ack(
                        uri, null, msg.to, fromHeader,
                        callId, ackCseq,
                        sipClient.username, sipClient.publicIp, sipClient.localPort
                    )
                    sipClient.sendResponse(ack, remoteContactAddress ?: sipClient.serverAddress)
                }
                // Ignore error responses once the dialog is established (200 OK
                // received).  This handles the case where a duplicate INVITE
                // (CSeq N+1, sent because a retransmitted 401 was treated as a
                // new challenge) gets a 491 (Request Pending) AFTER the original
                // INVITE's 200 OK already established the dialog.  Without this
                // guard, the 491 tears down an active call.
                val transitioned = synchronized(this) {
                    if (state == State.ANSWERED || state == State.TERMINATED) false else {
                        state = State.TERMINATED
                        true
                    }
                }
                if (!transitioned) {
                    Log.i(TAG, "Ignoring ${msg.statusCode} — call is no longer pending (CSeq: ${msg.cseq})")
                    return true
                }
                sipClient.logListener?.invoke("INVITE rejected: ${msg.statusCode} (call $callId)")
                listener?.onCallTerminated(this)
                return true
            }

            // ACK (for our 200 OK)
            msg.isRequest && msg.method == "ACK" -> {
                Log.d(TAG, "Received ACK for call $callId")
                return true
            }

            else -> {
                Log.w(TAG, "Unhandled SIP method or response for call $callId")
                return false
            }
        }
    }

    /** Accept an inbound INVITE: send 200 OK with SDP */
    fun accept(localRtpPort: Int) {
        val invite = originalInvite ?: return
        val accepted = synchronized(this) {
            if (direction != Direction.INBOUND || state !in setOf(State.TRYING, State.RINGING)) {
                false
            } else {
                this.localRtpPort = localRtpPort
                val ok = SipBuilder.ok200(
                    invite, sipClient.username, sipClient.publicIp, sipClient.localPort,
                    localRtpPort = localRtpPort, toTag = localTag,
                    srtp = localSrtpKeys, srtpTag = srtpTag,
                    codecPayloadType = negotiatedPayloadType,
                    telephoneEventPayloadType = negotiatedTelephoneEventPayloadType
                )
                state = State.ANSWERED
                // Enqueue before releasing the call lock so a later CANCEL
                // cannot overtake the INVITE 200 on the SIP send queue.
                sipClient.sendResponse(ok, invite.contactAddress ?: sipClient.serverAddress)
                true
            }
        }
        if (accepted) Log.i(TAG, "Sent 200 OK for inbound call $callId (RTP port: $localRtpPort)")
    }

    /** Turn down an inbound INVITE we cannot bridge. */
    fun reject(code: Int, reason: String) {
        val invite = originalInvite ?: return
        val transitioned = synchronized(this) {
            if (state == State.TERMINATED || state == State.ANSWERED) false else {
                state = State.TERMINATED
                val response = SipBuilder.reject(invite, code, reason, localTag)
                sipClient.sendResponse(response, invite.contactAddress ?: sipClient.serverAddress)
                true
            }
        }
        if (transitioned) {
            Log.i(TAG, "Rejected inbound call $callId with $code $reason")
            listener?.onCallTerminated(this)
        }
    }

    /** Send ACK for a received 200 OK */
    private fun sendAck(cseq: Int) {
        val uri = remoteContactUri ?: "sip:${sipClient.serverDomain}:${sipClient.serverPort}"
        val ack = SipBuilder.ack(
            uri, null, toHeader, fromHeader,
            callId, cseq,
            sipClient.username, sipClient.publicIp, sipClient.localPort
        )
        sipClient.sendResponse(ack, remoteContactAddress ?: sipClient.serverAddress)
        Log.d(TAG, "Sent ACK for call $callId (CSeq: $cseq)")
    }

    /** Send BYE to terminate the call */
    fun hangup() {
        val transitioned = synchronized(this) {
            if (state == State.TERMINATED) false else {
                val uri = remoteContactUri ?: "sip:${sipClient.serverDomain}:${sipClient.serverPort}"
                val bye = SipBuilder.bye(
                    uri, fromHeader, toHeader,
                    callId, localCseq++,
                    sipClient.username, sipClient.publicIp, sipClient.localPort
                )
                state = State.TERMINATED
                sipClient.sendResponse(bye, remoteContactAddress ?: sipClient.serverAddress)
                true
            }
        }
        if (transitioned) {
            Log.i(TAG, "Sent BYE for call $callId")
            listener?.onCallTerminated(this)
        }
    }

    private enum class CancelDisposition { INVALID, CANCELLED, ANSWERED }

    private fun handleCancel(cancel: SipMessage) {
        val invite = originalInvite
        val disposition = synchronized(this) {
            if (direction != Direction.INBOUND || invite == null || !sameInviteTransaction(invite, cancel)) {
                CancelDisposition.INVALID
            } else when (state) {
                State.TRYING, State.RINGING -> {
                    state = State.TERMINATED
                    CancelDisposition.CANCELLED
                }
                State.ANSWERED -> CancelDisposition.ANSWERED
                State.TERMINATED -> CancelDisposition.INVALID
            }
        }

        val address = remoteContactAddress ?: sipClient.serverAddress
        when (disposition) {
            CancelDisposition.INVALID -> sipClient.sendResponse(
                SipBuilder.statusResponse(cancel, 481, "Call/Transaction Does Not Exist", toTag = localTag),
                address
            )
            CancelDisposition.ANSWERED -> sipClient.sendResponse(
                SipBuilder.ok200(
                    cancel, sipClient.username, sipClient.publicIp, sipClient.localPort, toTag = localTag
                ),
                address
            )
            CancelDisposition.CANCELLED -> {
                // A CANCEL has its own transaction response; terminating the
                // INVITE uses the INVITE's original CSeq and Via.
                sipClient.sendResponse(
                    SipBuilder.ok200(
                        cancel, sipClient.username, sipClient.publicIp, sipClient.localPort, toTag = localTag
                    ),
                    address
                )
                sipClient.sendResponse(
                    SipBuilder.reject(invite!!, 487, "Request Terminated", localTag),
                    invite.contactAddress ?: sipClient.serverAddress
                )
                listener?.onCallTerminated(this)
            }
        }
    }

    /** Strict transaction identity check; ambiguous or malformed headers fail closed. */
    private fun sameInviteTransaction(invite: SipMessage, cancel: SipMessage): Boolean {
        if (!invite.isRequest || invite.method != "INVITE" || !cancel.isRequest || cancel.method != "CANCEL") {
            return false
        }
        val inviteCallId = invite.headerValues("call-id").singleOrNull()?.takeIf { it.isNotBlank() }
            ?: return false
        val cancelCallId = cancel.headerValues("call-id").singleOrNull()?.takeIf { it.isNotBlank() }
            ?: return false
        if (inviteCallId != cancelCallId || cancelCallId != callId) return false
        if (invite.requestUri.isNullOrEmpty() || invite.requestUri != cancel.requestUri) return false

        val inviteCseq = cseqNumber(invite, "INVITE") ?: return false
        val cancelCseq = cseqNumber(cancel, "CANCEL") ?: return false
        if (inviteCseq != cancelCseq) return false

        val inviteFrom = invite.headerValues("from").singleOrNull() ?: return false
        val cancelFrom = cancel.headerValues("from").singleOrNull() ?: return false
        val inviteFromTag = uniqueHeaderParameter(inviteFrom, "tag") ?: return false
        val cancelFromTag = uniqueHeaderParameter(cancelFrom, "tag") ?: return false
        if (inviteFromTag != cancelFromTag) return false

        val inviteVia = topViaIdentity(invite) ?: return false
        val cancelVia = topViaIdentity(cancel) ?: return false
        return inviteVia == cancelVia
    }

    private fun cseqNumber(message: SipMessage, method: String): Long? {
        val text = message.headerValues("cseq").singleOrNull()?.trim() ?: return null
        val match = Regex("^([0-9]+)[ \\t]+([A-Z]+)$").matchEntire(text) ?: return null
        if (match.groupValues[2] != method) return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it in 0..2_147_483_647L }
    }

    private data class ViaIdentity(val transport: String, val sentBy: String, val branch: String)

    private fun topViaIdentity(message: SipMessage): ViaIdentity? {
        val topVia = message.headerValues("via").firstOrNull()?.substringBefore(',')?.trim() ?: return null
        val match = Regex("^SIP/2\\.0/([^ \\t/;]+)[ \\t]+([^ \\t;,]+)(?:[ \\t;]|$)", RegexOption.IGNORE_CASE)
            .find(topVia) ?: return null
        val branch = uniqueHeaderParameter(topVia, "branch") ?: return null
        return ViaIdentity(
            transport = match.groupValues[1].lowercase(),
            sentBy = match.groupValues[2].lowercase(),
            branch = branch
        )
    }

    private fun uniqueHeaderParameter(header: String, parameter: String): String? {
        val suffix = header.substringAfterLast('>', missingDelimiterValue = header)
        val pattern = Regex(
            "(?:^|;)\\s*${Regex.escape(parameter)}\\s*=\\s*([^;\\s,]+)",
            RegexOption.IGNORE_CASE
        )
        val values = pattern.findAll(suffix).map { it.groupValues[1] }.toList()
        return values.singleOrNull()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "SipCall"
    }
}
