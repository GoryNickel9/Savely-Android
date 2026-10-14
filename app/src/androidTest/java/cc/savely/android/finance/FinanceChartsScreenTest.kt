package cc.savely.android.finance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.ui.SavelyTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic component fixtures only: no session, repository, server or backend writes. */
@RunWith(AndroidJUnit4::class)
class FinanceChartsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.parse("2024-03-15")
    private val food = FinanceCategory("food", "Categoria QA", TransactionType.EXPENSE)
    private val travel = FinanceCategory("travel", "Viaggi QA", TransactionType.EXPENSE)
    private val salary = FinanceCategory("salary", "Reddito QA", TransactionType.INCOME)
    private fun row(date: String, amount: String, type: TransactionType = TransactionType.EXPENSE,
        currency: String = "EUR", category: String? = "food") = FinanceTransaction(
        "$date-$amount-$currency-$type-$category", type, amount.toBigDecimal(), currency, LocalDate.parse(date), "untouched QA data", category)
    private fun period(from: String, to: String) = DashboardRange(LocalDate.parse(from), LocalDate.parse(to))

    @Composable private fun Fixture(content: @Composable () -> Unit) {
        // No localization preferences are changed; the provider's default is Italian.
        SavelyTheme("light") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("charts-scroll")) { content() }
        }
    }

    private fun choose(currentLabel: String, option: String) {
        compose.onNodeWithText(currentLabel).performScrollTo().performClick()
        compose.onNode(hasText(option) and hasClickAction()).performClick()
    }

    private fun panelText(text: String, panel: String): SemanticsNodeInteraction =
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(panel)))

    private fun selectVisibleAnalysis(label: String) {
        val chip = compose.onNode(hasText(label) and hasClickAction())
        chip.performScrollTo()
        repeat(8) {
            compose.waitForIdle()
            val node = chip.fetchSemanticsNode()
            val target = Rect(node.positionInRoot.x, node.positionInRoot.y,
                node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)
            val viewport = compose.onNodeWithTag("charts-scroll").fetchSemanticsNode().boundsInRoot
            if (target.left >= viewport.left && target.right <= viewport.right &&
                target.top >= viewport.top && target.bottom <= viewport.bottom) {
                chip.assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
                compose.waitForIdle()
                chip.assertIsSelected()
                return
            }
            val delta = (target.center.y - viewport.center.y).coerceIn(-viewport.height / 2f, viewport.height / 2f)
            compose.onNodeWithTag("charts-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        }
        throw AssertionError("Analysis chip must be fully visible before its tap: $label")
    }

    @Test fun restoredFiltersGraphicsAndInvalidDraftKeepTheLastAppliedRange() {
        val restoration = StateRestorationTester(compose)
        val data = FinanceData(categories = listOf(food, travel), transactions = listOf(
            row("2024-02-29", "10"), row("2024-03-01", "20"),
            row("2024-03-01", "999", currency = "USD", category = "travel")))
        restoration.setContent { Fixture { FinanceChartsScreen(data, today = today) } }
        choose("Periodo: Anno", "Tra date")
        compose.onNodeWithText("Dal").performScrollTo().performTextReplacement("2024-02-29")
        compose.onNodeWithText("Al").performScrollTo().performTextReplacement("2024-03-01")
        compose.onNodeWithText("Applica intervallo").performScrollTo().performClick()
        compose.onNodeWithText("Analisi Uscite").performScrollTo().performClick()
        choose("Valuta: Tutte le valute", "EUR")
        choose("Categoria: Tutte le categorie", "Categoria QA")
        choose("Granularità: Mensile", "Settimanale")
        panelText("Linea", "chart-time-EUR").performScrollTo().performClick()
        panelText("Torta", "chart-category-EUR-expense").performScrollTo().performClick()
        compose.onNodeWithText("Dal").performScrollTo().performTextReplacement("2024-02-30")
        compose.onNodeWithText("Applica intervallo").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Periodo selezionato: 2024-02-29 – 2024-03-01").assertExists()
        compose.onNodeWithText("Inserisci date valide nel formato YYYY-MM-DD").assertExists()
        compose.onNodeWithText("Dal").assertTextContains("2024-02-30")
        compose.onNodeWithText("Analisi Uscite").assertIsSelected()
        compose.onNodeWithText("Valuta: EUR").assertExists()
        compose.onNodeWithText("Categoria: Categoria QA").assertExists()
        compose.onNodeWithText("Granularità: Settimanale").assertExists()
        panelText("Linea", "chart-time-EUR").assertIsSelected()
        panelText("Torta", "chart-category-EUR-expense").assertIsSelected()
        panelText("Uscite 30,00\u00a0€", "chart-summary-EUR").assertExists()
        compose.onNodeWithTag("chart-time-USD").assertDoesNotExist()
    }

    @Test fun refreshedRemovedCategoryFallsBackToAllAndDoesNotReturnWhenTheCategoryReappears() {
        val original = FinanceData(categories = listOf(food, travel), transactions = listOf(
            row("2024-03-01", "0.10"), row("2024-03-02", "0.20"), row("2024-03-03", "3", category = "travel")))
        val data = mutableStateOf(original)
        compose.setContent { Fixture { FinanceChartsScreen(data.value, today = today) } }
        choose("Categoria: Tutte le categorie", "Categoria QA")
        panelText("Uscite ${formatMoney("0.30".toBigDecimal(), "EUR")}", "chart-summary-EUR").performScrollTo().assertIsDisplayed()

        compose.runOnIdle { data.value = original.copy(categories = listOf(food.copy(name = "Categoria QA aggiornata"), travel)) }
        compose.onNodeWithText("Categoria: Categoria QA aggiornata").performScrollTo().assertIsDisplayed()
        panelText("Uscite ${formatMoney("0.30".toBigDecimal(), "EUR")}", "chart-summary-EUR").assertExists()

        val detached = original.transactions.map { if (it.categoryId == food.id) it.copy(categoryId = null) else it }
        compose.runOnIdle { data.value = original.copy(categories = listOf(travel), transactions = detached) }
        compose.onNodeWithText("Categoria: Tutte le categorie").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Categoria: Seleziona").assertDoesNotExist()
        panelText("Uscite ${formatMoney("3.30".toBigDecimal(), "EUR")}", "chart-summary-EUR").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Periodo selezionato: 2024-01-01 – 2024-12-31").assertExists()

        compose.runOnIdle { data.value = original }
        compose.onNodeWithText("Categoria: Tutte le categorie").performScrollTo().assertIsDisplayed()
        panelText("Uscite ${formatMoney("3.30".toBigDecimal(), "EUR")}", "chart-summary-EUR").assertExists()
        choose("Categoria: Tutte le categorie", "Senza categoria")
        compose.runOnIdle { data.value = original.copy(categories = emptyList(), transactions = detached) }
        compose.onNodeWithText("Categoria: Senza categoria").performScrollTo().assertIsDisplayed()
        panelText("Uscite ${formatMoney("0.30".toBigDecimal(), "EUR")}", "chart-summary-EUR").assertExists()
    }

    @Test fun refreshedMissingCurrencyFallsBackToAllAndDoesNotReturnWhenTheCurrencyReappears() {
        val eur = row("2024-03-01", "0.10")
        val usd = row("2024-03-02", "7.25", currency = "USD")
        val original = FinanceData(categories = listOf(food), transactions = listOf(eur, usd))
        val data = mutableStateOf(original)
        compose.setContent { Fixture { FinanceChartsScreen(data.value, today = today) } }
        choose("Valuta: Tutte le valute", "EUR")
        panelText("Uscite ${formatMoney("0.10".toBigDecimal(), "EUR")}", "chart-summary-EUR").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chart-time-USD").assertDoesNotExist()

        compose.runOnIdle { data.value = original.copy(transactions = listOf(eur.copy(amount = "0.20".toBigDecimal()), usd)) }
        compose.onNodeWithText("Valuta: EUR").performScrollTo().assertIsDisplayed()
        panelText("Uscite ${formatMoney("0.20".toBigDecimal(), "EUR")}", "chart-summary-EUR").assertExists()
        compose.onNodeWithTag("chart-time-USD").assertDoesNotExist()

        compose.runOnIdle { data.value = original.copy(transactions = listOf(usd)) }
        compose.onNodeWithText("Valuta: Tutte le valute").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Valuta: Seleziona").assertDoesNotExist()
        compose.onNodeWithTag("chart-time-EUR").assertDoesNotExist()
        panelText("Uscite ${formatMoney("7.25".toBigDecimal(), "USD")}", "chart-summary-USD").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Periodo selezionato: 2024-01-01 – 2024-12-31").assertExists()

        compose.runOnIdle { data.value = original }
        compose.onNodeWithText("Valuta: Tutte le valute").performScrollTo().assertIsDisplayed()
        panelText("Uscite ${formatMoney("0.10".toBigDecimal(), "EUR")}", "chart-summary-EUR").assertExists()
        panelText("Uscite ${formatMoney("7.25".toBigDecimal(), "USD")}", "chart-summary-USD").assertExists()
    }

    @Test fun monthTypeCurrencyAndCategoryControlsKeepLeapDayAndExactTotals() {
        val data = FinanceData(categories = listOf(food, travel, salary), transactions = listOf(
            row("2024-02-01", "0.10"), row("2024-02-29", "0.20"), row("2024-02-29", "3", category = "travel"),
            row("2024-02-29", "55", currency = "USD"), row("2024-02-29", "10", TransactionType.INCOME, category = "salary"),
            row("2024-03-01", "999")))
        compose.setContent { Fixture { FinanceChartsScreen(data, today = today) } }
        choose("Periodo: Anno", "Mese")
        choose("Mese: marzo", "febbraio")
        compose.onNodeWithText("Applica intervallo").performScrollTo().performClick()
        compose.onNodeWithText("Periodo selezionato: 2024-02-01 – 2024-02-29").assertExists()
        selectVisibleAnalysis("Analisi Uscite")
        choose("Valuta: Tutte le valute", "EUR")
        choose("Categoria: Tutte le categorie", "Categoria QA")
        panelText("Uscite ${formatMoney("0.30".toBigDecimal(), "EUR")}", "chart-summary-EUR").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chart-time-USD").assertDoesNotExist()
        selectVisibleAnalysis("Analisi Entrate")
        compose.onNodeWithText("Categoria: Tutte le categorie").assertExists()
        panelText("Entrate ${formatMoney("10".toBigDecimal(), "EUR")}", "chart-summary-EUR").performScrollTo().assertIsDisplayed()
        panelText("Uscite ${formatMoney("0".toBigDecimal(), "EUR")}", "chart-summary-EUR").assertExists()
    }

    @Test fun customDateErrorsRetainLastAppliedRangeAndBoundaryTotals() {
        val data = FinanceData(transactions = listOf(row("2024-02-28", "999"), row("2024-02-29", "1"),
            row("2024-03-01", "2"), row("2024-03-02", "999")), categories = listOf(food))
        compose.setContent { Fixture { FinanceChartsScreen(data, today = today) } }
        choose("Periodo: Anno", "Tra date")
        compose.onNodeWithText("Dal").performScrollTo().performTextReplacement("2024-02-29")
        compose.onNodeWithText("Al").performScrollTo().performTextReplacement("2024-03-01")
        compose.onNodeWithText("Applica intervallo").performScrollTo().performClick()
        val applied = "Periodo selezionato: 2024-02-29 – 2024-03-01"
        compose.onNodeWithText(applied).assertExists()
        panelText("Uscite ${formatMoney("3".toBigDecimal(), "EUR")}", "chart-summary-EUR").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Dal").performScrollTo().performTextReplacement("2024-02-30")
        compose.onNodeWithText("Applica intervallo").performScrollTo().performClick()
        compose.onNodeWithText("Inserisci date valide nel formato YYYY-MM-DD").assertExists()
        compose.onNodeWithText(applied).assertExists()
        compose.onNodeWithText("Dal").performScrollTo().performTextReplacement("2024-03-02")
        compose.onNodeWithText("Applica intervallo").performScrollTo().performClick()
        compose.onNodeWithText("Intervallo non valido").assertExists()
        compose.onNodeWithText(applied).assertExists()
        panelText("Uscite ${formatMoney("3".toBigDecimal(), "EUR")}", "chart-summary-EUR").assertExists()
    }

    @Test fun categoryComparisonHasIndividualDataAndNegativePieFailsVisiblyWithoutDroppingRows() {
        val data = FinanceData(categories = listOf(food, travel), transactions = listOf(row("2024-03-01", "1"),
            row("2024-03-01", "2", category = "travel"), row("2024-03-01", "3", category = null),
            row("2024-03-01", "-1", category = "removed")))
        compose.setContent { Fixture { FinanceChartsScreen(data, today = today) } }
        choose("Periodo: Anno", "Mese")
        compose.onNodeWithText("Applica intervallo").performScrollTo().performClick()
        compose.onNodeWithText("Analisi Uscite").performScrollTo().performClick()
        val categoryPanel = "chart-category-EUR-expense"
        panelText("Torta", categoryPanel).performScrollTo().performClick()
        panelText("La torta richiede importi non negativi e un totale positivo. Usa Barre o il dettaglio.", categoryPanel).assertExists()
        panelText("Senza categoria", categoryPanel).assertExists()
        panelText("Categoria rimossa", categoryPanel).assertExists()
        panelText("Linea", categoryPanel).performScrollTo().performClick()
        panelText("Andamento cumulativo per categoria", categoryPanel).assertExists()
        choose("Dettaglio temporale della categoria: Senza categoria", "Viaggi QA")
        val firstDate = LocalDate.parse("2024-03-01").format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ITALIAN))
        compose.onNode(hasText(firstDate) and hasText("Cumulativo ${formatMoney("2".toBigDecimal(), "EUR")}") and
            hasAnyAncestor(hasTestTag(categoryPanel))).performScrollTo().assertIsDisplayed()
        panelText("Viaggi QA: ${formatMoney("2".toBigDecimal(), "EUR")}", categoryPanel).assertExists()
        panelText("Categoria rimossa: ${formatMoney("-1".toBigDecimal(), "EUR")}", categoryPanel).assertExists()
    }

    @Test fun dashboardCurrencySelectionResetsWhenNewRangeHasOnlyAnotherCurrencyAndKeepsInclusiveRows() {
        val range = mutableStateOf(period("2024-02-29", "2024-03-01"))
        val rows = listOf(row("2024-02-28", "999"), row("2024-02-29", "7", currency = "USD"), row("2024-03-01", "5"), row("2024-03-02", "999"))
        compose.setContent { Fixture { DashboardRangedChart(rows, range.value, Modifier.testTag("dashboard-chart")) } }
        choose("Valuta: Tutte le valute", "USD")
        panelText("Uscite ${formatMoney("7".toBigDecimal(), "USD")}", "dashboard-chart").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { range.value = period("2024-03-01", "2024-03-01") }
        compose.onNodeWithText("Valuta: Tutte le valute").performScrollTo().assertIsDisplayed()
        choose("Granularità: Giornaliero", "Settimanale")
        panelText("Asse temporale 2024-02-26 – 2024-02-26", "dashboard-chart").assertExists()
        panelText("Uscite ${formatMoney("5".toBigDecimal(), "EUR")}", "dashboard-chart").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("USD", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Uscite ${formatMoney("999".toBigDecimal(), "EUR")}").assertDoesNotExist()
    }

    @Test fun dashboardOversizedDailyRangeReportsLimitAndMonthlyChoiceExposesAllPaginatedBuckets() {
        val range = period("2000-01-01", "2010-01-01")
        val rows = listOf(row("2000-01-01", "0.10", TransactionType.INCOME), row("2010-01-01", "0.20", TransactionType.INCOME))
        compose.setContent { Fixture { DashboardRangedChart(rows, range, Modifier.testTag("dashboard-chart")) } }
        compose.onNodeWithText("Troppi intervalli per il grafico. Seleziona un periodo più breve o una granularità maggiore.").assertExists()
        choose("Granularità: Giornaliero", "Mensile")
        compose.onNodeWithText("Troppi intervalli per il grafico. Seleziona un periodo più breve o una granularità maggiore.").assertDoesNotExist()
        panelText("Pagina 1 di 7 · 121 righe", "dashboard-chart").performScrollTo().assertIsDisplayed()
        panelText("Pagina precedente", "dashboard-chart").assertIsNotEnabled()
        panelText("Pagina successiva", "dashboard-chart").performScrollTo().performClick()
        panelText("Pagina 2 di 7 · 121 righe", "dashboard-chart").assertExists()
        val firstDateOnPageTwo = LocalDate.parse("2001-09-01").format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ITALIAN))
        panelText(firstDateOnPageTwo, "dashboard-chart").assertExists()
        panelText("Pagina precedente", "dashboard-chart").assertIsEnabled()
    }

    @Test fun validLongYearlyRangeUsesExplicitAnnualCumulativeInsteadOfCrashingOrDroppingYears() {
        val data = FinanceData(transactions = listOf(row("1800-01-01", "1", TransactionType.INCOME, category = "salary"),
            row("2000-01-01", "2", TransactionType.INCOME, category = "salary")), categories = listOf(salary))
        compose.setContent { Fixture { FinanceChartsScreen(data, today = today) } }
        choose("Granularità: Mensile", "Annuale")
        choose("Periodo: Anno", "Tra date")
        compose.onNodeWithText("Dal").performScrollTo().performTextReplacement("1800-01-01")
        compose.onNodeWithText("Al").performScrollTo().performTextReplacement("2000-01-01")
        compose.onNodeWithText("Applica intervallo").performScrollTo().performClick()
        compose.onNodeWithText("Periodo selezionato: 1800-01-01 – 2000-01-01").assertExists()
        panelText("Entrate ${formatMoney("3".toBigDecimal(), "EUR")}", "chart-summary-EUR").performScrollTo().assertIsDisplayed()
        panelText("Granularità del cumulativo: Annuale", "chart-cumulative-EUR").performScrollTo().assertIsDisplayed()
        panelText("Pagina 1 di 11 · 201 righe", "chart-cumulative-EUR").assertExists()
        panelText("Pagina successiva", "chart-cumulative-EUR").performScrollTo().performClick()
        panelText("Pagina 2 di 11 · 201 righe", "chart-cumulative-EUR").assertExists()
    }
}
