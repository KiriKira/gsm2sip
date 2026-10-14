package com.callagent.gateway.net

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ControlApiClientUrlTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun acceptsHttpsAndPlainHttpControlOrigins() {
        ControlApiClient(context, "https://control.example/v1/")
        ControlApiClient(context, "http://192.168.1.10:8080")
        ControlApiClient(context, "http://gateway-host.tailnet.ts.net/edge")
    }

    @Test
    fun stillRejectsOtherSchemesCredentialsQueriesAndFragments() {
        listOf(
            "ftp://control.example",
            "http://user:pass@control.example",
            "https://user@control.example",
            "http://control.example/?token=x",
            "https://control.example#frag",
            "not a url"
        ).forEach { url ->
            val error = runCatching { ControlApiClient(context, url) }.exceptionOrNull()
            assertEquals(url, "INVALID_CONTROL_URL", (error as? ControlApiException)?.code)
        }
    }
}
