package cc.savely.android.modules

import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList

class CollectionOverviewRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var store: MemorySessionStore
    private lateinit var repository: CollectionOverviewRepository
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val rows = mutableListOf<JSONObject>()
    private var failOffset: Int? = null
    private var switchOffset: Int? = null
    private var reloginOffset: Int? = null
    private var duplicateSecondPage = false
    private var foreignSecondPage = false

    @Before fun setup() {
        store = MemorySessionStore(session(OWNER))
        repeat(501) { index -> rows.add(JSONObject().put("id", "row-$index").put("user_id", OWNER)
            .put("category", if (index < 500) "magic" else "pokemon").put("quantity", 1)
            .put("currency", "EUR").put("purchase_price", BigDecimal("0.01")).put("current_price", BigDecimal("0.02"))) }
        client = OkHttpClient()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    val url = request.requestUrl ?: return json(403, JSONObject())
                    if (request.method != "GET" || url.encodedPath != "/rest/v1/tgc_cards" ||
                        request.getHeader("Authorization") != "Bearer overview-access" || request.getHeader("apikey") != "public-test-key" ||
                        url.queryParameter("user_id") != "eq.$OWNER" || url.queryParameter("order") != "id.asc" ||
                        url.queryParameter("limit") != "500" || url.queryParameter("category") != null) return json(403, JSONObject())
                    val offset = url.queryParameter("offset")?.toIntOrNull() ?: return json(403, JSONObject())
                    if (offset == failOffset) return json(503, JSONObject().put("message", "Synthetic second-page failure"))
                    if (offset == switchOffset) store.save(session(FOREIGN_OWNER))
                    if (offset == reloginOffset) {
                        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                            JSONObject().put("sub", OWNER).put("session_id", "37000000-0000-4000-8000-000000000003").toString().toByteArray())
                        store.save(session(OWNER).copy(accessToken = "e30.$payload.fixture", refreshToken = "new-overview-refresh"))
                    }
                    val selected = rows.drop(offset).take(500).map { JSONObject(it.toString()) }.toMutableList()
                    if (offset == 500 && duplicateSecondPage) selected[0] = JSONObject(rows[0].toString())
                    if (offset == 500 && foreignSecondPage) selected[0].put("user_id", FOREIGN_OWNER)
                    return json(200, JSONArray(selected))
                }
            }
            start()
        }
        repository = CollectionOverviewRepository(SupabaseApi(BackendConfig(server.url("/").toString(), "public-test-key"), store, client))
    }
    @After fun cleanup() {
        server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }

    @Test fun personalPagesAreCompleteBeforeTotalsAndStableOrderIncludesEveryCategory() {
        runBlocking {
            val result = repository.load(CollectionKind.TCG)
            assertEquals(listOf("0", "500", "501"), requests.map { it.requestUrl!!.queryParameter("offset") })
            assertTrue(requests.all { it.method == "GET" })
            assertEquals(501L, result.pieces)
            assertEquals(501, result.records)
            assertEquals(0, BigDecimal("5.01").compareTo(result.currencies.single().investment))
            assertEquals(0, BigDecimal("10.02").compareTo(result.currencies.single().value))
            assertEquals(500L, result.categories.first { it.category == "magic" }.pieces)
            assertEquals(1L, result.categories.first { it.category == "pokemon" }.pieces)
        }
    }

    @Test fun failedLaterPageDoesNotReturnFirstPageTotalsOrRetryAutomatically() {
        failOffset = 500
        runBlocking {
            assertTrue(runCatching { repository.load(CollectionKind.TCG) }.isFailure)
            assertEquals(listOf("0", "500"), requests.map { it.requestUrl!!.queryParameter("offset") })
        }
    }

    @Test fun changedAccountDoesNotPublishOldOwnerSnapshotOrRequestNextPage() {
        switchOffset = 500
        runBlocking {
            assertTrue(runCatching { repository.load(CollectionKind.TCG) }.isFailure)
            assertEquals(2, requests.size)
        }
    }

    @Test fun sameOwnerWithNewLoginRejectsTheWholeSnapshotAndStopsBeforeNextPage() {
        reloginOffset = 500
        runBlocking {
            val before = rows.map(JSONObject::toString)
            assertTrue(runCatching { repository.load(CollectionKind.TCG) }.isFailure)
            assertEquals(listOf("0", "500"), requests.map { it.requestUrl!!.queryParameter("offset") })
            assertTrue(requests.all { it.method == "GET" })
            assertEquals(before, rows.map(JSONObject::toString))
        }
    }

    @Test fun duplicateOrForeignSecondPageRejectsWholeReadWithoutMutation() {
        runBlocking {
            val before = rows.map(JSONObject::toString)
            duplicateSecondPage = true
            assertTrue(runCatching { repository.load(CollectionKind.TCG) }.isFailure)
            duplicateSecondPage = false; foreignSecondPage = true
            assertTrue(runCatching { repository.load(CollectionKind.TCG) }.isFailure)
            assertEquals(before, rows.map(JSONObject::toString))
            assertTrue(requests.all { it.method == "GET" })
            assertEquals(listOf("0", "500", "0", "500"), requests.map { it.requestUrl!!.queryParameter("offset") })
        }
    }

    private fun session(owner: String) = Session("overview-access", "overview-refresh", Long.MAX_VALUE, owner, "overview@example.test")
    private fun json(code: Int, body: Any) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())
    private companion object {
        const val OWNER = "37000000-0000-4000-8000-000000000001"
        const val FOREIGN_OWNER = "37000000-0000-4000-8000-000000000002"
    }
}
