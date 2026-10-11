package cc.savely.android.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import org.json.JSONObject

enum class FinanceAssetType(val wire: String, val label: String) {
    STOCK("stock", "Azioni"), ETF("etf", "ETF"), CRYPTO("crypto", "Criptovalute"), BOND("bond", "Obbligazioni"),
    CASH("cash", "Liquidità"), REAL_ESTATE("real_estate", "Immobili"), OTHER("other", "Altro");

    companion object {
        fun fromWire(value: String): FinanceAssetType = entries.firstOrNull { it.wire == value }
            ?: error("Tipo asset non riconosciuto: $value")
    }
}

data class FinanceAsset(
    val id: String, val name: String, val symbol: String?, val type: FinanceAssetType,
    val quantity: BigDecimal, val purchasePrice: BigDecimal, val currentPrice: BigDecimal?, val currency: String,
    val purchaseDate: LocalDate, val soldAt: LocalDate?, val soldPrice: BigDecimal?, val notes: String,
) {
    val closed: Boolean get() = soldAt != null && soldPrice != null
    val marketPrice: BigDecimal get() = currentPrice ?: purchasePrice
    val marketValue: BigDecimal get() = marketPrice * quantity
    val cost: BigDecimal get() = purchasePrice * quantity
    val gain: BigDecimal get() = if (closed) soldPrice!! * quantity - cost else marketValue - cost
    // Non-EUR cash prices contain the EUR exchange rate, unlike instrument prices.
    val valuationCurrency: String get() = if (type == FinanceAssetType.CASH) "EUR" else currency
}

data class FinanceSnapshot(val id: String, val date: LocalDate, val value: BigDecimal, val components: JSONObject?)

data class PortfolioTotals(val currency: String, val value: BigDecimal, val cost: BigDecimal, val realized: BigDecimal) {
    val gain: BigDecimal get() = value - cost
}

fun portfolioTotals(assets: List<FinanceAsset>): List<PortfolioTotals> = assets.groupBy { it.valuationCurrency }
    .toSortedMap().map { (currency, rows) ->
        PortfolioTotals(currency,
            rows.filterNot { it.closed }.fold(BigDecimal.ZERO) { total, a -> total + a.marketValue },
            rows.filterNot { it.closed }.fold(BigDecimal.ZERO) { total, a -> total + a.cost },
            rows.filter { it.closed }.fold(BigDecimal.ZERO) { total, a -> total + a.gain })
    }

data class NetWorthComponents(
    val currency: String, val cashflow: BigDecimal, val portfolioGain: BigDecimal, val discountedRealEstate: BigDecimal,
) {
    val total: BigDecimal get() = (cashflow + portfolioGain + discountedRealEstate).setScale(2, RoundingMode.HALF_UP)
}

fun netWorthComponents(transactions: List<FinanceTransaction>, assets: List<FinanceAsset>): List<NetWorthComponents> {
    val investmentTypes = setOf(FinanceAssetType.STOCK, FinanceAssetType.ETF, FinanceAssetType.CRYPTO, FinanceAssetType.BOND)
    val currencies = (transactions.map { it.currency } + assets.filterNot { it.closed }.map { it.valuationCurrency }).distinct().sorted()
    return currencies.map { currency ->
        val cashflow = currencyTotals(transactions.filter { it.currency == currency }).firstOrNull()?.savings ?: BigDecimal.ZERO
        val openAssets = assets.filter { !it.closed && it.valuationCurrency == currency }
        val gains = openAssets.filter { it.type in investmentTypes }.fold(BigDecimal.ZERO) { total, a -> total + a.gain }
        val estate = openAssets.filter { it.type == FinanceAssetType.REAL_ESTATE }
            .fold(BigDecimal.ZERO) { total, a -> total + a.marketValue } * BigDecimal("0.75")
        NetWorthComponents(currency, cashflow, gains, estate)
    }
}

fun parseQuantity(text: String): BigDecimal {
    val input = text.trim().replace(',', '.')
    require(input.length <= 64 && Regex("\\d+(\\.\\d+)?").matches(input)) { "Quantità non valida" }
    val value = input.toBigDecimal().setScale(8, RoundingMode.HALF_UP)
    require(value >= BigDecimal.ZERO && value < BigDecimal("1000000000000")) { "Quantità oltre il limite del database" }
    return value
}

fun validatePositionClosure(date: LocalDate, price: BigDecimal, purchaseDate: LocalDate, today: LocalDate = LocalDate.now()) {
    require(date <= today && date >= today.minusYears(10) && date >= purchaseDate) { "La vendita deve essere successiva all’acquisto, non futura e negli ultimi 10 anni" }
    require(price > BigDecimal.ZERO && price <= BigDecimal("1000000")) { "Prezzo vendita richiesto tra 0,01 e 1.000.000" }
}

internal fun assetFromJson(json: JSONObject): FinanceAsset = FinanceAsset(
    json.getString("id"), json.getString("name"), json.nullableString("symbol"), FinanceAssetType.fromWire(json.getString("type")),
    json.decimal("quantity"), json.decimal("purchase_price"), if (json.isNull("current_price")) null else json.decimal("current_price"),
    json.nullableString("currency") ?: "EUR", LocalDate.parse(json.getString("purchase_date")),
    json.nullableString("sold_at")?.take(10)?.let(LocalDate::parse), if (json.isNull("sold_price")) null else json.decimal("sold_price"),
    json.nullableString("notes").orEmpty(),
)

