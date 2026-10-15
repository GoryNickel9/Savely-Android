package cc.savely.android.modules

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/** IEEE-754 Double matches the web engine; round uses JavaScript's ties toward +infinity. */
object FireCalculations {
    data class Inputs(
        val currentAge: Int = 30,
        val retirementAge: Int = 55,
        val currentSavings: Double = 100000.0,
        val annualContribution: Double = 24000.0,
        val annualIncome: Double = 72000.0,
        val expectedReturn: Double = 0.07,
        val inflationRate: Double = 0.03,
        val withdrawalRate: Double = 0.04,
        val annualExpenses: Double = 48000.0,
    ) {
        fun validate() {
            require(currentAge in 0..120 && retirementAge in 0..120) { "Età non valida" }
            require(listOf(currentSavings, annualContribution, annualIncome, expectedReturn,
                inflationRate, withdrawalRate, annualExpenses).all { it.isFinite() }) { "Valore non finito" }
            require(currentSavings >= 0 && annualContribution >= 0 && annualIncome >= 0 && annualExpenses >= 0) { "Importi negativi" }
            require(expectedReturn > -1 && inflationRate > -1) { "Rendimento e inflazione devono superare -100%" }
        }
    }

    data class Projection(val age: Int, val year: Int, val portfolio: Double,
        val contributions: Double, val totalContributions: Double, val inflationAdjusted: Double)
    data class Standard(val fireNumber: Double, val yearsToFire: Double, val fireAge: Double,
        val savingsRate: Double, val monthlyContribution: Double, val coastFireNumber: Double,
        val projections: List<Projection>)
    data class Barista(val baristaNumber: Double, val fullFireNumber: Double,
        val yearsToBaristaFire: Double, val partTimeIncomeNeeded: Double,
        val savingsFromPartTime: Double, val projections: List<Projection>)

    private fun jsRound(value: Double): Double = if (value.isFinite()) floor(value + 0.5) else value
    private fun tenth(value: Double) = jsRound(value * 10) / 10
    fun futureValue(present: Double, contribution: Double, rate: Double, years: Double): Double {
        if (rate == 0.0) return present + contribution * years
        val factor = (1 + rate).pow(years)
        return present * factor + contribution * ((factor - 1) / rate)
    }
    fun presentValue(target: Double, rate: Double, years: Double) =
        if (years <= 0) target else target / (1 + rate).pow(years)

    /** Port of yearsToTarget, including its 100-year fallback bound. */
    fun yearsToTarget(present: Double, contribution: Double, rate: Double, target: Double): Double {
        if (present >= target) return 0.0
        if (rate == 0.0) return if (contribution <= 0) Double.POSITIVE_INFINITY else (target - present) / contribution
        val numerator = contribution + target * rate
        val denominator = contribution + present * rate
        if (denominator <= 0 || numerator <= denominator) {
            var years = 0
            var current = present
            while (current < target && years < 100) {
                current = current * (1 + rate) + contribution
                years++
            }
            return if (years >= 100) Double.POSITIVE_INFINITY else years.toDouble()
        }
        val years = ln(numerator / denominator) / ln(1 + rate)
        return if (years < 0 || years > 100) Double.POSITIVE_INFINITY else years
    }
    fun projections(input: Inputs, years: Int, currentYear: Int): List<Projection> {
        require(years in 0..50)
        var portfolio = input.currentSavings
        var total = portfolio
        return (0..years).map { i ->
            val point = Projection(input.currentAge + i, currentYear + i, jsRound(portfolio),
                if (i == 0) input.currentSavings else input.annualContribution,
                jsRound(total), jsRound(portfolio / (1 + input.inflationRate).pow(i)))
            portfolio = portfolio * (1 + input.expectedReturn) + input.annualContribution
            total += input.annualContribution
            point
        }
    }
    private fun projectionYears(years: Double) = if (!years.isFinite()) 50 else (ceil(years).toInt() + 10).coerceAtMost(50)
    fun standard(input: Inputs, currentYear: Int = LocalDate.now().year): Standard {
        input.validate()
        val rate = if (input.withdrawalRate > 0) input.withdrawalRate else 0.04
        val target = input.annualExpenses / rate
        val realReturn = (1 + input.expectedReturn) / (1 + input.inflationRate) - 1
        val years = yearsToTarget(input.currentSavings, input.annualContribution, realReturn, target)
        return Standard(jsRound(target), tenth(years), tenth(input.currentAge + years),
            if (input.annualIncome > 0) input.annualContribution / input.annualIncome else 0.0,
            input.annualContribution / 12,
            jsRound(presentValue(target, realReturn, (input.retirementAge - input.currentAge).coerceAtLeast(0).toDouble())),
            projections(input, projectionYears(years), currentYear))
    }
    fun barista(input: Inputs, partTimeIncome: Double, currentYear: Int = LocalDate.now().year): Barista {
        input.validate()
        require(partTimeIncome.isFinite() && partTimeIncome >= 0)
        val rate = if (input.withdrawalRate > 0) input.withdrawalRate else 0.04
        val full = input.annualExpenses / rate
        val target = (input.annualExpenses - partTimeIncome).coerceAtLeast(0.0) / rate
        val realReturn = (1 + input.expectedReturn) / (1 + input.inflationRate) - 1
        val years = yearsToTarget(input.currentSavings, input.annualContribution, realReturn, target)
        return Barista(jsRound(target), jsRound(full), tenth(years), partTimeIncome, jsRound(full - target),
            projections(input, projectionYears(years), currentYear))
    }
}

