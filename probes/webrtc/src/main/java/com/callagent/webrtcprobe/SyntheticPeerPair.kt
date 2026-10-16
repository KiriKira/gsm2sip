package com.callagent.webrtcprobe

import android.content.Context
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.ExternalPlayout
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real native ICE/DTLS-SRTP and Opus, but exclusively synthetic PCM and in-process signaling. */
class SyntheticPeerPair(private val context: Context) : AutoCloseable {
    private val endpoints = mutableListOf<Endpoint>()
    private val relays = mutableListOf<CandidateRelay>()
    private val errors = ConcurrentLinkedQueue<String>()

    fun run(): JSONObject {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false).createInitializationOptions())
        val aRelay = CandidateRelay(errors).also { relays += it }
        val bRelay = CandidateRelay(errors).also { relays += it }
        val a = endpoint(1000.0, 1500.0, bRelay)
        val b = endpoint(1500.0, 1000.0, aRelay)
        aRelay.attach(a.peer!!)
        bRelay.attach(b.peer!!)

        val offer = description { a.peer!!.createOffer(it, MediaConstraints()) }
        setDescription { a.peer!!.setLocalDescription(it, offer) }
        setDescription { b.peer!!.setRemoteDescription(it, offer) }
        bRelay.remoteDescriptionReady()
        val answer = description { b.peer!!.createAnswer(it, MediaConstraints()) }
        setDescription { b.peer!!.setLocalDescription(it, answer) }
        setDescription { a.peer!!.setRemoteDescription(it, answer) }
        aRelay.remoteDescriptionReady()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            check(errors.isEmpty()) { "Native audio or ICE failed" }
            if (a.connected.get() && b.connected.get() && a.detector.matchedFrames.get() >= 30 &&
                b.detector.matchedFrames.get() >= 30 && a.source.frames.get() >= 30 && b.source.frames.get() >= 30) {
                check(ExternalPlayout.isExclusive(a.module) && ExternalPlayout.isExclusive(b.module)) {
                    "A default Android audio device was active"
                }
                return JSONObject().put("opus_only", true).put("both_peers_connected", true)
                    .put("a_capture_frames", a.source.frames.get()).put("b_capture_frames", b.source.frames.get())
                    .put("a_remote_tone_frames", a.detector.matchedFrames.get())
                    .put("b_remote_tone_frames", b.detector.matchedFrames.get())
                    .put("default_microphone", "DISABLED").put("default_audio_track", "BYPASSED")
            }
            Thread.sleep(20)
        }
        error("Timed out waiting for bidirectional decoded PCM")
    }

    private fun endpoint(sendFrequency: Double, expectedFrequency: Double, remote: CandidateRelay): Endpoint {
        val source = SyntheticPcm(sendFrequency)
        val detector = ToneDetector(expectedFrequency, sendFrequency)
        val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "webrtc-probe-audio-check").apply { isDaemon = true }
        }
        val module = try { JavaAudioDeviceModule.builder(context).setScheduler(scheduler)
            .setSampleRate(SyntheticPcm.SAMPLE_RATE)
            .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
            .setEnableVolumeLogger(false)
            .setAudioBufferCallback { buffer, format, channels, rate, _, _ -> source.fill(buffer, format, channels, rate) }
            .setPlaybackSamplesReadyCallback { samples ->
                detector.accept(samples.audioFormat, samples.channelCount, samples.sampleRate, samples.data)
            }
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(message: String) { errors += "capture_init" }
                override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode, message: String) { errors += "capture_start" }
                override fun onWebRtcAudioRecordError(message: String) { errors += "capture" }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(message: String) { errors += "playout_init" }
                override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode, message: String) { errors += "playout_start" }
                override fun onWebRtcAudioTrackError(message: String) { errors += "playout" }
            }).createAudioDeviceModule()
        } catch (failure: Throwable) {
            scheduler.shutdownNow()
            throw failure
        }
        val factory: PeerConnectionFactory
        try {
            ExternalPlayout.enable(module)
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        } catch (failure: Throwable) {
            runCatching { module.release() }.exceptionOrNull()?.let(failure::addSuppressed)
            scheduler.shutdownNow()
            throw failure
        }
        val endpoint = Endpoint(module, factory, source, detector, scheduler).also { endpoints += it }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED) errors += "ice"
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                endpoint.connected.set(state == PeerConnection.PeerConnectionState.CONNECTED)
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) = remote.add(candidate)
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = channel.dispose()
            override fun onRenegotiationNeeded() = Unit
        }
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        endpoint.peer = checkNotNull(factory.createPeerConnection(config, observer))
        val constraints = MediaConstraints().apply {
            for (key in listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression", "googHighpassFilter")) {
                optional += MediaConstraints.KeyValuePair(key, "false")
            }
        }
        endpoint.audioSource = factory.createAudioSource(constraints)
        endpoint.track = factory.createAudioTrack("synthetic-$sendFrequency", endpoint.audioSource)
        endpoint.peer!!.addTrack(endpoint.track, listOf("synthetic"))
        val opus = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
            .codecs.filter { it.name.equals("opus", ignoreCase = true) }
        check(opus.isNotEmpty()) { "SDK has no Opus encoder" }
        endpoint.peer!!.transceivers.single().setCodecPreferences(opus).throwError()
        return endpoint
    }

    private fun description(start: (SdpObserver) -> Unit): SessionDescription {
        val result = CompletableFuture<SessionDescription>()
        start(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) { result.complete(description) }
            override fun onCreateFailure(message: String) { result.completeExceptionally(IllegalStateException("SDP create failed")) }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(message: String) = Unit
        })
        return result.get(10, TimeUnit.SECONDS)
    }

    private fun setDescription(start: (SdpObserver) -> Unit) {
        val result = CompletableFuture<Unit>()
        start(object : SdpObserver {
            override fun onSetSuccess() { result.complete(Unit) }
            override fun onSetFailure(message: String) { result.completeExceptionally(IllegalStateException("SDP set failed")) }
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onCreateFailure(message: String) = Unit
        })
        result.get(10, TimeUnit.SECONDS)
    }

    override fun close() {
        relays.forEach { it.close() }
        var failure: Throwable? = null
        for (endpoint in endpoints.reversed()) {
            endpoint.source.stop()
            for (release in listOf<() -> Unit>(
                { endpoint.peer?.close() }, { endpoint.peer?.dispose() },
                { endpoint.track?.dispose() }, { endpoint.audioSource?.dispose() },
                { endpoint.factory.dispose() }, { endpoint.module.release() },
                {
                    endpoint.scheduler.shutdownNow()
                    check(endpoint.scheduler.awaitTermination(2, TimeUnit.SECONDS)) { "Audio scheduler did not stop" }
                }
            )) try { release() } catch (caught: Throwable) { failure = failure ?: caught }
        }
        endpoints.clear()
        failure?.let { throw it }
    }

    private class Endpoint(val module: JavaAudioDeviceModule, val factory: PeerConnectionFactory,
                           val source: SyntheticPcm, val detector: ToneDetector,
                           val scheduler: ScheduledExecutorService) {
        val connected = AtomicBoolean(false)
        var peer: PeerConnection? = null
        var audioSource: AudioSource? = null
        var track: AudioTrack? = null
    }

    private class CandidateRelay(private val errors: ConcurrentLinkedQueue<String>) : AutoCloseable {
        private var target: PeerConnection? = null
        private var ready = false
        private var closed = false
        private val pending = mutableListOf<IceCandidate>()
        @Synchronized fun attach(peer: PeerConnection) { target = peer }
        @Synchronized fun add(candidate: IceCandidate) {
            if (closed) return
            if (ready) {
                if (target?.addIceCandidate(candidate) != true) errors += "candidate_rejected"
            } else {
                if (pending.size >= 256) {
                    errors += "candidate_budget"
                    return
                }
                pending += candidate
            }
        }
        @Synchronized fun remoteDescriptionReady() {
            ready = true
            pending.forEach { if (target?.addIceCandidate(it) != true) errors += "candidate_rejected" }
            pending.clear()
        }
        @Synchronized override fun close() { closed = true; pending.clear(); target = null }
    }
}
