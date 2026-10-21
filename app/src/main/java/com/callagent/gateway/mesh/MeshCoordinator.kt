package com.callagent.gateway.mesh

import android.content.Context
import com.callagent.gateway.data.CredentialStore
import com.callagent.gateway.data.GatewayDatabase
import com.callagent.gateway.data.SessionVault
import com.callagent.gateway.net.ControlApiClient
import com.callagent.gateway.net.ControlApiException
import com.callagent.gateway.net.ControlExtensions
import com.callagent.gateway.net.HeartbeatSim
import com.callagent.gateway.sim.RadioSnapshot
import com.callagent.gateway.sim.RadioTelemetry
import com.callagent.gateway.sim.SimRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.UUID

/** Encrypted credentials for one additional mesh node. */
object MeshVaults {
    private const val PREFS = "mesh-sessions"

    fun forNode(gatewayId: String): SessionVault = SessionVault(PREFS, "node_${gatewayId.lowercase()}")
}

/**
 * Device facts shared by every node's heartbeat in one round, collected once
 * by the service.
 */
data class MeshHeartbeatInputs(
    val root: Boolean,
    val batteryPercent: Int?,
    val charging: Boolean,
    val serviceStateFor: (subscriptionId: Int) -> String
)

/**
 * Serves every additional (secondary) node next to the primary pairing.
 *
 * The primary pairing stays authoritative for local SIM confirmation. Each
 * secondary node is bound to the same confirmed SIMs under the same SIM IDs,
 * receives its own copy of every inbound SMS, and may send commands; the
 * shared command ledger refuses a message_id that another node already
 * delivered and only mirrors that execution back as an alias.
 */
class MeshCoordinator(
    context: Context,
    private val processCommands: (ControlApiClient, GatewayDatabase) -> Unit,
    private val log: (String) -> Unit
) {
    private val app = context.applicationContext
    private val database = GatewayDatabase.get(app)

    /** Sync every node independently; one unreachable node never blocks another. */
    fun syncAll(inputs: MeshHeartbeatInputs) {
        if (CredentialStore.load(app) == null) return
        database.meshNodes().forEach { node ->
            try {
                syncNode(node, inputs)
                database.recordMeshSync(node.gatewayId, null)
            } catch (e: ControlApiException) {
                database.recordMeshSync(node.gatewayId, e.code)
                log("MESH: ${hostOf(node.controlBaseUrl)} ${e.code}")
            } catch (e: Exception) {
                database.recordMeshSync(node.gatewayId, "SYNC_FAILED")
                log("MESH: ${hostOf(node.controlBaseUrl)} sync failed")
            }
        }
    }

    private fun syncNode(node: GatewayDatabase.MeshNode, inputs: MeshHeartbeatInputs) {
        val vault = MeshVaults.forNode(node.gatewayId)
        if (vault.load(app) == null) throw ControlApiException("PAIRING_REQUIRED", "节点凭据缺失，请重新添加该节点")
        val client = ControlApiClient(app, node.controlBaseUrl, vault)
        val snapshot = SimRegistry.snapshot(app)
        ensureBindings(node, client, snapshot)
        heartbeat(node, client, snapshot, inputs)
        database.emitAliasEvents(node.gatewayId)
        val events = database.pendingMeshEvents(node.gatewayId)
        if (events.isNotEmpty()) {
            val acked = client.uploadEvents(events)
            database.acknowledgeEvents(acked)
        }
        processCommands(client, database)
    }

    /**
     * Keep the node's SIM bindings equal to the primary's locally confirmed
     * SIMs, under the same IDs. The user confirmed these SIMs once on this
     * phone; the node only learns which IDs are live.
     */
    private fun ensureBindings(node: GatewayDatabase.MeshNode, client: ControlApiClient,
                               snapshot: SimRegistry.SimRegistrySnapshot) {
        val confirmed = snapshot.mappings.filter { it.identityState != SimRegistry.IdentityState.UNVERIFIED }
        if (confirmed.isEmpty()) return
        val bound = database.meshNodeSims(node.gatewayId)
        val upToDate = bound.size == confirmed.size && bound.all { it.state == "active" } &&
            bound.map { it.simId }.toSet() == confirmed.map { it.simId.lowercase() }.toSet()
        if (upToDate) return
        val subscriptions = snapshot.subscriptions.associateBy { it.subscriptionId }
        val proposal = JSONArray()
        confirmed.sortedBy { it.slotIndex }.forEach { mapping ->
            val subscription = subscriptions[mapping.subscriptionId]
            proposal.put(JSONObject()
                .put("slot_index", mapping.slotIndex)
                .put("label", subscription?.displayName?.takeIf { it.isNotBlank() }?.take(64) ?: "SIM ${mapping.slotIndex + 1}")
                .put("carrier_name", subscription?.carrierName?.takeIf { it.isNotBlank() }?.take(100) ?: JSONObject.NULL)
                .put("proposed_sim_id", mapping.simId.lowercase()))
        }
        val operationId = UUID.randomUUID().toString()
        val proposed = client.proposeSimBindings(operationId, proposal)
        val expected = confirmed.map { it.simId.lowercase() }.toSet()
        if (proposed.mappings.map { it.simId.lowercase() }.toSet() != expected) {
            throw ControlApiException("MESH_SIM_ID_MISMATCH", "节点未保留旧手机提议的 SIM ID")
        }
        val confirmations = JSONArray()
        proposed.mappings.forEach { mapping ->
            confirmations.put(JSONObject().put("sim_id", mapping.simId).put("confirmed", true).put("slot_index", mapping.slotIndex))
        }
        val result = client.confirmSimBindings(operationId, confirmations)
        if (result.any { it.state != "active" }) throw ControlApiException("MESH_SIM_NOT_ACTIVE", "节点未启用全部 SIM")
        database.replaceMeshNodeSims(node.gatewayId, proposed.mappingRevision, expected)
        log("MESH: ${hostOf(node.controlBaseUrl)} bound ${expected.size} SIM(s)")
    }

    private fun heartbeat(node: GatewayDatabase.MeshNode, client: ControlApiClient,
                          snapshot: SimRegistry.SimRegistrySnapshot, inputs: MeshHeartbeatInputs) {
        val accepts = { extension: String -> ControlExtensions.accepts(app, node.gatewayId, node.controlBaseUrl, extension) }
        val local = snapshot.mappings.associateBy { it.simId.lowercase() }
        val revision = database.meshNodes().firstOrNull { it.gatewayId == node.gatewayId }?.mappingRevision ?: return
        val sims = database.meshNodeSims(node.gatewayId).map { bound ->
            val mapping = local[bound.simId]
            val radio: RadioSnapshot? = if (mapping != null && accepts(RadioTelemetry.EXTENSION)) {
                runCatching {
                    RadioTelemetry.read(app, mapping.subscriptionId, accepts(RadioTelemetry.COUNTRY_EXTENSION))
                }.getOrNull()
            } else null
            HeartbeatSim(bound.simId, revision,
                mapping?.let { inputs.serviceStateFor(it.subscriptionId) } ?: "absent",
                identityVerified = bound.state == "active" && mapping != null &&
                    mapping.identityState != SimRegistry.IdentityState.UNVERIFIED,
                radio = radio)
        }
        val installId = if (accepts(MESH_EXTENSION)) database.installId() else null
        val prepared = database.prepareHeartbeatPayload(node.gatewayId) { sequence ->
            client.heartbeatBody(sequence, inputs.root,
                batteryPercent = inputs.batteryPercent,
                charging = inputs.charging, sims = sims, installId = installId).toString()
        }
        val result = try {
            client.heartbeat(JSONObject(prepared.second))
        } catch (e: ControlApiException) {
            if (e.code == "INVALID_JSON") {
                ControlExtensions.forget(app, node.gatewayId, node.controlBaseUrl)
                database.discardHeartbeat(node.gatewayId, prepared.first)
            } else if (e.code == "SIM_MAPPING_CHANGED") {
                // Re-bind from the primary's confirmed SIMs next round.
                database.replaceMeshNodeSims(node.gatewayId, revision, emptyList())
                database.discardHeartbeat(node.gatewayId, prepared.first)
            }
            throw e
        }
        database.applyMeshHeartbeat(node.gatewayId, result.mappingRevision, result.invalidatedSimIds)
        database.completeHeartbeat(node.gatewayId, prepared.first)
        ControlExtensions.record(app, node.gatewayId, node.controlBaseUrl, result.extensions)
    }

    companion object {
        const val MESH_EXTENSION = "mesh"

        fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)
    }
}

