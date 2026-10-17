package com.callagent.gateway.rtc

import android.content.Context
import com.callagent.gateway.data.CredentialStore
import com.callagent.gateway.mesh.MeshVaults
import com.callagent.gateway.net.ControlApiClient
import com.callagent.gateway.sip.SipCall
import com.callagent.rtcmedia.MediaApi
import com.callagent.rtcmedia.MediaApiException
import com.callagent.rtcmedia.PcmBridge
import com.callagent.rtcmedia.RtcAudioSession

/** Uses exactly the winning SIP node's existing paired identity. No node
 * forwarding and no second cellular executor/dispatch ledger are introduced. */
class GatewayRtcMedia(private val context: Context) {
    data class Prepared(val session: RtcAudioSession, val pcm: PcmBridge)

    fun prepare(call: SipCall, callId: String, valid: () -> Boolean,
                publish: (Prepared) -> Boolean, log: (String) -> Unit,
                digit: (Char) -> Unit, failure: (String) -> Unit): Prepared? {
        val vault=call.client.nodeGatewayId?.let(MeshVaults::forNode) ?: CredentialStore
        val session=checkNotNull(vault.load(context)){"RTC_PAIRED_NODE_REQUIRED"}
        val client=ControlApiClient(context,session.controlBaseUrl,vault,5000)
        val api=MediaApi{method,path,body->client.rtcRequest(method,path,body)}
        val deadline=System.nanoTime()+6_000_000_000L
        var admission=try{api.request("GET","/calls/$callId/media",null)}catch(error:MediaApiException){
            // Old/explicitly disabled servers cannot admit WebRTC calls. Other
            // errors leave the mode unknown and MUST NOT downgrade to SIP.
            if(error.status==404 || error.code=="RTC_MEDIA_DISABLED"){
                log("media=sip call_control=sip call_id=$callId event=selected reason=server_without_rtc");return null
            }
            throw error
        }
        while(admission.getString("media_transport")=="pending"){
            check(valid() && System.nanoTime()<deadline){"RTC_WINNER_NOT_READY"}
            Thread.sleep(150)
            admission=api.request("GET","/calls/$callId/media",null)
        }
        if(admission.getString("media_transport")=="sip"){
            log("media=sip call_control=sip call_id=$callId event=selected");return null
        }
        check(admission.getString("media_transport")=="webrtc")
        check(admission.getJSONObject("binding").getString("gateway_id")==session.gatewayId)
        check(admission.getJSONObject("binding").getString("call_id")==callId)
        val pcm=PcmBridge()
        val media=RtcAudioSession(context,admission,api,true,pcm,onDigit=digit,onFailure=failure,onLog=log)
        val prepared=Prepared(media,pcm)
        try{
            check(publish(prepared) && valid()){"RTC_CALL_RETIRED"}
            media.start()
            val until=System.nanoTime()+25_000_000_000L
            while(valid() && System.nanoTime()<until && !media.isClosed){
                if(media.awaitConnected(250))return prepared
            }
            error("RTC_MEDIA_NOT_CONNECTED")
        }catch(error:Throwable){media.close();throw error}
    }
}
