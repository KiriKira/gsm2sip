package com.callagent.gateway.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.callagent.gateway.DeviceProfile
import com.callagent.gateway.RootShell
import com.callagent.gateway.gsm.GsmCallManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges the audio of an active cellular call with a WebRTC peer through a
 * [com.callagent.rtcmedia.PcmBridge]: 20 ms PCM16 frames in both directions.
 *
 * The generic profile requests Android's public telephony RX/TX routes and
 * verifies the active devices. Capture sources are tested by reading frames;
 * opening an AudioRecord alone does not establish cellular audio support.
 * Acoustic capture is appended only by an explicit local profile option.
 *
 * The Magisk module disables Android's audio concurrency restrictions.
 * Playback runs at 16 kHz; capture opens at 16 kHz where the source allows.
 */
class CellularAudioSession(
    private val context: Context,
    private val externalPcm: com.callagent.rtcmedia.PcmBridge
) : CallAudioSession {
    private val running = AtomicBoolean(false)
    private val mediaFailureReported = AtomicBoolean(false)
    /** The four loop threads, so [stop] can wait for them to leave the
     *  AudioRecord/AudioTrack before those get released. */
    @Volatile private var workers: List<Thread> = emptyList()
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    /** 1 or 2, per [DeviceProfile.playbackStereo]. */
    private var playbackChannels = 1

    // ── Monitor ("snoop") ────────────────────────────────────────────
    // A second track on the phone's speaker carrying both sides of the call
    // mixed together, for listening in without touching the bridge.  It cannot
    // work by unmuting the call: the caller arrives on the modem downlink and
    // the agent goes out to Telephony Tx, so neither passes through the
    // speaker any more.  Both sides are therefore mixed here in software.
    //
    // The microphone stays muted throughout — this only ever plays audio out.
    @Volatile private var monitorTrack: AudioTrack? = null
    /** Most recent caller frame, 16-bit mono at [captureRate], for mixing. */
    @Volatile private var lastCallerFrame: ByteArray? = null

    /** Silence the agent towards the caller without disturbing the bridge.
     *  Audio keeps flowing in both directions and the call stays up; only the
     *  audio written towards the modem is zeroed.  Intended for cutting the
     *  agent off mid-sentence when it is saying something it should not. */
    @Volatile private var agentMuted = false

    // Jitter buffer for received frames.  Capacity 8 (160ms) absorbs
    // network jitter without audible gaps.  The playback loop drains
    // excess above 5 (100ms) to bound latency — acceptable for a GSM
    // bridge that already has 100-200ms of inherent GSM latency.
    // Previous capacity=3 was too aggressive: any slight jitter caused
    // packet drops and choppy audio.
    /** Jitter buffer, in 20ms frames.
     *
     *  Four frames was too tight to be workable.  Measured over a 33s call it
     *  sat at 3-4 — pinned at capacity — and logged 87 dropped frames *and* 90
     *  underruns out of ~1650: about three audible glitches a second, because
     *  at that depth every burst overflows and every gap empties it.  There is
     *  no depth that both absorbs real network jitter and holds 80ms.
     *
     *  So: room to absorb a burst, and a high-water mark to shed the excess a
     *  frame at a time so the burst does not park permanent latency in the
     *  queue.  Shedding one frame occasionally is the same kind of loss as the
     *  overflow drop, but it happens at a chosen depth instead of at the
     *  ceiling, and it is two orders of magnitude rarer. */
    private val jitterBuffer = ArrayBlockingQueue<ByteArray>(JITTER_CAPACITY)

    // Inactivity tracking
    @Volatile private var lastFrameReceivedTime = 0L
    private val FRAME_TIMEOUT_MS = 30_000L  // 30 seconds without a far-end frame = dead call

    // Packet counters and audio diagnostics
    @Volatile var txPacketCount = 0L; private set
    @Volatile var rxPacketCount = 0L; private set
    @Volatile var playbackFrames = 0L; private set
    @Volatile var captureRms = 0; private set
    @Volatile var playbackRms = 0; private set
    @Volatile var rawCaptureRms = 0; private set  // Before echo gate — 0 means source is silent
    /** Frames discarded because the jitter buffer was already full — the far
     *  end is arriving faster than playback drains it, or in bursts. */
    @Volatile private var rxDropped = 0L
    /** Frames of silence written because the jitter buffer was empty — the
     *  audible gaps, i.e. late or lost packets from the far end. */
    @Volatile private var underruns = 0L
    /** Frames shed at the high-water mark to keep the queue from parking
     *  latency after a burst. */
    @Volatile private var trimmedFrames = 0L
    @Volatile var audioSourceName = "none"; private set
    @Volatile private var playbackUsageName = "MEDIA"

    // Capture and playback rates may differ.  VOICE_CALL on MSM8930
    // only initializes at 8 kHz; WebRTC playback frames are 16 kHz PCM.
    private var captureRate = 8000
    private var playbackRate = 8000

    // Audio session ID from AudioRecord (for logging/diagnostics)
    private var audioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE

    // Silence detection: track audio source IDs that initialize but deliver
    // no usable frames. Remaining digital sources are tried first; acoustic
    // inputs only appear when the local profile explicitly allows them.
    private val silentSourceIds = mutableSetOf<Int>()
    // Set once every permitted source has been judged silent.  Digital
    // capture cannot tell a dead source from a quiet line: on an incoming
    // call nobody has to speak in the first seconds after answer (there is
    // no ringback, unlike an outgoing call), so every source "fails" and the
    // call used to be torn down about five seconds in.  Once the list is
    // exhausted the preferred source is reopened and kept; silence from then
    // on is dead air, never a reason to end the call.
    @Volatile private var holdCaptureSource = false
    @Volatile private var currentSourceId: Int = -1
    @Volatile private var currentSourceIsDigital: Boolean = false
    private data class SourceConfig(
        val source: Int,
        val name: String,
        val rate: Int,
        val digital: Boolean,
    )
    private data class AudioCapabilities(
        val telephonyInput: AudioDeviceInfo?,
        val telephonyOutput: AudioDeviceInfo?,
    )
    @Volatile private var audioInitFailureReason: String? = null
    // Silence detection thresholds — only counted during non-echo periods
    // (when decayingPlaybackRms <= echoGateThreshold) to avoid false resets
    // from incall_music echo leaking back through VOICE_CALL capture.
    private val SILENCE_RMS_THRESHOLD = 10   // Below this = truly dead source (ADC noise floor)

    /** How long to let a source settle before judging its frame rate. */
    private val RATE_CHECK_AFTER_MS = 4000L

    /** Below this share of the expected 50 frames/s, the source is failing.
     *  Generous on purpose: a healthy source sits at ~100%, and the case this
     *  catches measured 70%, so anything near the threshold is unambiguous. */
    private val RATE_MIN_PERCENT = 85L
    private val SILENCE_FRAME_LIMIT get() = profile.captureSilenceFrames

    override var listener: Listener? = null

    interface Listener {
        fun onAudioStarted()
        fun onAudioStopped()
        fun onAudioError(error: String)
        /** Telecom bridge callback for remote DTMF digits. */
        fun onDtmfTone(digit: Char, active: Boolean) {}
        fun onAudioTimeout() {}  // No far-end frame for FRAME_TIMEOUT_MS
        fun onAudioStats(stats: String) {}  // Periodic detailed stats
    }

    override fun start() {
        val activeProfile = profile
        Log.i(TAG, "Audio profile: name=${activeProfile.name}, generic=${activeProfile.useGenericRouting}, " +
            "rxRequired=${activeProfile.requireTelephonyRx}, txRequired=${activeProfile.requireTelephonyTx}, " +
            "micFallback=${activeProfile.allowMicFallback}")
        if (activeProfile.configError != null) {
            val error = "Local audio profile configuration is unavailable: ${activeProfile.configError}"
            Log.e(TAG, error)
            listener?.onAudioStats("Audio capability failure: invalid local profile")
            listener?.onAudioError(error)
            return
        }
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            listener?.onAudioError("RECORD_AUDIO permission is not granted")
            return
        }
        if (running.getAndSet(true)) return
        Log.i(TAG, "Starting WebRTC cellular PCM bridge at 16 kHz")

        var audioFailureReason: String? = null
        val audioReady = try {
            initAudio()
        } catch (e: SecurityException) {
            Log.e(TAG, "Audio initialization denied by platform permission policy", e)
            audioFailureReason = e.message
            false
        } catch (e: Exception) {
            Log.e(TAG, "Audio initialization failed: ${e.message}", e)
            audioFailureReason = e.message
            false
        }
        if (!audioReady) {
            val reason = audioFailureReason ?: audioInitFailureReason
            val error = reason?.let { "Audio initialization failed: $it" }
                ?: "Required audio capability is unavailable"
            Log.e(TAG, error)
            listener?.onAudioStats("Audio capability failure: $error")
            stop()
            listener?.onAudioError(error)
            return
        }

        lastFrameReceivedTime = System.currentTimeMillis()
        externalPcm.onDecodedFrame = { frame ->
            if (running.get()) {
                if (!jitterBuffer.offer(frame)) { jitterBuffer.poll(); jitterBuffer.offer(frame); rxDropped++ }
                rxPacketCount++
                lastFrameReceivedTime = System.currentTimeMillis()
            }
        }
        workers = listOf(
            loopThread("Audio-Play") { playbackLoop() },
            loopThread("Audio-Capt") { captureInitAndLoop() },
            loopThread("Audio-Timeout") { timeoutLoop() }
        )
        workers.forEach { it.start() }

        listener?.onAudioStarted()
    }

    /**
     * A loop thread that cannot take the process down with it.
     *
     * [stop] interrupts these threads, and several of them sleep outside a
     * try/catch — an InterruptedException escaping a thread body reaches the
     * default uncaught handler, which on Android kills the process.  Anything
     * thrown after the session has been stopped is expected and just noted.
     */
    private fun loopThread(name: String, body: () -> Unit): Thread =
        Thread({
            try {
                body()
            } catch (e: InterruptedException) {
                Log.d(TAG, "$name interrupted")
                if (running.get() && (name == "Audio-Play" || name == "Audio-Capt")) {
                    failAudioMedia("$name was interrupted while the media session was active")
                }
            } catch (e: Throwable) {
                if (running.get()) Log.e(TAG, "$name died: ${e.javaClass.simpleName}: ${e.message}", e)
                else Log.d(TAG, "$name ended during shutdown: ${e.message}")
                if (running.get() && (name == "Audio-Play" || name == "Audio-Capt")) {
                    failAudioMedia("$name failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }, name)

    /**
     * Initialize AudioRecord and AudioTrack according to the selected local
     * profile. The generic profile requests Android telephony routes and does
     * not configure OEM mixer controls.
     */
    private fun initAudio(): Boolean {
        // Before anything is opened: in-call recording and the per-session
        // voice mutes are gated on the HAL's is_call_active flag, and the HAL
        // chooses the input usecase when the record stream is created.  Telling
        // it after AudioRecord exists is too late — VOICE_CALL is already bound
        // to a plain capture path and returns silence.
        setHalCallState(2)

        // WebRTC hands over 16 kHz frames.
        playbackRate = 16000

        val wideband = true
        val capabilities = logAudioCapabilities()
        if (profile.requireTelephonyRx && capabilities.telephonyInput == null) {
            audioInitFailureReason = "Required TYPE_TELEPHONY input is not exposed by AudioManager"
            return false
        }
        if (profile.requireTelephonyTx && capabilities.telephonyOutput == null) {
            audioInitFailureReason = "Required TYPE_TELEPHONY output is not exposed by AudioManager"
            return false
        }
        val configs = buildCaptureConfigs(wideband)

        var record: AudioRecord? = null
        var usedRate = 8000

        // Permission is granted by the system/Magisk package policy. This
        // method only probes whether the selected audio source is available;
        // it must never rewrite app-ops or bypass PermissionController.
        for (cfg in configs) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    cfg.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) {
                    Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate}: invalid minBuf=$minBuf")
                    continue
                }
                val bufSize = minBuf.coerceAtLeast(cfg.rate / 50 * 2 * 2) // 40ms (two frames)
                if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    throw SecurityException("RECORD_AUDIO permission was revoked during setup")
                }
                val rec = try {
                    AudioRecord(
                        cfg.source, cfg.rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize
                    )
                } catch (e: SecurityException) {
                    if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        throw SecurityException("RECORD_AUDIO permission was revoked during setup", e)
                    }
                    Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate} denied: ${e.message}")
                    continue
                }
                if (cfg.digital && profile.captureFromTelephonyRx) {
                    val accepted = routeCaptureToTelephonyRx(rec)
                    if (profile.requireTelephonyRx && !accepted) {
                        rec.release()
                        audioInitFailureReason = "AudioRecord rejected the required TYPE_TELEPHONY input route"
                        return false
                    }
                }
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    record = rec
                    usedRate = cfg.rate
                    audioSourceName = cfg.name
                    currentSourceId = cfg.source
                    currentSourceIsDigital = cfg.digital
                    Log.i(TAG, "AudioRecord opened: ${cfg.name} @ ${cfg.rate}Hz " +
                        "digital=${cfg.digital}; audio delivery remains unproven until reads (buf=$bufSize)")
                    break
                } else {
                    Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate}: state=${rec.state}")
                    rec.release()
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate} denied: ${e.message}")
                if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    throw SecurityException("RECORD_AUDIO permission was revoked during setup", e)
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate} failed: ${e.message}")
            }
        }

        if (record != null) {
            audioRecord = record
            audioSessionId = record.audioSessionId
            captureRate = usedRate

            // Diagnostic sweep — off unless explicitly enabled, see
            // audioDiagnosticsEnabled().
            if (audioDiagnosticsEnabled()) logCaptureDiagnostics(record)
        }

        // Minimum buffer for lowest latency.  incall_music injects
        // AudioTrack output digitally into the modem uplink — there is no
        // acoustic speaker→mic path, so deep-buffer headroom is unnecessary.
        // Writing silence when the jitter buffer is empty prevents underruns.
        val playChannelMask = if (profile.playbackStereo)
            AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        playbackChannels = if (profile.playbackStereo) 2 else 1
        val minPlayBuf = AudioTrack.getMinBufferSize(
            playbackRate, playChannelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        // A minimum-sized buffer qualifies for the fast path, which the
        // Qualcomm HAL serves on MultiMedia5 — a front-end whose audio goes to
        // the speaker, not into the voice uplink.  Asking for a larger buffer
        // pushes the HAL to deep-buffer-playback on MultiMedia1, which is the
        // front-end the Incall_Music mixer injects from.
        val playBufSize = if (profile.playbackBufferMs > 0) {
            maxOf(minPlayBuf, playbackRate * 2 * playbackChannels * profile.playbackBufferMs / 1000)
        } else {
            minPlayBuf
        }

        // Profile-controlled playback usage:
        // USAGE_MEDIA (default / MSM8930): Maps to STREAM_MUSIC.  Qualcomm's
        // incall_music_enabled=true injects STREAM_MUSIC into voice TX.
        // USAGE_VOICE_COMMUNICATION (Exynos 9820): Maps to STREAM_VOICE_CALL.
        // Samsung's HAL may route this into the modem uplink when a voice
        // call is active.  On Exynos, no incall_music mixer exists, so
        // USAGE_MEDIA only plays on the speaker without reaching the modem.
        //
        // IMPORTANT: Do NOT use PERFORMANCE_MODE_LOW_LATENCY here.
        // Low-latency forces the HAL to use "low-latency-playback" usecase
        // which maps to MultiMedia5.  On MSM8930 (Galaxy S4 Mini), only
        // MultiMedia1 and MultiMedia2 have Incall_Music mixer controls.
        // MultiMedia5 has no incall_music mixer, so audio plays on the
        // earpiece but is NEVER injected into the modem uplink.
        // Using default (deep-buffer-playback → MultiMedia1) ensures the
        // Incall_Music Audio Mixer MultiMedia1 routes audio to the caller.
        val requestedUsage = profile.playbackUsage
        val contentType = when (requestedUsage) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> AudioAttributes.CONTENT_TYPE_SPEECH
            -1, AudioAttributes.USAGE_MEDIA -> AudioAttributes.CONTENT_TYPE_MUSIC
            else -> throw IllegalArgumentException(
                "Unsupported audio profile playback usage: $requestedUsage"
            )
        }
        val attributes = AudioAttributes.Builder().apply {
            // Pass only the documented public constants to setUsage. An
            // unexpected OEM profile value is a configuration error; do not
            // coerce it to a different stream and silently change call routing.
            when (requestedUsage) {
                -1, AudioAttributes.USAGE_MEDIA -> {
                    playbackUsageName = "MEDIA"
                    setUsage(AudioAttributes.USAGE_MEDIA)
                }
                AudioAttributes.USAGE_VOICE_COMMUNICATION -> {
                    playbackUsageName = "VOICE_COMMUNICATION"
                    setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                }
                else -> throw IllegalArgumentException(
                    "Unsupported audio profile playback usage: $requestedUsage"
                )
            }
            setContentType(contentType)
        }.build()
        // Must happen before the track exists on HALs that pick the output
        // usecase at creation time — see DeviceProfile.incallMusicBeforeTrack.
        if (profile.incallMusicBeforeTrack) {
            enableIncallMusic()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                attributes
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(playbackRate)
                    .setChannelMask(playChannelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply {
                if (profile.playbackLowLatency) {
                    // Deliberately no setBufferSizeInBytes here: asking for
                    // low latency and then handing the framework a large
                    // buffer is contradictory, and the request is silently
                    // ignored.  Let it size the buffer itself.
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                } else {
                    setBufferSizeInBytes(playBufSize)
                }
            }
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            audioInitFailureReason = "AudioTrack could not initialize (state=${track.state})"
            track.release()
            return false
        }
        audioTrack = track

        if (profile.playbackToTelephonyTx || profile.requireTelephonyTx) {
            val accepted = routeToTelephonyTx(track)
            if (profile.requireTelephonyTx && !accepted) {
                audioInitFailureReason = "AudioTrack rejected the required TYPE_TELEPHONY output route"
                return false
            }
        }

        // No platform AEC — AudioTrack is on USAGE_MEDIA (different stream
        // from AudioRecord), so platform AEC can't reference it anyway.
        // For VOICE_DOWNLINK, AEC was over-canceling (capRMS dropped from 252 to ~5).
        // Echo cancellation is handled by the host's WebRTC audio processing.

        // Log what was actually granted, not what was asked for: the
        // low-latency request can be refused silently.
        val grantedFrames = try { track.bufferSizeInFrames } catch (_: Exception) { -1 }
        val grantedMs = if (grantedFrames > 0) grantedFrames * 1000 / playbackRate else -1
        Log.i(TAG, "Audio init: playRate=$playbackRate playUsage=$playbackUsageName " +
            "playBuf=${grantedFrames}f/${grantedMs}ms (asked=$playBufSize min=$minPlayBuf " +
            "lowLatency=${profile.playbackLowLatency}) ch=$playbackChannels " +
            "capture=${if (audioRecord != null) audioSourceName else "deferred"} profile=${profile.name}")
        return true
    }

    /**
     * Initialize AudioRecord with retries, then run the capture loop.
     *
     * On cold boot, AudioFlinger refuses to create record tracks for
     * 10-30+ seconds after the voice call starts ("could not create record
     * track, status: -1").  The audio HAL needs time to fully initialize
     * the recording infrastructure after the modem voice path starts.
     *
     * Playback runs in a separate thread and
     * starts immediately.  This method retries capture init for up to 30s
     * so the caller hears the agent right away, even if the reverse
     * direction (GSM→host) takes longer to come up.
     */
    private fun captureInitAndLoop() {
        // Fast path: AudioRecord was already initialized in initAudio (warm boot)
        if (audioRecord != null) {
            if (captureLoop() || !running.get()) return
            // Source produced silence for 3s — fall through to try alternatives
            Log.w(TAG, "Primary source $audioSourceName silent, searching for working source (skip: $silentSourceIds)")
        }

        // Deferred/fallback source finding + capture loop.
        // Rebuilds the source list each iteration, excluding sources detected
        // as silent.  This handles both cold-boot AudioRecord unavailability
        // AND sources that initialize but deliver no audio (e.g., VOICE_CALL
        // on Exynos 9820 where the HAL doesn't route voice data to capture).
        val wideband = true
        val maxFallbacks = 10  // Enough cycles for every digital and explicit acoustic candidate

        for (fallback in 0 until maxFallbacks) {
            if (!running.get()) return

            // Build source list, filtering out sources that produced silence
            var configs = buildCaptureConfigs(wideband)
            if (configs.isEmpty() && silentSourceIds.isNotEmpty() && !holdCaptureSource) {
                val msg = "Every capture source was silent (${silentSourceIds.size}); the line may just be " +
                    "quiet — keeping the preferred source instead of ending the call"
                Log.w(TAG, msg)
                listener?.onAudioStats(msg)
                holdCaptureSource = true
                silentSourceIds.clear()
                configs = buildCaptureConfigs(wideband)
            }
            if (configs.isEmpty()) {
                Log.e(TAG, "All capture sources produced silence — no working source found")
                break
            }

            if (fallback > 0 || audioRecord == null) {
                Log.w(TAG, "Trying capture sources (fallback=$fallback): ${configs.joinToString { it.name }} (silent: $silentSourceIds)")
            }

            // Try to initialize AudioRecord with one of the remaining sources
            val maxAttempts = if (fallback == 0 && audioRecord == null) 15 else 3
            var sourceFound = false

            for (attempt in 1..maxAttempts) {
                if (!running.get()) return

                for (cfg in configs) {
                    if (cfg.source in silentSourceIds) continue
                    try {
                        val minBuf = AudioRecord.getMinBufferSize(
                            cfg.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                        )
                        if (minBuf <= 0) continue
                        val bufSize = minBuf.coerceAtLeast(cfg.rate / 50 * 2 * 2)
                        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            Log.e(TAG, "RECORD_AUDIO permission was revoked during capture setup")
                            stop()
                            listener?.onAudioError("RECORD_AUDIO permission was revoked")
                            return
                        }
                        val rec = try {
                            AudioRecord(
                                cfg.source, cfg.rate,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                bufSize
                            )
                        } catch (e: SecurityException) {
                            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                Log.e(TAG, "RECORD_AUDIO permission was revoked during capture setup", e)
                                stop()
                                listener?.onAudioError("RECORD_AUDIO permission was revoked")
                                return
                            }
                            Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate} denied: ${e.message}")
                            continue
                        }
                        if (cfg.digital && profile.captureFromTelephonyRx) {
                            val accepted = routeCaptureToTelephonyRx(rec)
                            if (profile.requireTelephonyRx && !accepted) {
                                rec.release()
                                failAudioMedia("AudioRecord rejected the required TYPE_TELEPHONY input route")
                                return
                            }
                        }
                        if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            if (!running.get()) { rec.release(); return }
                            audioRecord = rec
                            audioSessionId = rec.audioSessionId
                            captureRate = cfg.rate
                            audioSourceName = cfg.name
                            currentSourceId = cfg.source
                            currentSourceIsDigital = cfg.digital
                            Log.i(TAG, "AudioRecord opened: ${cfg.name} @ ${cfg.rate}Hz " +
                                "digital=${cfg.digital} (delivery is pending readback, fallback=$fallback attempt=$attempt)")
                            sourceFound = true
                            break
                        } else {
                            Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate}: state=${rec.state}")
                            rec.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate} failed: ${e.message}")
                    }
                }

                if (sourceFound) break

                if (attempt < maxAttempts && running.get()) {
                    Log.w(TAG, "All remaining sources failed (fallback=$fallback attempt=$attempt/$maxAttempts), retrying")
                }
            }

            if (!sourceFound) {
                Log.e(TAG, "Could not initialize any remaining capture source")
                break
            }

            // Run capture loop — returns false if silence detected
            Log.i(TAG, "Capture ready (fallback=$fallback), starting capture loop with $audioSourceName")
            if (captureLoop() || !running.get()) return
            // Silence detected — silentSourceIds updated, loop again with remaining sources
            Log.w(TAG, "Source $audioSourceName silent after 3s (skip: $silentSourceIds), trying next fallback")
        }

        val reason = if (profile.allowMicFallback) {
            "No permitted digital or explicitly allowed microphone capture source is available"
        } else {
            "No permitted digital telephony capture source delivered usable audio"
        }
        failAudioMedia(reason)
    }

    /** Build the single prioritized source list used by initial and fallback setup. */
    private fun buildCaptureConfigs(wideband: Boolean): List<SourceConfig> {
        val configs = mutableListOf<SourceConfig>()
        fun add(source: Int, name: String, rate: Int, digital: Boolean) {
            configs.add(SourceConfig(source, name, rate, digital))
        }

        // A profile that explicitly says its downlink source works can prefer
        // that digital source to avoid VOICE_CALL's mixed uplink. The generic
        // profile probes both digital Android telephony sources in order.
        if (profile.voiceDownlinkWorks && !profile.useGenericRouting) {
            if (wideband) add(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK@16k", 16000, true)
            add(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000, true)
        } else {
            if (wideband) add(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL@16k", 16000, true)
            add(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000, true)
            if (wideband) add(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK@16k", 16000, true)
            add(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000, true)
        }

        if (profile.allowMicFallback) {
            val acoustic = mutableListOf<SourceConfig>()
            fun addMic(source: Int, name: String) {
                acoustic.add(SourceConfig(source, name, 8000, digital = false))
            }
            if (profile.preferUnprocessedMic) {
                addMic(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED")
                addMic(MediaRecorder.AudioSource.CAMCORDER, "CAMCORDER")
            }
            if (profile.preferVoiceRecognition) {
                addMic(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION")
            }
            addMic(MediaRecorder.AudioSource.MIC, "MIC")
            if (!profile.preferVoiceRecognition) {
                addMic(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION")
            }
            addMic(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION")
            configs.addAll(acoustic)
        }

        return configs.filterNot { it.source in silentSourceIds }
    }

    /**
     * Stop the session and release the audio devices.
     *
     * The release has to happen *after* the loops have left `record.read()`
     * and `track.write()` — releasing an AudioRecord while another thread is
     * inside a read is a use-after-free in the native layer, which the broad
     * catch in the loops was quietly papering over.
     *
     * The waiting is done on its own thread because stop() is reached from
     * tearDown(), which runs on the main thread via the Telecom callback;
     * blocking there for up to four joins is how ANRs happen.
     */
    override fun stop() {
        externalPcm.clear()
        if (!running.getAndSet(false)) return
        Log.i(TAG, "Stopping cellular audio session")

        setMonitorEnabled(false)

        val ws = workers
        workers = emptyList()
        val rec = audioRecord
        val trk = audioTrack
        audioRecord = null
        audioTrack = null
        jitterBuffer.clear()

        Thread({
            val me = Thread.currentThread()
            ws.forEach { if (it !== me) it.interrupt() }
            ws.forEach {
                if (it === me) return@forEach
                try { it.join(JOIN_TIMEOUT_MS) } catch (_: InterruptedException) {}
                if (it.isAlive) Log.w(TAG, "${it.name} still running after ${JOIN_TIMEOUT_MS}ms")
            }
            rec?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
            trk?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
            setHalCallState(1)
            Log.i(TAG, "Cellular audio session fully released")
            listener?.onAudioStopped()
        }, "Audio-Stop").start()
    }

    // ── Capture: VOICE_CALL → echo gate → gain → WebRTC ──

    // Audio parameters from device profile
    private val profile get() = GsmCallManager.profile
    private val captureGain get() = profile.captureGain
    /** The profile's digital gain with the manual trim from settings applied. */
    private val playbackGain: Double
        get() = profile.playbackGain * GsmCallManager.agentGainFactor

    // Double-talk detection: VOICE_CALL captures uplink+downlink from
    // the modem DSP.  The uplink contains the host's voice (injected
    // via incall_music).  Instead of a hard echo gate (which made the
    // bridge half-duplex — caller COMPLETELY silenced during agent speech),
    // we adaptively estimate the echo level and detect when the caller is
    // speaking simultaneously (double-talk / barge-in).
    //
    // How it works: incall_music injects AudioTrack digitally into the
    // modem uplink with a consistent gain ratio.  We track that ratio
    // (echoGainRatio = captureRMS / playbackRMS) during echo-only frames.
    // When captureRMS significantly exceeds the expected echo level,
    // the caller must be speaking — forward the audio (some echo leaks
    // but the caller is audible).  This enables full-duplex barge-in.
    private val echoGateThreshold get() = profile.echoGateThreshold
    @Volatile private var currentPlaybackActive = false

    // Decaying echo reference: instead of a hard on/off echo gate, we
    // track a decaying playback RMS that persists through brief inter-word
    // gaps.  When the agent pauses between words (50-100ms), the echo
    // tail in the capture pipeline still has energy.  Without decay, the
    // gate flips off and the echo tail passes the noise gate, reaching
    // the agent as "caller speech" = false interruption.
    //
    // Decay factor 0.80 per 20ms frame:
    //   50ms (2-3 frames): ~64% of peak → echo tail suppressed
    //   100ms (5 frames):  ~33% → still suppressed
    //   200ms (10 frames): ~11% → fading, real speech passes easily
    //   300ms (15 frames): ~3%  → effectively zero
    // This allows barge-in after ~200ms while suppressing echo tails.
    private var decayingPlaybackRms = 0

    // Adaptive echo gain: ratio of capture RMS to playback RMS during
    // confirmed echo-only frames.  Updated via exponential moving average.
    // Initial 1.0 = assume 1:1 coupling; adapts to actual modem DSP gain.
    @Volatile private var echoGainRatio = 1.0f
    private var echoGainSamples = 0
    private val DOUBLE_TALK_RATIO get() = profile.doubleTalkRatio

    // Noise gate: below this RMS, send silence instead of captured audio.
    // Threshold is device-specific (modem DSP noise floor varies).
    private val noiseGateThreshold get() = profile.noiseGateThreshold

    // Diagnostic counters: track how frames are classified for stats logging
    @Volatile private var echoGatedFrames = 0L
    @Volatile private var forwardedFrames = 0L
    @Volatile private var doubleTalkFrames = 0L  // caller spoke during agent playback

    // CPU tracking: process CPU ticks at last sample for delta calculation
    private var lastCpuTicks = 0L
    private var lastCpuSampleMs = 0L

    /**
     * Capture loop: read from AudioRecord, gate, hand to WebRTC.
     * Returns true on normal exit (call ended), false if the source was
     * detected as silent (rawCapRMS near zero for 3 seconds) and should
     * be replaced with a fallback source.
     */
    private fun captureLoop(): Boolean {
        val record = audioRecord ?: return false

        record.startRecording()

        if (profile.requireTelephonyRx && currentSourceIsDigital) {
            val routed = waitForTelephonyRoute { record.routedDevice }
            if (!running.get()) return true
            if (routed?.type != AudioDeviceInfo.TYPE_TELEPHONY) {
                failAudioMedia(
                    "Digital capture route did not settle on TYPE_TELEPHONY " +
                        "(actual=${routed?.let(::describeDevice) ?: "none"})"
                )
                return true
            }
        }

        // Re-assert in-call capture routing now that the stream exists — see
        // DeviceProfile.mixerCaptureCmd.  Off the capture thread, because the
        // su round-trip takes ~100ms and would stall the first frames.
        val capCmd = DeviceProfile.resolveCmd(profile.mixerCaptureCmd)
        if (capCmd.isNotEmpty()) {
            Thread({
                val out = RootShell.execForOutput(capCmd, timeoutMs = 8000)
                Log.i(TAG, "Mixer capture routing: $out")
            }, "mixer-capture").start()
        }

        Log.i(TAG, "Capture route: source=$audioSourceName digital=$currentSourceIsDigital " +
            "routedFrom=${record.routedDevice?.let(::describeDevice) ?: "none"}")
        Log.i(TAG, "Capture started: source=$audioSourceName capRate=$captureRate session=$audioSessionId " +
            "gain=${captureGain}x profile=${profile.name} state=${record.recordingState}; deliveryProbe=pending")
        // Also report via the stats callback so it appears in the app log viewer
        listener?.onAudioStats("Capture opened: source=$audioSourceName rate=$captureRate " +
            "digital=$currentSourceIsDigital route=${record.routedDevice?.type ?: "none"}; audio delivery pending")

        // Buffer: 20ms of PCM at the actual capture sample rate
        val samplesPerFrame = captureRate / 50  // 160 @ 8kHz, 320 @ 16kHz
        val pcmBuf = ByteArray(samplesPerFrame * 2)
        var silenceFrameCount = 0
        // Whether this source has ever delivered audio.  The silence detector
        // answers "does this source work at all", which is only an open
        // question until the first frame arrives; after that, quiet means the
        // line is quiet.
        var sourceProven = false
        var lastDeadAirLog = 0L

        // Frame-rate watchdog.  The silence detector asks "is this source
        // delivering audio"; this asks "is it delivering it on time".  A
        // source can pass the first and fail the second, which is what a
        // bursty VOICE_CALL does, and only the second failure produces the
        // gappy uplink that makes an agent talk over itself.
        val captureStartMs = System.currentTimeMillis()
        var framesCaptured = 0L
        var rateChecked = false

        while (running.get()) {
            try {
                val read = record.read(pcmBuf, 0, pcmBuf.size)
                if (read <= 0) continue
                framesCaptured++

                // Checked once, on a window early in the call: switching
                // sources mid-call is disruptive, and a source that is going
                // to stall does so from the start.  A proven source is still
                // eligible -- "proven" only means it produced sound, which is
                // exactly the state this failure hides behind.
                if (!rateChecked && !holdCaptureSource) {
                    val elapsed = System.currentTimeMillis() - captureStartMs
                    if (elapsed >= RATE_CHECK_AFTER_MS) {
                        rateChecked = true
                        val expected = elapsed / 20                 // 50 frames/s
                        if (expected > 0 && framesCaptured * 100 / expected < RATE_MIN_PERCENT) {
                            val pct = framesCaptured * 100 / expected
                            val msg = "Source $audioSourceName too slow: $framesCaptured/$expected " +
                                "frames ($pct%) in ${elapsed}ms — trying fallback"
                            Log.w(TAG, msg)
                            listener?.onAudioStats(msg)
                            silentSourceIds.add(currentSourceId)
                            try { record.stop() } catch (_: Exception) {}
                            record.release()
                            audioRecord = null
                            return false
                        }
                    }
                }

                // Measure raw capture level BEFORE echo gate for diagnostics.
                // If rawCaptureRms=0, the audio source itself is silent
                // (HAL/modem not providing audio).  If rawCaptureRms>0 but
                // captureRms=0, the echo gate is suppressing.
                rawCaptureRms = pcmRms(pcmBuf)
                if (monitorTrack != null) lastCallerFrame = pcmBuf.copyOf()

                // Silence detection: only count during non-echo periods (when
                // decayingPlaybackRms <= echoGateThreshold).  incall_music echo
                // leaks back through VOICE_CALL capture, spiking rawCapRMS
                // above the threshold during agent speech — this would reset a
                // naive counter even though the source delivers NO caller audio.
                // By only counting non-echo frames, we accurately detect dead
                // sources regardless of whether the agent is speaking.
                val noEchoPeriod = decayingPlaybackRms <= echoGateThreshold
                if (noEchoPeriod) {
                    if (rawCaptureRms < SILENCE_RMS_THRESHOLD) {
                        silenceFrameCount++
                        if (silenceFrameCount == 10 || silenceFrameCount == 20) {
                            Log.w(TAG, "Source $audioSourceName low audio (no-echo): rawCapRMS=$rawCaptureRms silence=${silenceFrameCount}/${SILENCE_FRAME_LIMIT} frames")
                        }
                        if (silenceFrameCount >= SILENCE_FRAME_LIMIT) {
                            if (sourceProven || holdCaptureSource) {
                                // Dead air, not a dead source.  Tearing the
                                // AudioRecord down here and blacklisting the
                                // source was catastrophic mid-call: with
                                // playbackLeaksIntoCapture off every quiet
                                // frame counts, so one limit's worth of nobody
                                // speaking — six seconds on this profile —
                                // retired a working source.  A few of those
                                // exhausted the source list and capture stayed
                                // dead for the rest of the call: the agent
                                // stopped hearing the caller and never got
                                // them back.
                                val now = System.currentTimeMillis()
                                if (now - lastDeadAirLog > 30_000) {
                                    lastDeadAirLog = now
                                    val msg = "Dead air ${silenceFrameCount / 50}s on " +
                                        "$audioSourceName (source proven — keeping it)"
                                    Log.w(TAG, msg)
                                    listener?.onAudioStats(msg)
                                }
                                silenceFrameCount = 0
                            } else {
                                val msg = "Source $audioSourceName SILENT ($silenceFrameCount non-echo frames) — trying fallback"
                                Log.w(TAG, msg)
                                listener?.onAudioStats(msg)
                                silentSourceIds.add(currentSourceId)
                                try { record.stop() } catch (_: Exception) {}
                                record.release()
                                audioRecord = null
                                return false  // Signal: try another source
                            }
                        }
                    } else {
                        // Source delivered audio during non-echo → working
                        silenceFrameCount = 0
                        if (!sourceProven) {
                            val route = record.routedDevice?.let(::describeDevice) ?: "none"
                            val message = "Capture readback observed non-silent frames: source=$audioSourceName " +
                                "digital=$currentSourceIsDigital route=$route"
                            Log.i(TAG, message)
                            listener?.onAudioStats(message)
                        }
                        sourceProven = true
                    }
                }
                // During echo periods: don't update counter (can't distinguish
                // caller audio from incall_music echo)

                // Double-talk-aware gating: replaces the old hard echo gate
                // that made the bridge half-duplex (caller completely silenced
                // during agent speech).  Now uses adaptive echo level estimation
                // to detect when the caller is speaking over the agent.
                //
                // VOICE_CALL captures uplink+downlink.  incall_music injects
                // the agent's voice digitally into the uplink with a consistent
                // gain ratio.  We track that ratio and detect when capture energy
                // exceeds the expected echo — that excess is the caller's voice.
                // Update decaying echo reference: tracks playback level
                // through brief inter-word gaps to suppress echo tails.
                if (currentPlaybackActive && playbackRms > 0) {
                    decayingPlaybackRms = playbackRms
                } else if (decayingPlaybackRms > 0) {
                    decayingPlaybackRms = (decayingPlaybackRms * 0.80).toInt()
                }

                val shouldForward: Boolean
                if (profile.playbackLeaksIntoCapture &&
                    decayingPlaybackRms > echoGateThreshold) {
                    // Agent is speaking (or echo tail still decaying) —
                    // check for double-talk (barge-in).
                    val expectedEcho = (echoGainRatio * decayingPlaybackRms).toInt()
                        .coerceAtLeast(noiseGateThreshold)
                    if (rawCaptureRms > (expectedEcho * DOUBLE_TALK_RATIO).toInt()) {
                        // Double-talk: caller speaking over agent — forward.
                        // Some echo leaks through but caller is audible.
                        shouldForward = true
                        doubleTalkFrames++
                    } else {
                        // Echo-only: agent speaking, caller silent — send silence.
                        // Update echo gain estimate from confirmed echo frames.
                        if (currentPlaybackActive && playbackRms > 500) {
                            val r = rawCaptureRms.toFloat() / playbackRms
                            echoGainRatio = echoGainRatio * 0.95f + r * 0.05f
                            echoGainSamples++
                        }
                        shouldForward = false
                        echoGatedFrames++
                    }
                } else {
                    // No noise gate: the listener is a person, and a per-frame
                    // hard gate zeroes every quiet 20 ms of speech (syllable
                    // tails, soft consonants) -- on the generic profile's 300
                    // threshold nearly half the frames of a normal call, heard
                    // as a constant fast stutter.
                    // Caller speaking, agent silent — forward normally.
                    shouldForward = true
                }

                if (shouldForward) {
                    // Apply gain boost
                    if (captureGain > 1) {
                        for (i in 0 until read / 2) {
                            val lo = pcmBuf[i * 2].toInt() and 0xFF
                            val hi = pcmBuf[i * 2 + 1].toInt()
                            val sample = ((hi shl 8) or lo) * captureGain
                            val clamped = sample.coerceIn(-32768, 32767)
                            pcmBuf[i * 2] = (clamped and 0xFF).toByte()
                            pcmBuf[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
                        }
                    }
                    captureRms = pcmRms(pcmBuf)
                    forwardedFrames++
                } else {
                    java.util.Arrays.fill(pcmBuf, 0, read, 0.toByte())
                    captureRms = 0
                }

                externalPcm.capture(pcmBuf, captureRate)
                txPacketCount++
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Capture error: ${e.message}")
            }
        }
        return true  // Normal exit (call ended)
    }

    // ── Inactivity timeout ─────────────────────────────

    private fun timeoutLoop() {
        // Re-toggle incall_music after speaker route has settled.
        try { Thread.sleep(3_000) } catch (_: InterruptedException) { return }
        if (running.get()) {
            reToggleIncallMusic()
        }

        var tick = 0
        while (running.get()) {
            try {
                // Close together at first, then settle down.  At a flat 15s a
                // call that lasted sixteen seconds ended before a single stats
                // line was emitted, so there was nothing at all to diagnose it
                // with afterwards — which is exactly when it is wanted.
                Thread.sleep(if (tick < 3) 5_000L else 15_000L)
                tick++

                // Stats.  The stream volumes and mic-mute state used to be
                // queried and appended here every cycle; they are set once at
                // bridge setup and never change during a call, so reading them
                // fifty times over a call told us nothing new.
                val stats = "tx=$txPacketCount rx=$rxPacketCount play=$playbackFrames " +
                        "capRMS=$captureRms rawCapRMS=$rawCaptureRms playRMS=$playbackRms src=$audioSourceName " +
                        "rate=${captureRate}/${playbackRate} jbuf=${jitterBuffer.size} " +
                        "drop=$rxDropped under=$underruns trim=$trimmedFrames " +
                        "gates:echo=$echoGatedFrames fwd=$forwardedFrames dt=$doubleTalkFrames" +
                        getCpuStats()
                Log.i(TAG, "WebRTC PCM frames: $stats")
                listener?.onAudioStats(stats)

                val elapsed = System.currentTimeMillis() - lastFrameReceivedTime
                if (elapsed > FRAME_TIMEOUT_MS) {
                    Log.w(TAG, "Audio timeout: no far-end frame for ${elapsed / 1000}s")
                    listener?.onAudioTimeout()
                    break
                }
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    // ── Playback: jitter buffer → decode → selected AudioTrack route ──

    private fun logAudioCapabilities(): AudioCapabilities {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val inputs = try { am?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty() } catch (_: Exception) { emptyArray() }
        val outputs = try { am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty() } catch (_: Exception) { emptyArray() }
        val telephonyInput = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        val telephonyOutput = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        val inputSummary = inputs.joinToString { describeDevice(it) }.ifEmpty { "none" }
        val outputSummary = outputs.joinToString { describeDevice(it) }.ifEmpty { "none" }
        val message = "Audio capability: exposedTelephonyRx=${telephonyInput != null} " +
            "exposedTelephonyTx=${telephonyOutput != null} inputs=[$inputSummary] outputs=[$outputSummary]"
        Log.i(TAG, message)
        listener?.onAudioStats(message)
        return AudioCapabilities(telephonyInput, telephonyOutput)
    }

    private fun describeDevice(device: AudioDeviceInfo): String =
        "type=${device.type},id=${device.id},name=${device.productName}"

    /** Request the public telephony RX device on a digital AudioRecord source. */
    private fun routeCaptureToTelephonyRx(rec: AudioRecord): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val inputs = am?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty()
            val telephony = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            if (telephony == null) {
                Log.w(TAG, "No TYPE_TELEPHONY input exposed; inputs=[${inputs.joinToString { describeDevice(it) }}]")
                return false
            }
            val accepted = rec.setPreferredDevice(telephony)
            Log.i(TAG, "Capture preferred device -> TELEPHONY id=${telephony.id} accepted=$accepted")
            accepted
        } catch (e: Exception) {
            Log.w(TAG, "routeCaptureToTelephonyRx failed: ${e.message}")
            false
        }
    }

    /** Request the telephony TX device and leave route confirmation to playbackLoop after play(). */
    private fun routeToTelephonyTx(track: AudioTrack): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val outputs = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            val telephony = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            if (telephony == null) {
                Log.w(TAG, "No TYPE_TELEPHONY output exposed; outputs=${outputs.joinToString { describeDevice(it) }}")
                return false
            }
            val accepted = track.setPreferredDevice(telephony)
            Log.i(TAG, "Playback preferred device -> TELEPHONY id=${telephony.id} accepted=$accepted")
            accepted
        } catch (e: Exception) {
            Log.w(TAG, "routeToTelephonyTx failed: ${e.message}")
            false
        }
    }

    private fun waitForTelephonyRoute(device: () -> AudioDeviceInfo?): AudioDeviceInfo? {
        repeat(10) {
            val routed = try { device() } catch (_: Exception) { null }
            if (routed?.type == AudioDeviceInfo.TYPE_TELEPHONY) return routed
            if (!running.get()) return routed
            Thread.sleep(50)
        }
        return try { device() } catch (_: Exception) { null }
    }

    private fun failAudioMedia(error: String) {
        if (!mediaFailureReported.compareAndSet(false, true)) return
        val message = "Audio capability/media failure: $error"
        Log.e(TAG, message)
        listener?.onAudioStats(message)
        listener?.onAudioError(error)
        stop()
    }

    // ── Per-frame scratch buffers ────────────────────────────────────
    // Fifty frames a second, each of these used to allocate a fresh array:
    // the stereo interleave and the monitor mix.  Over a
    // ten-minute call that is on the order of a hundred thousand short-lived
    // arrays whose only effect is GC pressure — and a GC pause on a 20ms audio
    // loop is an audible glitch.  Each buffer belongs to the playback thread
    // and is handed straight to the next call on it, so reuse is safe.
    private var stereoBuf: ByteArray = ByteArray(0)
    private var monitorBuf: ByteArray = ByteArray(0)

    /**
     * Duplicate each 16-bit mono sample into both channels.
     *
     * Needed only so the track can match the HAL's stereo-only
     * incall_music_uplink mixPort; the content is still mono.
     */
    private fun monoToStereo(mono: ByteArray): ByteArray {
        if (stereoBuf.size != mono.size * 2) stereoBuf = ByteArray(mono.size * 2)
        val out = stereoBuf
        var i = 0
        var j = 0
        while (i + 1 < mono.size) {
            val lo = mono[i]
            val hi = mono[i + 1]
            out[j] = lo; out[j + 1] = hi
            out[j + 2] = lo; out[j + 3] = hi
            i += 2
            j += 4
        }
        return out
    }

    /**
     * Mark the voice sessions ACTIVE (2) or INACTIVE (1) with the audio HAL.
     * See DeviceProfile.halCallActiveVsids for why this is needed at all.
     */
    private fun setHalCallState(state: Int) {
        if (profile.halCallActiveVsids.isEmpty()) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            for (vsid in profile.halCallActiveVsids) {
                am.setParameters("vsid=$vsid;call_state=$state")
            }
            Log.i(TAG, "HAL call_state=$state for ${profile.halCallActiveVsids}")
        } catch (e: Exception) {
            Log.w(TAG, "setHalCallState($state) failed: ${e.message}")
        }
    }

    /** Mute or unmute the agent towards the caller. */
    override fun setAgentMuted(on: Boolean) {
        agentMuted = on
        Log.i(TAG, if (on) "Agent MUTED towards caller" else "Agent unmuted")
        listener?.onAudioStats(if (on) "Agent muted" else "Agent unmuted")
    }

    /** Turn the speaker monitor on or off mid-call.  Safe to call repeatedly. */
    override fun setMonitorEnabled(on: Boolean) {
        if (on) {
            if (monitorTrack != null) return
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    playbackRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            // Deliberately NOT USAGE_MEDIA.  Media maps to
                            // STREAM_MUSIC, and this bridge sets
                            // incall_music_enabled=true precisely so that
                            // STREAM_MUSIC is injected into the modem uplink —
                            // so a monitor track on USAGE_MEDIA would be sent
                            // to the caller instead of the speaker, which is
                            // the opposite of what monitoring is for.
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(playbackRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                // Force it to the loudspeaker; the call itself is elsewhere.
                try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { t.setPreferredDevice(it) }
                } catch (_: Exception) {}
                t.play()
                monitorTrack = t
                Log.i(TAG, "Monitor ON — both sides on the speaker, mic stays muted")
                listener?.onAudioStats("Monitor ON")
            } catch (e: Exception) {
                Log.w(TAG, "Monitor start failed: ${e.message}")
            }
        } else {
            monitorTrack?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
            monitorTrack = null
            Log.i(TAG, "Monitor OFF")
            listener?.onAudioStats("Monitor OFF")
        }
    }

    /**
     * Mix the agent's frame with the most recent caller frame and play the
     * result on the speaker.
     *
     * Weighted 3:1 towards the agent rather than mixed evenly.  The caller
     * arrives from the modem downlink at close to full scale while the agent's
     * side is quieter, so an even mix buries the agent — and the agent is
     * usually the half worth listening to, since the point of monitoring is to
     * hear what it is saying.  The weights still sum to 1, so two loud sides
     * cannot clip against each other.
     */
    private fun feedMonitor(agentPcm: ByteArray) {
        val t = monitorTrack ?: return
        try {
            val caller = lastCallerFrame
            if (monitorBuf.size != agentPcm.size) monitorBuf = ByteArray(agentPcm.size)
            val out = monitorBuf
            var i = 0
            while (i + 1 < agentPcm.size) {
                val a = ((agentPcm[i + 1].toInt() shl 8) or (agentPcm[i].toInt() and 0xFF)).toShort().toInt()
                val c = if (caller != null && i + 1 < caller.size) {
                    ((caller[i + 1].toInt() shl 8) or (caller[i].toInt() and 0xFF)).toShort().toInt()
                } else 0
                val mixed = (((a * 3) + c) / 4).coerceIn(-32768, 32767)
                out[i] = (mixed and 0xFF).toByte()
                out[i + 1] = ((mixed shr 8) and 0xFF).toByte()
                i += 2
            }
            t.write(out, 0, out.size)
        } catch (e: Exception) {
            Log.w(TAG, "Monitor write failed: ${e.message}")
        }
    }

    private fun playbackLoop() {
        val track = audioTrack ?: return

        // No prefill — the silence-frame loop below feeds the AudioTrack
        // continuously, preventing underruns.  Removing the old 20ms prefill
        // saves that much initial latency.
        track.play()
        if (profile.requireTelephonyTx) {
            val routed = waitForTelephonyRoute { track.routedDevice }
            if (!running.get()) return
            if (routed?.type != AudioDeviceInfo.TYPE_TELEPHONY) {
                failAudioMedia(
                    "AudioTrack did not settle on required TYPE_TELEPHONY output " +
                        "(actual=${routed?.let(::describeDevice) ?: "none"})"
                )
                return
            }
        }
        Log.i(TAG, "Playback started (rate=$playbackRate usage=$playbackUsageName deepBuffer=true) " +
            "routedTo=${track.routedDevice?.let(::describeDevice) ?: "none"}")

        // Set incall_music_enabled=true AFTER AudioTrack.play(): the Qualcomm
        // HAL on MSM8930 starts the incall-music usecase only once there is an
        // active STREAM_MUSIC output.
        //
        // Skipped where the profile already set it before the track existed.
        // Toggling it again here reconfigures the voice path and tears down the
        // in-call record session the HAL has just started — measured on SM6150:
        // VOICE_CALL delivered real audio (rawRMS=208) until this second toggle
        // fired 8ms later, after which capture went to zero.
        if (!profile.useGenericRouting) {
            if (!profile.incallMusicBeforeTrack) {
                enableIncallMusic()
            }
            // Set mixer controls only for an operator-selected legacy profile.
            enableIncallMusicViaMixer()
        }

        // Silence frame for when jitter buffer is empty — prevents underruns
        // that cause BUFFER TIMEOUT and AudioTrack disable/restart cycles.
        val silenceFrame = ByteArray(playbackRate * 2 * playbackChannels / 50) // 20ms

        // Track silence→speech transitions for fade-in.
        // Modem DSP AGC/DTX settles during silence; abrupt speech onset
        // gets over-amplified causing distortion at the start of each phrase.
        // A short fade-in smooths the transition.
        var wasPlayingSilence = true

        while (running.get()) {
            try {
                // Drain excess packets to bound latency.  When network
                // jitter causes burst arrivals, the buffer can accumulate.
                // Keep at most 5 (100ms) — enough headroom to absorb
                // jitter without audible gaps.  100ms is still well within
                // the GSM bridge's inherent latency budget.

                // Longer than the 20ms frame period, deliberately: at 18ms a
                // packet that was merely a little late was treated as missing
                // and a silence frame went to the caller instead.
                val encoded = jitterBuffer.poll(JITTER_POLL_MS, TimeUnit.MILLISECONDS)
                if (encoded == null) {
                    // Write silence to keep AudioTrack fed and prevent underruns.
                    track.write(silenceFrame, 0, silenceFrame.size)
                    underruns++
                    playbackRms = 0
                    currentPlaybackActive = false
                    wasPlayingSilence = true
                    continue
                }

                // Shed one frame if a burst has left the queue deep.  Without
                // this the depth reached after a burst is simply kept, and the
                // caller hears every later word that much later.
                if (jitterBuffer.size > JITTER_HIGH_WATER) {
                    jitterBuffer.poll()
                    trimmedFrames++
                }

                val pcm = encoded

                // Fade-in after silence: prevents modem DSP AGC spike that
                // causes distorted/harsh beginning of each agent phrase.
                // 5ms ramp (40 samples @ 8kHz) is enough to smooth the onset.
                if (wasPlayingSilence) {
                    applyFadeIn(pcm)
                    wasPlayingSilence = false
                }

                // Measure RMS BEFORE gain for the echo gate.  The echo gate
                // threshold was calibrated to raw codec output levels.  If
                // playbackGain > 1, measuring after gain would lower the
                // effective threshold, over-suppressing caller speech.
                val rawRms = pcmRms(pcm)
                currentPlaybackActive = rawRms > echoGateThreshold

                // Software gain: boost PCM before writing to AudioTrack.
                // This increases the digital level injected via incall_music
                // without changing STREAM_MUSIC volume (which clips at >14%).
                val gain = playbackGain
                if (gain != 1.0) {
                    for (i in 0 until pcm.size / 2) {
                        val lo = pcm[i * 2].toInt() and 0xFF
                        val hi = pcm[i * 2 + 1].toInt()
                        val sample = ((((hi shl 8) or lo) * gain).toInt())
                        val clamped = sample.coerceIn(-32768, 32767)
                        pcm[i * 2] = (clamped and 0xFF).toByte()
                        pcm[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
                    }
                }

                if (agentMuted) {
                    // Zero before both the modem and the monitor, so someone
                    // listening in hears the mute take effect rather than
                    // audio the caller is no longer getting.
                    java.util.Arrays.fill(pcm, 0)
                }
                playbackRms = if (playbackGain > 1) pcmRms(pcm) else rawRms
                feedMonitor(pcm)
                val out = if (playbackChannels == 2) monoToStereo(pcm) else pcm
                track.write(out, 0, out.size)
                playbackFrames++
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Playback error: ${e.message}")
            }
        }
    }

    /**
     * Apply a 5ms linear fade-in to the start of a PCM buffer.
     * Smooths the silence→speech transition that otherwise causes
     * the modem's uplink AGC/DTX to over-amplify the first samples.
     */
    private fun applyFadeIn(pcm: ByteArray, fadeMs: Int = 5) {
        val fadeSamples = playbackRate * fadeMs / 1000
        val totalSamples = pcm.size / 2
        val count = fadeSamples.coerceAtMost(totalSamples)
        for (i in 0 until count) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            val faded = (sample.toLong() * (i + 1) / count).toInt().coerceIn(-32768, 32767)
            pcm[i * 2] = (faded and 0xFF).toByte()
            pcm[i * 2 + 1] = ((faded shr 8) and 0xFF).toByte()
        }
    }

    /** Compute RMS level of PCM16 little-endian audio buffer (0-32767) */
    private fun pcmRms(pcm: ByteArray): Int {
        val sampleCount = pcm.size / 2
        if (sampleCount == 0) return 0
        var sumSquares = 0L
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSquares += sample.toLong() * sample
        }
        return Math.sqrt(sumSquares.toDouble() / sampleCount).toInt()
    }

    /**
     * Set incall_music_enabled=true via AudioManager.
     * Must be called AFTER AudioTrack.play() so the HAL has an active
     * STREAM_MUSIC output to route through the incall-music usecase.
     *
     * Also re-enforces stream volumes here as a secondary safeguard.
     * GsmCallManager sets volumes in configureAudioBridge(), but Android's
     * AudioPolicyManager can reset them when the speaker route change
     * completes.  By the time we get here (after AudioTrack.play()),
     * the route change is long finished, so our volume sticks.
     *
     * On Samsung Exynos (ABOX HAL), the audio_hw_proxy should handle
     * incall_music_enabled internally by routing SIFS→NSRC→voice TX.
     * We also try Samsung-specific parameter names as fallbacks in case
     * the standard parameter is not implemented in this LineageOS build.
     */
    private fun enableIncallMusic() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                // Force a false→true transition to start the incall-music
                // usecase in the HAL.  configureAudioBridge() no longer
                // primes true (it caused problems: the earpiece→speaker
                // route change would tear down the voice path and lose the
                // incall-music state, then the second true here was a no-op).
                //
                // By this point:
                //  1. AudioTrack.play() has started (active STREAM_MUSIC output)
                //  2. Speaker route is settled (configureAudioBridge ran 1+ sec ago)
                //  3. restoreAudio from previous call set false (clean slate)
                //
                // The explicit false first ensures the HAL processes it as a
                // genuine state transition, even if some stale true leaked.
                // 50ms gap lets the HAL fully tear down before re-creating.
                val param = profile.incallMusicParam
                if (param.isNotEmpty()) {
                    it.setParameters("${param}=false")
                    Thread.sleep(50)
                    it.setParameters("${param}=true")
                }

                // Samsung Exynos: additional HAL params for incall music injection.
                // Only when incallMusicParam is configured (empty = skip to avoid
                // breaking VOICE_CALL capture).
                // Samsung Exynos: additional HAL params for incall music.
                // These are "best effort" — on Exynos 9820, no visible mixer
                // effect is observed, but they may help on other Exynos devices.
                if (profile.name.contains("Exynos") && param.isNotEmpty()) {
                    it.setParameters("g_call_path=on")
                    it.setParameters("abox_incall_music=on")
                    it.setParameters("incall_music=1")
                }

                GsmCallManager.enforceVolumes(it)

                val msg = "incall_music: param=${param.ifEmpty { "NONE" }}, mode=${it.mode}" +
                    if (param.isNotEmpty() && profile.name.contains("Exynos")) " +g_call_path +abox_incall_music +incall_music=1" else ""
                Log.i(TAG, msg)
                listener?.onAudioStats(msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set incall_music_enabled: ${e.message}")
            listener?.onAudioStats("incall_music FAILED: ${e.message}")
        }
    }

    /**
     * Fallback: use root (Magisk) to set incall_music mixer controls
     * directly via tinymix.  Device-specific commands from the profile.
     *
     * The bare 'tinymix' in profile commands is resolved to the
     * discovered full path via [DeviceProfile.resolveCmd].
     */
    private fun enableIncallMusicViaMixer() {
        val mixerCmd = profile.mixerIncallMusicCmd
        if (mixerCmd.isEmpty()) {
            Log.i(TAG, "Mixer: no incall_music commands for ${profile.name}")
            return
        }
        val resolvedMixerCmd = DeviceProfile.resolveCmd(mixerCmd)
        if (resolvedMixerCmd.isEmpty()) {
            val msg = "Mixer: tinymix not found — cannot set incall_music mixer"
            Log.e(TAG, msg)
            listener?.onAudioStats(msg)
            return
        }
        Thread({
            try {
                // Just the routing commands.  This used to append a readback of
                // four 'ABOX ...' controls, which exist only on Exynos — on
                // every call, on a Qualcomm device, that was four more root
                // round-trips during audio setup to print "Invalid mixer ctl".
                val output = RootShell.execForOutput(resolvedMixerCmd, timeoutMs = 8000)
                if (output.isNotBlank()) Log.i(TAG, "Mixer incall_music: $output")
            } catch (e: Exception) {
                Log.w(TAG, "Mixer fallback failed: ${e.message}")
                listener?.onAudioStats("Mixer incall_music FAILED: ${e.message}")
            }
        }, "Audio-Mixer").start()
    }

    /**
     * Re-toggle incall_music_enabled false→true as a safety net.
     * Called ~3s after audio start when the speaker route change is
     * guaranteed to be complete.  Handles edge cases where the initial
     * toggle in enableIncallMusic() fired before the route settled.
     */
    private fun reToggleIncallMusic() {
        val param = profile.incallMusicParam
        if (param.isEmpty()) return
        if (profile.incallMusicBeforeTrack) {
            // The toggle is what kills in-call capture on this HAL; the
            // pre-track set has already done the job.
            return
        }
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                it.setParameters("${param}=false")
                Thread.sleep(50)
                it.setParameters("${param}=true")
                GsmCallManager.enforceVolumes(it)
                Log.i(TAG, "${param} re-toggled (3s safety), mode=${it.mode}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "${param} re-toggle failed: ${e.message}")
        }
    }

    /**
     * Log diagnostic info about capture capability:
     * Phase 1: NSRC routing + bridge state
     * Phase 2: mixer_paths.xml incall_music voice path definitions
     * Phase 3: /proc/asound capture/playback PCM status
     * Phase 6: Delayed NSRC re-check (t+5s)
     * Phase 7: ALSA capture PCM probe (tinycap, if available)
     */
    /**
     * Whether to run the full capture diagnostic sweep at call setup.
     *
     * That sweep greps every mixer_paths XML, walks all of /proc/asound,
     * sleeps five seconds and re-checks, then tinycaps every running PCM and
     * pipes each through od|grep — a 30s root budget, on every single call.
     * It is what established the routing on this SoC and on the Exynos that
     * turned out to have no digital path at all; with that settled it is pure
     * cost on the call path.  Left switchable for the next unknown device:
     *
     *   adb shell su -c "am broadcast ..."  — or simply set the pref:
     *   getSharedPreferences("gateway", 0).edit().putBoolean("audio_diag", true)
     */
    private fun audioDiagnosticsEnabled(): Boolean = try {
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
            .getBoolean("audio_diag", false)
    } catch (_: Exception) { false }

    private fun logCaptureDiagnostics(record: AudioRecord) {
        try {
            // Check CAPTURE_AUDIO_OUTPUT (system permission, not runtime)
            val hasCaptureOutput = context.checkCallingOrSelfPermission(
                "android.permission.CAPTURE_AUDIO_OUTPUT"
            ) == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Diag: CAPTURE_AUDIO_OUTPUT=$hasCaptureOutput " +
                "session=${record.audioSessionId} state=${record.state} " +
                "recState=${record.recordingState} source=$audioSourceName")
            listener?.onAudioStats("Diag: CAPTURE_AUDIO_OUTPUT=$hasCaptureOutput")

            // Comprehensive ABOX routing dump via root
            val tinymix = DeviceProfile.tinymixBin
            if (tinymix.isNotEmpty()) {
                Thread({
                    try {
                        // Phase 1: NSRC routing + bridge state
                        val routingCmd = buildString {
                            append("echo '=== NSRC routing ==='; ")
                            for (i in 0..2) {
                                append("echo -n 'NSRC${i}='; $tinymix 'ABOX NSRC${i}' 2>/dev/null || echo 'N/A'; ")
                                append("echo -n 'NSRC${i}_Bridge='; $tinymix 'ABOX NSRC${i} Bridge' 2>/dev/null || echo 'N/A'; ")
                            }
                            append("echo -n 'SoundType='; $tinymix 'ABOX Sound Type' 2>/dev/null || echo 'N/A'")
                        }
                        val routing = RootShell.execForOutput(routingCmd, timeoutMs = 8000)
                        for (line in routing.lines().filter { it.isNotBlank() }) {
                            Log.i(TAG, "Diag: $line")
                        }

                        // Phase 2: mixer_paths.xml — voice call & incall_music paths
                        val mixerPathsCmd = buildString {
                            append("echo '=== mixer_paths voice/incall ==='; ")
                            // Search for incall_music path definitions
                            append("for f in /vendor/etc/mixer_paths*.xml /vendor/etc/audio/mixer_paths*.xml; do ")
                            append("  if [ -f \"\$f\" ]; then ")
                            append("    echo \"--- \$f ---\"; ")
                            // Grep for incall_music path (if it exists, shows what controls to set)
                            append("    grep -B2 -A15 'incall.music\\|incallmusic' \"\$f\" 2>/dev/null | head -40; ")
                            // Also show voice_call path for comparison
                            append("    echo '--- voice_call path ---'; ")
                            append("    grep -B1 -A10 'name=\"voice-call\"\\|name=\"voice_call\"' \"\$f\" 2>/dev/null | head -30; ")
                            // Search for any path that mentions UAIF (modem TX)
                            append("    echo '--- UAIF paths ---'; ")
                            append("    grep -i 'uaif' \"\$f\" 2>/dev/null | head -20; ")
                            append("  fi; done")
                        }
                        val mixerPaths = RootShell.execForOutput(mixerPathsCmd, timeoutMs = 5000)
                        for (line in mixerPaths.lines().filter { it.isNotBlank() }) {
                            Log.i(TAG, "Diag: $line")
                        }

                        // Phase 3: Active capture/playback PCMs + hw_params (all cards)
                        val pcmCmd = buildString {
                            append("echo '=== capture PCMs (all cards) ==='; ")
                            append("for f in /proc/asound/card*/pcm*c/sub0/status; do ")
                            append("s=\$(head -1 \$f 2>/dev/null); d=\${f%/status}; ")
                            append("card=\${f#/proc/asound/}; card=\${card%%/*}; ")
                            append("pcm=\${d##*/}; ")
                            append("echo \"\$card/\$pcm: \$s\"; ")
                            append("if echo \"\$s\" | grep -q RUNNING; then ")
                            append("echo \"  hw: \$(cat \${d}/hw_params 2>/dev/null | head -5 | tr '\\n' ' ')\"; ")
                            append("name=\$(cat \${d%/sub0}/info 2>/dev/null | grep '^name:' | head -1); ")
                            append("[ -n \"\$name\" ] && echo \"  \$name\"; ")
                            append("fi; done; ")
                            // Also dump playback PCMs (all cards)
                            append("echo '=== playback PCMs (all cards) ==='; ")
                            append("for f in /proc/asound/card*/pcm*p/sub0/status; do ")
                            append("s=\$(head -1 \$f 2>/dev/null); d=\${f%/status}; ")
                            append("card=\${f#/proc/asound/}; card=\${card%%/*}; ")
                            append("pcm=\${d##*/}; ")
                            append("if echo \"\$s\" | grep -q RUNNING; then ")
                            append("echo \"\$card/\$pcm: \$s  hw: \$(cat \${d}/hw_params 2>/dev/null | head -3 | tr '\\n' ' ')\"; ")
                            append("fi; done")
                        }
                        val pcmResult = RootShell.execForOutput(pcmCmd, timeoutMs = 3000)
                        for (line in pcmResult.lines().filter { it.isNotBlank() }) {
                            Log.i(TAG, "Diag: $line")
                        }

                        // Phase 4-5 removed in v2.8.43 — routing map fully
                        // established (Madera codec, SLIMTX, HPOUT, DSP, ASRC).

                        // Phase 6: Delayed re-check (5s) — see if routing changes
                        // after enableIncallMusic/enableIncallMusicViaMixer run.
                        Thread.sleep(5000)
                        if (running.get()) {
                            val recheck = buildString {
                                append("echo '=== NSRC re-check (t+5s) ==='; ")
                                for (i in 0..2) {
                                    append("echo -n 'NSRC${i}='; $tinymix 'ABOX NSRC${i}' 2>/dev/null || echo 'N/A'; ")
                                    append("echo -n 'NSRC${i}_Bridge='; $tinymix 'ABOX NSRC${i} Bridge' 2>/dev/null || echo 'N/A'; ")
                                }
                                append("echo -n 'SoundType='; $tinymix 'ABOX Sound Type' 2>/dev/null || echo 'N/A'")
                            }
                            val recheckResult = RootShell.execForOutput(recheck, timeoutMs = 8000)
                            for (line in recheckResult.lines().filter { it.isNotBlank() }) {
                                Log.i(TAG, "Diag: $line")
                            }
                        }

                        // Phase 7: Probe ALSA capture PCMs for non-zero audio data.
                        // All AudioRecord sources return silence (rawCapRMS=0) on this
                        // device.  This probe reads raw bytes from each RUNNING capture
                        // PCM across ALL cards to find which device has modem downlink
                        // audio.  Card 1 (aboxvdma) may carry modem voice data.
                        if (running.get()) {
                            val probeCmd = buildString {
                                append("echo '=== ALSA capture PCM probe (all cards) ==='; ")
                                append("if [ ! -x /data/local/tmp/tinycap ]; then ")
                                append("  echo 'tinycap not found'; ")
                                append("else ")
                                // Iterate all capture PCMs across all cards
                                append("for d in /proc/asound/card*/pcm*c; do ")
                                append("  [ -d \"\$d/sub0\" ] || continue; ")
                                append("  s=\$(head -1 \$d/sub0/status 2>/dev/null); ")
                                append("  card=\${d#/proc/asound/}; card=\${card%%/*}; ")
                                append("  cardnum=\${card#card}; ")
                                append("  pcm=\${d##*/}; devnum=\${pcm#pcm}; devnum=\${devnum%c}; ")
                                append("  name=\$(cat \$d/info 2>/dev/null | grep '^name:' | head -1 | cut -d: -f2-); ")
                                append("  echo \"\$card/\$pcm:\$name status=\$s\"; ")
                                append("  if echo \"\$s\" | grep -q RUNNING; then ")
                                // Parse actual hw_params for this PCM
                                append("    ch=\$(cat \$d/sub0/hw_params 2>/dev/null | grep '^channels:' | awk '{print \$2}'); ")
                                append("    rate=\$(cat \$d/sub0/hw_params 2>/dev/null | grep '^rate:' | awk '{print \$2}'); ")
                                append("    fmt=\$(cat \$d/sub0/hw_params 2>/dev/null | grep '^format:' | awk '{print \$2}'); ")
                                append("    bits=16; echo \"\$fmt\" | grep -q S32 && bits=32; ")
                                append("    echo \"  hw: fmt=\$fmt ch=\$ch rate=\$rate bits=\$bits\"; ")
                                // Capture 1 second of raw audio with actual params
                                append("    timeout 2 /data/local/tmp/tinycap /data/local/tmp/probe_\${cardnum}_\${devnum}.raw ")
                                append("-D \$cardnum -d \$devnum -c \${ch:-2} -r \${rate:-48000} -b \${bits} -p 480 -n 4 2>&1 | head -2; ")
                                append("    f=/data/local/tmp/probe_\${cardnum}_\${devnum}.raw; ")
                                append("    if [ -f \"\$f\" ] && [ -s \"\$f\" ]; then ")
                                append("      sz=\$(wc -c < \"\$f\"); ")
                                append("      nz=\$(od -An -tx1 \"\$f\" | tr ' ' '\\n' | grep -cv '^00\$\\|^\$'); ")
                                append("      echo \"  probe: \${sz}B, \${nz} non-zero bytes\"; ")
                                append("      rm -f \"\$f\"; ")
                                append("    else ")
                                append("      echo '  probe: no data captured'; ")
                                append("    fi; ")
                                append("  fi; ")
                                append("done; ")
                                append("fi")
                            }
                            val probeResult = RootShell.execForOutput(probeCmd, timeoutMs = 30000)
                            for (line in probeResult.lines().filter { it.isNotBlank() }) {
                                Log.i(TAG, "Diag: $line")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Diag routing check failed: ${e.message}")
                    }
                }, "CaptureDiag").start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Capture diagnostics failed: ${e.message}")
        }
    }

    /**
     * Read CPU/memory/thread stats for diagnostics.
     * - System load from /proc/loadavg (1/5/15 min averages; >2.0 = overloaded on dual-core)
     * - Process CPU% from /proc/self/stat utime+stime delta over sample interval
     * - JVM heap usage and thread count
     */
    private fun getCpuStats(): String {
        return try {
            val sb = StringBuilder()

            // /proc/loadavg is blocked by SELinux (proc_loadavg) on priv_app.
            // Process CPU% — read utime(14) + stime(15) from /proc/self/stat
            // These are in clock ticks (typically 100 Hz on ARM).
            try {
                val statFields = java.io.File("/proc/self/stat").readText().trim()
                    .substringAfter(") ")  // skip past "(comm) "
                    .split(" ")
                // Fields after ") ": index 0=state, ..., 11=utime, 12=stime
                val utime = statFields.getOrNull(11)?.toLongOrNull() ?: 0L
                val stime = statFields.getOrNull(12)?.toLongOrNull() ?: 0L
                val totalTicks = utime + stime
                val nowMs = System.currentTimeMillis()

                if (lastCpuSampleMs > 0) {
                    val tickDelta = totalTicks - lastCpuTicks
                    val msDelta = nowMs - lastCpuSampleMs
                    if (msDelta > 0) {
                        // Convert ticks to ms (100 ticks/sec = 10ms/tick)
                        val cpuMs = tickDelta * 10
                        val cpuPct = (cpuMs * 100) / msDelta
                        sb.append(" cpu=${cpuPct}%")
                    }
                }
                lastCpuTicks = totalTicks
                lastCpuSampleMs = nowMs
            } catch (_: Exception) {}

            // Thread count and JVM heap
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val maxMb = rt.maxMemory() / (1024 * 1024)
            val threads = Thread.activeCount()
            sb.append(" mem=${usedMb}/${maxMb}M thr=$threads")

            sb.toString()
        } catch (_: Exception) { "" }
    }

    companion object {
        private const val TAG = "CellularAudio"

        /** Per-thread wait in [stop] before the audio devices are released
         *  anyway.  A capture read is one 20ms frame and the playback poll is
         *  18ms, so a healthy loop is gone well inside this. */
        private const val JOIN_TIMEOUT_MS = 400L

        /** Jitter buffer ceiling, in 20ms frames (240ms). */
        private const val JITTER_CAPACITY = 12
        /** Depth above which one frame per cycle is shed (140ms). */
        private const val JITTER_HIGH_WATER = 7
        /** How long playback waits for a late frame before writing silence. */
        private const val JITTER_POLL_MS = 30L
    }
}
