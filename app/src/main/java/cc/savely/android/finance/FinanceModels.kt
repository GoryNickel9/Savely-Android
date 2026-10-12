package cc.savely.android.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.util.Currency
import java.util.Locale
import org.json.JSONObject

enum class TransactionType(val wire: String, val label: String) {
    INCOME("income", "Entrata"), EXPENSE("expense", "Uscita");

    companion object {
        fun fromWire(value: String): TransactionType = entries.firstOrNull { it.wire == value }
            ?: error("Tipo transazione non riconosciuto: $value")
    }
}

val supportedCurrencies = listOf("EUR", "USD", "GBP", "CHF", "JPY", "CNY", "IDR", "CAD", "AUD")

data class FinanceCategory(
    val id: String,
    val name: String,
    val type: TransactionType,
    val icon: String = "",
    val color: String = "#22c55e",
)

data class FinanceTransaction(
    val id: String,
    val type: TransactionType,
    val amount: BigDecimal,
    val currency: String,
    val date: LocalDate,
    val description: String,
    val categoryId: String?,
    val exchangeRateEur: BigDecimal = BigDecimal.ONE,
)

data class FinanceBudget(val id: String, val categoryId: String, val amount: BigDecimal, val currency: String)

enum class RecurringFrequency(val wire: String, val label: String) {
    WEEKLY("weekly", "Settimanale"), MONTHLY("monthly", "Mensile"),
    QUARTERLY("quarterly", "Trimestrale"), YEARLY("yearly", "Annuale");

    companion object {
        fun fromWire(value: String): RecurringFrequency = entries.firstOrNull { it.wire == value }
            ?: error("Frequenza non riconosciuta: $value")
    }
}

data class FinanceRecurring(
    val id: String,
    val name: String,
    val amount: BigDecimal,
    val currency: String,
    val categoryId: String?,
    val frequency: RecurringFrequency,
    val weekInterval: Int,
    val nextDueDate: LocalDate,
    val active: Boolean,
)

data class FinanceData(
    val transactions: List<FinanceTransaction> = emptyList(),
    val categories: List<FinanceCategory> = emptyList(),
    val budgets: List<FinanceBudget> = emptyList(),
    val recurring: List<FinanceRecurring> = emptyList(),
    val defaultCurrency: String = "EUR",
    val assets: List<FinanceAsset> = emptyList(),
    val snapshots: List<FinanceSnapshot> = emptyList(),
    val lastManualPriceUpdate: java.time.Instant? = null,
    val ignoredRecurringSuggestions: Set<String> = emptySet(),
    val tcgCards: List<FinanceTcgCard> = emptyList(),
    val priceHistory: List<FinancePriceHistory> = emptyList(),
    val userId: String = "",
    val coupleConnection: FinanceCoupleConnection? = null,
    val sharedExpenses: List<FinanceSharedExpense> = emptyList(),
)

data class CurrencyTotals(val currency: String, val income: BigDecimal, val expenses: BigDecimal) {
    val savings: BigDecimal get() = income - expenses
}

/** Decimal input is consumed fully; SQL numeric(15,2) rounds half away from zero. */
fun parseMoney(text: String, allowZero: Boolean = false): BigDecimal {
    val input = text.trim()
    require(input.length <= 64) { "Importo troppo lungo" }
    val normalized = if (input.contains(',') && input.contains('.')) {
        require(Regex("[+-]?\\d{1,3}(\\.\\d{3})+,\\d+").matches(input)) { "Formato importo non valido" }
        input.replace(".", "").replace(',', '.')
    } else input.replace(',', '.')
    require(Regex("[+-]?\\d+(\\.\\d+)?").matches(normalized)) { "Inserisci un importo numerico" }
    val amount = normalized.toBigDecimal().setScale(2, RoundingMode.HALF_UP)
    require(if (allowZero) amount.signum() >= 0 else amount.signum() > 0) {
        if (allowZero) "Importo non negativo richiesto" else "Inserisci un importo maggiore di zero"
    }
    require(amount < BigDecimal("10000000000000")) { "Importo oltre il limite del database" }
    return amount
}

fun parseExchangeRate(text: String): BigDecimal {
    val input = text.trim().replace(',', '.')
    require(input.length <= 40 && Regex("\\d+(\\.\\d+)?").matches(input)) { "Cambio EUR non valido" }
    val rate = input.toBigDecimal().setScale(6, RoundingMode.HALF_UP)
    require(rate > BigDecimal.ZERO && rate < BigDecimal("1000000000")) { "Cambio EUR fuori intervallo" }
    return rate
}

fun formatMoney(amount: BigDecimal, currency: String): String = NumberFormat.getCurrencyInstance(Locale.ITALY).apply {
    this.currency = Currency.getInstance(currency)
    minimumFractionDigits = 2
    maximumFractionDigits = 2
    roundingMode = RoundingMode.HALF_UP
}.format(amount)

fun currencyTotals(transactions: List<FinanceTransaction>): List<CurrencyTotals> = transactions
    .groupBy { it.currency }.toSortedMap().map { (currency, rows) ->
        CurrencyTotals(
            currency,
            rows.filter { it.type == TransactionType.INCOME }.fold(BigDecimal.ZERO) { sum, t -> sum + t.amount },
            rows.filter { it.type == TransactionType.EXPENSE }.fold(BigDecimal.ZERO) { sum, t -> sum + t.amount },
        )
    }

