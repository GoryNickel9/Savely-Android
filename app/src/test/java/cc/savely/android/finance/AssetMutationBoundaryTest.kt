package cc.savely.android.finance

import androidx.lifecycle.viewModelScope
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssetMutationBoundaryTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val assetId = "22222222-2222-4222-8222-222222222222"

    @Before fun mainDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun `committed asset update with 503 stays blocked through failed refresh and reconciles without replay`() = runBlocking {
        assertReconciled("PATCH") { MockResponse().setResponseCode(503).setBody("{}") }
    }

    @Test fun `committed asset deletion with 503 stays blocked through failed refresh and reconciles without replay`() = runBlocking {
        assertReconciled("DELETE") { MockResponse().setResponseCode(503).setBody("{}") }
    }

    @Test fun `lost and malformed asset update and deletion responses require reconciliation without retry`() = runBlocking {
        for (method in listOf("PATCH", "DELETE")) {
            assertReconciled(method) { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
            assertReconciled(method) { MockResponse().setResponseCode(200).setBody("[") }
        }
    }

    @Test fun `explicit asset permission rejections stay correctable and do not mutate the saved row`() = runBlocking {
        for (method in listOf("PATCH", "DELETE")) {
            Fixture(method, commit = false) { MockResponse().setResponseCode(403).setBody("{}") }.use { fixture ->
                fixture.load()
                repeat(2) { attempt ->
                    fixture.model.mutate(fixture.operation) { fail("A rejection cannot confirm success") }
                    fixture.awaitFailure()
                    assertFalse(fixture.model.state.value.reconciliationRequired)
                    assertEquals(attempt + 1, fixture.writes.get())
                    assertEquals("Original asset", fixture.saved.get()!!.getString("name"))
                }
            }
        }
    }

    private suspend fun assertReconciled(method: String, response: () -> MockResponse) {
        Fixture(method, commit = true, response = response).use { fixture ->
            fixture.load()
            var successes = 0
            fixture.model.mutate(fixture.operation) { successes++ }
            fixture.awaitFailure()
            assertTrue(fixture.model.state.value.reconciliationRequired)
            assertEquals(0, successes)
            assertEquals(1, fixture.writes.get())
            assertEquals("Cached data stays available until the confirmed read", "Original asset",
                fixture.model.state.value.data!!.assets.single().name)
            if (method == "DELETE") assertNull(fixture.saved.get())
            else assertEquals("Updated asset", fixture.saved.get()!!.getString("name"))
            var blockedCalls = 0
            fixture.model.mutate({ blockedCalls++; fixture.operation(this) }) { fail("Blocked operation cannot succeed") }
            assertEquals(0, blockedCalls)

            fixture.failRead.set(true)
            fixture.model.refresh()
            fixture.awaitFailure()
            assertTrue(fixture.model.state.value.reconciliationRequired)
            fixture.model.mutate({ blockedCalls++; fixture.operation(this) }) { fail("Failed refresh cannot unlock writes") }
            assertEquals(0, blockedCalls)
            assertEquals(1, fixture.writes.get())

            fixture.failRead.set(false)
            fixture.model.refresh()
            withTimeout(10_000) {
                fixture.model.state.first { !it.loading && it.error == null && !it.reconciliationRequired }
            }
            val assets = fixture.model.state.value.data!!.assets
            if (method == "DELETE") assertTrue(assets.isEmpty()) else {
                assertEquals("Updated asset", assets.single().name)
                assertEquals(0, "2.50000000".toBigDecimal().compareTo(assets.single().quantity))
            }
            assertEquals("Neither refresh may repeat the mutation", 1, fixture.writes.get())
            assertEquals(0, successes)
            fixture.model.mutate({ blockedCalls++; error("Explicit action stops before HTTP") }) {}
            fixture.awaitFailure()
            assertEquals("Only a new explicit action may run after reconciliation", 1, blockedCalls)
            assertEquals(1, fixture.writes.get())
        }
    }

    private inner class Fixture(val method: String, val commit: Boolean, response: () -> MockResponse) : Closeable {
        val writes = AtomicInteger()
        val failRead = AtomicBoolean(false)
        val saved = AtomicReference<JSONObject?>(JSONObject().put("id", assetId).put("user_id", owner)
            .put("name", "Original asset").put("symbol", "QA").put("type", "stock").put("quantity", "1")
            .put("purchase_price", "10").put("current_price", "12").put("currency", "EUR")
            .put("purchase_date", "2026-06-01").put("sold_at", JSONObject.NULL).put("sold_price", JSONObject.NULL))
        private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(3, TimeUnit.SECONDS).build()
        private val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("Bearer asset-access", request.getHeader("Authorization"))
                    val url = requireNotNull(request.requestUrl)
                    assertEquals("eq.$owner", url.queryParameter("user_id"))
                    if (request.method == "GET") {
                        if (failRead.get()) return MockResponse().setResponseCode(503).setBody("{}")
                        val body = JSONArray()
                        if (url.encodedPath == "/rest/v1/portfolio_assets" && url.queryParameter("offset") == "0")
                            saved.get()?.let { body.put(JSONObject(it.toString())) }
                        return MockResponse().setBody(body.toString())
                    }
                    assertEquals(method, request.method)
                    assertEquals("/rest/v1/portfolio_assets", url.encodedPath)
                    assertEquals("eq.$assetId", url.queryParameter("id"))
                    writes.incrementAndGet()
                    if (commit) {
                        if (method == "DELETE") saved.set(null) else {
                            val changed = JSONObject(requireNotNull(saved.get()).toString())
                            val body = JSONObject(request.body.readUtf8())
                            body.keys().forEach { changed.put(it, body.get(it)) }
                            saved.set(changed)
                        }
                    }
                    return response()
                }
            }
            start()
        }
        private val url = server.url("/").toString()
        private val api = SupabaseApi(BackendConfig(url, "fixture", url), MemorySessionStore(
            Session("asset-access", "asset-refresh", Long.MAX_VALUE, owner, "asset@example.test")), client)
        val model = FinanceViewModel(FinanceRepository(api), "portfolio")
        val operation: suspend FinanceRepository.() -> Unit = {
            if (method == "DELETE") delete("portfolio_assets", assetId)
            else saveAsset(assetId, "Updated asset", "QA", FinanceAssetType.STOCK, "2.50000000", "10", "12", "EUR", "2026-06-01", "")
        }
        suspend fun load() {
            model.refresh()
            withTimeout(10_000) { model.state.first { it.data != null && !it.loading } }
            assertEquals("Original asset", model.state.value.data!!.assets.single().name)
        }
        suspend fun awaitFailure() {
            withTimeout(10_000) {
                model.state.first { !it.saving && !it.loading && it.error != null }
                model.viewModelScope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            }
        }
        override fun close() {
            runBlocking { withTimeout(10_000) { model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() } }
            api.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
