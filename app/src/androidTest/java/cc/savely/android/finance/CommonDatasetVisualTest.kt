package cc.savely.android.finance

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.visual.CommonVisualArtifacts
import cc.savely.android.visual.CommonVisualContent
import cc.savely.android.visual.CommonVisualProfile
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production screens, local TLS only, master dataset shared with the web comparison.
 * Screenshots are evidence to inspect, not an automatic declaration of visual parity.
 */
@RunWith(AndroidJUnit4::class)
class CommonDatasetVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private lateinit var finance: JSONObject
    private val writes = AtomicInteger()
    private val rejected = AtomicInteger()

    @Before fun setup() {
        val master = InstrumentationRegistry.getInstrumentation().context.assets
            .open("static-common-dataset.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        assertEquals("2026-06-15T12:00:00+02:00", master.getString("clock"))
        assertEquals("Europe/Rome", master.getString("timezone"))
        finance = master.getJSONObject("finance")
        val certificate = HeldCertificate.Builder().commonName("Savely common dataset local TLS")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            dispatcher = readOnlyDispatcher()
            start()
        }
        client = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                check(request.url.isHttps && request.url.host in setOf("localhost", "127.0.0.1") && request.url.port == server.port) {
                    "Visual fixtures forbid external traffic"
                }
                chain.proceed(request)
            }.build()
        val url = server.url("/").toString()
        api = SupabaseApi(BackendConfig(url, "public-fixture-key", url), MemorySessionStore(
            Session("common-dataset-access", "common-dataset-refresh", Long.MAX_VALUE, OWNER, "visual@example.invalid")), client)
    }

    @After fun teardown() {
        try {
            assertEquals("Read-only screenshots must not issue any mutation", 0, writes.get())
            assertEquals("Every read must match the local table and owner contract", 0, rejected.get())
        } finally {
            if (::api.isInitialized) api.close()
            if (::server.isInitialized) server.shutdown()
            if (::client.isInitialized) {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun dashboardCurrentPeriodAndWholeMonthBudgetAreVisible() = profiles("dashboard") { english, profile ->
        val statistics = hasContentDescription(if (english) "Statistics EUR" else "Statistiche EUR")
        scroll(statistics)
        compose.onNode(statistics).assertTextContains("180,00", substring = true)
            .assertTextContains("120,00", substring = true).assertTextContains("30,00", substring = true)
            .assertTextContains("90,00", substring = true).assertTextContains("75%", substring = true)
        capture("dashboard", "metrics", profile)
        visibleAmount(if (english) "Total expenses:" else "Totale spese:", "30,00")
        capture("dashboard", "category-donut", profile)
        val spending = text(if (english) "Spending in budgeted categories:" else "Spesa delle categorie con budget:")
        scroll(spending)
        compose.onNode(spending).assertTextContains("40,00", substring = true)
        visibleAmount(if (english) "Available:" else "Disponibile:", "20,00")
        capture("dashboard", "full-month-budget", profile)
    }

    @Test fun transactionsUseMasterRecordsAndCategorySearch() = profiles("transactions") { english, profile ->
        // The transaction page's month filter is a full calendar month, including future rows.
        scroll(text(if (english) "4 transactions" else "4 transazioni"))
        scroll(text("Cena"))
        compose.onNodeWithText("Cena").assertIsDisplayed()
        assertTrue(compose.onAllNodes(text("−30,00")).fetchSemanticsNodes().isNotEmpty())
        capture("transactions", "record", profile)
        list().performScrollToIndex(0)
        val search = if (english) "Search description or category" else "Cerca descrizione o categoria"
        scroll(text(search))
        compose.onNodeWithText(search).performTextInput("CIBO")
        scroll(text(if (english) "2 transactions" else "2 transazioni"))
        scroll(text("Spesa futura"))
        assertTrue(compose.onAllNodes(text("−10,00")).fetchSemanticsNodes().isNotEmpty())
        capture("transactions", "category-filter", profile)
        assertEquals(0, writes.get())
    }

    @Test fun budgetGlobalMedianAndDifferenceAreVisible() = profiles("budget") { english, profile ->
        val summary = hasContentDescription(if (english) "Budget summary EUR" else "Riepilogo budget EUR")
        scroll(summary)
        compose.onNode(summary).assertTextContains("60,00", substring = true)
            .assertTextContains("40,00", substring = true).assertTextContains("-20,00", substring = true)
        capture("budget", "global-summary", profile)
    }

    @Test fun chartsAppliedRangeShowsRangeTotalsNotLifetimeBalance() = profiles("charts") { english, profile ->
        scroll(text(if (english) "Period: Year" else "Periodo: Anno"))
        compose.onNodeWithText(if (english) "Period: Year" else "Periodo: Anno").performClick()
        compose.onNodeWithText(if (english) "Between dates" else "Tra date").performClick()
        val from = if (english) "From" else "Dal"
        val to = if (english) "To" else "Al"
        scroll(hasText(from)); compose.onNodeWithText(from).performTextReplacement("2026-06-01")
        scroll(hasText(to)); compose.onNodeWithText(to).performTextReplacement("2026-06-15")
        scroll(text(if (english) "Apply date range" else "Applica intervallo"))
        compose.onNodeWithText(if (english) "Apply date range" else "Applica intervallo").performClick()
        scroll(hasTestTag("chart-summary-EUR"))
        val summary = compose.onNodeWithTag("chart-summary-EUR")
        summary.onChildren().filter(text("120,00")).assertCountEquals(1)
        summary.onChildren().filter(text("30,00")).assertCountEquals(1)
        summary.onChildren().filter(text("90,00")).assertCountEquals(1)
        capture("charts", "range-summary", profile)
        scroll(text(if (english) "Income and expense trends" else "Andamento entrate e uscite"))
        capture("charts", "time-graphic", profile)
        val cumulative = hasTestTag("chart-cumulative-EUR")
        scroll(cumulative)
        compose.onNode(cumulative).assertIsDisplayed()
        val cadence = hasAnyAncestor(cumulative) and text(if (english) "Cumulative interval: Daily" else "Granularità del cumulativo: Giornaliero")
        scroll(cadence)
        ensureVisible(cadence)
        compose.onNode(cadence).assertIsDisplayed()
        capture("charts", "cumulative-graphic", profile)
        val cumulativeLabel = if (english) "Cumulative" else "Cumulativo"
        val firstPoint = hasAnyAncestor(cumulative) and text(if (english) "01 Jun 2026" else "01 giu 2026") and text(cumulativeLabel)
        scroll(firstPoint)
        ensureVisible(firstPoint)
        compose.onNode(firstPoint).assertIsDisplayed().assertTextContains("120,00", substring = true)
            .assertTextContains("€", substring = true)
        val finalPoint = hasAnyAncestor(cumulative) and text(if (english) "15 Jun 2026" else "15 giu 2026") and text(cumulativeLabel)
        scroll(finalPoint)
        ensureVisible(finalPoint)
        compose.onNode(finalPoint).assertIsDisplayed().assertTextContains("90,00", substring = true)
            .assertTextContains("€", substring = true)
        assertFullyVisible(finalPoint)
        capture("charts", "cumulative-terminal-point", profile)
    }

    @Test fun portfolioKeepsCurrenciesAndShowsCurrentHistoryEndpoint() = profiles("portfolio") { english, profile ->
        visibleAmount(if (english) "Open positions" else "Posizioni aperte", "300,00")
        capture("portfolio", "eur-summary", profile)
        visibleAmount(if (english) "Open positions" else "Posizioni aperte", "25,00")
        capture("portfolio", "usd-summary", profile)
        scroll(text(if (english) "Investment history" else "Andamento investimenti"))
        // The final reconstructed date uses the saved current prices, not historical market quotes.
        visibleAmount(if (english) "Value:" else "Valore:", "300,00")
        if (english) {
            assertHistoryPickerButtons()
            visibleAmount("Value:", "300,00")
        }
        capture("portfolio", "reconstructed-history", profile)
    }

    @Test fun netWorthSeparatesCurrentCurrencyTotalsFromRawServerHistory() = profiles("net-worth") { english, profile ->
        scroll(text("315,00"))
        compose.onNode(text("315,00")).assertIsDisplayed()
        capture("net-worth", "eur-current", profile)
        visibleAmount(if (english) "Investment profit" else "Profitto investimenti", "5,00")
        capture("net-worth", "usd-current", profile)
        val latest = text(if (english) "Latest recorded value:" else "Ultimo valore registrato:")
        scroll(latest)
        ensureVisible(latest)
        compose.onNode(latest).assertTextContains(if (english) "-39.00" else "-39,00", substring = true)
        val raw = compose.onNode(latest).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue("Server snapshots must not acquire a currency symbol", !raw.contains('€') && !raw.contains("USD"))
        capture("net-worth", "raw-server-history", profile)
    }

    @Test fun insightsRenderLiteralSavingAndNegativeMilestoneValues() = profiles("insights") { english, profile ->
        val saving = text(if (english) "Great saving month: EUR" else "Ottimo mese di risparmio: EUR")
        scroll(saving)
        val savingMessage = text("+211%")
        compose.onNode(savingMessage).assertTextContains("140,00", substring = true)
            .assertTextContains("45,00", substring = true)
        capture("insights", "saving", profile)
        val milestone = text(if (english) "New high in the value recorded by the server" else "Nuovo massimo del valore registrato dal server")
        scroll(milestone)
        val message = text(if (english) "The snapshot on 2026-06-15" else "La snapshot del 2026-06-15")
        compose.onNode(message).assertTextContains("-39,00", substring = true)
        capture("insights", "milestone", profile)
    }

    private fun profiles(section: String, verify: (Boolean, CommonVisualProfile) -> Unit) {
        val active = mutableStateOf(CommonVisualProfile.IT_LIGHT)
        compose.setContent {
            key(active.value) {
                CommonVisualContent(active.value) {
                    FinanceScreen(api, section, Modifier.fillMaxSize(), accountKey = OWNER, today = TODAY)
                }
            }
        }
        listOf(CommonVisualProfile.IT_LIGHT, CommonVisualProfile.EN_DARK_LARGE).forEach { profile ->
            compose.runOnIdle { active.value = profile }
            val english = profile == CommonVisualProfile.EN_DARK_LARGE
            val updated = if (english) "Last updated" else "Ultimo aggiornamento"
            compose.waitUntil(20_000) { compose.onAllNodes(text(updated)).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            assertFiniteVisibleNumbers()
            capture(section, "header", profile)
            verify(english, profile)
            assertEquals("No mutation is permitted while capturing a profile", 0, writes.get())
        }
    }

    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun text(value: String) = hasText(value, substring = true)
    private fun scroll(matcher: SemanticsMatcher) {
        list().performScrollToNode(matcher)
        compose.waitForIdle()
    }
    private fun ensureVisible(matcher: SemanticsMatcher) {
        // Adjust only asserted text rows inside tall items. ScrollBy has no fling and
        // each bounded adjustment drains before reading the next layout coordinates.
        repeat(32) {
            var previousSample = ""
            var stableSamples = 0
            compose.waitUntil(5_000) {
                val node = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
                val sample = node?.let { "${it.id}/${it.positionInRoot}/${it.size}" } ?: "uncomposed"
                stableSamples = if (sample == previousSample) stableSamples + 1 else 0
                previousSample = sample
                stableSamples >= 3
            }
            val node = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
            if (node == null) {
                // Lazy items may be disposed between layout samples; relocate the
                // item, then recompute the target rather than using stale bounds.
                scroll(matcher)
                return@repeat
            }
            val viewport = compose.onNodeWithTag(CommonVisualArtifacts.VIEWPORT).fetchSemanticsNode().boundsInRoot
            val position = node.positionInRoot
            val target = Rect(position.x, position.y, position.x + node.size.width, position.y + node.size.height)
            check(target.width <= viewport.width && target.height <= viewport.height) {
                "Asserted row is larger than the capture viewport: $matcher, target=$target, viewport=$viewport"
            }
            if (compose.onNode(matcher).isDisplayed() && target.left >= viewport.left && target.right <= viewport.right &&
                target.top >= viewport.top && target.bottom <= viewport.bottom) return
            val delta = (target.center.y - viewport.center.y).coerceIn(-viewport.height / 2f, viewport.height / 2f)
            list().performSemanticsAction(SemanticsActions.ScrollBy) { action ->
                action(0f, delta)
            }
            compose.waitForIdle()
        }
        compose.waitForIdle()
        assertFullyVisible(matcher)
    }
    private fun assertFullyVisible(matcher: SemanticsMatcher) {
        compose.onNode(matcher).assertIsDisplayed()
        val node = compose.onNode(matcher).fetchSemanticsNode()
        val viewport = compose.onNodeWithTag(CommonVisualArtifacts.VIEWPORT).fetchSemanticsNode().boundsInRoot
        val position = node.positionInRoot
        val target = Rect(position.x, position.y, position.x + node.size.width, position.y + node.size.height)
        assertTrue("Unclipped row must fit entirely inside capture viewport: $matcher, target=$target, viewport=$viewport",
            target.left >= viewport.left && target.right <= viewport.right &&
                target.top >= viewport.top && target.bottom <= viewport.bottom)
    }
    private fun visibleAmount(label: String, amount: String) {
        val matcher = text(label) and text(amount)
        scroll(matcher)
        ensureVisible(matcher)
        compose.onNode(matcher).assertTextContains(amount, substring = true).assertIsDisplayed()
    }
    private fun assertHistoryPickerButtons() {
        val previous = hasTestTag("history-point-previous")
        val next = hasTestTag("history-point-next")
        listOf(previous to "Previous point", next to "Next point").forEach { (matcher, label) ->
            scroll(matcher)
            ensureVisible(matcher)
            val button = compose.onNode(matcher).assertIsDisplayed().assertTextEquals(label)
            val width = button.fetchSemanticsNode().size.width
            val viewportWidth = compose.onNodeWithTag(CommonVisualArtifacts.VIEWPORT).fetchSemanticsNode().size.width
            assertTrue("$label must occupy at least 70% of the large-font viewport", width >= viewportWidth * 0.7f)
        }
        compose.onNode(next).assertIsNotEnabled()
        scroll(previous)
        ensureVisible(previous)
        compose.onNode(previous).assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
        scroll(next)
        ensureVisible(next)
        compose.onNode(next).assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
        compose.onNode(next).assertIsNotEnabled()
    }
    private fun capture(section: String, panel: String, profile: CommonVisualProfile) {
        compose.waitForIdle()
        if (section == "charts") {
            compose.waitUntil(15_000) {
                compose.onNodeWithTag(CommonVisualArtifacts.VIEWPORT).fetchSemanticsNode().boundsInWindow.top >= 0f
            }
            compose.waitForIdle()
        }
        assertFiniteVisibleNumbers()
        val suffix = if (profile == CommonVisualProfile.IT_LIGHT) "it-light-100" else "en-dark-200"
        CommonVisualArtifacts.capture(compose, "finance-$section-$suffix-$panel", profile, clockInjected = true)
    }
    private fun assertFiniteVisibleNumbers() {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        val bad = Regex("(?i)(?:\\bNaN\\b|\\bInfinity\\b|∞)")
        nodes.forEach { node ->
            node.config[SemanticsProperties.Text].forEach { value -> assertTrue("Nonfinite financial display: ${value.text}", !bad.containsMatchIn(value.text)) }
        }
    }

    private fun readOnlyDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.method != "GET") {
                writes.incrementAndGet()
                return MockResponse().setResponseCode(403).setBody("{\"code\":\"READ_ONLY_FIXTURE\"}")
            }
            val url = request.requestUrl!!
            val table = url.encodedPath.removePrefix("/rest/v1/")
            val source = when (table) {
                "profiles" -> JSONArray().put(JSONObject().put("user_id", OWNER)
                    .put("default_currency", "EUR").put("permissions", JSONObject().put("couple_expenses", false)))
                "categories" -> finance.getJSONArray("categories")
                "transactions" -> finance.getJSONArray("transactions")
                "budgets" -> finance.getJSONArray("budgets")
                "recurring_expenses" -> finance.getJSONArray("recurring")
                "portfolio_assets" -> finance.getJSONArray("assets")
                "net_worth_snapshots" -> finance.getJSONArray("snapshots")
                "asset_price_history" -> finance.getJSONArray("priceHistory")
                "tgc_cards", "manual_price_updates" -> JSONArray()
                else -> null
            }
            if (source == null || url.queryParameter("user_id") != "eq.$OWNER" ||
                request.getHeader("Authorization") != "Bearer common-dataset-access" || request.getHeader("apikey") != "public-fixture-key") {
                rejected.incrementAndGet()
                return MockResponse().setResponseCode(403).setBody("{\"code\":\"CONTRACT_REJECTED\"}")
            }
            val rows = (0 until source.length()).map { index ->
                JSONObject(source.getJSONObject(index).toString()).put("user_id", OWNER)
                    .put("created_at", "2026-06-15T10:00:00Z").apply {
                        if (table in setOf("categories", "transactions", "recurring_expenses")) put("deleted_at", JSONObject.NULL)
                    }
            }
            val orders = url.queryParameter("order").orEmpty().split(',').filter { it.isNotEmpty() }
            val ordered = rows.sortedWith(Comparator { left, right ->
                var comparison = 0
                for (order in orders) {
                    val key = order.substringBefore('.')
                    comparison = left.optString(key).compareTo(right.optString(key))
                    if (order.endsWith(".desc")) comparison = -comparison
                    if (comparison != 0) break
                }
                comparison
            })
            val offset = url.queryParameter("offset")?.toIntOrNull() ?: 0
            val limit = url.queryParameter("limit")?.toIntOrNull() ?: 500
            val page = JSONArray()
            ordered.drop(offset).take(limit).forEach { page.put(it) }
            return MockResponse().setHeader("Content-Type", "application/json").setBody(page.toString())
        }
    }

    companion object {
        private const val OWNER = "90000000-0000-4000-8000-000000000099"
        private val TODAY = LocalDate.of(2026, 6, 15)
    }
}
