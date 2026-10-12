package cc.savely.android.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
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

enum class InsightKind { SPENDING_ANOMALY, RECURRING_PRICE_CHANGE, BUDGET_EXCEEDED, PORTFOLIO_LOSS,
    NET_WORTH_MILESTONE, SAVING_MONTH, CATEGORY_TREND, NEW_RECURRING_DETECTED }

data class FinanceInsight(val id: String, val title: String, val message: String, val warning: Boolean, val value: BigDecimal,
    val severity: InsightSeverity = if (warning) InsightSeverity.WARNING else InsightSeverity.INFO,
    val titleTemplate: String? = null, val messageTemplate: String? = null, val textValues: List<Pair<String, Any?>> = emptyList(),
    val localizedValueKeys: Set<String> = emptySet(), val kind: InsightKind? = null)

/** Date.setMonth overflow used by web Insights, deliberately distinct from recurring-date clamp. */
internal fun insightWindowStart(today: LocalDate, months: Long): LocalDate =
    today.withDayOfMonth(1).minusMonths(months).plusDays((today.dayOfMonth - 1).toLong())

/** Web Insights uses individual historical expense amounts here, despite its monthly-median label.
 * Currency groups and the 50 native-unit minimum avoid summing incompatible monetary units.
 */
internal fun spendingAnomalyInsights(data: FinanceData, today: LocalDate): List<FinanceInsight> {
    val start = today.minusDays(730)
    val currentMonth = YearMonth.from(today)
    val current = linkedMapOf<Pair<String, String>, BigDecimal>()
    val historical = mutableMapOf<Pair<String, String>, MutableList<BigDecimal>>()
    for (transaction in data.transactions) {
        val category = transaction.categoryId
        if (transaction.type != TransactionType.EXPENSE || category.isNullOrEmpty() || transaction.date <= start) continue
        val key = category to transaction.currency
        if (YearMonth.from(transaction.date) == currentMonth) {
            current[key] = (current[key] ?: BigDecimal.ZERO) + transaction.amount
        } else {
            historical.getOrPut(key) { mutableListOf() }.add(transaction.amount)
        }
    }
    val names = data.categories.associate { it.id to it.name }
    return current.mapNotNull { (key, amount) ->
        if (amount < BigDecimal("50")) return@mapNotNull null
        val baseline = historical[key]?.sorted()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val middle = baseline.size / 2
        val median = if (baseline.size % 2 == 1) baseline[middle]
            else (baseline[middle - 1] + baseline[middle]).divide(BigDecimal("2"))
        // Exact inclusive 140% comparison, before rounding the median or displayed percentage.
        if (median.signum() <= 0 || amount * BigDecimal("5") < median * BigDecimal("7")) return@mapNotNull null
        val (category, currency) = key
        val name = names[category] ?: "Senza categoria"
        val difference = amount - median
        val percent = (difference * BigDecimal("100")).divide(median, 0, RoundingMode.HALF_UP)
        FinanceInsight("spending-anomaly-$currency-$category", "Spesa anomala in $name: $currency",
            "Hai speso ${formatMoney(amount, currency)} questo mese, +$percent% rispetto alla mediana dei singoli importi (${formatMoney(median, currency)}).",
            true, difference, InsightSeverity.WARNING,
            kind = InsightKind.SPENDING_ANOMALY, titleTemplate = "Spesa anomala in {{name}}: {{currency}}",
            messageTemplate = "Hai speso {{amount}} questo mese, +{{percent}}% rispetto alla mediana dei singoli importi ({{median}}).",
            textValues = listOf("name" to name, "currency" to currency, "amount" to formatMoney(amount, currency), "percent" to percent,
                "median" to formatMoney(median, currency)), localizedValueKeys = if (category in names) emptySet() else setOf("name"))
    }
}

