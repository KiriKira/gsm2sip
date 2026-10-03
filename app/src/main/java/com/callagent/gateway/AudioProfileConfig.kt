package com.callagent.gateway

import java.nio.charset.StandardCharsets

/** Local, root-managed audio settings. Values are deliberately typed and bounded. */
data class AudioProfileConfig(
    val preset: Preset,
    val allowMicFallback: Boolean?,
    val telephonyRxRequired: Boolean?,
    val captureGain: Int?,
    val captureSilenceFrames: Int?,
    val preferVoiceRecognition: Boolean?,
    val telephonyTxRequired: Boolean?,
    val playbackGain: Int?,
    val playbackBufferMs: Int?,
) {
    enum class Preset(val configName: String) {
        GENERIC("generic"),
        LEGACY_MSM8930("legacy_msm8930"),
        LEGACY_EXYNOS9820("legacy_exynos9820"),
        LEGACY_SM6150("legacy_sm6150"),
        LEGACY_QUALCOMM("legacy_qualcomm"),
        LEGACY_EXYNOS("legacy_exynos"),
    }
}

/** Strict JSON parser for the local audio profile. It has no Android dependencies. */
object AudioProfileConfigParser {
    const val MAX_BYTES = 8 * 1024

    fun parse(json: String): AudioProfileConfig {
        if (json.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) {
            throw AudioProfileConfigException("configuration exceeds ${MAX_BYTES} bytes")
        }
        val root = StrictJsonParser(json).parse() as? JsonValue.ObjectValue
            ?: throw AudioProfileConfigException("top-level JSON value must be an object")
        root.rejectUnknown(setOf("version", "preset", "capture", "playback"), "")

        val version = root.requiredInt("version", "")
        if (version != 1) throw AudioProfileConfigException("unsupported version: $version")

        val presetName = root.optionalString("preset", "") ?: "generic"
        val preset = AudioProfileConfig.Preset.entries.firstOrNull { it.configName == presetName }
            ?: throw AudioProfileConfigException("unsupported preset: $presetName")

        val capture = root.optionalObject("capture", "")
        capture?.rejectUnknown(
            setOf("allowMicFallback", "telephonyRxRequired", "gain", "silenceFrames", "preferVoiceRecognition"),
            "capture"
        )
        val playback = root.optionalObject("playback", "")
        playback?.rejectUnknown(setOf("telephonyTxRequired", "gain", "bufferMs"), "playback")

        return AudioProfileConfig(
            preset = preset,
            allowMicFallback = capture?.optionalBoolean("allowMicFallback", "capture"),
            telephonyRxRequired = capture?.optionalBoolean("telephonyRxRequired", "capture"),
            captureGain = capture?.optionalInt("gain", "capture", 1..20),
            captureSilenceFrames = capture?.optionalInt("silenceFrames", "capture", 25..500),
            preferVoiceRecognition = capture?.optionalBoolean("preferVoiceRecognition", "capture"),
            telephonyTxRequired = playback?.optionalBoolean("telephonyTxRequired", "playback"),
            playbackGain = playback?.optionalInt("gain", "playback", 1..20),
            playbackBufferMs = playback?.optionalInt("bufferMs", "playback", 0..500),
        )
    }

}

class AudioProfileConfigException(message: String) : IllegalArgumentException(message)

