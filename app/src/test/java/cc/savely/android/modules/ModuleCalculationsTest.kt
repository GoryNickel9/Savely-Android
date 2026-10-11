package cc.savely.android.modules

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

class ModuleCalculationsTest {
    @Test fun fireMatchesExecutedWebFixture() {
        val file = listOf(File("../parity/fixtures/web-calculations.json"), File("parity/fixtures/web-calculations.json")).first { it.exists() }
        val fixture = JSONObject(file.readText())
        assertEquals("1cfcf6297e85d56bfafe3522362ceecef6f6b281", fixture.getString("sourceRevision"))
        val standard = fixture.getJSONObject("standard")
        val inputs = input(standard.getJSONObject("inputs"))
        val actual = FireCalculations.standard(inputs, 2026)
        val expected = standard.getJSONObject("result")
        assertEquals(expected.getDouble("fireNumber"), actual.fireNumber, 0.0)
        assertEquals(expected.getDouble("yearsToFIRE"), actual.yearsToFire, 0.0)
        assertEquals(expected.getDouble("fireAge"), actual.fireAge, 0.0)
        assertEquals(expected.getDouble("savingsRate"), actual.savingsRate, 1e-12)
        assertEquals(expected.getDouble("monthlyContribution"), actual.monthlyContribution, 1e-12)
        assertEquals(expected.getDouble("coastFireNumber"), actual.coastFireNumber, 0.0)
        val projections = expected.getJSONArray("projections")
        assertEquals(projections.length(), actual.projections.size)
        actual.projections.forEachIndexed { index, point ->
            val web = projections.getJSONObject(index)
            assertEquals(web.getDouble("portfolio"), point.portfolio, 0.0)
            assertEquals(web.getDouble("inflationAdjusted"), point.inflationAdjusted, 0.0)
            assertEquals(web.getDouble("totalContributions"), point.totalContributions, 0.0)
            assertEquals(web.getInt("age"), point.age)
            assertEquals(web.getInt("year"), point.year)
        }
        val barista = fixture.getJSONObject("barista")
        val result = FireCalculations.barista(input(barista.getJSONObject("inputs")), barista.getDouble("partTimeIncome"), 2026)
        val baristaExpected = barista.getJSONObject("result")
        assertEquals(baristaExpected.getDouble("baristaNumber"), result.baristaNumber, 0.0)
        assertEquals(baristaExpected.getDouble("fullFireNumber"), result.fullFireNumber, 0.0)
        assertEquals(baristaExpected.getDouble("yearsToBaristaFIRE"), result.yearsToBaristaFire, 0.0)
        assertEquals(baristaExpected.getDouble("savingsFromPartTime"), result.savingsFromPartTime, 0.0)
    }

    @Test fun zeroReturnUnreachableAndWithdrawalFallback() {
        assertEquals(1300.0, FireCalculations.futureValue(1000.0, 100.0, 0.0, 3.0), 0.0)
        assertEquals(1420.0, FireCalculations.futureValue(1000.0, 100.0, 0.1, 2.0), 1e-10)
        assertEquals(1000.0, FireCalculations.presentValue(1210.0, 0.1, 2.0), 1e-10)
        assertEquals(3.0, FireCalculations.yearsToTarget(1000.0, 100.0, 0.0, 1300.0), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, FireCalculations.yearsToTarget(1000.0, 0.0, 0.0, 1300.0), 0.0)
        val result = FireCalculations.standard(FireCalculations.Inputs(currentSavings = 0.0, annualContribution = 0.0,
            expectedReturn = 0.0, inflationRate = 0.0, withdrawalRate = 0.0), 2026)
        assertEquals(1200000.0, result.fireNumber, 0.0)
        assertEquals(51, result.projections.size)
        assertTrue(result.yearsToFire.isInfinite())
        assertEquals(0.0, FireCalculations.barista(FireCalculations.Inputs(), 100000.0, 2026).yearsToBaristaFire, 0.0)
    }