/** Server snapshots have no currency. Never label their recorded aggregate as a native monetary total.
 * SQL enforces one owner/date row; id provides a deterministic tie-break for imported duplicate dates.
 */
internal fun netWorthMilestoneInsights(snapshots: List<FinanceSnapshot>): List<FinanceInsight> {
    if (snapshots.size < 2) return emptyList()
    val ordered = snapshots.sortedWith(compareBy<FinanceSnapshot> { it.date }.thenBy { it.id })
    val latest = ordered.last()
    val previousMax = ordered.dropLast(1).maxOf { it.value }
    // Negative histories are valid; do not initialize the previous maximum to zero or round the comparison.
    if (latest.value < previousMax + BigDecimal.ONE) return emptyList()
    val recordedValue = NumberFormat.getNumberInstance(Locale.ITALY).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        roundingMode = RoundingMode.HALF_UP
    }.format(latest.value)
    return listOf(FinanceInsight("net-worth-milestone", "Nuovo massimo del valore registrato dal server",
        "La snapshot del ${latest.date} ha raggiunto $recordedValue, almeno 1 sopra il massimo precedente. " +
            "La serie server non distingue le valute e non è confrontabile con i totali nativi per valuta.",
        false, latest.value, InsightSeverity.POSITIVE,
        kind = InsightKind.NET_WORTH_MILESTONE, titleTemplate = "Nuovo massimo del valore registrato dal server",
        messageTemplate = "La snapshot del {{date}} ha raggiunto {{amount}}, almeno 1 sopra il massimo precedente. La serie server non distingue le valute e non è confrontabile con i totali nativi per valuta.",
        textValues = listOf("date" to latest.date, "amount" to recordedValue)))
}

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
            false, currentNet, InsightSeverity.POSITIVE,
            kind = InsightKind.SAVING_MONTH, titleTemplate = "Ottimo mese di risparmio: {{currency}}",
            messageTemplate = "Stai risparmiando {{amount}} questo mese, +{{percent}}% rispetto alla media dei mesi registrati ({{average}}).",
            textValues = listOf("currency" to currency, "amount" to formatMoney(currentNet, currency), "percent" to percent,
                "average" to formatMoney(averageDisplay, currency)))
    }
}

