package cc.savely.android.modules

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.savely.android.ui.AppLanguage
import cc.savely.android.visual.*
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Currency
import java.util.Locale
import java.util.UUID

/** Real module UI and common synthetic inputs. No external catalog, cover, price or backend writes. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class CommonModuleVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var dataset: JSONObject
    private var backend: CommonVisualBackend? = null
    private var owner = ""
    private val tables = mutableMapOf<String, JSONArray>()
    private var firePreferences: SharedPreferences? = null
    private var fireOriginal: String? = null
    private var fireSeeded = false

    @Before fun loadInputs() {
        dataset = CommonVisualData.load()
        assertEquals("Production module year filters use the device year; do not mislabel a different year as the common clock",
            OffsetDateTime.parse(dataset.getString("clock")).year, LocalDate.now().year)
    }
    @After fun cleanup() {
        try {
            backend?.let { assertEquals("Native module snapshots must remain read-only and loopback-only", 0, it.rejected.get()) }
        } finally {
            try { backend?.close() }
            finally {
                if (fireSeeded) firePreferences!!.edit().apply {
                    if (fireOriginal == null) remove(FireParameters.STORAGE_KEY)
                    else putString(FireParameters.STORAGE_KEY, fireOriginal)
                }.also { check(it.commit()) }
            }
        }
    }

    @Test fun fireItalianLightLoadsTheActualSavedFormAndItsCommonTarget() {
        open("A")
        val parameters = fireParameters()
        val preferences = seedFire(parameters)
        render("fire", CommonVisualProfile.IT_LIGHT)
        awaitText("Parametri salvati sul dispositivo per questo account.")
        capture("fire-form", CommonVisualProfile.IT_LIGHT)
        list().performScrollToNode(hasText("Rendimento nominale (%)"))
        compose.onNode(hasText("Rendimento nominale (%)") and hasSetTextAction()).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("7")))
        capture("fire-rate", CommonVisualProfile.IT_LIGHT)
        list().performScrollToNode(hasText("Obiettivo FIRE", substring = false))
        compose.onNodeWithText("Obiettivo FIRE", substring = false).assertIsDisplayed()
        compose.onNodeWithText(money("1200000", "EUR"), substring = false).assertIsDisplayed()
        capture("fire-target", CommonVisualProfile.IT_LIGHT)
        list().performScrollToNode(hasTestTag("fire-projection"))
        compose.onNodeWithTag("fire-projection").assertIsDisplayed()
        val projected = FireCalculations.standard(parameters.inputs, OffsetDateTime.parse(dataset.getString("clock")).year)
        assertChartLabels(projected.projections, projected.fireNumber, english = false)
        capture("fire-projection", CommonVisualProfile.IT_LIGHT)
        assertEquals(parameters, FireParameters.decode(preferences.getString(FireParameters.STORAGE_KEY, null)!!))
    }

    @Test fun fireBaristaItalianLightUsesTheExactCommonParametersAndRealTab() {
        open("A")
        val parameters = fireParameters("fire-barista-baseline")
        val preferences = seedFire(parameters)
        render("fire", CommonVisualProfile.IT_LIGHT)
        awaitText("Parametri salvati sul dispositivo per questo account.")
        compose.onNodeWithText("Barista", substring = false).assertIsDisplayed().performTouchInput { click(center) }
        list().performScrollToNode(hasText("Obiettivo Barista", substring = false))
        compose.onNodeWithText("Obiettivo Barista", substring = false).assertIsDisplayed()
        compose.onNodeWithText(money("600000", "EUR"), substring = false).assertIsDisplayed()
        compose.onNodeWithText("Obiettivo FIRE completo", substring = false).assertIsDisplayed()
        compose.onNodeWithText(money("900000", "EUR"), substring = false).assertIsDisplayed()
        capture("fire-barista", CommonVisualProfile.IT_LIGHT)
        assertEquals(parameters, FireParameters.decode(preferences.getString(FireParameters.STORAGE_KEY, null)!!))
    }

    @Test fun fireProjectionEnglishDarkAtTwoHundredPercentUsesTheProductionChart() {
        val inputs = fireParameters().inputs
        val year = OffsetDateTime.parse(dataset.getString("clock")).year
        val calculated = FireCalculations.standard(inputs, year)
        compose.setContent { CommonVisualContent(CommonVisualProfile.EN_DARK_LARGE) {
            // The real form measures its chart inside a LazyColumn. Keep the isolated
            // component naturally measured and scrollable at font 200%, too.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                FireProjectionChart(calculated.projections, inputs.currentSavings, calculated.fireNumber)
            }
        } }
        compose.onNodeWithText("Progress toward FIRE", substring = false).assertIsDisplayed()
        compose.onNodeWithTag("fire-progress-bar").assertExists()
        compose.onNodeWithTag("fire-projection-year").assertExists()
        CommonVisualArtifacts.capture(compose, "module-fire-chart-${CommonVisualProfile.EN_DARK_LARGE.suffix}",
            CommonVisualProfile.EN_DARK_LARGE, clockInjected = true)
        assertChartLabels(calculated.projections, calculated.fireNumber, english = true, scrollable = true)
        CommonVisualArtifacts.capture(compose, "module-fire-chart-axis-${CommonVisualProfile.EN_DARK_LARGE.suffix}",
            CommonVisualProfile.EN_DARK_LARGE, clockInjected = true)
    }

    @Test fun fumoItalianLightDisplaysSavedLegacyDailyAndMonthlyValues() = fumo(CommonVisualProfile.IT_LIGHT)
    @Test fun fumoEnglishDarkAtTwoHundredPercentPreservesLegacyValuesAndOpaqueDates() = fumo(CommonVisualProfile.EN_DARK_LARGE)

    private fun fumo(profile: CommonVisualProfile) {
        open("B")
        val input = CommonVisualData.moduleCase(dataset, "fumo-legacy-liquid")
        tables["liquido_sigaretta"] = JSONArray().put(JSONObject(input.toString()).put("id", id("liquid-legacy"))
            .put("user_id", owner).put("created_at", dataset.getString("clock")))
        render("fumo", profile)
        awaitEnabled(if (english(profile)) "Add" else "Aggiungi")
        compose.onNodeWithText(if (english(profile)) "Liquid" else "Liquido", substring = false)
            .assertIsDisplayed().performTouchInput { click(center) }
        val yearlyQuantity = "1,25 ml/${if (english(profile)) "day" else "giorno"}"
        awaitText(yearlyQuantity, substring = true)
        list().performScrollToNode(hasText(yearlyQuantity, substring = true))
        compose.onNodeWithText(yearlyQuantity, substring = true).assertIsDisplayed()
        capture("fumo-yearly", profile)
        val prefix = if (english(profile)) "20 days" else "20 giorni"
        list().performScrollToNode(hasText(prefix, substring = true))
        val detail = compose.onNodeWithText(prefix, substring = true)
        detail.assertIsDisplayed().assertTextContains("1,25", substring = true)
            .assertTextContains(money("0.63", "EUR"), substring = true)
            .assertTextContains(money("18.75", "EUR"), substring = true)
        capture("fumo-detail", profile)
        assertEquals("2026-06-01", tables.getValue("liquido_sigaretta").getJSONObject(0).getString("data_arrivo"))
        assertEquals("2026-06-11", tables.getValue("liquido_sigaretta").getJSONObject(0).getString("data_finito"))
    }

    @Test fun pokerItalianLightShowsWeightedHoursAndAppliesDealOnlyToPresentation() {
        open("A")
        val input = CommonVisualData.moduleCase(dataset, "poker-hourly-weighted")
        val rows = input.getJSONArray("rows")
        tables["poker_next_cut"] = JSONArray().put(base("next-cut").put("deal", input.getDouble("deal"))
            .put("profit_loss", "0").put("amount", "0"))
        tables["poker_hourly_earnings"] = JSONArray().apply {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                put(base("earning-$index").put("date", if (index == 0) "2026-06-01" else "2026-05-01")
                    .put("hours_played", row.getDouble("minutes") / 60).put("profit_loss", row.get("netWon"))
                    .put("net_won_ev", row.get("netWonEv")))
            }
        }
        render("poker", CommonVisualProfile.IT_LIGHT)
        awaitEnabled("Aggiorna")
        compose.onNodeWithText("Guadagno orario", substring = false).assertIsDisplayed().performTouchInput { click(center) }
        awaitEnabled("Aggiungi")
        list().performScrollToNode(hasText("Net Won ${money("90", "EUR")}", substring = true))
        val yearly = compose.onNodeWithText("Net Won ${money("90", "EUR")}", substring = true)
        yearly.assertIsDisplayed().assertTextContains("3h 00m", substring = true)
            .assertTextContains("EV ${money("120", "EUR")}", substring = true)
            .assertTextContains("Orario dopo deal ${money("16.50", "EUR")}", substring = true)
            .assertTextContains("EV/h ${money("22", "EUR")}", substring = true)
        capture("poker-weighted", CommonVisualProfile.IT_LIGHT)
        assertEquals("100", tables.getValue("poker_hourly_earnings").getJSONObject(0).get("profit_loss").toString())
    }

    @Test fun tcgItalianLightKeepsEurAndUsdSeparateInTheRealOverview() = tcg(CommonVisualProfile.IT_LIGHT)
    @Test fun tcgEnglishDarkAtTwoHundredPercentKeepsTheCurrencyCardsScrollable() = tcg(CommonVisualProfile.EN_DARK_LARGE)

    private fun tcg(profile: CommonVisualProfile) {
        open("A")
        val input = CommonVisualData.moduleCase(dataset, "tcg-mixed-currency").getJSONArray("rows")
        tables["tgc_cards"] = JSONArray().apply { for (index in 0 until input.length()) {
            val row = pricesForTransport(input.getJSONObject(index), "purchase_price", "current_price")
            put(row.put("id", id("card-${row.getString("id")}")).put("user_id", owner)
                .put("name", "Dataset ${row.getString("category")}").put("created_at", dataset.getString("clock"))
                .put("condition", "near_mint").put("language", "EN").put("purchase_date", "2026-06-15")
                .put("card_id", JSONObject.NULL).put("image_url", JSONObject.NULL))
        } }
        overview("tcg", profile)
        list().performScrollToNode(hasText(if (english(profile)) "Total pieces" else "Totale Pezzi"))
        compose.onNodeWithText("9", substring = false).assertIsDisplayed()
        assertCard("EUR", "35", "45", "10", profile)
        capture("tcg-eur", profile)
        assertCard("USD", "10", "12", "2", profile)
        capture("tcg-usd", profile)
        list().performScrollToNode(hasTestTag("tcg-game-distribution"))
        compose.onNodeWithTag("tcg-game-distribution").assertIsDisplayed()
        compose.onNodeWithContentDescription(if (english(profile)) "Distribution by Game" else "Distribuzione per Gioco")
            .assertIsDisplayed()
        val legend = if (english(profile)) listOf(
            "magic" to "Magic: 2 pieces · 22.2%", "pokemon" to "Pokémon: 3 pieces · 33.3%",
            "yugioh" to "Yu-Gi-Oh: 4 pieces · 44.4%",
        ) else listOf(
            "magic" to "Magic: 2 pezzi · 22,2%", "pokemon" to "Pokémon: 3 pezzi · 33,3%",
            "yugioh" to "Yu-Gi-Oh: 4 pezzi · 44,4%",
        )
        legend.forEach { (game, text) ->
            compose.onNodeWithTag("tcg-game-distribution-$game").performScrollTo()
                .assertTextEquals(text).assertIsDisplayed()
        }
        legend.forEach { (game, _) -> compose.onNodeWithTag("tcg-game-distribution-$game").assertIsDisplayed() }
        compose.onNodeWithContentDescription(if (english(profile)) "Distribution by Game" else "Distribuzione per Gioco")
            .assertIsDisplayed()
        capture("tcg-pie", profile)
    }

    @Test fun libraryItalianLightKeepsNullPricesAndUsesTheCommonSixPieceOverview() {
        open("B")
        val input = CommonVisualData.moduleCase(dataset, "library-null-prices").getJSONArray("rows")
        tables["library_items"] = JSONArray().apply { for (index in 0 until input.length()) {
            val row = pricesForTransport(input.getJSONObject(index), "purchase_price", "reselling_value")
            put(row.put("id", id("book-${row.getString("id")}")).put("user_id", owner)
                .put("title", "Dataset ${row.getString("category")}").put("created_at", dataset.getString("clock"))
                .put("cover_image", JSONObject.NULL).put("author", JSONObject.NULL).put("publisher", JSONObject.NULL))
        } }
        overview("libreria", CommonVisualProfile.IT_LIGHT)
        list().performScrollToNode(hasText("Totale Pezzi"))
        compose.onNodeWithText("6", substring = false).assertIsDisplayed()
        assertCard("EUR", "23.50", "24", "0.50", CommonVisualProfile.IT_LIGHT)
        capture("library-overview", CommonVisualProfile.IT_LIGHT)
        assertEquals(JSONObject.NULL, tables.getValue("library_items").getJSONObject(0).get("purchase_price"))
        assertEquals(JSONObject.NULL, tables.getValue("library_items").getJSONObject(1).get("reselling_value"))
    }

    private fun seedFire(parameters: FireParameters): SharedPreferences {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(FireParameters.preferencesName(owner), Context.MODE_PRIVATE)
        firePreferences = preferences
        // Preserve exactly the one synthetic owner's parameter key; unrelated preferences are untouched.
        fireOriginal = preferences.getString(FireParameters.STORAGE_KEY, null)
        check(preferences.edit().putString(FireParameters.STORAGE_KEY, parameters.encode()).commit())
        fireSeeded = true
        return preferences
    }
    private fun fireParameters(caseId: String = "fire-standard-baseline"): FireParameters {
        val input = CommonVisualData.moduleCase(dataset, caseId)
        return FireParameters(FireCalculations.Inputs(input.getInt("currentAge"), input.getInt("retirementAge"),
            input.getDouble("currentSavings"), input.getDouble("annualContribution"), input.getDouble("annualIncome"),
            input.getDouble("expectedReturn"), input.getDouble("inflationRate"), input.getDouble("withdrawalRate"),
            input.getDouble("annualExpenses")), input.optDouble("partTimeIncome",
                CommonVisualData.moduleCase(dataset, "fire-barista-baseline").getDouble("partTimeIncome")))
    }
    private fun english(profile: CommonVisualProfile) = profile.language == AppLanguage.ENGLISH
    private fun pricesForTransport(input: JSONObject, vararg fields: String): JSONObject = JSONObject(input.toString()).apply {
        // The shared dataset keeps exact decimal lexemes as strings. Supabase NUMERIC
        // columns arrive as JSON numbers; convert only this independent transport copy.
        fields.forEach { field ->
            require(has(field)) { "Synthetic price field missing: $field" }
            if (!isNull(field)) {
                val raw = get(field)
                require(raw is String || raw is Number) { "Synthetic price field has an invalid type: $field" }
                put(field, BigDecimal(raw.toString()))
            }
            require(isNull(field) || get(field) is Number) { "Synthetic price transport must be a JSON number: $field" }
        }
    }
    private fun assertChartLabels(points: List<FireCalculations.Projection>, target: Double,
        english: Boolean, scrollable: Boolean = false) {
        val maximum = maxOf(target, points.maxOf { maxOf(it.portfolio, it.inflationAdjusted) }, 1.0)
        val expected = listOf(
            "fire-projection-scale" to "${if (english) "Scale" else "Scala"}: ${money("0", "EUR")} – ${money(maximum.toString(), "EUR")}",
            "fire-projection-start" to points.first().let { "${it.year} · ${if (english) "age ${it.age}" else "${it.age} anni"}" },
            "fire-projection-end" to points.last().let { "${it.year} · ${if (english) "age ${it.age}" else "${it.age} anni"}" },
        )
        expected.forEach { (tag, text) ->
            val label = compose.onNodeWithTag(tag)
            if (scrollable) label.performScrollTo()
            label.assertTextEquals(text).assertIsDisplayed()
        }
        // All three labels must remain visible together in the ensuing axis screenshot.
        expected.forEach { (tag, _) -> compose.onNodeWithTag(tag).assertIsDisplayed() }
    }
    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun awaitText(text: String, substring: Boolean = false) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitEnabled(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodes(hasText(text) and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    private fun render(section: String, profile: CommonVisualProfile) {
        compose.setContent { CommonVisualContent(profile) { ModuleScreen(backend!!.api, section, Modifier.fillMaxSize()) } }
    }
    private fun overview(section: String, profile: CommonVisualProfile) {
        render(section, profile)
        awaitEnabled(if (english(profile)) "Add" else "Aggiungi")
        val overview = hasText(if (english(profile)) "Overview" else "Panoramica") and hasClickAction()
        scrollTab(overview)
        compose.onNode(overview).assertIsDisplayed().performClick().assertIsSelected()
        awaitEnabled(if (english(profile)) "Update" else "Aggiorna")
        list().performScrollToIndex(1)
        awaitText(if (english(profile)) "Total pieces" else "Totale Pezzi")
        list().performScrollToNode(hasText(if (english(profile)) "Total pieces" else "Totale Pezzi"))
    }
    private fun scrollTab(matcher: SemanticsMatcher) {
        // Tabs is a real horizontal ScrollState row, independent of the records LazyColumn.
        // Bound the gestures rather than invoking ScrollTo's synchronous coroutine bridge.
        repeat(8) {
            compose.waitForIdle()
            val node = compose.onNode(matcher).fetchSemanticsNode()
            val ancestor = generateSequence(node.parent) { it.parent }
                .first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
            val targetX = node.positionInRoot.x + node.size.width / 2f
            val viewport = ancestor.boundsInRoot
            if (compose.onNode(matcher).isDisplayed() && targetX in viewport.left..viewport.right) return
            compose.onNode(SemanticsMatcher("Module tabs scroll ancestor") { it.id == ancestor.id })
                .performTouchInput { if (targetX > viewport.center.x) swipeLeft() else swipeRight() }
        }
        throw AssertionError("Module tab remains outside the viewport: $matcher")
    }
    private fun assertCard(currency: String, investment: String, value: String, profit: String, profile: CommonVisualProfile) {
        val tag = "overview-total-$currency"
        list().performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed()
        val labels = if (english(profile)) listOf("Investment", "Value", "Profit / Loss") else listOf("Investimento", "Valore", "Profitto / Perdita")
        listOf(investment, value, profit).forEachIndexed { index, amount ->
            compose.onNode(hasText("${labels[index]}: ${money(amount, currency)}") and hasAnyAncestor(hasTestTag(tag))).assertExists()
        }
    }
    private fun capture(screen: String, profile: CommonVisualProfile) =
        CommonVisualArtifacts.capture(compose, "module-$screen-${profile.suffix}", profile)
    private fun money(amount: String, currency: String): String = NumberFormat.getCurrencyInstance(Locale.ITALY)
        .apply { this.currency = Currency.getInstance(currency); minimumFractionDigits = 2; maximumFractionDigits = 2 }
        .format(BigDecimal(amount))
    private fun id(ref: String) = UUID.nameUUIDFromBytes("common-module-$ref".toByteArray(Charsets.UTF_8)).toString()
    private fun base(ref: String) = JSONObject().put("id", id(ref)).put("user_id", owner).put("created_at", dataset.getString("clock"))
    private fun open(alias: String) {
        owner = dataset.getJSONObject("account").getJSONObject("actors").getJSONObject(alias).getString("id")
        backend = CommonVisualBackend(dataset, alias, ::response)
    }
    private fun response(request: RecordedRequest): okhttp3.mockwebserver.MockResponse? {
        val url = request.requestUrl ?: return null
        if (!url.encodedPath.startsWith("/rest/v1/") || request.method != "GET" || url.queryParameter("user_id") != "eq.$owner") return null
        val table = url.encodedPath.removePrefix("/rest/v1/")
        if (table !in setOf("transactions", "poker_next_cut", "poker_manual_expenses", "poker_hourly_earnings", "poker_rakeback",
                "cbd", "thc", "liquido_sigaretta", "tgc_cards", "library_items")) return null
        val rows = tables[table] ?: JSONArray()
        val category = url.queryParameter("category")?.removePrefix("eq.")
        val selected = (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.getString("user_id") == owner && (category == null || it.optString("category") == category) }
            .sortedBy { it.getString("id") }
        val offset = url.queryParameter("offset")?.toIntOrNull() ?: 0
        val limit = url.queryParameter("limit")?.toIntOrNull() ?: 500
        if (offset < 0 || limit !in 1..1000) return null
        return CommonVisualBackend.json(JSONArray(selected.drop(offset).take(limit)).toString())
    }
}
