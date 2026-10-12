package cc.savely.android.transfer

import androidx.lifecycle.ViewModelStore
import cc.savely.android.data.*
import cc.savely.android.ui.AppLanguage
import cc.savely.android.ui.TranslationCatalog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelParityTest {
    @Before fun main() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun restore() { Dispatchers.resetMain() }

    @Test fun interestDraftWritesNothingBeforeConfirmationThenCreatesIncomeCategoryAndLiteralDescription() = runBlocking {
        fixture { f ->
            f.model.prepare({ "Data,Descrizione,Importo\n2026-10-12,SPESE ACCREDITO A FRONTE DEL SALDO MENSILE,1.25" }, ImportFormat.BANK, "EUR")
            val preview = idle(f)
            assertTrue(f.writes.isEmpty())
            assertEquals("Dividendi", preview.newCategories.single().name)
            assertEquals("Interessi", preview.preview.single().description)
            f.model.confirmImport(); idle(f)
            assertEquals(listOf("categories", "transactions"), f.writes.map { it.first })
            val category = f.writes[0].second
            val transaction = f.writes[1].second
            assertEquals("income", category.getString("type"))
            assertEquals("Interessi", transaction.getString("description"))
            assertEquals(category.getString("id"), transaction.getString("category_id"))
            assertEquals("1.25", transaction.get("amount").toString())
            f.assertScope()
        }
    }

    @Test fun newCategoryKeywordAndNoteAreStagedThenPersistedInOwnedSharedMapping() = runBlocking {
        fixture { f ->
            f.prepareBank()
            f.model.newCategory(2, "Spesa nuova", "🛒", "#123456")
            f.model.description(2, "Shop")
            f.model.note(2, "Famiglia")
            f.model.rememberMapping(2, true)
            assertTrue(f.writes.isEmpty())
            val id = f.model.state.value.newCategories.single().id
            f.model.confirmImport(); idle(f)
            assertEquals(listOf("categories", "category_mappings", "transactions"), f.writes.map { it.first })
            val mapping = f.writes[1].second
            assertEquals("shared", mapping.getString("source"))
            assertEquals("shop", mapping.getString("description"))
            assertEquals(id, mapping.getString("category_id"))
            assertEquals("Shop - Famiglia", f.writes[2].second.getString("description"))
            f.assertScope()
        }
    }

    @Test fun duplicateIsinRowsProduceOneQuantityOnePositionAndSubsequentFileSkipsExistingSymbol() = runBlocking {
        fixture { f ->
            val input = "Data,Tipo,Descrizione,Importo\n2026-10-12,Buy Trade IE00BK5BQT80 All-World,Acquisto,-123.45\n2026-10-12,Buy Trade IE00BK5BQT80 All-World,Acquisto,-123.45"
            f.model.prepare({ input }, ImportFormat.BANK, "EUR")
            val preview = idle(f)
            assertEquals(listOf(ImportStatus.READY, ImportStatus.DUPLICATE), preview.preview.map { it.status })
            val draft = preview.isinDrafts.getValue("IE00BK5BQT80")
            f.model.isinDraft(draft.copy(symbol = "VWCE"))
            f.model.confirmImport(); idle(f)
            assertEquals(listOf("isin_mappings", "portfolio_assets"), f.writes.map { it.first })
            assertEquals(1, f.writes[1].second.getInt("quantity"))
            assertEquals("123.45", f.writes[1].second.get("purchase_price").toString())
            assertEquals("2026-10-12", f.writes[1].second.getString("purchase_date"))
            f.model.prepare({ input }, ImportFormat.BANK, "EUR")
            assertTrue(idle(f).preview.all { it.status == ImportStatus.DUPLICATE && !it.selected })
            assertEquals(2, f.writes.size)
            f.assertScope()
        }
    }

    @Test fun ambiguousMappingCommitRequiresReadAndExplicitResubmitWithoutRewritingConfirmedPrefix() = runBlocking {
        fixture { f ->
            f.prepareBank()
            f.model.newCategory(2, "Spesa", "🛒", "#123456")
            f.model.description(2, "Shop"); f.model.rememberMapping(2, true)
            val rowId = f.model.state.value.preview.single().insertId
            f.ambiguousTable = "category_mappings"
            f.model.confirmImport()
            assertEquals(ImportStatus.UNCERTAIN, idle(f).preview.single().status)
            assertEquals(listOf("categories", "category_mappings"), f.writes.map { it.first })
            f.model.confirmImport(); idle(f)
            assertEquals(2, f.writes.size)
            f.model.recheckDuplicates()
            val checked = idle(f)
            assertEquals(ImportStatus.FAILED, checked.preview.single().status)
            assertFalse(checked.preview.single().selected)
            f.model.select(2, true); f.model.confirmImport()
            assertEquals(ImportStatus.IMPORTED, idle(f).preview.single().status)
            assertEquals(listOf("categories", "category_mappings", "transactions"), f.writes.map { it.first })
            assertEquals(rowId, f.writes.last().second.getString("id"))
            f.assertScope()
        }
    }

    @Test fun committedTransaction503ReconcilesExactIdAndPayloadWithoutSecondInsert() = runBlocking {
        fixture { f ->
            f.addCategory()
            f.prepareBank()
            f.model.category(2, Fixture.CATEGORY)
            f.ambiguousTable = "transactions"
            f.model.confirmImport()
            assertEquals(ImportStatus.UNCERTAIN, idle(f).preview.single().status)
            val id = f.model.state.value.preview.single().insertId
            assertEquals(id, f.writes.single().second.getString("id"))
            f.model.recheckDuplicates()
            assertEquals(ImportStatus.IMPORTED, idle(f).preview.single().status)
            assertEquals(1, f.writes.size)
            f.assertScope()
        }
    }

    @Test fun ownerChangeAfterCommittedHttpSuccessRemainsUncertainAndNeverProvesAbsence() = runBlocking {
        fixture { f ->
            f.addCategory(); f.prepareBank(); f.model.category(2, Fixture.CATEGORY)
            f.afterCommitOwner = "other"
            f.model.confirmImport()
            val state = idle(f)
            assertEquals(1, f.writes.size)
            assertEquals(ImportStatus.UNCERTAIN, state.preview.single().status)
            assertEquals(TransferMutationStatus.UNCERTAIN, state.mutations.single().status)
            assertEquals(state.preview.single().insertId, f.rows["transactions"]!!.single().getString("id"))
            f.model.confirmImport(); idle(f)
            assertEquals(1, f.writes.size)
            f.assertScope()
        }
    }

    @Test fun accountSwitchAfterPreviewDoesNotReadOrWriteAnotherOwner() = runBlocking {
        fixture { f ->
            f.addCategory(); f.prepareBank(); f.model.category(2, Fixture.CATEGORY)
            val reads = f.server.requestCount
            f.store.save(f.session.copy(userId = "other"))
            f.model.confirmImport()
            assertTrue(idle(f).message!!.contains("Account cambiato"))
            assertEquals(reads, f.server.requestCount)
            assertTrue(f.writes.isEmpty())
            f.assertScope()
        }
    }

    @Test fun logoutAndNewLoginOfSameUidCannotReuseOldPreviewOrItsRevision() = runBlocking {
        fixture { f ->
            f.addCategory(); f.prepareBank(); f.model.category(2, Fixture.CATEGORY)
            val oldRevision = requireNotNull(f.model.state.value.context?.ownerRevision)
            val reads = f.server.requestCount
            f.store.clear()
            assertNull(f.api.session())
            f.store.save(f.session.copy(accessToken = "replacement-access", refreshToken = "replacement-refresh"))
            val newSession = requireNotNull(f.api.session())
            assertNotEquals(oldRevision, f.api.sessionOwnerRevision(newSession))
            f.model.confirmImport()
            assertTrue(idle(f).message!!.contains("Account cambiato"))
            assertEquals(reads, f.server.requestCount)
            assertTrue(f.writes.isEmpty())
            assertEquals(oldRevision, f.model.state.value.context?.ownerRevision)
            f.assertScope()
        }
    }

    @Test fun existingSubstringMappingRewritesBeforeDuplicatePreviewAndOnlyMatchingSourceIsUsedForRevolut() = runBlocking {
        fixture { f ->
            f.addCategory()
            f.rows["category_mappings"] = mutableListOf(JSONObject().put("id", "mapping-1").put("user_id", Fixture.OWNER)
                .put("description", "shop").put("category_id", Fixture.CATEGORY).put("source", "shared"))
            f.rows["transactions"] = mutableListOf(JSONObject().put("id", "transaction-1").put("user_id", Fixture.OWNER)
                .put("description", "shop").put("date", "2026-10-12").put("amount", 12.50).put("currency", "EUR").put("type", "expense"))
            f.prepareBank()
            assertEquals(ImportStatus.DUPLICATE, f.model.state.value.preview.single().status)
            assertEquals("shop", f.model.state.value.preview.single().description)
            assertTrue(f.writes.isEmpty())
            f.assertScope()
        }
    }

    @Test fun explicitMappingEditCanReturnToAnEarlierValueWithoutOldConfirmedLedgerSuppressingWrite() = runBlocking {
        fixture { f ->
            val id = "mapping-owned"
            f.rows["isin_mappings"] = mutableListOf(JSONObject().put("id", id).put("user_id", Fixture.OWNER)
                .put("isin", "IE00BK5BQT80").put("symbol", "FIRST").put("name", "Fund").put("asset_type", "etf")
                .put("created_at", "preserved"))
            f.model.loadMappings(); idle(f)
            for (symbol in listOf("SECOND", "FIRST", "SECOND")) {
                val mapping = f.model.state.value.context!!.isinMappings.single()
                f.model.saveIsinMapping(mapping.copy(symbol = symbol))
                val state = idle(f)
                assertEquals("Requested=$symbol, writes=${f.writes.map { it.second.optString("symbol") }}, " +
                    "ledger=${state.mutations.map { it.key to it.status }}, notice=${state.message}",
                    symbol, state.context!!.isinMappings.single().symbol)
            }
            assertEquals(listOf("SECOND", "FIRST", "SECOND"), f.writes.map { it.second.getString("symbol") })
            assertEquals(3, f.model.state.value.mutations.count { it.status == TransferMutationStatus.CONFIRMED })
            assertEquals("preserved", f.rows["isin_mappings"]!!.single().getString("created_at"))
            assertTrue(f.writes.all { it.second.getString("id") == id })
            f.assertScope()
        }
    }

    @Test fun invalidNewFileClearsPreviousPreviewAndCannotCommitOldRows() = runBlocking {
        fixture { f ->
            f.addCategory(); f.prepareBank(); f.model.category(2, Fixture.CATEGORY)
            assertEquals(1, f.model.state.value.preview.size)
            f.model.prepare({ "description,amount\nwrong,12" }, ImportFormat.NORMALIZED, "EUR")
            assertTrue(idle(f).preview.isEmpty())
            f.model.confirmImport()
            assertTrue(f.writes.isEmpty())
            f.assertScope()
        }
    }

    @Test fun validationNoticesPreserveLegacyItalianAndRenderBothTypesAndRateErrorsInEnglishBeforeHttp() = runBlocking {
        val catalog = englishCatalog()
        val cases = listOf(
            Triple("expense", "Riga 2: scegli una categoria expense valida.", "Row 2: choose a valid category (Expense)."),
            Triple("income", "Riga 2: scegli una categoria income valida.", "Row 2: choose a valid category (Income)."),
            Triple("invalid-rate", "Riga 2: cambio EUR non valido.", "Row 2: invalid EUR exchange rate."),
            Triple("eur-rate", "Riga 2: il cambio EUR deve essere 1.", "Row 2: the EUR exchange rate must be 1."),
        )
        for ((kind, italian, english) in cases) fixture { f ->
            if (kind.endsWith("rate")) f.addCategory()
            if (kind == "income") {
                f.model.prepare({ "Data,Descrizione,Importo\n2026-10-12,Shop purchase,12.50" }, ImportFormat.BANK, "EUR")
                idle(f)
            } else f.prepareBank()
            if (kind.endsWith("rate")) {
                f.model.category(2, Fixture.CATEGORY)
                f.model.rate(2, if (kind == "invalid-rate") "0" else "2")
            }
            val requestsBefore = f.server.requestCount
            val description = f.model.state.value.preview.single().description
            f.model.confirmImport()
            val state = f.model.state.value
            assertEquals(italian, state.message)
            val notice = requireNotNull(state.messageNotice)
            assertEquals(2, notice.values.single { it.first == "row" }.second)
            val values = notice.values.map { (key, value) ->
                key to if (key in notice.localizedValueKeys) catalog.translate(AppLanguage.ENGLISH, value.toString()) else value
            }
            assertEquals(english, catalog.translate(AppLanguage.ENGLISH, notice.template, *values.toTypedArray()))
            assertEquals(description, state.preview.single().description)
            assertTrue(state.preview.single().selected)
            assertTrue(state.mutations.isEmpty())
            assertTrue(f.writes.isEmpty())
            assertEquals(requestsBefore, f.server.requestCount)
            f.assertScope()
        }
    }

    @Test fun categoryRemovedAfterPreviewKeepsTypedRowNoticeWithoutWritingOrLosingUserDescription() = runBlocking {
        fixture { f ->
            f.addCategory(); f.prepareBank(); f.model.category(2, Fixture.CATEGORY)
            f.model.description(2, "Uscita {{row}} · nome utente")
            f.rows["categories"] = mutableListOf()
            f.model.confirmImport()
            val row = idle(f).preview.single()
            assertEquals(ImportStatus.FAILED, row.status)
            assertEquals("Riga 2: scegli una categoria expense valida.", row.message)
            val notice = requireNotNull(row.messageNotice)
            assertEquals(setOf("type"), notice.localizedValueKeys)
            assertEquals("Uscita", notice.values.single { it.first == "type" }.second)
            assertEquals("Uscita {{row}} · nome utente", row.description)
            assertTrue(f.writes.isEmpty())
            f.assertScope()
        }
    }

    private fun englishCatalog(): TranslationCatalog {
        val path = sequenceOf(File("src/main/res/raw/ui_translations_en.json"), File("app/src/main/res/raw/ui_translations_en.json"))
            .first { it.isFile }
        val json = JSONObject(path.readText(Charsets.UTF_8))
        return TranslationCatalog(json.keys().asSequence().associateWith { json.getString(it) })
    }

    private suspend fun idle(f: Fixture) = withTimeout(10_000) { f.model.state.first { !it.busy } }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) { Fixture().use { block(it) } }

    private class Fixture : AutoCloseable {
        val rows = mutableMapOf<String, MutableList<JSONObject>>()
        val writes = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val server = MockWebServer()
        val session = Session("fixture-access", "fixture-refresh", System.currentTimeMillis() / 1000 + 3600, OWNER, "fixture@example.test")
        val store = MemorySessionStore(session)
        val api: SupabaseApi
        val model: TransferViewModel
        private val viewModels = ViewModelStore()
        private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        @Volatile var ambiguousTable: String? = null
        @Volatile var afterCommitOwner: String? = null
        @Volatile var denied = 0
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    val table = url.pathSegments.last()
                    if (request.getHeader("Authorization") != "Bearer fixture-access") return deny()
                    if (request.method == "GET") {
                        if (url.queryParameter("user_id") != "eq.$OWNER" || url.queryParameter("order") != "id.asc") return deny()
                        val cursor = url.queryParameter("id")?.removePrefix("gt.")
                        val page = synchronized(rows) { rows[table].orEmpty().filter { cursor == null || it.getString("id") > cursor }.sortedBy { it.getString("id") } }
                        return response(200, JSONArray(page).toString())
                    }
                    if (request.method == "PATCH" && table == "isin_mappings") {
                        if (url.queryParameter("user_id") != "eq.$OWNER") return deny()
                        val id = url.queryParameter("id")?.removePrefix("eq.") ?: return deny()
                        val existing = rows[table].orEmpty().singleOrNull { it.getString("id") == id } ?: return deny()
                        if (existing.getString("user_id") != OWNER) return deny()
                        for (field in listOf("isin", "symbol", "name", "asset_type")) {
                            if (url.queryParameter(field) != "eq.\"${existing.getString(field)}\"") return response(200, "[]")
                        }
                        val body = JSONObject(request.body.readUtf8())
                        if (body.optString("user_id") != OWNER || body.optString("id") != id) return deny()
                        writes += table to JSONObject(body.toString())
                        body.keys().forEach { existing.put(it, body.get(it)) }
                        return response(200, JSONArray().put(existing).toString())
                    }
                    if (request.method != "POST" || table !in setOf("transactions", "categories", "category_mappings", "isin_mappings", "portfolio_assets")) return deny()
                    val body = JSONObject(request.body.readUtf8())
                    if (body.optString("user_id") != OWNER) return deny()
                    writes += table to body
                    synchronized(rows) { rows.getOrPut(table) { mutableListOf() } += JSONObject(body.toString()) }
                    afterCommitOwner?.let { store.save(session.copy(userId = it)); afterCommitOwner = null }
                    if (ambiguousTable == table) { ambiguousTable = null; return response(503, "{}") }
                    return response(201, JSONArray().put(body).toString())
                }
            }
            server.start()
            val origin = server.url("/").toString()
            api = SupabaseApi(BackendConfig(origin, "fixture-public", origin), store, client)
            model = TransferViewModel(api).also { viewModels.put("fixture", it) }
        }
        suspend fun prepareBank() {
            model.prepare({ "Data,Descrizione,Importo\n2026-10-12,Shop purchase,-12.50" }, ImportFormat.BANK, "EUR")
            withTimeout(10_000) { model.state.first { !it.busy } }
        }
        fun addCategory() { rows["categories"] = mutableListOf(JSONObject().put("id", CATEGORY).put("user_id", OWNER).put("name", "Spese").put("type", "expense")) }
        fun assertScope() { assertEquals(0, denied); assertTrue(writes.all { it.second.getString("user_id") == OWNER }) }
        override fun close() { viewModels.clear(); api.close(); server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        private fun deny(): MockResponse { denied++; return response(403, "{}") }
        private fun response(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
        companion object { const val OWNER = "owner"; const val CATEGORY = "12000000-0000-4000-8000-000000000001" }
    }
}