object PokerCalculations {
    data class Expense(val date: LocalDate, val amount: Double, val type: String)
    data class Hourly(val hours: Double, val netWon: Double, val netWonEv: Double) {
        val hourlyRate get() = if (hours > 0) netWon / hours else 0.0
        val hourlyRateEv get() = if (hours > 0) netWonEv / hours else 0.0
    }
    fun hourly(minutes: Double, netWon: Double, netWonEv: Double): Hourly {
        require(listOf(minutes, netWon, netWonEv).all { it.isFinite() })
        require(minutes >= 0) { "I minuti non possono essere negativi" }
        return Hourly(minutes / 60, netWon, netWonEv)
    }
    fun aggregate(rows: List<Hourly>) = Hourly(rows.sumOf { it.hours }, rows.sumOf { it.netWon }, rows.sumOf { it.netWonEv })
    fun rakebackPercentage(rake: Double, received: Double) = if (rake > 0) received / rake * 100 else 0.0
    fun nextCut(monthlySpending: Double, deal: Double, profitLoss: Double): Double {
        require(deal.isFinite() && deal > 0 && deal <= 1) { "Il deal deve essere maggiore di 0 e massimo 1" }
        require(monthlySpending.isFinite() && profitLoss.isFinite())
        val need = monthlySpending / deal - profitLoss
        require(need.isFinite()) { "Inserisci un numero valido" }
        return need
    }
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
    /** Web parseLocalDate + setDate(-730): local calendar cutoff, inclusive, without future cap or empty-month padding. */
    fun medianMonthlySpending(expenses: List<Expense>, clock: Clock = Clock.systemDefaultZone()): Double {
        val zone = clock.zone
        // Date.setDate preserves wall time; atZone also matches its earlier-overlap/forward-gap resolution.
        val cutoff = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), zone)
            .minusDays(730).atZone(zone).toInstant()
        return median(expenses.filter { it.type == "expense" && it.date.atStartOfDay(zone).toInstant() >= cutoff }
            .groupBy { YearMonth.from(it.date) }.values.map { month -> month.sumOf { it.amount } })
    }
}

object FumoCalculations {
    data class Derived(val days: Long?, val quantityPerDay: Double?, val euroPerDay: Double?, val monthlyCost: Double?)
    fun derived(arrival: LocalDate, finished: LocalDate?, quantity: Double?, cost: Double): Derived {
        val days = finished?.let { ChronoUnit.DAYS.between(arrival, it) }
        return if (days != null && days > 0) Derived(days, quantity?.div(days), cost / days, cost / days * 30)
        else Derived(null, null, null, null)
    }
}
