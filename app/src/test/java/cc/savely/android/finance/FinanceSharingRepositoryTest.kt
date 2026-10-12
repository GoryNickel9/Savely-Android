package cc.savely.android.finance

import cc.savely.android.data.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FinanceSharingRepositoryTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val partner = "22222222-2222-4222-8222-222222222222"
    private val connection = "33333333-3333-4333-8333-333333333333"
    private val shareId = "44444444-4444-4444-8444-444444444444"
    private val txId = "55555555-5555-4555-8555-555555555555"
    private lateinit var server: MockWebServer
    private lateinit var api: SupabaseApi
    private lateinit var repository: FinanceRepository
    private val publicClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        api = SupabaseApi(BackendConfig(server.url("/").toString(), "public-key", server.url("/").toString()),
            MemorySessionStore(Session("access", "refresh", Long.MAX_VALUE, owner, "qa@example.test")))
        repository = FinanceRepository(api, exchangeRates = FinanceExchangeRates(publicClient, server.url("/latest")))
    }
    @After fun teardown() { api.close(); server.shutdown(); publicClient.dispatcher.executorService.shutdown(); publicClient.connectionPool.evictAll() }
    private fun response(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun active() = JSONObject().put("id", connection).put("user_a", owner).put("user_b", partner).put("revoked_at", JSONObject.NULL)
    private fun share(creator: String = owner, id: String = shareId) = JSONObject().put("id", id).put("connection_id", connection)
        .put("original_tx_id", txId).put("created_by", creator).put("couple_category_name", "Casa")
        .put("creator_share_amount", "6.01").put("my_share_amount", "4.00").put("total_amount", "10.01")
        .put("currency", "EUR").put("exchange_rate_eur", "1").put("description", "Casa").put("date", "2026-10-12").put("tx_deleted_at", JSONObject.NULL)
    @Test fun `transactions with disabled permission do not access couple tables`() = runBlocking {
        server.enqueue(response("[]")); server.enqueue(response("""[{"default_currency":"EUR","permissions":{"couple_expenses":false}}]""")); server.enqueue(response("[]"))
        val data = repository.load("transactions")
        assertNull(data.coupleConnection); assertTrue(data.sharedExpenses.isEmpty())
        assertEquals(3, server.requestCount)
        assertEquals(listOf("categories", "profiles", "transactions"), (0 until 3).map { server.takeRequest().requestUrl!!.pathSegments.last() })
    }
    @Test fun `transactions reads all active scoped shares and filters deleted originals without totals truncation`() = runBlocking {
        server.enqueue(response("[]")); server.enqueue(response("""[{"default_currency":"EUR","permissions":{"couple_expenses":true}}]""")); server.enqueue(response("[]"))
        server.enqueue(response(JSONArray().put(active()).toString()))
        server.enqueue(response(JSONArray().put(share()).toString()))
        server.enqueue(response(JSONArray().put(share(partner, UUID.randomUUID().toString()))
            .put(share(partner, UUID.randomUUID().toString()).put("tx_deleted_at", "2026-10-12T10:00:00Z")).toString()))
        server.enqueue(response("[]"))
        val data = repository.load("transactions")
        assertEquals(owner, data.userId); assertEquals(connection, data.coupleConnection!!.id)
        assertEquals(2, data.sharedExpenses.size)
        val requests = (0 until 7).map { server.takeRequest() }
        assertEquals("(user_a.eq.$owner,user_b.eq.$owner)", requests[3].requestUrl!!.queryParameter("or"))
        assertEquals("is.null", requests[3].requestUrl!!.queryParameter("revoked_at"))
        requests.takeLast(3).forEach { assertEquals("GET", it.method); assertEquals("eq.$connection", it.requestUrl!!.queryParameter("connection_id")) }
        assertEquals(listOf("0", "1", "3"), requests.takeLast(3).map { it.requestUrl!!.queryParameter("offset") })
    }
    @Test fun `failed later shared page remains an error rather than presenting a private-only ledger`() = runBlocking {
        server.enqueue(response("[]")); server.enqueue(response("""[{"permissions":{"couple_expenses":true}}]""")); server.enqueue(response("[]"))
        server.enqueue(response(JSONArray().put(active()).toString())); server.enqueue(response(JSONArray().put(share()).toString()))
        server.enqueue(response("{}", 503))
        val failure = runCatching { repository.load("transactions") }.exceptionOrNull()
        assertTrue(failure is ApiException); assertEquals(503, (failure as ApiException).status); assertEquals(6, server.requestCount)
    }
    private fun creationDispatcher(failShare: Boolean = false): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath
            return when {
                path == "/rest/v1/couple_connections" && request.method == "GET" -> response(JSONArray().put(active()).toString())
                path == "/rest/v1/transactions" && request.method == "POST" -> response(JSONArray().put(JSONObject(request.body.clone().readUtf8())).toString())
                path == "/rest/v1/transactions" && request.method == "GET" -> response(JSONArray().put(JSONObject()
                    .put("id", request.requestUrl!!.queryParameter("id")!!.removePrefix("eq.")).put("amount", "10.01").put("currency", "EUR")).toString())
                path == "/rest/v1/shared_expenses" && request.method == "GET" -> response("[]")
                path == "/rest/v1/shared_expenses" && request.method == "POST" -> if (failShare) response("{}", 403) else response(JSONArray().put(JSONObject(request.body.clone().readUtf8())).toString())
                else -> response("{}", 400)
            }
        }
    }
    @Test fun `new custom share commits owned transaction then shares exact allocated UUID and split`() = runBlocking {
        server.dispatcher = creationDispatcher()
        repository.saveTransaction(null, TransactionType.EXPENSE, "10.01", "EUR", "", null, "Synthetic", "2026-10-12",
            TransactionShareRequest(connection, "Casa", "4.00"))
        val requests = (0 until 6).map { server.takeRequest() }
        val transaction = JSONObject(requests[1].body.readUtf8())
        val shared = JSONObject(requests.last().body.readUtf8())
        assertEquals(owner, transaction.getString("user_id")); UUID.fromString(transaction.getString("id"))
        assertEquals(transaction.getString("id"), shared.getString("original_tx_id"))
        assertEquals(owner, shared.getString("created_by")); assertEquals(connection, shared.getString("connection_id"))
        assertEquals("custom", shared.getString("split_mode")); assertEquals("4.00", shared.getString("partner_amount"))
        assertEquals("eq.$owner", requests[3].requestUrl!!.queryParameter("user_id"))
        assertEquals(6, server.requestCount)
    }
    @Test fun `share failure reports committed UUID once and does not retry either write`() = runBlocking {
        server.dispatcher = creationDispatcher(failShare = true)
        val failure = runCatching { repository.saveTransaction(null, TransactionType.EXPENSE, "10.01", "EUR", "", null, "Synthetic", "2026-10-12",
            TransactionShareRequest(connection, null)) }.exceptionOrNull()
        assertTrue(failure is TransactionSharingException)
        assertTrue((failure as TransactionSharingException).transactionConfirmed)
        val requests = (0 until 6).map { server.takeRequest() }
        assertEquals(JSONObject(requests[1].body.readUtf8()).getString("id"), failure.transactionId)
        assertEquals(2, requests.count { it.method == "POST" }); assertEquals(6, server.requestCount)
    }
    @Test fun `lost personal insert response carries allocated UUID without claiming commit or retrying`() = runBlocking {
        server.enqueue(response(JSONArray().put(active()).toString()))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val failure = runCatching { repository.saveTransaction(null, TransactionType.EXPENSE, "10.01", "EUR", "", null, "Synthetic", "2026-10-12",
            TransactionShareRequest(connection, null)) }.exceptionOrNull()
        assertTrue(failure is TransactionSharingException)
        assertFalse((failure as TransactionSharingException).transactionConfirmed)
        server.takeRequest()
        val insert = server.takeRequest()
        assertEquals(failure.transactionId, JSONObject(insert.body.readUtf8()).getString("id"))
        assertEquals("POST", insert.method); assertEquals(2, server.requestCount)
    }
    @Test fun `invalid custom split and editing shared transaction fail before any HTTP`() = runBlocking {
        listOf("0", "10.01", "10.02", "4.001").forEach { amount ->
            assertTrue(runCatching { repository.saveTransaction(null, TransactionType.EXPENSE, "10.01", "EUR", "", null, "Synthetic", "2026-10-12",
                TransactionShareRequest(connection, null, amount)) }.isFailure)
        }
        assertTrue(runCatching { repository.saveTransaction(txId, TransactionType.EXPENSE, "10.01", "EUR", "", null, "Synthetic", "2026-10-12",
            TransactionShareRequest(connection, null)) }.isFailure)
        assertEquals(0, server.requestCount)
    }
    @Test fun `creator removes only link using creator scope and partner uses exact RPC`() = runBlocking {
        for (creator in listOf(owner, partner)) {
            val row = share(creator)
            server.enqueue(response(JSONArray().put(active()).toString())); server.enqueue(response(JSONArray().put(row).toString()))
            server.enqueue(response(if (creator == owner) JSONArray().put(JSONObject().put("id", shareId)).toString() else "null"))
            repository.removeTransactionShare(financeSharedExpenseFromJson(row, financeConnectionFromJson(active(), owner)))
            server.takeRequest(); server.takeRequest()
            val write = server.takeRequest()
            if (creator == owner) {
                assertEquals("DELETE", write.method); assertEquals("eq.$owner", write.requestUrl!!.queryParameter("created_by"))
                assertEquals("eq.$connection", write.requestUrl!!.queryParameter("connection_id")); assertEquals("eq.$shareId", write.requestUrl!!.queryParameter("id"))
            } else {
                assertEquals("POST", write.method); assertEquals("/rest/v1/rpc/delete_shared_expense_by_partner", write.requestUrl!!.encodedPath)
                assertEquals(setOf("p_id"), JSONObject(write.body.readUtf8()).keys().asSequence().toSet())
            }
        }
        assertEquals(6, server.requestCount)
    }
    @Test fun `revoked connection blocks share removal without deleting a personal transaction`() = runBlocking {
        server.enqueue(response("[]"))
        assertTrue(runCatching { repository.removeTransactionShare(financeSharedExpenseFromJson(share(), financeConnectionFromJson(active(), owner))) }.isFailure)
        assertEquals(1, server.requestCount); assertEquals("GET", server.takeRequest().method)
    }
    @Test fun `uncertain partner removal requires reconciliation without a retry or personal delete`() = runBlocking {
        val row = share(partner)
        server.enqueue(response(JSONArray().put(active()).toString())); server.enqueue(response(JSONArray().put(row).toString()))
        server.enqueue(response("{}", 503))
        val error = runCatching { repository.removeTransactionShare(financeSharedExpenseFromJson(row,
            financeConnectionFromJson(active(), owner))) }.exceptionOrNull()
        assertTrue(error is FinancePendingRefreshException)
        assertEquals("Rimozione della condivisione non confermata. Aggiorna i dati prima di continuare.", error!!.message)
        server.takeRequest(); server.takeRequest()
        assertEquals("/rest/v1/rpc/delete_shared_expense_by_partner", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(3, server.requestCount)
    }
    @Test fun `automatic quote occurs before personal INSERT and explicit edit rate stays unchanged`() = runBlocking {
        server.enqueue(response("""{"base":"USD","rates":{"EUR":0.9155555}}"""))
        server.enqueue(response("""[{"id":"$txId"}]""")); server.enqueue(response("""[{"id":"$txId"}]"""))
        repository.saveTransaction(null, TransactionType.EXPENSE, "1.01", "USD", "", null, "Synthetic", "2026-01-01")
        repository.saveTransaction(txId, TransactionType.EXPENSE, "1.01", "USD", "0.800001", null, "Synthetic", "2026-01-01")
        val quote = server.takeRequest(); val insert = server.takeRequest(); val update = server.takeRequest()
        assertNull(quote.getHeader("Authorization")); assertNull(quote.getHeader("apikey"))
        assertEquals("0.915556", JSONObject(insert.body.readUtf8()).getString("exchange_rate_eur"))
        assertEquals("0.800001", JSONObject(update.body.readUtf8()).getString("exchange_rate_eur"))
        assertEquals("PATCH", update.method); assertEquals("eq.$owner", update.requestUrl!!.queryParameter("user_id"))
    }
    @Test fun `failed quote cannot create an expense with fabricated exchange rate`() = runBlocking {
        server.enqueue(response("{}", 503))
        assertTrue(runCatching { repository.saveTransaction(null, TransactionType.EXPENSE, "1.01", "USD", "", null, "Synthetic", "2026-01-01") }.isFailure)
        assertEquals(1, server.requestCount); assertEquals("GET", server.takeRequest().method)
    }
}
