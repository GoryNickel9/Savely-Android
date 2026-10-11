package cc.savely.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

class CoupleRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: CoupleRepository
    private lateinit var store: MemorySessionStore

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        store = MemorySessionStore(Session("couple-test-token", "refresh", System.currentTimeMillis() / 1000 + 3600, USER, "couple@example.test"))
        repository = CoupleRepository(SupabaseApi(BackendConfig(server.url("/").toString(), "public-test"), store))
    }
    @After fun cleanup() { server.shutdown() }

    @Test fun invalidPartnerCodeNeverQueriesTheBackend() = runBlocking {
        expectInvalid { repository.sendInvitation("OABC2345") }
        expectInvalid { repository.sendInvitation("abcd1234") }
        expectInvalid { repository.sendInvitation("short") }
        assertEquals(0, server.requestCount)
    }

    @Test fun invitationNormalizesCodeAndRejectsSelfOrDuplicateWithoutWriting() = runBlocking {
        enqueue("\"$USER\"")
        expectInvalid { repository.sendInvitation(" abcd2345 ") }
        val lookup = server.takeRequest()
        assertEquals("/rest/v1/rpc/find_user_by_couple_code", lookup.path)
        assertEquals("ABCD2345", JSONObject(lookup.body.readUtf8()).getString("p_code"))
        enqueue("\"$PARTNER\""); enqueue("""[{"id":"$ROW1"}]""")
        expectInvalid { repository.sendInvitation("ABCD2345") }
        assertEquals("POST", server.takeRequest().method)
        val pending = server.takeRequest()
        assertEquals("eq.$USER", pending.requestUrl!!.queryParameter("sender_id"))
        assertEquals("eq.pending", pending.requestUrl!!.queryParameter("status"))
        assertEquals(3, server.requestCount)
    }

    @Test fun sharingVerifiesMembershipOwnerAndDuplicateThenWritesExactDecimal() = runBlocking {
        enqueue("""[{"id":"$CONNECTION"}]""")
        enqueue("""[{"id":"$TX","amount":1.01,"currency":"EUR"}]""")
        enqueue("[]")
        enqueue("""[{"id":"$ROW1"}]""")
        repository.shareExpense(CONNECTION, TX, " Casa ", "0,50")
        val active = server.takeRequest()
        assertEquals("is.null", active.requestUrl!!.queryParameter("revoked_at"))
        assertEquals("(user_a.eq.$USER,user_b.eq.$USER)", active.requestUrl!!.queryParameter("or"))
        val owned = server.takeRequest()
        assertEquals("eq.$USER", owned.requestUrl!!.queryParameter("user_id"))
        assertEquals("eq.expense", owned.requestUrl!!.queryParameter("type"))
        val existing = server.takeRequest()
        assertEquals("eq.$CONNECTION", existing.requestUrl!!.queryParameter("connection_id"))
        val post = server.takeRequest()
        assertEquals("Bearer couple-test-token", post.getHeader("Authorization"))
        val body = JSONObject(post.body.readUtf8())
        assertEquals("custom", body.getString("split_mode"))
        assertEquals("0.50", body.getString("partner_amount"))
        assertEquals("Casa", body.getString("couple_category_name"))
        assertEquals(USER, body.getString("created_by"))
        assertFalse(body.has("category_id"))
    }

    @Test fun invalidOrStaleShareNeverWrites() = runBlocking {
        expectInvalid { repository.shareExpense(CONNECTION, TX, "", "0") }
        assertEquals(0, server.requestCount)
        enqueue("[]")
        expectInvalid { repository.shareExpense(CONNECTION, TX, "", "0.01") }
        assertEquals(1, server.requestCount)
        server.takeRequest()
        enqueue("""[{"id":"$CONNECTION"}]"""); enqueue("""[{"id":"$TX","amount":10,"currency":"EUR"}]""")
        expectInvalid { repository.shareExpense(CONNECTION, TX, "", "10") }
        assertEquals(3, server.requestCount)
        server.takeRequest(); server.takeRequest()
        enqueue("""[{"id":"$CONNECTION"}]"""); enqueue("""[{"id":"$TX","amount":10,"currency":"EUR"}]"""); enqueue("""[{"id":"$ROW1"}]""")
        expectInvalid { repository.shareExpense(CONNECTION, TX, "", "4.00") }
        assertEquals(6, server.requestCount)
    }

    @Test fun loadPaginatesPastShortServerPagesBeforeComputingBudgetMedians() = runBlocking {
        enqueue("""[{"couple_code":"ABCD2345","permissions":{"couple_expenses":true}}]""")
        enqueue("""[{"id":"$CONNECTION","user_a":"$USER","user_b":"$PARTNER"}]""")
        enqueue("[]")
        enqueue(JSONArray().put(share(ROW1, "2026-01-01", "10.00", "EUR", "Casa")).toString())
        enqueue(JSONArray().put(share(ROW2, "2026-02-01", "20.00", "EUR", "Casa")).toString())
        enqueue("[]")
        enqueue("""[{"id":"$ROW1","couple_category_name":"Casa","currency":"EUR","amount":12}]""")
        enqueue("[]")
        enqueue("[]")
        enqueue("[]")
        val result = repository.load()
        assertEquals(2, result.shares.length())
        assertEquals(BigDecimal("15.00"), result.comparisons.single().median)
        assertTrue(result.comparisons.single().exceeded)
        assertEquals(1f, result.comparisons.single().progress, 0f)
        val requests = (1..10).map { server.takeRequest() }
        assertEquals("gt.$ROW1", requests[4].requestUrl!!.queryParameter("id"))
        assertEquals("gt.$ROW2", requests[5].requestUrl!!.queryParameter("id"))
        assertEquals("eq.$CONNECTION", requests[4].requestUrl!!.queryParameter("connection_id"))
        assertEquals("not.is.null", requests.last().requestUrl!!.queryParameter("revoked_at"))
    }

    @Test fun archivePaginatesShortPagesSortsRevocationAndNeverReadsPrivateFormerPartnerData() = runBlocking {
        enqueue("""[{"couple_code":"ABCD2345","permissions":{"couple_expenses":true}}]""")
        enqueue("[]"); enqueue("[]")
        enqueue(JSONArray().put(archive(ROW1, "2026-01-01T00:00:00Z")).toString())
        enqueue(JSONArray().put(archive(ROW2, "2026-02-01T00:00:00Z")).toString())
        enqueue("[]")
        val result = repository.load()
        assertNull(result.connection)
        assertEquals(listOf(ROW2, ROW1), result.archivedConnections.map { it.id })
        assertEquals("2026-02-01T00:00:00Z", result.archivedConnections.first().revokedAt.toString())
        assertEquals(0, result.shares.length()); assertEquals(0, result.budgets.length()); assertTrue(result.medians.isEmpty())
        val requests = (1..6).map { server.takeRequest() }
        requests.drop(3).forEach {
            assertEquals("GET", it.method)
            assertEquals("/rest/v1/couple_connections", it.requestUrl!!.encodedPath)
            assertEquals("(user_a.eq.$USER,user_b.eq.$USER)", it.requestUrl!!.queryParameter("or"))
            assertEquals("not.is.null", it.requestUrl!!.queryParameter("revoked_at"))
        }
        assertEquals("gt.$ROW1", requests[4].requestUrl!!.queryParameter("id"))
        assertEquals("gt.$ROW2", requests[5].requestUrl!!.queryParameter("id"))
    }

    @Test fun archiveFailureDoesNotReturnAnEmptyArchiveOrPartialSnapshot() = runBlocking {
        enqueue("""[{"permissions":{"couple_expenses":true}}]"""); enqueue("[]"); enqueue("[]")
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        try { repository.load(); fail("Expected archive loading error") } catch (_: ApiException) { }
        assertEquals(4, server.requestCount)
    }

    @Test fun archiveRejectsRowsOutsideTheOwnerScopeAndMalformedDates() = runBlocking {
        for (row in listOf(archive(ROW1, "2026-01-01T00:00:00Z").put("user_a", PARTNER).put("user_b", ROW2),
            archive(ROW1, "not-an-instant"))) {
            enqueue("""[{"permissions":{"couple_expenses":true}}]"""); enqueue("[]"); enqueue("[]")
            enqueue(JSONArray().put(row).toString()); enqueue("[]")
            try { repository.load(); fail("Expected invalid archive") } catch (_: IllegalArgumentException) { }
            catch (_: java.time.format.DateTimeParseException) { }
        }
        assertEquals(10, server.requestCount)
    }

    @Test fun archiveRejectsAccountSwitchBeforeReturningData() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.encodedPath.endsWith("profiles")) return MockResponse().setBody("""[{"permissions":{"couple_expenses":true}}]""")
                if (url.queryParameter("revoked_at") == "not.is.null") runBlocking {
                    store.save(Session("other-token", "refresh", System.currentTimeMillis() / 1000 + 3600, PARTNER, "other@example.test"))
                }
                return MockResponse().setBody("[]")
            }
        }
        try { repository.load(); fail("Expected account-switch rejection") }
        catch (error: ApiException) { assertEquals(ApiError.AUTHENTICATION, error.kind) }
    }

    @Test fun disabledPermissionStopsBeforeReadingCoupleData() = runBlocking {
        enqueue("""[{"permissions":{"couple_expenses":"true"}}]""")
        try { repository.load(); fail("Expected permission error") }
        catch (error: ApiException) { assertEquals(ApiError.PERMISSION, error.kind) }
        assertEquals(1, server.requestCount)
    }

    @Test fun duplicatePaginationCursorIsRejectedRatherThanRepeatingOrCalculatingTotals() = runBlocking {
        enqueue("""[{"permissions":{"couple_expenses":true}}]"""); enqueue("[]")
        enqueue("""[{"id":"$ROW1"}]"""); enqueue("""[{"id":"$ROW1"}]""")
        expectInvalid { repository.load() }
        assertEquals(4, server.requestCount)
    }

    private fun enqueue(body: String) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body)) }
    private fun archive(id: String, revokedAt: String): JSONObject = JSONObject().put("id", id).put("user_a", USER).put("user_b", PARTNER)
        .put("created_at", "2025-01-01T00:00:00Z").put("revoked_at", revokedAt)
    private suspend fun expectInvalid(block: suspend () -> Unit) {
        try { block(); fail("Expected invalid couple operation") } catch (_: IllegalArgumentException) { }
    }

    companion object {
        const val USER = "10000000-0000-0000-0000-000000000001"
        const val PARTNER = "10000000-0000-0000-0000-000000000002"
        const val CONNECTION = "20000000-0000-0000-0000-000000000001"
        const val TX = "30000000-0000-0000-0000-000000000001"
        const val ROW1 = "40000000-0000-0000-0000-000000000001"
        const val ROW2 = "40000000-0000-0000-0000-000000000002"
        fun share(id: String, date: String, amount: String, currency: String, category: String?, deleted: Boolean = false): JSONObject =
            JSONObject().put("id", id).put("date", date).put("my_share_amount", amount).put("currency", currency)
                .put("couple_category_name", category ?: JSONObject.NULL).put("tx_deleted_at", if (deleted) "2026-03-01T00:00:00Z" else JSONObject.NULL)
    }
}