private sealed interface JsonValue {
    data class ObjectValue(val fields: LinkedHashMap<String, JsonValue>) : JsonValue
    data class ArrayValue(val elements: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val raw: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

private fun JsonValue.ObjectValue.rejectUnknown(allowed: Set<String>, path: String) {
    val unknown = fields.keys.firstOrNull { it !in allowed } ?: return
    val location = if (path.isEmpty()) unknown else "$path.$unknown"
    throw AudioProfileConfigException("unknown field: $location")
}

private fun JsonValue.ObjectValue.requiredInt(name: String, path: String): Int {
    val value = fields[name] ?: throw AudioProfileConfigException("missing required field: $name")
    return value.asInt(pathName(path, name))
}

private fun JsonValue.ObjectValue.optionalString(name: String, path: String): String? =
    fields[name]?.let {
        (it as? JsonValue.StringValue)?.value
            ?: throw AudioProfileConfigException("${pathName(path, name)} must be a string")
    }

private fun JsonValue.ObjectValue.optionalObject(name: String, path: String): JsonValue.ObjectValue? =
    fields[name]?.let {
        it as? JsonValue.ObjectValue
            ?: throw AudioProfileConfigException("${pathName(path, name)} must be an object")
    }

private fun JsonValue.ObjectValue.optionalBoolean(name: String, path: String): Boolean? =
    fields[name]?.let {
        (it as? JsonValue.BooleanValue)?.value
            ?: throw AudioProfileConfigException("${pathName(path, name)} must be a boolean")
    }

private fun JsonValue.ObjectValue.optionalInt(name: String, path: String, range: IntRange): Int? =
    fields[name]?.let {
        val value = it.asInt(pathName(path, name))
        if (value !in range) {
            throw AudioProfileConfigException("${pathName(path, name)} must be in ${range.first}..${range.last}")
        }
        value
    }

private fun JsonValue.asInt(field: String): Int {
    val number = this as? JsonValue.NumberValue
        ?: throw AudioProfileConfigException("$field must be an integer")
    if (!number.raw.matches(Regex("-?(0|[1-9][0-9]*)"))) {
        throw AudioProfileConfigException("$field must be an integer")
    }
    return number.raw.toIntOrNull()
        ?: throw AudioProfileConfigException("$field is outside the supported integer range")
}

private fun pathName(path: String, field: String): String = if (path.isEmpty()) field else "$path.$field"

/** Minimal RFC 8259 parser with duplicate-key and nesting checks. */
private class StrictJsonParser(private val source: String) {
    private var offset = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = parseValue(0)
        skipWhitespace()
        if (offset != source.length) fail("trailing content")
        return value
    }

    private fun parseValue(depth: Int): JsonValue {
        if (depth > MAX_DEPTH) fail("JSON nesting exceeds $MAX_DEPTH")
        if (offset >= source.length) fail("unexpected end of input")
        return when (source[offset]) {
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            '"' -> JsonValue.StringValue(parseString())
            't' -> { consumeLiteral("true"); JsonValue.BooleanValue(true) }
            'f' -> { consumeLiteral("false"); JsonValue.BooleanValue(false) }
            'n' -> { consumeLiteral("null"); JsonValue.NullValue }
            '-', in '0'..'9' -> JsonValue.NumberValue(parseNumber())
            else -> fail("unexpected character")
        }
    }

    private fun parseObject(depth: Int): JsonValue.ObjectValue {
        offset++ // {
        skipWhitespace()
        val fields = LinkedHashMap<String, JsonValue>()
        if (take('}')) return JsonValue.ObjectValue(fields)
        while (true) {
            if (offset >= source.length || source[offset] != '"') fail("object key must be a string")
            val key = parseString()
            if (fields.containsKey(key)) fail("duplicate object key")
            skipWhitespace()
            expect(':')
            skipWhitespace()
            fields[key] = parseValue(depth)
            skipWhitespace()
            if (take('}')) return JsonValue.ObjectValue(fields)
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseArray(depth: Int): JsonValue.ArrayValue {
        offset++ // [
        skipWhitespace()
        val elements = ArrayList<JsonValue>()
        if (take(']')) return JsonValue.ArrayValue(elements)
        while (true) {
            elements.add(parseValue(depth))
            skipWhitespace()
            if (take(']')) return JsonValue.ArrayValue(elements)
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (offset < source.length) {
            val char = source[offset++]
            when {
                char == '"' -> return out.toString()
                char == '\\' -> {
                    if (offset >= source.length) fail("unterminated escape")
                    when (val escaped = source[offset++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> out.append(parseUnicodeEscape())
                        else -> fail("invalid string escape")
                    }
                }
                char.code < 0x20 -> fail("unescaped control character")
                else -> out.append(char)
            }
        }
        fail("unterminated string")
    }

    private fun parseUnicodeEscape(): Char {
        if (offset + 4 > source.length) fail("incomplete unicode escape")
        val digits = source.substring(offset, offset + 4)
        if (digits.any { it.digitToIntOrNull(16) == null }) fail("invalid unicode escape")
        offset += 4
        return digits.toInt(16).toChar()
    }

    private fun parseNumber(): String {
        val start = offset
        take('-')
        if (take('0')) {
            if (offset < source.length && source[offset].isDigit()) fail("leading zero in number")
        } else {
            if (offset >= source.length || source[offset] !in '1'..'9') fail("invalid number")
            while (offset < source.length && source[offset].isDigit()) offset++
        }
        if (take('.')) {
            if (offset >= source.length || !source[offset].isDigit()) fail("invalid fraction")
            while (offset < source.length && source[offset].isDigit()) offset++
        }
        if (offset < source.length && (source[offset] == 'e' || source[offset] == 'E')) {
            offset++
            if (offset < source.length && (source[offset] == '+' || source[offset] == '-')) offset++
            if (offset >= source.length || !source[offset].isDigit()) fail("invalid exponent")
            while (offset < source.length && source[offset].isDigit()) offset++
        }
        return source.substring(start, offset)
    }

    private fun consumeLiteral(value: String) {
        if (!source.regionMatches(offset, value, 0, value.length)) fail("invalid literal")
        offset += value.length
    }

    private fun skipWhitespace() {
        while (offset < source.length && source[offset] in WHITESPACE) offset++
    }

    private fun expect(char: Char) {
        if (!take(char)) fail("expected '$char'")
    }

    private fun take(char: Char): Boolean {
        if (offset < source.length && source[offset] == char) {
            offset++
            return true
        }
        return false
    }

    private fun fail(message: String): Nothing =
        throw AudioProfileConfigException("invalid JSON at character $offset: $message")

    private companion object {
        const val MAX_DEPTH = 8
        val WHITESPACE = charArrayOf(' ', '\t', '\r', '\n')
    }
}
