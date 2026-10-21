package com.callagent.webrtcprobe

import android.content.Context
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.ExternalPlayout
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Real native ICE/DTLS-SRTP and Opus, but exclusively synthetic PCM and in-process signaling. */
class SyntheticPeerPair(private val context: Context) : AutoCloseable {
    private val endpoints = mutableListOf<Endpoint>()
    private val relays = mutableListOf<CandidateRelay>()
    private val errors = ConcurrentLinkedQueue<String>()
    private var stage = "initializing"
        set(value) {
            field = value
            reportState(JSONObject().put("stage", value))
        }
    private var cleanupStage = "not_started"
    private var closingSnapshot: JSONObject? = null

    private fun reportState(state: JSONObject) {
        state.put("recorded_at_epoch_ms", System.currentTimeMillis())
        context.filesDir.resolve("webrtc-probe-state.json").writeText(state.toString())
        android.util.Log.i("WebRtcProbeState", state.toString())
    }

    fun diagnostics(): JSONObject = (closingSnapshot ?: snapshot())
        .put("stage", stage).put("cleanup_stage", cleanupStage)

    private fun snapshot(): JSONObject {
        val result = JSONObject().put("errors", org.json.JSONArray(errors.distinct().take(16)))
        for ((index, endpoint) in endpoints.withIndex()) {
            val name = if (index == 0) "a" else "b"
            result.put("${name}_connection_state", endpoint.connectionState)
                .put("${name}_ice_state", endpoint.iceState)
                .put("${name}_ice_gathering_state", endpoint.gatheringState)
                .put("${name}_capture_frames", endpoint.source.frames.get())
                .put("${name}_playout_frames", endpoint.detector.frames.get())
                .put("${name}_remote_tone_frames", endpoint.detector.matchedFrames.get())
                .put("${name}_last_rms", endpoint.detector.lastRms)
        }
        for ((index, relay) in relays.withIndex()) {
            val name = if (index == 0) "a" else "b"
            result.put("${name}_ice_received", relay.received.get())
                .put("${name}_ice_delivered", relay.delivered.get())
                .put("${name}_ice_callbacks", relay.callbacks.get())
                .put("${name}_ice_queued", relay.queueSize())
        }
        return result
    }

