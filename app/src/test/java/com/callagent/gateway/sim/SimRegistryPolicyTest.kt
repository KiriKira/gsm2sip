package com.callagent.gateway.sim

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimRegistryPolicyTest {
    @Test
    fun knownFingerprintCannotBeReboundToAnotherPhysicalSim() {
        assertTrue(SimRegistry.Policy.fingerprintMismatch("salted-iccid-a", "salted-iccid-b"))
        assertFalse(SimRegistry.Policy.fingerprintMismatch("salted-iccid-a", "salted-iccid-a"))
        // If Android does not expose either fingerprint, the explicit local
        // confirmation path decides; a missing value is not treated as proof
        // that a different card is present.
        assertFalse(SimRegistry.Policy.fingerprintMismatch("salted-iccid-a", null))
        assertTrue(SimRegistry.Policy.newFingerprintNeedsConfirmation(
            SimRegistry.IdentityState.LOCAL_CONFIRMED, null, "salted-iccid-b"
        ))
        assertFalse(SimRegistry.Policy.newFingerprintNeedsConfirmation(
            SimRegistry.IdentityState.FINGERPRINT_VERIFIED, "salted-iccid-a", "salted-iccid-a"
        ))
    }

    @Test
    fun unFingerprintedLocalConfirmationExpiresAcrossBootOrMissingBootCount() {
        assertFalse(SimRegistry.Policy.localConfirmationExpired(
            SimRegistry.IdentityState.LOCAL_CONFIRMED, null, 12, 12
        ))
        assertTrue(SimRegistry.Policy.localConfirmationExpired(
            SimRegistry.IdentityState.LOCAL_CONFIRMED, null, 12, 13
        ))
        assertTrue(SimRegistry.Policy.localConfirmationExpired(
            SimRegistry.IdentityState.LOCAL_CONFIRMED, null, 12, null
        ))
        // A stable local fingerprint remains valid across reboot.
        assertFalse(SimRegistry.Policy.localConfirmationExpired(
            SimRegistry.IdentityState.FINGERPRINT_VERIFIED, "salted-iccid-a", 12, 13
        ))
    }

    @Test
    fun bindingChangesRequireAtomicNewProposalAndProposalCannotRegress() {
        assertFalse(SimRegistry.Policy.canUseSingleConfirmation(8, null, bindingChanged = true))
        assertFalse(SimRegistry.Policy.canUseSingleConfirmation(8, 8, bindingChanged = true))
        assertFalse(SimRegistry.Policy.canUseSingleConfirmation(8, 9, bindingChanged = true))
        assertTrue(SimRegistry.Policy.canUseSingleConfirmation(8, 8, bindingChanged = false))
        assertTrue(SimRegistry.Policy.canUseSingleConfirmation(8, null, bindingChanged = false))
        assertFalse(SimRegistry.Policy.canUseSingleConfirmation(8, 7, bindingChanged = false))
        assertFalse(SimRegistry.Policy.isNewProposal(8, 8))
        assertFalse(SimRegistry.Policy.isNewProposal(8, 7))
        assertTrue(SimRegistry.Policy.isNewProposal(8, 9))
    }

    @Test
    fun dispatchRejectsStaleServerRevisionOrChangedLocalBarrier() {
        assertTrue(SimRegistry.Policy.dispatchVersionsMatch(8, 8, 20, 20))
        assertFalse(SimRegistry.Policy.dispatchVersionsMatch(7, 8, 20, 20))
        assertFalse(SimRegistry.Policy.dispatchVersionsMatch(8, 8, 19, 20))
        // Initial command validation may omit the local barrier, but the
        // dispatch-time recheck supplies it to catch identity changes.
        assertTrue(SimRegistry.Policy.dispatchVersionsMatch(8, 8, null, 20))
    }

    @Test
    fun heartbeatMayOnlyAdvanceRevisionWhenItNamesInvalidatedMappings() {
        assertTrue(SimRegistry.Policy.heartbeatRevisionIsConsistent(8, 8, invalidatedCount = 0))
        assertFalse(SimRegistry.Policy.heartbeatRevisionIsConsistent(8, 9, invalidatedCount = 0))
        assertFalse(SimRegistry.Policy.heartbeatRevisionIsConsistent(8, 8, invalidatedCount = 1))
        assertFalse(SimRegistry.Policy.heartbeatRevisionIsConsistent(8, 7, invalidatedCount = 1))
        assertTrue(SimRegistry.Policy.heartbeatRevisionIsConsistent(8, 9, invalidatedCount = 1))
        // A persisted retry may replay the same response after the local
        // invalidation was committed but before the pending request was cleared.
        assertTrue(SimRegistry.Policy.heartbeatRevisionIsConsistent(
            9, 9, invalidatedCount = 1, alreadyInvalidatedCount = 1
        ))
        assertFalse(SimRegistry.Policy.heartbeatRevisionIsConsistent(
            9, 9, invalidatedCount = 1, alreadyInvalidatedCount = 0
        ))
    }

    @Test
    fun resyncCannotRollBackAuthoritativeRevision() {
        assertTrue(SimRegistry.Policy.resyncRevisionIsAcceptable(8, 8))
        assertTrue(SimRegistry.Policy.resyncRevisionIsAcceptable(8, 9))
        assertFalse(SimRegistry.Policy.resyncRevisionIsAcceptable(8, 7))
    }
}
