package cc.savely.android.modules

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
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.ui.SavelyTheme
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.Timeout
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Native collection/dialog tests against a local TLS server and synthetic account only.
 * Saved-state restoration rebuilds the composition; it does not kill or recreate the Activity.
 */
@RunWith(AndroidJUnit4::class)
class CollectionScreenTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(90)
    // ScrollBy launches Compose-managed animation work. A separately queued effect dispatcher
    // prevents performScrollTo's internal loop from observing progress before it returns.
    @get:Rule(order = 1) val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private lateinit var focus: FocusManager
    private var keyboard: SoftwareKeyboardController? = null
    private val rows = ConcurrentHashMap<String, JSONObject>()
    private val posts = CopyOnWriteArrayList<JSONObject>()
    private val idReads = CopyOnWriteArrayList<String>()
    private val listCategories = CopyOnWriteArrayList<String>()
    private val denied = AtomicInteger()
    private val duplicates = AtomicInteger()
    private val ambiguousSecondPost = AtomicBoolean(false)
    private val failFirstIdRead = AtomicBoolean(false)
    private var touchDiagnostic = "No pointer target sampled"
    private val restoration by lazy { StateRestorationTester(compose) }

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Savely collection UI fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            dispatcher = fixture()
            start()
        }
        val base = server.url("/").toString()
        val session = Session("collection-device-access", "collection-device-refresh",
            System.currentTimeMillis() / 1000 + 3600, OWNER, "collection-ui@example.test")
        api = SupabaseApi(BackendConfig(base, "public-test-key", base), MemorySessionStore(session), client)
    }

    @After fun cleanup() {
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun mangaInvalidRangeCannotWriteAndValidRangeExplainsSeparateUnitPrices() {
        mount("libreria")
        openTab("Manga", "manga")
        list().performScrollToIndex(0)
        touch(button("Aggiungi"))
        replace(RANGE, "1-101")
        compose.onNodeWithText("Usa un intervallo crescente di massimo 100 volumi").assertIsDisplayed()
        compose.onNodeWithText("Salva").assertIsNotEnabled()
        assertEquals(0, posts.size)
        replace(RANGE, "1-3")
        compose.onNodeWithText("3 volumi separati, quantità 1 ciascuno. Prezzo di acquisto e rivendita sono per volume.")
            .assertIsDisplayed()
        compose.onNodeWithText("Quantità").assertDoesNotExist()
        compose.onNodeWithText("Aggiungi 3 volumi").assertIsEnabled()
        touch(button("Annulla"))
        assertEquals(0, posts.size)
        assertEquals(0, denied.get())
    }

    @Test fun mangaAmbiguousCommitRestoresPendingPlanAndExplicitRetryDoesNotDuplicateVolumes() {
        ambiguousSecondPost.set(true)
        failFirstIdRead.set(true)
        mount("libreria")
        openTab("Manga", "manga")
        list().performScrollToIndex(0)
        touch(button("Aggiungi"))
        replace(RANGE, "1-3")
        replace("Titolo", "Serie sintetica")
        replace("Autore (facoltativo)", "Autore sintetico")
        replace("Editore (facoltativo)", "Editore sintetico")
        replace("Anno pubblicazione (facoltativo)", "2024")
        replace("Prezzo acquisto unitario (€)", "6,25")
        replace("Valore rivendita unitario (€)", "8,50")
        replace("Note (facoltativo)", "Nota della serie")
        touch(button("Aggiungi 3 volumi"))
        await("Manga partial failure is visible") {
            compose.onAllNodesWithText("1/3 volumi confermati · Serie sintetica").fetchSemanticsNodes().isNotEmpty() &&
                enabled(button("Riprendi serie")) && idReads.size == 1
        }
        assertEquals("No third POST while verification is unavailable", 2, posts.size)
        assertEquals("Both committed rows remain even though only one is confirmed", 2, rows.size)
        val originalIds = posts.map { it.getString("id") }
        assertEquals(originalIds[1], idReads.single())

        // Restores the parent add-dialog flag and immutable plan/IDs through Android saved-state APIs.
        // This deliberately claims composition restoration, not Activity/process-death coverage.
        clearInput()
        restoration.emulateSavedInstanceStateRestore()
        await("Saved pending Manga plan restored without automatic POST") {
            compose.onAllNodesWithText("1/3 volumi confermati · Serie sintetica").fetchSemanticsNodes().isNotEmpty() &&
                enabled(button("Riprendi serie"))
        }
        assertEquals(2, posts.size)
        compose.onNodeWithText("Titolo").performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Serie sintetica")))
        compose.onNodeWithText("Titolo").assertIsNotEnabled()
        touch(button("Riprendi serie"))
        await("Explicit retry confirms existing UUID and finishes the series") {
            posts.size == 3 && rows.size == 3 &&
                compose.onAllNodesWithText("Riprendi serie").fetchSemanticsNodes().isEmpty() && enabled(button("Aggiungi"))
        }
        assertEquals(originalIds, posts.take(2).map { it.getString("id") })
        assertEquals(3, posts.map { it.getString("id") }.distinct().size)
        assertEquals(listOf(originalIds[1], originalIds[1]), idReads.toList())
        assertEquals(0, duplicates.get())
        posts.forEachIndexed { index, body ->
            assertEquals("Serie sintetica Vol. ${index + 1}", body.getString("title"))
            assertEquals(1, body.getInt("quantity"))
            assertEquals(OWNER, body.getString("user_id"))
            assertEquals("Autore sintetico", body.getString("author"))
            assertEquals("Editore sintetico", body.getString("publisher"))
            assertEquals(2024, body.getInt("year"))
            assertEquals("Nota della serie", body.getString("notes"))
            assertEquals(0, BigDecimal("6.25").compareTo(BigDecimal(body.get("purchase_price").toString())))
            assertEquals(0, BigDecimal("8.50").compareTo(BigDecimal(body.get("reselling_value").toString())))
        }
        assertCount("3 di 3 elementi", 7)
        list().performScrollToIndex(8)
        compose.onNodeWithText("Titolo: Serie sintetica Vol. 1").assertIsDisplayed()
        assertEquals(0, denied.get())
    }

    @Test fun libraryAuthorPublisherYearFiltersCombineAndKeepCollectionTotalsUnchanged() {
        add(book(1, "Primo libro", "A", "P", 2023, 10, 15))
        add(book(2, "Secondo libro", "A", "Q", 2024, 20, 25))
        add(book(3, "Terzo libro", "B", "P", 2024, 30, 35))
        mount("libreria")
        awaitLoaded("libri")
        assertCount("3 di 3 elementi", 8)
        val originalTotals = totals(6)
        selectFilter(3, "Autore: Tutti", "A")
        assertCount("2 di 3 elementi", 9)
        selectFilter(4, "Editore: Tutti", "P")
        assertCount("1 di 3 elementi", 9)
        selectFilter(5, "Anno: Tutti", "2024")
        assertCount("0 di 3 elementi", 9)
        list().performScrollToIndex(10)
        compose.onNodeWithText("Nessun elemento corrisponde ai filtri. Usa Azzera filtri per mostrare la collezione.")
            .assertIsDisplayed()
        assertEquals(originalTotals, totals(7))
        list().performScrollToIndex(6)
        touch(button("Azzera filtri"))
        assertCount("3 di 3 elementi", 8)
        assertEquals(originalTotals, totals(6))
        assertEquals(0, posts.size)
        assertEquals(0, denied.get())
    }

    @Test fun tcgSetNormalizationIsMagicOnlyAndYugiohTextSearchUsesNameOnly() {
        add(card(11, "magic", "Carta uno", "abc"))
        add(card(12, "magic", "Carta due", "ABC"))
        add(card(13, "magic", "Carta tre", "XYZ"))
        add(card(21, "pokemon", "Creatura uno", "abc"))
        add(card(22, "pokemon", "Creatura due", "ABC"))
        add(card(31, "yugioh", "Drago sintetico", "SET-ONLY"))
        add(card(32, "yugioh", "Mago sintetico", "OTHER"))
        mount("tcg")
        awaitLoaded("magic")
        list().performScrollToIndex(3)
        touch(button("Set: Tutti"))
        compose.onNodeWithText("ABC", substring = false).assertIsDisplayed()
        compose.onNodeWithText("abc", substring = false).assertDoesNotExist()
        touch(button("ABC"))
        assertCount("2 di 3 elementi", 7)
        list().performScrollToIndex(2)
        compose.onNodeWithText("Cerca per nome o set").performTextReplacement("XYZ")
        assertCount("0 di 3 elementi", 7)
        list().performScrollToIndex(4)
        touch(button("Azzera filtri"))
        list().performScrollToIndex(2)
        compose.onNodeWithText("Cerca per nome o set").performTextReplacement("abc")
        assertCount("2 di 3 elementi", 7)

        openTab("Pokémon", "pokemon")
        list().performScrollToIndex(3)
        touch(button("Set: Tutti"))
        compose.onNodeWithText("abc", substring = false).assertIsDisplayed()
        compose.onNodeWithText("ABC", substring = false).assertIsDisplayed()
        touch(button("abc"))
        assertCount("1 di 2 elementi", 7)
        list().performScrollToIndex(3)
        touch(button("Set: abc"))
        touch(button("ABC"))
        assertCount("1 di 2 elementi", 7)

        openTab("Yu-Gi-Oh", "yugioh")
        list().performScrollToIndex(2)
        compose.onNodeWithText("Cerca per nome").performTextReplacement("SET-ONLY")
        assertCount("0 di 2 elementi", 7)
        list().performScrollToIndex(2)
        compose.onNodeWithText("Cerca per nome").performTextReplacement("Drago")
        assertCount("1 di 2 elementi", 7)
        assertEquals(0, posts.size)
        assertEquals(0, denied.get())
    }

    private fun mount(section: String) {
        restoration.setContent {
            val localFocus = LocalFocusManager.current
            val localKeyboard = LocalSoftwareKeyboardController.current
            SideEffect { focus = localFocus; keyboard = localKeyboard }
            SavelyTheme("light") { ModuleScreen(api, section, Modifier.fillMaxSize()) }
        }
    }
    private fun clearInput() {
        compose.runOnIdle { focus.clearFocus(force = true); keyboard?.hide() }
    }
    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun button(label: String) = hasText(label, substring = false) and hasClickAction()
    private fun enabled(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()?.let {
            !it.config.contains(SemanticsProperties.Disabled)
        } == true

    /** Deliver a real pointer click only after a fully displayed, enabled target settles over two frames. */
    private fun touch(matcher: SemanticsMatcher) {
        clearInput()
        var previous: Rect? = null
        var previousViewports: List<Rect>? = null
        var stableFrames = 0
        await("Stable touch: $matcher") {
            compose.mainClock.advanceTimeByFrame()
            val node = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
            val bounds = node?.boundsInRoot
            val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().map { it.boundsInRoot }
            val viewports = generateSequence(node?.parent) { it.parent }.filter {
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange) ||
                    it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
            }.map { it.boundsInRoot }.toList()
            val ready = node != null && !node.config.contains(SemanticsProperties.Disabled) && bounds != null &&
                bounds.width > 0 && bounds.height > 0 && roots.any { it.contains(bounds.center) } &&
                viewports.all { it.contains(bounds.center) } &&
                compose.onNode(matcher).isDisplayed()
            stableFrames = if (ready && bounds == previous && viewports == previousViewports) stableFrames + 1 else 0
            previous = bounds
            previousViewports = viewports
            touchDiagnostic = "target=$matcher, bounds=$bounds, viewports=$viewports, roots=$roots, stableFrames=$stableFrames"
            stableFrames >= 2
        }
        compose.onNode(matcher).assertIsEnabled().assertIsDisplayed().performTouchInput { click() }
    }
    private fun replace(label: String, value: String) {
        clearInput()
        compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
    }
    private fun openTab(label: String, category: String) {
        touch(button(label))
        awaitLoaded(category)
    }
    private fun awaitLoaded(category: String) {
        await("Collection loaded: $category") { category in listCategories && enabled(button("Aggiungi")) }
    }
    private fun selectFilter(index: Int, label: String, option: String) {
        clearInput()
        list().performScrollToIndex(index)
        touch(button(label))
        touch(button(option))
    }
    private fun assertCount(label: String, index: Int) {
        clearInput()
        list().performScrollToIndex(index)
        await("Count displayed: $label") {
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() &&
                compose.onNodeWithText(label).isDisplayed()
        }
        compose.onNodeWithText(label).assertIsDisplayed()
    }
    private fun totals(index: Int): List<String> {
        clearInput()
        list().performScrollToIndex(index)
        val cost = compose.onNode(hasText("pezzi · costo", substring = true)).assertIsDisplayed()
        val value = compose.onNode(hasText("Valore ", substring = true) and
            hasText(" · variazione ", substring = true)).assertIsDisplayed()
        return listOf(cost, value).map { target ->
            target.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        }
    }
    private fun await(label: String, predicate: () -> Boolean) {
        try {
            compose.waitUntil(15_000, predicate)
        } catch (failure: ComposeTimeoutException) {
            val trees = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.map { index ->
                runCatching { compose.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToString() }
                    .getOrElse { "Semantics unavailable: ${it.message}" }
            }
            throw AssertionError("Collection timeout: $label, clock=${compose.mainClock.currentTime}, " +
                "posts=${posts.size}, rows=${rows.size}, idReads=$idReads, denied=${denied.get()}, duplicates=${duplicates.get()}\n" +
                "$touchDiagnostic\n" +
                trees.joinToString("\n"), failure)
        }
    }

    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return reject()
            if (request.getHeader("Authorization") != "Bearer collection-device-access" ||
                request.getHeader("apikey") != "public-test-key") return reject()
            val table = url.pathSegments.last()
            if (table !in listOf("library_items", "tgc_cards")) return reject()
            if (request.method == "POST") {
                if (table != "library_items") return reject()
                val body = JSONObject(request.body.readUtf8())
                if (body.optString("user_id") != OWNER || body.optString("category") != "manga" ||
                    !runCatching { UUID.fromString(body.getString("id")) }.isSuccess) return reject()
                posts.add(JSONObject(body.toString()))
                if (rows.putIfAbsent(body.getString("id"), body) != null) {
                    duplicates.incrementAndGet()
                    return json(409, JSONObject().put("code", "23505"))
                }
                if (ambiguousSecondPost.get() && posts.size == 2)
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                return json(201, JSONArray().put(body))
            }
            if (request.method != "GET" || url.queryParameter("user_id") != "eq.$OWNER") return reject()
            val id = url.queryParameter("id")?.removePrefix("eq.")
            if (id != null) {
                if (table != "library_items" || url.queryParameter("limit") != "2") return reject()
                idReads.add(id)
                if (failFirstIdRead.getAndSet(false)) return json(503, JSONObject().put("message", "Synthetic reconciliation failure"))
                return json(200, JSONArray().also { result -> rows[id]?.let { result.put(it) } })
            }
            val category = url.queryParameter("category")?.removePrefix("eq.") ?: return reject()
            val valid = if (table == "library_items") listOf("libri", "fumetti", "manga") else listOf("magic", "pokemon", "yugioh")
            if (category !in valid || url.queryParameter("limit") != "500" || url.queryParameter("order") != "created_at.desc") return reject()
            val offset = url.queryParameter("offset")?.toIntOrNull()?.takeIf { it >= 0 } ?: return reject()
            listCategories.add(category)
            val selected = rows.values.filter { it.optString("category") == category && it.optString("user_id") == OWNER }
                .sortedBy { it.optString(if (table == "library_items") "title" else "name") }.drop(offset).take(500)
            return json(200, JSONArray(selected))
        }
    }
    private fun add(body: JSONObject) { rows[body.getString("id")] = body }
    private fun book(number: Int, title: String, author: String, publisher: String, year: Int, purchase: Int, resale: Int) =
        JSONObject().put("id", id(number)).put("user_id", OWNER).put("category", "libri").put("title", title)
            .put("author", author).put("publisher", publisher).put("year", year).put("quantity", 1)
            .put("purchase_price", purchase).put("reselling_value", resale).put("created_at", "2026-01-01T00:00:00Z")
    private fun card(number: Int, category: String, name: String, set: String) = JSONObject()
        .put("id", id(number)).put("user_id", OWNER).put("category", category).put("name", name).put("set_code", set)
        .put("condition", "near_mint").put("language", "IT").put("quantity", 1).put("purchase_price", 2)
        .put("current_price", 3).put("purchase_date", "2026-01-01").put("created_at", "2026-01-01T00:00:00Z")
    private fun id(number: Int) = "32000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
    private fun json(status: Int, body: Any) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun reject(): MockResponse { denied.incrementAndGet(); return json(403, JSONObject()) }
    private companion object {
        const val OWNER = "32000000-0000-4000-8000-000000000100"
        const val RANGE = "Numero volume o intervallo (facoltativo)"
    }
}
