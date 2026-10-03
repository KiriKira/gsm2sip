package com.callagent.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProfileConfigTest {
    @Test
    fun omittedPresetDefaultsToGenericAndDoesNotEnableAcousticFallback() {
        val parsed = AudioProfileConfigParser.parse("""{"version":1}""")

        assertEquals(AudioProfileConfig.Preset.GENERIC, parsed.preset)
        assertNull(parsed.allowMicFallback)
        assertNull(parsed.telephonyRxRequired)
        assertNull(parsed.telephonyTxRequired)
        assertNull(parsed.captureGain)
        assertNull(parsed.playbackGain)
    }

    @Test
    fun parsesExplicitLegacyPresetAndBoundedTypedOptions() {
        val parsed = AudioProfileConfigParser.parse(
            """{"version":1,"preset":"legacy_sm6150","capture":{"allowMicFallback":true,"telephonyRxRequired":false,"gain":4,"silenceFrames":250,"preferVoiceRecognition":true},"playback":{"telephonyTxRequired":true,"gain":3,"bufferMs":120}}"""
        )

        assertEquals(AudioProfileConfig.Preset.LEGACY_SM6150, parsed.preset)
        assertTrue(parsed.allowMicFallback == true)
        assertFalse(parsed.telephonyRxRequired == true)
        assertEquals(4, parsed.captureGain)
        assertEquals(250, parsed.captureSilenceFrames)
        assertTrue(parsed.preferVoiceRecognition == true)
        assertTrue(parsed.telephonyTxRequired == true)
        assertEquals(3, parsed.playbackGain)
        assertEquals(120, parsed.playbackBufferMs)
    }

    @Test
    fun rejectsUnknownFieldsAndDuplicateKeys() {
        assertThrows(AudioProfileConfigException::class.java) {
            AudioProfileConfigParser.parse("""{"version":1,"device":"SM6150"}""")
        }
        assertThrows(AudioProfileConfigException::class.java) {
            AudioProfileConfigParser.parse("""{"version":1,"version":1}""")
        }
        assertThrows(AudioProfileConfigException::class.java) {
            AudioProfileConfigParser.parse("""{"version":1,"capture":{"micFallback":true}}""")
        }
    }

    @Test
    fun rejectsUnsupportedVersionsPresetsAndWrongTypes() {
        listOf(
            """{"version":2}""",
            """{"version":1,"preset":"sm6150"}""",
            """{"version":"1"}""",
            """{"version":1,"capture":{"allowMicFallback":"true"}}""",
            """{"version":1,"playback":[]}""",
            """{"version":1,"capture":{"gain":1.5}}""",
            """{"version":1,"capture":{"gain":true}}""",
        ).forEach { invalid ->
            assertThrows(AudioProfileConfigException::class.java) {
                AudioProfileConfigParser.parse(invalid)
            }
        }
    }

    @Test
    fun rejectsNumericValuesOutsideTheirBoundsAndOversizedUtf8() {
        listOf(
            """{"version":1,"capture":{"gain":0}}""",
            """{"version":1,"capture":{"gain":21}}""",
            """{"version":1,"capture":{"silenceFrames":24}}""",
            """{"version":1,"capture":{"silenceFrames":501}}""",
            """{"version":1,"playback":{"bufferMs":-1}}""",
            """{"version":1,"playback":{"bufferMs":501}}""",
        ).forEach { invalid ->
            assertThrows(AudioProfileConfigException::class.java) {
                AudioProfileConfigParser.parse(invalid)
            }
        }

        val oversized = "{" + " ".repeat(AudioProfileConfigParser.MAX_BYTES) + "}"
        assertTrue(oversized.toByteArray(Charsets.UTF_8).size > AudioProfileConfigParser.MAX_BYTES)
        assertThrows(AudioProfileConfigException::class.java) {
            AudioProfileConfigParser.parse(oversized)
        }

        val multibyteOversized = """{"version":1,"preset":"${"é".repeat(AudioProfileConfigParser.MAX_BYTES / 2)}"}"""
        assertTrue(multibyteOversized.length < AudioProfileConfigParser.MAX_BYTES)
        assertTrue(multibyteOversized.toByteArray(Charsets.UTF_8).size > AudioProfileConfigParser.MAX_BYTES)
        assertThrows(AudioProfileConfigException::class.java) {
            AudioProfileConfigParser.parse(multibyteOversized)
        }
    }

    @Test
    fun rejectsMalformedJsonAndTrailingContent() {
        listOf(
            """{"version":1,"preset":"generic}""",
            """{"version":1,}""",
            """{"version":1} trailing""",
            """{"version":1,"capture":{"allowMicFallback":true,,}}""",
        ).forEach { invalid ->
            assertThrows(AudioProfileConfigException::class.java) {
                AudioProfileConfigParser.parse(invalid)
            }
        }
    }
}
