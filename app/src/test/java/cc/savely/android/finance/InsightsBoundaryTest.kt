package cc.savely.android.finance

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Literal thresholds, signed half ties and ordering in the production Insights calculation. */
class InsightsBoundaryTest {
    private val today = LocalDate.parse("2026-06-15")
    private fun transaction(id: String, date: String, amount: String, category: String? = null,
        description: String = id, currency: String = "EUR") = FinanceTransaction(id, TransactionType.EXPENSE,
        BigDecimal(amount), currency, LocalDate.parse(date), description, category)
    private fun position(current: String) = FinanceAsset("position", "QA Position", "V08", FinanceAssetType.STOCK,
        BigDecimal.ONE, BigDecimal("200"), BigDecimal(current), "EUR", today.minusDays(30), null, null, "")

    @Test fun negativeLossDisplayUsesWebSignedHalfTiesWithoutChangingItsExactThreshold() {
        listOf("169" to "-15", "167" to "-16", "168.98" to "-16", "169.02" to "-15", "170" to "-15").forEach { (current, expected) ->
            val insight = financeInsights(FinanceData(assets = listOf(position(current))), today).single()
            assertEquals(InsightKind.PORTFOLIO_LOSS, insight.kind)
            assertEquals(InsightSeverity.WARNING, insight.severity)
            assertEquals("V08: $expected% rispetto al prezzo di acquisto.", insight.message)
            assertEquals(0, BigDecimal(expected).compareTo(insight.textValues.toMap().getValue("percent") as BigDecimal))
        }
        assertTrue("One cent above the exact loss threshold remains excluded",
            financeInsights(FinanceData(assets = listOf(position("170.01"))), today).isEmpty())
    }

    @Test fun budgetRequiresStrictOverrunAndMatchingCurrencyMonthAndPositiveLimit() {
        val category = FinanceCategory("budget", "QA Budget {{amount}}", TransactionType.EXPENSE)
        val base = FinanceData(categories = listOf(category), budgets = listOf(
            FinanceBudget("eur", category.id, BigDecimal("100"), "EUR"),
            FinanceBudget("usd", category.id, BigDecimal("1000"), "USD"),
            FinanceBudget("zero", category.id, BigDecimal.ZERO, "EUR")), transactions = listOf(
            transaction("current", "2026-06-10", "100", category.id),
            transaction("foreign", "2026-06-10", "100", category.id, currency = "USD"),
            transaction("prior", "2026-05-10", "999", category.id)))
        assertTrue("Equality, foreign spend and prior-month spend must not create a budget overrun",
            financeInsights(base, today).none { it.kind == InsightKind.BUDGET_EXCEEDED })
        val updated = base.copy(transactions = base.transactions.map { if (it.id == "current") it.copy(amount = BigDecimal("100.01")) else it })
        val result = financeInsights(updated, today).filter { it.kind == InsightKind.BUDGET_EXCEEDED }
        assertEquals(listOf("budget-eur"), result.map { it.id })
        assertEquals(BigDecimal("0.01"), result.single().value)
        assertEquals(InsightSeverity.WARNING, result.single().severity)
        assertEquals("Speso 100,01\u00a0€ su 100,00\u00a0€.", result.single().message)
        assertEquals("QA Budget {{amount}}", result.single().textValues.toMap()["name"])
        assertTrue("Account-provided category names remain opaque", result.single().localizedValueKeys.isEmpty())
    }

    @Test fun sixRemainingDetectorsKeepLiteralSeverityOrderAndDoNotMutateTheirInputs() {
        val data = aggregate()
        val before = data.copy(transactions = data.transactions.map { it.copy() }, assets = data.assets.map { it.copy() })
        val result = financeInsights(data, today)
        assertEquals(listOf("spending-anomaly-EUR-anomaly", "budget-budget-eur", "recurring-price-EUR:v08 netflix",
            "loss-position", "category-trend-EUR-trend", "new-recurring-EUR:v08 netflix"), result.map { it.id })
        assertEquals(listOf(InsightKind.SPENDING_ANOMALY, InsightKind.BUDGET_EXCEEDED, InsightKind.RECURRING_PRICE_CHANGE,
            InsightKind.PORTFOLIO_LOSS, InsightKind.CATEGORY_TREND, InsightKind.NEW_RECURRING_DETECTED), result.map { it.kind })
        assertEquals(4, result.count { it.severity == InsightSeverity.WARNING })
        assertEquals(2, result.count { it.severity == InsightSeverity.INFO })
        assertEquals(0, result.count { it.severity == InsightSeverity.POSITIVE })
        assertEquals(BigDecimal("20"), result.first().value)
        assertEquals(BigDecimal("1"), result[1].value)
        assertEquals("10", result[2].textValues.toMap()["percent"].toString())
        assertEquals("25", result[4].textValues.toMap()["percent"].toString())
        assertEquals(3, result[5].textValues.toMap()["count"])
        assertEquals(before, data)
        assertFalse(result.any { it.kind in setOf(InsightKind.SAVING_MONTH, InsightKind.NET_WORTH_MILESTONE) })
    }

    private fun aggregate(): FinanceData = FinanceData(
        categories = listOf(FinanceCategory("anomaly", "QA Anomaly", TransactionType.EXPENSE),
            FinanceCategory("budget", "QA Budget", TransactionType.EXPENSE), FinanceCategory("trend", "QA Trend", TransactionType.EXPENSE)),
        transactions = listOf(transaction("anomaly-old", "2026-05-10", "50", "anomaly"),
            transaction("anomaly-current", "2026-06-10", "70", "anomaly"), transaction("budget-current", "2026-06-10", "101", "budget"),
            transaction("trend-one", "2025-10-10", "40", "trend"), transaction("trend-two", "2025-11-10", "40", "trend"),
            transaction("trend-three", "2025-12-10", "40", "trend"), transaction("trend-four", "2026-04-10", "50", "trend"),
            transaction("trend-five", "2026-05-10", "50", "trend"),
            transaction("recurring-one", "2026-04-10", "10", description = "V08 Netflix"),
            transaction("recurring-two", "2026-05-10", "10", description = "V08 Netflix"),
            transaction("recurring-three", "2026-06-10", "11", description = "V08 Netflix")),
        budgets = listOf(FinanceBudget("budget-eur", "budget", BigDecimal("100"), "EUR")), assets = listOf(position("169")),
    )
}
