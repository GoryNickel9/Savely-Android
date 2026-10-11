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
import java.math.BigDecimal

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

    @Test fun `dashboard reads personal paginated budgets alongside transactions without extra modules`() = runBlocking {
        repeat(3) { server.enqueue(response("[]")) }
        server.enqueue(response("""[{"id":"budget","category_id":"food","amount":"50.00","currency":"USD"}]"""))
        server.enqueue(response("[]"))
        val data = repository.load("dashboard")
        assertEquals(BigDecimal("50.00"), data.budgets.single().amount)
        assertEquals("USD", data.budgets.single().currency)
        val requests = (0 until 5).map { server.takeRequest() }
        assertEquals(listOf("categories", "profiles", "transactions", "budgets", "budgets"), requests.map { it.requestUrl!!.pathSegments.last() })
        requests.forEach { assertEquals("GET", it.method); assertEquals("eq.$user", it.requestUrl!!.queryParameter("user_id")) }
        assertEquals("500", requests.last().requestUrl!!.queryParameter("limit"))
        assertEquals("0", requests[3].requestUrl!!.queryParameter("offset"))
        assertEquals("1", requests.last().requestUrl!!.queryParameter("offset"))
        assertEquals("id.asc", requests.last().requestUrl!!.queryParameter("order"))
        assertEquals(5, server.requestCount)
    }

    @Test fun `dashboard budget read errors remain visible instead of false empty summary`() = runBlocking {
        repeat(3) { server.enqueue(response("[]")) }
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody("{}"))
        val failure = try { repository.load("dashboard"); null } catch (exception: ApiException) { exception }
        assertEquals(ApiError.PERMISSION, failure?.kind)
        assertEquals(4, server.requestCount)
    }

    @Test fun `insights loads paginated own recurring including inactive rows and account dismissals`() = runBlocking {
        dismissals.ignore(user, "EUR:ignored")
        dismissals.ignore("another-account", "EUR:other")
        repeat(4) { server.enqueue(response("[]")) }
        fun recurring(id: String) = JSONObject().put("id", id).put("name", "Tracked")
            .put("amount", "12.50").put("currency", "EUR").put("category_id", JSONObject.NULL)
            .put("frequency", "monthly").put("week_interval", 1).put("next_due_date", "2026-10-01").put("is_active", false)
        val page = JSONArray()
        repeat(500) { page.put(recurring("r-$it")) }
        server.enqueue(response(page.toString()))
        server.enqueue(response(JSONArray().put(recurring("last")).toString()))
        server.enqueue(response("[]"))
        server.enqueue(response("[]"))
        server.enqueue(response("[]"))
        val data = repository.load("insights")
        assertEquals(501, data.recurring.size)
        assertTrue(data.recurring.none { it.active })
        assertEquals(setOf("EUR:ignored"), data.ignoredRecurringSuggestions)
        val requests = (0 until 9).map { server.takeRequest() }
        assertEquals(listOf("categories", "profiles", "transactions", "budgets", "recurring_expenses", "recurring_expenses", "recurring_expenses", "portfolio_assets", "net_worth_snapshots"),
            requests.map { it.requestUrl!!.pathSegments.last() })
        requests.forEach { assertEquals("GET", it.method); assertEquals("eq.$user", it.requestUrl!!.queryParameter("user_id")) }
        val first = requests[4].requestUrl!!
        assertEquals("is.null", first.queryParameter("deleted_at"))
        assertEquals(null, first.queryParameter("is_active"))
        assertEquals("next_due_date.asc,id.asc", first.queryParameter("order"))
        assertEquals("500", first.queryParameter("limit"))
        assertEquals("0", first.queryParameter("offset"))
        assertEquals("500", requests[5].requestUrl!!.queryParameter("offset"))
    }

    @Test fun `insights recurring query failure does not pretend there are no tracked candidates`() = runBlocking {
        repeat(4) { server.enqueue(response("[]")) }
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody("{}"))
        val failure = try { repository.load("insights"); null } catch (exception: ApiException) { exception }
        assertEquals(ApiError.PERMISSION, failure?.kind)
        assertEquals(5, server.requestCount)
        repeat(4) { server.takeRequest() }
        assertEquals("/rest/v1/recurring_expenses", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun `insights dismissal read failure remains visible rather than exposing ignored candidates`() = runBlocking {
        val failing = FinanceRepository(api, object : RecurringDismissals {
            override fun keys(userId: String): Set<String> { assertEquals(user, userId); error("Preferenze illeggibili") }
            override fun ignore(userId: String, key: String) = Unit
        })
        repeat(7) { server.enqueue(response("[]")) }
        val failure = try { failing.load("insights"); null } catch (exception: IllegalStateException) { exception }
        assertEquals("Preferenze illeggibili", failure?.message)
        assertEquals(7, server.requestCount)
        repeat(7) { assertEquals("GET", server.takeRequest().method) }
    }

    @Test fun `insights loads all personal server snapshots in stable date id order without inventing currency`() = runBlocking {
        repeat(6) { server.enqueue(response("[]")) }
        val firstDay = java.time.LocalDate.parse("2020-01-01")
        fun snapshot(index: Int) = JSONObject().put("id", "snapshot-$index").put("date", firstDay.plusDays(index.toLong()).toString())
            .put("net_worth", if (index == 500) "-99.00" else "-100.00").put("components", JSONObject.NULL)
        val first = JSONArray()
        repeat(500) { first.put(snapshot(it)) }
        server.enqueue(response(first.toString()))
        server.enqueue(response(JSONArray().put(snapshot(500)).toString()))
        server.enqueue(response("[]"))
        val data = repository.load("insights")
        assertEquals(501, data.snapshots.size)
        assertEquals(java.math.BigDecimal("-99.00"), data.snapshots.last().value)
        assertEquals(java.math.BigDecimal("-99.00"), netWorthMilestoneInsights(data.snapshots).single().value)
        val requests = (0 until 9).map { server.takeRequest() }
        assertEquals(listOf("categories", "profiles", "transactions", "budgets", "recurring_expenses", "portfolio_assets", "net_worth_snapshots", "net_worth_snapshots", "net_worth_snapshots"),
            requests.map { it.requestUrl!!.pathSegments.last() })
        requests.forEach { assertEquals("GET", it.method); assertEquals("eq.$user", it.requestUrl!!.queryParameter("user_id")) }
        val snapshotRequests = requests.takeLast(3)
        snapshotRequests.forEach {
            assertEquals("id,date,net_worth,components", it.requestUrl!!.queryParameter("select"))
            assertEquals("date.asc,id.asc", it.requestUrl!!.queryParameter("order"))
            assertEquals("500", it.requestUrl!!.queryParameter("limit"))
            assertEquals(null, it.requestUrl!!.queryParameter("deleted_at"))
            assertEquals(null, it.requestUrl!!.queryParameter("date"))
        }
        assertEquals("0", snapshotRequests.first().requestUrl!!.queryParameter("offset"))
        assertEquals("500", snapshotRequests[1].requestUrl!!.queryParameter("offset"))
        assertEquals("501", snapshotRequests.last().requestUrl!!.queryParameter("offset"))
        assertEquals(9, server.requestCount)
    }

    @Test fun `insights snapshot permission failure is surfaced rather than silently omitting the milestone`() = runBlocking {
        repeat(6) { server.enqueue(response("[]")) }
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody("{}"))
        val failure = try { repository.load("insights"); null } catch (exception: ApiException) { exception }
        assertEquals(ApiError.PERMISSION, failure?.kind)
        assertEquals(403, failure?.status)
        assertEquals(7, server.requestCount)
        repeat(6) { assertEquals("GET", server.takeRequest().method) }
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/rest/v1/net_worth_snapshots", request.requestUrl!!.encodedPath)
        assertEquals("eq.$user", request.requestUrl!!.queryParameter("user_id"))
    }

    @Test fun `insights failure on later snapshot page never exposes a partial history as a new maximum`() = runBlocking {
        repeat(6) { server.enqueue(response("[]")) }
        val firstDay = java.time.LocalDate.parse("2020-01-01")
        val page = JSONArray()
        repeat(500) { index -> page.put(JSONObject().put("id", "snapshot-$index")
            .put("date", firstDay.plusDays(index.toLong()).toString()).put("net_worth", "$index.00")) }
        server.enqueue(response(page.toString()))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json").setBody("{}"))
        val failure = try { repository.load("insights"); null } catch (exception: ApiException) { exception }
        assertEquals(503, failure?.status)
        assertEquals(8, server.requestCount)
        repeat(7) { assertEquals("GET", server.takeRequest().method) }
        val last = server.takeRequest()
        assertEquals("GET", last.method)
        assertEquals("500", last.requestUrl!!.queryParameter("offset"))
        assertEquals("eq.$user", last.requestUrl!!.queryParameter("user_id"))
    }

    @Test fun `insights malformed snapshot amount fails parsing instead of replacing the server value with zero`() = runBlocking {
        repeat(6) { server.enqueue(response("[]")) }
        server.enqueue(response("""[{"id":"malformed","date":"2026-06-01","net_worth":null}]"""))
        server.enqueue(response("[]"))
        val failure = try { repository.load("insights"); null } catch (exception: NumberFormatException) { exception }
        assertTrue(failure != null)
        assertEquals(8, server.requestCount)
        repeat(8) { assertEquals("GET", server.takeRequest().method) }
    }

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
        server.enqueue(response("[]"))
        server.enqueue(response("""[{"id":"$tx"}]"""))
        server.enqueue(response("""[{"id":"$record"}]"""))
        repository.delete("categories", record)
        val query = server.takeRequest().requestUrl!!
        assertEquals("eq.$user", query.queryParameter("user_id"))
        assertEquals("eq.$record", query.queryParameter("category_id"))
        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("offset"))
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
        server.enqueue(response("[]"))
        server.enqueue(response("[]"))
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
