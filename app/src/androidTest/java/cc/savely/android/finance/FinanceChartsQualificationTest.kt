package cc.savely.android.finance

import android.graphics.BitmapFactory
import android.graphics.Color as PixelColor
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.visual.CommonVisualArtifacts
import cc.savely.android.visual.CommonVisualContent
import cc.savely.android.visual.CommonVisualProfile
import java.time.LocalDate
import java.util.Currency
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Production chart components with synthetic values only: no API, session or backend transport. */
@RunWith(AndroidJUnit4::class)
class FinanceChartsQualificationTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeout: Timeout = Timeout.seconds(180)
    private val today = LocalDate.parse("2026-06-15")
    private val food = FinanceCategory("food", "QA Food", TransactionType.EXPENSE, color = "#22c55e")
    private val travel = FinanceCategory("travel", "QA Travel", TransactionType.EXPENSE, color = "#f59e0b")
    private val salary = FinanceCategory("salary", "QA Salary", TransactionType.INCOME)
    private val unused = FinanceCategory("unused", "QA Empty", TransactionType.EXPENSE)
    private var rootView: View? = null
    private var hideInput: () -> Unit = {}
    private var timeColors = emptyList<Int>()

    private fun row(id: String, date: String, amount: String, type: TransactionType,
        currency: String = "EUR", category: String) = FinanceTransaction(id, type, amount.toBigDecimal(), currency,
        LocalDate.parse(date), "Synthetic V05 $id", category)

    private fun commonRows() = listOf(
        row("income", "2026-06-01", "120", TransactionType.INCOME, category = salary.id),
        row("expense", "2026-06-15", "30", TransactionType.EXPENSE, category = food.id),
    )

    @Test fun filtersAndWeeklyMonthlyYearlyTablesKeepLiteralCurrencyTotalsAcrossBarsAndLines() {
        val data = FinanceData(categories = listOf(food, travel, salary), transactions = commonRows() + listOf(
            row("usd-income", "2026-06-01", "8", TransactionType.INCOME, "USD", salary.id),
            row("usd-expense", "2026-06-08", "2", TransactionType.EXPENSE, "USD", food.id),
            row("before", "2026-05-31", "999", TransactionType.INCOME, category = salary.id),
            row("after", "2026-06-16", "999", TransactionType.EXPENSE, category = travel.id),
        ))
        profiles(data) { english, profile ->
            applyRange(english, "2026-06-01", "2026-06-15")
            summary(english, "EUR", "120,00", "30,00", "90,00")
            summary(english, "USD", "8,00", "2,00", "6,00")
            show(inPanel("chart-summary-EUR", exact(if (english) "Savings rate: 75,0%" else "Tasso di risparmio: 75,0%")))
            choose(if (english) "Currency: All currencies" else "Valuta: Tutte le valute", "EUR")
            compose.onNodeWithTag("chart-summary-USD").assertDoesNotExist()
            choose(if (english) "Interval: Monthly" else "Granularità: Mensile", if (english) "Weekly" else "Settimanale")
            timeRow(english, if (english) "01 Jun 2026" else "01 giu 2026", "120,00", "0,00", "120,00")
            timeRow(english, if (english) "08 Jun 2026" else "08 giu 2026", "0,00", "0,00", "0,00")
            timeRow(english, if (english) "15 Jun 2026" else "15 giu 2026", "0,00", "30,00", "-30,00")
            captureGraphic("weekly-bars", profile, "chart-time-EUR", "chart-time-graphic", timeColors)
            click(inPanel("chart-time-EUR", exact(if (english) "Line" else "Linea") and hasClickAction()))
            timeRow(english, if (english) "15 Jun 2026" else "15 giu 2026", "0,00", "30,00", "-30,00")
            summary(english, "EUR", "120,00", "30,00", "90,00")
            captureGraphic("weekly-lines", profile, "chart-time-EUR", "chart-time-graphic", timeColors)
            choose(if (english) "Interval: Weekly" else "Granularità: Settimanale", if (english) "Monthly" else "Mensile")
            timeRow(english, if (english) "Jun 2026" else "giu 2026", "120,00", "30,00", "90,00")
            choose(if (english) "Interval: Monthly" else "Granularità: Mensile", if (english) "Yearly" else "Annuale")
            timeRow(english, "2026", "120,00", "30,00", "90,00")
            click(exact(if (english) "Expense analysis" else "Analisi Uscite") and hasClickAction())
            choose(if (english) "Category: All categories" else "Categoria: Tutte le categorie", food.name)
            summary(english, "EUR", "0,00", "30,00", "-30,00")
            click(exact(if (english) "Income analysis" else "Analisi Entrate") and hasClickAction())
            show(exact(if (english) "Category: All categories" else "Categoria: Tutte le categorie"))
            summary(english, "EUR", "120,00", "0,00", "120,00")
        }
    }

    @Test fun positivePieAndCategoryLinesKeepTheIndividualOneThreeAndTwentyFiveSeventyFiveDetails() {
        val data = FinanceData(categories = listOf(food, travel), transactions = listOf(
            row("food", "2026-06-01", "1", TransactionType.EXPENSE, category = food.id),
            row("travel", "2026-06-02", "3", TransactionType.EXPENSE, category = travel.id),
        ))
        profiles(data) { english, profile ->
            applyRange(english, "2026-06-01", "2026-06-02")
            click(exact(if (english) "Expense analysis" else "Analisi Uscite") and hasClickAction())
            val panel = "chart-category-EUR-expense"
            categoryDetails(panel, food.name, "1,00", "25%")
            categoryDetails(panel, travel.name, "3,00", "75%")
            captureAt("category-bars", profile, inPanel(panel, exact("${travel.name}: ${eur("3,00")}")))
            click(inPanel(panel, exact(if (english) "Pie" else "Torta") and hasClickAction()))
            captureGraphic("category-pie", profile, panel, "chart-category-pie", listOf(0xff22c55e.toInt(), 0xfff59e0b.toInt()))
            categoryDetails(panel, travel.name, "3,00", "75%")
            val last = categoryDetails(panel, food.name, "1,00", "25%")
            assertFullyVisible(last)
            capture("category-pie-details", profile)
            click(inPanel(panel, exact(if (english) "Line" else "Linea") and hasClickAction()))
            captureGraphic("category-lines", profile, panel, "chart-category-lines", listOf(0xff22c55e.toInt(), 0xfff59e0b.toInt()))
            show(inPanel(panel, exact("${travel.name}: ${eur("3,00")}")))
            show(inPanel(panel, exact("${food.name}: ${eur("1,00")}")))
            choose(if (english) "Category timeline details: QA Travel" else "Dettaglio temporale della categoria: QA Travel", food.name)
            val first = if (english) "01 Jun 2026" else "01 giu 2026"
            val second = if (english) "02 Jun 2026" else "02 giu 2026"
            listOf(first, second).forEach { date ->
                show(inPanel(panel, exact(date) and exact("${if (english) "Cumulative" else "Cumulativo"} ${eur("1,00")}")))
            }
            categoryDetails(panel, food.name, "1,00", "25%")
            categoryDetails(panel, travel.name, "3,00", "75%")
        }
    }

    @Test fun invalidAndReversedDraftsKeepAppliedTotalsThenValidApplyHidesKeyboardAndEmptyFilterClearsCharts() {
        profiles(FinanceData(categories = listOf(food, salary, unused), transactions = commonRows())) { english, profile ->
            applyRange(english, "2026-06-01", "2026-06-15", proveKeyboard = true)
            summary(english, "EUR", "120,00", "30,00", "90,00")
            captureAt("valid-range", profile, inPanel("chart-summary-EUR", exact("${if (english) "Net" else "Netto"} ${eur("90,00")}")))
            input(if (english) "From" else "Dal", "2026-02-30")
            clickApply(english)
            val malformed = exact(if (english) "Enter valid dates in YYYY-MM-DD format" else "Inserisci date valide nel formato YYYY-MM-DD")
            compose.onNode(malformed).assertExists()
            appliedRange(english).assertExists()
            assertSummaryExists(english, "120,00", "30,00", "90,00")
            hideForReading()
            captureAt("invalid-date", profile, malformed)
            input(if (english) "From" else "Dal", "2026-06-16")
            input(if (english) "To" else "Al", "2026-06-01")
            clickApply(english)
            compose.onNodeWithText(if (english) "Invalid date range" else "Intervallo non valido").assertExists()
            appliedRange(english).assertExists()
            assertSummaryExists(english, "120,00", "30,00", "90,00")
            input(if (english) "From" else "Dal", "2026-06-01")
            input(if (english) "To" else "Al", "2026-06-15")
            awaitIme(true)
            clickApply(english)
            awaitIme(false)
            compose.onNode(malformed).assertDoesNotExist()
            compose.onNodeWithText(if (english) "Invalid date range" else "Intervallo non valido").assertDoesNotExist()
            click(exact(if (english) "Expense analysis" else "Analisi Uscite") and hasClickAction())
            choose(if (english) "Category: All categories" else "Categoria: Tutte le categorie", unused.name)
            val empty = exact(if (english) "No transactions in the selected period" else "Nessuna transazione nel periodo selezionato")
            show(empty)
            compose.onNodeWithTag("chart-time-EUR").assertDoesNotExist()
            appliedRange(english).assertExists()
            capture("empty-filter", profile)
            choose(if (english) "Category: QA Empty" else "Categoria: QA Empty", if (english) "All categories" else "Tutte le categorie")
            summary(english, "EUR", "0,00", "30,00", "-30,00")
        }
    }

    private fun profiles(data: FinanceData, verify: (Boolean, CommonVisualProfile) -> Unit) {
        val active = mutableStateOf<CommonVisualProfile?>(null)
        compose.setContent {
            active.value?.let { profile -> key(profile) {
                CommonVisualContent(profile) {
                    val view = LocalView.current
                    val focus = LocalFocusManager.current
                    val keyboard = LocalSoftwareKeyboardController.current
                    val colors = listOf(MaterialTheme.colorScheme.primary.toArgb(), MaterialTheme.colorScheme.error.toArgb())
                    SideEffect {
                        rootView = view
                        hideInput = { focus.clearFocus(force = true); keyboard?.hide() }
                        timeColors = colors
                    }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("v05-scroll")) {
                        FinanceChartsScreen(data, today = today)
                    }
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
                rootView = null
                hideInput = {}
            }
        }
    }

    private fun exact(text: String) = hasText(text, substring = false)
    private fun inPanel(tag: String, matcher: SemanticsMatcher) = matcher and hasAnyAncestor(hasTestTag(tag))
    private fun eur(amount: String) = "$amount\u00a0€"

    private fun choose(control: String, option: String) {
        click(exact(control) and hasClickAction())
        compose.onNode(exact(option) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun input(label: String, value: String) {
        compose.onNodeWithText(label).performScrollTo().performClick().performTextReplacement(value)
        compose.waitForIdle()
    }

    private fun applyRange(english: Boolean, from: String, to: String, proveKeyboard: Boolean = false) {
        choose(if (english) "Period: Year" else "Periodo: Anno", if (english) "Between dates" else "Tra date")
        input(if (english) "From" else "Dal", from)
        input(if (english) "To" else "Al", to)
        if (proveKeyboard) awaitIme(true)
        clickApply(english)
        awaitIme(false)
    }

    private fun clickApply(english: Boolean) {
        // The IME can resize this viewport. Let the real scroll container reveal the button first.
        compose.onNodeWithText(if (english) "Apply date range" else "Applica intervallo").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun appliedRange(english: Boolean) = compose.onNodeWithText(
        if (english) "Selected period: 2026-06-01 – 2026-06-15" else "Periodo selezionato: 2026-06-01 – 2026-06-15")

    private fun summary(english: Boolean, currency: String, income: String, expenses: String, net: String) {
        listOf((if (english) "Income" else "Entrate") to income,
            (if (english) "Expenses" else "Uscite") to expenses, (if (english) "Net" else "Netto") to net).forEach { (label, amount) ->
            val symbol = Currency.getInstance(currency).getSymbol(Locale.ITALY)
            val matcher = inPanel("chart-summary-$currency", exact("$label $amount\u00a0$symbol"))
            show(matcher)
        }
    }

    private fun assertSummaryExists(english: Boolean, income: String, expenses: String, net: String) {
        listOf((if (english) "Income" else "Entrate") to income,
            (if (english) "Expenses" else "Uscite") to expenses, (if (english) "Net" else "Netto") to net).forEach { (label, amount) ->
            compose.onNode(inPanel("chart-summary-EUR", exact("$label ${eur(amount)}"))).assertExists()
        }
    }

    private fun timeRow(english: Boolean, date: String, income: String, expense: String, net: String) {
        val row = inPanel("chart-time-EUR", exact(date) and exact("${if (english) "Income" else "Entrate"} ${eur(income)}") and
            exact("${if (english) "Expenses" else "Uscite"} ${eur(expense)}") and exact("${if (english) "Net" else "Netto"} ${eur(net)}"))
        show(row)
    }

    private fun categoryDetails(panel: String, name: String, amount: String, percent: String): SemanticsMatcher {
        val matcher = inPanel(panel, exact(name) and exact(eur(amount)) and exact(percent))
        show(matcher)
        return matcher
    }

    private fun click(matcher: SemanticsMatcher) {
        show(matcher)
        compose.onNode(matcher).assertIsEnabled().performClick()
        compose.waitForIdle()
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
            assertTrue("Asserted row must fit the viewport: $target / $viewport", target.width <= viewport.width && target.height <= viewport.height)
            if (target.left >= viewport.left && target.right <= viewport.right && target.top >= viewport.top && target.bottom <= viewport.bottom) {
                compose.onNode(matcher).assertIsDisplayed()
                return
            }
            val delta = (target.center.y - viewport.center.y).coerceIn(-viewport.height / 2f, viewport.height / 2f)
            compose.onNodeWithTag("v05-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            compose.waitForIdle()
        }
        assertFullyVisible(matcher)
    }

    private fun assertFullyVisible(matcher: SemanticsMatcher) {
        compose.onNode(matcher).assertIsDisplayed()
        val target = bounds(matcher)
        val viewport = bounds(hasTestTag(CommonVisualArtifacts.VIEWPORT))
        assertTrue("Unclipped target required: $target / $viewport",
            target.left >= viewport.left && target.right <= viewport.right && target.top >= viewport.top && target.bottom <= viewport.bottom)
    }

    private fun hideForReading() {
        // Error captures are observations after explicit test-side focus release, never proof of valid Apply hiding IME.
        compose.runOnIdle { hideInput() }
        awaitIme(false)
    }

    private fun awaitIme(expected: Boolean) {
        compose.waitUntil(8_000) {
            var visible: Boolean? = null
            compose.runOnIdle { visible = rootView?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) } }
            visible == expected
        }
    }

    private fun captureAt(name: String, profile: CommonVisualProfile, matcher: SemanticsMatcher) {
        show(matcher)
        capture(name, profile)
    }

    private fun capture(name: String, profile: CommonVisualProfile) =
        CommonVisualArtifacts.capture(compose, "v05-$name-${profile.suffix}", profile, clockInjected = true)

    private fun captureGraphic(name: String, profile: CommonVisualProfile, panel: String, tag: String, colors: List<Int>) {
        val matcher = inPanel(panel, hasTestTag(tag))
        show(matcher)
        assertFullyVisible(matcher)
        val target = bounds(matcher)
        val viewport = bounds(hasTestTag(CommonVisualArtifacts.VIEWPORT))
        val file = capture(name, profile)
        val image = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
        try {
            assertEquals(viewport.width.toInt(), image.width)
            assertEquals(viewport.height.toInt(), image.height)
            colors.forEach { color ->
                var painted = 0
                for (y in (target.top - viewport.top).toInt() until (target.bottom - viewport.top).toInt() step 2) {
                    for (x in (target.left - viewport.left).toInt() until (target.right - viewport.left).toInt() step 2) {
                        val actual = image.getPixel(x, y)
                        if (abs(PixelColor.red(actual) - PixelColor.red(color)) <= 8 &&
                            abs(PixelColor.green(actual) - PixelColor.green(color)) <= 8 &&
                            abs(PixelColor.blue(actual) - PixelColor.blue(color)) <= 8) painted++
                    }
                }
                assertTrue("The visible production Canvas must paint its data color; file=${file.name}, color=$color", painted >= 8)
            }
        } finally { image.recycle() }
    }
}
