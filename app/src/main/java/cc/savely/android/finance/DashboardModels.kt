package cc.savely.android.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class DashboardPreset(val label: String) {
    CURRENT_MONTH("Mese corrente"), PREVIOUS_MONTH("Mese precedente"), LAST_THIRTY_DAYS("Ultimi 30 giorni"),
    CURRENT_YEAR("Anno corrente"), CUSTOM("Personalizzato"),
}

data class DashboardRange(val from: LocalDate, val to: LocalDate) {
    init { require(from <= to) { "Intervallo non valido" } }
    val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1
    operator fun contains(date: LocalDate): Boolean = date >= from && date <= to
    fun previous(): DashboardRange = DashboardRange(from.minusDays(days), from.minusDays(1))
}

/** Local date ranges match dashboardStats.ts: inclusive days and previous range of equal duration.
 * Invalid custom input fails visibly; valid reversed dates are normalized like the web.
 */
fun resolveDashboardRange(preset: DashboardPreset, today: LocalDate, fromText: String = "", toText: String = ""): DashboardRange = when (preset) {
    DashboardPreset.CURRENT_MONTH -> DashboardRange(today.withDayOfMonth(1), today)
    DashboardPreset.PREVIOUS_MONTH -> YearMonth.from(today).minusMonths(1).let { DashboardRange(it.atDay(1), it.atEndOfMonth()) }
    DashboardPreset.LAST_THIRTY_DAYS -> DashboardRange(today.minusDays(29), today)
    DashboardPreset.CURRENT_YEAR -> DashboardRange(today.withDayOfYear(1), today)
    DashboardPreset.CUSTOM -> {
        fun parse(text: String): LocalDate {
            require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(text)) { "Inserisci date valide nel formato YYYY-MM-DD" }
            val date = try { LocalDate.parse(text) } catch (_: java.time.format.DateTimeParseException) {
                throw IllegalArgumentException("Inserisci date valide nel formato YYYY-MM-DD")
            }
            require(date.year in 1..9999) { "Inserisci date valide nel formato YYYY-MM-DD" }
            return date
        }
        val from = parse(fromText)
        val to = parse(toText)
        DashboardRange(minOf(from, to), maxOf(from, to))
    }
}

data class DashboardAmounts(val income: BigDecimal, val expenses: BigDecimal, val balance: BigDecimal) {
    val savings: BigDecimal get() = income - expenses
    val savingsRate: BigDecimal get() = if (income.signum() > 0) savings.multiply(BigDecimal("100")).divide(income, 12, RoundingMode.HALF_UP) else BigDecimal.ZERO
}

data class DashboardCurrencyStats(val currency: String, val current: DashboardAmounts, val previous: DashboardAmounts) {
    val incomeDelta: BigDecimal? get() = dashboardPercentChange(current.income, previous.income)
    val expenseDelta: BigDecimal? get() = dashboardPercentChange(current.expenses, previous.expenses)
    val savingsDelta: BigDecimal? get() = dashboardPercentChange(current.savings, previous.savings)
    val balanceDelta: BigDecimal? get() = dashboardPercentChange(current.balance, previous.balance)
}

fun dashboardPercentChange(current: BigDecimal, previous: BigDecimal): BigDecimal? =
    if (previous.signum() == 0) null else (current - previous).multiply(BigDecimal("100")).divide(previous.abs(), 12, RoundingMode.HALF_UP)

fun formatDashboardPercent(percent: BigDecimal): String = NumberFormat.getNumberInstance(Locale.ITALY).apply {
    minimumFractionDigits = 0
    maximumFractionDigits = 1
    roundingMode = RoundingMode.HALF_UP
}.format(percent) + "%"

/** Balance is cumulative at each period end; currencies with only later transactions are excluded. */
fun dashboardCurrencyStats(transactions: List<FinanceTransaction>, range: DashboardRange): List<DashboardCurrencyStats> {
    val previous = range.previous()
    return transactions.filter { it.date <= range.to }.groupBy { it.currency }.toSortedMap().map { (currency, rows) ->
        fun totals(period: DashboardRange): DashboardAmounts {
            var income = BigDecimal.ZERO
            var expenses = BigDecimal.ZERO
            var balance = BigDecimal.ZERO
            rows.forEach { transaction ->
                val isIncome = transaction.type == TransactionType.INCOME
                if (transaction.date <= period.to) balance += if (isIncome) transaction.amount else -transaction.amount
                if (transaction.date in period) {
                    if (isIncome) income += transaction.amount else expenses += transaction.amount
                }
            }
            return DashboardAmounts(income, expenses, balance)
        }
        DashboardCurrencyStats(currency, totals(range), totals(previous))
    }
}

