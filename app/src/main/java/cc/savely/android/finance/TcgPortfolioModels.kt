package cc.savely.android.finance

import java.math.BigDecimal
import java.util.Currency
import java.util.Locale
import org.json.JSONObject

data class FinanceTcgCard(
    val id: String,
    val category: String,
    val quantity: Int,
    val purchasePrice: BigDecimal,
    val currentPrice: BigDecimal?,
    val currency: String,
) {
    // The existing CardTrader updater always writes EUR. Non-EUR legacy rows cannot safely yield P/L.
    val valued: Boolean get() = currency == "EUR" && currentPrice != null && currentPrice.signum() > 0
}

data class TcgPortfolioTotal(val category: String, val pieces: Long, val value: BigDecimal, val cost: BigDecimal) {
    val gain: BigDecimal get() = value - cost
    val label: String get() = when (category) {
        "magic" -> "Magic: The Gathering"
        "pokemon" -> "Pokémon TCG"
        "yugioh" -> "Yu-Gi-Oh!"
        "lorcana" -> "Disney Lorcana"
        else -> category
    }
}

/** Portfolio.tsx includes only positive current prices, with no purchase-price fallback for TCG. */
fun tcgPortfolioTotals(cards: List<FinanceTcgCard>): List<TcgPortfolioTotal> = cards.filter { it.valued }
    .groupBy { it.category }.toSortedMap().map { (category, rows) ->
        TcgPortfolioTotal(category, rows.sumOf { it.quantity.toLong() },
            rows.fold(BigDecimal.ZERO) { total, card -> total + card.currentPrice!! * BigDecimal(card.quantity) },
            rows.fold(BigDecimal.ZERO) { total, card -> total + card.purchasePrice * BigDecimal(card.quantity) })
    }

data class PortfolioComposition(val currency: String, val liquid: BigDecimal, val illiquid: BigDecimal, val tcg: BigDecimal) {
    val total: BigDecimal get() = liquid + illiquid
}

/** Market-value composition is independent from the Savely net-worth cashflow/P&L formula. */
fun portfolioComposition(assets: List<FinanceAsset>, cards: List<FinanceTcgCard>): List<PortfolioComposition> {
    val open = assets.filterNot { it.closed }
    val tcgValue = tcgPortfolioTotals(cards).fold(BigDecimal.ZERO) { total, group -> total + group.value }
    val currencies = (open.map { it.valuationCurrency } + if (cards.any { it.valued }) listOf("EUR") else emptyList()).distinct().sorted()
    return currencies.map { currency ->
        val rows = open.filter { it.valuationCurrency == currency }
        val tcg = if (currency == "EUR") tcgValue else BigDecimal.ZERO
        val illiquid = rows.filter { it.type in setOf(FinanceAssetType.REAL_ESTATE, FinanceAssetType.OTHER) }
            .fold(BigDecimal.ZERO) { total, asset -> total + asset.marketValue } + tcg
        val liquid = rows.filter { it.type !in setOf(FinanceAssetType.REAL_ESTATE, FinanceAssetType.OTHER) }
            .fold(BigDecimal.ZERO) { total, asset -> total + asset.marketValue }
        PortfolioComposition(currency, liquid, illiquid, tcg)
    }
}

internal fun tcgCardFromJson(json: JSONObject): FinanceTcgCard {
    val quantity = json.decimal("quantity").intValueExact()
    require(quantity >= 0) { "Quantità TCG negativa: correggi il dato della collezione" }
    val purchase = json.decimal("purchase_price")
    require(purchase.signum() >= 0) { "Prezzo acquisto TCG negativo: correggi il dato della collezione" }
    val currency = (json.nullableString("currency") ?: "EUR").uppercase(Locale.ROOT)
    Currency.getInstance(currency) // Unknown units must fail explicitly rather than be relabelled EUR.
    return FinanceTcgCard(json.getString("id"), json.getString("category"), quantity, purchase,
        if (json.isNull("current_price")) null else json.decimal("current_price"), currency)
}
