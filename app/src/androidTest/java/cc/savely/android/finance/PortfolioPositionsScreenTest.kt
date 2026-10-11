package cc.savely.android.finance

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.ui.SavelyTheme
import java.io.IOException
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure dialog fixture complements the TLS screen test for refresh reconciliation. */
@RunWith(AndroidJUnit4::class)
class PortfolioPositionsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun differentQuotesRequireExplicitPriceAndPartialFailurePreventsSecondSubmission() {
        val today = LocalDate.now()
        fun asset(id: String, quantity: String, quote: String) = FinanceAsset(id, "Gruppo QA", "QA", FinanceAssetType.STOCK,
            quantity.toBigDecimal(), "10.00".toBigDecimal(), quote.toBigDecimal(), "EUR", today.minusDays(30), null, null, "")
        val group = PortfolioPositionGroup(listOf(asset("first-qa", "2", "12.00"), asset("second-qa", "3", "13.00")))
        val saving = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        val failure = mutableStateOf<PositionCloseException?>(null)
        var submissions = 0
        var refreshes = 0
        var dismissals = 0
        var submittedDate = ""
        var submittedPrice = ""
        compose.setContent { SavelyTheme("light") {
            CloseAssetGroupDialog(group, saving.value, error.value, failure.value,
                dismiss = { dismissals++ }, refreshAndDismiss = { refreshes++ }) { date, price ->
                submissions++
                submittedDate = date
                submittedPrice = price
                saving.value = true
            }
        } }
        assertEquals("", compose.onNodeWithText("Prezzo unitario vendita in EUR").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("Conferma chiusura collettiva").performClick()
        compose.onNodeWithText("Inserisci un importo numerico").assertExists()
        assertEquals("Different quote snapshots must not be averaged into a submitted sale price", 0, submissions)
        compose.onNodeWithText("Prezzo unitario vendita in EUR").performTextInput("12,50")
        compose.onNodeWithText("Conferma chiusura collettiva").performClick()
        assertEquals(1, submissions)
        assertEquals(today.toString(), submittedDate)
        assertEquals("12,50", submittedPrice)
        compose.onNodeWithText("Salvataggio…").assertIsNotEnabled()
        compose.onNodeWithText("Annulla").assertIsNotEnabled()
        compose.onNodeWithText("Prezzo unitario vendita in EUR").assertIsNotEnabled()
        compose.runOnIdle {
            saving.value = false
            failure.value = PositionCloseException(1, 2, "second-qa", IOException("fixture response lost"))
            error.value = failure.value!!.message
        }
        compose.onNodeWithText("Conferma chiusura collettiva").assertDoesNotExist()
        compose.onNodeWithText("Prezzo unitario vendita in EUR").assertIsNotEnabled()
        compose.onNodeWithText("Chiusure confermate: 1 su 2. Verifica anche l’acquisto second-qa dopo il refresh.").assertExists()
        compose.onNodeWithText("Chiusura interrotta.", substring = true).assertExists()
        assertEquals("Rendering the failure cannot automatically retry", 1, submissions)
        compose.onNodeWithText("Aggiorna e verifica").performClick()
        assertEquals(1, refreshes)
        assertEquals("Refresh must not invoke the closure callback again", 1, submissions)
        assertEquals(0, dismissals)
    }
}
