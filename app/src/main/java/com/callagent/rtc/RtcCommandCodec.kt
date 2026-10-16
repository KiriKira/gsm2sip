package com.callagent.rtc

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class RtcCommandKind(val wire: String) {
    OFFER("offer"), ANSWER("answer"), CANDIDATE("candidate"), END_CANDIDATES("end_of_candidates"), RESTART("restart"), CLOSE("close")
}

data class RtcCommand(
    val callId: String,
    val legId: String,
    val attemptId: String,
    val operationId: String,
    val generation: Int,
    val revision: Long,
    val kind: RtcCommandKind,
    val sdp: String? = null,
    val ice: RtcIce? = null
)

/** Experimental RTC v1 participant envelope. No sender/owner/recipient override,
 * permissive JSONObject parsing, unbounded read, or networking is exposed here.
 * Source and tests are kept identical in both Android repositories. */
object RtcCommandCodec {
    const val MAX_FRAME_BYTES = 96 * 1024
    const val MAX_SDP_BYTES = 64 * 1024
    private val fields = setOf("version", "call_id", "leg_id", "attempt_id", "operation_id", "generation", "revision", "kind", "sdp", "ice")
    private val required = fields - setOf("sdp", "ice")
    private val idPattern = Regex("[A-Za-z0-9_-]{1,128}")
    private val integerPattern = Regex("-?(0|[1-9][0-9]*)")

    fun decode(bytes: ByteArray): RtcCommand {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FRAME_BYTES) { "RTC frame size exceeds budget" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        val root = JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            val value = readObject(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing RTC JSON" }
            value
        }
        require(root.keys.containsAll(required) && fields.containsAll(root.keys)) { "Unknown or missing RTC field" }
        require(number(root, "version") == 1L) { "Unsupported RTC version" }
        val kind = RtcCommandKind.values().singleOrNull { it.wire == string(root, "kind") }
            ?: throw IllegalArgumentException("Unknown RTC command")
        val hasSDP = kind == RtcCommandKind.OFFER || kind == RtcCommandKind.ANSWER
        require(root.containsKey("sdp") == hasSDP && root.containsKey("ice") == (kind == RtcCommandKind.CANDIDATE)) { "Invalid RTC payload for kind" }
        val generation = number(root, "generation")
        require(generation in 1..RemoteIceBuffer.MAX_GENERATIONS.toLong()) { "Invalid RTC generation" }
        val revision = number(root, "revision")
        val cas = hasSDP || kind == RtcCommandKind.RESTART
        require(if (cas) revision >= 1 else revision == 0L) { "Invalid RTC revision" }
        val sdp = if (hasSDP) string(root, "sdp").also {
            require(it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= MAX_SDP_BYTES && '\u0000' !in it) { "Invalid RTC SDP" }
        } else null
        val ice = if (kind == RtcCommandKind.CANDIDATE) {
            @Suppress("UNCHECKED_CAST")
            val value = root["ice"] as? Map<String, Any> ?: throw IllegalArgumentException("Invalid RTC ICE payload")
            require(value.keys == setOf("candidate", "sdp_mid", "sdp_mline_index")) { "Unknown or missing RTC ICE field" }
            val candidate = string(value, "candidate")
            val mid = id(value, "sdp_mid")
            val index = number(value, "sdp_mline_index")
            require(candidate.isNotEmpty() && candidate.toByteArray(Charsets.UTF_8).size <= RemoteIceBuffer.MAX_CANDIDATE_BYTES &&
                candidate.none { it == '\r' || it == '\n' || it == '\u0000' } && index in 0..15) { "Invalid RTC candidate" }
            RtcIce(candidate, mid, index.toInt())
        } else null
        return RtcCommand(id(root, "call_id"), id(root, "leg_id"), id(root, "attempt_id"), id(root, "operation_id"),
            generation.toInt(), revision, kind, sdp, ice)
    }

    fun encode(command: RtcCommand): ByteArray {
        val value = JSONObject().put("version", 1).put("call_id", command.callId).put("leg_id", command.legId)
            .put("attempt_id", command.attemptId).put("operation_id", command.operationId)
            .put("generation", command.generation).put("revision", command.revision).put("kind", command.kind.wire)
        command.sdp?.let { value.put("sdp", it) }
        command.ice?.let { value.put("ice", JSONObject().put("candidate", it.candidate).put("sdp_mid", it.sdpMid).put("sdp_mline_index", it.sdpMLineIndex)) }
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        // Validate typed callers as strictly as received frames. This also
        // rejects an encoder call combining the wrong kind with SDP/ICE.
        require(decode(bytes) == command) { "Invalid RTC command to encode" }
        return bytes
    }

    private fun readObject(reader: JsonReader, depth: Int): Map<String, Any> {
        require(depth <= 1 && reader.peek() == JsonToken.BEGIN_OBJECT) { "Invalid RTC JSON structure" }
        val result = linkedMapOf<String, Any>()
        reader.beginObject()
        while (reader.hasNext()) {
            require(result.size < 16) { "RTC field budget exceeded" }
            val name = reader.nextName()
            require(name !in result) { "Duplicate RTC key" }
            val value: Any = when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> readObject(reader, depth + 1)
                JsonToken.STRING -> reader.nextString().also { require(validUnicode(it)) { "Invalid RTC Unicode scalar" } }
                JsonToken.NUMBER -> reader.nextString().let {
                    require(integerPattern.matches(it)) { "RTC integer required" }
                    it.toLongOrNull() ?: throw IllegalArgumentException("RTC integer out of range")
                }
                else -> throw IllegalArgumentException("Unsupported RTC JSON value")
            }
            result[name] = value
        }
        reader.endObject()
        return result
    }

    private fun string(value: Map<String, Any>, key: String) = value[key] as? String ?: throw IllegalArgumentException("RTC string required")
    private fun number(value: Map<String, Any>, key: String) = value[key] as? Long ?: throw IllegalArgumentException("RTC integer required")
    private fun id(value: Map<String, Any>, key: String) = string(value, key).also { require(idPattern.matches(it)) { "Invalid RTC identifier" } }

    private fun validUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (Character.isLowSurrogate(character)) return false
            if (Character.isHighSurrogate(character)) {
                if (index >= value.length || !Character.isLowSurrogate(value[index++])) return false
            }
        }
        return true
    }
}
