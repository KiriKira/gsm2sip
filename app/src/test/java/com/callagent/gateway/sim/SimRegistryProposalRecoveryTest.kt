package com.callagent.gateway.sim

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.Settings
import android.telephony.SubscriptionManager
import com.callagent.gateway.data.CredentialStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [SimRegistryProposalRecoveryTest.Credentials::class])
class SimRegistryProposalRecoveryTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val oldId = "9d581e56-b6c5-451c-b5de-4b2aedd9fc77"
    private val newId = "2733e069-2333-4ee7-ba57-0dae83668828"
    private val prefs get() = context.getSharedPreferences("sim_registry_v1", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
        subscriptions("")
        seed(SimRegistry.IdentityState.UNVERIFIED)
    }

    @After
    fun tearDown() { prefs.edit().clear().commit() }

    @Test
    fun newerCompleteProposalReplacesUnverifiedOldUuidOnSameSubscription() {
        val result = SimRegistry.confirmMappings(context, listOf(binding(newId)), 17)
        assertEquals(listOf(newId), result.map { it.simId })
        assertEquals(SimRegistry.IdentityState.LOCAL_CONFIRMED, result.single().identityState)
        val restored = SimRegistry.snapshot(context)
        assertEquals(17L, restored.mappingRevision)
        assertEquals(listOf(newId), restored.mappings.map { it.simId })
    }

    @Test
    fun oldAndEqualRevisionCannotReplaceUnverifiedBinding() {
        listOf(8L, 16L).forEach { revision ->
            val error = assertThrows(SimRegistry.SimMappingException::class.java) {
                SimRegistry.confirmMappings(context, listOf(binding(newId)), revision)
            }
            assertEquals(SimRegistry.ErrorCode.SIM_MAPPING_CHANGED, error.code)
        }
        assertEquals(16L, prefs.getLong("mapping_revision", -1))
    }

    @Test
    fun sameUuidCannotBePinnedToDifferentPhysicalSim() {
        seed(SimRegistry.IdentityState.UNVERIFIED, "different-pinned-fingerprint")
        subscriptions("8901234567890123456")
        val error = assertThrows(SimRegistry.SimMappingException::class.java) {
            SimRegistry.confirmMappings(context, listOf(binding(oldId)), 17)
        }
        assertEquals(SimRegistry.ErrorCode.SIM_MAPPING_CHANGED, error.code)
        assertEquals(16L, prefs.getLong("mapping_revision", -1))
    }

    @Test
    fun verifiedOldBindingStillCannotBeTakenOverByAnotherUuid() {
        seed(SimRegistry.IdentityState.LOCAL_CONFIRMED)
        val error = assertThrows(SimRegistry.SimMappingException::class.java) {
            SimRegistry.confirmMappings(context, listOf(binding(newId)), 17)
        }
        assertEquals(SimRegistry.ErrorCode.SIM_ALREADY_MAPPED, error.code)
    }

    @Test
    fun completeProposalRejectsDuplicateSubscriptionsAndDuplicateUuids() {
        listOf(
            listOf(binding(oldId), binding(newId)),
            listOf(binding(newId), SimRegistry.SimBindingConfirmation(newId, 2))
        ).forEach { confirmations ->
            val error = assertThrows(SimRegistry.SimMappingException::class.java) {
                SimRegistry.confirmMappings(context, confirmations, 17)
            }
            assertEquals(SimRegistry.ErrorCode.SIM_MAPPING_CHANGED, error.code)
        }
    }

    private fun binding(simId: String) = SimRegistry.SimBindingConfirmation(simId, 1)

    private fun subscriptions(iccId: String) {
        val info = SubscriptionInfoBuilder.newBuilder().setId(1).setSimSlotIndex(0).setIccId(iccId)
            .setDisplayName("SIM 1").setCarrierName("Test").buildSubscriptionInfo()
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(listOf(info))
    }

    private fun seed(identity: SimRegistry.IdentityState, fingerprint: String = "") {
        val mapping = JSONObject().put("sim_id", oldId).put("sub_id", 1).put("slot_index", 0)
            .put("fingerprint", fingerprint).put("identity_state", identity.name)
            .put("confirmed_boot_count", 7).put("phone_account", "")
        prefs.edit().clear().putLong("mapping_revision", 16)
            .putString("server_owner_id", "gateway-recovery")
            .putString("server_owner_control_base_url", "https://control.example.test")
            .putString("mappings", JSONArray().put(mapping).toString()).commit()
    }

    @Implements(value = CredentialStore::class, isInAndroidSdk = false)
    class Credentials {
        @Implementation
        fun load(context: Context): CredentialStore.Session = CredentialStore.Session(
            "gateway-recovery", "https://control.example.test", "test-access", "2030-01-01T00:00:00Z",
            "test-refresh", "2031-01-01T00:00:00Z"
        )
    }
}
