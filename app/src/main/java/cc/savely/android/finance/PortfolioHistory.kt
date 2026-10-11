package cc.savely.android.finance

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.json.JSONObject

data class FinancePriceHistory(val id: String, val assetId: String, val date: LocalDate, val price: BigDecimal)

internal fun priceHistoryFromJson(json: JSONObject): FinancePriceHistory = FinancePriceHistory(
    json.getString("id"), json.getString("asset_id"), LocalDate.parse(json.getString("recorded_at")), json.decimal("price"),
)

enum class PortfolioHistoryMode(val label: String) {
    RECONSTRUCTED("Andamento ricostruito"), RECORDED("Prezzi registrati"),
}

enum class HistoryPeriod(val label: String) {
    ALL("Tutto lo storico"), CURRENT_YEAR("Anno corrente"), PREVIOUS_MONTH("Mese precedente"), CUSTOM("Personalizzato"),
}

fun historyPeriodRange(period: HistoryPeriod, today: LocalDate, fromText: String, toText: String): DashboardRange? = when (period) {
    HistoryPeriod.ALL -> null
    HistoryPeriod.CURRENT_YEAR -> resolveDashboardRange(DashboardPreset.CURRENT_YEAR, today)
    HistoryPeriod.PREVIOUS_MONTH -> resolveDashboardRange(DashboardPreset.PREVIOUS_MONTH, today)
    HistoryPeriod.CUSTOM -> resolveDashboardRange(DashboardPreset.CUSTOM, today, fromText, toText)
}

data class HistoricalValueChange(val first: BigDecimal, val latest: BigDecimal, val count: Int) {
    val change: BigDecimal get() = latest - first
    // Negative baselines use their absolute value, unlike dividing by the signed starting value.
    val percent: BigDecimal? get() = if (first.signum() == 0) null else
        change.multiply(BigDecimal("100")).divide(first.abs(), 12, RoundingMode.HALF_UP)
}

fun historicalValueChange(orderedValues: List<BigDecimal>): HistoricalValueChange? = if (orderedValues.isEmpty()) null else
    HistoricalValueChange(orderedValues.first(), orderedValues.last(), orderedValues.size)

data class PortfolioHistoryPoint(
    val date: LocalDate, val currency: String, val invested: BigDecimal, val value: BigDecimal,
    val recordedAssets: Int, val interpolatedAssets: Int, val currentAssets: Int,
) {
    val profit: BigDecimal get() = value - invested
    val observedAssets: Int get() = recordedAssets + interpolatedAssets + currentAssets
}

private val historyInvestmentTypes = setOf(FinanceAssetType.STOCK, FinanceAssetType.ETF, FinanceAssetType.CRYPTO, FinanceAssetType.BOND)

data class PortfolioReturnSummary(
    val currency: String, val invested: BigDecimal, val investmentValue: BigDecimal,
    val gainIncludingEstate: BigDecimal,
) {
    val investmentGain: BigDecimal get() = investmentValue - invested
    val returnPercent: BigDecimal? get() = if (invested.signum() > 0)
        investmentGain.multiply(BigDecimal("100")).divide(invested, 12, RoundingMode.HALF_UP) else null
}

/** Portfolio.tsx: return excludes cash/estate/other; the gain card includes estate, excludes cash/other/TCG. */
fun portfolioReturnSummaries(assets: List<FinanceAsset>): List<PortfolioReturnSummary> = assets.filterNot { it.closed }
    .filter { it.type != FinanceAssetType.CASH && it.type != FinanceAssetType.OTHER }
    .groupBy { it.valuationCurrency }.toSortedMap().map { (currency, rows) ->
        val investments = rows.filter { it.type in historyInvestmentTypes }
        PortfolioReturnSummary(currency, investments.fold(BigDecimal.ZERO) { sum, asset -> sum + asset.cost },
            investments.fold(BigDecimal.ZERO) { sum, asset -> sum + asset.marketValue },
            rows.fold(BigDecimal.ZERO) { sum, asset -> sum + asset.marketValue - asset.cost })
    }

data class PortfolioAllocation(val currency: String, val type: FinanceAssetType?, val value: BigDecimal, val percent: BigDecimal?)

fun portfolioAllocations(assets: List<FinanceAsset>, cards: List<FinanceTcgCard>): List<PortfolioAllocation> {
    val values = linkedMapOf<Pair<String, FinanceAssetType?>, BigDecimal>()
    assets.filterNot { it.closed }.forEach { asset ->
        val key = asset.valuationCurrency to asset.type
        values[key] = (values[key] ?: BigDecimal.ZERO) + asset.marketValue
    }
    val tcg = tcgPortfolioTotals(cards).fold(BigDecimal.ZERO) { sum, total -> sum + total.value }
    if (cards.any { it.valued }) values["EUR" to null] = tcg
    val totals = values.entries.groupBy { it.key.first }.mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.value } }
    return values.entries.sortedWith(compareBy<Map.Entry<Pair<String, FinanceAssetType?>, BigDecimal>> { it.key.first }
        .thenBy { it.key.second?.ordinal ?: Int.MAX_VALUE }).map { (key, value) ->
        val total = totals.getValue(key.first)
        PortfolioAllocation(key.first, key.second, value, if (total.signum() > 0)
            value.multiply(BigDecimal("100")).divide(total, 12, RoundingMode.HALF_UP) else null)
    }
}

