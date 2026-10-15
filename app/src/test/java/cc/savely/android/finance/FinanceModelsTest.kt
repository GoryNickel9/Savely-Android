package cc.savely.android.finance

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceModelsTest {
    private fun transaction(
        amount: String, date: String = "2026-10-11", type: TransactionType = TransactionType.EXPENSE,
        currency: String = "EUR", category: String? = "food",
    ) = FinanceTransaction("id-$amount-$date", type, BigDecimal(amount), currency, LocalDate.parse(date), "Spesa", category)

    @Test fun `currency display rounds halves up with two digits without changing exact input`() {
        val cases = listOf("0.305" to "0,31", "0.005" to "0,01", "-0.305" to "-0,31", "-0.005" to "-0,01")
        supportedCurrencies.forEach { currency ->
            cases.forEach { (raw, expected) ->
                val amount = raw.toBigDecimal()
                val before = amount.toPlainString()
                val formatted = formatMoney(amount, currency)
                val numericText = Regex("-?\\d+,\\d+").find(formatted)?.value
                assertEquals("$currency $raw display", expected, numericText)
                assertEquals("Formatting must retain $currency exact input", before, amount.toPlainString())
                assertEquals(3, amount.scale())
            }
        }
    }

    @Test fun `Italian amount and SQL rounding preserve decimal value`() {
        assertEquals(BigDecimal("1234.56"), parseMoney("1.234,56"))
        assertEquals(BigDecimal("10.50"), parseMoney("10,50"))
        assertEquals(BigDecimal("0.11"), parseMoney("0.105"))
        assertEquals(BigDecimal("0.00"), parseMoney("0", true))
    }

    @Test fun `invalid partial and negative amounts fail without backend write`() {
        listOf("", "NaN", "1e100", "12EUR", "1,2,3", "1.234.5,67", "0", "-1", "0.004", "10000000000000").forEach {
            assertThrows("Must reject $it", IllegalArgumentException::class.java) { parseMoney(it) }
        }
    }

    @Test fun `exchange rates validate six decimal SQL precision`() {
        assertEquals(BigDecimal("0.912346"), parseExchangeRate("0,9123456"))
        listOf("NaN", "0", "-1", "1000000000", "0.0000001").forEach {
            assertThrows(IllegalArgumentException::class.java) { parseExchangeRate(it) }
        }
    }

    @Test fun `decimal additions and currencies remain separate`() {
        val totals = currencyTotals(listOf(
            transaction("0.10", type = TransactionType.INCOME), transaction("0.20", type = TransactionType.INCOME),
            transaction("0.10"), transaction("50.00", currency = "USD"),
        ))
        assertEquals(BigDecimal("0.30"), totals[0].income)
        assertEquals(BigDecimal("0.20"), totals[0].savings)
        assertEquals("USD", totals[1].currency)
        assertEquals(BigDecimal("-50.00"), totals[1].savings)
    }

    @Test fun `date category search and type filters use inclusive local dates`() {
        val rows = listOf(transaction("1", "2026-10-01"), transaction("2", "2026-10-11"), transaction("3", "2026-09-30"))
        val filtered = filterTransactions(rows, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-11"), "food", TransactionType.EXPENSE, "SPESA")
        assertEquals(2, filtered.size)
        assertEquals(LocalDate.parse("2026-10-11"), filtered.first().date)
        assertEquals(emptyList<FinanceTransaction>(), filterTransactions(rows, categoryId = "other"))
        assertThrows(IllegalArgumentException::class.java) { filterTransactions(rows, LocalDate.parse("2026-10-11"), LocalDate.parse("2026-10-01")) }
    }

    @Test fun `search matches category name or description without requiring both`() {
        val categories = listOf(FinanceCategory("food", "Alimentari", TransactionType.EXPENSE))
        val categoryOnly = transaction("10").copy(description = "", id = "category-only")
        val descriptionOnly = transaction("20", category = null).copy(description = "Acquisto ALIMENTARI", id = "description-only")
        val other = transaction("30", category = "other").copy(description = "Trasporto", id = "other")
        val result = filterTransactions(listOf(categoryOnly, descriptionOnly, other), search = " alimentari ", categories = categories)
        assertEquals(setOf("category-only", "description-only"), result.map { it.id }.toSet())
    }

    @Test fun `category name search remains combined with date type and category filters`() {
        val categories = listOf(FinanceCategory("food", "Alimentari", TransactionType.EXPENSE))
        val inPeriod = transaction("10", "2026-10-11").copy(description = "", id = "included")
        val old = transaction("20", "2026-09-30").copy(description = "", id = "old")
        val income = transaction("30", type = TransactionType.INCOME).copy(description = "", id = "income")
        val result = filterTransactions(listOf(inPeriod, old, income), LocalDate.parse("2026-10-01"),
            LocalDate.parse("2026-10-31"), "food", TransactionType.EXPENSE, "ALIMENTARI", categories)
        assertEquals(listOf("included"), result.map { it.id })
    }

    @Test fun `missing category still permits description search and blank search includes all`() {
        val removed = transaction("10", category = "removed").copy(description = "Alimentari", id = "removed")
        val uncategorized = transaction("20", category = null).copy(description = "", id = "uncategorized")
        assertEquals(listOf("removed"), filterTransactions(listOf(removed, uncategorized), search = "alimentari").map { it.id })
        assertEquals(2, filterTransactions(listOf(removed, uncategorized), search = "  ").size)
        assertEquals(emptyList<FinanceTransaction>(), filterTransactions(listOf(uncategorized), search = "alimentari"))
    }

    @Test fun `budget uses median of populated months rather than mean or zero filled months`() {
        val rows = listOf(transaction("20", "2026-08-01"), transaction("10", "2026-08-02"),
            transaction("70", "2026-10-01"), transaction("10000", "2020-01-01"),
            transaction("500", "2026-10-01", currency = "USD"))
        assertEquals(BigDecimal("50"), medianMonthlyExpense(rows, LocalDate.parse("2026-10-11")))
        assertEquals(BigDecimal.ZERO, medianMonthlyExpense(rows, LocalDate.parse("2026-10-11"), "unknown"))
    }

    @Test fun `budget includes future populated months as web does`() {
        val rows = listOf(transaction("20", "2026-08-01"), transaction("80", "2026-12-01"))
        assertEquals(BigDecimal("50"), medianMonthlyExpense(rows, LocalDate.parse("2026-10-11")))
    }

    @Test fun `global budget median groups all categories before taking median`() {
        val today = LocalDate.parse("2026-10-11")
        val rows = listOf(
            transaction("100", "2026-01-01", category = "food"), transaction("10", "2026-02-01", category = "food"),
            transaction("10", "2026-03-01", category = "food"), transaction("10", "2026-01-01", category = "travel"),
            transaction("100", "2026-02-01", category = "travel"), transaction("10", "2026-04-01", category = "travel"),
            transaction("20", "2026-03-01", category = null),
        )
        val summary = budgetCurrencySummaries(listOf(FinanceBudget("b1", "food", BigDecimal("40"), "EUR"),
            FinanceBudget("b2", "travel", BigDecimal("10"), "EUR")), rows, today).single()
        assertEquals(BigDecimal("20"), medianMonthlyExpense(rows, today, "food") + medianMonthlyExpense(rows, today, "travel"))
        // Global populated-month totals are 110, 110, 30, 10; median is 70, not the category median sum.
        assertEquals(BigDecimal("70"), summary.monthlyMedianExpense)
        assertEquals(BigDecimal("50"), summary.totalBudget)
        assertEquals(BigDecimal("20"), summary.difference)
    }

    @Test fun `global budget preserves date boundary future dates and uncategorized expenses`() {
        val today = LocalDate.parse("2026-10-11")
        val rows = listOf(transaction("9000", today.minusDays(730).toString()),
            transaction("8000", today.minusDays(731).toString()),
            transaction("20", "2026-08-01", category = null),
            transaction("80", "2026-12-01", category = "removed"),
            transaction("7000", "2026-09-01", type = TransactionType.INCOME),
            transaction("6000", "2020-01-01", currency = "JPY"))
        val summary = budgetCurrencySummaries(listOf(FinanceBudget("b", "food", BigDecimal("60"), "EUR")), rows, today).single()
        assertEquals("EUR", summary.currency)
        assertEquals(BigDecimal("50"), summary.monthlyMedianExpense)
        assertEquals(BigDecimal("-10"), summary.difference)
    }

    @Test fun `budget currencies include unbudgeted expenses and unspent budgets without conversion`() {
        val rows = listOf(transaction("0.10", "2026-08-01"), transaction("0.20", "2026-08-01"),
            transaction("0.31", "2026-10-01"), transaction("40.00", currency = "USD"))
        val summaries = budgetCurrencySummaries(listOf(FinanceBudget("eur1", "food", BigDecimal("0.10"), "EUR"),
            FinanceBudget("eur2", "removed", BigDecimal("0.20"), "EUR"),
            FinanceBudget("gbp", "food", BigDecimal("10.00"), "GBP")), rows, LocalDate.parse("2026-10-11"))
        assertEquals(listOf("EUR", "GBP", "USD"), summaries.map { it.currency })
        assertEquals(BigDecimal("0.30"), summaries[0].totalBudget)
        assertEquals(BigDecimal("0.305"), summaries[0].monthlyMedianExpense)
        assertEquals(BigDecimal("0.005"), summaries[0].difference)
        assertEquals(BigDecimal.ZERO, summaries[1].monthlyMedianExpense)
        assertEquals(BigDecimal("-10.00"), summaries[1].difference)
        assertEquals(BigDecimal.ZERO, summaries[2].totalBudget)
        assertEquals(BigDecimal("40.00"), summaries[2].difference)
    }

    @Test fun `empty budget summary does not invent a currency or populate months with income`() {
        assertEquals(emptyList<BudgetCurrencySummary>(), budgetCurrencySummaries(emptyList(),
            listOf(transaction("100", type = TransactionType.INCOME, currency = "USD")), LocalDate.parse("2026-10-11")))
        val zero = budgetCurrencySummaries(listOf(FinanceBudget("zero", "food", BigDecimal.ZERO, "EUR")),
            emptyList(), LocalDate.parse("2026-10-11")).single()
        assertEquals(BigDecimal.ZERO, zero.totalBudget)
        assertEquals(BigDecimal.ZERO, zero.monthlyMedianExpense)
        assertEquals(BigDecimal.ZERO, zero.difference)
    }

    @Test fun `monthly quarterly yearly dates clamp to end of month`() {
        assertEquals(LocalDate.parse("2026-02-28"), nextDueDate(LocalDate.parse("2026-01-31"), RecurringFrequency.MONTHLY))
        assertEquals(LocalDate.parse("2024-02-29"), nextDueDate(LocalDate.parse("2024-01-31"), RecurringFrequency.MONTHLY))
        assertEquals(LocalDate.parse("2026-04-30"), nextDueDate(LocalDate.parse("2026-01-31"), RecurringFrequency.QUARTERLY))
        assertEquals(LocalDate.parse("2025-02-28"), nextDueDate(LocalDate.parse("2024-02-29"), RecurringFrequency.YEARLY))
        assertEquals(LocalDate.parse("2026-10-25"), nextDueDate(LocalDate.parse("2026-10-11"), RecurringFrequency.WEEKLY, 2))
        assertThrows(IllegalArgumentException::class.java) { nextDueDate(LocalDate.now(), RecurringFrequency.WEEKLY, 0) }
    }

    @Test fun `JSON transaction clears nullable fields and preserves exact decimal strings`() {
        val payload = transactionPayload(TransactionType.EXPENSE, BigDecimal("9999999999999.99"), "EUR", BigDecimal.ONE,
            null, "", LocalDate.parse("2026-10-11"))
        assertEquals("9999999999999.99", payload.getString("amount"))
        assertTrue(payload.isNull("category_id"))
        assertTrue(payload.isNull("description"))
        val decoded = transactionFromJson(JSONObject(payload.toString()).put("id", "transaction-id"))
        assertEquals(BigDecimal("9999999999999.99"), decoded.amount)
        assertNull(decoded.categoryId)
        assertEquals("", decoded.description)
        assertEquals(LocalDate.parse("2026-10-11"), decoded.date)
    }

    @Test fun `unknown backend enum fails visibly instead of being counted as expense`() {
        assertThrows(IllegalStateException::class.java) { TransactionType.fromWire("transfer") }
        assertThrows(IllegalStateException::class.java) { RecurringFrequency.fromWire("daily") }
    }

    @Test fun `JSON recurring handles null interval and category`() {
        val decoded = recurringFromJson(JSONObject("""{"id":"r","name":"Abbonamento","amount":"12.50","currency":"EUR","category_id":null,"frequency":"monthly","week_interval":null,"next_due_date":"2026-02-28","is_active":false}"""))
        assertEquals(BigDecimal("12.50"), decoded.amount)
        assertEquals(1, decoded.weekInterval)
        assertNull(decoded.categoryId)
        assertEquals(false, decoded.active)
    }

    @Test fun `SQL null currency and category appearance use safe defaults`() {
        val tx = transactionPayload(TransactionType.EXPENSE, BigDecimal.ONE, "EUR", BigDecimal.ONE, null, "", LocalDate.parse("2026-10-11"))
            .put("id", "t").put("currency", JSONObject.NULL)
        assertEquals("EUR", transactionFromJson(tx).currency)
        val category = categoryFromJson(JSONObject("""{"id":"c","name":"Test","type":"expense","icon":null,"color":null}"""))
        assertEquals("", category.icon)
        assertEquals("#22c55e", category.color)
        val budget = budgetFromJson(JSONObject("""{"id":"b","category_id":"c","amount":"0","currency":null}"""))
        assertEquals("EUR", budget.currency)
    }
}
