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
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.savely.android.visual.CommonVisualArtifacts
import cc.savely.android.visual.CommonVisualBackend
import cc.savely.android.visual.CommonVisualContent
import cc.savely.android.visual.CommonVisualData
import cc.savely.android.visual.CommonVisualProfile
import java.time.LocalDate
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Real production Insights UI, synthetic owner-scoped TLS reads, no external service or mutation. */
@RunWith(AndroidJUnit4::class)
class InsightsQualificationTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeout: Timeout = Timeout.seconds(180)
    private val today = LocalDate.parse("2026-06-15")

    @Test fun sixDetectorsShowLiteralAmountsSeverityAndOpaqueNamesInItalianAndLargeEnglish() {
        profiles { english, profile, _ ->
            counts(english, 6, 4, 0, 2)
            card("spending-anomaly-EUR-$ANOMALY", if (english) "Unusual expense in QA Anomaly: EUR" else "Spesa anomala in QA Anomaly: EUR",
                english, warning = true, expected = listOf("70,00\u00a0€", "+40%", "50,00\u00a0€"))
            capture("anomaly", profile)
            card("budget-$BUDGET", if (english) "Budget exceeded: QA Budget" else "Budget superato: QA Budget",
                english, warning = true, expected = listOf("101,00\u00a0€", "100,00\u00a0€"))
            card("recurring-price-EUR:v08 netflix", if (english) "Recurring expense increased: V08 Netflix" else "Spesa ricorrente aumentata: V08 Netflix",
                english, warning = true, expected = listOf("11,00\u00a0€", "10%", "10,00\u00a0€"))
            card("loss-$POSITION", if (english) "Position at a loss: QA Position {{percent}}" else "Posizione in perdita: QA Position {{percent}}",
                english, warning = true, expected = listOf(if (english) "V08: -15% compared with the purchase price." else "V08: -15% rispetto al prezzo di acquisto."))
            capture("loss", profile)
            card("category-trend-EUR-$TREND", if (english) "QA Trend trending upward: EUR" else "QA Trend in trend crescente: EUR",
                english, warning = false, expected = listOf("25%", "50,00\u00a0€", "40,00\u00a0€"))
            capture("trend", profile)
            card("new-recurring-EUR:v08 netflix", if (english) "Possible recurring expense: V08 Netflix" else "Possibile spesa ricorrente: V08 Netflix",
                english, warning = false, expected = listOf(if (english) "a monthly frequency" else "una cadenza mensile", "10,00\u00a0€",
                    if (english) "(3 occurrences)" else "(3 occorrenze)"))
        }
    }

    @Test fun severityFiltersShowTheirActualSubsetAndAnEmptyPositiveFilterThenRestoreAll() {
        profiles { english, profile, _ ->
            counts(english, 6, 4, 0, 2)
            filter(if (english) "Warning (4)" else "Attenzione (4)")
            val warnings = setOf("spending-anomaly-EUR-$ANOMALY", "budget-$BUDGET", "recurring-price-EUR:v08 netflix", "loss-$POSITION")
            val info = setOf("category-trend-EUR-$TREND", "new-recurring-EUR:v08 netflix")
            assertEquals("The whole warning list, including offscreen rows, must match the selected severity", warnings, renderedInsightIds())
            filter("Info (2)")
            assertEquals(info, renderedInsightIds())
            card("new-recurring-EUR:v08 netflix", if (english) "Possible recurring expense: V08 Netflix" else "Possibile spesa ricorrente: V08 Netflix",
                english, warning = false, expected = listOf("10,00\u00a0€", if (english) "3 occurrences" else "3 occorrenze"))
            capture("info-filter", profile)
            filter(if (english) "Positive (0)" else "Positivi (0)")
            assertEquals(emptySet<String>(), renderedInsightIds())
            val empty = hasText(if (english) "No signals in the selected filter" else "Nessun segnale nel filtro selezionato")
            show(empty)
            compose.onAllNodes(hasContentDescription("Insight ", substring = true)).assertCountEquals(0)
            capture("empty-positive-filter", profile)
            filter(if (english) "All (6)" else "Tutti (6)")
            assertEquals(warnings + info, renderedInsightIds())
            showTitle("loss-$POSITION")
            showTitle("new-recurring-EUR:v08 netflix")
            counts(english, 6, 4, 0, 2)
        }
    }

    @Test fun failedSnapshotReadIsAnErrorAndOnlyAnExplicitRetryCanProduceTheValidEmptyState() {
        profiles(empty = true, snapshotsFailInitially = true) { english, profile, fixture ->
            val error = if (english) "Service temporarily unavailable. Try again." else "Servizio temporaneamente non disponibile. Riprova."
            show(hasText(error))
            compose.onNodeWithText(if (english) "All (0)" else "Tutti (0)").assertDoesNotExist()
            compose.onNodeWithText(if (english) "No signals in the eight available checks" else "Nessun segnale negli otto controlli disponibili").assertDoesNotExist()
            capture("read-error", profile)
            assertEquals("No automatic retry is permitted on a snapshot 503", 1, fixture.failedSnapshots.get())
            fixture.snapshotsFail.set(false)
            click(hasText(if (english) "Try again" else "Riprova") and hasClickAction())
            awaitLoaded(english)
            counts(english, 0, 0, 0, 0)
            show(hasText(if (english) "No signals in the eight available checks" else "Nessun segnale negli otto controlli disponibili"))
            compose.onAllNodes(hasContentDescription("Insight ", substring = true)).assertCountEquals(0)
            assertEquals("A successful explicit read retry must not be confused with a replayed write", 1, fixture.failedSnapshots.get())
        }
    }

    private class Fixture(val backend: CommonVisualBackend, val snapshotsFail: AtomicBoolean,
        val failedSnapshots: AtomicInteger, val violations: AtomicInteger, val reads: AtomicInteger)
    private data class Scene(val profile: CommonVisualProfile, val fixture: Fixture)

    private fun profiles(empty: Boolean = false, snapshotsFailInitially: Boolean = false,
        verify: (Boolean, CommonVisualProfile, Fixture) -> Unit) {
        val active = mutableStateOf<Scene?>(null)
        compose.setContent {
            active.value?.let { scene -> key(scene) {
                CommonVisualContent(scene.profile) {
                    FinanceScreen(scene.fixture.backend.api, "insights", Modifier.fillMaxSize(),
                        accountKey = scene.fixture.backend.session.userId, today = today)
                }
            } }
        }
        listOf(CommonVisualProfile.IT_LIGHT, CommonVisualProfile.EN_DARK_LARGE).forEach { profile ->
            val fixture = fixture(empty, snapshotsFailInitially)
            val english = profile == CommonVisualProfile.EN_DARK_LARGE
            try {
                compose.runOnIdle { active.value = Scene(profile, fixture) }
                try {
                    if (snapshotsFailInitially) awaitText(if (english) "Try again" else "Riprova") else awaitLoaded(english)
                } catch (failure: ComposeTimeoutException) {
                    diagnostic("initial-${profile.suffix}", JSONObject().put("reads", fixture.reads.get())
                        .put("failedSnapshots", fixture.failedSnapshots.get()).put("violations", fixture.violations.get())
                        .put("rejected", fixture.backend.rejected.get())
                        .put("retryNodes", compose.onAllNodesWithText(if (english) "Try again" else "Riprova").fetchSemanticsNodes().size), profile)
                    throw failure
                }
                verify(english, profile, fixture)
            } finally {
                compose.runOnIdle { active.value = null }
                compose.waitForIdle()
                try {
                    assertEquals("No non-owner read or mutation is allowed", 0, fixture.violations.get())
                    assertEquals("External or unsupported transport is forbidden", 0, fixture.backend.rejected.get())
                    assertTrue("Production Insights must actually read its data", fixture.reads.get() > 0)
                } finally { fixture.backend.close() }
            }
        }
    }

    private fun fixture(empty: Boolean, initiallyFail: Boolean): Fixture {
        val master = CommonVisualData.load()
        val owner = master.getJSONObject("account").getJSONObject("actors").getJSONObject("A").getString("id")
        val sources = sources(owner, empty)
        val failing = AtomicBoolean(initiallyFail)
        val failed = AtomicInteger()
        val violations = AtomicInteger()
        val reads = AtomicInteger()
        val backend = CommonVisualBackend(master, "A") { request: RecordedRequest ->
            val url = request.requestUrl!!
            val table = url.encodedPath.removePrefix("/rest/v1/")
            val source = sources[table]
            if (request.method != "GET" || source == null || url.queryParameter("user_id") != "eq.$owner" ||
                (url.queryParameter("limit")?.toIntOrNull() ?: -1) !in 1..500 ||
                (url.queryParameter("offset")?.toIntOrNull() ?: 0) !in 0..500) {
                violations.incrementAndGet()
                CommonVisualBackend.json("{}", 403)
            } else {
                reads.incrementAndGet()
                if (table == "net_worth_snapshots" && failing.get()) {
                    failed.incrementAndGet()
                    CommonVisualBackend.json("{\"code\":\"SYNTHETIC_UNAVAILABLE\"}", 503)
                } else {
                    val offset = url.queryParameter("offset")?.toIntOrNull() ?: 0
                    val limit = url.queryParameter("limit")!!.toInt()
                    val page = JSONArray()
                    (offset until minOf(offset + limit, source.length())).forEach { page.put(source.getJSONObject(it)) }
                    CommonVisualBackend.json(page.toString())
                }
            }
        }
        return Fixture(backend, failing, failed, violations, reads)
    }

    private fun sources(owner: String, empty: Boolean): Map<String, JSONArray> {
        fun category(id: String, name: String) = JSONObject().put("id", id).put("user_id", owner).put("type", "expense")
            .put("name", name).put("icon", "").put("color", "#22c55e").put("deleted_at", JSONObject.NULL)
        fun transaction(id: Int, date: String, amount: String, category: String? = null, description: String = "V08 row $id") = JSONObject()
            .put("id", "88000000-0000-4000-8000-${id.toString().padStart(12, '0')}").put("user_id", owner)
            .put("type", "expense").put("date", date).put("amount", amount).put("currency", "EUR")
            .put("category_id", category ?: JSONObject.NULL).put("description", description).put("exchange_rate_eur", "1")
            .put("deleted_at", JSONObject.NULL)
        val transactions = JSONArray()
        val categories = JSONArray()
        val budgets = JSONArray()
        val assets = JSONArray()
        if (!empty) {
            categories.put(category(ANOMALY, "QA Anomaly")).put(category(BUDGET_CATEGORY, "QA Budget")).put(category(TREND, "QA Trend"))
            listOf(transaction(1, "2026-05-10", "50", ANOMALY), transaction(2, "2026-06-10", "70", ANOMALY),
                transaction(3, "2026-06-10", "101", BUDGET_CATEGORY), transaction(4, "2025-10-10", "40", TREND),
                transaction(5, "2025-11-10", "40", TREND), transaction(6, "2025-12-10", "40", TREND),
                transaction(7, "2026-04-10", "50", TREND), transaction(8, "2026-05-10", "50", TREND),
                transaction(9, "2026-04-10", "10", description = "V08 Netflix"), transaction(10, "2026-05-10", "10", description = "V08 Netflix"),
                transaction(11, "2026-06-10", "11", description = "V08 Netflix")).forEach { transactions.put(it) }
            budgets.put(JSONObject().put("id", BUDGET).put("user_id", owner).put("category_id", BUDGET_CATEGORY).put("amount", "100").put("currency", "EUR"))
            assets.put(JSONObject().put("id", POSITION).put("user_id", owner).put("name", "QA Position {{percent}}").put("symbol", "V08")
                .put("type", "stock").put("quantity", "1").put("purchase_price", "200").put("current_price", "169").put("currency", "EUR")
                .put("purchase_date", "2026-05-15").put("sold_at", JSONObject.NULL).put("sold_price", JSONObject.NULL).put("notes", JSONObject.NULL))
        }
        return mapOf("profiles" to JSONArray().put(JSONObject().put("user_id", owner).put("default_currency", "EUR")),
            "categories" to categories, "transactions" to transactions, "budgets" to budgets, "portfolio_assets" to assets,
            "recurring_expenses" to JSONArray(), "net_worth_snapshots" to JSONArray())
    }

    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun awaitText(value: String) = compose.waitUntil(15_000) {
        compose.onAllNodes(hasText(value, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitLoaded(english: Boolean) = awaitText(if (english) "Last updated" else "Ultimo aggiornamento")

    private fun counts(english: Boolean, all: Int, warning: Int, positive: Int, info: Int) {
        listOf("${if (english) "All" else "Tutti"} ($all)", "${if (english) "Warning" else "Attenzione"} ($warning)",
            "${if (english) "Positive" else "Positivi"} ($positive)", "Info ($info)").forEach { label ->
            show(hasText(label))
            compose.onNodeWithText(label).assertExists()
        }
    }

    private fun filter(label: String) {
        click(hasText(label) and hasClickAction())
        compose.onNodeWithText(label).assertIsSelected()
    }
    private fun click(matcher: SemanticsMatcher) {
        show(matcher)
        compose.onNode(matcher).assertIsEnabled().performClick()
        compose.waitForIdle()
    }

    private fun card(id: String, title: String, english: Boolean, warning: Boolean, expected: List<String>) {
        showTitle(id, title)
        val card = compose.onNodeWithContentDescription("Insight $id")
        card.assertTextContains(title, substring = true)
            .assertTextContains(if (warning) { if (english) "Warning" else "Attenzione" } else "Info", substring = true)
        expected.forEach { card.assertTextContains(it, substring = true) }
        val scope = hasAnyAncestor(hasContentDescription("Insight $id"))
        compose.onNode(hasText(title) and scope, useUnmergedTree = true).assertIsDisplayed()
        val messages = compose.onAllNodes(hasText(title).not() and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and scope,
            useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("The production card must expose its rendered text", messages.isNotEmpty())
        // Large-font paragraphs may be taller than the viewport; they must wrap, not overflow horizontally.
        messages.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(SemanticsMatcher("node ${node.id}") { it.id == node.id }, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { layout ->
                // Wrap-content badges retain a larger paragraph constraint; measure the shaped lines.
                val overflow = (0 until layout.lineCount).any { line ->
                    layout.getLineLeft(line) < -1f || layout.getLineRight(line) > layout.size.width + 1f || layout.isLineEllipsized(line)
                }
                if (overflow) diagnostic("overflow-${id.replace(':', '-')}-${if (english) "en" else "it"}",
                    JSONObject().put("text", layout.layoutInput.text.text).put("width", layout.size.width)
                        .put("paragraphWidth", layout.multiParagraph.width.toDouble())
                        .put("constraints", layout.layoutInput.constraints.toString())
                        .put("lines", JSONArray().apply { repeat(layout.lineCount) { index ->
                            put(JSONObject().put("left", layout.getLineLeft(index).toDouble()).put("right", layout.getLineRight(index).toDouble()))
                        } }), if (english) CommonVisualProfile.EN_DARK_LARGE else CommonVisualProfile.IT_LIGHT)
                assertFalse("Insights shaped lines must fit the rendered text width without ellipsis", overflow)
            }
        }
    }

    private fun diagnostic(name: String, details: JSONObject, profile: CommonVisualProfile) {
        val safeName = name.lowercase(java.util.Locale.ROOT)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "common-dataset-visual")
            .apply { check(mkdirs() || isDirectory) }
        CommonVisualArtifacts.capture(compose, "v08-diagnostic-$safeName", profile, clockInjected = true)
        File(directory, "v08-diagnostic-$safeName-details.json").writeText(details.toString(2), Charsets.UTF_8)
    }

    private fun showTitle(id: String, expectedTitle: String? = null) {
        val card = hasContentDescription("Insight $id")
        list().performScrollToNode(card)
        compose.waitForIdle()
        val title = hasAnyAncestor(card) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        // The first text descendant is the title; the following text is the severity/message.
        val text = expectedTitle ?: compose.onAllNodes(title, useUnmergedTree = true).fetchSemanticsNodes().first()
            .config[SemanticsProperties.Text].single().text
        show(hasText(text) and hasAnyAncestor(card), unmerged = true, relocate = card)
    }
    private fun renderedInsightIds(): Set<String> {
        list().performScrollToIndex(0)
        compose.waitForIdle()
        val observed = linkedSetOf<String>()
        repeat(40) {
            compose.onAllNodes(hasContentDescription("Insight ", substring = true)).fetchSemanticsNodes().forEach { node ->
                node.config[SemanticsProperties.ContentDescription].filter { it.startsWith("Insight ") }
                    .forEach { observed.add(it.removePrefix("Insight ")) }
            }
            val before = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val viewport = compose.onNodeWithTag(CommonVisualArtifacts.VIEWPORT).fetchSemanticsNode().boundsInRoot
            list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, viewport.height * .6f) }
            compose.waitForIdle()
            val after = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            if (after == before) return observed
        }
        throw AssertionError("Insights list did not reach its end within the bounded fixture scroll")
    }

    private fun show(matcher: SemanticsMatcher, unmerged: Boolean = false, relocate: SemanticsMatcher = matcher) {
        list().performScrollToNode(relocate)
        compose.waitForIdle()
        compose.onNode(matcher, useUnmergedTree = unmerged).performScrollTo()
        compose.waitForIdle()
        repeat(12) {
            val node = compose.onNode(matcher, useUnmergedTree = unmerged).fetchSemanticsNode()
            val target = Rect(node.positionInRoot.x, node.positionInRoot.y,
                node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)
            val viewport = compose.onNodeWithTag(CommonVisualArtifacts.VIEWPORT).fetchSemanticsNode().boundsInRoot
            assertTrue("The asserted target must fit the actual viewport", target.width <= viewport.width && target.height <= viewport.height)
            if (target.left >= viewport.left && target.right <= viewport.right && target.top >= viewport.top && target.bottom <= viewport.bottom) {
                compose.onNode(matcher, useUnmergedTree = unmerged).assertIsDisplayed()
                return
            }
            val delta = (target.center.y - viewport.center.y).coerceIn(-viewport.height / 2, viewport.height / 2)
            list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            compose.waitForIdle()
        }
        throw AssertionError("The asserted Insights target remains clipped: $matcher")
    }

    private fun capture(state: String, profile: CommonVisualProfile) =
        CommonVisualArtifacts.capture(compose, "v08-$state-${profile.suffix}", profile, clockInjected = true)

    companion object {
        private const val ANOMALY = "88000000-0000-4000-8000-000000000101"
        private const val BUDGET_CATEGORY = "88000000-0000-4000-8000-000000000102"
        private const val TREND = "88000000-0000-4000-8000-000000000103"
        private const val BUDGET = "88000000-0000-4000-8000-000000000104"
        private const val POSITION = "88000000-0000-4000-8000-000000000105"
    }
}
