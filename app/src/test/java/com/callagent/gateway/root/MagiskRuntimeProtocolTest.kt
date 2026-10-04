package com.callagent.gateway.root

import com.callagent.gateway.BuildConfig
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MagiskRuntimeProtocolTest {
    private data class TestAccount(val subId: Int, val handleId: String, val userId: Int = 0)

    @Test
    fun parsesOnlyExactRequestedSubscriptionAndKeepsOpaqueHandleId() {
        val parsed = MagiskRuntimeProtocol.parse(sharedFixture(), 0, BuildConfig.VERSION_CODE, 14)

        assertNotNull(parsed)
        assertEquals(14, parsed!!.single().subscriptionId)
        assertEquals("telephony-account", parsed.single().id)
        assertNull(MagiskRuntimeProtocol.parse(response(listOf(account(7, "telephony-account"))), 0, BuildConfig.VERSION_CODE, 8))
    }

    @Test
    fun duplicateSubscriptionOrHandleCannotCreateAnArbitraryMapping() {
        assertNull(
            MagiskRuntimeProtocol.parse(
                response(listOf(account(7, "account-a"), account(7, "account-b"))),
                0,
                BuildConfig.VERSION_CODE
            )
        )
        assertNull(
            MagiskRuntimeProtocol.parse(
                response(listOf(account(7, "same-account"), account(8, "same-account"))),
                0,
                BuildConfig.VERSION_CODE
            )
        )
    }

    @Test
    fun differentUserAndUnavailableBrokerCannotImpersonateAnAccount() {
        assertNull(
            MagiskRuntimeProtocol.parse(
                response(listOf(account(7, "account-a", rowUserId = 10))),
                expectedUserId = 0,
                expectedAppVersion = BuildConfig.VERSION_CODE
            )
        )
        assertNull(
            MagiskRuntimeProtocol.parse(
                response(emptyList(), status = "unavailable", error = "permission"),
                expectedUserId = 0,
                expectedAppVersion = BuildConfig.VERSION_CODE
            )
        )
        assertNull(
            MagiskRuntimeProtocol.parse(
                response(emptyList(), status = "unavailable", error = "context"),
                expectedUserId = 0,
                expectedAppVersion = BuildConfig.VERSION_CODE
            )
        )
    }

    @Test
    fun successfulEmptySnapshotIsDistinctFromUnavailableBroker() {
        val parsed = MagiskRuntimeProtocol.parse(
            response(emptyList()),
            expectedUserId = 0,
            expectedAppVersion = BuildConfig.VERSION_CODE
        )

        assertNotNull(parsed)
        assertTrue(parsed!!.isEmpty())
    }

    @Test
    fun rejectsVersionMismatchDuplicateFieldsAndUnboundedOutput() {
        val valid = response(listOf(account(7, "account-a")))
        assertNull(MagiskRuntimeProtocol.parse(valid, 0, BuildConfig.VERSION_CODE + 1))
        assertNull(MagiskRuntimeProtocol.parse(valid + "status=ok\n", 0, BuildConfig.VERSION_CODE))
        assertNull(MagiskRuntimeProtocol.parse("x".repeat(32 * 1024 + 1), 0, BuildConfig.VERSION_CODE))
    }

    private fun response(
        rows: List<TestAccount>,
        status: String = "ok",
        error: String? = null
    ): String = buildList {
        add("protocol=1")
        add("broker_version=1")
        add("app_version=${BuildConfig.VERSION_CODE}")
        add("broker_uid=1000")
        add("source=magisk-system-telephony")
        add("status=$status")
        add("user_id=0")
        add("count=${rows.size}")
        rows.forEachIndexed { index, row ->
            val component = "com.android.phone/com.android.services.telephony.TelephonyConnectionService"
            add("account.$index.sub_id=${row.subId}")
            add("account.$index.component_b64=${encode(component)}")
            add("account.$index.id_b64=${encode(row.handleId)}")
            add("account.$index.user_id=${row.userId}")
        }
        error?.let { add("error=$it") }
    }.joinToString("\n", postfix = "\n")

    private fun account(subId: Int, handleId: String, rowUserId: Int = 0) =
        TestAccount(subId, handleId, rowUserId)

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun sharedFixture(): String {
        val relativePath = "tools/fixtures/root-telephony-accounts-v1.txt"
        val current = File(System.getProperty("user.dir")).absoluteFile
        val candidates = listOf(
            File(current, relativePath),
            File(current, "../$relativePath"),
            File(current, "../../$relativePath")
        )
        val fixture = candidates.firstOrNull(File::isFile)
            ?: throw AssertionError("Shared module protocol fixture was not found")
        return fixture.readText(Charsets.UTF_8)
    }
}