internal fun snapshotFromJson(json: JSONObject): FinanceSnapshot = FinanceSnapshot(
    json.getString("id"), LocalDate.parse(json.getString("date")), json.decimal("net_worth"), json.optJSONObject("components"),
)

enum class InsightSeverity(val label: String) { WARNING("Attenzione"), POSITIVE("Positivo"), INFO("Info") }

data class FinanceInsight(val id: String, val title: String, val message: String, val warning: Boolean, val value: BigDecimal,
    val severity: InsightSeverity = if (warning) InsightSeverity.WARNING else InsightSeverity.INFO)

/** Date.setMonth overflow used by web Insights, deliberately distinct from recurring-date clamp. */
internal fun insightWindowStart(today: LocalDate, months: Long): LocalDate =
    today.withDayOfMonth(1).minusMonths(months).plusDays((today.dayOfMonth - 1).toLong())

internal fun savingMonthInsights(transactions: List<FinanceTransaction>, today: LocalDate): List<FinanceInsight> {
    val start = insightWindowStart(today, 12)
    val currentMonth = YearMonth.from(today)
    return transactions.filter { it.date > start }.groupBy { it.currency }.toSortedMap().mapNotNull { (currency, rows) ->
        val netByMonth = rows.groupBy { YearMonth.from(it.date) }.mapValues { (_, month) ->
            month.fold(BigDecimal.ZERO) { sum, transaction ->
                sum + if (transaction.type == TransactionType.INCOME) transaction.amount else -transaction.amount
            }
        }
        val currentNet = netByMonth[currentMonth] ?: BigDecimal.ZERO
        val historical = netByMonth.filterKeys { it != currentMonth }.values
        if (historical.isEmpty() || currentNet.signum() <= 0) return@mapNotNull null
        val sum = historical.fold(BigDecimal.ZERO, BigDecimal::add)
        if (sum.signum() <= 0) return@mapNotNull null
        val count = BigDecimal(historical.size)
        // Exact 120% comparison; avoid a rounded average or binary floating point at the threshold.
        if (currentNet * count * BigDecimal("5") < sum * BigDecimal("6")) return@mapNotNull null
        val averageDisplay = sum.divide(count, 2, RoundingMode.HALF_UP)
        val percent = ((currentNet * count - sum) * BigDecimal("100")).divide(sum, 0, RoundingMode.HALF_UP)
        FinanceInsight("saving-month-$currency", "Ottimo mese di risparmio: $currency",
            "Stai risparmiando ${formatMoney(currentNet, currency)} questo mese, +$percent% rispetto alla media dei mesi registrati (${formatMoney(averageDisplay, currency)}).",
            false, currentNet, InsightSeverity.POSITIVE)
    }
}

internal fun newRecurringInsights(data: FinanceData): List<FinanceInsight> = availableRecurringSuggestions(data).take(3).map { candidate ->
    val cadence = when (candidate.frequency) {
        RecurringFrequency.WEEKLY -> "una cadenza settimanale"
        RecurringFrequency.MONTHLY -> "una cadenza mensile"
        RecurringFrequency.QUARTERLY -> "una cadenza trimestrale"
        RecurringFrequency.YEARLY -> "una cadenza annuale"
    }
    FinanceInsight("new-recurring-${candidate.key}", "Possibile spesa ricorrente: ${candidate.description}",
        "Rilevata $cadence di ${formatMoney(candidate.medianAmount, candidate.currency)} (${candidate.occurrenceCount} occorrenze). Vuoi tenerla tracciata?",
        false, candidate.medianAmount, InsightSeverity.INFO)
}

/** Four web counterparts: budget_exceeded, portfolio_loss, saving_month and new_recurring_detected. */
fun financeInsights(data: FinanceData, today: LocalDate = LocalDate.now()): List<FinanceInsight> {
    val result = mutableListOf<FinanceInsight>()
    for (budget in data.budgets) {
        val spend = data.transactions.filter {
            it.type == TransactionType.EXPENSE && it.categoryId == budget.categoryId && it.currency == budget.currency &&
                it.date.year == today.year && it.date.month == today.month
        }.fold(BigDecimal.ZERO) { total, t -> total + t.amount }
        if (budget.amount > BigDecimal.ZERO && spend > budget.amount) {
            val name = data.categories.find { it.id == budget.categoryId }?.name ?: "Categoria rimossa"
            result.add(FinanceInsight("budget-${budget.id}", "Budget superato: $name",
                "Speso ${formatMoney(spend, budget.currency)} su ${formatMoney(budget.amount, budget.currency)}.", true, spend - budget.amount))
        }
    }
    for (asset in data.assets.filter { it.soldAt == null }) {
        if (asset.purchasePrice.signum() <= 0) continue
        // Compare the exact 85% price boundary before rounding the ratio used in the message/value.
        if (asset.marketPrice * BigDecimal("100") > asset.purchasePrice * BigDecimal("85")) continue
        val change = (asset.marketPrice - asset.purchasePrice).divide(asset.purchasePrice, 10, RoundingMode.HALF_UP)
        result.add(FinanceInsight("loss-${asset.id}", "Posizione in perdita: ${asset.name}",
            "${asset.symbol ?: asset.name}: ${(change * BigDecimal("100")).setScale(0, RoundingMode.HALF_UP)}% rispetto al prezzo di acquisto.", true, change))
    }
    result.addAll(savingMonthInsights(data.transactions, today))
    // Select the global top three by confidence/count before the final severity/value ordering.
    result.addAll(newRecurringInsights(data))
    return result.sortedWith(compareBy<FinanceInsight> { it.severity.ordinal }.thenByDescending { it.value.abs() })
}