fun filterTransactions(
    transactions: List<FinanceTransaction>,
    from: LocalDate? = null,
    to: LocalDate? = null,
    categoryId: String? = null,
    type: TransactionType? = null,
    search: String = "",
    categories: List<FinanceCategory> = emptyList(),
): List<FinanceTransaction> {
    require(from == null || to == null || from <= to) { "La data iniziale supera quella finale" }
    val categoryNames = categories.associate { it.id to it.name }
    val searchTerm = search.trim()
    return transactions.filter {
        (from == null || it.date >= from) && (to == null || it.date <= to) &&
            (categoryId == null || it.categoryId == categoryId) && (type == null || it.type == type) &&
            (searchTerm.isBlank() || it.description.contains(searchTerm, ignoreCase = true) ||
                categoryNames[it.categoryId]?.contains(searchTerm, ignoreCase = true) == true)
    }.sortedWith(compareByDescending<FinanceTransaction> { it.date }.thenBy { it.id })
}

/** Matches utils.ts: median of populated months, includes future transactions, excludes boundary midnight. */
fun medianMonthlyExpense(
    transactions: List<FinanceTransaction>,
    today: LocalDate,
    categoryId: String? = null,
    currency: String = "EUR",
): BigDecimal {
    val totals = transactions.filter {
        it.type == TransactionType.EXPENSE && it.currency == currency && it.date > today.minusDays(730) &&
            (categoryId == null || it.categoryId == categoryId)
    }.groupBy { YearMonth.from(it.date) }.values.map { month ->
        month.fold(BigDecimal.ZERO) { sum, t -> sum + t.amount }
    }.sorted()
    if (totals.isEmpty()) return BigDecimal.ZERO
    val middle = totals.size / 2
    return if (totals.size % 2 == 1) totals[middle] else (totals[middle - 1] + totals[middle]).divide(BigDecimal("2"))
}

data class BudgetCurrencySummary(
    val currency: String,
    val totalBudget: BigDecimal,
    val monthlyMedianExpense: BigDecimal,
) {
    /** Same sign as Budget.tsx: positive means spending above the planned budget. */
    val difference: BigDecimal get() = monthlyMedianExpense - totalBudget
}

/** Global monthly totals include every expense category, including null/removed categories.
 * Matches Budget.tsx/getGlobalMedianMonthlySpending, with separate currencies instead of a mixed EUR total.
 */
fun budgetCurrencySummaries(
    budgets: List<FinanceBudget>,
    transactions: List<FinanceTransaction>,
    today: LocalDate,
): List<BudgetCurrencySummary> {
    val expenses = transactions.filter { it.type == TransactionType.EXPENSE && it.date > today.minusDays(730) }
    val currencies = (budgets.map { it.currency } + expenses.map { it.currency }).toSortedSet()
    return currencies.map { currency ->
        BudgetCurrencySummary(currency,
            budgets.filter { it.currency == currency }.fold(BigDecimal.ZERO) { total, budget -> total + budget.amount },
            medianMonthlyExpense(expenses, today, currency = currency))
    }
}

fun nextDueDate(date: LocalDate, frequency: RecurringFrequency, weekInterval: Int = 1): LocalDate {
    require(weekInterval in 1..520) { "Intervallo settimane non valido" }
    return when (frequency) {
        RecurringFrequency.WEEKLY -> date.plusWeeks(weekInterval.toLong())
        RecurringFrequency.MONTHLY -> date.plusMonths(1)
        RecurringFrequency.QUARTERLY -> date.plusMonths(3)
        RecurringFrequency.YEARLY -> date.plusYears(1)
    }
}

internal fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
internal fun JSONObject.decimal(key: String): BigDecimal = get(key).toString().toBigDecimal()

internal fun categoryFromJson(json: JSONObject): FinanceCategory = FinanceCategory(
    json.getString("id"), json.getString("name"), TransactionType.fromWire(json.getString("type")),
    json.nullableString("icon").orEmpty(), json.nullableString("color") ?: "#22c55e",
)

internal fun transactionFromJson(json: JSONObject): FinanceTransaction = FinanceTransaction(
    json.getString("id"), TransactionType.fromWire(json.getString("type")), json.decimal("amount"),
    json.nullableString("currency") ?: "EUR", LocalDate.parse(json.getString("date")),
    json.nullableString("description").orEmpty(), json.nullableString("category_id"),
    if (json.isNull("exchange_rate_eur")) BigDecimal.ONE else json.decimal("exchange_rate_eur"),
)

internal fun budgetFromJson(json: JSONObject): FinanceBudget = FinanceBudget(
    json.getString("id"), json.getString("category_id"), json.decimal("amount"), json.nullableString("currency") ?: "EUR",
)

internal fun recurringFromJson(json: JSONObject): FinanceRecurring = FinanceRecurring(
    json.getString("id"), json.getString("name"), json.decimal("amount"), json.nullableString("currency") ?: "EUR",
    json.nullableString("category_id"), RecurringFrequency.fromWire(json.getString("frequency")),
    if (json.isNull("week_interval")) 1 else json.getInt("week_interval"),
    LocalDate.parse(json.getString("next_due_date")), json.getBoolean("is_active"),
)

internal fun transactionPayload(
    type: TransactionType, amount: BigDecimal, currency: String, rate: BigDecimal,
    categoryId: String?, description: String, date: LocalDate,
): JSONObject = JSONObject().put("type", type.wire).put("amount", amount.toPlainString())
    .put("currency", currency).put("exchange_rate_eur", rate.toPlainString())
    .put("category_id", categoryId ?: JSONObject.NULL).put("description", description.ifBlank { null } ?: JSONObject.NULL)
    .put("date", date.toString())
