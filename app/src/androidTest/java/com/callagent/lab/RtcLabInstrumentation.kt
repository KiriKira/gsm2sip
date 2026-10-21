package com.callagent.lab

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.callagent.rtcmedia.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** Real production native peers + production authenticated API relay. Hardware
 * alone is synthetic. This runner does NOT claim SIP/Telecom acceptance. */
class RtcLabInstrumentation : Instrumentation() {
    private var arguments=Bundle()
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);this.arguments=arguments?:Bundle();start()}
    private class Tone(private val frequency:Double,private val remote:Double){
        private var cursor=0L
        private val wave=IntArray(160){(6500*sin(2*PI*frequency*it/16000)).toInt()}
        val matches=AtomicLong();val silence=AtomicLong();val frames=AtomicLong()
        fun frame():ByteArray=ByteArray(640).also{out->repeat(320){i->
            val v=wave[(cursor++%wave.size).toInt()]
            out[2*i]=v.toByte();out[2*i+1]=(v shr 8).toByte()
        }}
        fun accept(data:ByteArray){
            var energy=0.0
            fun power(hz:Double):Double{
                val coefficient=2*cos(2*PI*hz/16000)
                var previous=0.0;var before=0.0
                for(i in 0 until data.size/2){val v=((data[2*i].toInt() and 255) or (data[2*i+1].toInt() shl 8)).toDouble()
                    val next=v+coefficient*previous-before;before=previous;previous=next
                }
                return previous*previous+before*before-coefficient*previous*before
            }
            for(i in 0 until data.size/2){val v=((data[2*i].toInt() and 255) or (data[2*i+1].toInt() shl 8)).toDouble();energy+=v*v}
            val rms=sqrt(energy/(data.size/2));frames.incrementAndGet()
            if(rms<100)silence.incrementAndGet()
            if(rms>400 && power(remote)>power(frequency)*8)matches.incrementAndGet()
        }
        fun json()=JSONObject().put("decoded_frames",frames.get()).put("remote_tone_frames",matches.get()).put("silent_frames",silence.get())
    }
    private fun api(base:String,token:String)=MediaApi{method,path,body->
        val c=URL(base+path).openConnection() as HttpURLConnection
        c.requestMethod=method;c.connectTimeout=5000;c.readTimeout=5000
        c.setRequestProperty("Authorization","Bearer $token")
        if(body!=null){c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toString().toByteArray())}}
        try{val code=c.responseCode;val stream=if(code in 200..299)c.inputStream else c.errorStream
            val result=stream?.bufferedReader()?.use{JSONObject(it.readText())}?:JSONObject()
            if(code !in 200..299)throw MediaApiException(code,result.optJSONObject("error")?.optString("code")?:"HTTP_$code")
            result
        }finally{c.disconnect()}
    }
    override fun onStart(){
        val result=JSONObject().put("scope","production_native_audio_and_authenticated_http_relay").put("sip_telecom_acceptance",false)
        val errors=CopyOnWriteArrayList<String>();val events=CopyOnWriteArrayList<String>();val digits=AtomicInteger()
        val feeder=Executors.newSingleThreadScheduledExecutor()
        var host:RtcAudioSession?=null;var gateway:RtcAudioSession?=null;var closing=false
        var passed=false;var stage="fixture"
        val a=Tone(500.0,900.0);val b=Tone(900.0,500.0)
        try{
            check(android.os.Build.SUPPORTED_ABIS.contains("x86_64")){"emulator_fixture_only"}
            val fixture=JSONObject(targetContext.filesDir.resolve("rtc-lab-fixture.json").readText())
            val base=fixture.getString("base_url");val call=fixture.getString("call_id")
            val hostApi=api(base,fixture.getString("client_token"));val gatewayApi=api(base,fixture.getString("gateway_token"))
            gatewayApi.request("POST","/devices/self/rtc-capability",JSONObject().put("version",1))
            val admission=RtcAudioSession.select(hostApi,call)
            val gatewayAdmission=gatewayApi.request("GET","/calls/$call/media",null)
            val hostPcm=PcmBridge();val gatewayPcm=PcmBridge()
            hostPcm.onDecodedFrame=a::accept;gatewayPcm.onDecodedFrame=b::accept
            fun fail(reason:String){if(!closing)errors.add(reason)}
            host=RtcAudioSession(targetContext,admission,hostApi,false,hostPcm,onFailure=::fail,onLog=events::add)
            gateway=RtcAudioSession(targetContext,gatewayAdmission,gatewayApi,true,gatewayPcm,onDigit={digits.incrementAndGet()},onFailure=::fail,onLog=events::add)
            stage="native_connect";gateway.start();host.start()
            check(host.awaitConnected(30000) && gateway.awaitConnected(5000)){"native_connect_timeout"}
            feeder.scheduleAtFixedRate({hostPcm.capture(a.frame(),16000);gatewayPcm.capture(b.frame(),16000)},0,20,TimeUnit.MILLISECONDS)
            host.enableAudio(true);gateway.enableAudio(true);gateway.markCellularActive()
            fun await(code:String,seconds:Int=45,predicate:()->Boolean){
                val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong())
                while(!predicate()){check(errors.isEmpty()){errors.joinToString()};check(System.nanoTime()<end){code};Thread.sleep(100)}
            }
            stage="cross_tones";await("cross_tone_missing"){a.matches.get()>25 && b.matches.get()>25 && host.cellularActive}
            result.put("initial_host",a.json()).put("initial_gateway",b.json())
            stage="mute";host.mute(true);Thread.sleep(1500)
            val silent=b.silence.get();val matched=b.matches.get();val reverse=a.matches.get()
            await("mute_frames_not_received"){b.silence.get()-silent>=30 && a.matches.get()-reverse>=20}
            check(b.matches.get()-matched<5){"mute_did_not_isolate_host_audio"}
            host.mute(false);val resumed=b.matches.get();await("unmute_not_restored"){b.matches.get()-resumed>30}
            stage="dtmf";host.sendDtmf("5");await("dtmf_not_received"){digits.get()==1}
            stage="authorization_and_replay_lifetime"
            Thread.sleep(65000)
            check(errors.isEmpty() && host.isConnected && gateway.isConnected && !host.isClosed && !gateway.isClosed){"long_call_failed"}
            result.put("host",a.json()).put("gateway",b.json()).put("dtmf_events",digits.get())
                .put("grant_renewal_over_60s",true).put("survives_replay_ttl",true)
            passed=true
        }catch(failure:Throwable){result.put("failure",failure.javaClass.simpleName+":"+failure.message)}
        finally{
            closing=true;feeder.shutdownNow();host?.close();gateway?.close()
            result.put("signal_workers_stopped",(host?.awaitShutdown(10000)?:true) && (gateway?.awaitShutdown(10000)?:true))
            result.put("final_host",a.json()).put("final_gateway",b.json())
            result.put("errors",JSONArray(errors.toList())).put("events",JSONArray(events.toList())).put("stage",stage)
            result.put("status",if(passed)"PASS" else "FAIL")
            targetContext.filesDir.resolve("rtc-lab-result.json").writeText(result.toString(2))
            finish(if(passed)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{putString("rtc_result",result.toString())})
        }
    }
}
