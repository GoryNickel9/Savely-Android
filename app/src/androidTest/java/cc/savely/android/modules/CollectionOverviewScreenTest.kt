package cc.savely.android.modules

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
import java.util.Currency
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** TLS loopback fixtures and ordinary pointer clicks; no deployed backend/RLS assertions. */
@RunWith(AndroidJUnit4::class)
class CollectionOverviewScreenTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(90)
    @get:Rule(order = 1) val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private val rows = CopyOnWriteArrayList<JSONObject>()
    private val overviewOffsets = CopyOnWriteArrayList<Int>()
    private val mutations = AtomicInteger()
    private val denied = AtomicInteger()
    private val failSecondPage = AtomicBoolean()
    private var fixtureTable = "tgc_cards"

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Savely overview fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); dispatcher = fixture(); start() }
        api = SupabaseApi(BackendConfig(server.url("/").toString(), "public-test-key"),
            MemorySessionStore(Session("overview-device-access", "overview-device-refresh", Long.MAX_VALUE,
                OWNER, "overview-ui@example.test")), client)
    }
    @After fun cleanup() {
        server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }

    @Test fun tcgOverviewShowsEachCurrencyAndCategoryWithoutAddingMoneyUnitsOrForeignRows() {
        rows.add(card(1, "magic", 2, "10", "15", "EUR"))
        rows.add(card(2, "pokemon", 3, "5", null, "EUR"))
        rows.add(card(3, "yugioh", 4, "2.50", "3", "USD"))
        rows.add(card(4, "magic", 99, "999999", "999999", "EUR").put("user_id", FOREIGN))
        val before = rows.map(JSONObject::toString)
        openOverview("tcg")
        assertEquals(listOf(0, 3), overviewOffsets.toList())
        list().performScrollToIndex(1)
        compose.onNodeWithText("Totale Pezzi").assertIsDisplayed()
        compose.onNodeWithText("9").assertIsDisplayed()
        assertMoneyCard(3, "overview-total-EUR", "EUR", "35", "45", "10")
        assertMoneyCard(4, "overview-total-USD", "USD", "10", "12", "2")
        assertMoneyCard(6, "overview-category-magic-EUR", "EUR", "20", "30", "10")
        assertMoneyCard(8, "overview-category-pokemon-EUR", "EUR", "15", "15", "0")
        assertMoneyCard(10, "overview-category-yugioh-USD", "USD", "10", "12", "2")
        assertEquals(before, rows.map(JSONObject::toString))
        assertScope()
    }

    @Test fun libraryOverviewKeepsNullPricesAndCategoryCountsAndOpensNativeCategory() {
        fixtureTable = "library_items"
        rows.add(book(1, "libri", 2, null, "6"))
        rows.add(book(2, "fumetti", 3, "4.50", null))
        rows.add(book(3, "manga", 1, "10", "12"))
        rows.add(book(4, "libri", 99, "999999", "999999").put("user_id", FOREIGN))
        val before = rows.map(JSONObject::toString)
        openOverview("libreria")
        assertMoneyCard(2, "overview-total-EUR", "EUR", "23.50", "24", "0.50")
        assertMoneyCard(4, "overview-category-libri-EUR", "EUR", "0", "12", "12")
        compose.onNode(hasText("Rendimento: —") and hasAnyAncestor(hasTestTag("overview-category-libri-EUR"))).assertIsDisplayed()
        assertMoneyCard(6, "overview-category-fumetti-EUR", "EUR", "13.50", "0", "-13.50")
        assertMoneyCard(8, "overview-category-manga-EUR", "EUR", "10", "12", "2")
        list().performScrollToIndex(3)
        touch(button("Apri Libri"))
        await("Native Libri form remains available") { enabled(button("Aggiungi")) && compose.onAllNodes(button("Panoramica")).fetchSemanticsNodes().isNotEmpty() }
        list().performScrollToIndex(8)
        compose.onNodeWithText("1 di 1 elementi").assertIsDisplayed()
        assertEquals(before, rows.map(JSONObject::toString))
        assertSame(JSONObject.NULL, rows[0].get("purchase_price"))
        assertEquals("api-1", rows[0].getString("api_id"))
        assertScope()
    }

    @Test fun laterPageFailureHidesAllTotalsAndExplicitRetryLoadsCompleteSnapshot() {
        repeat(501) { index -> rows.add(card(index, "magic", 1, "1", "2", "EUR")) }
        val before = rows.map(JSONObject::toString)
        failSecondPage.set(true)
        openOverview("tcg", success = false)
        compose.onNodeWithText("Panoramica non disponibile. Nessun totale parziale viene mostrato.").assertIsDisplayed()
        compose.onNodeWithText("Totale Pezzi").assertDoesNotExist()
        compose.onNodeWithTag("overview-total-EUR").assertDoesNotExist()
        assertEquals(listOf(0, 500), overviewOffsets.toList())
        failSecondPage.set(false)
        touch(button("Riprova"))
        await("Retry reads a fresh complete snapshot") { overviewOffsets.size == 5 && enabled(button("Aggiorna")) }
        assertEquals(listOf(0, 500, 0, 500, 501), overviewOffsets.toList())
        assertMoneyCard(2, "overview-total-EUR", "EUR", "501", "1002", "501")
        list().performScrollToIndex(1)
        compose.onNodeWithText("501").assertIsDisplayed()
        assertEquals(before, rows.map(JSONObject::toString))
        assertScope()
    }

    private fun openOverview(section: String, success: Boolean = true) {
        compose.setContent { SavelyTheme("light") { ModuleScreen(api, section, Modifier.fillMaxSize()) } }
        await("Initial native category loaded") { enabled(button("Aggiungi")) }
        compose.onNode(button("Panoramica")).performScrollTo()
        touch(button("Panoramica"))
        await("Owner overview load finished") { enabled(button(if (success) "Aggiorna" else "Riprova")) && overviewOffsets.isNotEmpty() }
    }
    private fun assertMoneyCard(index: Int, tag: String, currency: String, investment: String, value: String, profit: String) {
        list().performScrollToIndex(index)
        compose.onNodeWithTag(tag).assertIsDisplayed()
        listOf("Investimento: ${amount(investment, currency)}", "Valore: ${amount(value, currency)}",
            "Profitto / Perdita: ${amount(profit, currency)}").forEach { expected ->
            compose.onNode(hasText(expected) and hasAnyAncestor(hasTestTag(tag))).assertIsDisplayed()
        }
    }
    private fun amount(literal: String, code: String) = NumberFormat.getCurrencyInstance(Locale.ITALY)
        .apply { currency = Currency.getInstance(code); minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(BigDecimal(literal))
    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun button(text: String) = hasText(text) and hasClickAction()
    private fun enabled(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
        ?.config?.contains(SemanticsProperties.Disabled) == false
    private fun touch(matcher: SemanticsMatcher) {
        var previous: Rect? = null
        var stable = 0
        await("Stable touch target $matcher") {
            compose.mainClock.advanceTimeByFrame()
            val node = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
            val bounds = node?.boundsInRoot
            val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().map { it.boundsInRoot }
            val scrolls = generateSequence(node?.parent) { it.parent }.filter {
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange) || it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
            }.map { it.boundsInRoot }
            val ready = node != null && bounds != null && bounds.width > 0 && bounds.height > 0 &&
                roots.any { it.contains(bounds.center) } && scrolls.all { it.contains(bounds.center) } && enabled(matcher)
            stable = if (ready && bounds == previous) stable + 1 else 0
            previous = bounds
            stable >= 2
        }
        compose.onNode(matcher).assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
    }
    private fun await(label: String, condition: () -> Boolean) {
        try { compose.waitUntil(15_000, condition) }
        catch (failure: ComposeTimeoutException) {
            throw AssertionError("Overview fixture: $label, clock=${compose.mainClock.currentTime}, offsets=$overviewOffsets, " +
                "denied=${denied.get()}, mutations=${mutations.get()}\n${compose.onRoot().printToString()}", failure)
        }
    }
    private fun assertScope() {
        assertEquals(0, mutations.get()); assertEquals(0, denied.get())
        compose.onAllNodesWithText("999999", substring = true).assertCountEquals(0)
    }
    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return reject()
            if (request.method != "GET") { mutations.incrementAndGet(); return reject() }
            if (url.encodedPath != "/rest/v1/$fixtureTable" || request.getHeader("Authorization") != "Bearer overview-device-access" ||
                request.getHeader("apikey") != "public-test-key" || url.queryParameter("user_id") != "eq.$OWNER" ||
                url.queryParameter("limit") != "500") return reject()
            val offset = url.queryParameter("offset")?.toIntOrNull()?.takeIf { it >= 0 } ?: return reject()
            val category = url.queryParameter("category")?.removePrefix("eq.")
            if (category == null) {
                if (url.queryParameter("order") != "id.asc") return reject()
                overviewOffsets.add(offset)
                if (offset == 500 && failSecondPage.get()) return json(503, JSONObject())
            } else if (url.queryParameter("order") != "created_at.desc" || category !in listOf("magic", "libri")) return reject()
            val selected = rows.filter { it.getString("user_id") == OWNER && (category == null || it.getString("category") == category) }
                .sortedBy { it.getString("id") }.drop(offset).take(500)
            return json(200, JSONArray(selected))
        }
    }
    private fun reject(): MockResponse { denied.incrementAndGet(); return json(403, JSONObject()) }
    private fun json(code: Int, body: Any) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun card(index: Int, category: String, quantity: Int, purchase: String, current: String?, currency: String) = base(index, category, quantity)
        .put("name", "Carta $index").put("purchase_price", BigDecimal(purchase)).put("current_price", current?.let(::BigDecimal) ?: JSONObject.NULL)
        .put("currency", currency).put("condition", "near_mint").put("language", "EN").put("purchase_date", "2026-01-01")
    private fun book(index: Int, category: String, quantity: Int, purchase: String?, resale: String?) = base(index, category, quantity)
        .put("title", "Titolo utente $index").put("api_id", "api-$index").put("cover_image", JSONObject.NULL)
        .put("purchase_price", purchase?.let(::BigDecimal) ?: JSONObject.NULL).put("reselling_value", resale?.let(::BigDecimal) ?: JSONObject.NULL)
    private fun base(index: Int, category: String, quantity: Int) = JSONObject().put("id", "38000000-0000-4000-8000-${index.toString().padStart(12, '0')}")
        .put("user_id", OWNER).put("category", category).put("quantity", quantity).put("created_at", "2026-01-01T00:00:00Z")
    private companion object {
        const val OWNER = "38000000-0000-4000-8000-999999999991"
        const val FOREIGN = "38000000-0000-4000-8000-999999999992"
    }
}