fun dashboardRecentTransactions(transactions: List<FinanceTransaction>, range: DashboardRange): List<FinanceTransaction> =
    transactions.filter { it.date in range }.sortedWith(compareByDescending<FinanceTransaction> { it.date }.thenBy { it.id }).take(5)

data class DashboardCategoryExpense(
    val currency: String, val categoryId: String?, val name: String?, val color: String?,
    val amount: BigDecimal, val percent: BigDecimal,
)

/** Dashboard web uses joined categories: a removed category joins the uncategorized group. */
fun dashboardCategoryExpenses(transactions: List<FinanceTransaction>, categories: List<FinanceCategory>, range: DashboardRange): List<DashboardCategoryExpense> {
    val catalog = categories.associateBy { it.id }
    val grouped = linkedMapOf<Pair<String, String?>, BigDecimal>()
    transactions.filter { it.type == TransactionType.EXPENSE && it.date in range }.forEach { row ->
        require(row.amount.signum() >= 0 && row.currency in supportedCurrencies) { "Spese per categoria non disponibili: importo o valuta non validi." }
        val category = catalog[row.categoryId]
        val key = row.currency to category?.id
        grouped[key] = (grouped[key] ?: BigDecimal.ZERO) + row.amount
    }
    val totals = grouped.entries.groupBy { it.key.first }.mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.value } }
    return grouped.map { (key, amount) ->
        val category = catalog[key.second]
        val total = totals.getValue(key.first)
        DashboardCategoryExpense(key.first, key.second, category?.name, category?.color, amount,
            if (total.signum() > 0) amount.multiply(BigDecimal("100")).divide(total, 12, RoundingMode.HALF_UP) else BigDecimal.ZERO)
    }.sortedWith(compareBy<DashboardCategoryExpense> { it.currency }.thenByDescending { it.amount }.thenBy { it.categoryId ?: "" })
}

data class DashboardBudgetRow(val budget: FinanceBudget, val spent: BigDecimal) {
    // Web widget reports zero for a zero limit and caps the ratio at 999%; visual progress clamps separately.
    val percent: BigDecimal get() = if (budget.amount.signum() > 0)
        spent.multiply(BigDecimal("100")).divide(budget.amount, 12, RoundingMode.HALF_UP).min(BigDecimal("999")) else BigDecimal.ZERO
}

data class DashboardBudgetSummary(val currency: String, val planned: BigDecimal, val spent: BigDecimal) {
    val remaining: BigDecimal get() = planned - spent
}

/** Monthly generic budgets always use the full current calendar month, independent of the KPI period. */
fun dashboardBudgetRows(budgets: List<FinanceBudget>, transactions: List<FinanceTransaction>, today: LocalDate): List<DashboardBudgetRow> {
    val month = YearMonth.from(today)
    val spent = transactions.filter { it.type == TransactionType.EXPENSE && it.categoryId != null && YearMonth.from(it.date) == month }
        .groupBy { it.categoryId to it.currency }.mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.amount } }
    return budgets.map { DashboardBudgetRow(it, spent[it.categoryId to it.currency] ?: BigDecimal.ZERO) }
        .sortedWith(compareByDescending<DashboardBudgetRow> { it.percent }.thenBy { it.budget.id })
}

fun dashboardBudgetSummaries(rows: List<DashboardBudgetRow>): List<DashboardBudgetSummary> = rows.groupBy { it.budget.currency }.toSortedMap().map { (currency, budgets) ->
    DashboardBudgetSummary(currency, budgets.fold(BigDecimal.ZERO) { sum, row -> sum + row.budget.amount },
        budgets.distinctBy { it.budget.categoryId }.fold(BigDecimal.ZERO) { sum, row -> sum + row.spent })
}
