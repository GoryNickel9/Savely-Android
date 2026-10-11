package cc.savely.android.finance

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class RecurringCalendarTest {
    private fun recurring(date: String, frequency: RecurringFrequency = RecurringFrequency.MONTHLY, active: Boolean = true, interval: Int = 1, id: String = "r") =
        FinanceRecurring(id, "Abbonamento", BigDecimal("12.50"), "EUR", null, frequency, interval, LocalDate.parse(date), active)
    private fun dates(row: FinanceRecurring, month: String) = recurringCalendarOccurrences(listOf(row), YearMonth.parse(month)).map { it.date.toString() }

    @Test fun `calendar starts at the first saved due date and never backfills earlier months or days`() {
        val row = recurring("2026-06-20")
        assertEquals(emptyList<String>(), dates(row, "2026-05"))
        assertEquals(listOf("2026-06-20"), dates(row, "2026-06"))
        assertEquals(listOf("2026-07-20"), dates(row, "2026-07"))
    }

    @Test fun `calendar monthly projections retain clamp drift after short months like native processing`() {
        val row = recurring("2026-01-31")
        assertEquals(listOf("2026-02-28"), dates(row, "2026-02"))
        assertEquals(listOf("2026-03-28"), dates(row, "2026-03"))
        assertEquals(LocalDate.parse("2026-01-31"), row.nextDueDate)
    }

    @Test fun `calendar leap month and yearly february clamp do not use Insights setMonth overflow`() {
        assertEquals(listOf("2024-02-29"), dates(recurring("2024-01-31"), "2024-02"))
        assertEquals(listOf("2024-03-29"), dates(recurring("2024-01-31"), "2024-03"))
        val yearly = recurring("2024-02-29", RecurringFrequency.YEARLY)
        assertEquals(listOf("2025-02-28"), dates(yearly, "2025-02"))
        assertEquals(listOf("2028-02-28"), dates(yearly, "2028-02"))
    }

    @Test fun `calendar quarterly clamp and weekly custom intervals include each projected occurrence`() {
        assertEquals(listOf("2026-04-30"), dates(recurring("2026-01-31", RecurringFrequency.QUARTERLY), "2026-04"))
        assertEquals(listOf("2026-07-30"), dates(recurring("2026-01-31", RecurringFrequency.QUARTERLY), "2026-07"))
        assertEquals(listOf("2026-06-01", "2026-06-15", "2026-06-29"), dates(recurring("2026-06-01", RecurringFrequency.WEEKLY, interval = 2), "2026-06"))
        assertEquals(listOf("2026-06-07", "2026-06-14", "2026-06-21", "2026-06-28"), dates(recurring("2026-05-31", RecurringFrequency.WEEKLY), "2026-06"))
    }

    @Test fun `calendar paused records never generate projections even when their imported interval is invalid`() {
        assertEquals(emptyList<String>(), dates(recurring("2026-06-01", active = false, interval = 0), "2026-06"))
        assertThrows(IllegalArgumentException::class.java) { dates(recurring("2026-06-01", interval = 0), "2026-06") }
    }

    @Test fun `calendar Monday grid contains leap days once and pads only outside the month`() {
        val month = YearMonth.parse("2024-02")
        val weeks = recurringCalendarWeeks(month)
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(listOf(null, null, null, LocalDate.parse("2024-02-01")), weeks.first().take(4))
        assertEquals(29, weeks.flatten().filterNotNull().size)
        assertEquals(29, weeks.flatten().filterNotNull().distinct().size)
        assertEquals(LocalDate.parse("2024-02-29"), weeks.flatten().filterNotNull().last())
    }

    @Test fun `calendar date id ordering keeps same day multiple currencies separate and preserves records`() {
        val rows = listOf(recurring("2026-06-01", id = "z"), recurring("2026-06-01", id = "a").copy(currency = "USD"),
            recurring("2026-06-02", id = "b"))
        val before = rows.map { it.copy() }
        val result = recurringCalendarOccurrences(rows, YearMonth.parse("2026-06"))
        assertEquals(listOf("a", "z", "b"), result.map { it.recurring.id })
        assertEquals(listOf("USD", "EUR", "EUR"), result.map { it.recurring.currency })
        assertEquals(listOf("a:2026-06-01", "z:2026-06-01", "b:2026-06-02"), result.map { it.key })
        assertEquals(before, rows)
    }
}