/** Add or remove secondary nodes. Network calls block; run them off the UI thread. */
object MeshPairing {
    data class Added(val gatewayId: String, val host: String)

    fun add(context: Context, controlBaseUrl: String, pairingCode: String, deviceName: String): Added {
        val app = context.applicationContext
        val primary = CredentialStore.load(app)
            ?: throw ControlApiException("PAIRING_REQUIRED", "请先完成主节点配对并确认 SIM")
        val probe = ControlApiClient(app, controlBaseUrl)
        if (MeshCoordinator.MESH_EXTENSION !in probe.capabilities()) {
            throw ControlApiException("MESH_UNSUPPORTED", "该服务器版本不支持多节点，请先升级服务器")
        }
        val session = probe.pairMeshNode(pairingCode, deviceName)
        if (session.gatewayId == primary.gatewayId || session.controlBaseUrl == primary.controlBaseUrl.trimEnd('/')) {
            throw ControlApiException("MESH_DUPLICATE_NODE", "这就是主节点，不需要再次添加")
        }
        MeshVaults.forNode(session.gatewayId).save(app, session)
        val host = MeshCoordinator.hostOf(session.controlBaseUrl)
        if (!GatewayDatabase.get(app).addMeshNode(session.gatewayId, session.controlBaseUrl, host)) {
            throw ControlApiException("MESH_DUPLICATE_NODE", "该节点已在列表中")
        }
        return Added(session.gatewayId, host)
    }

    fun remove(context: Context, gatewayId: String) {
        val app = context.applicationContext
        val database = GatewayDatabase.get(app)
        val node = database.meshNodes().firstOrNull { it.gatewayId == gatewayId }
        val vault = MeshVaults.forNode(gatewayId)
        if (node != null) runCatching { ControlApiClient(app, node.controlBaseUrl, vault).revoke() }
        database.removeMeshNode(gatewayId)
        vault.clear(app)
    }
}
