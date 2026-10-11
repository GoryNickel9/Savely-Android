package cc.savely.android.finance

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PortfolioModelsTest {
    private fun asset(type: FinanceAssetType = FinanceAssetType.STOCK, quantity: String = "2", purchase: String = "10",
        current: String? = "15", currency: String = "EUR", sold: String? = null, soldPrice: String? = null) = FinanceAsset(
        "asset-${type.wire}-$currency", "Asset", "TEST", type, quantity.toBigDecimal(), purchase.toBigDecimal(),
        current?.toBigDecimal(), currency, LocalDate.parse("2026-01-01"), sold?.let(LocalDate::parse), soldPrice?.toBigDecimal(), "")

    @Test fun `open position fallback and half closed records match web`() {
        val row = asset(current = null, sold = "2026-10-01", soldPrice = null)
        assertFalse(row.closed)
        assertEquals(BigDecimal("20"), row.marketValue)
        assertEquals(BigDecimal("0"), row.gain)
    }

    @Test fun `portfolio open value and realized gains remain separate by currency`() {
        val result = portfolioTotals(listOf(asset(), asset(currency = "USD"), asset(sold = "2026-10-01", soldPrice = "8")))
        assertEquals("EUR", result[0].currency)
        assertEquals(BigDecimal("30"), result[0].value)
        assertEquals(BigDecimal("10"), result[0].gain)
        assertEquals(BigDecimal("-4"), result[0].realized)
        assertEquals("USD", result[1].currency)
    }

    @Test fun `net worth excludes cash other and closed investment and discounts estate`() {
        val transactions = listOf(FinanceTransaction("t", TransactionType.INCOME, BigDecimal("100"), "EUR", LocalDate.parse("2026-10-11"), "", null))
        val result = netWorthComponents(transactions, listOf(asset(), asset(FinanceAssetType.REAL_ESTATE, "1", "1000", "1200"),
            asset(FinanceAssetType.CASH, "1", "500", "700"), asset(FinanceAssetType.OTHER, "1", "100", "200"),
            asset(sold = "2026-10-01", soldPrice = "50"))).single()
        assertEquals(BigDecimal("100"), result.cashflow)
        assertEquals(BigDecimal("10"), result.portfolioGain)
        assertEquals(BigDecimal("900.00"), result.discountedRealEstate)
        assertEquals(BigDecimal("1010.00"), result.total)
    }

    @Test fun `cash FX model uses native quantity and EUR rate`() {
        val row = asset(FinanceAssetType.CASH, "100", "1", "0.91", "USD")
        assertEquals("EUR", row.valuationCurrency)
        assertEquals(BigDecimal("91.00"), row.marketValue)
    }

    @Test fun `quantity preserves eight decimals with numeric20 limit`() {
        assertEquals(BigDecimal("0.00000001"), parseQuantity("0,00000001"))
        assertEquals(BigDecimal("0.00000000"), parseQuantity("0"))
        listOf("-1", "abc", "1e4", "1000000000000").forEach {
            assertThrows(IllegalArgumentException::class.java) { parseQuantity(it) }
        }
    }

    @Test fun `closure validates date and sale price limits`() {
        val today = LocalDate.parse("2026-10-11")
        val purchase = LocalDate.parse("2026-01-01")
        validatePositionClosure(today, BigDecimal("1000000"), purchase, today)
        assertThrows(IllegalArgumentException::class.java) { validatePositionClosure(today.plusDays(1), BigDecimal.ONE, purchase, today) }
        assertThrows(IllegalArgumentException::class.java) { validatePositionClosure(purchase.minusDays(1), BigDecimal.ONE, purchase, today) }
        assertThrows(IllegalArgumentException::class.java) { validatePositionClosure(today, BigDecimal.ZERO, purchase, today) }
    }

    @Test fun `JSON portfolio handles nullable price symbol sale date and notes`() {
        val row = assetFromJson(JSONObject("""{"id":"a","name":"Test","symbol":null,"type":"etf","quantity":"0.12345678","purchase_price":"20.30","current_price":null,"currency":null,"purchase_date":"2026-01-01","sold_at":null,"sold_price":null,"notes":null}"""))
        assertEquals(BigDecimal("0.12345678"), row.quantity)
        assertEquals(BigDecimal("20.30"), row.marketPrice)
        assertFalse(row.closed)
        assertEquals("", row.notes)
        assertEquals("EUR", row.currency)
    }

    @Test fun `insight budget ignores foreign currency and portfolio loss threshold is inclusive`() {
        val tx = FinanceTransaction("t", TransactionType.EXPENSE, BigDecimal("200"), "USD", LocalDate.parse("2026-10-11"), "", "food")
        val data = FinanceData(transactions = listOf(tx), budgets = listOf(FinanceBudget("b", "food", BigDecimal("100"), "EUR")),
            assets = listOf(asset(purchase = "100", current = "85")))
        val insights = financeInsights(data, LocalDate.parse("2026-10-11"))
        assertEquals(1, insights.size)
        assertEquals("loss-asset-stock-EUR", insights.single().id)
    }
}
