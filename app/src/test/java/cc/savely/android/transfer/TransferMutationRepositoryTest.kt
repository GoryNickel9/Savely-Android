package cc.savely.android.transfer

import cc.savely.android.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TransferMutationRepositoryTest {
    @Test fun committed503ReconcilesSameUuidAndPayloadWithoutSecondPost() = runBlocking {
        fixture { api, server ->
            var stored: JSONObject? = null
            val posts = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") {
                        assertEquals("/rest/v1/portfolio_assets", request.requestUrl!!.encodedPath)
                        posts.incrementAndGet(); stored = JSONObject(request.body.readUtf8())
                        return response(503, "{}")
                    }
                    assertEquals("eq.owner", request.requestUrl!!.queryParameter("user_id"))
                    return response(200, if (request.requestUrl!!.queryParameter("id") == null) JSONArray().put(stored).toString() else "[]")
                }
            }
            val repository = TransferRepository(api)
            val plan = plan(api, "portfolio_assets")
            try { repository.applyMutation(plan); fail("503 accepted") } catch (error: ApiException) { assertEquals(ApiError.SERVER, error.kind) }
            assertEquals(TransferMutationStatus.CONFIRMED, repository.reconcile(plan.copy(status = TransferMutationStatus.UNCERTAIN)))
            assertEquals(1, posts.get())
        }
    }

    @Test fun reliableAbsenceRequiresExplicitRetryAndReusesIdenticalUuidAndBody() = runBlocking {
        fixture { api, server ->
            val payloads = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET") return response(200, "[]")
                    payloads += request.body.readUtf8()
                    return if (payloads.size == 1) response(503, "{}") else response(201, JSONArray().put(JSONObject(payloads.last())).toString())
                }
            }
            val repository = TransferRepository(api)
            val original = plan(api, "categories")
            try { repository.applyMutation(original) } catch (_: ApiException) { }
            val uncertain = original.copy(status = TransferMutationStatus.UNCERTAIN)
            try { repository.applyMutation(uncertain); fail("Blind retry accepted") } catch (_: TransferException) { }
            assertEquals(1, payloads.size)
            assertEquals(TransferMutationStatus.ABSENT, repository.reconcile(uncertain))
            assertEquals(1, payloads.size)
            repository.applyMutation(original.copy(status = TransferMutationStatus.ABSENT))
            assertEquals(2, payloads.size)
            assertEquals(payloads[0], payloads[1])
        }
    }

    @Test fun sameUuidDifferentPayloadIsBlocked() = runBlocking {
        fixture { api, server ->
            val plan = plan(api, "isin_mappings")
            server.dispatcher = rowsDispatcher(JSONObject(plan.payload).put("name", "Other payload"))
            assertEquals(TransferMutationStatus.BLOCKED, TransferRepository(api).reconcile(plan))
        }
    }

    @Test fun readFailureNeverProvesAbsence() = runBlocking {
        fixture { api, server ->
            server.enqueue(response(503, "{}"))
            try { TransferRepository(api).reconcile(plan(api, "category_mappings")); fail("Failed GET treated as absence") }
            catch (error: ApiException) { assertEquals(ApiError.SERVER, error.kind) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun ownerLeakIsRejectedAndCannotMatchEvenWithSameUuid() = runBlocking {
        fixture { api, server ->
            val plan = plan(api, "isin_mappings")
            server.dispatcher = rowsDispatcher(JSONObject(plan.payload).put("user_id", "foreign"))
            try { TransferRepository(api).reconcile(plan); fail("Foreign response accepted") }
            catch (error: TransferException) { assertTrue(error.message!!.contains("altro account")) }
        }
    }

    @Test fun changedAccountBlocksMutationBeforeAnyRequest() = runBlocking {
        fixture { api, server ->
            try { TransferRepository(api).applyMutation(plan(api, "categories").copy(owner = "other")); fail("Wrong account accepted") }
            catch (_: TransferException) { }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun updateAndDeleteAlwaysUseExplicitOwnerAndPreserveIsinAndOtherMetadata() = runBlocking {
        fixture { api, server ->
            val base = plan(api, "isin_mappings")
            val before = JSONObject(base.payload).put("isin", "IE00BK5BQT80").put("symbol", "VWCE").put("asset_type", "etf")
            val plan = base.copy(payload = before.toString(), kind = TransferMutationKind.UPDATE, expectedBefore = before.toString())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("eq.owner", request.requestUrl!!.queryParameter("user_id"))
                    assertEquals("eq.${plan.id}", request.requestUrl!!.queryParameter("id"))
                    assertEquals("eq.\"VWCE\"", request.requestUrl!!.queryParameter("symbol"))
                    return response(200, JSONArray().put(JSONObject(plan.payload).put("created_at", "server-only")).toString())
                }
            }
            val repository = TransferRepository(api)
            repository.applyMutation(plan)
            repository.applyMutation(plan.copy(kind = TransferMutationKind.DELETE))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun concurrentOwnedMappingChangeCannotBeOverwrittenOrDeletedByStaleSnapshot() = runBlocking {
        fixture { api, server ->
            val original = plan(api, "isin_mappings")
            val before = JSONObject(original.payload).put("isin", "IE00BK5BQT80").put("symbol", "OLD")
                .put("name", "Fund, \"quoted\"").put("asset_type", "etf")
            val concurrent = JSONObject(before.toString()).put("symbol", "CONCURRENT")
            val mutations = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET") return response(200,
                        if (request.requestUrl!!.queryParameter("id") == null) JSONArray().put(concurrent).toString() else "[]")
                    assertEquals("eq.\"OLD\"", request.requestUrl!!.queryParameter("symbol"))
                    assertEquals("eq.\"Fund, \\\"quoted\\\"\"", request.requestUrl!!.queryParameter("name"))
                    if (request.requestUrl!!.queryParameter("symbol") == "eq.\"CONCURRENT\"") mutations.incrementAndGet()
                    return response(200, "[]") // Atomic predicate did not match: zero rows modified.
                }
            }
            val repository = TransferRepository(api)
            for (kind in listOf(TransferMutationKind.UPDATE, TransferMutationKind.DELETE)) {
                val plan = original.copy(payload = JSONObject(before.toString()).put("symbol", "REQUESTED").toString(),
                    kind = kind, expectedBefore = before.toString())
                try { repository.applyMutation(plan); fail("Concurrent mapping overwritten") } catch (_: ApiException) { }
                assertEquals(TransferMutationStatus.BLOCKED, repository.reconcile(plan.copy(status = TransferMutationStatus.UNCERTAIN)))
            }
            assertEquals(0, mutations.get())
            assertEquals("CONCURRENT", concurrent.getString("symbol"))
        }
    }

    @Test fun absentDeleteConfirmsButMissingUpdateRemainsBlocked() = runBlocking {
        fixture { api, server ->
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = response(200, "[]") }
            val repository = TransferRepository(api)
            assertEquals(TransferMutationStatus.CONFIRMED, repository.reconcile(plan(api, "isin_mappings").copy(kind = TransferMutationKind.DELETE)))
            assertEquals(TransferMutationStatus.BLOCKED, repository.reconcile(plan(api, "isin_mappings").copy(kind = TransferMutationKind.UPDATE)))
        }
    }

    @Test fun nullableCategoryMappingRetainsOwnedIdForUpdateRatherThanBlindInsert() = runBlocking {
        fixture { api, server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    if (request.method == "PATCH") {
                        assertEquals("is.null", url.queryParameter("category_id"))
                        assertEquals("eq.\"shop\"", url.queryParameter("description"))
                        assertEquals("eq.\"shared\"", url.queryParameter("source"))
                        return response(200, JSONArray().put(JSONObject(request.body.readUtf8())).toString())
                    }
                    if (url.queryParameter("id") != null || url.pathSegments.last() != "category_mappings") return response(200, "[]")
                    return response(200, JSONArray().put(JSONObject().put("id", "mapping-existing").put("user_id", "owner")
                        .put("description", "shop").put("category_id", JSONObject.NULL).put("source", "shared")).toString())
                }
            }
            val context = TransferRepository(api).importContext()
            assertEquals("mapping-existing", context.categoryMappings.single().id)
            assertNull(context.categoryMappings.single().categoryId)
            assertTrue(context.mappings.isEmpty())
            val previous = JSONObject().put("id", "mapping-existing").put("user_id", "owner").put("description", "shop")
                .put("category_id", JSONObject.NULL).put("source", "shared")
            val desired = JSONObject(previous.toString()).put("category_id", "owned-category")
            TransferRepository(api).applyMutation(TransferMutation("fixture-null", "category_mappings", "mapping-existing", "owner",
                desired.toString(), requireNotNull(context.ownerRevision), TransferMutationKind.UPDATE, expectedBefore = previous.toString()))
        }
    }

    private suspend fun fixture(block: suspend (SupabaseApi, MockWebServer) -> Unit) {
        val server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val origin = server.url("/").toString()
        val api = SupabaseApi(BackendConfig(origin, "fixture-public", origin),
            MemorySessionStore(Session("fixture-access", "fixture-refresh", System.currentTimeMillis() / 1000 + 3600, "owner", "fixture@example.test")), client)
        try { block(api, server) } finally { api.close(); server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    private suspend fun plan(api: SupabaseApi, table: String): TransferMutation {
        val id = "11000000-0000-4000-8000-000000000001"
        return TransferMutation("fixture", table, id, "owner", JSONObject().put("id", id).put("user_id", "owner").put("name", "Fixture").toString(), requireNotNull(api.sessionOwnerRevision(requireNotNull(api.session()))))
    }
    private fun rowsDispatcher(row: JSONObject) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = response(200, if (request.requestUrl!!.queryParameter("id") == null) JSONArray().put(row).toString() else "[]")
    }
    private fun response(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
}
