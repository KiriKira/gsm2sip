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
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.audio.CallAudioSession
import com.callagent.gateway.audio.CellularAudioSession
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the actual GatewayService and CallOrchestrator. Only physical
 * cellular PCM is replaced; pairing, SIM mapping, RTC call control, WebRTC
 * media and Telecom are real. */
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
        override var listener:CellularAudioSession.Listener?=null
        override fun start(){check(GsmCallManager.isCallActive){"pcm_before_telecom_active"};tone.start();listener?.onAudioStarted()}
        override fun stop(){tone.close();listener?.onAudioStopped()}
        override fun setAgentMuted(on:Boolean){tone.muted=on}
        override fun setMonitorEnabled(on:Boolean){check(!on){"synthetic_hardware_has_no_speaker"}}
    }
    override fun onStart(){
        var activity:Activity?=null
        var automation:android.app.UiAutomation?=null
        val result=JSONObject().put("scope","real_gateway_service_rtc_telecom_with_synthetic_cellular_pcm")
        try{
            check(android.os.Build.SUPPORTED_ABIS.contains("x86_64")){"emulator_only"}
            // Only shell permissions are needed here. Leave accessibility free
            // for the UI smoke runner while this long-lived fixture is active.
            automation=getUiAutomation(if(android.os.Build.VERSION.SDK_INT>=31)
                android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY else 0).also{it.adoptShellPermissionIdentity()}
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
            var snapshot=SimRegistry.snapshot(targetContext,includeVoice=true)
            check(snapshot.subscriptions.isNotEmpty()){"emulator_sim_missing"}
            check(snapshot.subscriptions.all{it.voiceAvailable}){"real_emulator_telecom_account_unavailable"}
            // A fingerprint-less virtual SIM needs explicit local confirmation
            // after an AVD reboot. The lab operator identifies the retained
            // modem/card; use the normal proposal/confirmation contracts and
            // registry checks, keeping its original server SIM UUID.
            val retainedSim=config.optString("reconfirm_retained_emulator_sim")
            if(retainedSim.isNotEmpty()){
                check(reuse && snapshot.ownerGatewayId==paired.gatewayId)
                val previous=snapshot.mappings.single()
                val subscription=snapshot.subscriptions.single()
                check(previous.simId==retainedSim && previous.subscriptionId==subscription.subscriptionId && previous.slotIndex==subscription.slotIndex)
                val operation=UUID.fromString(config.getString("reconfirm_operation_id")).toString()
                val mappings=JSONArray().put(JSONObject().put("slot_index",subscription.slotIndex)
                    .put("label","Emulated SIM").put("carrier_name",subscription.carrierName).put("phone_number",JSONObject.NULL)
                    .put("existing_sim_id",retainedSim).put("same_sim_verified",true))
                val proposal=api.proposeSimBindings(operation,mappings)
                check(proposal.mappings.single().simId==retainedSim)
                api.confirmSimBindings(proposal.operationId,JSONArray().put(JSONObject().put("sim_id",retainedSim)
                    .put("confirmed",true).put("slot_index",subscription.slotIndex)))
                if(snapshot.mappingRevision<proposal.mappingRevision){
                    SimRegistry.confirmMappings(targetContext,listOf(SimRegistry.SimBindingConfirmation(retainedSim,subscription.subscriptionId)),proposal.mappingRevision)
                }
                snapshot=SimRegistry.snapshot(targetContext,includeVoice=true)
                check(snapshot.mappings.single().identityState!=SimRegistry.IdentityState.UNVERIFIED)
                config.remove("reconfirm_retained_emulator_sim");config.remove("reconfirm_operation_id")
                targetContext.filesDir.resolve("joint-fixture.json").writeText(config.toString())
            }
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
                proposal.mappings.first().simId to proposal.mappingRevision
            }
            check(api.rtcControlAvailable()){"server_without_rtc_call_control"}
            GatewayService.orchestratorFactoryForInstrumentation={service->
                // The synthetic backend supplies PCM directly and needs no
                // physical modem mixer profile. SIM resolution and Telecom
                // dispatch still use the production implementations.
                CallOrchestrator(service,outboundAudioPreparation={true},audioFactory={audio->
                    val tone=LabPcmTone(900.0,500.0,audio.pcm)
                    tones.add(tone)
                    PcmHardware(tone)
                }).also{orch=it}
            }
            stage="service_start"
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onMain{check(GatewayService.start(targetContext)){"service_start_denied"}}
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
                        "reconnect"->{
                            check(orch?.bridgeState==CallOrchestrator.BridgeState.IDLE){"active_call_reconnect_forbidden"}
                            onMain{GatewayService.stop(targetContext)}
                            Thread.sleep(700)
                            onMain{check(GatewayService.start(targetContext)){"reconnect_start_denied"}}
                        }
                        "mesh_add"->{
                            val node=com.callagent.gateway.mesh.MeshPairing.add(targetContext,command.getString("api_url"),command.getString("pairing_code"),"RTC joint gateway mesh")
                            reply.put("added_gateway_id",node.gatewayId)
                        }
                        "hangup"->onMain{GsmCallManager.hangupCall()}
                        "mute"->orch?.setAgentMuted(command.getBoolean("enabled"))
                        "status"->Unit
                        "stop"->break
                        else->error("unknown_op")
                    }
                    reply.put("status","OK").put("bridge",orch?.bridgeState?.name).put("media","webrtc").put("call_control","rtc")
                        .put("telecom_state",GsmCallManager.activeCallState).put("pcm",JSONArray(tones.map{it.report()}))
                        .put("dtmf_events",JSONArray(GatewayService.logSnapshot().filter{it.contains("DTMF received and played") || it.contains("DTMF tone stopped")}))
                }catch(failure:Throwable){reply.put("status","FAIL").put("error",failure.javaClass.simpleName+":"+failure.message)}
                targetContext.filesDir.resolve("joint-result.json").writeText(reply.toString())
            }
            result.put("status","PASS")
        }catch(failure:Throwable){result.put("status","FAIL").put("error",failure.javaClass.simpleName+":"+failure.message)}
        finally{
            runCatching{automation?.dropShellPermissionIdentity()}
            GatewayService.orchestratorFactoryForInstrumentation=null
            runCatching{onMain{GatewayService.stop(targetContext);activity?.finish()}}
            tones.forEach{it.close()};result.put("stage",stage)
            targetContext.filesDir.resolve("joint-finished.json").writeText(result.toString())
            finish(Activity.RESULT_OK,Bundle().apply{putString("joint_result",result.toString())})
        }
    }
}
