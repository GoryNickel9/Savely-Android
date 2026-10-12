package cc.savely.android.finance

import androidx.lifecycle.viewModelScope
import cc.savely.android.data.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceInsertUncertaintyTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val category = "22222222-2222-4222-8222-222222222222"
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val apis = mutableListOf<SupabaseApi>()
    private val models = mutableListOf<FinanceViewModel>()
    private val posts = AtomicInteger()
    private val bodies = mutableListOf<JSONObject>()

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(3, TimeUnit.SECONDS).build()
    }
    @After fun teardown() {
        models.forEach { it.viewModelScope.cancel() }
        apis.forEach { it.close() }
        Dispatchers.resetMain()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
    private fun api(selectedClient: OkHttpClient = client): SupabaseApi {
        val url = server.url("/").toString()
        return SupabaseApi(BackendConfig(url, "fixture", url),
            MemorySessionStore(Session("access", "refresh", Long.MAX_VALUE, owner, "qa@example.test")), selectedClient)
            .also { apis += it }
    }
    private fun model(api: SupabaseApi = api()) = FinanceViewModel(FinanceRepository(api), "transactions").also { models += it }
    private fun fixture(response: () -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET" && request.requestUrl!!.encodedPath == "/rest/v1/categories")
                    return MockResponse().setBody("""[{"type":"expense"}]""")
                assertEquals("POST", request.method)
                assertEquals("Bearer access", request.getHeader("Authorization"))
                val body = JSONObject(request.body.readUtf8())
                assertEquals(owner, body.getString("user_id"))
                java.util.UUID.fromString(body.getString("id"))
                synchronized(bodies) { bodies += body }
                posts.incrementAndGet()
                return response()
            }
        }
    }
    private suspend fun FinanceRepository.create(table: String) = when (table) {
        "transactions" -> saveTransaction(null, TransactionType.EXPENSE, "1.01", "EUR", "1", null, "Synthetic", "2026-10-12")
        "categories" -> saveCategory(null, "Synthetic", TransactionType.EXPENSE, "📦", "#6b7280")
        "budgets" -> saveBudget(null, category, "20")
        "recurring_expenses" -> saveRecurring(null, "Synthetic", "20", "EUR", null, RecurringFrequency.MONTHLY, "1", "2027-01-01", false)
        "portfolio_assets" -> saveAsset(null, "Synthetic", "TEST", FinanceAssetType.STOCK, "1", "20", "", "EUR", "2026-10-12", "")
        else -> error("Unexpected fixture table")
    }
    private suspend fun assertBlocked(model: FinanceViewModel) {
        withTimeout(10_000) { model.state.first { !it.saving && it.error != null } }
        assertTrue(model.state.value.reconciliationRequired)
        var callbacks = 0
        model.mutate({ callbacks++; create("transactions") }) { callbacks++ }
        assertEquals("Neither the operation nor success callback may run before reconciliation", 0, callbacks)
    }

    @Test fun `malformed successful responses block every private create path without replay`() = runBlocking {
        fixture { MockResponse().setResponseCode(201).setBody("[") }
        val tables = listOf("transactions", "categories", "budgets", "recurring_expenses", "portfolio_assets")
        tables.forEachIndexed { index, table ->
            val model = model()
            var succeeded = false
            model.mutate({ create(table) }) { succeeded = true }
            assertBlocked(model)
            assertFalse(succeeded)
            assertEquals(index + 1, posts.get())
        }
        assertEquals(5, synchronized(bodies) { bodies.map { it.getString("id") }.distinct().size })
    }

    @Test fun `lost insert response blocks another save even when the server consumed the payload`() = runBlocking {
        fixture { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val model = model()
        model.mutate({ create("transactions") }) { fail("Lost response must not confirm success") }
        assertBlocked(model)
        assertEquals(1, posts.get())
    }

    @Test fun `server failure after insert attempt requires reconciliation and never retries`() = runBlocking {
        fixture { MockResponse().setResponseCode(503).setBody("{}") }
        val model = model()
        model.mutate({ create("transactions") }) { fail("503 must not confirm success") }
        assertBlocked(model)
        assertEquals(1, posts.get())
    }

    @Test fun `explicit permission rejection leaves the form correctable without an uncertainty lock`() = runBlocking {
        fixture { MockResponse().setResponseCode(403).setBody("{}") }
        val model = model()
        repeat(2) { attempt ->
            model.mutate({ create("transactions") }) { fail("403 must not confirm success") }
            withTimeout(10_000) { model.state.first { !it.saving && it.error != null } }
            assertFalse(model.state.value.reconciliationRequired)
            assertEquals(attempt + 1, posts.get())
        }
    }

    @Test fun `cancellation after committed insert clears saving and preserves the reconciliation block`() = runBlocking {
        fixture { MockResponse().setResponseCode(201).setBody(JSONArray().put(synchronized(bodies) { bodies.last() }).toString()) }
        val cancelledClient = client.newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            response.close()
            throw CancellationException("Synthetic cancellation after server response")
        }.build()
        val model = model(api(cancelledClient))
        model.mutate({ create("transactions") }) { fail("Cancellation must not confirm success") }
        assertBlocked(model)
        assertEquals(1, posts.get())
    }
}
