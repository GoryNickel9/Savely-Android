package cc.savely.android.finance

import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RecurringProcessingTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: FinanceRepository
    private val user = "30000000-0000-4000-8000-000000000001"
    private val recurringId = "30000000-0000-4000-8000-000000000002"
    private val txId = "30000000-0000-4000-8000-000000000003"
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val url = server.url("/").toString()
        repository = FinanceRepository(SupabaseApi(BackendConfig(url, "public-test-key", url),
            MemorySessionStore(Session("access", "refresh", Long.MAX_VALUE, user, "recurring@example.test"))))
    }
    @After fun teardown() { server.shutdown() }
    private fun json(body: String, status: Int = 200) = MockResponse().setHeader("Content-Type", "application/json").setResponseCode(status).setBody(body)
    private fun recurring(date: String = "2026-01-31", frequency: String = "monthly", interval: Int = 1,
        name: String = "Abbonamento", active: Boolean = true) = JSONObject().put("id", recurringId).put("name", name)
        .put("amount", "12.50").put("currency", "EUR").put("category_id", JSONObject.NULL)
        .put("frequency", frequency).put("week_interval", interval).put("next_due_date", date).put("is_active", active)
    private fun due(row: JSONObject = recurring()) = json(JSONArray().put(row).toString())
    private fun updated() = json("""[{"id":"$recurringId"}]""")
    private fun enqueueDue(row: JSONObject = recurring()) { server.enqueue(due(row)); server.enqueue(json("[]")) }
    private fun takeDuePages() { repeat(2) { assertEquals("GET", server.takeRequest().method) } }

    @Test fun `toggle only changes active flag on owner undeleted record`() = runBlocking {
        server.enqueue(updated())
        repository.setRecurringActive(recurringId, false)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("eq.$user", request.requestUrl!!.queryParameter("user_id"))
        assertEquals("eq.$recurringId", request.requestUrl!!.queryParameter("id"))
        assertEquals("is.null", request.requestUrl!!.queryParameter("deleted_at"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(1, body.length())
        assertFalse(body.getBoolean("is_active"))
    }

    @Test fun `process creates exact dated transaction and clamps monthly date without catching up all backlog`() = runBlocking {
        enqueueDue(); server.enqueue(json("[]")); server.enqueue(json("""[{"id":"$txId"}]""")); server.enqueue(updated())
        assertEquals(RecurringProcessingResult(1, 0, 1), repository.processDueRecurring(LocalDate.parse("2026-10-11")))
        val query = server.takeRequest().requestUrl!!
        assertEquals("eq.$user", query.queryParameter("user_id"))
        assertEquals("is.null", query.queryParameter("deleted_at"))
        assertEquals("eq.true", query.queryParameter("is_active"))
        assertEquals("lte.2026-10-11", query.queryParameter("next_due_date"))
        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("offset"))
        val check = server.takeRequest().requestUrl!!
        assertEquals(listOf("gte.2026-01-01", "lt.2026-02-01"), check.queryParameterValues("date"))
        assertEquals("1", check.queryParameter("limit"))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(user, body.getString("user_id"))
        assertEquals("12.50", body.getString("amount"))
        assertEquals("2026-01-31", body.getString("date"))
        assertTrue(body.isNull("category_id"))
        assertFalse(body.has("exchange_rate_eur"))
        val advance = server.takeRequest()
        assertEquals("2026-02-28", JSONObject(advance.body.readUtf8()).getString("next_due_date"))
        assertEquals("eq.2026-01-31", advance.requestUrl!!.queryParameter("next_due_date"))
        assertEquals("eq.true", advance.requestUrl!!.queryParameter("is_active"))
        assertEquals("eq.$user", advance.requestUrl!!.queryParameter("user_id"))
        assertEquals(5, server.requestCount)
    }

    @Test fun `weekly same month transaction skips insert and advances custom interval`() = runBlocking {
        enqueueDue(recurring("2026-10-01", "weekly", 2))
        server.enqueue(json("""[{"id":"$txId"}]""")); server.enqueue(updated())
        assertEquals(RecurringProcessingResult(0, 1, 1), repository.processDueRecurring(LocalDate.parse("2026-10-11")))
        takeDuePages()
        val check = server.takeRequest().requestUrl!!
        assertEquals(listOf("gte.2026-10-01", "lt.2026-11-01"), check.queryParameterValues("date"))
        assertEquals("2026-10-15", JSONObject(server.takeRequest().body.readUtf8()).getString("next_due_date"))
        assertEquals(4, server.requestCount)
    }

    @Test fun `paused future and empty due records make no writes`() = runBlocking {
        server.enqueue(json(JSONArray().put(recurring(active = false)).put(recurring("2027-01-31").put("id", txId)).toString()))
        server.enqueue(json("[]"))
        assertEquals(RecurringProcessingResult(), repository.processDueRecurring(LocalDate.parse("2026-10-11")))
        assertEquals(2, server.requestCount)
        server.enqueue(json("[]"))
        assertEquals(RecurringProcessingResult(), repository.processDueRecurring(LocalDate.parse("2026-10-11")))
        assertEquals(3, server.requestCount)
    }

    @Test fun `description reserved characters cannot inject filters and December bounds reach January`() = runBlocking {
        val name = "Piano A&B #\"test\"\nuser_id=eq.other"
        enqueueDue(recurring("2026-12-31", name = name))
        server.enqueue(json("""[{"id":"$txId"}]""")); server.enqueue(updated())
        repository.processDueRecurring(LocalDate.parse("2026-12-31"))
        takeDuePages()
        val check = server.takeRequest().requestUrl!!
        assertEquals("eq.$name", check.queryParameter("description"))
        assertEquals(listOf("eq.$user"), check.queryParameterValues("user_id"))
        assertEquals(listOf("gte.2026-12-01", "lt.2027-01-01"), check.queryParameterValues("date"))
        assertEquals("2027-01-31", JSONObject(server.takeRequest().body.readUtf8()).getString("next_due_date"))
    }

    @Test fun `failed insert stops and does not advance or report success`() = runBlocking {
        enqueueDue(); server.enqueue(json("[]")); server.enqueue(json("{}", 403))
        val failure = try { repository.processDueRecurring(LocalDate.parse("2026-01-31")); null } catch (error: RecurringProcessingException) { error }
        assertEquals(RecurringProcessingResult(), failure?.result)
        assertTrue(failure?.message.orEmpty().contains("salvataggio della transazione"))
        assertTrue(failure?.message.orEmpty().contains("verifica i movimenti"))
        assertEquals(4, server.requestCount)
    }

    @Test fun `committed insert then failed advance reports partial result and explicit rerun deduplicates`() = runBlocking {
        enqueueDue(); server.enqueue(json("[]")); server.enqueue(json("""[{"id":"$txId"}]""")); server.enqueue(json("{}", 403))
        val failure = try { repository.processDueRecurring(LocalDate.parse("2026-01-31")); null } catch (error: RecurringProcessingException) { error }
        assertEquals(RecurringProcessingResult(1, 0, 0), failure?.result)
        assertTrue(failure?.message.orEmpty().contains("1 transazioni create"))
        assertTrue(failure?.message.orEmpty().contains("aggiornamento della scadenza"))
        assertEquals(5, server.requestCount)
        enqueueDue(); server.enqueue(json("""[{"id":"$txId"}]""")); server.enqueue(updated())
        assertEquals(RecurringProcessingResult(0, 1, 1), repository.processDueRecurring(LocalDate.parse("2026-01-31")))
        val methods = (0 until 9).map { server.takeRequest().method }
        assertEquals(1, methods.count { it == "POST" })
    }

    @Test fun `failed dedup check never creates or advances schedule`() = runBlocking {
        enqueueDue(); server.enqueue(json("{}", 500))
        val failure = try { repository.processDueRecurring(LocalDate.parse("2026-01-31")); null } catch (error: RecurringProcessingException) { error }
        assertEquals(RecurringProcessingResult(), failure?.result)
        assertTrue(failure?.message.orEmpty().contains("verifica delle transazioni"))
        assertEquals(3, server.requestCount)
    }

    @Test fun `processing preserves leap year quarterly and yearly clamp conventions`() = runBlocking {
        val cases = listOf(
            Triple("2024-01-31", "monthly", "2024-02-29"),
            Triple("2026-01-31", "quarterly", "2026-04-30"),
            Triple("2024-02-29", "yearly", "2025-02-28"),
        )
        cases.forEach { (date, frequency, expected) ->
            enqueueDue(recurring(date, frequency))
            server.enqueue(json("""[{"id":"$txId"}]""")); server.enqueue(updated())
            assertEquals(RecurringProcessingResult(0, 1, 1), repository.processDueRecurring(LocalDate.parse("2026-10-11")))
            takeDuePages(); server.takeRequest()
            assertEquals(expected, JSONObject(server.takeRequest().body.readUtf8()).getString("next_due_date"))
        }
    }

    @Test fun `invalid second record reports validation stage and preserves first committed work`() = runBlocking {
        val invalid = recurring("2026-02-01").put("id", "30000000-0000-4000-8000-000000000004").put("amount", "0")
        server.enqueue(json(JSONArray().put(recurring()).put(invalid).toString()))
        server.enqueue(json("[]"))
        server.enqueue(json("[]")); server.enqueue(json("""[{"id":"$txId"}]""")); server.enqueue(updated())
        val failure = try { repository.processDueRecurring(LocalDate.parse("2026-10-11")); null } catch (error: RecurringProcessingException) { error }
        assertEquals(RecurringProcessingResult(1, 0, 1), failure?.result)
        assertTrue(failure?.message.orEmpty().contains("validazione della ricorrenza"))
        assertTrue(failure?.message.orEmpty().contains("1 transazioni create"))
        assertEquals(5, server.requestCount)
        val methods = (0 until 5).map { server.takeRequest().method }
        assertEquals(listOf("GET", "GET", "GET", "POST", "PATCH"), methods)
    }
}
