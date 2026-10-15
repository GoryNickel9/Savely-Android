package cc.savely.android.modules

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class PokerQualificationModelsTest {
    @Test fun `spending median after noon excludes the boundary and empty months but includes future expense months`() {
        val reference = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC)
        fun expense(day: String, value: Double, type: String = "expense") = PokerCalculations.Expense(LocalDate.parse(day), value, type)
        val rows = listOf(expense("2024-06-14", 9999.0), expense("2024-06-15", 9999.0),
            expense("2026-01-01", 100.0), expense("2026-01-31", 50.0),
            expense("2026-03-15", 250.0), expense("2027-01-01", 300.0), expense("2026-02-01", 9999.0, "income"))
        assertEquals(250.0, PokerCalculations.medianMonthlySpending(rows, reference), 0.0)
        assertEquals(200.0, PokerCalculations.medianMonthlySpending(rows.filter { it.date.year < 2027 }, reference), 0.0)
        assertEquals(0.0, PokerCalculations.medianMonthlySpending(rows.take(2), reference), 0.0)
        assertEquals(0.0, PokerCalculations.medianMonthlySpending(emptyList(), reference), 0.0)
        assertEquals(900.0, PokerCalculations.nextCut(250.0 + 25.0, 0.5, -350.0), 0.0)
        assertEquals(-50.0, PokerCalculations.nextCut(250.0 + 25.0, 0.5, 600.0), 0.0)
    }

    @Test fun `weighted yearly rates and median differ from averaging monthly hourly rates`() {
        val rows = listOf(PokerCalculations.hourly(60.0, 100.0, 200.0), PokerCalculations.hourly(180.0, -40.0, -80.0))
        val year = PokerCalculations.aggregate(rows)
        assertEquals(4.0, year.hours, 0.0); assertEquals(60.0, year.netWon, 0.0); assertEquals(120.0, year.netWonEv, 0.0)
        assertEquals(15.0, year.hourlyRate, 0.0); assertEquals(30.0, year.hourlyRateEv, 0.0)
        assertEquals(2.0, PokerCalculations.median(rows.map { it.hours }), 0.0)
        assertEquals(25.0, PokerCalculations.rakebackPercentage(100.0 + 300.0, 10.0 + 90.0), 0.0)
        assertEquals(0.0, PokerCalculations.rakebackPercentage(0.0, 90.0), 0.0)
    }

    @Test fun `quarter ties use the exact binary rate sign while invalid deals never yield a target`() {
        assertEquals("0,13", pokerRateMoney(0.25 * 0.5).removeSuffix("€").trim())
        assertEquals("-0,13", pokerRateMoney(-0.25 * 0.5).removeSuffix("€").trim())
        listOf(0.0, -0.5, 1.00001, Double.NaN, Double.POSITIVE_INFINITY).forEach { deal ->
            assertThrows(IllegalArgumentException::class.java) { PokerCalculations.nextCut(275.0, deal, -350.0) }
        }
        assertEquals(275.0, PokerCalculations.nextCut(275.0, 1.0, 0.0), 0.0)
        val overflow = assertThrows(IllegalArgumentException::class.java) { PokerCalculations.nextCut(1e10, 1e-300, 0.0) }
        assertEquals("Inserisci un numero valido", overflow.message)
    }
}