    @Test fun negativeReturnUsesWebIterativeFallback() {
        assertEquals(2.0, FireCalculations.yearsToTarget(100.0, 20.0, -0.1, 115.0), 0.0)
        assertTrue(FireCalculations.yearsToTarget(100.0, 0.0, -0.1, 200.0).isInfinite())
    }

    @Test fun hourlyUsesMinutesAndAggregatesWeightedRates() {
        val a = PokerCalculations.hourly(120.0, 100.0, 80.0)
        val b = PokerCalculations.hourly(60.0, -10.0, 40.0)
        assertEquals(2.0, a.hours, 0.0)
        assertEquals(50.0, a.hourlyRate, 0.0)
        assertEquals(40.0, a.hourlyRateEv, 0.0)
        val total = PokerCalculations.aggregate(listOf(a, b))
        assertEquals(30.0, total.hourlyRate, 0.0)
        assertEquals(40.0, total.hourlyRateEv, 0.0)
        assertEquals(0.0, PokerCalculations.hourly(0.0, 100.0, 100.0).hourlyRate, 0.0)
        assertEquals(27.5, a.hourlyRate * 0.55, 1e-12)
    }

    @Test fun nextCutAndRakebackUseGrossTargetsAndWeightedTotals() {
        assertEquals(2500.0, PokerCalculations.nextCut(2000.0, 0.5, 1500.0), 0.0)
        assertEquals(-500.0, PokerCalculations.nextCut(1000.0, 0.5, 2500.0), 0.0)
        assertEquals(20.0, PokerCalculations.rakebackPercentage(1000.0, 200.0), 0.0)
        assertEquals(0.0, PokerCalculations.rakebackPercentage(0.0, 200.0), 0.0)
        assertThrows(IllegalArgumentException::class.java) { PokerCalculations.nextCut(100.0, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { PokerCalculations.hourly(-1.0, 0.0, 0.0) }
    }

    @Test fun spendingMedianMatchesObservedWebBehavior() {
        val items = listOf(
            PokerCalculations.Expense(LocalDate.parse("2026-01-02"), 100.0, "expense"),
            PokerCalculations.Expense(LocalDate.parse("2026-01-03"), 50.0, "expense"),
            PokerCalculations.Expense(LocalDate.parse("2026-03-02"), 250.0, "expense"),
            PokerCalculations.Expense(LocalDate.parse("2026-03-02"), 9999.0, "income"),
            PokerCalculations.Expense(LocalDate.parse("2023-01-02"), 9999.0, "expense"))
        assertEquals(200.0, PokerCalculations.medianMonthlySpending(items, LocalDate.parse("2025-01-01")), 0.0)
        assertEquals(0.0, PokerCalculations.median(emptyList()), 0.0)
    }

    @Test fun fumoDatesMatchWebDerivedFieldsAndHandleLeapYear() {
        val a = FumoCalculations.derived(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-11"), 20.0, 50.0)
        assertEquals(10L, a.days)
        assertEquals(2.0, a.quantityPerDay!!, 0.0)
        assertEquals(5.0, a.euroPerDay!!, 0.0)
        assertEquals(150.0, a.monthlyCost!!, 0.0)
        val leap = FumoCalculations.derived(LocalDate.parse("2024-02-28"), LocalDate.parse("2024-03-01"), 10.0, 6.0)
        assertEquals(2L, leap.days)
        assertNull(FumoCalculations.derived(LocalDate.parse("2026-01-01"), null, 20.0, 50.0).days)
        assertNull(FumoCalculations.derived(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-01"), 20.0, 50.0).days)
    }

    private fun input(value: JSONObject) = FireCalculations.Inputs(value.getInt("currentAge"), value.getInt("retirementAge"),
        value.getDouble("currentSavings"), value.getDouble("annualContribution"), value.getDouble("annualIncome"),
        value.getDouble("expectedReturn"), value.getDouble("inflationRate"), value.getDouble("withdrawalRate"), value.getDouble("annualExpenses"))
}