/** Web trend compares individual expense medians; its recent sample is also part of the baseline. */
internal fun categoryTrendInsights(data: FinanceData, today: LocalDate): List<FinanceInsight> {
    val windowStart = insightWindowStart(today, 12)
    val recentStart = insightWindowStart(today, 3)
    val currentMonth = YearMonth.from(today)
    val baseline = mutableMapOf<Pair<String, String>, MutableList<BigDecimal>>()
    val recent = linkedMapOf<Pair<String, String>, MutableList<BigDecimal>>()
    for (transaction in data.transactions) {
        val category = transaction.categoryId
        if (transaction.type != TransactionType.EXPENSE || category.isNullOrEmpty() ||
            transaction.date <= windowStart || YearMonth.from(transaction.date) == currentMonth) continue
        val key = category to transaction.currency
        baseline.getOrPut(key) { mutableListOf() }.add(transaction.amount)
        // LocalDate excludes the boundary day, like the web reference after midnight; future dates remain eligible.
        if (transaction.date > recentStart) recent.getOrPut(key) { mutableListOf() }.add(transaction.amount)
    }
    fun median(amounts: List<BigDecimal>): BigDecimal {
        val sorted = amounts.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
            else (sorted[middle - 1] + sorted[middle]).divide(BigDecimal("2"))
    }
    val names = data.categories.associate { it.id to it.name }
    return recent.mapNotNull { (key, amounts) ->
        if (amounts.size < 2) return@mapNotNull null
        val medianRecent = median(amounts)
        val medianBaseline = median(baseline.getValue(key))
        // Inclusive 125%, positive baseline, and 50 native currency units before display rounding.
        if (medianBaseline.signum() <= 0 || medianRecent < BigDecimal("50") ||
            medianRecent * BigDecimal("4") < medianBaseline * BigDecimal("5")) return@mapNotNull null
        val (category, currency) = key
        val name = names[category] ?: "Senza categoria"
        val difference = medianRecent - medianBaseline
        val percent = (difference * BigDecimal("100")).divide(medianBaseline, 0, RoundingMode.HALF_UP)
        FinanceInsight("category-trend-$currency-$category", "$name in trend crescente: $currency",
            "La mediana dei singoli importi in $name è cresciuta del $percent% nel campione degli ultimi 3 mesi (${formatMoney(medianRecent, currency)} rispetto a ${formatMoney(medianBaseline, currency)} nei 12 mesi).",
            false, difference, InsightSeverity.INFO,
            kind = InsightKind.CATEGORY_TREND, titleTemplate = "{{name}} in trend crescente: {{currency}}",
            messageTemplate = "La mediana dei singoli importi in {{name}} è cresciuta del {{percent}}% nel campione degli ultimi 3 mesi ({{recent}} rispetto a {{baseline}} nei 12 mesi).",
            textValues = listOf("name" to name, "currency" to currency, "percent" to percent, "recent" to formatMoney(medianRecent, currency),
                "baseline" to formatMoney(medianBaseline, currency)), localizedValueKeys = if (category in names) emptySet() else setOf("name"))
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
        false, candidate.medianAmount, InsightSeverity.INFO,
        kind = InsightKind.NEW_RECURRING_DETECTED, titleTemplate = "Possibile spesa ricorrente: {{name}}",
        messageTemplate = "Rilevata {{cadence}} di {{amount}} ({{count}} occorrenze). Vuoi tenerla tracciata?",
        textValues = listOf("name" to candidate.description, "cadence" to cadence, "amount" to formatMoney(candidate.medianAmount, candidate.currency),
            "count" to candidate.occurrenceCount), localizedValueKeys = setOf("cadence"))
}

internal fun recurringPriceChangeInsights(
    transactions: List<FinanceTransaction>, candidates: List<RecurringSuggestion>,
): List<FinanceInsight> {
    val keys = candidates.map { it.key }.toSet()
    val latestByKey = mutableMapOf<String, FinanceTransaction>()
    for (transaction in transactions) {
        if (transaction.type != TransactionType.EXPENSE) continue
        val key = recurringSuggestionKey(transaction.description, transaction.currency)
        if (key !in keys) continue
        val previous = latestByKey[key]
        // Like web, the first input wins a date tie; there is no upper date bound.
        if (previous == null || transaction.date > previous.date) latestByKey[key] = transaction
    }
    return candidates.mapNotNull { candidate ->
        if (candidate.medianAmount.signum() <= 0) return@mapNotNull null
        val latest = latestByKey[candidate.key] ?: return@mapNotNull null
        val difference = latest.amount - candidate.medianAmount
        if (difference.abs() * BigDecimal.TEN < candidate.medianAmount) return@mapNotNull null
        val increased = difference.signum() > 0
        // Math.round rounds negative half-percent ties toward positive infinity.
        val percent = (difference * BigDecimal("100")).divide(candidate.medianAmount, 0,
            if (increased) RoundingMode.HALF_UP else RoundingMode.HALF_DOWN)
        val direction = if (increased) "aumentato" else "diminuito"
        FinanceInsight("recurring-price-${candidate.key}", "Spesa ricorrente $direction: ${candidate.description}",
            "L'ultimo importo (${formatMoney(latest.amount, candidate.currency)}) è $percent% rispetto al solito (${formatMoney(candidate.medianAmount, candidate.currency)}).",
            increased, difference, if (increased) InsightSeverity.WARNING else InsightSeverity.INFO,
            kind = InsightKind.RECURRING_PRICE_CHANGE, titleTemplate = if (increased) "Spesa ricorrente aumentato: {{name}}" else "Spesa ricorrente diminuito: {{name}}",
            messageTemplate = "L'ultimo importo ({{amount}}) è {{percent}}% rispetto al solito ({{median}}).",
            textValues = listOf("name" to candidate.description, "amount" to formatMoney(latest.amount, candidate.currency), "percent" to percent,
                "median" to formatMoney(candidate.medianAmount, candidate.currency)))
    }
}

