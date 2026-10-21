package com.callagent.gateway.rtc

import android.content.Context
import android.util.Log
import com.callagent.gateway.data.CredentialStore
import com.callagent.gateway.data.GatewayDatabase
import com.callagent.gateway.data.SessionSource
import com.callagent.gateway.mesh.MeshVaults
import com.callagent.gateway.net.ControlApiClient
import com.callagent.gateway.bridge.CallOrchestrator
import com.callagent.gateway.sim.SimRegistry
import com.callagent.rtcmedia.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Per-node authenticated control loops. They share exactly one physical
 * orchestrator, SIM validator, occupancy and durable cellular executor. */
class GatewayCallControl(private val context: Context, private val orchestrator: CallOrchestrator) : AutoCloseable {
    class Node(val gatewayId: String, val api: MediaApi, val control: RtcCallControl, val primary: Boolean) {
        @Volatile var ready = false
        @Volatile var checked = false
        @Volatile var supported = false
        @Volatile var lastSeen = System.nanoTime()
        var capabilityAt = 0L
    }
    private val closed=AtomicBoolean()
    private val nodes=ConcurrentHashMap<String,Node>()
    private val workers=ConcurrentHashMap<String,java.util.concurrent.ScheduledExecutorService>()
    private val incomingOffers=ConcurrentHashMap<String,IncomingForkCoordinator>()
    private val reconciler=Executors.newSingleThreadScheduledExecutor { Thread(it,"RTC-Gateway-Nodes") }
    val ready: Boolean get()=nodes.values.any{it.ready}
    fun start(){reconciler.scheduleWithFixedDelay(::reconcile,0,10,TimeUnit.SECONDS)}
    private fun reconcile(){
        if(closed.get())return
        val sources=buildList<Pair<SessionSource,Boolean>>{
            add(CredentialStore to true)
            GatewayDatabase.get(context).meshNodes().forEach{add(MeshVaults.forNode(it.gatewayId) to false)}
        }
        val wanted=hashSetOf<String>()
        sources.forEach{(vault,primary)->
            val session=runCatching{vault.load(context)}.getOrNull()?:return@forEach
            wanted.add(session.gatewayId)
            if(nodes.containsKey(session.gatewayId))return@forEach
            val client=ControlApiClient(context,session.controlBaseUrl,vault,3000)
            val api=MediaApi{method,path,body->client.rtcRequest(method,path,body)}
            val node=Node(session.gatewayId,api,RtcCallControl(api),primary)
            nodes[node.gatewayId]=node
            val worker=Executors.newSingleThreadScheduledExecutor{Thread(it,"RTC-Gateway-${node.gatewayId.take(8)}")}
            workers[node.gatewayId]=worker
            worker.scheduleWithFixedDelay({poll(node,client)},0,750,TimeUnit.MILLISECONDS)
        }
        (nodes.keys-wanted).forEach{id->workers.remove(id)?.shutdownNow();nodes.remove(id)}
    }
    private fun poll(node:Node,client:ControlApiClient){
        if(closed.get())return
        try{
            val now=System.nanoTime()
            if(now-node.capabilityAt>25_000_000_000L){
                node.supported=client.rtcControlAvailable();node.checked=true;node.capabilityAt=now
                if(!node.supported){node.ready=false;return}
                check(RtcAudioSession.isAvailable(context)){"RTC_NATIVE_UNAVAILABLE"}
                node.control.capability(true)
                node.api.request("POST","/devices/self/rtc-capability",JSONObject().put("version",1))
            }
            if(!node.supported)return
            GatewayDatabase.get(context).rtcTerminals(node.gatewayId).forEach{(id,kind)->
                try {
                    val snapshot=node.control.get(id)
                    if(snapshot.getString("state")!="ended")node.control.operation(id,kind)
                    GatewayDatabase.get(context).acknowledgeRtcTerminal(id,node.gatewayId)
                }catch(error:MediaApiException){if(error.status==404)GatewayDatabase.get(context).acknowledgeRtcTerminal(id,node.gatewayId)else throw error}
            }
            val calls=node.control.calls();node.lastSeen=System.nanoTime();val wasReady=node.ready;node.ready=true
            if(!wasReady)orchestrator.onRtcNodeReady()
            calls.forEach{orchestrator.onRtcControlSnapshot(node,it)}
        }catch(_:Throwable){
            node.ready=false
            if(System.nanoTime()-node.lastSeen>15_000_000_000L)orchestrator.onRtcControlLost(node)
        }
    }
    /** Offers the ringing cellular call to every ready node. A definite create
     * failure is left out; an ambiguous response still counts as a possible offer.
     * Once offered, errors end this call; it is never offered a second time. */
    fun offerIncoming(mapping:SimRegistry.SimMapping,id:String,number:String,valid:()->Boolean){
        val eligible=nodes.values.filter{it.supported && it.ready}
        check(eligible.isNotEmpty()){"RTC_NO_READY_NODE"}
        check(GatewayDatabase.get(context).reserveRtcCellularSlot(id)){"RTC_CELLULAR_OCCUPIED"}
        val fork=IncomingForkCoordinator(eligible.map{it.gatewayId})
        check(incomingOffers.putIfAbsent(id,fork)==null){"RTC_INCOMING_ALREADY_OFFERED"}
        var offered=0
        try {
            for(node in eligible){
                check(valid()){"RTC_INCOMING_RETIRED"}
                if(!fork.beginCreate(node.gatewayId))break
                var requestAttempted=false
                var snapshot:JSONObject?=null
                var failure:Throwable?=null
                try{
                    val revision=if(node.primary)mapping.mappingRevision else GatewayDatabase.get(context).meshNodeSims(node.gatewayId)
                        .firstOrNull{it.simId.equals(mapping.simId,true)&&it.state=="active"}?.mappingRevision
                    checkNotNull(revision){"RTC_NODE_SIM_NOT_BOUND"}
                    requestAttempted=true
                    snapshot=node.control.create(id,node.gatewayId,mapping.simId,revision,
                        "",true,number.takeIf{it.isNotBlank()})
                }catch(error:Throwable){failure=error}

                val outcome=when{
                    snapshot!=null->IncomingForkCoordinator.CreateOutcome.CREATED
                    !requestAttempted->IncomingForkCoordinator.CreateOutcome.REJECTED
                    failure?.let(::mayHaveCommittedCreate)==true->IncomingForkCoordinator.CreateOutcome.UNKNOWN
                    else->IncomingForkCoordinator.CreateOutcome.REJECTED
                }
                val deliveries=fork.completeCreate(node.gatewayId,outcome)
                deliveries.forEach{GatewayDatabase.get(context).queueRtcTerminal(id,it.nodeGatewayId,it.kind)}

                if(snapshot!=null){
                    offered++
                    try{orchestrator.onRtcControlSnapshot(node,snapshot!!)}catch(error:Exception){
                        Log.w("GsmRtc","call_id=$id event=incoming_snapshot_failed node=${node.gatewayId.take(8)} error=${error.javaClass.simpleName}")
                    }
                }else{
                    Log.w("GsmRtc","call_id=$id event=incoming_offer_failed node=${node.gatewayId.take(8)} error=${failure?.javaClass?.simpleName?:"CreateRejected"}")
                }
            }
            check(offered>0){"RTC_INCOMING_NOT_OFFERED"}
            Log.i("GsmRtc","call_id=$id event=incoming_offered nodes=$offered")
        }finally{
            fork.finishOffers()
            incomingOffers.remove(id,fork)
        }
    }
    fun winner(id:String,winningNode:String){
        val deliveries=incomingOffers[id]?.winner(winningNode)
            ?:nodes.values.filter{it.supported && it.gatewayId!=winningNode}.map{IncomingForkCoordinator.TerminalDelivery(it.gatewayId,"answered_elsewhere")}
        deliveries.forEach{GatewayDatabase.get(context).queueRtcTerminal(id,it.nodeGatewayId,it.kind)}
    }
    fun ended(id:String,state:String){
        val fork=incomingOffers[id]
        val terminalNodes=fork?.ended()?.map{it.nodeGatewayId}
            ?:nodes.values.filter{it.supported}.map{it.gatewayId}
        GatewayDatabase.get(context).completeRtcCellular(id,state,terminalNodes)
    }

    override fun close(){
        if(!closed.compareAndSet(false,true))return
        reconciler.shutdownNow();workers.values.forEach{it.shutdownNow()};workers.clear();nodes.clear()
    }
}
