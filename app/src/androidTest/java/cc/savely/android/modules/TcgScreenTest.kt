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
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Savely 1cfcf629: TcgCollectionPage.handleSubmit, fetchCtZeroPrice and useTcgCards totals.
 * Local TLS and real Compose touch verify client behavior; they do not prove deployed RLS.
 */
@RunWith(AndroidJUnit4::class)
class TcgScreenTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(90)
    @get:Rule(order = 1) val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private lateinit var focus: FocusManager
    private var keyboard: SoftwareKeyboardController? = null
    private val rows = ConcurrentHashMap<String, JSONObject>()
    private val posts = CopyOnWriteArrayList<JSONObject>()
    private val cardReads = AtomicInteger()
    private val searchReads = AtomicInteger()
    private val priceReads = AtomicInteger()
    private val patchDeleteRequests = AtomicInteger()
    private val denied = AtomicInteger()
    private val foreignScopeRequests = AtomicInteger()
    private val releasePost = CountDownLatch(1)
    private var touchDiagnostic = "No pointer target sampled"
    private val purchaseDate = LocalDate.now().toString()

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Savely TCG UI fixture")
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
        val session = Session("tcg-device-access", "tcg-device-refresh",
            System.currentTimeMillis() / 1000 + 3600, OWNER, "tcg-ui@example.test")
        api = SupabaseApi(BackendConfig(base, "public-test-key", base), MemorySessionStore(session), client)
        rows[FOREIGN_ROW] = JSONObject().put("id", FOREIGN_ROW).put("user_id", FOREIGN_OWNER)
            .put("name", "Carta estranea").put("category", "magic").put("card_id", "999")
            .put("set_code", "FOREIGN").put("condition", "near_mint").put("language", "EN")
            .put("quantity", 99).put("purchase_price", 999999).put("current_price", 999999)
            .put("purchase_date", purchaseDate).put("created_at", "${purchaseDate}T00:00:00Z")
    }

    @After fun cleanup() {
        releasePost.countDown()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun selectedCardPrice503StillAllowsSingleCreateWithNullPriceAndPurchaseValueFallback() {
        compose.setContent {
            val localFocus = LocalFocusManager.current
            val localKeyboard = LocalSoftwareKeyboardController.current
            SideEffect { focus = localFocus; keyboard = localKeyboard }
            SavelyTheme("light") { ModuleScreen(api, "tcg", Modifier.fillMaxSize()) }
        }
        await("Owner Magic collection loaded") { cardReads.get() > 0 && enabled(button("Aggiungi")) }
        list().performScrollToIndex(1)
        touch(button("Cerca su CardTrader"))
        replace("Nome carta", CARD_NAME)
        touch(button("Cerca"))
        await("Selected blueprint returned by the local catalog") {
            searchReads.get() == 1 && enabled(button("$CARD_NAME · $SET_NAME"))
        }
        compose.onNode(button("$CARD_NAME · $SET_NAME")).performScrollTo()
        touch(button("$CARD_NAME · $SET_NAME"))
        replace("Quantità", "3")
        replace("Prezzo acquisto unitario (€)", "12,50")
        replace("Data acquisto (AAAA-MM-GG)", purchaseDate)
        replace("Prezzo attuale unitario (€) (facoltativo)", "")
        val readsBefore = cardReads.get()
        // Two normal pointer taps precede the gated response; only one request may reach either service.
        touch(button("Salva"), taps = 2)
        await("Price failure allows one in-flight insert with a disabled save button") {
            priceReads.get() == 1 && posts.size == 1 &&
                compose.onAllNodes(button("Salva")).fetchSemanticsNodes().singleOrNull()
                    ?.config?.contains(SemanticsProperties.Disabled) == true
        }
        assertEquals(1, priceReads.get())
        assertEquals(1, posts.size)
        assertStoredContract(posts.single())
        releasePost.countDown()
        await("Created row read back and dialog closed") {
            cardReads.get() >= readsBefore + 2 &&
                compose.onAllNodes(button("Salva")).fetchSemanticsNodes().isEmpty()
        }
        clearInput()
        list().performScrollToIndex(0)
        await("Reloaded collection header enabled") { enabled(button("Aggiungi")) }
        assertEquals(OWN_ROW, rows[OWN_ROW]!!.getString("id"))
        assertStoredContract(rows[OWN_ROW]!!)

        val beforeRefresh = cardReads.get()
        touch(button("Aggiorna"))
        await("Explicit GET reload completed") { cardReads.get() >= beforeRefresh + 2 && enabled(button("Aggiungi")) }
        list().performScrollToIndex(4)
        compose.onNodeWithText("3 pezzi · costo ${euro("37.50")}").assertIsDisplayed()
        compose.onNode(hasText("Valore ", substring = true) and hasText(" · variazione ", substring = true))
            .assertIsDisplayed().assertTextEquals("Valore ${euro("37.50")} · variazione ${euro("0")}")
        list().performScrollToIndex(7)
        compose.onNodeWithText("Nome carta: $CARD_NAME").assertIsDisplayed()
        compose.onNodeWithText("Set: $SET_NAME").assertIsDisplayed()
        compose.onNodeWithText("Condizione: near_mint").assertIsDisplayed()
        compose.onNodeWithText("Lingua: EN").assertIsDisplayed()
        compose.onAllNodesWithText("Prezzo attuale unitario (€):", substring = true).assertCountEquals(0)
        assertEquals(1, searchReads.get())
        assertEquals(1, priceReads.get())
        assertEquals(1, posts.size)
        assertEquals("This flow only inserts; it must not edit or delete", 0, patchDeleteRequests.get())
        assertEquals(1, rows.values.count { it.getString("user_id") == OWNER })
        assertEquals("No missing/foreign owner filters or foreign-owner inserts", 0, foreignScopeRequests.get())
        assertEquals("Only permitted local fixture routes and methods", 0, denied.get())
        assertNumber(rows[FOREIGN_ROW]!!, "purchase_price", "999999")
        assertNumber(rows[FOREIGN_ROW]!!, "current_price", "999999")
        assertNumber(rows[FOREIGN_ROW]!!, "quantity", "99")
        compose.onAllNodesWithText("Carta estranea").assertCountEquals(0)
        compose.onAllNodesWithText("999999", substring = true).assertCountEquals(0)
    }

    private fun assertStoredContract(body: JSONObject) {
        assertEquals(OWNER, body.getString("user_id"))
        assertEquals(CARD_NAME, body.getString("name"))
        assertEquals("magic", body.getString("category"))
        assertEquals("42", body.getString("card_id"))
        assertEquals(SET_NAME, body.getString("set_code"))
        assertEquals("near_mint", body.getString("condition"))
        assertEquals("EN", body.getString("language"))
        assertEquals(purchaseDate, body.getString("purchase_date"))
        assertNumber(body, "quantity", "3")
        assertNumber(body, "purchase_price", "12.50")
        assertTrue("The failed price service must not invent zero or omit the null field", body.has("current_price"))
        assertSame(JSONObject.NULL, body.get("current_price"))
        assertTrue("The selected fixture intentionally has no external image", body.has("image_url"))
        assertSame(JSONObject.NULL, body.get("image_url"))
    }
    private fun assertNumber(body: JSONObject, key: String, expected: String) {
        assertTrue("$key must use a JSON number", body.get(key) is Number)
        assertEquals("$key must retain audited quantity and units", 0,
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
            throw AssertionError("TCG fixture timeout: $label, clock=${compose.mainClock.currentTime}, " +
                "posts=${posts.size}, cardReads=${cardReads.get()}, searchReads=${searchReads.get()}, " +
                "priceReads=${priceReads.get()}, patchDelete=${patchDeleteRequests.get()}, " +
                "denied=${denied.get()}, foreign=${foreignScopeRequests.get()}\n" +
                "$touchDiagnostic\n${trees.joinToString("\n")}", failure)
        }
    }

    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return reject()
            if (request.getHeader("Authorization") != "Bearer tcg-device-access" ||
                request.getHeader("apikey") != "public-test-key") return reject()
            if (request.method in listOf("PATCH", "DELETE")) { patchDeleteRequests.incrementAndGet(); return reject() }
            if (url.encodedPath == "/functions/v1/cardtrader-proxy") {
                if (request.method != "GET") return reject()
                return when (url.queryParameter("action")) {
                    "search" -> {
                        if (url.queryParameterNames != setOf("action", "name") || url.queryParameter("name") != CARD_NAME)
                            return reject()
                        searchReads.incrementAndGet()
                        json(200, JSONArray().put(JSONObject().put("id", "42").put("name", CARD_NAME)
                            .put("set", JSONObject().put("name", SET_NAME)).put("image", "")))
                    }
                    "prices" -> {
                        if (url.queryParameterNames != setOf("action", "id", "condition") ||
                            url.queryParameter("id") != "42" || url.queryParameter("condition") != "near_mint") return reject()
                        priceReads.incrementAndGet()
                        json(503, JSONObject().put("message", "Synthetic CardTrader price service unavailable"))
                    }
                    else -> reject()
                }
            }
            if (url.encodedPath != "/rest/v1/tgc_cards") return reject()
            if (request.method == "POST") {
                if (url.queryParameterNames.isNotEmpty()) return reject()
                val body = JSONObject(request.body.readUtf8())
                if (body.optString("user_id") != OWNER) { foreignScopeRequests.incrementAndGet(); return reject() }
                posts.add(JSONObject(body.toString()))
                if (posts.size != 1 || rows.containsKey(OWN_ROW))
                    return json(409, JSONObject().put("message", "Synthetic duplicate POST"))
                val persisted = JSONObject(body.toString()).put("id", OWN_ROW).put("created_at", "${purchaseDate}T00:00:00Z")
                rows[OWN_ROW] = persisted
                if (!releasePost.await(20, TimeUnit.SECONDS))
                    return json(503, JSONObject().put("message", "Synthetic test response gate expired"))
                return json(201, JSONArray().put(persisted))
            }
            if (request.method != "GET") return reject()
            if (url.queryParameter("user_id") != "eq.$OWNER") { foreignScopeRequests.incrementAndGet(); return reject() }
            if (url.queryParameter("category") != "eq.magic" || url.queryParameter("order") != "created_at.desc" ||
                url.queryParameter("limit") != "500") return reject()
            val offset = url.queryParameter("offset")?.toIntOrNull()?.takeIf { it >= 0 } ?: return reject()
            cardReads.incrementAndGet()
            return json(200, JSONArray(rows.values.filter { it.getString("user_id") == OWNER && it.getString("category") == "magic" }
                .sortedByDescending { it.getString("created_at") }.drop(offset).take(500)))
        }
    }
    private fun json(status: Int, body: Any) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun reject(): MockResponse { denied.incrementAndGet(); return json(403, JSONObject()) }
    private companion object {
        const val OWNER = "35000000-0000-4000-8000-000000000001"
        const val FOREIGN_OWNER = "35000000-0000-4000-8000-000000000002"
        const val OWN_ROW = "35000000-0000-4000-8000-000000000003"
        const val FOREIGN_ROW = "35000000-0000-4000-8000-000000000004"
        const val CARD_NAME = "Carta fixture"
        const val SET_NAME = "SET TEST"
    }
}
