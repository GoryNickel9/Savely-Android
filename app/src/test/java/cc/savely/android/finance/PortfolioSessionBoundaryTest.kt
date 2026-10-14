package cc.savely.android.finance

import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import java.io.Closeable
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortfolioSessionBoundaryTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val sid = "33333333-3333-4333-8333-333333333333"
    private val nextSid = "44444444-4444-4444-8444-444444444444"
    private val today = LocalDate.now()
    private fun session(user: String = owner, sessionId: String = sid, signature: String = "original"): Session {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            JSONObject().put("sub", user).put("session_id", sessionId).toString().toByteArray())
        return Session("synthetic.$payload.$signature", "synthetic-refresh", Long.MAX_VALUE, user, "close@example.test")
    }
    private fun asset(index: Int) = FinanceAsset("55555555-5555-4555-8555-${index.toString().padStart(12, '0')}",
        "Synthetic purchase", "QA", FinanceAssetType.STOCK, BigDecimal("2"), BigDecimal("10"), BigDecimal("12"),
        "EUR", today.minusDays(30), null, null, "")

    @Test fun `replacement during the final close preflight prevents every patch for same or different user`() = runBlocking {
        for (user in listOf(owner, other)) Fixture().use { fixture ->
            val replacement = session(user, nextSid)
            fixture.afterSecondRead = { fixture.store.save(replacement) }
            val failure = runCatching { fixture.repository.closeAssetGroup(fixture.assets, today.toString(), "13") }.exceptionOrNull()
            assertTrue(failure is PositionCloseException)
            failure as PositionCloseException
            assertFalse(failure.attemptedWrite)
            assertEquals(0, failure.confirmed)
            assertEquals(fixture.assets.last().id, failure.uncertainId)
            assertEquals(2, fixture.reads.get())
            assertEquals(0, fixture.patches.get())
            assertEquals(replacement, fixture.store.load())
            assertNull(fixture.api.sessionInvalidation.value)
            assertTrue(fixture.tokens.all { it == "Bearer ${session().accessToken}" })
        }
    }

    @Test fun `replacement after the first committed close cannot close the next purchase under either new session`() = runBlocking {
        for (user in listOf(owner, other)) Fixture().use { fixture ->
            val replacement = session(user, nextSid)
            fixture.afterFirstPatch = { fixture.store.save(replacement) }
            val failure = runCatching { fixture.repository.closeAssetGroup(fixture.assets, today.toString(), "13") }.exceptionOrNull()
            assertTrue(failure is PositionCloseException)
            failure as PositionCloseException
            assertTrue(failure.attemptedWrite)
            assertEquals("A postflight owner change leaves the first close uncertain", 0, failure.confirmed)
            assertEquals(fixture.assets.first().id, failure.uncertainId)
            assertEquals(2, fixture.reads.get())
            assertEquals(1, fixture.patches.get())
            assertEquals(listOf(fixture.assets.first().id), fixture.closedIds)
            assertEquals(replacement, fixture.store.load())
            assertNull(fixture.api.sessionInvalidation.value)
            assertTrue(fixture.tokens.all { it == "Bearer ${session().accessToken}" })
        }
    }

    @Test fun `token rotation of the same session during preflight keeps both closes usable without changing owner`() = runBlocking {
        Fixture().use { fixture ->
            val rotated = session(signature = "rotated")
            fixture.afterSecondRead = { fixture.store.save(rotated) }
            fixture.repository.closeAssetGroup(fixture.assets, today.toString(), "13")
            assertEquals(2, fixture.reads.get())
            assertEquals(2, fixture.patches.get())
            assertEquals(fixture.assets.map { it.id }, fixture.closedIds)
            assertEquals(rotated, fixture.store.load())
            assertEquals(listOf("Bearer ${session().accessToken}", "Bearer ${session().accessToken}",
                "Bearer ${rotated.accessToken}", "Bearer ${rotated.accessToken}"), fixture.tokens)
            assertNull(fixture.api.sessionInvalidation.value)
        }
    }

    private inner class Fixture : Closeable {
        val assets = listOf(asset(1), asset(2))
        val store = MemorySessionStore(session())
        val reads = AtomicInteger()
        val patches = AtomicInteger()
        val tokens = Collections.synchronizedList(mutableListOf<String?>())
        val closedIds = Collections.synchronizedList(mutableListOf<String>())
        var afterSecondRead: () -> Unit = {}
        var afterFirstPatch: () -> Unit = {}
        private fun row(asset: FinanceAsset, closed: Boolean) = JSONObject().put("id", asset.id).put("user_id", owner)
            .put("name", asset.name).put("symbol", asset.symbol).put("type", asset.type.wire)
            .put("quantity", "2").put("purchase_price", "10").put("current_price", "12").put("currency", "EUR")
            .put("purchase_date", asset.purchaseDate.toString()).put("sold_at", if (closed) today.toString() else JSONObject.NULL)
            .put("sold_price", if (closed) "13" else JSONObject.NULL)
        private val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = requireNotNull(request.requestUrl)
                    assertEquals("/rest/v1/portfolio_assets", url.encodedPath)
                    assertEquals("eq.$owner", url.queryParameter("user_id"))
                    tokens += request.getHeader("Authorization")
                    val selected = assets.single { "eq.${it.id}" == url.queryParameter("id") }
                    val closed = when (request.method) {
                        "GET" -> {
                            if (reads.incrementAndGet() == 2) afterSecondRead()
                            false
                        }
                        "PATCH" -> {
                            val body = JSONObject(request.body.readUtf8())
                            assertEquals(setOf("sold_at", "sold_price"), body.keys().asSequence().toSet())
                            assertEquals(today.toString(), body.getString("sold_at"))
                            assertEquals("13.00", body.getString("sold_price"))
                            closedIds += selected.id
                            if (patches.incrementAndGet() == 1) afterFirstPatch()
                            true
                        }
                        else -> error("Unexpected local close request")
                    }
                    return MockResponse().setBody(JSONArray().put(row(selected, closed)).toString())
                }
            }
            start()
        }
        private val url = server.url("/").toString()
        val api = SupabaseApi(BackendConfig(url, "fixture", url), store)
        val repository = FinanceRepository(api)
        override fun close() { api.close(); server.shutdown() }
    }
}
