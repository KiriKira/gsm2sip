package com.callagent.lab

import com.callagent.rtcmedia.PcmBridge
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** Only packaged in instrumentation: synthetic replacement of physical PCM. */
class LabPcmTone(private val local:Double,private val remote:Double, val pcm:PcmBridge=PcmBridge()) : AutoCloseable {
    private var cursor=0L
    private val wave=IntArray(160){(6500*sin(2*PI*local*it/16000)).toInt()}
    val captured=AtomicLong();val decoded=AtomicLong();val matched=AtomicLong();val silent=AtomicLong()
    @Volatile var muted=false
    private val worker=Executors.newSingleThreadScheduledExecutor()
    fun start(){
        pcm.onDecodedFrame={frame->
            val samples=DoubleArray(frame.size/2){i-> ((frame[2*i].toInt() and 255) or (frame[2*i+1].toInt() shl 8)).toDouble()}
            val rms=if(muted)0.0 else sqrt(samples.sumOf{it*it}/samples.size)
            fun power(f:Double):Double{val c=2*cos(2*PI*f/16000);var p=0.0;var b=0.0;for(x in samples){val n=x+c*p-b;b=p;p=n};return p*p+b*b-c*p*b}
            decoded.incrementAndGet()
            if(rms<100)silent.incrementAndGet()
            if(rms>400 && power(remote)>power(local)*8)matched.incrementAndGet()
        }
        worker.scheduleAtFixedRate({
            val frame=ByteArray(640)
            repeat(320){i->val v=wave[(cursor++%wave.size).toInt()];frame[2*i]=v.toByte();frame[2*i+1]=(v shr 8).toByte()}
            pcm.capture(frame,16000);captured.incrementAndGet()
        },0,20,TimeUnit.MILLISECONDS)
    }
    fun report()=JSONObject().put("hardware","SYNTHETIC_PCM_16K").put("capture_frames",captured.get()).put("decoded_frames",decoded.get())
        .put("remote_tone_frames",matched.get()).put("silent_frames",silent.get()).put("queue_underruns",pcm.underruns.get()).put("queue_overruns",pcm.overruns.get())
    override fun close(){worker.shutdownNow();pcm.clear()}
}
