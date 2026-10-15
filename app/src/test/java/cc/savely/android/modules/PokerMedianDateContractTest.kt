package cc.savely.android.modules

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Literal SQL DATE fixtures for pinned web utils.ts parseLocalDate/getDateRange/global median. */
class PokerMedianDateContractTest {
    private fun expense(date: String, amount: Double) =
        PokerCalculations.Expense(LocalDate.parse(date), amount, "expense")

    private val juneBoundary = listOf(
        expense("2024-06-14", 9999.0), expense("2024-06-15", 100.0), expense("2024-07-15", 300.0))

    @Test fun `UTC exact midnight includes cutoff date but one millisecond later excludes it`() {
        val midnight = Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC)
        val later = Clock.fixed(Instant.parse("2026-06-15T00:00:00.001Z"), ZoneOffset.UTC)
        assertEquals(200.0, PokerCalculations.medianMonthlySpending(juneBoundary, midnight), 0.0)
        assertEquals(300.0, PokerCalculations.medianMonthlySpending(juneBoundary, later), 0.0)
    }

    @Test fun `Rome at half past midnight excludes local cutoff midnight even while UTC is yesterday`() {
        val clock = Clock.fixed(Instant.parse("2026-06-14T22:30:00Z"), ZoneId.of("Europe/Rome"))
        assertEquals(15, ZonedDateTime.now(clock).dayOfMonth)
        assertEquals(0, ZonedDateTime.now(clock).hour)
        assertEquals(300.0, PokerCalculations.medianMonthlySpending(juneBoundary, clock), 0.0)
    }

    @Test fun `Rome noon retains the established non-boundary median`() {
        val clock = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneId.of("Europe/Rome"))
        assertEquals(12, ZonedDateTime.now(clock).hour)
        assertEquals(300.0, PokerCalculations.medianMonthlySpending(juneBoundary, clock), 0.0)
    }

    @Test fun `Los Angeles clock may be in previous month but SQL DATE months do not shift west`() {
        val clock = Clock.fixed(Instant.parse("2026-06-01T00:30:00Z"), ZoneId.of("America/Los_Angeles"))
        assertEquals(5, ZonedDateTime.now(clock).monthValue)
        assertEquals(31, ZonedDateTime.now(clock).dayOfMonth)
        val rows = listOf(expense("2026-05-31", 10.0), expense("2026-06-01", 20.0),
            expense("2026-06-02", 30.0), expense("2026-07-01", 100.0))
        // Local SQL DATE groups: May 10, June 50, July 100. Future months remain eligible, as on web.
        assertEquals(50.0, PokerCalculations.medianMonthlySpending(rows, clock), 0.0)
    }

    @Test fun `setDate subtracts calendar days across DST instead of 730 times 24 hours`() {
        val clock = Clock.fixed(Instant.parse("2026-10-25T23:00:00Z"), ZoneId.of("Europe/Rome"))
        assertEquals(0, ZonedDateTime.now(clock).hour)
        assertEquals(26, ZonedDateTime.now(clock).dayOfMonth)
        val rows = listOf(expense("2024-10-25", 9999.0), expense("2024-10-26", 100.0),
            expense("2024-11-01", 300.0))
        // Now is CET; 730 calendar days earlier is 2024-10-26 00:00 CEST (22:00Z).
        // Subtracting an Instant duration would yield 01:00 CEST and incorrectly discard 100.
        assertEquals(200.0, PokerCalculations.medianMonthlySpending(rows, clock), 0.0)
    }
}
