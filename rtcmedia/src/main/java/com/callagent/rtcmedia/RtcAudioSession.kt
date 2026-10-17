package com.callagent.rtcmedia

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface MediaApi {
    /** Paths are relative to the existing authenticated /v1 API. Implementations
     * retain the same bound session and refresh/revocation rules as other calls. */
    fun request(method: String, path: String, body: JSONObject?): JSONObject
}
class MediaApiException(val status: Int, val code: String) : Exception(code)

/** One immutable, server-authorized WebRTC audio leg. All SDP stays out of logs.
 * Signaling uses authenticated HTTPS; existing SIP controls the business call.
 * No method here can dial, answer a cellular call, or switch back to RTP audio. */
class RtcAudioSession(
    private val context: Context,
    admission: JSONObject,
    private val api: MediaApi,
    private val gateway: Boolean,
    private val pcm: PcmBridge? = null,
    private val onReady: () -> Unit = {},
    private val onDigit: (Char) -> Unit = {},
    private val onFailure: (String) -> Unit,
    private val onLog: (String) -> Unit = {}
) : AutoCloseable {
    private val binding = admission.getJSONObject("binding")
    val callId: String = binding.getString("call_id")
    val legId: String = binding.getString("leg_id")
    private val attemptId = binding.getString("attempt_id")
    private val selfId = binding.getString(if (gateway) "gateway_id" else "client_id")
    private val remoteId = binding.getString(if (gateway) "client_id" else "gateway_id")
    private val servers = buildList {
        val items = admission.getJSONArray("ice_servers")
        for(i in 0 until items.length()) {
            val item=items.getJSONObject(i);val urls=item.getJSONArray("urls")
            val builder=PeerConnection.IceServer.builder((0 until urls.length()).map(urls::getString))
            if(item.has("username"))builder.setUsername(item.getString("username"))
            if(item.has("credential"))builder.setPassword(item.getString("credential"))
            add(builder.createIceServer())
        }
    }
    private val worker=Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"RTC-Signal-${callId.take(8)}") }
    private val closed=AtomicBoolean()
    private val started=AtomicBoolean()
    private val failed=AtomicBoolean()
    private val connectionLatch=CountDownLatch(1)
    @Volatile private var peer:NativePeer?=null
    @Volatile var isConnected=false;private set
    @Volatile var cellularActive=false;private set
    val isClosed: Boolean get() = closed.get()
    private var descriptionApplied=false
    private var readyReported=false
    private var revision=1L
    private var highestSequence=0L
    private var startedAt=0L
    private var disconnectedAt=0L
    private var lastStats=0L
    private var lastPoll=0L
    private var generation=1
    @Volatile private var desiredAudio=false
    @Volatile private var desiredMute=false
    private var everConnected=false

    companion object {
        fun isAvailable(context:Context)=NativePeer.isAvailable(context)
        fun select(api:MediaApi,callId:String):JSONObject = api.request("POST","/calls/$callId/media",JSONObject().put("media_transport","webrtc"))
    }
    fun start() {
        check(started.compareAndSet(false,true))
        startedAt=System.nanoTime()
        event("selected")
        worker.execute {
            try {
                if(closed.get())return@execute
                val native=NativePeer(context,callId,servers,pcm,::event,onDigit){ reason -> runCatching { worker.execute { fail(reason) } }; Unit }
                if(closed.get()){native.close();return@execute};peer=native
                native.mute(desiredMute);native.enableAudio(desiredAudio)
                if(!gateway){
                    refresh()
                    val offer=native.offer()
                    send("offer",offer)
                }
                if(!closed.get())worker.scheduleWithFixedDelay(::poll,0,250,TimeUnit.MILLISECONDS)
            }catch(_:Throwable){fail("native_or_offer_setup_failed")}
        }
    }
    private fun poll() {
        if(closed.get())return
        val now=System.nanoTime()
        if(isConnected && now-lastPoll<2_000_000_000L)return
        lastPoll=now
        try {
            val result=refresh()
            val state=result.getJSONObject("state")
            val snapshot=state.getJSONObject("snapshot")
            if(snapshot.getString("phase")=="closed"){fail("server_authorization_closed");return}
            check(snapshot.getJSONObject("binding").getString("leg_id")==legId)
            check(snapshot.getJSONObject("binding").getString("call_id")==callId)
            generation=snapshot.getInt("generation")
            check(generation==1){"unsupported_remote_restart"}
            val signals=state.getJSONArray("signals")
            for(i in 0 until signals.length()){
                val signal=signals.getJSONObject(i);val sequence=signal.getLong("sequence")
                if(sequence<=highestSequence)continue
                check(signal.getString("sender_id")==remoteId && signal.getString("recipient_id")==selfId && signal.getInt("generation")==generation)
                when(signal.getString("kind")){
                    "offer" -> {
                        check(gateway && !descriptionApplied)
                        descriptionApplied=true
                        val answer=checkNotNull(peer).answer(signal.getString("sdp"))
                        // Gathering may run while the other endpoint renews authority.
                        refresh();send("answer",answer)
                    }
                    "answer" -> {
                        check(!gateway && !descriptionApplied)
                        descriptionApplied=true;checkNotNull(peer).remoteAnswer(signal.getString("sdp"));event("answer_applied")
                    }
                    "close" -> {fail("remote_closed");return}
                    else -> error("unsupported_signal")
                }
                highestSequence=sequence
            }
            val connected=peer?.isConnected==true
            isConnected=connected
            if(connected){
                everConnected=true;connectionLatch.countDown();disconnectedAt=0
                if(!gateway && cellularActive && !readyReported){readyReported=true;event("media_and_cellular_ready");onReady()}
            }else if(everConnected){
                if(disconnectedAt==0L)disconnectedAt=now
                if(now-disconnectedAt>10_000_000_000L){fail("ice_recovery_timeout");return}
            }else if(now-startedAt>28_000_000_000L){fail("media_negotiation_timeout");return}
            if(now-lastStats>5_000_000_000L){lastStats=now;peer?.stats()}
        }catch(failure:MediaApiException){
            if(failure.status in listOf(400,401,403,404,410,429)){fail("signal_${failure.code}")}
            else if(System.nanoTime()-lastSuccessfulRead>10_000_000_000L)fail("signal_unavailable")
        }catch(_:Throwable){fail("media_signal_or_native_failure")}
    }
    private var lastSuccessfulRead=System.nanoTime()
    private fun refresh():JSONObject {
        val result=api.request("GET","/rtc/legs/$legId",null)
        val snapshot=result.getJSONObject("state").getJSONObject("snapshot")
        revision=snapshot.getLong("revision")
        cellularActive=result.getBoolean("cellular_active")
        lastSuccessfulRead=System.nanoTime()
        return result
    }
    private fun send(kind:String,sdp:String?=null){
        var command=JSONObject().put("version",1).put("call_id",callId).put("leg_id",legId)
            .put("attempt_id",attemptId).put("operation_id",UUID.randomUUID().toString())
            .put("generation",generation).put("revision",if(kind=="close")0 else revision).put("kind",kind)
        if(sdp!=null)command.put("sdp",sdp)
        for(attempt in 0..2){
            try {
                val response=api.request("POST","/rtc/legs/$legId",command)
                revision=response.getJSONObject("ack").getLong("revision");event("${kind}_committed");return
            }catch(failure:MediaApiException){
                if(failure.status!=409 || attempt==2)throw failure
                refresh();command=JSONObject(command.toString()).put("revision",revision)
            }
        }
    }
    fun awaitConnected(timeoutMillis:Long):Boolean = connectionLatch.await(timeoutMillis,TimeUnit.MILLISECONDS) && peer?.isConnected==true && !closed.get()
    fun enableAudio(enabled:Boolean){desiredAudio=enabled;if(!closed.get())peer?.enableAudio(enabled)}
    fun mute(muted:Boolean){desiredMute=muted;if(!closed.get())peer?.mute(muted)}
    fun sendDtmf(digits:String){check(!closed.get());checkNotNull(peer).sendDtmf(digits)}
    fun markCellularActive(){
        worker.execute {
            if(closed.get())return@execute
            try {api.request("POST","/calls/$callId/media/active",JSONObject().put("leg_id",legId));cellularActive=true;event("cellular_active")}
            catch(_:Throwable){fail("cellular_authority_update_failed")}
        }
    }
    private fun event(event:String){
        val line="media=webrtc call_control=sip call_id=$callId leg_id=$legId role=${if(gateway)"gateway" else "host"} event=$event"
        Log.i("GsmRtc",line);onLog(line)
    }
    private fun fail(reason:String){
        if(closed.get() || !failed.compareAndSet(false,true))return
        event("failed reason=$reason");connectionLatch.countDown()
        // Fail closed even if the app callback itself throws.
        try{onFailure(reason)}finally{close()}
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        connectionLatch.countDown();peer?.close();peer=null
        runCatching{worker.execute {runCatching{send("close")}}};worker.shutdown()
        event("closed")
    }
}
