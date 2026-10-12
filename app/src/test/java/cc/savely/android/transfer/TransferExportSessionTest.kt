package cc.savely.android.transfer

import androidx.lifecycle.ViewModelStore
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException

/** Source-only regressions: no real accounts or backend; execution intentionally deferred. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferExportSessionTest {
    @Before fun setupMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun restoreMain() { Dispatchers.resetMain() }

    @Test fun newLoginForSameUidDuringReadAbortsWithoutFetchingLaterTables(): Unit = runBlocking {
        Fixture().use { fixture ->
            fixture.reloginDuringTransactionRead = true
            assertTrue(runCatching { TransferRepository(fixture.api).export() }.isFailure)
            assertEquals(listOf("couple_connections", "transactions"), fixture.requests.map { it.requestUrl!!.pathSegments.last() })
            assertTrue(fixture.requests.all { it.method == "GET" })
            assertEquals(0, fixture.denied.get())
        }
    }

    @Test fun preparedCsvCannotBeSavedAfterSameUidStartsANewSession(): Unit = runBlocking {
        Fixture().use { fixture ->
            val models = ViewModelStore()
            try {
                val model = TransferViewModel(fixture.api).also { models.put("export", it) }
                model.prepareExport()
                val prepared = withTimeout(10_000) { model.state.first { !it.busy } }
                val exported = requireNotNull(prepared.export)
                assertEquals(1, exported.counts.getValue("transactions"))
                assertTrue(exported.csv.contains("Owned export row"))
                fixture.store.save(session(NEW_LOGIN))
                val writes = AtomicInteger()
                val requestsBefore = fixture.requests.size
                model.saveExport { writes.incrementAndGet() }
                val rejected = withTimeout(10_000) { model.state.first { !it.busy } }
                assertEquals("Account cambiato. Prepara di nuovo l'export.", rejected.message)
                assertEquals(0, writes.get())
                assertEquals(requestsBefore, fixture.requests.size)
                assertEquals(0, fixture.denied.get())
            } finally { models.clear() }
        }
    }

    @Test fun unchangedSessionExportsSharedRowsOnlyThroughOwnedConnection(): Unit = runBlocking {
        Fixture().use { fixture ->
            fixture.includeConnection = true
            val exported = TransferRepository(fixture.api).export()
            assertEquals(OWNER, exported.userId)
            assertEquals(fixture.api.sessionOwnerRevision(requireNotNull(fixture.api.session())), exported.ownerRevision)
            assertEquals(1, exported.counts.getValue("transactions"))
            assertEquals(1, exported.counts.getValue("couple_connections"))
            assertEquals(1, exported.counts.getValue("shared_expenses"))
            assertEquals(1, exported.counts.getValue("couple_budgets"))
            assertEquals(5, TransferCsv.parseRows(exported.csv).size)
            assertEquals(0, fixture.denied.get())
            assertTrue(fixture.requests.all { it.method == "GET" })
        }
    }

    @Test fun freshViewModelPickerCallbackWithoutCsvDiscardsOnceAndNeverReadsOrWrites(): Unit = runBlocking {
        Fixture().use { fixture ->
            val models = ViewModelStore()
            try {
                val model = TransferViewModel(fixture.api).also { models.put("restored-export", it) }
                val discards = AtomicInteger()
                val writes = AtomicInteger()
                model.saveExport(discardUnwritten = { discards.incrementAndGet(); ExportDiscardResult.REMOVED }) { writes.incrementAndGet() }
                val failed = withTimeout(10_000) { model.state.first { !it.busy } }
                assertEquals("L'export preparato non è più disponibile. Prepara di nuovo l'export. Il documento vuoto appena creato è stato rimosso.", failed.message)
                assertEquals("{{reason}} {{cleanup}}", failed.messageNotice?.template)
                assertEquals(setOf("reason", "cleanup"), failed.messageNotice?.localizedValueKeys)
                assertNull(failed.export)
                assertEquals(1, discards.get())
                assertEquals(0, writes.get())
                assertTrue(fixture.requests.isEmpty())
            } finally { models.clear() }
        }
    }

    @Test fun missingCsvAndFailedDiscardExplainBothErrorsWithoutExposingProviderDetails(): Unit = runBlocking {
        Fixture().use { fixture ->
            val models = ViewModelStore()
            try {
                val model = TransferViewModel(fixture.api).also { models.put("restored-export", it) }
                val writes = AtomicInteger()
                model.saveExport(discardUnwritten = { throw IOException("Private provider URI must not appear") }) { writes.incrementAndGet() }
                val failed = withTimeout(10_000) { model.state.first { !it.busy } }
                assertEquals("L'export preparato non è più disponibile. Prepara di nuovo l'export. Impossibile rimuovere il documento vuoto appena creato. Controllalo prima di riprovare.", failed.message)
                assertFalse(failed.message.orEmpty().contains("Private provider"))
                assertNull(failed.export)
                assertEquals(0, writes.get())
                assertTrue(fixture.requests.isEmpty())
            } finally { models.clear() }
        }
    }

    @Test fun busyPickerCallbackDoesNotUnlockAnInProgressOperationOrReadBackend(): Unit = runBlocking {
        Fixture().use { fixture ->
            val models = ViewModelStore()
            val readGate = CompletableDeferred<String>()
            try {
                val model = TransferViewModel(fixture.api).also { models.put("export", it) }
                model.prepare({ readGate.await() }, ImportFormat.NORMALIZED, "EUR")
                assertTrue(model.state.value.busy)
                val writes = AtomicInteger()
                model.saveExport(discardUnwritten = { ExportDiscardResult.REMOVED }) { writes.incrementAndGet() }
                val rejected = withTimeout(10_000) { model.state.first { it.message != null } }
                assertEquals("Un'altra operazione è in corso. Il CSV non è stato salvato. Il documento vuoto appena creato è stato rimosso.", rejected.message)
                assertTrue(rejected.busy)
                assertEquals(0, writes.get())
                assertTrue(fixture.requests.isEmpty())
            } finally { models.clear(); readGate.cancel() }
        }
    }

    @Test fun sessionChangesAfterCompletedWriteNeverDiscardTheWrittenCsv(): Unit = runBlocking {
        Fixture().use { fixture ->
            val models = ViewModelStore()
            try {
                val model = TransferViewModel(fixture.api).also { models.put("export", it) }
                model.prepareExport()
                val ready = withTimeout(10_000) { model.state.first { !it.busy } }
                val csv = requireNotNull(ready.export).csv
                val reads = fixture.requests.size
                val discards = AtomicInteger()
                var output = ""
                model.saveExport(discardUnwritten = { discards.incrementAndGet(); ExportDiscardResult.REMOVED }) {
                    output = it
                    fixture.store.save(session(NEW_LOGIN))
                }
                val rejected = withTimeout(10_000) { model.state.first { !it.busy } }
                assertEquals(csv, output)
                assertEquals(0, discards.get())
                assertFalse(rejected.message.orEmpty().startsWith("CSV salvato:"))
                assertEquals(reads, fixture.requests.size)
                assertTrue(fixture.requests.all { it.method == "GET" })
            } finally { models.clear() }
        }
    }

    @Test fun staleCallbackWithoutPendingRequestCannotWriteEvenWhenCsvIsPrepared(): Unit = runBlocking {
        Fixture().use { fixture ->
            val models = ViewModelStore()
            try {
                val model = TransferViewModel(fixture.api).also { models.put("export", it) }
                model.prepareExport()
                val ready = withTimeout(10_000) { model.state.first { !it.busy } }
                assertNotNull(ready.export)
                val reads = fixture.requests.size
                val writes = AtomicInteger()
                model.saveExport(hasPendingRequest = false, discardUnwritten = { ExportDiscardResult.RETAINED }) { writes.incrementAndGet() }
                val failed = withTimeout(10_000) { model.state.first { !it.busy } }
                assertEquals("Risultato del selettore file non più valido. Prepara di nuovo l'export. Il documento non è stato rimosso: non è un nuovo file vuoto verificabile. Controllalo prima di riprovare.", failed.message)
                assertEquals(0, writes.get())
                assertEquals(reads, fixture.requests.size)
                assertEquals(ready.export, failed.export)
            } finally { models.clear() }
        }
    }

    private class Fixture : AutoCloseable {
        val store = MemorySessionStore(session(OLD_LOGIN))
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val denied = AtomicInteger()
        @Volatile var reloginDuringTransactionRead = false
        @Volatile var includeConnection = false
        private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        private val server = MockWebServer()
        val api: SupabaseApi
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    val url = request.requestUrl!!
                    val table = url.pathSegments.last()
                    val scope = when (table) {
                        "couple_connections" -> url.queryParameter("or") == "(user_a.eq.$OWNER,user_b.eq.$OWNER)"
                        "couple_connection_requests" -> url.queryParameter("or") == "(sender_id.eq.$OWNER,receiver_id.eq.$OWNER)"
                        "couple_budgets", "shared_expenses" -> includeConnection && url.queryParameter("connection_id") == "in.($CONNECTION)"
                        else -> url.queryParameter("user_id") == "eq.$OWNER"
                    }
                    if (request.method != "GET" || table !in TransferRepository.USER_TABLES || !scope ||
                        request.getHeader("Authorization") != "Bearer ${session(OLD_LOGIN).accessToken}") {
                        denied.incrementAndGet()
                        return response(403, "{}")
                    }
                    if (table == "transactions" && reloginDuringTransactionRead) store.save(session(NEW_LOGIN))
                    val row = if (url.queryParameter("id") != null) null else when (table) {
                        "transactions" -> JSONObject().put("id", "export-row").put("user_id", OWNER)
                            .put("description", "Owned export row").put("amount", 12.5)
                        "couple_connections" -> if (includeConnection) JSONObject().put("id", CONNECTION)
                            .put("user_a", OWNER).put("user_b", "synthetic-partner") else null
                        "couple_budgets", "shared_expenses" -> JSONObject().put("id", "$table-row").put("connection_id", CONNECTION)
                        else -> null
                    }
                    return response(200, if (row == null) "[]" else JSONArray().put(row).toString())
                }
            }
            server.start()
            val origin = server.url("/").toString()
            api = SupabaseApi(BackendConfig(origin, "public-fixture-key", origin), store, client)
        }
        override fun close() {
            api.close(); server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    private companion object {
        const val OWNER = "38000000-0000-4000-8000-000000000001"
        const val CONNECTION = "38000000-0000-4000-8000-000000000002"
        const val OLD_LOGIN = "38000000-0000-4000-8000-000000000003"
        const val NEW_LOGIN = "38000000-0000-4000-8000-000000000004"
        fun session(login: String): Session {
            val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                JSONObject().put("sub", OWNER).put("session_id", login).toString().toByteArray(Charsets.UTF_8))
            return Session("e30.$payload.fixture", "fixture-refresh-$login", Long.MAX_VALUE, OWNER, "export@example.test")
        }
        fun response(code: Int, body: String) = MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json").setBody(body)
    }
}
