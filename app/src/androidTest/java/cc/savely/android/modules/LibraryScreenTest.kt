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
import androidx.compose.ui.test.junit4.createComposeRule
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
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Savely 1cfcf629: LibraryPage.openEdit/handleEditSubmit accept nullable library prices.
 * Synthetic TLS fixtures and real pointer clicks verify the client, not deployed RLS.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(90)
    @get:Rule(order = 1) val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private lateinit var focus: FocusManager
    private var keyboard: SoftwareKeyboardController? = null
    private val rows = ConcurrentHashMap<String, JSONObject>()
    private val patches = CopyOnWriteArrayList<JSONObject>()
    private val patchedIds = CopyOnWriteArrayList<String>()
    private val bookReads = AtomicInteger()
    private val insertDeleteRequests = AtomicInteger()
    private val denied = AtomicInteger()
    private val foreignScopeRequests = AtomicInteger()
    private val releasePatch = CountDownLatch(1)
    private var allowedPatches = 1
    private lateinit var foreignSnapshot: String
    private var touchDiagnostic = "No pointer target sampled"

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Savely Library UI fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
        server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            dispatcher = fixture()
            start()
        }
        val base = server.url("/").toString()
        val session = Session("library-device-access", "library-device-refresh",
            System.currentTimeMillis() / 1000 + 3600, OWNER, "library-ui@example.test")
        api = SupabaseApi(BackendConfig(base, "public-test-key", base), MemorySessionStore(session), client)
        rows[OWN_ROW] = book(OWN_ROW, OWNER, TITLE)
        rows[FOREIGN_ROW] = book(FOREIGN_ROW, FOREIGN_OWNER, "Libro estraneo")
            .put("purchase_price", 999999).put("reselling_value", 999999).put("quantity", 99)
        foreignSnapshot = rows[FOREIGN_ROW]!!.toString()
    }

    @After fun cleanup() {
        releasePatch.countDown()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun nullablePricesRemainNullWhenEditingNotesAndQuantityAfterRejectingInvalidNumbers() {
        compose.setContent {
            val localFocus = LocalFocusManager.current
            val localKeyboard = LocalSoftwareKeyboardController.current
            SideEffect { focus = localFocus; keyboard = localKeyboard }
            SavelyTheme("light") { ModuleScreen(api, "libreria", Modifier.fillMaxSize()) }
        }
        await("Owner book collection loaded") { bookReads.get() >= 2 && enabled(button("Aggiungi")) }
        assertNullPrices(rows[OWN_ROW]!!)
        list().performScrollToIndex(9)
        compose.onNode(button("Modifica")).performScrollTo()
        touch(button("Modifica"))
        replace(PURCHASE, "1,2,3")
        touch(button("Salva"))
        compose.onNodeWithText("Inserisci un numero valido").performScrollTo().assertIsDisplayed()
        assertEquals("Malformed input must not issue a PATCH", 0, patches.size)
        assertNullPrices(rows[OWN_ROW]!!)
        replace(PURCHASE, "")
        replace(RESALE, "-0,01")
        touch(button("Salva"))
        compose.onNodeWithText("Valore rivendita unitario (€): valore negativo").performScrollTo().assertIsDisplayed()
        assertEquals("Optional prices must still reject negative input", 0, patches.size)
        assertNullPrices(rows[OWN_ROW]!!)
        replace(RESALE, "")
        replace("Quantità", "2")
        replace("Note (facoltativo)", NEW_NOTE)
        val readsBefore = bookReads.get()
        // The response is gated so both real taps happen before the edit completes.
        touch(button("Salva"), taps = 2)
        await("One pending edit disables save") {
            patches.size == 1 && compose.onAllNodes(button("Salva")).fetchSemanticsNodes().singleOrNull()
                ?.config?.contains(SemanticsProperties.Disabled) == true
        }
        assertEquals(listOf(OWN_ROW), patchedIds.toList())
        val body = patches.single()
        assertNullPrices(body)
        assertNumber(body, "quantity", "2")
        assertEquals(NEW_NOTE, body.getString("notes"))
        assertEquals(TITLE, body.getString("title"))
        listOf("id", "user_id", "api_id", "cover_image").forEach { key ->
            assertFalse("The form must not overwrite unexposed $key", body.has(key))
        }
        releasePatch.countDown()
        await("Saved record reloaded and dialog closed") {
            bookReads.get() >= readsBefore + 2 && compose.onAllNodes(button("Salva")).fetchSemanticsNodes().isEmpty()
        }
        clearInput()
        list().performScrollToIndex(0)
        await("Reloaded collection enables its header") { enabled(button("Aggiungi")) }
        val stored = rows[OWN_ROW]!!
        assertEquals(OWN_ROW, stored.getString("id"))
        assertEquals(OWNER, stored.getString("user_id"))
        assertEquals(API_ID, stored.getString("api_id"))
        assertTrue(stored.has("cover_image"))
        assertSame(JSONObject.NULL, stored.get("cover_image"))
        assertNullPrices(stored)
        assertNumber(stored, "quantity", "2")
        assertEquals(NEW_NOTE, stored.getString("notes"))

        val beforeRefresh = bookReads.get()
        touch(button("Aggiorna"))
        await("Explicit owner GET reload completed") { bookReads.get() >= beforeRefresh + 2 && enabled(button("Aggiungi")) }
        list().performScrollToIndex(6)
        compose.onNodeWithText("2 pezzi · costo ${euro("0")}").assertIsDisplayed()
        compose.onNode(hasText("Valore ", substring = true) and hasText(" · variazione ", substring = true))
            .assertIsDisplayed().assertTextEquals("Valore ${euro("0")} · variazione ${euro("0")}")
        list().performScrollToIndex(9)
        compose.onNodeWithText("Titolo: $TITLE").assertIsDisplayed()
        compose.onNodeWithText("Quantità: 2").assertIsDisplayed()
        compose.onNodeWithText("Note: $NEW_NOTE").assertIsDisplayed()
        compose.onAllNodesWithText("Prezzo acquisto unitario (€):", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Valore rivendita unitario (€):", substring = true).assertCountEquals(0)
        assertEquals(1, patches.size)
        assertEquals(listOf(OWN_ROW), patchedIds.toList())
        assertEquals("Only the existing row may be updated", 0, insertDeleteRequests.get())
        assertEquals("No missing/foreign owner filters or foreign ID updates", 0, foreignScopeRequests.get())
        assertEquals("Only permitted local routes and methods", 0, denied.get())
        assertEquals(1, rows.values.count { it.getString("user_id") == OWNER })
        assertEquals("The foreign row must remain byte-for-byte unchanged", foreignSnapshot, rows[FOREIGN_ROW]!!.toString())
        compose.onAllNodesWithText("Libro estraneo").assertCountEquals(0)
        compose.onAllNodesWithText("999999", substring = true).assertCountEquals(0)
    }

    @Test fun mangaCoverRejectsUnsafeUrlThenAddsChangesAndClearsOnSameOwnerRecord() {
        allowedPatches = 3
        rows[OWN_ROW] = book(OWN_ROW, OWNER, "Manga copertina").put("category", "manga")
            .put("fixture_metadata", "preserve-unexposed-value")
        rows[FOREIGN_ROW]!!.put("category", "manga")
        foreignSnapshot = rows[FOREIGN_ROW]!!.toString()
        compose.setContent {
            val localFocus = LocalFocusManager.current
            val localKeyboard = LocalSoftwareKeyboardController.current
            SideEffect { focus = localFocus; keyboard = localKeyboard }
            SavelyTheme("light") { ModuleScreen(api, "libreria", Modifier.fillMaxSize()) }
        }
        // The initial Libri category is empty: its first page already terminates pagination.
        await("Initial empty owner collection loaded") { bookReads.get() == 1 && enabled(button("Aggiungi")) }
        assertEquals("An empty Libri category needs exactly one owner GET", 1, bookReads.get())
        val beforeTab = bookReads.get()
        touch(button("Manga"))
        await("Owner Manga collection loaded") { bookReads.get() >= beforeTab + 2 && enabled(button("Aggiungi")) }
        openMangaEdit()
        listOf("javascript:alert(1)", "https://user:secret@localhost:1/cover.png", "/cover.png").forEach { invalid ->
            replace(COVER, invalid)
            touch(button("Salva"))
            compose.onNode(hasText("URL copertina: inserisci un URL HTTP o HTTPS valido", substring = true))
                .performScrollTo().assertIsDisplayed()
            assertEquals("Invalid cover input must not reach PATCH", 0, patches.size)
            assertSame(JSONObject.NULL, rows[OWN_ROW]!!.get("cover_image"))
        }
        // Loopback-only URLs exercise the stored field; image loading itself is outside this fixture.
        replace(COVER, "http://localhost:1/cover-a.png")
        replace("Note (facoltativo)", NEW_NOTE)
        replace("Quantità", "2")
        val beforeAdd = bookReads.get()
        touch(button("Salva"), taps = 2)
        await("Only one cover PATCH is pending") {
            patches.size == 1 && compose.onAllNodes(button("Salva")).fetchSemanticsNodes().singleOrNull()
                ?.config?.contains(SemanticsProperties.Disabled) == true
        }
        assertEquals("http://localhost:1/cover-a.png", patches.single().getString("cover_image"))
        releasePatch.countDown()
        awaitMangaSaved(beforeAdd, 1)
        assertEquals("http://localhost:1/cover-a.png", rows[OWN_ROW]!!.getString("cover_image"))
        openMangaEdit()
        replace(COVER, "https://localhost:1/cover-b.png?size=small")
        val beforeChange = bookReads.get()
        touch(button("Salva"))
        awaitMangaSaved(beforeChange, 2)
        assertEquals("https://localhost:1/cover-b.png?size=small", rows[OWN_ROW]!!.getString("cover_image"))
        openMangaEdit()
        replace(COVER, "")
        val beforeClear = bookReads.get()
        touch(button("Salva"))
        awaitMangaSaved(beforeClear, 3)
        assertTrue(patches.last().has("cover_image"))
        assertSame(JSONObject.NULL, patches.last().get("cover_image"))
        val beforeRefresh = bookReads.get()
        clearInput()
        list().performScrollToIndex(0)
        touch(button("Aggiorna"))
        await("Explicit owner Manga GET after clearing cover") {
            bookReads.get() >= beforeRefresh + 2 && enabled(button("Aggiungi"))
        }
        val stored = rows[OWN_ROW]!!
        assertEquals(OWN_ROW, stored.getString("id"))
        assertEquals(OWNER, stored.getString("user_id"))
        assertEquals(API_ID, stored.getString("api_id"))
        assertEquals("preserve-unexposed-value", stored.getString("fixture_metadata"))
        assertEquals(NEW_NOTE, stored.getString("notes"))
        assertEquals(2, stored.getInt("quantity"))
        assertSame(JSONObject.NULL, stored.get("cover_image"))
        assertNullPrices(stored)
        patches.forEach { body ->
            assertNullPrices(body)
            listOf("id", "user_id", "api_id", "fixture_metadata").forEach { assertFalse(body.has(it)) }
        }
        assertEquals(listOf(OWN_ROW, OWN_ROW, OWN_ROW), patchedIds.toList())
        assertEquals(3, patches.size)
        assertEquals(0, insertDeleteRequests.get())
        assertEquals(0, foreignScopeRequests.get())
        assertEquals(0, denied.get())
        assertEquals(foreignSnapshot, rows[FOREIGN_ROW]!!.toString())
        compose.onAllNodesWithText("Libro estraneo").assertCountEquals(0)
    }

    private fun openMangaEdit() {
        clearInput()
        list().performScrollToIndex(8)
        compose.onNode(button("Modifica")).performScrollTo()
        touch(button("Modifica"))
    }
    private fun awaitMangaSaved(readsBefore: Int, expectedPatches: Int) {
        await("Manga edit reloaded") {
            patches.size == expectedPatches && bookReads.get() >= readsBefore + 2 &&
                compose.onAllNodes(button("Salva")).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun assertNullPrices(body: JSONObject) {
        listOf("purchase_price", "reselling_value").forEach { key ->
            assertTrue("$key must be explicitly present", body.has(key))
            assertSame("$key must remain JSON null rather than zero", JSONObject.NULL, body.get(key))
        }
    }
    private fun assertNumber(body: JSONObject, key: String, expected: String) {
        assertTrue("$key must use a JSON number", body.get(key) is Number)
        assertEquals("$key must retain the expected quantity", 0,
            BigDecimal(expected).compareTo(BigDecimal(body.get(key).toString())))
    }
    private fun euro(amount: String) = NumberFormat.getCurrencyInstance(Locale.ITALY).format(BigDecimal(amount))
    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun button(label: String) = hasText(label, substring = false) and hasClickAction()
    private fun enabled(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()?.let {
            !it.config.contains(SemanticsProperties.Disabled)
        } == true
    private fun clearInput() { compose.runOnIdle { focus.clearFocus(force = true); keyboard?.hide() } }
    private fun replace(label: String, value: String) {
        clearInput()
        compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
    }
    private fun touch(matcher: SemanticsMatcher, taps: Int = 1) {
        clearInput()
        var previous: Rect? = null
        var previousViewports: List<Rect>? = null
        var stableFrames = 0
        await("Stable pointer target: $matcher") {
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
                viewports.all { it.contains(bounds.center) } && compose.onNode(matcher).isDisplayed()
            stableFrames = if (ready && bounds == previous && viewports == previousViewports) stableFrames + 1 else 0
            previous = bounds
            previousViewports = viewports
            touchDiagnostic = "target=$matcher, bounds=$bounds, viewports=$viewports, stableFrames=$stableFrames"
            stableFrames >= 2
        }
        compose.onNode(matcher).assertIsEnabled().assertIsDisplayed().performTouchInput {
            repeat(taps) { click(); advanceEventTime(40) }
        }
    }
    private fun await(label: String, predicate: () -> Boolean) {
        try { compose.waitUntil(15_000, predicate) }
        catch (failure: ComposeTimeoutException) {
            val trees = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.map { index ->
                runCatching { compose.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToString() }
                    .getOrElse { "Semantics unavailable: ${it.message}" }
            }
            throw AssertionError("Library fixture timeout: $label, clock=${compose.mainClock.currentTime}, " +
                "patches=${patches.size}, bookReads=${bookReads.get()}, insertDelete=${insertDeleteRequests.get()}, " +
                "denied=${denied.get()}, foreign=${foreignScopeRequests.get()}\n" +
                "$touchDiagnostic\n${trees.joinToString("\n")}", failure)
        }
    }

    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return reject()
            if (request.getHeader("Authorization") != "Bearer library-device-access" ||
                request.getHeader("apikey") != "public-test-key") return reject()
            if (request.method in listOf("POST", "DELETE")) { insertDeleteRequests.incrementAndGet(); return reject() }
            if (url.encodedPath != "/rest/v1/library_items") return reject()
            if (request.method == "PATCH") {
                if (url.queryParameterNames != setOf("id")) return reject()
                val id = url.queryParameter("id")?.removePrefix("eq.") ?: return reject()
                val existing = rows[id]
                if (id != OWN_ROW || existing?.optString("user_id") != OWNER) {
                    foreignScopeRequests.incrementAndGet()
                    return reject()
                }
                val body = JSONObject(request.body.readUtf8())
                if (body.has("user_id") && body.optString("user_id") != OWNER) {
                    foreignScopeRequests.incrementAndGet()
                    return reject()
                }
                patchedIds.add(id)
                patches.add(JSONObject(body.toString()))
                if (patches.size > allowedPatches) return json(409, JSONObject().put("message", "Synthetic duplicate PATCH"))
                val persisted = JSONObject(existing.toString())
                body.keys().forEach { key -> persisted.put(key, body.get(key)) }
                rows[id] = persisted
                if (!releasePatch.await(20, TimeUnit.SECONDS))
                    return json(503, JSONObject().put("message", "Synthetic test response gate expired"))
                return json(200, JSONArray().put(persisted))
            }
            if (request.method != "GET") return reject()
            if (url.queryParameter("user_id") != "eq.$OWNER") { foreignScopeRequests.incrementAndGet(); return reject() }
            val category = url.queryParameter("category")?.removePrefix("eq.")
            if (category !in listOf("libri", "manga") || url.queryParameter("order") != "created_at.desc" ||
                url.queryParameter("limit") != "500") return reject()
            val offset = url.queryParameter("offset")?.toIntOrNull()?.takeIf { it >= 0 } ?: return reject()
            bookReads.incrementAndGet()
            return json(200, JSONArray(rows.values.filter { it.getString("user_id") == OWNER && it.getString("category") == category }
                .sortedByDescending { it.getString("created_at") }.drop(offset).take(500)))
        }
    }
    private fun book(id: String, owner: String, title: String) = JSONObject()
        .put("id", id).put("user_id", owner).put("category", "libri").put("title", title)
        .put("author", "Autore fixture").put("publisher", "Editore fixture").put("year", 2024)
        .put("api_id", API_ID).put("cover_image", JSONObject.NULL).put("quantity", 1)
        .put("purchase_price", JSONObject.NULL).put("reselling_value", JSONObject.NULL).put("notes", JSONObject.NULL)
        .put("created_at", "2024-01-01T00:00:00Z")
    private fun json(status: Int, body: Any) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun reject(): MockResponse { denied.incrementAndGet(); return json(403, JSONObject()) }
    private companion object {
        const val OWNER = "36000000-0000-4000-8000-000000000001"
        const val FOREIGN_OWNER = "36000000-0000-4000-8000-000000000002"
        const val OWN_ROW = "36000000-0000-4000-8000-000000000003"
        const val FOREIGN_ROW = "36000000-0000-4000-8000-000000000004"
        const val TITLE = "Libro senza prezzo"
        const val API_ID = "google-book-fixture-42"
        const val NEW_NOTE = "Nota aggiornata"
        const val PURCHASE = "Prezzo acquisto unitario (€) (facoltativo)"
        const val RESALE = "Valore rivendita unitario (€) (facoltativo)"
        const val COVER = "URL copertina (facoltativo)"
    }
}
