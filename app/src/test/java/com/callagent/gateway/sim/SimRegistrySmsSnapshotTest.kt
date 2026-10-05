package com.callagent.gateway.sim

import android.Manifest
import android.content.Context
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimRegistrySmsSnapshotTest {
    private val context = RuntimeEnvironment.getApplication()

    @After
    fun cleanUp() {
        SimRegistry.voiceAccountResolverOverrideForTest = null
        context.getSharedPreferences("sim_registry_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun defaultSnapshotDoesNotResolveVoiceAccounts() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        var resolverCalls = 0
        SimRegistry.voiceAccountResolverOverrideForTest =
            { _, _: List<SubscriptionInfo> ->
                resolverCalls++
                throw AssertionError("SMS snapshot requested voice account resolution")
            }

        SimRegistry.snapshot(context)

        assertEquals("default snapshot must stay on the SMS-only path", 0, resolverCalls)
    }

    @Test
    fun voiceSnapshotOnlyResolvesAccountsWhenExplicitlyRequested() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        var resolverCalls = 0
        SimRegistry.voiceAccountResolverOverrideForTest =
            { _, _: List<SubscriptionInfo> ->
                resolverCalls++
                emptyMap<Int, PhoneAccountHandle>()
            }

        SimRegistry.snapshot(context, includeVoice = true)

        assertEquals("explicit voice snapshot should resolve account associations", 1, resolverCalls)
    }
}
