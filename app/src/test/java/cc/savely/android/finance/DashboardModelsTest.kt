package cc.savely.android.finance

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DashboardModelsTest {
    private val today = LocalDate.parse("2026-06-15")
    private fun tx(date: String, amount: String, type: TransactionType = TransactionType.EXPENSE, currency: String = "EUR", category: String? = "food") =
        FinanceTransaction("$date-$amount-$currency-$type", type, amount.toBigDecimal(), currency, LocalDate.parse(date), "", category)

    @Test fun `dashboard presets have inclusive local boundaries matching the web`() {
        assertEquals(DashboardRange(LocalDate.parse("2026-06-01"), today), resolveDashboardRange(DashboardPreset.CURRENT_MONTH, today))
        assertEquals(DashboardRange(LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31")), resolveDashboardRange(DashboardPreset.PREVIOUS_MONTH, today))
        assertEquals(DashboardRange(LocalDate.parse("2026-05-17"), today), resolveDashboardRange(DashboardPreset.LAST_THIRTY_DAYS, today))
        assertEquals(DashboardRange(LocalDate.parse("2026-01-01"), today), resolveDashboardRange(DashboardPreset.CURRENT_YEAR, today))
        assertEquals(30L, resolveDashboardRange(DashboardPreset.LAST_THIRTY_DAYS, today).days)
        assertEquals(LocalDate.parse("2024-02-29"), resolveDashboardRange(DashboardPreset.PREVIOUS_MONTH, LocalDate.parse("2024-03-15")).to)
    }

    @Test fun `dashboard custom reverses valid bounds and rejects incomplete malformed nonexistent dates`() {
        val expected = DashboardRange(LocalDate.parse("2026-05-01"), LocalDate.parse("2026-06-15"))
        assertEquals(expected, resolveDashboardRange(DashboardPreset.CUSTOM, today, "2026-06-15", "2026-05-01"))
        listOf("", "2026-2-03", "2026-02-30", "2026-06-15T00:00:00Z", "0000-01-01", "2026-06-15 trailing").forEach {
            assertThrows(IllegalArgumentException::class.java) { resolveDashboardRange(DashboardPreset.CUSTOM, today, it, "2026-06-15") }
        }
    }

    @Test fun `dashboard previous period has equal inclusive day duration across leap and year boundaries`() {
        val range = DashboardRange(LocalDate.parse("2024-03-01"), LocalDate.parse("2024-03-15"))
        assertEquals(DashboardRange(LocalDate.parse("2024-02-15"), LocalDate.parse("2024-02-29")), range.previous())
        assertEquals(range.days, range.previous().days)
        val single = DashboardRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-01"))
        assertEquals(LocalDate.parse("2025-12-31"), single.previous().from)
        assertEquals(single.previous().from, single.previous().to)
    }

    @Test fun `dashboard statistics compare exact amounts and cumulative balances at each period end`() {
        val rows = listOf(tx("2026-04-01", "30", TransactionType.INCOME), tx("2026-05-17", "100", TransactionType.INCOME),
            tx("2026-05-31", "40"), tx("2026-06-01", "120", TransactionType.INCOME), tx("2026-06-15", "30"),
            tx("2026-06-16", "99999", TransactionType.INCOME))
        val result = dashboardCurrencyStats(rows, resolveDashboardRange(DashboardPreset.CURRENT_MONTH, today)).single()
        assertEquals(DashboardAmounts(BigDecimal("120"), BigDecimal("30"), BigDecimal("180")), result.current)
        assertEquals(DashboardAmounts(BigDecimal("100"), BigDecimal("40"), BigDecimal("90")), result.previous)
        assertEquals("20%", formatDashboardPercent(result.incomeDelta!!))
        assertEquals("-25%", formatDashboardPercent(result.expenseDelta!!))
        assertEquals("50%", formatDashboardPercent(result.savingsDelta!!))
        assertEquals("100%", formatDashboardPercent(result.balanceDelta!!))
        assertEquals("75%", formatDashboardPercent(result.current.savingsRate))
    }

    @Test fun `dashboard zero comparison remains unavailable and negative baseline uses its absolute value`() {
        assertEquals(null, dashboardPercentChange(BigDecimal.TEN, BigDecimal.ZERO))
        assertEquals("150%", formatDashboardPercent(dashboardPercentChange(BigDecimal("50"), BigDecimal("-100"))!!))
        assertEquals(BigDecimal.ZERO, DashboardAmounts(BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO).savingsRate)
        assertEquals("-50%", formatDashboardPercent(DashboardAmounts(BigDecimal("100"), BigDecimal("150"), BigDecimal.ZERO).savingsRate))
    }

    @Test fun `dashboard currencies stay separate and future only currencies do not create cards`() {
        val rows = listOf(tx("2026-06-01", "0.10", TransactionType.INCOME), tx("2026-06-02", "0.20", TransactionType.INCOME),
            tx("2026-06-01", "100", TransactionType.INCOME, "USD"), tx("2026-06-30", "999", TransactionType.INCOME, "JPY"))
        val before = rows.map { it.amount }
        val stats = dashboardCurrencyStats(rows, resolveDashboardRange(DashboardPreset.CURRENT_MONTH, today))
        assertEquals(listOf("EUR", "USD"), stats.map { it.currency })
        assertEquals(BigDecimal("0.30"), stats.first().current.income)
        assertEquals(BigDecimal("100"), stats.last().current.income)
        assertEquals(before, rows.map { it.amount })
    }

    @Test fun `dashboard recent records are filtered sorted deterministically and limited to five`() {
        val rows = (1..7).map { tx("2026-06-0$it", "$it") } + tx("2026-05-31", "1000") + tx("2026-06-16", "2000")
        val recent = dashboardRecentTransactions(rows.reversed(), resolveDashboardRange(DashboardPreset.CURRENT_MONTH, today))
        assertEquals(listOf(7, 6, 5, 4, 3), recent.map { it.amount.toInt() })
        val ties = listOf(rows.first().copy(id = "z"), rows.first().copy(id = "a"))
        assertEquals(listOf("a", "z"), dashboardRecentTransactions(ties, resolveDashboardRange(DashboardPreset.CURRENT_MONTH, today)).map { it.id })
    }

    @Test fun `dashboard budgets include full current month future and match only their own category currency`() {
        val budgets = listOf(FinanceBudget("a", "food", BigDecimal("50"), "EUR"), FinanceBudget("b", "food", BigDecimal("100"), "USD"))
        val rows = listOf(tx("2026-06-01", "30"), tx("2026-06-30", "30"), tx("2026-06-10", "100", currency = "USD"),
            tx("2026-05-31", "9999"), tx("2026-06-10", "9999", category = null), tx("2026-06-10", "9999", TransactionType.INCOME))
        val result = dashboardBudgetRows(budgets, rows, today)
        assertEquals(listOf("a", "b"), result.map { it.budget.id })
        assertEquals(BigDecimal("60"), result.first().spent)
        assertEquals("120%", formatDashboardPercent(result.first().percent))
        assertEquals(listOf("EUR", "USD"), dashboardBudgetSummaries(result).map { it.currency })
        assertEquals(BigDecimal("-10"), dashboardBudgetSummaries(result).first().remaining)
    }

    @Test fun `dashboard budget summary does not double count duplicate category rows and zero limit stays zero`() {
        val budgets = listOf(FinanceBudget("a", "food", BigDecimal("20"), "EUR"), FinanceBudget("b", "food", BigDecimal("30"), "EUR"),
            FinanceBudget("zero", "other", BigDecimal.ZERO, "EUR"))
        val result = dashboardBudgetRows(budgets, listOf(tx("2026-06-01", "10"), tx("2026-06-02", "10", category = "other")), today)
        val summary = dashboardBudgetSummaries(result).single()
        assertEquals(BigDecimal("50"), summary.planned)
        assertEquals(BigDecimal("20"), summary.spent)
        assertEquals(BigDecimal.ZERO, result.last().percent)
        val huge = dashboardBudgetRows(listOf(FinanceBudget("cap", "food", BigDecimal.ONE, "EUR")), listOf(tx("2026-06-01", "10000")), today).single()
        assertEquals(BigDecimal("999"), huge.percent)
    }

    @Test fun `category donut uses exact expenses within inclusive range and separates currencies`() {
        val categories = listOf(FinanceCategory("food", "Nome {{amount}}", TransactionType.EXPENSE, "", "#123"),
            FinanceCategory("other", "Nome {{amount}}", TransactionType.EXPENSE, "", "invalid"))
        val rows = listOf(tx("2026-06-01", "0.10"), tx("2026-06-15", "0.20"), tx("2026-06-02", "0.30", category = "other"),
            tx("2026-06-01", "100", currency = "USD"), tx("2026-05-31", "1000"), tx("2026-06-16", "1000"),
            tx("2026-06-10", "999", TransactionType.INCOME), tx("2026-06-01", "200", currency = "USD", category = "other"))
        val before = rows.map { it.copy() }
        val slices = dashboardCategoryExpenses(rows, categories, resolveDashboardRange(DashboardPreset.CURRENT_MONTH, today))
        assertEquals(listOf("EUR", "EUR", "USD", "USD"), slices.map { it.currency })
        assertEquals(listOf("food", "other", "other", "food"), slices.map { it.categoryId })
        assertEquals(listOf("0.30", "0.30", "200", "100"), slices.map { it.amount.toPlainString() })
        assertEquals("50.000000000000", slices.first().percent.toPlainString())
        assertEquals("66.666666666667", slices[2].percent.toPlainString())
        assertEquals("Nome {{amount}}", slices.first().name)
        assertEquals("#123", slices.first().color)
        assertEquals(before, rows)
    }

    @Test fun `removed and absent categories share the web uncategorized bucket without losing amounts`() {
        val rows = listOf(tx("2026-06-01", "20", category = null), tx("2026-06-02", "30", category = "removed"),
            tx("2026-06-03", "40", category = "another-removed"))
        val slice = dashboardCategoryExpenses(rows, emptyList(), resolveDashboardRange(DashboardPreset.CURRENT_MONTH, today)).single()
        assertNull(slice.categoryId); assertNull(slice.name)
        assertEquals(BigDecimal("90"), slice.amount)
        assertEquals(BigDecimal("100.000000000000"), slice.percent)
    }

    @Test fun `all categories remain distinct and sorting is deterministic without other bucket`() {
        val categories = (0..20).map { FinanceCategory("id-${it.toString().padStart(2, '0')}", "Same name", TransactionType.EXPENSE, "", "#123456") }
        val rows = categories.map { tx("2026-06-01", "1", category = it.id) }
        val range = resolveDashboardRange(DashboardPreset.CURRENT_MONTH, today)
        assertEquals(categories.map { it.id }, dashboardCategoryExpenses(rows.reversed(), categories, range).map { it.categoryId })
        assertEquals(21, dashboardCategoryExpenses(rows, categories, range).size)
    }

    @Test fun `donut distinguishes no expenses zero total and invalid source data`() {
        val range = resolveDashboardRange(DashboardPreset.CURRENT_MONTH, today)
        assertTrue(dashboardCategoryExpenses(listOf(tx("2026-06-01", "50", TransactionType.INCOME)), emptyList(), range).isEmpty())
        assertEquals(BigDecimal.ZERO, dashboardCategoryExpenses(listOf(tx("2026-06-01", "0")), emptyList(), range).single().percent)
        assertThrows(IllegalArgumentException::class.java) { dashboardCategoryExpenses(listOf(tx("2026-06-01", "-1")), emptyList(), range) }
        assertThrows(IllegalArgumentException::class.java) { dashboardCategoryExpenses(listOf(tx("2026-06-01", "1", currency = "BAD")), emptyList(), range) }
        supportedCurrencies.forEach { currency ->
            assertEquals(currency, dashboardCategoryExpenses(listOf(tx("2026-06-01", "1", currency = currency)), emptyList(), range).single().currency)
        }
    }
}
