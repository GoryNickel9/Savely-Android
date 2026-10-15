package cc.savely.android.finance

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.ui.LanguagePreferences
import cc.savely.android.ui.LocalizationProvider
import cc.savely.android.ui.SavelyTheme
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure presentation fixtures: no API, credentials, transport or network calls. */
@RunWith(AndroidJUnit4::class)
class PortfolioHistoryScreenTest {
    @get:Rule val compose = createComposeRule()
    private var testPreferences: SharedPreferences? = null
    @After fun clearTestPreferences() { testPreferences?.edit()?.clear()?.commit() }

    private fun asset(id: String, currency: String = "EUR", quantity: String = "2", type: FinanceAssetType = FinanceAssetType.STOCK,
        name: String = id, sold: Boolean = false) = FinanceAsset(id, name, "SAME", type, quantity.toBigDecimal(), BigDecimal("100"),
        BigDecimal("140"), currency, LocalDate.now().minusDays(4), if (sold) LocalDate.now().minusDays(1) else null,
        if (sold) BigDecimal("130") else null, "")
    private fun showPortfolio(assets: List<FinanceAsset>, history: List<FinancePriceHistory>) {
        compose.setContent { SavelyTheme("light") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { PortfolioHistoryPanel(assets, history) }
        } }
    }
    private fun click(text: String) {
        compose.onNodeWithText(text).performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }
    private fun select(current: String, option: String) {
        click(current)
        compose.onNodeWithText(option).performClick()
        compose.waitForIdle()
    }

    @Test fun reconstructedAndRecordedDetailsHaveLiteralValuesCoverageAndSeparateCurrencies() {
        val today = LocalDate.now()
        val assets = listOf(asset("eur"), asset("usd", "USD", "3"))
        val history = listOf(FinancePriceHistory("eur-recorded", "eur", today.minusDays(1), BigDecimal("130")),
            FinancePriceHistory("usd-recorded", "usd", today.minusDays(1), BigDecimal("30")))
        showPortfolio(assets, history)
        compose.onNodeWithText("Valore: ", substring = true).assertTextContains("280,00", substring = true)
        compose.onNodeWithText("Profitto: ", substring = true).assertTextContains("80,00", substring = true)
        compose.onNodeWithText("Copertura: 0 prezzi registrati, 0 interpolati, 1 prezzi correnti.").assertExists()
        click("Punto precedente")
        compose.onNodeWithText("Valore: ", substring = true).assertTextContains("260,00", substring = true)
        compose.onNodeWithText("Copertura: 1 prezzi registrati, 0 interpolati, 0 prezzi correnti.").assertExists()
        select("Serie: Andamento ricostruito", "Prezzi registrati")
        compose.onNodeWithText("Valore: ", substring = true).assertTextContains("260,00", substring = true)
        compose.onNodeWithText("Investito: ", substring = true).assertTextContains("200,00", substring = true)
        select("Valuta: EUR", "USD")
        compose.onNodeWithText("Valore: ", substring = true).assertTextContains("90,00", substring = true)
        compose.onNodeWithText("Investito: ", substring = true).assertTextContains("300,00", substring = true)
        compose.onNodeWithText("Profitto: ", substring = true).assertTextContains("-210,00", substring = true)
        compose.onNodeWithText("Un solo punto: la variazione zero non misura una tendenza.").assertExists()
    }

    @Test fun serverCustomRangeUsesActualSnapshotsPriorPeriodAndRejectsInvalidDatesWithoutChangingRange() {
        val snapshots = listOf(FinanceSnapshot("prior-a", LocalDate.parse("2024-05-17"), BigDecimal("80"), null),
            FinanceSnapshot("prior-b", LocalDate.parse("2024-05-31"), BigDecimal("100"), null),
            FinanceSnapshot("current-a", LocalDate.parse("2024-06-01"), BigDecimal("110"), null),
            FinanceSnapshot("current-b", LocalDate.parse("2024-06-15"), BigDecimal("130"),
                JSONObject().put("cashflow", "110").put("portfolioPL", "20").put("realEstateDiscounted", "0")),
            FinanceSnapshot("future", LocalDate.parse("2040-06-15"), BigDecimal("999"), null))
        compose.setContent { SavelyTheme("light") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { NetWorthHistoryPanel(snapshots) }
        } }
        select("Periodo storico: Tutto lo storico", "Personalizzato")
        compose.onNodeWithText("Dal").performScrollTo().performTextReplacement("2024-06-01")
        compose.onNodeWithText("Al").performScrollTo().performTextReplacement("2024-06-15")
        click("Applica intervallo")
        compose.onNodeWithText("Ultimo valore registrato: 130,00").assertExists()
        compose.onNodeWithText("Variazione del periodo: +20,00").assertExists()
        compose.onNodeWithText("Variazione nel periodo precedente: 20,00").assertExists()
        compose.onNodeWithText("Differenza rispetto all’ultimo valore del periodo precedente: 30,00").assertExists()
        compose.onNodeWithText("30% rispetto al periodo precedente").assertExists()
        compose.onNodeWithText("Valore registrato: 130,00").assertExists()
        compose.onNodeWithText("Cashflow registrato: 110,00").assertExists()
        compose.onNodeWithText("Immobili scontati registrati: 0,00").assertExists()
        compose.onNodeWithText("Dal").performScrollTo().performTextReplacement("2024-02-30")
        click("Applica intervallo")
        compose.onNodeWithText("Inserisci date valide nel formato YYYY-MM-DD").assertExists()
        compose.onNodeWithText("Periodo selezionato: 2024-06-01 – 2024-06-15").assertExists()
        compose.onNodeWithText("Ultimo valore registrato: 130,00").assertExists()
        compose.onAllNodesWithText("€", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("USD", substring = true).assertCountEquals(0)
    }

    @Test fun malformedSnapshotComponentsStayVisibleAsErrorWhileRecordedValueRemainsAvailable() {
        val snapshots = listOf(FinanceSnapshot("bad-components", LocalDate.parse("2024-06-15"), BigDecimal("130.305"),
            JSONObject().put("cashflow", "not-a-number")))
        compose.setContent { SavelyTheme("light") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { NetWorthHistoryPanel(snapshots) }
        } }
        compose.onNodeWithText("Valore registrato: 130,31").assertExists()
        compose.onNodeWithText("Componenti dello snapshot non validi.").assertExists()
        compose.onNodeWithText("Una sola registrazione: la variazione zero non misura una tendenza.").assertExists()
        compose.onNodeWithText("Componenti non registrati per questa data.").assertDoesNotExist()
    }

    @Test fun englishHistoryFormatsRecordedNumbersAndDoesNotInventCurrency() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("portfolio-history-ui-test-language", Context.MODE_PRIVATE)
        testPreferences = preferences
        preferences.edit().clear().putString("language", "en").commit()
        val snapshots = listOf(FinanceSnapshot("s", LocalDate.parse("2024-06-15"), BigDecimal("1.305"), null))
        compose.setContent { LocalizationProvider(LanguagePreferences(preferences)) { SavelyTheme("light") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { NetWorthHistoryPanel(snapshots) }
        } } }
        compose.onNodeWithText("Recorded value: 1.31").assertExists()
        compose.onNodeWithText("Components not recorded for this date.").assertExists()
        compose.onNodeWithText("History period: All history").assertExists()
        compose.onAllNodesWithText("€", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("$", substring = true).assertCountEquals(0)
    }

    @Test fun existingClosedInstrumentPrefillsOnlyIdentityAndRetainsNewQuantityPriceAndDate() {
        val submits = AtomicInteger()
        val assets = listOf(asset("stock-eur", name = "Scelta aperta"),
            asset("etf-eur", type = FinanceAssetType.ETF, name = "Scelta ETF EUR", sold = true),
            asset("etf-usd", "USD", "999", FinanceAssetType.ETF, "Scelta chiusa {{currency}}", sold = true))
        compose.setContent { SavelyTheme("light") {
            AssetEditor(null, FinanceData(assets = assets), false, null, {}, { submits.incrementAndGet() })
        } }
        compose.onNodeWithText("Quantità").performScrollTo().performTextReplacement("2")
        compose.onNodeWithText("Prezzo acquisto unitario").performScrollTo().performTextReplacement("30")
        select("Strumento esistente: Nuovo strumento", "Scelta chiusa {{currency}} · SAME · ETF · USD")
        compose.onNodeWithText("Tipo: ETF").assertExists()
        compose.onNodeWithText("Valuta: USD").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Scelta chiusa {{currency}}", substring = false)).assertExists()
        compose.onNodeWithText("Quantità").assertTextContains("2", substring = true)
        compose.onNodeWithText("Prezzo acquisto unitario").assertTextContains("30", substring = true)
        compose.onNodeWithText("Acquisto: ${LocalDate.now()}").assertExists()
        assertEquals("Selecting an existing instrument must not submit a purchase", 0, submits.get())
    }
}