/** Currently-open positions only, excluding cash/other, as Portfolio.tsx (estate is included).
 * The web interpolates elapsed milliseconds; native uses local calendar days and DECIMAL128 division.
 * Dates follow the web's floor(dayCount/60) sampling including the last day, without allocating all days.
 * The final day uses the saved current price; missing current_price falls back to purchase_price.
 */
fun portfolioHistory(
    assets: List<FinanceAsset>, history: List<FinancePriceHistory>, mode: PortfolioHistoryMode,
    today: LocalDate = LocalDate.now(),
): List<PortfolioHistoryPoint> {
    val open = assets.filter { !it.closed && it.type != FinanceAssetType.CASH && it.type != FinanceAssetType.OTHER }
    // SQL has UNIQUE(asset_id,recorded_at); imported duplicates resolve by the last id, deterministically.
    val prices = history.sortedWith(compareBy<FinancePriceHistory> { it.date }.thenBy { it.id })
        .associateBy { it.assetId to it.date }
    if (mode == PortfolioHistoryMode.RECORDED) {
        val assetsById = open.associateBy { it.id }
        // Process each matching event once, avoiding an assets-by-dates Cartesian scan.
        val groups = prices.values.mapNotNull { price -> assetsById[price.assetId]?.let { it to price } }
            .groupBy { (asset, price) -> asset.valuationCurrency to price.date }
        return groups.entries.sortedWith(compareBy<Map.Entry<Pair<String, LocalDate>, List<Pair<FinanceAsset, FinancePriceHistory>>>> { it.key.first }
            .thenBy { it.key.second }).map { (key, observed) ->
                PortfolioHistoryPoint(key.second, key.first, observed.fold(BigDecimal.ZERO) { sum, row -> sum + row.first.cost },
                    observed.fold(BigDecimal.ZERO) { sum, row -> sum + row.first.quantity * row.second.price }, observed.size, 0, 0)
            }
    }
    return open.groupBy { it.valuationCurrency }.toSortedMap().flatMap { (currency, rows) ->
            val eligible = rows.filter { it.purchaseDate <= today }
            if (eligible.isEmpty()) return@flatMap emptyList()
            val first = eligible.minOf { it.purchaseDate }
            val dayCount = ChronoUnit.DAYS.between(first, today) + 1
            val step = maxOf(1L, dayCount / 60)
            val offsets = mutableListOf<Long>()
            var offset = 0L
            while (offset < dayCount) { offsets.add(offset); offset += step }
            if (offsets.last() != dayCount - 1) offsets.add(dayCount - 1)
            offsets.map { days ->
                val date = first.plusDays(days)
                val owned = eligible.filter { it.purchaseDate <= date }
                var invested = BigDecimal.ZERO
                var value = BigDecimal.ZERO
                var recorded = 0
                var interpolated = 0
                var current = 0
                owned.forEach { asset ->
                    invested += asset.cost
                    val historical = prices[asset.id to date]
                    val price = when {
                        date == today -> { current++; asset.marketPrice }
                        historical != null -> { recorded++; historical.price }
                        else -> {
                            interpolated++
                            val totalDays = maxOf(1L, ChronoUnit.DAYS.between(asset.purchaseDate, today))
                            val elapsed = ChronoUnit.DAYS.between(asset.purchaseDate, date)
                            val progress = BigDecimal(elapsed).divide(BigDecimal(totalDays), MathContext.DECIMAL128)
                            asset.purchasePrice + (asset.marketPrice - asset.purchasePrice) * progress
                        }
                    }
                    value += asset.quantity * price
                }
                PortfolioHistoryPoint(date, currency, invested, value, recorded, interpolated, current)
            }
    }
}

data class ServerNetWorthHistory(
    val snapshots: List<FinanceSnapshot>, val first: FinanceSnapshot, val latest: FinanceSnapshot,
) {
    val variation: BigDecimal get() = latest.value - first.value
    val variationPercent: BigDecimal? get() = if (first.value.signum() == 0) null else
        variation.multiply(BigDecimal("100")).divide(first.value.abs(), 12, RoundingMode.HALF_UP)
    val minimum: BigDecimal get() = snapshots.minOf { it.value }
    val maximum: BigDecimal get() = snapshots.maxOf { it.value }
}

/** Server snapshots have no currency; do not compare or combine this series with native per-currency totals. */
fun serverNetWorthHistory(snapshots: List<FinanceSnapshot>): ServerNetWorthHistory? {
    if (snapshots.isEmpty()) return null
    val ordered = snapshots.sortedWith(compareBy<FinanceSnapshot> { it.date }.thenBy { it.id })
    return ServerNetWorthHistory(ordered, ordered.first(), ordered.last())
}

data class ServerSnapshotComponents(val cashflow: BigDecimal?, val portfolioGain: BigDecimal?, val discountedEstate: BigDecimal?)

fun serverSnapshotComponents(snapshot: FinanceSnapshot): ServerSnapshotComponents? = snapshot.components?.let { json ->
    fun optional(key: String): BigDecimal? = if (!json.has(key) || json.isNull(key)) null else json.decimal(key)
    ServerSnapshotComponents(optional("cashflow"), optional("portfolioPL"), optional("realEstateDiscounted"))
}

fun formatRecordedValue(value: BigDecimal, locale: Locale): String = java.text.NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits = 2; maximumFractionDigits = 2; roundingMode = RoundingMode.HALF_UP
}.format(value)
