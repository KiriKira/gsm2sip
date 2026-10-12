package com.callagent.gateway.data

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

/** Cross-node guarantees of the shared gateway ledger. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GatewayMeshDatabaseTest {
    private lateinit var context: Context
    private lateinit var db: GatewayDatabase
    private val runId = UUID.randomUUID().toString()
    private val primary = "primary-$runId"
    private val node = "node-$runId"
    private val simId = UUID.randomUUID().toString()

    @Before
    fun open() {
        context = RuntimeEnvironment.getApplication()
        GatewayDatabase.get(context).close()
        context.deleteDatabase("gateway-data.db")
        db = GatewayDatabase.get(context)
        db.recoverUnknownDispatches()
        db.adoptGatewayId(primary)
        assertTrue(db.addMeshNode(node, "https://vps.example", "vps.example"))
    }

    @After
    fun close() {
        db.close()
        context.deleteDatabase("gateway-data.db")
    }

    @Test
    fun inboundSmsIsCopiedToEveryNodeWithTheSameMessageId() {
        val messageId = UUID.randomUUID().toString()
        db.recordIncomingSms(messageId, "+8610086", "+861380000", "验证码 1234", 1_000L, 1,
            subId = 3, slotIndex = 0, simId = simId, mappingRevision = 5, resolution = "resolved")

        val primaryEvents = db.pendingEvents(primary)
        assertEquals(1, primaryEvents.size)
        assertEquals(5L, primaryEvents.single().mappingRevision)
        assertEquals("node copy waits until the node has bound this SIM", 0, db.pendingMeshEvents(node).size)

        db.replaceMeshNodeSims(node, 2, listOf(simId))
        val copy = db.pendingMeshEvents(node).single()
        assertEquals("sms.received", copy.type)
        assertEquals(node, copy.ownerGatewayId)
        assertEquals(simId, copy.simId)
        assertEquals("the node's own revision is filled in before upload", 2L, copy.mappingRevision)
        assertEquals(messageId, JSONObject(copy.payload).getString("message_id"))
        assertTrue(copy.eventId != primaryEvents.single().eventId)
        assertTrue(copy.sequence > primaryEvents.single().sequence)

        // A later node revision change does not rewrite an already frozen copy.
        db.applyMeshHeartbeat(node, 3, emptyList())
        assertEquals(2L, db.pendingMeshEvents(node).single().mappingRevision)

        // Each node acknowledges independently; the primary keeps its own body.
        db.acknowledgeEvents(listOf(copy.eventId))
        assertTrue(db.pendingMeshEvents(node).isEmpty())
        assertEquals("验证码 1234", JSONObject(db.pendingEvents(primary).single().payload).getString("text"))

        // A duplicate broadcast of the same SMS creates no further copies.
        db.recordIncomingSms(messageId, "+8610086", "+861380000", "验证码 1234", 1_000L, 1,
            subId = 3, slotIndex = 0, simId = simId, mappingRevision = 5, resolution = "resolved")
        assertTrue(db.pendingMeshEvents(node).isEmpty())
    }

    @Test
    fun unknownSimCopiesUploadImmediatelyWithoutRevision() {
        db.recordIncomingSms(UUID.randomUUID().toString(), "+1", "", "hello", 2_000L, 1,
            subId = -1, slotIndex = -1, simId = null, mappingRevision = null, resolution = "unknown")
        val copy = db.pendingMeshEvents(node).single()
        assertNull(copy.simId)
        assertNull(copy.mappingRevision)
    }

    @Test
    fun theSameMessageFromAnotherNodeIsAliasedAndNeverDispatchedTwice() {
        val messageId = UUID.randomUUID().toString()
        assertEquals(GatewayDatabase.InsertCommandResult.INSERTED, db.insertClaimedCommand(command("cmd-p", messageId, primary, 7), primary))
        assertEquals(GatewayDatabase.InsertCommandResult.ALIASED, db.insertClaimedCommand(command("cmd-n", messageId, node, 2), node))
        assertTrue(db.alias("cmd-n-$runId"))
        assertEquals("a repeated poll of the aliased command stays an alias",
            GatewayDatabase.InsertCommandResult.ALIASED, db.insertClaimedCommand(command("cmd-n", messageId, node, 2), node))
        assertEquals("aliases are never inserted as executable commands", null, db.command("cmd-n-$runId"))
        assertEquals("different content under the same message_id is refused",
            GatewayDatabase.InsertCommandResult.CONFLICT,
            db.insertClaimedCommand(command("cmd-x", messageId, node, 2, text = "other"), node))

        // Nothing to mirror before the original is dispatched.
        assertEquals(0, db.emitAliasEvents(node))
        assertTrue(db.prepareCommandParts("cmd-p-$runId", 2))
        assertEquals(GatewayDatabase.DispatchStartResult.STARTED, db.beginDispatch("cmd-p-$runId"))
        assertTrue(db.recordPartState("cmd-p-$runId", 0, "submitted"))
        assertEquals("dispatching plus one part state", 2, db.emitAliasEvents(node))
        assertEquals("no duplicate mirror events", 0, db.emitAliasEvents(node))
        assertTrue(db.recordPartState("cmd-p-$runId", 1, "delivered"))
        assertEquals(1, db.emitAliasEvents(node))

        val mirrored = db.pendingMeshEvents(node)
        assertEquals(listOf("sms.dispatching", "sms.part_state", "sms.part_state"), mirrored.map { it.type })
        mirrored.forEach { event ->
            val payload = JSONObject(event.payload)
            assertEquals("cmd-n-$runId", payload.getString("command_id"))
            assertEquals(messageId, payload.getString("message_id"))
            assertEquals(2L, event.mappingRevision)
        }
        assertFalse("the node never sees the primary's command ID",
            mirrored.any { JSONObject(it.payload).getString("command_id") == "cmd-p-$runId" })
    }

    @Test
    fun failedBeforeDispatchIsMirroredAsCommandState() {
        val messageId = UUID.randomUUID().toString()
        db.insertClaimedCommand(command("cmd-p", messageId, primary, 7), primary)
        db.insertClaimedCommand(command("cmd-n", messageId, node, 2), node)
        assertTrue(db.markCommandTerminal("cmd-p-$runId", "failed", "SIM_UNAVAILABLE"))
        assertEquals(1, db.emitAliasEvents(node))
        val event = db.pendingMeshEvents(node).single()
        assertEquals("sms.command_state", event.type)
        assertEquals("failed", JSONObject(event.payload).getString("state"))
        assertEquals(0, db.emitAliasEvents(node))
    }

    @Test
    fun sendBudgetCountsEveryNodeForTheSameSim() {
        repeat(5) { index ->
            val owner = if (index % 2 == 0) primary else node
            val id = "budget-$index"
            db.insertClaimedCommand(command(id, UUID.randomUUID().toString(), owner, if (owner == primary) 7 else 2), owner)
            assertTrue(db.prepareCommandParts("$id-$runId", 1))
            assertEquals(GatewayDatabase.DispatchStartResult.STARTED, db.beginDispatch("$id-$runId"))
        }
        db.insertClaimedCommand(command("budget-6", UUID.randomUUID().toString(), node, 2), node)
        assertTrue(db.prepareCommandParts("budget-6-$runId", 1))
        assertEquals(GatewayDatabase.DispatchStartResult.RATE_LIMITED, db.beginDispatch("budget-6-$runId"))
    }

    @Test
    fun removedNodesStopReceivingCopiesAndInstallIdIsStable() {
        val install = db.installId()
        assertEquals(install, db.installId())
        db.replaceMeshNodeSims(node, 2, listOf(simId))
        db.removeMeshNode(node)
        assertFalse(db.isServingGateway(node))
        db.recordIncomingSms(UUID.randomUUID().toString(), "+1", "", "after removal", 3_000L, 1,
            subId = 3, slotIndex = 0, simId = simId, mappingRevision = 5, resolution = "resolved")
        assertTrue(db.pendingMeshEvents(node).isEmpty())
        assertEquals(GatewayDatabase.InsertCommandResult.CONFLICT,
            db.insertClaimedCommand(command("late", UUID.randomUUID().toString(), node, 2), node))
    }

    private fun command(suffix: String, messageId: String, owner: String, revision: Long, text: String = "hello") =
        GatewayDatabase.Command(
            commandId = "$suffix-$runId", messageId = messageId, simId = simId, mappingRevision = revision,
            to = "+15550001234", text = text, partCount = 0,
            expiresAt = System.currentTimeMillis() + 3_600_000L, state = "queued",
            payloadHash = "hash-$suffix", ownerGatewayId = owner
        )
}
