package cc.savely.android.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class FinanceChartPeriod(val label: String) {
    ALL("Tutto"), YEAR("Anno"), MONTH("Mese"), SINCE("Da data"), BETWEEN("Tra date")
}

enum class FinanceChartGranularity(val label: String) {
    DAILY("Giornaliero"), WEEKLY("Settimanale"), MONTHLY("Mensile"), YEARLY("Annuale")
}

private const val MAX_CHART_BUCKETS = 2400

/** ChartsIncomeExpense.tsx saving rate, applied to one native currency's already filtered totals.
 * One decimal uses exact HALF_UP rather than JavaScript Number/toFixed's binary approximation at ties.
 */
fun financeChartSavingRate(totals: CurrencyTotals): BigDecimal = if (totals.income.signum() <= 0) {
    BigDecimal.ZERO.setScale(1)
} else {
    totals.savings.max(BigDecimal.ZERO).multiply(BigDecimal("100")).divide(totals.income, 1, RoundingMode.HALF_UP)
}

/** Web uses days through one year, then months. Very long ranges use explicitly labelled years. */
fun financeChartCumulativeGranularity(range: DashboardRange): FinanceChartGranularity = when {
    range.days <= 366 -> FinanceChartGranularity.DAILY
    ChronoUnit.MONTHS.between(YearMonth.from(range.from), YearMonth.from(range.to)) + 1 <= MAX_CHART_BUCKETS -> FinanceChartGranularity.MONTHLY
    else -> FinanceChartGranularity.YEARLY
}

data class FinanceChartFilter(
    val type: TransactionType? = null,
    val currency: String? = null,
    val categoryId: String? = null,
    val uncategorizedOnly: Boolean = false,
) {
    init { require(categoryId == null || !uncategorizedOnly) { "Filtro categoria non valido" } }
}

data class FinanceChartPoint(
    /** Monday/month/year anchor may precede the range, but its amounts contain only inclusive range rows. */
    val date: LocalDate,
    val income: BigDecimal,
    val expenses: BigDecimal,
    /** Starts at zero at the beginning of this range; this is not the account's lifetime balance. */
    val cumulativeBalance: BigDecimal,
) {
    val net: BigDecimal get() = income - expenses
}

data class FinanceChartCategory(
    val categoryId: String?,
    val type: TransactionType,
    /** Null means a missing category; presentation localizes the fallback, never a user's name. */
    val name: String?,
    val color: String?,
    val amount: BigDecimal,
    /** Share of this type's total in this currency, rounded only for display. */
    val percent: BigDecimal,
)

/** Charts*.tsx modes. Invalid custom dates are rejected so the caller can keep its last applied range. */
fun resolveFinanceChartRange(
    period: FinanceChartPeriod,
    transactions: List<FinanceTransaction>,
    today: LocalDate,
    year: Int = today.year,
    month: Int = today.monthValue,
    fromText: String = "",
    toText: String = "",
): DashboardRange {
    require(year in 1..9999 && month in 1..12) { "Inserisci anno e mese validi" }
    return when (period) {
        FinanceChartPeriod.ALL -> DashboardRange(transactions.minOfOrNull { it.date } ?: today,
            transactions.maxOfOrNull { it.date } ?: today)
        FinanceChartPeriod.YEAR -> DashboardRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
        FinanceChartPeriod.MONTH -> YearMonth.of(year, month).let { DashboardRange(it.atDay(1), it.atEndOfMonth()) }
        FinanceChartPeriod.SINCE -> DashboardRange(if (fromText.isBlank()) transactions.minOfOrNull { it.date } ?: today else parseFinanceChartDate(fromText), today)
        FinanceChartPeriod.BETWEEN -> DashboardRange(
            if (fromText.isBlank()) transactions.minOfOrNull { it.date } ?: today else parseFinanceChartDate(fromText),
            if (toText.isBlank()) transactions.maxOfOrNull { it.date } ?: today else parseFinanceChartDate(toText))
    }
}

private fun parseFinanceChartDate(text: String): LocalDate {
    val message = "Inserisci date valide nel formato YYYY-MM-DD"
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(text)) { message }
    val date = try { LocalDate.parse(text) } catch (_: DateTimeParseException) { throw IllegalArgumentException(message) }
    require(date.year in 1..9999) { message }
    return date
}

