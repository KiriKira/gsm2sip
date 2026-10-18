package com.callagent.rtcmedia

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.ExternalPlayout
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One real native ICE/DTLS-SRTP/Opus endpoint. SIP audio hardware is never used. */
internal class NativePeer(
    context: Context,
    private val callId: String,
    servers: List<PeerConnection.IceServer>,
    private val pcm: PcmBridge?,
    private val onEvent: (String) -> Unit,
    private val onDigit: (Char) -> Unit,
    private val onFailure: (String) -> Unit,
    forceRelay: Boolean = false
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val connected = CountDownLatch(1)
    private val gathered = CountDownLatch(1)
    private val localCandidates = java.util.concurrent.ConcurrentLinkedQueue<IceCandidate>()
    private val candidateCount = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var gatheringComplete = false; private set
    val hasQueuedCandidates: Boolean get() = localCandidates.isNotEmpty()
    fun takeCandidates(): List<IceCandidate> = buildList {
        while (true) add(localCandidates.poll() ?: break)
    }
    private val lock = Any()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val pacer = ExternalPlayout.FramePacer()
    private val module: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private val source: AudioSource
    private val track: AudioTrack
    private val peer: PeerConnection
    private var controls: DataChannel? = null
    private val digitIds = linkedSetOf<String>()
    private var digitWindow = 0L
    private var digitCount = 0
    @Volatile var isConnected = false; private set
    @Volatile private var audioEnabled = false
    @Volatile private var muted = false

    companion object {
        @Volatile private var initialized = false
        @Synchronized fun initialize(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false).createInitializationOptions())
            initialized = true
        }
        fun isAvailable(context: Context): Boolean = runCatching { initialize(context); true }.getOrDefault(false)
    }

    init {
        initialize(context)
        val builder = JavaAudioDeviceModule.builder(context.applicationContext)
            .setScheduler(scheduler).setSampleRate(16000).setEnableVolumeLogger(false)
            .setUseHardwareAcousticEchoCanceler(pcm == null).setUseHardwareNoiseSuppressor(pcm == null)
        if (pcm != null) {
            builder.setAudioBufferCallback { buffer, format, channels, rate, _, _ ->
                pacer.awaitNextFrame()
                try { pcm.fill(buffer, format, channels, rate) }
                catch (_: Throwable) { buffer.clear(); while (buffer.hasRemaining()) buffer.put(0.toByte());buffer.rewind();onFailure("external_pcm_capture_format") }
                System.nanoTime()
            }
            builder.setPlaybackSamplesReadyCallback { samples ->
                try { pcm.decoded(samples.audioFormat,samples.channelCount,samples.sampleRate,samples.data) }
                catch (_: Throwable) { onFailure("external_pcm_playout_format") }
            }
        }
        builder.setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(message: String) = onFailure("audio_capture_init")
            override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode,message: String) = onFailure("audio_capture_start")
            override fun onWebRtcAudioRecordError(message: String) = onFailure("audio_capture_runtime")
        }).setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
            override fun onWebRtcAudioTrackInitError(message: String) = onFailure("audio_playout_init")
            override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode,message: String) = onFailure("audio_playout_start")
            override fun onWebRtcAudioTrackError(message: String) = onFailure("audio_playout_runtime")
        })
        module = builder.createAudioDeviceModule()
        if (pcm != null) ExternalPlayout.enable(module)
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onEvent("ice_${state.name.lowercase()}")
                if (state == PeerConnection.IceConnectionState.FAILED && !closed.get()) onFailure("ice_failed")
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                isConnected = state == PeerConnection.PeerConnectionState.CONNECTED
                onEvent("peer_${state.name.lowercase()}")
                if (isConnected) connected.countDown()
                if (state == PeerConnection.PeerConnectionState.FAILED && !closed.get()) onFailure("dtls_connection_failed")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) { gatheringComplete=true; gathered.countDown() }
            }
            override fun onIceCandidate(candidate: IceCandidate) {
                if (candidateCount.incrementAndGet()>128) { onFailure("local_candidate_budget"); return }
                localCandidates.add(candidate)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) { attachControls(channel) }
            override fun onRenegotiationNeeded() = Unit
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            if (forceRelay) iceTransportsType = PeerConnection.IceTransportsType.RELAY
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        peer = checkNotNull(factory.createPeerConnection(config,observer))
        peer.setAudioRecording(false);peer.setAudioPlayout(false)
        source = factory.createAudioSource(MediaConstraints().apply {
            if (pcm != null) for (key in listOf("googEchoCancellation","googAutoGainControl","googNoiseSuppression","googHighpassFilter")) optional += MediaConstraints.KeyValuePair(key,"false")
        })
        track = factory.createAudioTrack("gsm-audio",source)
        track.setEnabled(false)
        peer.addTrack(track,listOf("gsm-call"))
        val opus = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO).codecs.filter { it.name.equals("opus",true) }
        check(opus.isNotEmpty()) { "Opus unavailable" }
        peer.transceivers.single().setCodecPreferences(opus).throwError()
    }

    fun offer(): String {
        attachControls(checkNotNull(peer.createDataChannel("gsm-controls",DataChannel.Init().apply { ordered=true })))
        return localDescription { peer.createOffer(it,MediaConstraints()) }
    }
    fun answer(sdp: String): String {
        setDescription(SessionDescription(SessionDescription.Type.OFFER,sdp),false)
        return localDescription { peer.createAnswer(it,MediaConstraints()) }
    }
    fun remoteAnswer(sdp: String) = setDescription(SessionDescription(SessionDescription.Type.ANSWER,sdp),false)
    fun remoteCandidate(mid: String, index: Int, candidate: String) {
        check(peer.addIceCandidate(IceCandidate(mid,index,candidate))) { "remote_candidate_rejected" }
    }
    private fun localDescription(create: (SdpObserver)->Unit): String {
        val future = CompletableFuture<SessionDescription>()
        create(observer(created={ future.complete(it) },failed={ future.completeExceptionally(IllegalStateException("sdp_create")) }))
        val description=future.get(5,TimeUnit.SECONDS)
        setDescription(description,true)
        check(!closed.get()) { "closed" }
        // Every candidate is relayed exactly through the committed trickle
        // queue. Do not publish an inline duplicate captured during setLocal.
        return checkNotNull(peer.localDescription).description.split("\r\n")
            .filterNot { it.startsWith("a=candidate:") || it == "a=end-of-candidates" }
            .joinToString("\r\n").also { check(it.toByteArray().size <= 65536) }
    }
    private fun setDescription(description: SessionDescription,local: Boolean) {
        val future=CompletableFuture<Unit>()
        val observer=observer(set={future.complete(Unit)},failed={future.completeExceptionally(IllegalStateException("sdp_set"))})
        if(local) peer.setLocalDescription(observer,description) else peer.setRemoteDescription(observer,description)
        future.get(5,TimeUnit.SECONDS)
    }
    private fun observer(created:(SessionDescription)->Unit={},set:()->Unit={},failed:()->Unit={})=object:SdpObserver {
        override fun onCreateSuccess(description:SessionDescription)=created(description)
        override fun onSetSuccess()=set()
        override fun onCreateFailure(message:String)=failed()
        override fun onSetFailure(message:String)=failed()
    }
    fun enableAudio(enabled:Boolean) = synchronized(lock) {
        if (closed.get()) return@synchronized
        audioEnabled=enabled
        track.setEnabled(enabled && !muted)
        peer.setAudioRecording(enabled);peer.setAudioPlayout(enabled)
        onEvent(if(enabled) "audio_enabled" else "audio_suspended")
    }
    fun mute(value:Boolean)=synchronized(lock){muted=value;if(!closed.get())track.setEnabled(audioEnabled && !value)}
    private fun attachControls(channel:DataChannel) {
        if(channel.label()!="gsm-controls" || closed.get()){channel.close();channel.dispose();return}
        synchronized(lock){if(controls!=null){channel.close();channel.dispose();return};controls=channel}
        channel.registerObserver(object:DataChannel.Observer{
            override fun onBufferedAmountChange(previousAmount:Long)=Unit
            override fun onStateChange()=Unit
            override fun onMessage(buffer:DataChannel.Buffer){
                if(pcm==null || !audioEnabled || !isConnected || buffer.binary || buffer.data.remaining()>512)return
                try {
                    val bytes=ByteArray(buffer.data.remaining());buffer.data.get(bytes)
                    val message=JSONObject(String(bytes,Charsets.UTF_8))
                    if(message.getString("type")!="dtmf" || message.getString("call_id")!=callId)return
                    val id=message.getString("id");val digit=message.getString("digit")
                    if(id.length!=36 || digit.length!=1 || digit[0] !in "0123456789*#ABCD")return
                    synchronized(digitIds){
                        if(System.nanoTime()-digitWindow>1_000_000_000L){digitWindow=System.nanoTime();digitCount=0}
                        if(digitCount>=16 || !digitIds.add(id))return
                        digitCount++;if(digitIds.size>256)digitIds.remove(digitIds.first())
                    }
                    onDigit(digit[0]);onEvent("dtmf_received")
                }catch(_:Throwable){onFailure("invalid_control_message")}
            }
        })
    }
    fun sendDtmf(digits:String)=synchronized(lock){
        check(audioEnabled && isConnected && !closed.get()){"media_not_active"}
        require(digits.length in 1..16 && digits.all{it in "0123456789*#ABCD"})
        val channel=checkNotNull(controls);check(channel.state()==DataChannel.State.OPEN)
        for(digit in digits){
            val data=JSONObject().put("type","dtmf").put("call_id",callId).put("id",UUID.randomUUID().toString()).put("digit",digit.toString()).toString().toByteArray()
            check(channel.send(DataChannel.Buffer(ByteBuffer.wrap(data),false))){"control_send_failed"}
        }
        onEvent("dtmf_sent")
    }
    fun stats()=synchronized(lock){
        if(closed.get())return@synchronized
        peer.getStats { report ->
            var tx=0L;var rx=0L;val types=linkedSetOf<String>()
            for(stat in report.statsMap.values){
                if(stat.type=="outbound-rtp")tx+=(stat.members["packetsSent"] as? Number)?.toLong()?:0
                if(stat.type=="inbound-rtp")rx+=(stat.members["packetsReceived"] as? Number)?.toLong()?:0
                if(stat.type=="local-candidate" || stat.type=="remote-candidate")(stat.members["candidateType"] as? String)?.let(types::add)
            }
            val transport = report.statsMap.values.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] != null }
            val pair = report.statsMap[transport?.members?.get("selectedCandidatePairId") as? String]
            val local = report.statsMap[pair?.members?.get("localCandidateId") as? String]
            val remote = report.statsMap[pair?.members?.get("remoteCandidateId") as? String]
            val selected = "${local?.members?.get("candidateType")}/${remote?.members?.get("candidateType")}"
            onEvent("stats selected_pair=$selected tx_packets=$tx rx_packets=$rx candidate_types=${types.joinToString(",")} pcm_capture=${pcm?.captureFrames?.get()?:0} pcm_decoded=${pcm?.decodedFrames?.get()?:0}")
        }
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        synchronized(lock){
            runCatching{controls?.unregisterObserver();controls?.close();controls?.dispose()};controls=null
            runCatching{peer.setAudioRecording(false);peer.setAudioPlayout(false);peer.close();peer.dispose()}
            runCatching{track.dispose()};runCatching{source.dispose()};runCatching{factory.dispose()};runCatching{module.release()}
        }
        scheduler.shutdownNow();pcm?.clear();onEvent("native_closed")
    }
}
