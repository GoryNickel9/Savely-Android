package cc.savely.android.transfer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cc.savely.android.data.*
import cc.savely.android.ui.SavelyTheme
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.Timeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** TLS localhost + actual owner ViewModel and real pointer gestures; no deployed backend/picker claim. */
class TransferParityScreenTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(90)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var fixture: Fixture
    private lateinit var model: TransferViewModel
    private lateinit var focus: FocusManager
    private var keyboard: SoftwareKeyboardController? = null

    @Before fun setup() {
        fixture = Fixture()
        compose.runOnUiThread {
            model = TransferViewModel(fixture.api)
            compose.activity.viewModelStore.put("transfer-${System.identityHashCode(fixture.api)}-${Fixture.OWNER}", model)
        }
        compose.setContent {
            val currentFocus = LocalFocusManager.current
            val currentKeyboard = LocalSoftwareKeyboardController.current
            SideEffect { focus = currentFocus; keyboard = currentKeyboard }
            SavelyTheme("light") { TransferScreen(fixture.api, Modifier.fillMaxSize()) }
        }
        await { compose.onAllNodesWithText("Importa ed esporta").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun cleanup() { fixture.close() }

    @Test fun unknownIsinRequiresSymbolThenTouchConfirmationPersistsMappingAndQuantityOnePosition() {
        prepare("Data,Tipo,Descrizione,Importo\n2026-10-12,Buy Trade IE00BK5BQT80 Vanguard All-World,Acquisto,-123.45")
        assertTrue(fixture.writes.isEmpty())
        list().performScrollToIndex(4)
        touch(hasText("Importa 1 righe selezionate") and hasClickAction())
        touch(hasText("Conferma e importa") and hasClickAction())
        await { !model.state.value.busy && model.state.value.message?.contains("Simbolo richiesto") == true }
        assertTrue(fixture.writes.isEmpty())
        list().performScrollToIndex(5)
        replace("Simbolo", "VWCE")
        list().performScrollToIndex(4)
        touch(hasText("Importa 1 righe selezionate") and hasClickAction())
        touch(hasText("Conferma e importa") and hasClickAction())
        await { !model.state.value.busy && model.state.value.preview.single().status == ImportStatus.IMPORTED }
        assertEquals(listOf("isin_mappings", "portfolio_assets"), fixture.writes.map { it.first })
        val mapping = fixture.writes[0].second
        val asset = fixture.writes[1].second
        assertEquals("IE00BK5BQT80", mapping.getString("isin"))
        assertEquals("VWCE", mapping.getString("symbol"))
        assertEquals("etf", asset.getString("type"))
        assertEquals(1, asset.getInt("quantity"))
        assertEquals("123.45", asset.get("purchase_price").toString())
        assertEquals("2026-10-12", asset.getString("purchase_date"))
        assertEquals("Importato da banca - ISIN: IE00BK5BQT80", asset.getString("notes"))
        assertEquals(model.state.value.preview.single().insertId, asset.getString("id"))
        list().performScrollToIndex(1)
        touch(hasText("Gestisci Mapping ISIN") and hasClickAction())
        await { !model.state.value.busy && model.state.value.context?.isinMappings?.size == 1 }
        val readBack = runBlocking { fixture.api.select("portfolio_assets", "select=*&order=id.asc&limit=500&user_id=eq.${Fixture.OWNER}") }
        assertEquals(1, readBack.length())
        assertEquals(asset.toString(), readBack.getJSONObject(0).toString())
        assertEquals(2, fixture.writes.size)
        fixture.assertScope()
    }

    @Test fun stagedCategoryKeywordAndNoteSurviveAmbiguousMappingCommitAndExplicitRetryWithoutDuplicatePrefix() {
        prepare("Data,Descrizione,Importo\n2026-10-12,Shop purchase,-12.50")
        list().performScrollToIndex(5)
        touch(hasText("Nuova categoria") and hasClickAction())
        replace("Nome categoria", "Spesa famiglia")
        touch(hasText("Prepara categoria") and hasClickAction())
        await { model.state.value.newCategories.size == 1 }
        list().performScrollToIndex(5)
        replace("Descrizione / keyword", "Shop")
        replace("Nota importazione", "Famiglia")
        // Row checkbox is a real toggle; draft/category creation has performed no HTTP write.
        scrollVisible(hasText("Ricorda questa categoria per le importazioni future"))
        val rememberCheckbox = hasTestTag("import-remember-category-${model.state.value.preview.single().transaction.row}") and isToggleable()
        touch(rememberCheckbox)
        assertTrue(fixture.writes.isEmpty())
        fixture.ambiguousTable = "category_mappings"
        list().performScrollToIndex(4)
        touch(hasText("Importa 1 transazioni selezionate") and hasClickAction())
        touch(hasText("Conferma e importa") and hasClickAction())
        await { !model.state.value.busy && model.state.value.preview.single().status == ImportStatus.UNCERTAIN }
        assertEquals(listOf("categories", "category_mappings"), fixture.writes.map { it.first })
        list().performScrollToIndex(4)
        touch(hasText("Verifica gli esiti incerti") and hasClickAction())
        await { !model.state.value.busy && model.state.value.preview.single().status == ImportStatus.FAILED }
        assertEquals(2, fixture.writes.size)
        list().performScrollToIndex(4)
        touch(hasText("Seleziona tutte") and hasClickAction())
        touch(hasText("Importa 1 transazioni selezionate") and hasClickAction())
        touch(hasText("Conferma e importa") and hasClickAction())
        await { !model.state.value.busy && model.state.value.preview.single().status == ImportStatus.IMPORTED }
        assertEquals(listOf("categories", "category_mappings", "transactions"), fixture.writes.map { it.first })
        assertEquals("shared", fixture.writes[1].second.getString("source"))
        assertEquals("shop", fixture.writes[1].second.getString("description"))
        assertEquals("Shop - Famiglia", fixture.writes[2].second.getString("description"))
        assertEquals(fixture.writes[0].second.getString("id"), fixture.writes[2].second.getString("category_id"))
        fixture.assertScope()
    }

    @Test fun mappingManagementEditsAndDeletesOnlyOwnedIdAfterExplicitDialogs() {
        val id = "13000000-0000-4000-8000-000000000010"
        val original = JSONObject().put("id", id).put("user_id", Fixture.OWNER).put("isin", "IE00BK5BQT80")
            .put("symbol", "OLD").put("name", "All-World").put("asset_type", "etf").put("created_at", "preserved-server-metadata")
        fixture.rows["isin_mappings"] = CopyOnWriteArrayList(listOf(original, fixture.foreign))
        list().performScrollToIndex(1)
        touch(hasText("Gestisci Mapping ISIN") and hasClickAction())
        await { !model.state.value.busy && model.state.value.context?.isinMappings?.size == 1 }
        touch(hasText("Modifica") and hasClickAction())
        replace("Simbolo", "VWCE")
        touch(hasText("Salva mapping") and hasClickAction())
        await { !model.state.value.busy && fixture.patches.get() == 1 && compose.onAllNodesWithText("Salva mapping").fetchSemanticsNodes().isEmpty() }
        val stored = fixture.rows["isin_mappings"]!!.single { it.getString("user_id") == Fixture.OWNER }
        assertEquals(id, stored.getString("id"))
        assertEquals("IE00BK5BQT80", stored.getString("isin"))
        assertEquals("VWCE", stored.getString("symbol"))
        assertEquals("preserved-server-metadata", stored.getString("created_at"))
        touch(hasText("Elimina") and hasClickAction())
        touch(hasText("Elimina") and hasAnyAncestor(isDialog() and hasAnyDescendant(hasText("Eliminare questo mapping?"))) and hasClickAction())
        await { !model.state.value.busy && fixture.deletes.get() == 1 }
        assertTrue(fixture.rows["isin_mappings"]!!.none { it.getString("user_id") == Fixture.OWNER })
        assertTrue(fixture.writes.isEmpty())
        fixture.assertScope()
    }

    private fun prepare(input: String) {
        list().performScrollToIndex(1)
        touch(hasText("BBVA / Trade Republic CSV") and hasClickAction())
        compose.runOnUiThread { model.prepare({ input }, ImportFormat.BANK, "EUR") }
        await { !model.state.value.busy && model.state.value.preview.isNotEmpty() }
    }
    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun clearInput() = compose.runOnIdle { focus.clearFocus(force = true); keyboard?.hide() }
    private fun replace(label: String, text: String) { clearInput(); scrollVisible(hasText(label)).performTextReplacement(text) }
    private fun scrollVisible(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        var swipes = 0
        await {
            val nodes = compose.onAllNodes(matcher).fetchSemanticsNodes()
            assertTrue("Unique scroll target: $matcher", nodes.size <= 1)
            val node = nodes.singleOrNull() ?: return@await false
            if (compose.onNode(matcher).isDisplayed()) true else {
                val ancestor = generateSequence(node.parent) { it.parent }.firstOrNull { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                    ?: throw AssertionError("No visible scroll ancestor for $matcher")
                assertTrue("Bounded scroll target $matcher", swipes++ < 8)
                compose.onNode(SemanticsMatcher("Scroll ancestor ${ancestor.id}") { it.id == ancestor.id }).performTouchInput {
                    if (node.positionInRoot.y > ancestor.boundsInRoot.center.y) swipeUp() else swipeDown()
                }
                false
            }
        }
        return compose.onNode(matcher).assertIsDisplayed()
    }
    private fun touch(matcher: SemanticsMatcher) {
        clearInput()
        scrollVisible(matcher)
        var previous: Rect? = null
        var stable = 0
        await {
            compose.mainClock.advanceTimeByFrame()
            val node = compose.onNode(matcher).fetchSemanticsNode()
            val bounds = node.boundsInRoot
            val viewports = generateSequence(node.parent) { it.parent }.filter { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }.map { it.boundsInRoot }.toList()
            val visible = !node.config.contains(SemanticsProperties.Disabled) && bounds.width > 0 && bounds.height > 0 &&
                viewports.all { it.contains(bounds.center) } && compose.onNode(matcher).isDisplayed()
            stable = if (visible && bounds == previous) stable + 1 else 0
            previous = bounds
            stable >= 2
        }
        compose.onNode(matcher).assertIsEnabled().assertIsDisplayed().performTouchInput { click() }
    }
    private fun await(predicate: () -> Boolean) = compose.waitUntil(15_000, predicate)

    private class Fixture : AutoCloseable {
        val rows = ConcurrentHashMap<String, CopyOnWriteArrayList<JSONObject>>()
        val writes = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val patches = AtomicInteger()
        val deletes = AtomicInteger()
        val denied = AtomicInteger()
        val foreign = JSONObject().put("id", "13000000-0000-4000-8000-000000000099").put("user_id", "foreign")
            .put("isin", "US0000000000").put("symbol", "PRIVATE").put("name", "Foreign private mapping").put("asset_type", "stock")
        private val foreignSnapshot = foreign.toString()
        @Volatile var ambiguousTable: String? = null
        private val server = MockWebServer()
        private val client: OkHttpClient
        val api: SupabaseApi
        init {
            rows["isin_mappings"] = CopyOnWriteArrayList(listOf(foreign))
            val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    val table = url.pathSegments.last()
                    if (request.getHeader("Authorization") != "Bearer parity-fixture-access" || request.getHeader("apikey") != "parity-fixture-public" || table !in TransferRepository.USER_TABLES) return reject()
                    if (request.method == "GET") {
                        if (url.queryParameter("user_id") != "eq.$OWNER" || url.queryParameter("order") != "id.asc" || url.queryParameter("limit") != "500") return reject()
                        val cursor = url.queryParameter("id")?.removePrefix("gt.")
                        return response(200, JSONArray(rows[table].orEmpty().filter { it.optString("user_id") == OWNER && (cursor == null || it.getString("id") > cursor) }.sortedBy { it.getString("id") }).toString())
                    }
                    if (request.method == "POST" && table in setOf("categories", "category_mappings", "isin_mappings", "transactions", "portfolio_assets")) {
                        val body = JSONObject(request.body.readUtf8())
                        if (body.optString("user_id") != OWNER) return reject()
                        if (rows[table].orEmpty().any { it.getString("id") == body.getString("id") }) return response(409, "{}")
                        writes += table to body
                        rows.getOrPut(table) { CopyOnWriteArrayList() } += JSONObject(body.toString())
                        if (ambiguousTable == table) { ambiguousTable = null; return response(503, "{}") }
                        return response(201, JSONArray().put(body).toString())
                    }
                    if (table != "isin_mappings" || url.queryParameter("user_id") != "eq.$OWNER") return reject()
                    val id = url.queryParameter("id")?.removePrefix("eq.") ?: return reject()
                    val row = rows[table].orEmpty().singleOrNull { it.getString("id") == id } ?: return reject()
                    if (row.optString("user_id") != OWNER) return reject()
                    if (request.method == "PATCH") {
                        val body = JSONObject(request.body.readUtf8())
                        if (body.optString("user_id") != OWNER || body.optString("id") != id) return reject()
                        body.keys().forEach { row.put(it, body.get(it)) }; patches.incrementAndGet()
                    } else if (request.method == "DELETE") { rows[table]!!.remove(row); deletes.incrementAndGet() }
                    else return reject()
                    return response(200, JSONArray().put(row).toString())
                }
            }
            server.start()
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
            client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).retryOnConnectionFailure(false).build()
            val origin = server.url("/").toString()
            api = SupabaseApi(BackendConfig(origin, "parity-fixture-public", origin), MemorySessionStore(Session("parity-fixture-access", "parity-fixture-refresh",
                System.currentTimeMillis() / 1000 + 3600, OWNER, "parity@example.test")), client)
        }
        fun assertScope() {
            assertEquals(0, denied.get())
            assertEquals(foreignSnapshot, rows["isin_mappings"]!!.single { it.getString("user_id") != OWNER }.toString())
            assertTrue(writes.all { it.second.getString("user_id") == OWNER })
        }
        override fun close() { api.close(); server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        private fun reject(): MockResponse { denied.incrementAndGet(); return response(403, "{}") }
        private fun response(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
        companion object { const val OWNER = "13000000-0000-4000-8000-000000000001" }
    }
}
