package cc.savely.android.finance

import androidx.compose.foundation.layout.Box
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
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Real presentation components with synthetic records; no API, client, credentials or transport. */
@RunWith(AndroidJUnit4::class)
class NetWorthQualificationTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeout: Timeout = Timeout.seconds(180)
    private val today = LocalDate.of(2026, 6, 15)
    private var hideInput: () -> Unit = {}

    private fun snapshot(id: String, day: String, value: String, components: JSONObject? = null) =
        FinanceSnapshot(id, LocalDate.parse(day), BigDecimal(value), components)
    private fun components(cashflow: String, profit: String, estate: String) = JSONObject()
        .put("cashflow", cashflow).put("portfolioPL", profit).put("realEstateDiscounted", estate)

    @Test fun allPeriodsAndNegativePreviousComparisonUseActualSnapshotsAndPreserveInvalidRange() {
        val data = listOf(snapshot("old", "2025-12-31", "-200"), snapshot("april", "2026-04-30", "-160"),
            snapshot("may-first", "2026-05-17", "-120"), snapshot("may-last", "2026-05-31", "-100"),
            snapshot("june-first", "2026-06-01", "-90", components("-100", "10", "0")),
            snapshot("june-last", "2026-06-15", "-50", components("-80", "30", "0")),
            snapshot("future", "2026-07-01", "25"))
        profiles({ NetWorthHistoryPanel(data.reversed(), today) }) { en, profile ->
            metric(en, "Ultimo valore registrato", "Latest recorded value", "25,00", "25.00")
            metric(en, "Variazione del periodo", "Period change", "+225,00", "+225.00")
            text(if (en) "Percentage change: 112,5%" else "Variazione percentuale: 112,5%")
            text(if (en) "Minimum -200.00 · Maximum 25.00" else "Minimo -200,00 · Massimo 25,00")
            choose(period(en, "Tutto lo storico", "All history"), if (en) "Current year" else "Anno corrente")
            text(selected(en, "2026-01-01", "2026-06-15"))
            metric(en, "Ultimo valore registrato", "Latest recorded value", "-50,00", "-50.00")
            metric(en, "Variazione del periodo", "Period change", "+110,00", "+110.00")
            text(if (en) "Percentage change: 68,8%" else "Variazione percentuale: 68,8%")
            choose(period(en, "Anno corrente", "Current year"), if (en) "Previous month" else "Mese precedente")
            text(selected(en, "2026-05-01", "2026-05-31"))
            metric(en, "Ultimo valore registrato", "Latest recorded value", "-100,00", "-100.00")
            metric(en, "Variazione del periodo", "Period change", "+20,00", "+20.00")
            text(if (en) "Percentage change: 16,7%" else "Variazione percentuale: 16,7%")
            text(if (en) "Comparison unavailable: the previous period requires at least two available points."
                else "Confronto non disponibile: il periodo precedente richiede almeno due punti disponibili.")
            choose(period(en, "Mese precedente", "Previous month"), if (en) "Custom" else "Personalizzato")
            apply(en, "2026-06-15", "2026-06-01")
            text(selected(en, "2026-06-01", "2026-06-15"))
            metric(en, "Variazione del periodo", "Period change", "+40,00", "+40.00")
            text(if (en) "Percentage change: 44,4%" else "Variazione percentuale: 44,4%")
            text(if (en) "Minimum -90.00 · Maximum -50.00" else "Minimo -90,00 · Massimo -50,00")
            capture("negative-period-summary", profile, hasText(if (en) "Minimum -90.00 · Maximum -50.00" else "Minimo -90,00 · Massimo -50,00"))
            text(if (en) "Previous period: 2026-05-17 – 2026-05-31" else "Periodo precedente: 2026-05-17 – 2026-05-31")
            metric(en, "Variazione nel periodo precedente", "Change in the previous period", "20,00", "20.00")
            metric(en, "Differenza rispetto all’ultimo valore del periodo precedente", "Difference from the final value of the previous period", "50,00", "50.00")
            text(if (en) "50% compared with the previous period" else "50% rispetto al periodo precedente")
            metric(en, "Valore registrato", "Recorded value", "-50,00", "-50.00")
            metric(en, "Cashflow registrato", "Recorded cashflow", "-80,00", "-80.00")
            metric(en, "Profitto investimenti registrato", "Recorded investment profit", "30,00", "30.00")
            metric(en, "Immobili scontati registrati", "Recorded discounted real estate", "0,00", "0.00")
            capture("negative-snapshot-details", profile, hasText(if (en) "Recorded discounted real estate: 0.00" else "Immobili scontati registrati: 0,00"))
            click(hasTestTag("history-point-previous"))
            metric(en, "Valore registrato", "Recorded value", "-90,00", "-90.00")
            metric(en, "Cashflow registrato", "Recorded cashflow", "-100,00", "-100.00")
            compose.onNodeWithTag("history-point-previous").assertIsNotEnabled()
            click(hasTestTag("history-point-next"))
            metric(en, "Valore registrato", "Recorded value", "-50,00", "-50.00")
            apply(en, "2026-02-30", "2026-06-15")
            text(if (en) "Enter valid dates in YYYY-MM-DD format" else "Inserisci date valide nel formato YYYY-MM-DD")
            text(selected(en, "2026-06-01", "2026-06-15"))
            metric(en, "Ultimo valore registrato", "Latest recorded value", "-50,00", "-50.00")
            apply(en, "2026-08-01", "2026-08-31")
            text(selected(en, "2026-08-01", "2026-08-31"))
            text(if (en) "No points available in the selected period." else "Nessun punto disponibile nel periodo selezionato.")
            compose.onAllNodesWithText(if (en) "Latest recorded value:" else "Ultimo valore registrato:", substring = true).assertCountEquals(0)
            assertNoRawCurrency()
        }
    }

    @Test fun emptyZeroSingleAndInvalidComponentsStayDistinctInBothLanguages() {
        val rows = mutableStateOf(emptyList<FinanceSnapshot>())
        profiles({ NetWorthHistoryPanel(rows.value, today) }) { en, profile ->
            compose.runOnIdle { rows.value = emptyList() }
            compose.waitForIdle()
            val empty = if (en) "No server-recorded history available." else "Nessuno storico registrato dal server disponibile."
            text(empty)
            capture("empty-history", profile, hasText(empty))
            compose.onAllNodesWithTag("history-point-previous").assertCountEquals(0)
            compose.runOnIdle { rows.value = listOf(snapshot("zero", "2026-06-01", "0"), snapshot("ten", "2026-06-15", "10")) }
            compose.waitForIdle()
            metric(en, "Variazione del periodo", "Period change", "+10,00", "+10.00")
            text(if (en) "Percentage unavailable: the first recorded value is zero." else "Percentuale non disponibile: primo valore registrato zero.")
            compose.runOnIdle { rows.value = listOf(snapshot("partial", "2026-06-15", "-0.305",
                JSONObject().put("cashflow", "0").put("portfolioPL", JSONObject.NULL))) }
            compose.waitForIdle()
            metric(en, "Valore registrato", "Recorded value", "-0,31", "-0.31")
            metric(en, "Cashflow registrato", "Recorded cashflow", "0,00", "0.00")
            text(if (en) "Recorded investment profit: Not recorded" else "Profitto investimenti registrato: Non registrato")
            text(if (en) "Recorded discounted real estate: Not recorded" else "Immobili scontati registrati: Non registrato")
            text(if (en) "Only one record: a zero change does not measure a trend." else "Una sola registrazione: la variazione zero non misura una tendenza.")
            compose.onAllNodesWithTag("history-point-previous").assertCountEquals(0)
            compose.runOnIdle { rows.value = listOf(snapshot("malformed", "2026-06-15", "-0.305", JSONObject().put("cashflow", "bad"))) }
            compose.waitForIdle()
            val invalid = if (en) "Invalid snapshot components." else "Componenti dello snapshot non validi."
            text(invalid)
            metric(en, "Valore registrato", "Recorded value", "-0,31", "-0.31")
            capture("invalid-components", profile, hasText(invalid))
            compose.onAllNodesWithText(if (en) "Components not recorded for this date." else "Componenti non registrati per questa data.").assertCountEquals(0)
            compose.runOnIdle { rows.value = listOf(snapshot("missing", "2026-06-15", "-0.305")) }
            compose.waitForIdle()
            text(if (en) "Components not recorded for this date." else "Componenti non registrati per questa data.")
            compose.onAllNodesWithText(invalid).assertCountEquals(0)
            assertNoRawCurrency()
        }
    }

    @Test fun currentComponentsStayPerCurrencyAndNeverRelabelOrReconcileTheRawServerValue() {
        fun transaction(id: String, type: TransactionType, amount: String, currency: String) =
            FinanceTransaction(id, type, BigDecimal(amount), currency, today, id, null)
        fun asset(id: String, type: FinanceAssetType, quantity: String, purchase: String, current: String, currency: String = "EUR", closed: Boolean = false) =
            FinanceAsset(id, id, id, type, BigDecimal(quantity), BigDecimal(purchase), BigDecimal(current), currency,
                today.minusDays(4), if (closed) today else null, if (closed) BigDecimal("999") else null, "")
        val data = FinanceData(transactions = listOf(transaction("eur-in", TransactionType.INCOME, "100", "EUR"),
            transaction("eur-out", TransactionType.EXPENSE, "200", "EUR"), transaction("usd-in", TransactionType.INCOME, "100", "USD"),
            transaction("usd-out", TransactionType.EXPENSE, "50", "USD")), assets = listOf(
                asset("stock", FinanceAssetType.STOCK, "2", "100", "140"), asset("estate", FinanceAssetType.REAL_ESTATE, "1", "100", "200"),
                asset("cash", FinanceAssetType.CASH, "1000", "1", "0.5", "USD"), asset("other", FinanceAssetType.OTHER, "1", "1", "999"),
                asset("closed", FinanceAssetType.STOCK, "100", "1", "999", closed = true), asset("etf", FinanceAssetType.ETF, "2", "10", "20", "USD")),
            tcgCards = listOf(FinanceTcgCard("excluded-tcg", "magic", 100, BigDecimal.ONE, BigDecimal("999"), "EUR")),
            snapshots = listOf(snapshot("raw", "2026-06-15", "-39")))
        profiles({
            netWorthComponents(data.transactions, data.assets).forEach { value ->
                Box(Modifier.testTag("v07-current-${value.currency}")) { NetWorthCard(value) }
            }
            Box(Modifier.testTag("v07-server")) { NetWorthHistoryPanel(data.snapshots, today) }
        }) { en, profile ->
            fun current(currency: String, value: String) = hasText(value) and hasAnyAncestor(hasTestTag("v07-current-$currency"))
            val eur = "\u00a0€"
            show(current("EUR", "130,00$eur"))
            show(current("EUR", if (en) "Balance -100,00$eur" else "Saldo -100,00$eur"))
            show(current("EUR", if (en) "Investment profit 80,00$eur" else "Profitto investimenti 80,00$eur"))
            val estate = current("EUR", if (en) "Real estate at 75% 150,00$eur" else "Immobili al 75% 150,00$eur")
            show(estate); capture("current-eur-components", profile, estate)
            val usd = "\u00a0${Currency.getInstance("USD").getSymbol(Locale.ITALY)}"
            show(current("USD", "70,00$usd"))
            show(current("USD", if (en) "Balance 50,00$usd" else "Saldo 50,00$usd"))
            show(current("USD", if (en) "Investment profit 20,00$usd" else "Profitto investimenti 20,00$usd"))
            val raw = if (en) "Recorded value: -39.00" else "Valore registrato: -39,00"
            text(raw)
            val inServer = hasAnyAncestor(hasTestTag("v07-server"))
            compose.onAllNodes(inServer and hasText("€", substring = true)).assertCountEquals(0)
            compose.onAllNodes(inServer and hasText("USD", substring = true)).assertCountEquals(0)
            compose.onAllNodes(inServer and hasText("130", substring = true)).assertCountEquals(0)
            compose.onAllNodes(inServer and hasText("70,00", substring = true)).assertCountEquals(0)
        }
    }

    private fun profiles(content: @Composable () -> Unit, verify: (Boolean, CommonVisualProfile) -> Unit) {
        val active = mutableStateOf<CommonVisualProfile?>(null)
        compose.setContent {
            active.value?.let { profile -> key(profile) {
                CommonVisualContent(profile) {
                    val focus = LocalFocusManager.current
                    val keyboard = LocalSoftwareKeyboardController.current
                    androidx.compose.runtime.SideEffect { hideInput = { focus.clearFocus(force = true); keyboard?.hide() } }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("v07-scroll")) { content() }
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

    private fun period(en: Boolean, italian: String, english: String) = if (en) "History period: $english" else "Periodo storico: $italian"
    private fun selected(en: Boolean, from: String, to: String) = if (en) "Selected period: $from – $to" else "Periodo selezionato: $from – $to"
    private fun metric(en: Boolean, italian: String, english: String, itValue: String, enValue: String) = text(if (en) "$english: $enValue" else "$italian: $itValue")
    private fun text(value: String) = show(hasText(value))

    private fun choose(control: String, option: String) {
        click(hasText(control) and hasClickAction())
        compose.onNode(hasText(option) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun apply(en: Boolean, from: String, to: String) {
        compose.onNodeWithText(if (en) "From" else "Dal").performScrollTo().performTextReplacement(from)
        compose.onNodeWithText(if (en) "To" else "Al").performScrollTo().performTextReplacement(to)
        click(hasText(if (en) "Apply date range" else "Applica intervallo") and hasClickAction())
        compose.runOnIdle { hideInput() }
        compose.waitForIdle()
    }

    private fun assertNoRawCurrency() {
        compose.onAllNodesWithText("€", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("USD", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("$", substring = true).assertCountEquals(0)
    }

    private fun click(matcher: SemanticsMatcher) {
        show(matcher)
        compose.onNode(matcher).assertIsEnabled().performClick()
        compose.waitForIdle()
    }

    private fun bounds(matcher: SemanticsMatcher): Rect {
        val node = compose.onNode(matcher).fetchSemanticsNode()
        return Rect(node.positionInRoot.x, node.positionInRoot.y, node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)
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
            compose.onNodeWithTag("v07-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            compose.waitForIdle()
        }
        val target = bounds(matcher)
        val viewport = bounds(hasTestTag(CommonVisualArtifacts.VIEWPORT))
        assertTrue("Unclipped target required: $target / $viewport", target.left >= viewport.left && target.right <= viewport.right &&
            target.top >= viewport.top && target.bottom <= viewport.bottom)
        compose.onNode(matcher).assertIsDisplayed()
    }

    private fun capture(name: String, profile: CommonVisualProfile, matcher: SemanticsMatcher) {
        show(matcher)
        CommonVisualArtifacts.capture(compose, "v07-$name-${profile.suffix}", profile, clockInjected = true)
    }
}
