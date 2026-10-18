package com.callagent.lab

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import com.callagent.gateway.MainActivity
import com.callagent.gateway.service.GatewayService
import com.callagent.gateway.net.ControlApiClient
import com.callagent.gateway.data.*
import com.callagent.gateway.sim.SimRegistry
import com.callagent.gateway.bridge.CallOrchestrator
import com.callagent.gateway.rtc.GatewayRtcMedia
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.rtp.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the actual GatewayService and CallOrchestrator. Only physical
 * cellular PCM is replaced; pairing, SIM mapping, SIP, RTC and Telecom are real. */
@android.annotation.TargetApi(29)
class GatewayJointInstrumentation:Instrumentation(){
    private val tones=CopyOnWriteArrayList<LabPcmTone>()
    @Volatile private var orch:CallOrchestrator?=null
    private var stage="init"
        set(value){field=value;runCatching{targetContext.filesDir.resolve("joint-stage.txt").writeText(value)}}
    private fun onMain(action:()->Unit){
        var failure:Throwable?=null
        runOnMainSync{try{action()}catch(error:Throwable){failure=error}}
        failure?.let{throw it}
    }
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private class PcmHardware(private val tone:LabPcmTone):CallAudioSession{
        override var listener:RtpSession.Listener?=null
        override var srtpSend:SrtpContext?=null
        override var srtpRecv:SrtpContext?=null
        override fun start(){check(GsmCallManager.isCallActive){"pcm_before_telecom_active"};tone.start();listener?.onRtpStarted()}
        override fun stop(){tone.close();listener?.onRtpStopped()}
        override fun setAgentMuted(on:Boolean){tone.muted=on}
        override fun setMonitorEnabled(on:Boolean){check(!on){"synthetic_hardware_has_no_speaker"}}
    }
    override fun onStart(){
        var activity:Activity?=null
        val result=JSONObject().put("scope","real_gateway_service_sip_rtc_telecom_with_synthetic_cellular_pcm")
        try{
            check(android.os.Build.SUPPORTED_ABIS.contains("x86_64")){"emulator_only"}
            uiAutomation.adoptShellPermissionIdentity()
            val telecom=targetContext.getSystemService(android.telecom.TelecomManager::class.java)
            val telephony=targetContext.getSystemService(android.telephony.TelephonyManager::class.java)
            val accountDiagnostics=JSONArray()
            for(handle in telecom.callCapablePhoneAccounts.orEmpty()){
                val row=JSONObject().put("component",handle.componentName.flattenToString())
                try{
                    val subId=telephony.getSubscriptionId(handle)
                    row.put("subscription_id",subId).put("sim_subscription",telecom.getPhoneAccount(handle)?.hasCapabilities(android.telecom.PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION))
                        .put("forward_matches",subId>=0 && telephony.createForSubscriptionId(subId).phoneAccountHandle==handle)
                }catch(error:Throwable){row.put("error",error.javaClass.simpleName+":"+error.message)}
                accountDiagnostics.put(row)
            }
            targetContext.filesDir.resolve("joint-real-telecom.json").writeText(accountDiagnostics.toString())
            val config=JSONObject(targetContext.filesDir.resolve("joint-fixture.json").readText())
            onMain{targetContext.stopService(Intent(targetContext,GatewayService::class.java))}
            Thread.sleep(500)
            stage="pair"
            val api=ControlApiClient(targetContext,config.getString("api_url"))
            val reuse=config.optBoolean("reuse_pairing")
            if(!reuse)api.pair(config.getString("pairing_code"),"RTC joint emulator gateway")
            val paired=checkNotNull(CredentialStore.load(targetContext))
            stage="bind_emulated_sim"
            val snapshot=SimRegistry.snapshot(targetContext,includeVoice=true)
            check(snapshot.subscriptions.isNotEmpty()){"emulator_sim_missing"}
            check(snapshot.subscriptions.all{it.voiceAvailable}){"real_emulator_telecom_account_unavailable"}
            val confirmed=if(reuse){
                check(snapshot.ownerGatewayId==paired.gatewayId){"resume_gateway_identity_changed"}
                check(snapshot.ownerControlBaseUrl==paired.controlBaseUrl){"resume_gateway_origin_changed"}
                snapshot.mappings.single().simId to snapshot.mappingRevision
            }else{
                val mappings=JSONArray()
                snapshot.subscriptions.sortedBy{it.slotIndex}.forEach{mappings.put(JSONObject().put("slot_index",it.slotIndex).put("label","Emulated SIM").put("carrier_name",it.carrierName).put("phone_number",JSONObject.NULL))}
                val proposal=api.proposeSimBindings(UUID.randomUUID().toString(),mappings)
                api.confirmSimBindings(proposal.operationId,JSONArray().apply{proposal.mappings.forEach{put(JSONObject().put("sim_id",it.simId).put("confirmed",true).put("slot_index",it.slotIndex))}})
                SimRegistry.confirmMappings(targetContext,proposal.mappings.map{m->SimRegistry.SimBindingConfirmation(m.simId,snapshot.subscriptions.single{it.slotIndex==m.slotIndex}.subscriptionId)},proposal.mappingRevision)
                VoiceCredentialStore.saveProvisioned(targetContext,paired.gatewayId,api.rotateSipCredentials(UUID.randomUUID().toString()))
                proposal.mappings.first().simId to proposal.mappingRevision
            }
            val prefs=targetContext.getSharedPreferences("gateway",0)
            val server=checkNotNull(prefs.getString("server",null))
            val username=checkNotNull(prefs.getString("user",null))
            val password=VoiceCredentialStore.password(targetContext,server,username)
            check(password.isNotBlank()){"resume_sip_credentials_missing"}
            targetContext.getSharedPreferences("gateway",0).edit().putBoolean("use_stun",false).putString("own_number","+15550100299").commit()
            GatewayService.orchestratorFactoryForInstrumentation={service,sip->
                // The synthetic backend supplies PCM directly and needs no
                // physical modem mixer profile. SIM resolution and Telecom
                // dispatch still use the production implementations.
                CallOrchestrator(service,sip,outboundAudioPreparation={true},audioFactory={audio->
                    if(audio.externalPcm==null)audio.realSession() else {
                        val tone=LabPcmTone(900.0,500.0,checkNotNull(audio.externalPcm))
                        tones.add(tone)
                        PcmHardware(tone)
                    }
                },rtcAdapter=GatewayRtcMedia(service)).also{orch=it}
            }
            stage="service_start"
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onMain{check(GatewayService.start(targetContext,server,prefs.getInt("port",5061),username,password)){"service_start_denied"}}
            val ready=JSONObject().put("status","READY").put("gateway_id",paired.gatewayId).put("sim_id",confirmed.first).put("mapping_revision",confirmed.second)
            targetContext.filesDir.resolve("joint-ready.json").writeText(ready.toString())
            var last="";val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.HOURS.toNanos(6)
            stage="commands"
            while(System.nanoTime()<deadline){
                Thread.sleep(200)
                val command=runCatching{JSONObject(targetContext.filesDir.resolve("joint-command.json").readText())}.getOrNull()?:continue
                val id=command.getString("id");if(id==last)continue;last=id
                val reply=JSONObject().put("id",id)
                try{
                    when(command.getString("op")){
                        "hangup"->onMain{GsmCallManager.hangupCall()}
                        "mute"->orch?.setAgentMuted(command.getBoolean("enabled"))
                        "status"->Unit
                        "stop"->break
                        else->error("unknown_op")
                    }
                    reply.put("status","OK").put("bridge",orch?.bridgeState?.name).put("media",orch?.mediaTransport)
                        .put("telecom_state",GsmCallManager.activeCallState).put("pcm",JSONArray(tones.map{it.report()}))
                        .put("dtmf_events",JSONArray(GatewayService.logSnapshot().filter{it.contains("DTMF received and played") || it.contains("DTMF tone stopped")}))
                }catch(failure:Throwable){reply.put("status","FAIL").put("error",failure.javaClass.simpleName+":"+failure.message)}
                targetContext.filesDir.resolve("joint-result.json").writeText(reply.toString())
            }
            result.put("status","PASS")
        }catch(failure:Throwable){result.put("status","FAIL").put("error",failure.javaClass.simpleName+":"+failure.message)}
        finally{
            runCatching{uiAutomation.dropShellPermissionIdentity()}
            GatewayService.orchestratorFactoryForInstrumentation=null
            runCatching{onMain{GatewayService.stop(targetContext);activity?.finish()}}
            tones.forEach{it.close()};result.put("stage",stage)
            targetContext.filesDir.resolve("joint-finished.json").writeText(result.toString())
            finish(Activity.RESULT_OK,Bundle().apply{putString("joint_result",result.toString())})
        }
    }
}
