package cc.savely.android.finance

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioModelsTest {
    private fun recurringHistory(description: String, amount: String, count: Int, currency: String = "EUR", gap: Long = 30) =
        (0 until count).map { index -> FinanceTransaction("$description-$currency-$index", TransactionType.EXPENSE,
            amount.toBigDecimal(), currency, LocalDate.parse("2026-06-15").minusDays((count - index - 1) * gap), description, null) }

    @Test fun `new recurring selects global confidence count top three before sorting displayed amounts`() {
        val mediumTen = recurringHistory("Medium ten", "30", 10).mapIndexed { index, row ->
            if (index == 0) row.copy(amount = BigDecimal("31")) else row }
        val rows = recurringHistory("High five", "10", 5) + recurringHistory("High four", "20", 4) +
            mediumTen + recurringHistory("Excluded medium", "999", 3)
        val data = FinanceData(transactions = rows)
        assertEquals(listOf("new-recurring-EUR:high five", "new-recurring-EUR:high four", "new-recurring-EUR:medium ten"),
            newRecurringInsights(data).map { it.id })
        val display = financeInsights(data, reference)
        assertEquals(listOf("new-recurring-EUR:medium ten", "new-recurring-EUR:high four", "new-recurring-EUR:high five"), display.map { it.id })
        assertEquals(listOf(BigDecimal("30.00"), BigDecimal("20.00"), BigDecimal("10.00")), display.map { it.value })
        assertEquals(listOf(InsightSeverity.INFO, InsightSeverity.INFO, InsightSeverity.INFO), display.map { it.severity })
        assertFalse(display.any { it.warning })
        assertEquals(listOf("loss-asset-stock-EUR", "new-recurring-EUR:medium ten", "new-recurring-EUR:high four", "new-recurring-EUR:high five"),
            financeInsights(data.copy(assets = listOf(asset(purchase = "100", current = "85"))), reference).map { it.id })
    }

    @Test fun `new recurring removes inactive tracked and account ignored candidates before top three`() {
        val rows = recurringHistory("Tracked", "10", 5) + recurringHistory("Ignored", "20", 5) + recurringHistory("Available", "30", 3)
        val tracked = FinanceRecurring("r", "Pagamento Tracked 1234", BigDecimal.TEN, "EUR", null,
            RecurringFrequency.MONTHLY, 1, reference, false)
        val data = FinanceData(transactions = rows, recurring = listOf(tracked), ignoredRecurringSuggestions = setOf("EUR:ignored"))
        assertEquals(listOf("new-recurring-EUR:available"), newRecurringInsights(data).map { it.id })
        assertEquals(emptyList<FinanceInsight>(), newRecurringInsights(data.copy(ignoredRecurringSuggestions = setOf("EUR:ignored", "EUR:available"))))
    }

    @Test fun `new recurring global cap never combines or suppresses different currencies`() {
        val rows = supportedCurrencies.flatMap { currency -> recurringHistory("Subscription", "12.50", 3, currency) }
        val tracked = FinanceRecurring("r", "Subscription", BigDecimal("12.50"), "EUR", null,
            RecurringFrequency.MONTHLY, 1, reference, false)
        val data = FinanceData(transactions = rows, recurring = listOf(tracked), ignoredRecurringSuggestions = setOf("USD:subscription"))
        val insights = newRecurringInsights(data)
        // Existing deterministic key tie-break applies after equal confidence and occurrence count.
        assertEquals(listOf("new-recurring-AUD:subscription", "new-recurring-CAD:subscription", "new-recurring-CHF:subscription"), insights.map { it.id })
        assertEquals(3, insights.size)
        supportedCurrencies.forEach { currency ->
            val only = newRecurringInsights(FinanceData(transactions = recurringHistory("Subscription", "12.50", 3, currency))).single()
            assertEquals("new-recurring-$currency:subscription", only.id)
            assertEquals(BigDecimal("12.50"), only.value)
            assertTrue(only.message.contains("12,50") && only.message.contains("3 occorrenze"))
        }
    }

    @Test fun `new recurring labels all four cadences without changing date detection`() {
        listOf(7L to "settimanale", 30L to "mensile", 90L to "trimestrale", 365L to "annuale").forEach { (gap, label) ->
            val insight = newRecurringInsights(FinanceData(transactions = recurringHistory("Abbonamento", "10", 4, gap = gap))).single()
            assertEquals("Possibile spesa ricorrente: Abbonamento", insight.title)
            assertTrue(insight.message.startsWith("Rilevata una cadenza $label di 10,00"))
            assertTrue(insight.message.endsWith("(4 occorrenze). Vuoi tenerla tracciata?"))
        }
    }
    private val reference = LocalDate.parse("2026-06-15")
    private fun transaction(date: String, amount: String, type: TransactionType = TransactionType.INCOME,
        currency: String = "EUR", category: String? = null) = FinanceTransaction("$date-$amount-$type-$currency",
        type, amount.toBigDecimal(), currency, LocalDate.parse(date), "", category)
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

    @Test fun `loss threshold rejects one cent above 85 percent before ratio rounding in every currency`() {
        supportedCurrencies.forEach { currency ->
            val data = FinanceData(assets = listOf(asset(purchase = "1000000000.00", current = "850000000.01", currency = currency)))
            // Exact loss is 14.999999999%; rounding the ratio to 10 decimals would falsely reach 15%.
            assertEquals("One cent above boundary in $currency must not trigger", emptyList<FinanceInsight>(),
                financeInsights(data, LocalDate.parse("2026-10-11")))
        }
    }

    @Test fun `loss threshold includes exact 85 percent and one cent below independently of currency`() {
        supportedCurrencies.forEach { currency ->
            listOf("850000000.00", "849999999.99").forEach { current ->
                val row = asset(purchase = "1000000000.00", current = current, currency = currency)
                val insight = financeInsights(FinanceData(assets = listOf(row)), LocalDate.parse("2026-10-11")).single()
                assertEquals("loss-${row.id}", insight.id)
                assertEquals(true, insight.warning)
                assertEquals(BigDecimal("-0.1500000000"), insight.value)
            }
        }
    }

    @Test fun `loss detector still requires positive purchase and ignores missing current price and sold positions`() {
        supportedCurrencies.forEach { currency ->
            val rows = listOf(asset(purchase = "0", current = "0", currency = currency),
                asset(purchase = "-100", current = "-200", currency = currency),
                asset(purchase = "100", current = null, currency = currency),
                asset(purchase = "100", current = "85", currency = currency, sold = "2026-10-01", soldPrice = "85"))
            assertEquals("No invalid baseline or closed position in $currency may trigger", emptyList<FinanceInsight>(),
                financeInsights(FinanceData(assets = rows), LocalDate.parse("2026-10-11")))
        }
    }

    @Test fun `saving month accepts exact 120 percent and rejects a cent below without floating arithmetic`() {
        val historical = listOf(transaction("2026-04-15", "150"), transaction("2026-04-15", "50", TransactionType.EXPENSE),
            transaction("2026-05-15", "250"), transaction("2026-05-15", "150", TransactionType.EXPENSE))
        val exact = savingMonthInsights(historical + transaction("2026-06-15", "120"), reference).single()
        assertEquals("saving-month-EUR", exact.id)
        assertEquals(InsightSeverity.POSITIVE, exact.severity)
        assertFalse(exact.warning)
        assertEquals(BigDecimal("120"), exact.value)
        assertEquals(emptyList<FinanceInsight>(), savingMonthInsights(historical + transaction("2026-06-15", "119.99"), reference))
        // Executed web fixture returns [] here because Number computes threshold 0.18000000000000002.
        val decimal = savingMonthInsights(listOf(transaction("2026-04-15", "0.10"), transaction("2026-05-15", "0.20"),
            transaction("2026-06-15", "0.18")), reference).single()
        assertEquals(BigDecimal("0.18"), decimal.value)
    }

    @Test fun `saving month requires historical populated months positive average and positive current net`() {
        val noSignals = listOf(emptyList(), listOf(transaction("2026-06-15", "120")),
            listOf(transaction("2026-05-15", "50"), transaction("2026-05-15", "50", TransactionType.EXPENSE), transaction("2026-06-15", "120")),
            listOf(transaction("2026-05-15", "50"), transaction("2026-05-15", "100", TransactionType.EXPENSE), transaction("2026-06-15", "120")),
            listOf(transaction("2026-05-15", "100"), transaction("2026-06-15", "100"), transaction("2026-06-15", "100", TransactionType.EXPENSE)),
            listOf(transaction("2026-05-15", "100"), transaction("2026-06-15", "100"), transaction("2026-06-15", "120", TransactionType.EXPENSE)))
        noSignals.forEach { assertEquals(emptyList<FinanceInsight>(), savingMonthInsights(it, reference)) }
    }

    @Test fun `saving baseline uses monthly nets and populated months including zero net months`() {
        assertEquals(emptyList<FinanceInsight>(), savingMonthInsights(listOf(transaction("2026-05-15", "1000"),
            transaction("2026-06-15", "120")), reference)) // Zero-filling 12 months would wrongly trigger.
        val rows = listOf(transaction("2026-04-15", "500", category = "food"), transaction("2026-04-16", "400", TransactionType.EXPENSE, category = null),
            transaction("2026-05-15", "50"), transaction("2026-05-16", "50", TransactionType.EXPENSE, category = "removed"),
            transaction("2026-06-15", "80"), transaction("2026-06-16", "20", TransactionType.EXPENSE))
        assertEquals(BigDecimal("60"), savingMonthInsights(rows, reference).single().value) // Mean of 100 and 0 is 50.
    }

    @Test fun `saving month date window excludes boundary day and includes next day`() {
        val base = listOf(transaction("2026-05-15", "100"), transaction("2026-06-15", "120"))
        assertEquals(BigDecimal("120"), savingMonthInsights(base + transaction("2025-06-15", "9000"), reference).single().value)
        assertEquals(emptyList<FinanceInsight>(), savingMonthInsights(base + transaction("2025-06-16", "1000"), reference))
    }

    @Test fun `saving window preserves JavaScript month overflow without changing recurring clamp`() {
        assertEquals(LocalDate.parse("2026-03-03"), insightWindowStart(LocalDate.parse("2026-05-31"), 3))
        val leap = LocalDate.parse("2024-02-29")
        assertEquals(LocalDate.parse("2023-03-01"), insightWindowStart(leap, 12))
        val rows = listOf(transaction("2023-02-28", "9000"), transaction("2023-03-01", "9000"),
            transaction("2023-03-02", "100"), transaction("2024-02-29", "120"))
        assertEquals(BigDecimal("120"), savingMonthInsights(rows, leap).single().value)
        assertEquals(LocalDate.parse("2025-02-28"), nextDueDate(leap, RecurringFrequency.YEARLY))
    }

    @Test fun `saving month includes future dates in baseline and current month as web does`() {
        val base = listOf(transaction("2026-05-15", "100"), transaction("2026-06-15", "120"))
        assertEquals(emptyList<FinanceInsight>(), savingMonthInsights(base + transaction("2026-07-15", "1000"), reference))
        val futureCurrent = listOf(transaction("2026-05-15", "100"), transaction("2026-06-05", "70"), transaction("2026-06-30", "50"))
        assertEquals(BigDecimal("120"), savingMonthInsights(futureCurrent, reference).single().value)
    }

    @Test fun `saving signals keep currencies independent when web combined amounts suppress or create signal`() {
        val rows = listOf(transaction("2026-05-15", "100"), transaction("2026-05-15", "100", currency = "USD"),
            transaction("2026-06-15", "120"), transaction("2026-06-15", "100", currency = "USD"))
        assertEquals(listOf("saving-month-EUR"), savingMonthInsights(rows, reference).map { it.id })
        val other = listOf(transaction("2026-05-15", "100"), transaction("2026-05-15", "1", currency = "USD"),
            transaction("2026-06-15", "110"), transaction("2026-06-15", "200", currency = "USD"))
        assertEquals(listOf("saving-month-USD"), savingMonthInsights(other, reference).map { it.id })
        supportedCurrencies.forEach { currency ->
            assertEquals("saving-month-$currency", savingMonthInsights(listOf(transaction("2026-05-15", "100", currency = currency),
                transaction("2026-06-15", "120", currency = currency)), reference).single().id)
        }
    }

    @Test fun `saving threshold does not use the rounded average shown in the message`() {
        val rows = listOf(transaction("2026-03-15", "0.01"), transaction("2026-04-15", "0.01"),
            transaction("2026-05-15", "0.01"), transaction("2026-05-16", "0.01", TransactionType.EXPENSE),
            transaction("2026-06-15", "0.01"))
        // Historical average is 0.02/3, displayed as 0.01; using that rounded average would reject this.
        assertEquals(BigDecimal("0.01"), savingMonthInsights(rows, reference).single().value)
    }

    @Test fun `insights keep existing warning defaults and sort severity before absolute value`() {
        val rows = listOf(transaction("2026-05-15", "100"), transaction("2026-05-15", "100", currency = "USD"),
            transaction("2026-06-15", "320"), transaction("2026-06-15", "200", TransactionType.EXPENSE, category = "food"),
            transaction("2026-06-15", "240", currency = "USD"))
        val insights = financeInsights(FinanceData(transactions = rows, budgets = listOf(FinanceBudget("b", "food", BigDecimal("100"), "EUR")),
            assets = listOf(asset(purchase = "100", current = "85"))), reference)
        assertEquals(listOf("budget-b", "loss-asset-stock-EUR", "saving-month-USD", "saving-month-EUR"), insights.map { it.id })
        assertEquals(listOf(InsightSeverity.WARNING, InsightSeverity.WARNING, InsightSeverity.POSITIVE, InsightSeverity.POSITIVE), insights.map { it.severity })
    }
}