/** Eight web counterparts; native monetary groups retain their currencies, server snapshots remain unlabelled. */
fun financeInsights(data: FinanceData, today: LocalDate = LocalDate.now()): List<FinanceInsight> {
    val result = mutableListOf<FinanceInsight>()
    // Web orders anomalies before budgets; preserve that order when severity and absolute values tie.
    result.addAll(spendingAnomalyInsights(data, today))
    for (budget in data.budgets) {
        val spend = data.transactions.filter {
            it.type == TransactionType.EXPENSE && it.categoryId == budget.categoryId && it.currency == budget.currency &&
                it.date.year == today.year && it.date.month == today.month
        }.fold(BigDecimal.ZERO) { total, t -> total + t.amount }
        if (budget.amount > BigDecimal.ZERO && spend > budget.amount) {
            val name = data.categories.find { it.id == budget.categoryId }?.name ?: "Categoria rimossa"
            result.add(FinanceInsight("budget-${budget.id}", "Budget superato: $name",
                "Speso ${formatMoney(spend, budget.currency)} su ${formatMoney(budget.amount, budget.currency)}.", true, spend - budget.amount,
                kind = InsightKind.BUDGET_EXCEEDED, titleTemplate = "Budget superato: {{name}}", messageTemplate = "Speso {{spent}} su {{limit}}.",
                textValues = listOf("name" to name, "spent" to formatMoney(spend, budget.currency), "limit" to formatMoney(budget.amount, budget.currency)),
                localizedValueKeys = if (data.categories.any { it.id == budget.categoryId }) emptySet() else setOf("name")))
        }
    }
    for (asset in data.assets.filter { it.soldAt == null }) {
        if (asset.purchasePrice.signum() <= 0) continue
        // Compare the exact 85% price boundary before rounding the ratio used in the message/value.
        if (asset.marketPrice * BigDecimal("100") > asset.purchasePrice * BigDecimal("85")) continue
        val change = (asset.marketPrice - asset.purchasePrice).divide(asset.purchasePrice, 10, RoundingMode.HALF_UP)
        result.add(FinanceInsight("loss-${asset.id}", "Posizione in perdita: ${asset.name}",
            "${asset.symbol ?: asset.name}: ${(change * BigDecimal("100")).setScale(0, RoundingMode.HALF_UP)}% rispetto al prezzo di acquisto.", true, change,
            kind = InsightKind.PORTFOLIO_LOSS, titleTemplate = "Posizione in perdita: {{name}}", messageTemplate = "{{symbol}}: {{percent}}% rispetto al prezzo di acquisto.",
            textValues = listOf("name" to asset.name, "symbol" to (asset.symbol ?: asset.name),
                "percent" to (change * BigDecimal("100")).setScale(0, RoundingMode.HALF_UP))))
    }
    // Web orders milestone before saving; preserve stable equal-value positive signals in that order.
    result.addAll(netWorthMilestoneInsights(data.snapshots))
    result.addAll(savingMonthInsights(data.transactions, today))
    // Trend precedes recurring candidates in web generation, preserving stable ties after the final sort.
    result.addAll(categoryTrendInsights(data, today))
    // Select the global top three by confidence/count before the final severity/value ordering.
    result.addAll(newRecurringInsights(data))
    result.addAll(recurringPriceChangeInsights(data.transactions, availableRecurringSuggestions(data)))
    return result.sortedWith(compareBy<FinanceInsight> { it.severity.ordinal }.thenByDescending { it.value.abs() })
}