fun filterFinanceChartTransactions(
    transactions: List<FinanceTransaction>, range: DashboardRange, filter: FinanceChartFilter = FinanceChartFilter(),
): List<FinanceTransaction> = transactions.filter { row ->
    row.date in range && (filter.type == null || row.type == filter.type) &&
        (filter.currency == null || row.currency == filter.currency) &&
        (filter.categoryId == null || row.categoryId == filter.categoryId) &&
        (!filter.uncategorizedOnly || row.categoryId == null)
}

/** Exact arithmetic per currency; zero buckets and Monday weeks match dashboardStats.buildSeries. */
fun financeChartSeries(
    transactions: List<FinanceTransaction>, range: DashboardRange, currency: String,
    granularity: FinanceChartGranularity,
): List<FinanceChartPoint> {
    val dates = financeChartBucketDates(range, granularity)
    val amounts = mutableMapOf<LocalDate, Pair<BigDecimal, BigDecimal>>()
    transactions.forEach { row ->
        if (row.currency != currency || row.date !in range) return@forEach
        val date = financeChartBucket(row.date, granularity)
        val previous = amounts[date] ?: (BigDecimal.ZERO to BigDecimal.ZERO)
        amounts[date] = if (row.type == TransactionType.INCOME) previous.first + row.amount to previous.second
            else previous.first to previous.second + row.amount
    }
    var balance = BigDecimal.ZERO
    return dates.map { date ->
        val (income, expenses) = amounts[date] ?: (BigDecimal.ZERO to BigDecimal.ZERO)
        balance += income - expenses
        FinanceChartPoint(date, income, expenses, balance)
    }
}

/** Categories are keyed by ID and type, never by name. Uncategorized and removed categories remain in totals. */
fun financeChartCategoryBreakdown(
    transactions: List<FinanceTransaction>, categories: List<FinanceCategory>, currency: String,
): List<FinanceChartCategory> {
    val catalog = categories.associateBy { it.id }
    val groups = mutableMapOf<Pair<TransactionType, String?>, BigDecimal>()
    val totals = mutableMapOf<TransactionType, BigDecimal>()
    transactions.filter { it.currency == currency }.forEach { row ->
        val key = row.type to row.categoryId
        groups[key] = (groups[key] ?: BigDecimal.ZERO) + row.amount
        totals[row.type] = (totals[row.type] ?: BigDecimal.ZERO) + row.amount
    }
    return groups.map { (key, amount) ->
        val category = catalog[key.second]?.takeIf { it.type == key.first }
        val total = totals[key.first] ?: BigDecimal.ZERO
        FinanceChartCategory(key.second, key.first, category?.name, category?.color, amount,
            if (total.signum() > 0) amount.multiply(BigDecimal("100")).divide(total, 12, RoundingMode.HALF_UP) else BigDecimal.ZERO)
    }.sortedWith(compareBy<FinanceChartCategory> { it.type.ordinal }.thenByDescending { it.amount }
        .thenBy { it.categoryId ?: "" })
}

/** Both graphics and accessible rows are bounded; exceeding this bound fails instead of silently dropping buckets. */
fun financeChartBucketDates(range: DashboardRange, granularity: FinanceChartGranularity): List<LocalDate> {
    val start = financeChartBucket(range.from, granularity)
    val end = financeChartBucket(range.to, granularity)
    val count = when (granularity) {
        FinanceChartGranularity.DAILY -> ChronoUnit.DAYS.between(start, end) + 1
        FinanceChartGranularity.WEEKLY -> ChronoUnit.WEEKS.between(start, end) + 1
        FinanceChartGranularity.MONTHLY -> ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(end)) + 1
        FinanceChartGranularity.YEARLY -> end.year.toLong() - start.year + 1
    }
    require(count in 1..MAX_CHART_BUCKETS.toLong()) { "Troppi intervalli per il grafico. Seleziona un periodo più breve o una granularità maggiore." }
    return List(count.toInt()) { index -> when (granularity) {
        FinanceChartGranularity.DAILY -> start.plusDays(index.toLong())
        FinanceChartGranularity.WEEKLY -> start.plusWeeks(index.toLong())
        FinanceChartGranularity.MONTHLY -> start.plusMonths(index.toLong())
        FinanceChartGranularity.YEARLY -> start.plusYears(index.toLong())
    } }
}

private fun financeChartBucket(date: LocalDate, granularity: FinanceChartGranularity): LocalDate = when (granularity) {
    FinanceChartGranularity.DAILY -> date
    FinanceChartGranularity.WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    FinanceChartGranularity.MONTHLY -> date.withDayOfMonth(1)
    FinanceChartGranularity.YEARLY -> date.withDayOfYear(1)
}
