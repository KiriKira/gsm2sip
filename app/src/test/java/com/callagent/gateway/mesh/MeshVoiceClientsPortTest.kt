package com.callagent.gateway.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class MeshVoiceClientsPortTest {
    @Test
    fun nodePortsSkipThoseHeldByRunningClients() {
        assertEquals(5062, MeshVoiceClients.nodeLocalPort(emptySet()))
        assertEquals(5064, MeshVoiceClients.nodeLocalPort(setOf(5062)))
        // A removed node frees its slot; the survivors keep theirs.
        assertEquals(5062, MeshVoiceClients.nodeLocalPort(setOf(5064, 5066)))
        assertEquals(5066, MeshVoiceClients.nodeLocalPort(setOf(5062, 5064, 5068)))
    }
}
