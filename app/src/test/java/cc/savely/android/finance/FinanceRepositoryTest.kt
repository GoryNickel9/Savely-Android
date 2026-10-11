package cc.savely.android.finance

import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.data.ApiException
import cc.savely.android.data.ApiError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FinanceRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: FinanceRepository
    private lateinit var api: SupabaseApi
    private lateinit var dismissals: MemoryRecurringDismissals
    private val user = "11111111-1111-4111-8111-111111111111"
    private val record = "22222222-2222-4222-8222-222222222222"

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val store = MemorySessionStore(Session("access", "refresh", Long.MAX_VALUE, user, "test@example.com"))
        api = SupabaseApi(BackendConfig(server.url("/").toString(), "public-key", server.url("/").toString()), store)
        dismissals = MemoryRecurringDismissals()
        repository = FinanceRepository(api, dismissals)
    }
    @After fun tearDown() { server.shutdown() }
    private fun response(value: String) = MockResponse().setHeader("Content-Type", "application/json").setHeader("Content-Range", "0-0/1").setBody(value)

    @Test fun `pagination continues past a full page and filters owner and deletion`() = runBlocking {
        val page = JSONArray()
        repeat(500) { page.put(JSONObject().put("id", "category-$it").put("name", "Category $it").put("type", "expense")) }
        server.enqueue(response(page.toString()))
        server.enqueue(response("[]"))
        server.enqueue(response("[]"))
        val data = repository.load("categories")
        assertEquals(500, data.categories.size)
        val first = server.takeRequest().requestUrl!!
        assertEquals("500", first.queryParameter("limit"))
        assertEquals("0", first.queryParameter("offset"))
        assertEquals("eq.$user", first.queryParameter("user_id"))
        assertEquals("is.null", first.queryParameter("deleted_at"))
        assertEquals("name.asc,id.asc", first.queryParameter("order"))
        assertEquals("500", server.takeRequest().requestUrl!!.queryParameter("offset"))
        assertEquals("/rest/v1/profiles", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun `transaction creates exact amount local date and owner`() = runBlocking {
        server.enqueue(response("""[{"id":"$record"}]"""))
        repository.saveTransaction(null, TransactionType.EXPENSE, "1.234,56", "USD", "0,91", null, "Spesa", "2026-10-11")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("1234.56", body.getString("amount"))
        assertEquals("0.910000", body.getString("exchange_rate_eur"))
        assertEquals("2026-10-11", body.getString("date"))
        assertEquals(user, body.getString("user_id"))
        assertTrue(body.isNull("category_id"))
    }

    @Test fun `transaction updates clear fields and scope owner`() = runBlocking {
        server.enqueue(response("""[{"id":"$record"}]"""))
        repository.saveTransaction(record, TransactionType.EXPENSE, "20", "EUR", "", null, "", "2026-10-11")
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("eq.$record", request.requestUrl!!.queryParameter("id"))
        assertEquals("eq.$user", request.requestUrl!!.queryParameter("user_id"))
        val body = JSONObject(request.body.readUtf8())
        assertTrue(body.isNull("category_id"))
        assertTrue(body.isNull("description"))
        assertEquals("1", body.getString("exchange_rate_eur"))
    }

    @Test fun `category deletion detaches own transactions then soft deletes`() = runBlocking {
        val tx = "33333333-3333-4333-8333-333333333333"
        server.enqueue(response("""[{"id":"$tx"}]"""))
        server.enqueue(response("""[{"id":"$tx"}]"""))
        server.enqueue(response("""[{"id":"$record"}]"""))
        repository.delete("categories", record)
        val query = server.takeRequest().requestUrl!!
        assertEquals("eq.$user", query.queryParameter("user_id"))
        assertEquals("eq.$record", query.queryParameter("category_id"))
        val detach = server.takeRequest()
        assertTrue(JSONObject(detach.body.readUtf8()).isNull("category_id"))
        assertEquals("eq.$user", detach.requestUrl!!.queryParameter("user_id"))
        val remove = server.takeRequest()
        assertEquals("PATCH", remove.method)
        assertEquals("/rest/v1/categories", remove.requestUrl!!.encodedPath)
        assertTrue(JSONObject(remove.body.readUtf8()).getString("deleted_at").endsWith("Z"))
        assertEquals("return=minimal,count=exact", remove.getHeader("Prefer"))
    }

    @Test fun `existing budget amount remains editable after category is soft deleted`() = runBlocking {
        server.enqueue(response("""[{"id":"$record"}]"""))
        repository.saveBudget(record, "category-no-longer-present", "0")
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/rest/v1/budgets", request.requestUrl!!.encodedPath)
        assertEquals("0.00", JSONObject(request.body.readUtf8()).getString("amount"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `partial price service errors remain visible after cooldown is logged`() = runBlocking {
        server.enqueue(response("[]"))
        server.enqueue(response("""{"updated":2,"checked":3,"errors":[{"symbol":"MISSING","error":"Price not found"}]}"""))
        server.enqueue(response("""[{"id":"$record"}]"""))
        val failure = try { repository.updatePrices(); null } catch (error: IllegalStateException) { error }
        assertTrue(failure?.message.orEmpty().contains("Aggiornamento parziale"))
        assertTrue(failure?.message.orEmpty().contains("MISSING"))
        server.takeRequest()
        assertEquals("/functions/v1/update-prices", server.takeRequest().requestUrl!!.encodedPath)
        val log = server.takeRequest()
        assertEquals("/rest/v1/manual_price_updates", log.requestUrl!!.encodedPath)
        assertEquals(2, JSONObject(log.body.readUtf8()).getInt("assets_updated"))
    }

    @Test fun `recurring reads owner history and local ignore persists without backend calls`() = runBlocking {
        repeat(4) { server.enqueue(response("[]")) }
        val data = repository.load("recurring")
        assertTrue(data.ignoredRecurringSuggestions.isEmpty())
        val requests = (0 until 4).map { server.takeRequest() }
        assertEquals(listOf("categories", "profiles", "transactions", "recurring_expenses"),
            requests.map { it.requestUrl!!.pathSegments.last() })
        requests.forEach { assertEquals("eq.$user", it.requestUrl!!.queryParameter("user_id")) }
        assertEquals("is.null", requests[2].requestUrl!!.queryParameter("deleted_at"))
        assertEquals("500", requests[2].requestUrl!!.queryParameter("limit"))
        repository.dismissRecurringSuggestion("EUR:netflix")
        assertEquals(4, server.requestCount)
        repeat(4) { server.enqueue(response("[]")) }
        assertEquals(setOf("EUR:netflix"), FinanceRepository(api, dismissals).load("recurring").ignoredRecurringSuggestions)
    }

    @Test fun `failed local persistence is surfaced without writing backend`() = runBlocking {
        val failing = FinanceRepository(api, object : RecurringDismissals {
            override fun keys(userId: String) = emptySet<String>()
            override fun ignore(userId: String, key: String) { error("Disco pieno") }
        })
        repeat(4) { server.enqueue(response("[]")) }
        failing.load("recurring")
        val error = try { failing.dismissRecurringSuggestion("EUR:netflix"); null } catch (exception: IllegalStateException) { exception }
        assertEquals("Disco pieno", error?.message)
        assertEquals(4, server.requestCount)
    }

    @Test fun `portfolio TCG read paginates own cards even with module permission disabled`() = runBlocking {
        server.enqueue(response("[]"))
        server.enqueue(response("""[{"default_currency":"EUR","permissions":{"tcg":false}}]"""))
        server.enqueue(response("[]"))
        server.enqueue(response("[]"))
        val page = JSONArray()
        repeat(500) { page.put(JSONObject().put("id", "card-$it").put("category", "magic").put("quantity", 1)
            .put("purchase_price", "1.00").put("current_price", "2.00").put("currency", "EUR")) }
        server.enqueue(response(page.toString()))
        server.enqueue(response("""[{"id":"last-card","category":"pokemon","quantity":2,"purchase_price":"12.34","current_price":"15.01","currency":null}]"""))
        val data = repository.load("portfolio")
        assertEquals(501, data.tcgCards.size)
        assertEquals("EUR", data.tcgCards.last().currency)
        assertEquals(java.math.BigDecimal("30.02"), tcgPortfolioTotals(data.tcgCards).last().value)
        repeat(4) { server.takeRequest() }
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("GET", first.method)
        assertEquals("/rest/v1/tgc_cards", first.requestUrl!!.encodedPath)
        assertEquals("eq.$user", first.requestUrl!!.queryParameter("user_id"))
        assertEquals("created_at.desc,id.asc", first.requestUrl!!.queryParameter("order"))
        assertEquals("id,category,quantity,purchase_price,current_price,currency", first.requestUrl!!.queryParameter("select"))
        assertEquals("0", first.requestUrl!!.queryParameter("offset"))
        assertEquals("500", second.requestUrl!!.queryParameter("offset"))
        assertEquals("eq.$user", second.requestUrl!!.queryParameter("user_id"))
    }

    @Test fun `TCG permission or transport errors do not become silent zero portfolio totals`() = runBlocking {
        repeat(4) { server.enqueue(response("[]")) }
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json")
            .setBody("""{"message":"TCG fixture denied"}"""))
        val failure = try { repository.load("portfolio"); null } catch (exception: ApiException) { exception }
        assertEquals(ApiError.PERMISSION, failure?.kind)
        assertEquals(403, failure?.status)
        assertEquals(5, server.requestCount)
    }

    @Test fun `net worth does not read TCG or change server formula`() = runBlocking {
        repeat(5) { server.enqueue(response("[]")) }
        val data = repository.load("net-worth")
        assertTrue(data.tcgCards.isEmpty())
        assertTrue(netWorthComponents(data.transactions, data.assets).isEmpty())
        val paths = (0 until 5).map { server.takeRequest().requestUrl!!.encodedPath }
        assertTrue("/rest/v1/tgc_cards" !in paths)
        assertEquals("/rest/v1/net_worth_snapshots", paths.last())
    }
}
