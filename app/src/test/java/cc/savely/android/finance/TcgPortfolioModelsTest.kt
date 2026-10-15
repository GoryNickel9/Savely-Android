package cc.savely.android.finance

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TcgPortfolioModelsTest {
    private fun card(category: String = "magic", quantity: Int = 2, purchase: String = "10.00", current: String? = "12.50", currency: String = "EUR") =
        FinanceTcgCard("card-$category", category, quantity, purchase.toBigDecimal(), current?.toBigDecimal(), currency)
    private fun asset(type: FinanceAssetType, currency: String = "EUR", price: String = "100", sold: Boolean = false) =
        FinanceAsset("a", "Asset", null, type, BigDecimal.ONE, BigDecimal("80"), price.toBigDecimal(), currency,
            LocalDate.parse("2026-01-01"), if (sold) LocalDate.parse("2026-02-01") else null,
            if (sold) BigDecimal("90") else null, "")

    @Test fun `TCG groups include positive current prices with no fallback for unpriced cards`() {
        val cards = listOf(card(), card(quantity = 1, purchase = "20.00", current = "15.00"), card("pokemon", current = "100.00"),
            card("pokemon", current = null), card(current = "0"), card(current = "-1"))
        val totals = tcgPortfolioTotals(cards)
        assertEquals(listOf("magic", "pokemon"), totals.map { it.category })
        assertEquals(3L, totals[0].pieces)
        assertEquals(BigDecimal("40.00"), totals[0].value)
        assertEquals(BigDecimal("40.00"), totals[0].cost)
        assertEquals(BigDecimal("0.00"), totals[0].gain)
        assertEquals(BigDecimal("200.00"), totals[1].value)
        assertEquals(BigDecimal("180.00"), totals[1].gain)
    }

    @Test fun `foreign currency cards are excluded because updater prices EUR regardless of stored unit`() {
        val foreign = card(current = "999.00", currency = "USD")
        assertFalse(foreign.valued)
        assertTrue(tcgPortfolioTotals(listOf(foreign)).isEmpty())
        assertTrue(portfolioComposition(emptyList(), listOf(foreign)).isEmpty())
    }

    @Test fun `liquidity separates closed positions currencies and TCG without estate haircut`() {
        val assets = listOf(asset(FinanceAssetType.STOCK), asset(FinanceAssetType.REAL_ESTATE),
            asset(FinanceAssetType.OTHER), asset(FinanceAssetType.CASH, "USD", "0.91"),
            asset(FinanceAssetType.BOND, "USD", "50"), asset(FinanceAssetType.CRYPTO, sold = true))
        val totals = portfolioComposition(assets, listOf(card()))
        assertEquals(listOf("EUR", "USD"), totals.map { it.currency })
        assertEquals(BigDecimal("100.91"), totals[0].liquid)
        assertEquals(BigDecimal("225.00"), totals[0].illiquid)
        assertEquals(BigDecimal("25.00"), totals[0].tcg)
        assertEquals(BigDecimal("325.91"), totals[0].total)
        assertEquals(BigDecimal("50"), totals[1].total)
        assertEquals(BigDecimal.ZERO, totals[1].tcg)
    }

    @Test fun `TCG only portfolio creates EUR composition while net worth formula stays independent`() {
        val data = FinanceData(tcgCards = listOf(card()))
        assertEquals(BigDecimal("25.00"), portfolioComposition(data.assets, data.tcgCards).single().total)
        assertTrue(netWorthComponents(data.transactions, data.assets).isEmpty())
    }

    @Test fun `JSON supports nullable currency prices and SQL Lorcana category`() {
        val row = tcgCardFromJson(JSONObject("""{"id":"card","category":"lorcana","quantity":3,"purchase_price":"12.34","current_price":null,"currency":null}"""))
        assertEquals("EUR", row.currency)
        assertEquals(BigDecimal("12.34"), row.purchasePrice)
        assertNull(row.currentPrice)
        assertFalse(row.valued)
        assertEquals("Disney Lorcana", tcgPortfolioTotals(listOf(row.copy(currentPrice = BigDecimal.ONE))).single().label)
    }

    @Test fun `large integer quantity and decimal price aggregate without double or integer overflow`() {
        val row = tcgCardFromJson(JSONObject("""{"id":"card","category":"magic","quantity":2147483647,"purchase_price":"99999999.99","current_price":"99999999.99","currency":"EUR"}"""))
        val total = tcgPortfolioTotals(listOf(row, row)).single()
        assertEquals(4294967294L, total.pieces)
        assertEquals(BigDecimal("429496729357050327.06"), total.value)
    }

    @Test fun `fractional overflowing negative quantity and negative cost fail explicitly`() {
        fun json(quantity: String, price: String = "1.00") = JSONObject("""{"id":"card","category":"magic","quantity":"$quantity","purchase_price":"$price","current_price":null,"currency":"EUR"}""")
        assertThrows(ArithmeticException::class.java) { tcgCardFromJson(json("1.5")) }
        assertThrows(ArithmeticException::class.java) { tcgCardFromJson(json("2147483648")) }
        assertThrows(IllegalArgumentException::class.java) { tcgCardFromJson(json("-1")) }
        assertThrows(IllegalArgumentException::class.java) { tcgCardFromJson(json("1", "-1.00")) }
    }
}
