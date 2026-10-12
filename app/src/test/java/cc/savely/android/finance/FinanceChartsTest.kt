package cc.savely.android.finance

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FinanceChartsTest {
    private val today = LocalDate.parse("2024-03-15")
    private fun row(date: String, amount: String, type: TransactionType = TransactionType.EXPENSE,
        currency: String = "EUR", category: String? = "food", id: String = "$date-$amount-$currency-$type") =
        FinanceTransaction(id, type, amount.toBigDecimal(), currency, LocalDate.parse(date), "opaque name", category)
    private fun range(from: String, to: String) = DashboardRange(LocalDate.parse(from), LocalDate.parse(to))
    private fun decimal(expected: String, actual: BigDecimal) = assertEquals(expected, 0, expected.toBigDecimal().compareTo(actual))

    @Test fun `periods cover leap month full year and inclusive since dates`() {
        assertEquals(range("2024-01-01", "2024-12-31"), resolveFinanceChartRange(FinanceChartPeriod.YEAR, emptyList(), today))
        assertEquals(range("2024-02-01", "2024-02-29"), resolveFinanceChartRange(FinanceChartPeriod.MONTH, emptyList(), today, month = 2))
        assertEquals(range("2024-03-15", "2024-03-15"), resolveFinanceChartRange(FinanceChartPeriod.SINCE, emptyList(), today, fromText = "2024-03-15"))
        assertEquals(range("2024-02-29", "2024-03-01"), resolveFinanceChartRange(FinanceChartPeriod.BETWEEN, emptyList(), today,
            fromText = "2024-02-29", toText = "2024-03-01"))
    }

    @Test fun `all range uses complete unsorted data including future dates and empty data is one day`() {
        val rows = listOf(row("2025-01-01", "1"), row("2020-01-01", "1"), row("2024-02-29", "1"))
        assertEquals(range("2020-01-01", "2025-01-01"), resolveFinanceChartRange(FinanceChartPeriod.ALL, rows, today))
        assertEquals(DashboardRange(today, today), resolveFinanceChartRange(FinanceChartPeriod.ALL, emptyList(), today))
    }

    @Test fun `custom dates reject malformed nonexistent reversed and future since boundaries`() {
        listOf("2024-2-29", "2023-02-29", "0000-01-01", "2024-03-15T00:00:00Z", " 2024-03-15").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { resolveFinanceChartRange(FinanceChartPeriod.BETWEEN, emptyList(), today,
                fromText = invalid, toText = "2024-03-15") }
        }
        assertThrows(IllegalArgumentException::class.java) { resolveFinanceChartRange(FinanceChartPeriod.BETWEEN, emptyList(), today,
            fromText = "2024-03-16", toText = "2024-03-15") }
        assertThrows(IllegalArgumentException::class.java) { resolveFinanceChartRange(FinanceChartPeriod.SINCE, emptyList(), today, fromText = "2024-03-16") }
        assertThrows(IllegalArgumentException::class.java) { resolveFinanceChartRange(FinanceChartPeriod.MONTH, emptyList(), today, month = 13) }
        assertThrows(IllegalArgumentException::class.java) { resolveFinanceChartRange(FinanceChartPeriod.YEAR, emptyList(), today, year = 10000) }
    }

    @Test fun `optional chart bounds use full data extrema and empty data remains a day`() {
        val rows = listOf(row("2024-02-01", "1"), row("2024-04-01", "2"))
        assertEquals(range("2024-02-01", "2024-04-01"), resolveFinanceChartRange(FinanceChartPeriod.BETWEEN, rows, today))
        assertEquals(range("2024-02-01", "2024-03-10"), resolveFinanceChartRange(FinanceChartPeriod.BETWEEN, rows, today, toText = "2024-03-10"))
        assertEquals(range("2024-02-10", "2024-04-01"), resolveFinanceChartRange(FinanceChartPeriod.BETWEEN, rows, today, fromText = "2024-02-10"))
        assertEquals(range("2024-02-01", "2024-03-15"), resolveFinanceChartRange(FinanceChartPeriod.SINCE, rows, today))
        assertEquals(DashboardRange(today, today), resolveFinanceChartRange(FinanceChartPeriod.BETWEEN, emptyList(), today))
    }

    @Test fun `filters use inclusive boundaries ID currency and type and preserve input`() {
        val rows = listOf(row("2024-02-28", "99"), row("2024-02-29", "1", id = "first"),
            row("2024-03-01", "2", id = "last"), row("2024-03-02", "99"),
            row("2024-03-01", "3", currency = "USD"), row("2024-03-01", "4", TransactionType.INCOME),
            row("2024-03-01", "5", category = "other"), row("2024-03-01", "6", category = null))
        val snapshot = rows.toList()
        val period = range("2024-02-29", "2024-03-01")
        assertEquals(listOf("first", "last"), filterFinanceChartTransactions(rows, period,
            FinanceChartFilter(TransactionType.EXPENSE, "EUR", "food")).map { it.id })
        assertEquals(listOf(null), filterFinanceChartTransactions(rows, period, FinanceChartFilter(uncategorizedOnly = true)).map { it.categoryId })
        assertEquals(6, filterFinanceChartTransactions(rows, period).size)
        assertEquals(snapshot, rows)
        assertThrows(IllegalArgumentException::class.java) { FinanceChartFilter(categoryId = "food", uncategorizedOnly = true) }
    }

    @Test fun `daily series conserve exact decimals zero buckets range-only cumulative and currency isolation`() {
        val rows = listOf(row("2024-02-28", "999", TransactionType.INCOME), row("2024-02-29", "0.10", TransactionType.INCOME),
            row("2024-02-29", "0.20", TransactionType.INCOME), row("2024-03-02", "0.07"),
            row("2024-03-01", "100", currency = "USD"), row("2024-03-03", "999"))
        val points = financeChartSeries(rows.reversed(), range("2024-02-29", "2024-03-02"), "EUR", FinanceChartGranularity.DAILY)
        assertEquals(listOf("2024-02-29", "2024-03-01", "2024-03-02"), points.map { it.date.toString() })
        decimal("0.30", points.first().income)
        decimal("0", points[1].net)
        decimal("0.30", points[1].cumulativeBalance)
        decimal("0.23", points.last().cumulativeBalance)
        decimal("0.30", points.fold(BigDecimal.ZERO) { sum, point -> sum + point.income })
        decimal("0.07", points.fold(BigDecimal.ZERO) { sum, point -> sum + point.expenses })
        assertEquals(points, financeChartSeries(rows, range("2024-02-29", "2024-03-02"), "EUR", FinanceChartGranularity.DAILY))
    }

    @Test fun `week anchors Monday across year without importing outside range amounts`() {
        val rows = listOf(row("2023-12-25", "999"), row("2023-12-31", "1"), row("2024-01-01", "2"), row("2024-01-02", "999"))
        val points = financeChartSeries(rows, range("2023-12-31", "2024-01-01"), "EUR", FinanceChartGranularity.WEEKLY)
        assertEquals(listOf("2023-12-25", "2024-01-01"), points.map { it.date.toString() })
        decimal("1", points.first().expenses)
        decimal("2", points.last().expenses)
        decimal("-3", points.last().cumulativeBalance)
    }

    @Test fun `month and year series fill empty buckets and retain exact large fractional amounts`() {
        val rows = listOf(row("2023-12-31", "999999999999.12345678", TransactionType.INCOME), row("2024-02-29", "0.00000001"))
        val months = financeChartSeries(rows, range("2023-12-31", "2024-02-29"), "EUR", FinanceChartGranularity.MONTHLY)
        assertEquals(listOf("2023-12-01", "2024-01-01", "2024-02-01"), months.map { it.date.toString() })
        decimal("0", months[1].net)
        decimal("999999999999.12345677", months.last().cumulativeBalance)
        val years = financeChartSeries(rows, range("2023-12-31", "2025-01-01"), "EUR", FinanceChartGranularity.YEARLY)
        assertEquals(listOf(2023, 2024, 2025), years.map { it.date.year })
        decimal("0", years.last().net)
        decimal("999999999999.12345677", years.last().cumulativeBalance)
    }

    @Test fun `category totals keep distinct IDs same names uncategorized removed and separate type denominators`() {
        val categories = listOf(FinanceCategory("food", "Same", TransactionType.EXPENSE),
            FinanceCategory("other", "Same", TransactionType.EXPENSE), FinanceCategory("salary", "Income", TransactionType.INCOME))
        val rows = listOf(row("2024-03-01", "10"), row("2024-03-01", "20", category = "other"),
            row("2024-03-01", "30", category = null), row("2024-03-01", "40", category = "removed"),
            row("2024-03-01", "50", TransactionType.INCOME, category = "salary"), row("2024-03-01", "999", currency = "USD"))
        val slices = financeChartCategoryBreakdown(rows, categories, "EUR")
        val expense = slices.filter { it.type == TransactionType.EXPENSE }
        assertEquals(4, expense.size)
        decimal("100", expense.fold(BigDecimal.ZERO) { sum, slice -> sum + slice.amount })
        assertEquals(listOf("Same", "Same"), expense.filter { it.name != null }.map { it.name })
        assertNull(expense.first { it.categoryId == "removed" }.name)
        assertNull(expense.first { it.categoryId == null }.name)
        decimal("40", expense.first { it.categoryId == "removed" }.percent)
        decimal("100", slices.single { it.type == TransactionType.INCOME }.percent)
    }

    @Test fun `category nonpositive inputs remain reconciled and mismatched type names are not borrowed`() {
        val rows = listOf(row("2024-03-01", "10"), row("2024-03-01", "-10", category = "refund"), row("2024-03-01", "0", category = null))
        val slices = financeChartCategoryBreakdown(rows, listOf(FinanceCategory("food", "Wrong type", TransactionType.INCOME)), "EUR")
        assertEquals(3, slices.size)
        assertTrue(slices.all { it.percent.compareTo(BigDecimal.ZERO) == 0 })
        assertNull(slices.first { it.categoryId == "food" }.name)
        decimal("0", slices.fold(BigDecimal.ZERO) { sum, slice -> sum + slice.amount })
    }

    @Test fun `bucket limits are inclusive for all cadences and fail before truncation`() {
        val start = LocalDate.parse("0001-01-01")
        val lasts = mapOf(FinanceChartGranularity.DAILY to start.plusDays(2399), FinanceChartGranularity.WEEKLY to start.plusWeeks(2399),
            FinanceChartGranularity.MONTHLY to start.plusMonths(2399), FinanceChartGranularity.YEARLY to start.plusYears(2399))
        lasts.forEach { (cadence, last) ->
            assertEquals(2400, financeChartBucketDates(DashboardRange(start, last), cadence).size)
            val overflow = when (cadence) {
                FinanceChartGranularity.DAILY -> last.plusDays(1)
                FinanceChartGranularity.WEEKLY -> last.plusWeeks(1)
                FinanceChartGranularity.MONTHLY -> last.plusMonths(1)
                FinanceChartGranularity.YEARLY -> last.plusYears(1)
            }
            assertThrows(IllegalArgumentException::class.java) { financeChartBucketDates(DashboardRange(start, overflow), cadence) }
        }
    }

    @Test fun `cumulative cadence switches at day and month bounds with complete yearly fallback`() {
        val start = LocalDate.parse("1800-01-01")
        assertEquals(FinanceChartGranularity.DAILY, financeChartCumulativeGranularity(DashboardRange(start, start.plusDays(365))))
        assertEquals(FinanceChartGranularity.MONTHLY, financeChartCumulativeGranularity(DashboardRange(start, start.plusDays(366))))
        assertEquals(FinanceChartGranularity.MONTHLY, financeChartCumulativeGranularity(range("1800-01-01", "1999-12-31")))
        val longRange = range("1800-01-01", "2000-01-01")
        assertEquals(FinanceChartGranularity.YEARLY, financeChartCumulativeGranularity(longRange))
        val rows = listOf(row("1800-01-01", "1", TransactionType.INCOME), row("2000-01-01", "2", TransactionType.INCOME))
        val points = financeChartSeries(rows, longRange, "EUR", financeChartCumulativeGranularity(longRange))
        assertEquals(201, points.size)
        decimal("3", points.last().cumulativeBalance)
    }
}
