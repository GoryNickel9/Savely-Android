package cc.savely.android.finance

import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import java.io.Closeable
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortfolioPriceContractTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val originalSid = "33333333-3333-4333-8333-333333333333"
    private val replacementSid = "44444444-4444-4444-8444-444444444444"
    private fun session(user: String = owner, sid: String = originalSid): Session {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            JSONObject().put("sub", user).put("session_id", sid).toString().toByteArray())
        return Session("synthetic.$payload.signature", "synthetic-refresh", Long.MAX_VALUE, user, "price@example.test")
    }

    @Test fun `known no assets response may omit checked and records exact zero counts`() = runBlocking {
        Fixture("""{"message":"No assets to update","updated":0,"errors":[]}""").use { fixture ->
            fixture.repository.updatePrices()
            assertEquals(1, fixture.services.get())
            assertEquals(1, fixture.logs.get())
            assertEquals(0, fixture.logBody!!.getInt("assets_updated"))
            assertEquals(0, fixture.logBody!!.getInt("assets_checked"))
        }
    }

    @Test fun `normal counts stay exact and cash FX updates are allowed to exceed checked instruments`() = runBlocking {
        Fixture("""{"updated":3,"checked":1,"errors":[]}""").use { fixture ->
            fixture.repository.updatePrices()
            assertEquals(3, fixture.logBody!!.getInt("assets_updated"))
            assertEquals(1, fixture.logBody!!.getInt("assets_checked"))
            assertEquals(3, fixture.server.requestCount)
        }
    }

    @Test fun `malformed successful price responses require reconciliation without creating a cooldown log`() = runBlocking {
        val invalid = listOf(
            "{}", "[]", """{"error":"synthetic-private-error"}""",
            """{"updated":0,"errors":[]}""",
            """{"updated":0,"errors":[],"message":"other"}""",
            """{"updated":0,"errors":[],"message":null}""",
            """{"updated":0,"errors":[],"message":false}""",
            """{"updated":0}""", """{"checked":0,"errors":[]}""",
            """{"updated":"0","checked":0,"errors":[]}""",
            """{"updated":false,"checked":0,"errors":[]}""",
            """{"updated":-1,"checked":0,"errors":[]}""",
            """{"updated":1.25,"checked":1,"errors":[]}""",
            """{"updated":2147483648,"checked":1,"errors":[]}""",
            """{"updated":0,"checked":"0","errors":[]}""",
            """{"updated":0,"checked":true,"errors":[]}""",
            """{"updated":0,"checked":-1,"errors":[]}""",
            """{"updated":0,"checked":0.5,"errors":[]}""",
            """{"updated":0,"checked":null,"errors":[]}""",
            """{"updated":0,"checked":0,"errors":null}""",
            """{"updated":0,"checked":0,"errors":{}}""",
            """{"updated":0,"checked":0,"errors":"[]"}""",
            """{"updated":1,"errors":[]}""",
            """{"updated":0,"errors":[{"symbol":"QA","error":"missing"}]}""",
            """{"updated":0,"checked":0,"errors":[],"error":"synthetic-private-error"}""",
        )
        invalid.forEachIndexed { index, body ->
            Fixture(body).use { fixture ->
                val failure = runCatching { fixture.repository.updatePrices() }.exceptionOrNull()
                assertTrue("Invalid synthetic response case $index must require reconciliation", failure is FinancePendingRefreshException)
                assertFalse(failure!!.message.orEmpty().contains("synthetic-private-error"))
                assertEquals("No cooldown for invalid response case $index", 0, fixture.logs.get())
                assertEquals(1, fixture.services.get())
                assertEquals(2, fixture.server.requestCount)
            }
        }
    }

    @Test fun `failed or lost service responses never retry the service or create a cooldown log`() = runBlocking {
        for (lost in listOf(false, true)) {
            Fixture("{}").use { fixture ->
                fixture.serviceResponse = if (lost) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    else MockResponse().setResponseCode(503).setBody("{}")
                assertTrue(runCatching { fixture.repository.updatePrices() }.exceptionOrNull() is FinancePendingRefreshException)
                assertEquals(1, fixture.services.get())
                assertEquals(0, fixture.logs.get())
                assertEquals(2, fixture.server.requestCount)
            }
        }
    }

    @Test fun `failed cooldown confirmation after successful service remains uncertain without repeating either write`() = runBlocking {
        Fixture().use { fixture ->
            fixture.logResponse = MockResponse().setResponseCode(503).setBody("{}")
            assertTrue(runCatching { fixture.repository.updatePrices() }.exceptionOrNull() is FinancePendingRefreshException)
            assertEquals(1, fixture.services.get())
            assertEquals(1, fixture.logs.get())
            assertEquals(3, fixture.server.requestCount)
        }
    }

    @Test fun `unexpired cooldown refuses the service before any mutation`() = runBlocking {
        Fixture().use { fixture ->
            fixture.cooldown = Instant.now().plusSeconds(3600).toString()
            assertTrue(runCatching { fixture.repository.updatePrices() }.isFailure)
            assertEquals(0, fixture.services.get() + fixture.logs.get())
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test fun `replacement session during service response preserves its owner and cannot receive a cooldown write`() = runBlocking {
        for (user in listOf(owner, other)) Fixture().use { fixture ->
            val replacement = session(user, replacementSid)
            fixture.afterService = { fixture.store.save(replacement) }
            assertTrue(runCatching { fixture.repository.updatePrices() }.exceptionOrNull() is FinancePendingRefreshException)
            assertEquals(1, fixture.services.get())
            assertEquals(0, fixture.logs.get())
            assertEquals(replacement, fixture.store.load())
            assertNull(fixture.api.sessionInvalidation.value)
        }
    }

    @Test fun `replacement after committed cooldown response does not replay or clear the new session`() = runBlocking {
        for (user in listOf(owner, other)) Fixture().use { fixture ->
            val replacement = session(user, replacementSid)
            fixture.afterLog = { fixture.store.save(replacement) }
            assertTrue(runCatching { fixture.repository.updatePrices() }.exceptionOrNull() is FinancePendingRefreshException)
            assertEquals(1, fixture.services.get())
            assertEquals(1, fixture.logs.get())
            assertEquals(replacement, fixture.store.load())
            assertNull(fixture.api.sessionInvalidation.value)
            assertEquals(3, fixture.server.requestCount)
        }
    }

    private inner class Fixture(body: String = """{"updated":2,"checked":3,"errors":[]}""") : Closeable {
        val store = MemorySessionStore(session())
        val services = AtomicInteger()
        val logs = AtomicInteger()
        var logBody: JSONObject? = null
        var cooldown: String? = null
        var afterService: () -> Unit = {}
        var afterLog: () -> Unit = {}
        var serviceResponse = MockResponse().setBody(body)
        var logResponse = MockResponse().setBody("""[{"id":"55555555-5555-4555-8555-555555555555"}]""")
        private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(3, TimeUnit.SECONDS).build()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("Bearer ${session().accessToken}", request.getHeader("Authorization"))
                    val url = requireNotNull(request.requestUrl)
                    return when (url.encodedPath) {
                        "/rest/v1/manual_price_updates" -> if (request.method == "GET") {
                            assertEquals("eq.$owner", url.queryParameter("user_id"))
                            MockResponse().setBody(cooldown?.let { """[{"updated_at":"$it"}]""" } ?: "[]")
                        } else {
                            assertEquals("POST", request.method)
                            logBody = JSONObject(request.body.readUtf8())
                            assertEquals(owner, logBody!!.getString("user_id"))
                            logs.incrementAndGet()
                            afterLog()
                            logResponse
                        }
                        "/functions/v1/update-prices" -> {
                            assertEquals("POST", request.method)
                            assertEquals(owner, JSONObject(request.body.readUtf8()).getString("user_id"))
                            services.incrementAndGet()
                            afterService()
                            serviceResponse
                        }
                        else -> error("Unexpected local fixture request")
                    }
                }
            }
            start()
        }
        private val url = server.url("/").toString()
        val api = SupabaseApi(BackendConfig(url, "fixture", url), store, client)
        val repository = FinanceRepository(api)
        override fun close() {
            api.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
