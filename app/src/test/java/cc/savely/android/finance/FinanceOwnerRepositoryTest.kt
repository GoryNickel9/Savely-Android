package cc.savely.android.finance

import cc.savely.android.data.*
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Switch account while a prerequisite is in flight; the new account must receive no mutation. */
class FinanceOwnerRepositoryTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val category = "33333333-3333-4333-8333-333333333333"
    private val connection = "44444444-4444-4444-8444-444444444444"
    private val share = "55555555-5555-4555-8555-555555555555"
    private val transaction = "66666666-6666-4666-8666-666666666666"
    private lateinit var store: MemorySessionStore
    private lateinit var api: SupabaseApi
    private lateinit var server: MockWebServer
    private lateinit var repository: FinanceRepository
    private lateinit var publicClient: OkHttpClient
    private val writes = AtomicInteger()
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun switchAccount() = store.save(Session("other-access", "other-refresh", Long.MAX_VALUE, other, "other@example.test"))
    @Before fun setup() {
        store = MemorySessionStore(Session("owner-access", "owner-refresh", Long.MAX_VALUE, owner, "owner@example.test"))
        server = MockWebServer().apply { start() }
        publicClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val url = server.url("/").toString()
        api = SupabaseApi(BackendConfig(url, "fixture", url), store)
        repository = FinanceRepository(api, exchangeRates = FinanceExchangeRates(publicClient, server.url("/latest")))
    }
    @After fun teardown() { api.close(); server.shutdown(); publicClient.dispatcher.executorService.shutdown(); publicClient.connectionPool.evictAll() }
    private fun switchOn(path: String, body: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method != "GET") { writes.incrementAndGet(); return json("{}").setResponseCode(403) }
                if (request.requestUrl!!.encodedPath == path) { switchAccount(); return json(body) }
                return json("{}").setResponseCode(404)
            }
        }
    }
    @Test fun `quote awaited by A cannot create a personal transaction under B`() = runBlocking {
        switchOn("/latest", """{"base":"USD","rates":{"EUR":0.9155555}}""")
        assertTrue(runCatching { repository.saveTransaction(null, TransactionType.EXPENSE, "1.01", "USD", "", null,
            "Synthetic", "2026-01-01") }.isFailure)
        assertEquals(0, writes.get()); assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("apikey"))
    }
    @Test fun `category validation awaited by A cannot create a budget under B`() = runBlocking {
        switchOn("/rest/v1/categories", """[{"type":"expense"}]""")
        assertTrue(runCatching { repository.saveBudget(null, category, "20.00") }.isFailure)
        assertEquals(0, writes.get()); assertEquals(1, server.requestCount)
        assertEquals("eq.$owner", server.takeRequest().requestUrl!!.queryParameter("user_id"))
    }
    @Test fun `category validation awaited by A cannot create a recurring expense under B`() = runBlocking {
        switchOn("/rest/v1/categories", """[{"type":"expense"}]""")
        assertTrue(runCatching { repository.saveRecurring(null, "Synthetic", "20.00", "EUR", category,
            RecurringFrequency.MONTHLY, "1", "2027-01-01", true) }.isFailure)
        assertEquals(0, writes.get()); assertEquals(1, server.requestCount)
        assertEquals("eq.$owner", server.takeRequest().requestUrl!!.queryParameter("user_id"))
    }
    @Test fun `cooldown read awaited by A cannot invoke price service for B`() = runBlocking {
        switchOn("/rest/v1/manual_price_updates", "[]")
        assertTrue(runCatching { repository.updatePrices() }.isFailure)
        assertEquals(0, writes.get()); assertEquals(1, server.requestCount)
        assertEquals("eq.$owner", server.takeRequest().requestUrl!!.queryParameter("user_id"))
    }
    @Test fun `shared link preflight awaited by A cannot invoke partner removal as B`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method != "GET") { writes.incrementAndGet(); return json("{}").setResponseCode(403) }
                return when (request.requestUrl!!.encodedPath) {
                    "/rest/v1/couple_connections" -> json("""[{"id":"$connection","user_a":"$owner","user_b":"$other","revoked_at":null}]""")
                    "/rest/v1/shared_expenses_view" -> {
                        switchAccount()
                        json("""[{"id":"$share","connection_id":"$connection","original_tx_id":"$transaction","created_by":"$other","creator_share_amount":6.01,"my_share_amount":4.00,"total_amount":10.01,"currency":"EUR","exchange_rate_eur":1,"description":"Synthetic","date":"2026-10-12","tx_deleted_at":null}]""")
                    }
                    else -> json("{}").setResponseCode(404)
                }
            }
        }
        val expense = FinanceSharedExpense(share, connection, transaction, other, null, "6.01".toBigDecimal(),
            "4.00".toBigDecimal(), "10.01".toBigDecimal(), "EUR", java.math.BigDecimal.ONE, "Synthetic", LocalDate.parse("2026-10-12"))
        assertTrue(runCatching { repository.removeTransactionShare(expense) }.isFailure)
        assertEquals(0, writes.get()); assertEquals(2, server.requestCount)
    }
}