    fun run(): JSONObject {
        reportState(JSONObject().put("stage", stage))
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false).createInitializationOptions())
        val aRelay = CandidateRelay(errors).also { relays += it }
        val bRelay = CandidateRelay(errors).also { relays += it }
        stage = "endpoint_a"
        val a = endpoint(1000.0, 1500.0, bRelay)
        stage = "endpoint_b"
        val b = endpoint(1500.0, 1000.0, aRelay)
        aRelay.attach(a.peer!!)
        bRelay.attach(b.peer!!)

        stage = "create_offer"
        val offer = description { a.peer!!.createOffer(it, MediaConstraints()) }
        stage = "set_local_offer"
        setDescription { a.peer!!.setLocalDescription(it, offer) }
        stage = "set_remote_offer"
        setDescription { b.peer!!.setRemoteDescription(it, offer) }
        bRelay.remoteDescriptionReady()
        stage = "create_answer"
        val answer = description { b.peer!!.createAnswer(it, MediaConstraints()) }
        stage = "set_local_answer"
        setDescription { b.peer!!.setLocalDescription(it, answer) }
        stage = "set_remote_answer"
        setDescription { a.peer!!.setRemoteDescription(it, answer) }
        aRelay.remoteDescriptionReady()

        // Let transport setup finish before starting four real-time audio threads.
        // This also keeps synthetic media from competing with ICE on slow devices.
        stage = "wait_for_ice"
        val iceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!a.connected.get() || !b.connected.get()) {
            check(errors.isEmpty()) { "Native audio or ICE failed" }
            check(System.nanoTime() < iceDeadline) { "Timed out waiting for ICE and DTLS" }
            Thread.sleep(20)
        }
        for (endpoint in listOf(a, b)) {
            endpoint.peer!!.setAudioRecording(true)
            endpoint.peer!!.setAudioPlayout(true)
        }

        stage = "wait_for_pcm"
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var nextReport = System.nanoTime()
        while (System.nanoTime() < deadline) {
            if (System.nanoTime() >= nextReport) {
                reportState(snapshot().put("stage", stage))
                nextReport = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            }
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
        val capturePacer = CaptureFramePacer()
        val capturePriority = ThreadLocal.withInitial { false }
        val detector = ToneDetector(expectedFrequency, sendFrequency)
        val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "webrtc-probe-audio-check").apply { isDaemon = true }
        }
        val module = try { JavaAudioDeviceModule.builder(context).setScheduler(scheduler)
            .setSampleRate(SyntheticPcm.SAMPLE_RATE)
            .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
            .setEnableVolumeLogger(false)
            .setAudioBufferCallback { buffer, format, channels, rate, _, _ ->
                if (!capturePriority.get()) {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
                    capturePriority.set(true)
                }
                capturePacer.awaitNextFrame()
                source.fill(buffer, format, channels, rate)
            }
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
                endpoint.iceState = state.name
                if (state == PeerConnection.IceConnectionState.FAILED) errors += "ice"
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                endpoint.connectionState = state.name
                endpoint.connected.set(state == PeerConnection.PeerConnectionState.CONNECTED)
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                endpoint.gatheringState = state.name
            }
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
        endpoint.peer!!.setAudioRecording(false)
        endpoint.peer!!.setAudioPlayout(false)
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
        closingSnapshot = snapshot()
        cleanupStage = "ice_relays"
        reportState(diagnostics())
        relays.forEach { it.close() }
        var failure: Throwable? = null
        for (endpoint in endpoints.reversed()) {
            cleanupStage = "peer_resources"
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
        cleanupStage = if (failure == null) "complete" else "failed"
        reportState(diagnostics())
        failure?.let { throw it }
    }

    private class Endpoint(val module: JavaAudioDeviceModule, val factory: PeerConnectionFactory,
                           val source: SyntheticPcm, val detector: ToneDetector,
                           val scheduler: ScheduledExecutorService) {
        val connected = AtomicBoolean(false)
        @Volatile var connectionState = "NEW"
        @Volatile var iceState = "NEW"
        @Volatile var gatheringState = "NEW"
        var peer: PeerConnection? = null
        var audioSource: AudioSource? = null
        var track: AudioTrack? = null
    }

    private class CandidateRelay(private val errors: ConcurrentLinkedQueue<String>) : AutoCloseable {
        val received = java.util.concurrent.atomic.AtomicInteger()
        val delivered = java.util.concurrent.atomic.AtomicInteger()
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        // Native addIceCandidate synchronously enters the other peer's signaling
        // thread. Calling it from an ICE observer can deadlock both signaling
        // threads, even without a Java monitor. Dispatch off those callbacks.
        private val dispatcher = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(256), ThreadFactory { task ->
                Thread(task, "webrtc-probe-ice-relay").apply { isDaemon = true }
            })
        private var target: PeerConnection? = null
        private var ready = false
        private val closed = AtomicBoolean(false)
        private val pending = mutableListOf<IceCandidate>()
        fun attach(peer: PeerConnection) = enqueue { target = peer }
        fun queueSize() = dispatcher.queue.size
        fun add(candidate: IceCandidate) {
            callbacks.incrementAndGet()
            enqueue { addOnDispatcher(candidate) }
        }
        private fun addOnDispatcher(candidate: IceCandidate) {
            received.incrementAndGet()
            if (ready) {
                deliver(candidate)
            } else {
                if (pending.size >= 256) {
                    errors += "candidate_budget"
                    return
                }
                pending += candidate
            }
        }
        fun remoteDescriptionReady() = enqueue {
            ready = true
            pending.forEach { deliver(it) }
            pending.clear()
        }
        private fun deliver(candidate: IceCandidate) {
            if (target?.addIceCandidate(candidate) == true) delivered.incrementAndGet()
            else errors += "candidate_rejected"
        }
        private fun enqueue(action: () -> Unit) {
            if (closed.get()) return
            try {
                dispatcher.execute {
                    if (!closed.get()) try { action() } catch (_: RuntimeException) {
                        errors += "candidate_delivery_failed"
                    }
                }
            } catch (_: RejectedExecutionException) {
                if (!closed.get()) errors += "candidate_budget"
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            dispatcher.shutdownNow()
            // Drain any in-flight native call before disposing either peer.
            check(dispatcher.awaitTermination(2, TimeUnit.SECONDS)) { "ICE relay did not stop" }
            pending.clear()
            target = null
        }
    }
}
