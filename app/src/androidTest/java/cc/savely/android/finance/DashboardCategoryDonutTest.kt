package cc.savely.android.finance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.ui.SavelyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Presentation only: synthetic amounts, no API or session. */
@RunWith(AndroidJUnit4::class)
class DashboardCategoryDonutTest {
    @get:Rule val compose = createComposeRule()
    private val from = LocalDate.parse("2026-06-01")
    private val range = DashboardRange(from, LocalDate.parse("2026-06-15"))
    private fun tx(id: String, amount: String, currency: String = "EUR", category: String? = "food", type: TransactionType = TransactionType.EXPENSE) =
        FinanceTransaction(id, type, amount.toBigDecimal(), currency, from, "", category)
    private fun show(rows: List<FinanceTransaction>, categories: List<FinanceCategory> = emptyList()) {
        compose.setContent { SavelyTheme("light") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { DashboardCategoryDonut(rows, categories, range) }
        } }
    }

    @Test fun exactLegendAmountsAndSharesRemainAccessibleAcrossCurrenciesAndUnsafeColorFallback() {
        val categories = listOf(FinanceCategory("food", "Cibo {{amount}}", TransactionType.EXPENSE, color = "invalid"))
        show(listOf(tx("1", "0.10"), tx("2", "0.20"), tx("3", "0.30", category = "removed"), tx("4", "100", "USD")), categories)
        compose.onAllNodesWithText("Totale spese: ", substring = true).assertCountEquals(2)
        compose.onNode(hasText("Totale spese: ", substring = true) and hasText("0,60", substring = true)).assertExists()
        compose.onNode(hasText("Totale spese: ", substring = true) and hasText("100,00", substring = true)).assertExists()
        compose.onNode(hasText("Cibo {{amount}}") and hasText("0,30", substring = true)).performScrollTo()
            .assertTextContains("50%", substring = true)
        compose.onNodeWithText("Nessuna categoria").assertTextContains("0,30", substring = true).assertTextContains("50%", substring = true)
        compose.onNode(hasText("Cibo {{amount}}") and hasText("100,00", substring = true)).performScrollTo()
            .assertTextContains("100%", substring = true)
    }

    @Test fun zeroTotalShowsEmptyStateWithoutInventingPercentages() {
        show(listOf(tx("zero", "0")))
        compose.onNodeWithText("Nessuna transazione nel periodo selezionato").assertExists()
        compose.onNodeWithText("0%", substring = true).assertDoesNotExist()
    }

    @Test fun incomeOnlyAndOutsideRangeDoNotCreateExpenseSlices() {
        show(listOf(tx("income", "50", type = TransactionType.INCOME), tx("future", "100").copy(date = range.to.plusDays(1))))
        compose.onNodeWithText("Nessuna transazione nel periodo selezionato").assertExists()
        compose.onNodeWithText("Totale spese: ", substring = true).assertDoesNotExist()
    }

    @Test fun invalidLegacyExpenseShowsErrorRatherThanAnEmptyDonut() {
        show(listOf(tx("negative", "-1")))
        compose.onNodeWithText("Spese per categoria non disponibili: importo o valuta non validi.").assertExists()
        compose.onNodeWithText("Nessuna transazione nel periodo selezionato").assertDoesNotExist()
    }
}
