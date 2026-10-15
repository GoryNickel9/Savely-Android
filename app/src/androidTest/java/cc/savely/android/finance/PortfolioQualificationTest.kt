package cc.savely.android.finance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.visual.CommonVisualArtifacts
import cc.savely.android.visual.CommonVisualContent
import cc.savely.android.visual.CommonVisualProfile
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Production widgets, fixed synthetic positions and no API/client/session/network transport. */
@RunWith(AndroidJUnit4::class)
class PortfolioQualificationTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeout: Timeout = Timeout.seconds(180)
    private val today = LocalDate.of(2026, 6, 15)

    private fun asset(id: String, type: FinanceAssetType = FinanceAssetType.STOCK, quantity: String = "1",
        purchase: String = "100", current: String = "100", currency: String = "EUR",
        closed: Boolean = false, name: String = id) = FinanceAsset(id, name, "SAME", type,
        BigDecimal(quantity), BigDecimal(purchase), BigDecimal(current), currency, today.minusDays(4),
        if (closed) today.minusDays(1) else null, if (closed) BigDecimal("999") else null, "Do not copy these old notes")

    @Test fun allocationPiesPreserveExactLegendsAndCurrencyWithLargeEnglishText() {
        val data = FinanceData(assets = listOf(asset("stock"),
            asset("cash", FinanceAssetType.CASH, "100", "1", "0.5", "USD"),
            asset("estate", FinanceAssetType.REAL_ESTATE, purchase = "200", current = "200"),
            asset("other", FinanceAssetType.OTHER, purchase = "50", current = "50"),
            asset("usd", purchase = "500", current = "500", currency = "USD"),
            asset("closed", purchase = "999", current = "999", closed = true)),
            tcgCards = listOf(FinanceTcgCard("priced", "magic", 4, BigDecimal.TEN, BigDecimal("25"), "EUR"),
                FinanceTcgCard("unpriced", "magic", 100, BigDecimal.TEN, null, "EUR"),
                FinanceTcgCard("foreign", "magic", 100, BigDecimal.TEN, BigDecimal("25"), "USD")))
        val updates = AtomicInteger()
        profiles({ PortfolioSummary(data, saving = false, updatePrices = { updates.incrementAndGet() }) }) { en, profile ->
            show(hasText(if (en) "Invested, including real estate: ${eur("300,00")}" else "Investito, immobili inclusi: ${eur("300,00")}"))
            show(hasText(if (en) "Invested in instruments: ${eur("100,00")}" else "Investito in strumenti: ${eur("100,00")}"))
            listOf(Triple("stock", "100,00", "20%"), Triple("cash", "50,00", "10%"),
                Triple("real_estate", "200,00", "40%"), Triple("other", "50,00", "10%"),
                Triple("tcg", "100,00", "20%")).forEach { (type, amount, percentage) ->
                legend("portfolio-type-legend-EUR-$type", eur(amount), percentage)
            }
            legend("portfolio-liquidity-legend-EUR-liquid", eur("150,00"), "30%")
            legend("portfolio-liquidity-legend-EUR-illiquid", eur("350,00"), "70%")
            captureAt("type-pie", profile, hasTestTag("portfolio-type-pie-EUR"))
            captureAt("liquidity-pie", profile, hasTestTag("portfolio-liquidity-pie-EUR"))
            captureAt("allocation-details", profile, hasTestTag("portfolio-type-legend-EUR-real_estate"))
            val usdLegend = hasTestTag("portfolio-type-legend-USD-stock")
            show(usdLegend)
            compose.onNode(usdLegend).assertTextContains(usd("500,00"), substring = true).assertTextContains("100%", substring = true)
            assertEquals("Reading either profile must not request price updates", 0, updates.get())
        }
    }

    @Test fun recordedPartialCoverageAndReconstructedPricesKeepAllLiteralPointsAndUsdIndependent() {
        val assets = listOf(asset("a", quantity = "2", current = "140"),
            asset("b", quantity = "3", purchase = "10", current = "20"),
            asset("usd", purchase = "500", current = "600", currency = "USD"))
        fun price(id: String, asset: String, day: Int, value: String) =
            FinancePriceHistory(id, asset, today.withDayOfMonth(day), BigDecimal(value))
        val history = listOf(price("a11", "a", 11, "110"), price("a13", "a", 13, "120"),
            price("a15", "a", 15, "150"), price("b12", "b", 12, "12"), price("b13", "b", 13, "15"),
            price("usd13", "usd", 13, "550"))
        profiles({ PortfolioHistoryPanel(assets, history, today) }) { en, profile ->
            point(en, "340,00", "230,00", "110,00", recorded = 0, interpolated = 0, current = 2)
            captureAt("reconstructed-endpoint", profile, hasText(valueLabel(en, eur("340,00"))))
            listOf(Triple("312,50", "82,50", 0), Triple("285,00", "55,00", 2),
                Triple("256,00", "26,00", 1), Triple("250,00", "20,00", 1)).forEach { (value, profit, recorded) ->
                click(hasTestTag("history-point-previous"))
                point(en, value, "230,00", profit, recorded, 2 - recorded, 0)
            }
            choose(if (en) "Series: Reconstructed history" else "Serie: Andamento ricostruito",
                if (en) "Recorded prices" else "Prezzi registrati")
            point(en, "300,00", "200,00", "100,00", 1, 0, 0)
            click(hasTestTag("history-point-previous"))
            point(en, "285,00", "230,00", "55,00", 2, 0, 0)
            captureAt("recorded-partial-coverage", profile, hasText(coverage(en, 2, 0, 0)))
            click(hasTestTag("history-point-previous"))
            point(en, "36,00", "30,00", "6,00", 1, 0, 0)
            click(hasTestTag("history-point-previous"))
            point(en, "220,00", "200,00", "20,00", 1, 0, 0)
            compose.onNodeWithTag("history-point-previous").assertIsNotEnabled()
            choose(if (en) "Currency: EUR" else "Valuta: EUR", "USD")
            show(hasText(valueLabel(en, usd("550,00"))))
            show(hasText(if (en) "Invested: ${usd("500,00")}" else "Investito: ${usd("500,00")}"))
            show(hasText(coverage(en, 1, 0, 0)))
            show(hasText(if (en) "Only one point: a zero change does not measure a trend." else "Un solo punto: la variazione zero non misura una tendenza."))
        }
    }

    @Test fun closedEtfUsdReuseCopiesOnlyIdentityAndPreservesNewPurchaseDraft() {
        val saves = AtomicInteger()
        val assets = listOf(asset("stock-eur", name = "QA Stock EUR"),
            asset("etf-eur", FinanceAssetType.ETF, closed = true, name = "QA ETF EUR"),
            asset("etf-usd", FinanceAssetType.ETF, "999", "777", "888", "USD", true, "QA ETF USD"))
        val purchaseDay = LocalDate.now().toString()
        profiles({ AssetEditor(null, FinanceData(assets = assets), false, null, {}, { saves.incrementAndGet() }) }) { en, _ ->
            input(if (en) "Quantity" else "Quantità", "2")
            input(if (en) "Purchase price per unit" else "Prezzo acquisto unitario", "30")
            input(if (en) "Optional current price" else "Prezzo corrente facoltativo", "31")
            input(if (en) "Optional notes" else "Note facoltative", "QA new notes {{currency}}")
            compose.runOnIdle { hideInput() }
            compose.waitForIdle()
            val control = if (en) "Existing instrument: New instrument" else "Strumento esistente: Nuovo strumento"
            compose.onNodeWithText(control).performScrollTo().performClick()
            compose.onNodeWithText("QA ETF USD · SAME · ETF · USD").performScrollTo().performClick()
            compose.waitForIdle()
            listOf(if (en) "Type: ETF" else "Tipo: ETF", if (en) "Currency: USD" else "Valuta: USD",
                if (en) "Purchase: $purchaseDay" else "Acquisto: $purchaseDay").forEach {
                compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
            }
            field(if (en) "Name" else "Nome", "QA ETF USD")
            field(if (en) "Optional symbol" else "Simbolo facoltativo", "SAME")
            field(if (en) "Quantity" else "Quantità", "2")
            field(if (en) "Purchase price per unit" else "Prezzo acquisto unitario", "30")
            field(if (en) "Optional current price" else "Prezzo corrente facoltativo", "31")
            field(if (en) "Optional notes" else "Note facoltative", "QA new notes {{currency}}")
            assertEquals("Choosing a prior closed instrument never submits or merges a purchase", 0, saves.get())
        }
    }

    private var hideInput: () -> Unit = {}

    private fun profiles(content: @Composable () -> Unit, verify: (Boolean, CommonVisualProfile) -> Unit) {
        val active = mutableStateOf<CommonVisualProfile?>(null)
        compose.setContent {
            active.value?.let { profile -> key(profile) {
                CommonVisualContent(profile) {
                    val focus = LocalFocusManager.current
                    val keyboard = LocalSoftwareKeyboardController.current
                    androidx.compose.runtime.SideEffect { hideInput = { focus.clearFocus(force = true); keyboard?.hide() } }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("v06-scroll")) { content() }
                }
            } }
        }
        listOf(CommonVisualProfile.IT_LIGHT, CommonVisualProfile.EN_DARK_LARGE).forEach { profile ->
            compose.runOnIdle { active.value = profile }
            compose.waitForIdle()
            try { verify(profile == CommonVisualProfile.EN_DARK_LARGE, profile) }
            finally {
                compose.runOnIdle { hideInput(); active.value = null }
                compose.waitForIdle()
                hideInput = {}
            }
        }
    }

    private fun eur(amount: String) = "$amount\u00a0€"
    private fun usd(amount: String) = "$amount\u00a0${Currency.getInstance("USD").getSymbol(Locale.ITALY)}"
    private fun valueLabel(en: Boolean, amount: String) = if (en) "Value: $amount" else "Valore: $amount"
    private fun coverage(en: Boolean, recorded: Int, interpolated: Int, current: Int) = if (en)
        "Coverage: $recorded recorded prices, $interpolated interpolated prices, $current current prices."
        else "Copertura: $recorded prezzi registrati, $interpolated interpolati, $current prezzi correnti."

    private fun point(en: Boolean, value: String, invested: String, profit: String, recorded: Int, interpolated: Int, current: Int) {
        show(hasText(valueLabel(en, eur(value))))
        show(hasText(if (en) "Invested: ${eur(invested)}" else "Investito: ${eur(invested)}"))
        show(hasText(if (en) "Profit: ${eur(profit)}" else "Profitto: ${eur(profit)}"))
        show(hasText(coverage(en, recorded, interpolated, current)))
    }

    private fun legend(tag: String, amount: String, percentage: String) {
        show(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertTextContains(amount, substring = true).assertTextContains(" · $percentage", substring = true)
    }

    private fun choose(control: String, option: String) {
        click(hasText(control) and hasClickAction())
        compose.onNode(hasText(option) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun click(matcher: SemanticsMatcher) {
        show(matcher)
        compose.onNode(matcher).assertIsEnabled().performClick()
        compose.waitForIdle()
    }

    private fun input(label: String, value: String) {
        compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
        compose.waitForIdle()
    }

    private fun field(label: String, expected: String) {
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertTextContains(expected, substring = true)
    }

    private fun bounds(matcher: SemanticsMatcher): Rect {
        val node = compose.onNode(matcher).fetchSemanticsNode()
        return Rect(node.positionInRoot.x, node.positionInRoot.y,
            node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)
    }

    private fun show(matcher: SemanticsMatcher) {
        compose.onNode(matcher).performScrollTo()
        compose.waitForIdle()
        repeat(8) {
            val target = bounds(matcher)
            val viewport = bounds(hasTestTag(CommonVisualArtifacts.VIEWPORT))
            assertTrue("Target must fit the viewport: $target / $viewport", target.width <= viewport.width && target.height <= viewport.height)
            if (target.left >= viewport.left && target.right <= viewport.right && target.top >= viewport.top && target.bottom <= viewport.bottom) {
                compose.onNode(matcher).assertIsDisplayed()
                return
            }
            val delta = (target.center.y - viewport.center.y).coerceIn(-viewport.height / 2f, viewport.height / 2f)
            compose.onNodeWithTag("v06-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            compose.waitForIdle()
        }
        val target = bounds(matcher)
        val viewport = bounds(hasTestTag(CommonVisualArtifacts.VIEWPORT))
        assertTrue("Target must be completely visible: $target / $viewport", target.left >= viewport.left && target.right <= viewport.right &&
            target.top >= viewport.top && target.bottom <= viewport.bottom)
        compose.onNode(matcher).assertIsDisplayed()
    }

    private fun captureAt(name: String, profile: CommonVisualProfile, matcher: SemanticsMatcher) {
        show(matcher)
        CommonVisualArtifacts.capture(compose, "v06-$name-${profile.suffix}", profile, clockInjected = true)
    }
}
