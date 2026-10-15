package cc.savely.android.finance

import androidx.lifecycle.viewModelScope
import cc.savely.android.data.ApiException
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

/** Local HTTP fixtures only: uncertain updates, prerequisite validation and device-local ignore ownership. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecurringWriteBoundaryTest {
    private val owner = "71000000-0000-4000-8000-000000000001"
    private val other = "71000000-0000-4000-8000-000000000002"
    private val recurringId = "71000000-0000-4000-8000-000000000003"
    private val categoryId = "71000000-0000-4000-8000-000000000004"
    private val sid = "71000000-0000-4000-8000-000000000005"
    private val otherSid = "71000000-0000-4000-8000-000000000006"
    private lateinit var server: MockWebServer
    private lateinit var store: MemorySessionStore
    private lateinit var api: SupabaseApi
    private val models = mutableListOf<FinanceViewModel>()
    private var mainReplaced = false

    private fun session(user: String = owner, sessionId: String = sid): Session {
        fun encode(raw: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
        val token = "${encode("{}")}.${encode("""{"sub":"$user","session_id":"$sessionId"}""")}.synthetic"
        return Session(token, "synthetic-refresh", Long.MAX_VALUE, user, "synthetic@example.test")
    }
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        store = MemorySessionStore(session())
        val url = server.url("/").toString()
        api = SupabaseApi(BackendConfig(url, "synthetic-public", url), store)
    }
    @After fun teardown() {
        try {
            runBlocking {
                withContext(Dispatchers.IO) {
                    withTimeout(10_000) { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
                }
            }
        } finally {
            if (mainReplaced) Dispatchers.resetMain()
            api.close()
            server.shutdown()
        }
    }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun model(repository: FinanceRepository): FinanceViewModel {
        Dispatchers.setMain(Dispatchers.Unconfined)
        mainReplaced = true
        return FinanceViewModel(repository, "recurring").also(models::add)
    }
    private suspend fun completed(model: FinanceViewModel, condition: (FinanceUiState) -> Boolean) =
        withContext(Dispatchers.IO) {
            withTimeout(10_000) {
                val state = model.state.first(condition)
                // State is emitted before mutation finally releases its busy flag.
                model.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
                state
            }
        }

    @Test fun `invalid recurring amount date interval and id fail before expired auth or category HTTP`() = runBlocking {
        store.save(session().copy(expiresAt = 1))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = json("{}", 503)
        }
        val repository = FinanceRepository(api)
        val invalid = listOf(
            Triple("0", "2026-06-15", null), Triple("NaN", "2026-06-15", null),
            Triple("12.50", "2026-02-30", null), Triple("12.50", "not-a-date", null),
            Triple("12.50", "2026-06-15", "invalid-uuid"),
        )
        invalid.forEach { (amount, date, id) ->
            assertNotNull(runCatching { repository.saveRecurring(id, "Synthetic", amount, "EUR", categoryId,
                RecurringFrequency.MONTHLY, "1", date, true) }.exceptionOrNull())
        }
        listOf("0", "521", "not-a-number").forEach { interval ->
            assertTrue(runCatching { repository.saveRecurring(null, "Synthetic", "12.50", "EUR", categoryId,
                RecurringFrequency.WEEKLY, interval, "2026-06-15", true) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals("Invalid input must not refresh auth or read a category", 0, server.requestCount)
    }

    @Test fun `toggle uncertain responses are pending and no request is automatically repeated`() = runBlocking {
        val responses = listOf(json("{}", 503), json("{}", 408), json("not-json"), json("[]"),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val repository = FinanceRepository(api)
        responses.forEachIndexed { index, response ->
            server.enqueue(response)
            val error = runCatching { repository.setRecurringActive(recurringId, true) }.exceptionOrNull()
            assertTrue("Uncertain variant $index must require reconciliation", error is FinancePendingRefreshException)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("eq.$owner", request.requestUrl!!.queryParameter("user_id"))
            assertEquals("eq.$recurringId", request.requestUrl!!.queryParameter("id"))
            assertEquals("is.null", request.requestUrl!!.queryParameter("deleted_at"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(setOf("is_active"), body.keys().asSequence().toSet())
            assertTrue(body.getBoolean("is_active"))
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test fun `explicit forbidden toggle stays correctable without an uncertainty wrapper`() = runBlocking {
        server.enqueue(json("{}", 403))
        val error = runCatching { FinanceRepository(api).setRecurringActive(recurringId, false) }.exceptionOrNull()
        assertTrue(error is ApiException)
        assertEquals(403, (error as ApiException).status)
        assertEquals(1, server.requestCount)
    }

    @Test fun `existing edit uncertainty preserves exact rounded payload and owner undeleted scope`() = runBlocking {
        server.enqueue(json("{}", 503))
        val error = runCatching { FinanceRepository(api).saveRecurring(recurringId, "  Synthetic  ", "0,015", "USD", null,
            RecurringFrequency.MONTHLY, "unused", "2024-02-29", false) }.exceptionOrNull()
        assertTrue(error is FinancePendingRefreshException)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("eq.$owner", request.requestUrl!!.queryParameter("user_id"))
        assertEquals("eq.$recurringId", request.requestUrl!!.queryParameter("id"))
        assertEquals("is.null", request.requestUrl!!.queryParameter("deleted_at"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("Synthetic", body.getString("name")); assertEquals("0.02", body.getString("amount"))
        assertEquals("USD", body.getString("currency")); assertEquals("2024-02-29", body.getString("next_due_date"))
        assertEquals(1, body.getInt("week_interval")); assertFalse(body.getBoolean("is_active")); assertTrue(body.isNull("category_id"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `successful PATCH followed by session replacement remains pending without using new credentials`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                store.save(session(sessionId = otherSid))
                return json("""[{"id":"$recurringId"}]""")
            }
        }
        val error = runCatching { FinanceRepository(api).setRecurringActive(recurringId, true) }.exceptionOrNull()
        assertTrue(error is FinancePendingRefreshException)
        assertEquals(session().accessToken, server.takeRequest().getHeader("Authorization")?.removePrefix("Bearer "))
        assertEquals(session(sessionId = otherSid).accessToken, api.session()!!.accessToken)
        assertEquals(1, server.requestCount)
    }

    @Test fun `committed toggle with lost reply blocks other operations through failed refresh`() = runBlocking {
        val committed = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.method == "PATCH") {
                committed.incrementAndGet(); json("{}", 503)
            } else json("{}", 503)
        }
        val model = model(FinanceRepository(api))
        model.setRecurringActive(recurringId, true)
        val failed = completed(model) { !it.saving && it.error != null }
        assertTrue(failed.reconciliationRequired)
        model.setRecurringActive(recurringId, false); model.processDueRecurring()
        model.refresh()
        completed(model) { !it.loading && it.error != null }
        assertTrue(model.state.value.reconciliationRequired)
        model.setRecurringActive(recurringId, false)
        assertEquals(1, committed.get())
        assertEquals(2, server.requestCount)
    }

    @Test fun `ignore rejects both another owner and a new session for the loaded owner without local writes`() = runBlocking {
        for (replacement in listOf(session(other, otherSid), session(sessionId = otherSid))) {
            store.save(session()); api.session()
            repeat(4) { server.enqueue(json("[]")) }
            val ignored = MemoryRecurringDismissals()
            val repository = FinanceRepository(api, ignored)
            repository.load("recurring")
            val before = server.requestCount
            store.save(replacement); api.session()
            assertTrue(runCatching { repository.dismissRecurringSuggestion("EUR:synthetic") }.isFailure)
            assertTrue(ignored.keys(owner).isEmpty()); assertTrue(ignored.keys(other).isEmpty())
            assertEquals(before, server.requestCount)
        }
    }

    private fun suggestionReads() {
        server.enqueue(json("[]")); server.enqueue(json("[]"))
        val transactions = JSONArray()
        listOf("2026-04-01", "2026-05-01", "2026-06-01").forEachIndexed { index, date -> transactions.put(JSONObject()
            .put("id", "synthetic-$index").put("type", "expense").put("amount", "12.50").put("currency", "EUR")
            .put("date", date).put("description", "Synthetic subscription").put("category_id", JSONObject.NULL)) }
        server.enqueue(json(transactions.toString())); server.enqueue(json("[]")); server.enqueue(json("[]"))
    }

    @Test fun `local ignore completion after owner change cannot publish stale dismissed UI state`() = runBlocking {
        val ignored = MemoryRecurringDismissals()
        val repository = FinanceRepository(api, object : RecurringDismissals {
            override fun keys(userId: String) = ignored.keys(userId)
            override fun ignore(userId: String, key: String) {
                ignored.ignore(userId, key)
                store.save(session(other, otherSid))
            }
        })
        suggestionReads()
        val model = model(repository)
        model.refresh()
        val loaded = completed(model) { !it.loading && it.updatedAt != null }
        val key = availableRecurringSuggestions(loaded.data!!).single().key
        model.dismissRecurringSuggestion(key)
        val failed = completed(model) { !it.saving && it.error != null }
        assertTrue(failed.data!!.ignoredRecurringSuggestions.isEmpty())
        assertEquals(setOf(key), ignored.keys(owner)) // A local commit can precede the postflight identity change.
        assertTrue(ignored.keys(other).isEmpty())
        assertEquals(other, api.session()!!.userId)
        assertEquals(5, server.requestCount)
    }

    @Test fun `reconciliation block rejects local ignore before storage mutation`() = runBlocking {
        val ignored = MemoryRecurringDismissals()
        suggestionReads()
        val model = model(FinanceRepository(api, ignored))
        model.refresh()
        val loaded = completed(model) { !it.loading && it.updatedAt != null }
        val key = availableRecurringSuggestions(loaded.data!!).single().key
        model.mutate({ throw FinancePendingRefreshException("Synthetic pending result", IllegalStateException("lost reply")) }) {}
        completed(model) { !it.saving && it.reconciliationRequired }
        model.dismissRecurringSuggestion(key)
        assertTrue(ignored.keys(owner).isEmpty())
        assertTrue(model.state.value.data!!.ignoredRecurringSuggestions.isEmpty())
        assertTrue(model.state.value.reconciliationRequired)
        assertEquals(5, server.requestCount)
    }
}
