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
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MangaVolumesTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var store: MemorySessionStore
    private lateinit var repository: MangaVolumeRepository
    private val rows = ConcurrentHashMap<String, JSONObject>()
    private val posts = CopyOnWriteArrayList<JSONObject>()
    private val reads = CopyOnWriteArrayList<RecordedRequest>()
    private val outcomes = ConcurrentHashMap<Int, Outcome>()
    private val failReads = AtomicBoolean(false)
    private val beforePostSnapshot = AtomicReference<String?>()
    private val snapshotsObservedAtPost = CopyOnWriteArrayList<String>()

    private enum class Outcome { REJECT, COMMIT_AND_DISCONNECT, COMMIT_AND_CONFLICT, COMMIT_BAD_REPRESENTATION }

    @Before fun setup() {
        client = OkHttpClient()
        store = MemorySessionStore(session(OWNER))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") {
                        val body = JSONObject(request.body.readUtf8())
                        snapshotsObservedAtPost.add(beforePostSnapshot.get().orEmpty())
                        posts.add(body)
                        assertEquals("library_items", request.requestUrl!!.pathSegments.last())
                        assertEquals(OWNER, body.getString("user_id"))
                        when (outcomes[posts.size]) {
                            Outcome.REJECT -> return json(503, JSONObject().put("message", "Synthetic POST failure"))
                            Outcome.COMMIT_AND_DISCONNECT -> {
                                rows[body.getString("id")] = body
                                return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                            }
                            Outcome.COMMIT_AND_CONFLICT -> {
                                rows[body.getString("id")] = body
                                return json(409, JSONObject().put("code", "23505"))
                            }
                            Outcome.COMMIT_BAD_REPRESENTATION -> {
                                rows[body.getString("id")] = body
                                return json(201, JSONArray())
                            }
                            null -> {
                                if (rows.putIfAbsent(body.getString("id"), body) != null) return json(409, JSONObject().put("code", "23505"))
                                return json(201, JSONArray().put(body))
                            }
                        }
                    }
                    if (request.method == "GET") {
                        reads.add(request)
                        if (failReads.get()) return json(503, JSONObject().put("message", "Synthetic read failure"))
                        val url = request.requestUrl!!
                        val id = url.queryParameter("id")?.removePrefix("eq.")
                        val owner = url.queryParameter("user_id")?.removePrefix("eq.")
                        assertEquals("2", url.queryParameter("limit"))
                        val row = rows[id]?.takeIf { it.getString("user_id") == owner }
                        return json(200, JSONArray().also { if (row != null) it.put(row) })
                    }
                    return json(405, JSONObject())
                }
            }
            start()
        }
        repository = MangaVolumeRepository(api())
    }

    @After fun teardown() {
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /** Executed original parseVolumeRange from readonly Manga.tsx, SHA 1cfcf629, via Node on 2026-10-11. */
    @Test fun parserMatchesExecutedWebFixturesForValidInputsAndCountLimit() {
        assertNull(MangaVolumes.parse(""))
        assertNull(MangaVolumes.parse("  "))
        assertEquals(listOf(3), MangaVolumes.parse("3"))
        assertEquals((1..5).toList(), MangaVolumes.parse("1-5"))
        assertEquals(listOf(2, 3, 4), MangaVolumes.parse("2–4"))
        assertEquals((1..100).toList(), MangaVolumes.parse("1-100"))
        assertEquals(listOf(1000, 1001, 1002), MangaVolumes.parse("1000-1002"))
        assertEquals(listOf(3), MangaVolumes.parse(" 003 "))
        assertEquals(listOf(Int.MAX_VALUE), MangaVolumes.parse(Int.MAX_VALUE.toString()))
    }

    @Test fun invalidAndOverflowRangesNeverFallBackToOrdinaryCreation() {
        listOf("0", "-1", "5-3", "1-101", "1-9999999999", "2147483648", "3x", "3.5", "1-2x",
            "1—3", "1,2", "x", "9".repeat(65)).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { MangaVolumes.parse(value) }
        }
        // Intentional validation difference: the web's Number.parseInt("3x") produces volume 3.
    }

    @Test fun payloadExpansionPreservesUnitPricesMetadataAndSourceAndGeneratesDistinctIds() {
        val base = base().put("quantity", 7).put("title", "  Berserk  ")
        val source = base.toString()
        val batch = MangaVolumes.prepare(OWNER, base, listOf(2, 3, 4))
        assertEquals(source, base.toString())
        assertEquals("Berserk", batch.baseTitle)
        assertEquals(3, batch.volumes.map { it.body().getString("id") }.distinct().size)
        batch.volumes.forEach { planned ->
            val body = planned.body()
            assertEquals("Berserk Vol. ${planned.number}", body.getString("title"))
            assertEquals(1, body.getInt("quantity"))
            assertEquals(OWNER, body.getString("user_id"))
            assertEquals("manga", body.getString("category"))
            assertEquals("Kentaro Miura", body.getString("author"))
            assertEquals("Panini", body.getString("publisher"))
            assertEquals(1997, body.getInt("year"))
            assertEquals("https://example.test/cover.jpg", body.getString("cover_image"))
            assertEquals("fixture-api-id", body.getString("api_id"))
            assertEquals("Nota sintetica", body.getString("notes"))
            assertEquals(0, BigDecimal("6.25").compareTo(BigDecimal(body.get("purchase_price").toString())))
            assertEquals(0, BigDecimal("8.50").compareTo(BigDecimal(body.get("reselling_value").toString())))
        }
        batch.volumes[0].body().put("notes", "Changed copy")
        assertEquals("Nota sintetica", batch.volumes[0].body().getString("notes"))
    }

    @Test fun snapshotRoundTripRetainsEveryIdPayloadConfirmedPrefixAndUncertainCurrentRow() {
        val original = plan().copy(completed = 1, reconcileCurrent = true)
        val restored = MangaVolumeBatch.decode(original.encode())
        assertEquals(original.ownerId, restored.ownerId)
        assertEquals(original.baseTitle, restored.baseTitle)
        assertEquals(1, restored.completed)
        assertTrue(restored.reconcileCurrent)
        assertEquals(original.volumes.map { it.number }, restored.volumes.map { it.number })
        assertEquals(original.volumes.map { it.payload }, restored.volumes.map { it.payload })
    }

    @Test fun snapshotRejectsWrongVersionDuplicateIdOwnerMismatchAndInvalidProgress() {
        fun snapshot() = JSONObject(plan().encode())
        val invalid = listOf(
            snapshot().put("version", 2), snapshot().put("version", "1"), snapshot().put("completed", 1.5),
            snapshot().put("completed", 4), snapshot().put("reconcile", "true"),
            snapshot().apply {
                val entries = getJSONArray("volumes")
                entries.getJSONObject(1).getJSONObject("payload").put("id", entries.getJSONObject(0).getJSONObject("payload").getString("id"))
            },
            snapshot().apply { getJSONArray("volumes").getJSONObject(0).getJSONObject("payload").put("user_id", OTHER_OWNER) },
            snapshot().apply { getJSONArray("volumes").getJSONObject(0).getJSONObject("payload").put("quantity", 2) },
            snapshot().apply { getJSONArray("volumes").getJSONObject(0).getJSONObject("payload").put("title", "Other title") },
        )
        invalid.forEach { value -> assertThrows(IllegalArgumentException::class.java) { MangaVolumeBatch.decode(value.toString()) } }
        assertThrows(IllegalArgumentException::class.java) { MangaVolumeBatch.decode("x".repeat(131073)) }
        assertThrows(Exception::class.java) { MangaVolumeBatch.decode("{broken") }
    }

    @Test fun existingDatabasePricePrecisionAndPayloadLimitsAreValidatedBeforeAnyWrite() {
        listOf("-1", "1.234", "100000000").forEach { amount ->
            assertThrows(IllegalArgumentException::class.java) {
                MangaVolumes.prepare(OWNER, base().put("purchase_price", BigDecimal(amount)), listOf(1))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { MangaVolumes.prepare(OWNER, base(), (1..101).toList()) }
        assertThrows(IllegalArgumentException::class.java) { MangaVolumes.prepare(OWNER, base(), listOf(1, 3)) }
        assertThrows(IllegalArgumentException::class.java) { MangaVolumes.prepare(OWNER, base().put("notes", "n".repeat(2000)), (1..100).toList()) }
        assertEquals(0, server.requestCount)
    }

    @Test fun successfulSavePublishesPendingSnapshotBeforePostAndNeverRewritesCompletedBatch(): Unit = runBlocking {
        val batch = plan()
        val progress = mutableListOf<MangaVolumeBatch>()
        val result = repository.save(batch) { current ->
            beforePostSnapshot.set(current.encode())
            progress.add(current)
        }
        assertTrue(result.complete)
        assertEquals(3, posts.size)
        assertEquals(3, rows.size)
        assertEquals(listOf(0, 1, 1, 2, 2, 3), progress.map { it.completed })
        assertTrue(progress.first().reconcileCurrent)
        assertTrue(progress.filter { it.reconcileCurrent }.all { !it.complete })
        assertNotNull(beforePostSnapshot.get())
        snapshotsObservedAtPost.forEachIndexed { index, encoded ->
            val pending = MangaVolumeBatch.decode(encoded)
            assertEquals(index, pending.completed)
            assertTrue(pending.reconcileCurrent)
            assertEquals(posts[index].getString("id"), pending.volumes[pending.completed].body().getString("id"))
        }
        val requestsBefore = server.requestCount
        val restored = MangaVolumeBatch.decode(result.encode())
        MangaVolumeRepository(api()).save(restored) { fail("Completed batches must not publish another request") }
        assertEquals(requestsBefore, server.requestCount)
    }

    @Test fun ambiguousCommitReconcilesExactOwnerAndPayloadWithoutDuplicatePost(): Unit = runBlocking {
        outcomes[2] = Outcome.COMMIT_AND_DISCONNECT
        val batch = plan()
        val result = repository.save(batch) {}
        assertTrue(result.complete)
        assertEquals(3, posts.size)
        assertEquals(batch.volumes.map { it.body().getString("id") }, posts.map { it.getString("id") })
        assertEquals(1, reads.size)
        assertEquals("eq.${batch.volumes[1].body().getString("id")}", reads[0].requestUrl!!.queryParameter("id"))
        assertEquals("eq.$OWNER", reads[0].requestUrl!!.queryParameter("user_id"))
        assertEquals("Bearer synthetic-access", reads[0].getHeader("Authorization"))
    }

    @Test fun reliableAbsenceRequiresExplicitRetryWithSameIdAndNeverRewritesConfirmedPrefix(): Unit = runBlocking {
        outcomes[2] = Outcome.REJECT
        val batch = plan()
        var snapshot = batch
        expectFailure { repository.save(batch) { snapshot = it } }
        assertEquals(1, snapshot.completed)
        assertTrue(snapshot.reconcileCurrent)
        assertEquals(2, posts.size) // Failure does not automatically re-POST even after a successful empty read.
        assertEquals(1, rows.size)
        val restored = MangaVolumeBatch.decode(snapshot.encode())
        val finished = MangaVolumeRepository(api()).save(restored) { snapshot = it }
        assertTrue(finished.complete)
        val ids = batch.volumes.map { it.body().getString("id") }
        assertEquals(listOf(ids[0], ids[1], ids[1], ids[2]), posts.map { it.getString("id") })
        assertEquals(3, rows.size)
        assertEquals(2, reads.size)
    }

    @Test fun failedReconciliationReadPreventsEveryRetryPost(): Unit = runBlocking {
        outcomes[1] = Outcome.REJECT
        failReads.set(true)
        var snapshot = plan()
        expectFailure { repository.save(snapshot) { snapshot = it } }
        assertTrue(snapshot.reconcileCurrent)
        assertEquals(0, snapshot.completed)
        assertEquals(1, posts.size)
        expectFailure { repository.save(MangaVolumeBatch.decode(snapshot.encode())) { snapshot = it } }
        assertEquals(1, posts.size)
        assertTrue(rows.isEmpty())
        assertEquals(2, reads.size)
    }

    @Test fun mismatchedExistingPayloadRefusesRetryAndPreservesExistingRow(): Unit = runBlocking {
        val batch = plan().copy(reconcileCurrent = true)
        val existing = batch.volumes[0].body().put("notes", "Changed on another client")
        rows[existing.getString("id")] = existing
        expectFailure { repository.save(batch) {} }
        assertTrue(posts.isEmpty())
        assertEquals("Changed on another client", rows[existing.getString("id")]!!.getString("notes"))
    }

    @Test fun conflictAfterCommitIsReadAndVerifiedWithoutAnotherPost(): Unit = runBlocking {
        outcomes[1] = Outcome.COMMIT_AND_CONFLICT
        val batch = MangaVolumes.prepare(OWNER, base(), listOf(3))
        assertTrue(repository.save(batch) {}.complete)
        assertEquals(1, posts.size)
        assertEquals(1, reads.size)
        assertEquals(1, rows.size)
    }

    @Test fun malformedSuccessfulResponseUsesExactIdReadInsteadOfDuplicatingInsertion(): Unit = runBlocking {
        outcomes[1] = Outcome.COMMIT_BAD_REPRESENTATION
        val batch = MangaVolumes.prepare(OWNER, base(), listOf(3))
        assertTrue(repository.save(batch) {}.complete)
        assertEquals(1, posts.size)
        assertEquals(1, reads.size)
    }

    @Test fun accountSwitchStopsBeforeAnyReadOrWrite(): Unit = runBlocking {
        val batch = plan().copy(reconcileCurrent = true)
        store.save(session(OTHER_OWNER))
        expectFailure { repository.save(batch) { fail("Different account must not advance the batch") } }
        assertEquals(0, server.requestCount)
    }

    private suspend fun expectFailure(action: suspend () -> Unit) {
        var failure: Exception? = null
        try { action() } catch (caught: Exception) { failure = caught }
        assertNotNull("Expected explicit failure", failure)
    }
    private fun api() = SupabaseApi(BackendConfig(server.url("/").toString(), "public-test-key"), store, client)
    private fun session(owner: String) = Session("synthetic-access", "synthetic-refresh", Long.MAX_VALUE, owner, "manga@example.test")
    private fun plan() = MangaVolumes.prepare(OWNER, base(), listOf(1, 2, 3))
    private fun base() = JSONObject().put("title", "Berserk").put("category", "manga").put("quantity", 1)
        .put("author", "Kentaro Miura").put("publisher", "Panini").put("year", 1997)
        .put("cover_image", "https://example.test/cover.jpg").put("api_id", "fixture-api-id")
        .put("purchase_price", BigDecimal("6.25")).put("reselling_value", BigDecimal("8.50"))
        .put("notes", "Nota sintetica")
    private fun json(status: Int, value: Any) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(value.toString())

    companion object {
        private const val OWNER = "31000000-0000-4000-8000-000000000001"
        private const val OTHER_OWNER = "31000000-0000-4000-8000-000000000002"
    }
}
