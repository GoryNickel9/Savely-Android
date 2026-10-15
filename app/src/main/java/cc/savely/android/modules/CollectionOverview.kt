package cc.savely.android.modules

import cc.savely.android.data.SupabaseApi
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale

internal enum class CollectionKind(val table: String, val categories: List<String>) {
    TCG("tgc_cards", listOf("magic", "pokemon", "yugioh")),
    LIBRARY("library_items", listOf("libri", "fumetti", "manga")),
}

internal data class CollectionCurrencyTotal(
    val currency: String, val pieces: Long, val investment: BigDecimal, val value: BigDecimal,
) {
    val profit: BigDecimal get() = value - investment
    val gainPercent: BigDecimal? get() = if (investment.signum() == 0) null
        else profit.multiply(BigDecimal(100)).divide(investment, java.math.MathContext.DECIMAL128)
}

internal data class CollectionCategoryTotal(
    val category: String, val records: Int, val pieces: Long, val currencies: List<CollectionCurrencyTotal>,
)

internal data class CollectionOverview(
    val ownerId: String, val records: Int, val pieces: Long,
    val currencies: List<CollectionCurrencyTotal>, val categories: List<CollectionCategoryTotal>,
    val ownerRevision: Long? = null,
)

/** A complete owner snapshot is required before any totals are returned to the screen. */
internal class CollectionOverviewRepository(private val api: SupabaseApi) {
    suspend fun load(kind: CollectionKind): CollectionOverview {
        val snapshot = api.session() ?: error("Accedi per leggere i tuoi dati")
        val owner = snapshot.userId
        val revision = requireNotNull(api.sessionOwnerRevision(snapshot)) { "Account cambiato durante la lettura della collezione" }
        suspend fun checkOwner() {
            val current = api.session()
            check(current != null && current.userId == owner && api.sessionOwnerRevision(current) == revision) {
                "Account cambiato durante la lettura della collezione"
            }
        }
        val rows = mutableListOf<JSONObject>()
        val ids = mutableSetOf<String>()
        while (true) {
            checkOwner()
            val page = api.select(kind.table, "user_id=eq.$owner&order=id.asc&limit=500&offset=${rows.size}",
                expectedOwner = owner, expectedRevision = revision)
            checkOwner()
            require(page.length() <= 500) { "Pagina della collezione non valida" }
            if (page.length() == 0) break
            for (index in 0 until page.length()) {
                val row = page.getJSONObject(index)
                require(row.getString("user_id") == owner) { "Record della collezione di un altro account" }
                require(ids.add(row.getString("id"))) { "La collezione è cambiata durante la lettura. Riprova." }
                rows.add(row)
            }
            require(rows.size <= 100000) { "Collezione troppo grande per la panoramica" }
        }
        val result = collectionOverview(kind, owner, rows).copy(ownerRevision = revision)
        checkOwner()
        return result
    }
}

/** Web uses price * quantity, null Library prices -> 0 and null TCG current price -> purchase.
 * TCG's currency column is retained: monetary totals from distinct units are never added together.
 */
internal fun collectionOverview(kind: CollectionKind, owner: String, rows: List<JSONObject>): CollectionOverview {
    data class Entry(val category: String, val currency: String, val pieces: Long,
        val investment: BigDecimal, val value: BigDecimal)
    fun price(row: JSONObject, key: String, nullable: Boolean): BigDecimal {
        if (row.isNull(key)) {
            require(nullable) { "Prezzo della collezione mancante" }
            return BigDecimal.ZERO
        }
        val raw = row.get(key)
        require(raw is Number) { "Prezzo della collezione non valido" }
        return raw.toString().toBigDecimalOrNull()?.also {
            require(it.signum() >= 0 && it <= BigDecimal("99999999.99") && it.stripTrailingZeros().scale() <= 2) {
                "Prezzo della collezione non valido"
            }
        } ?: error("Prezzo della collezione non valido")
    }
    val seen = mutableSetOf<String>()
    val entries = rows.map { row ->
        require(row.getString("user_id") == owner) { "Record della collezione di un altro account" }
        require(seen.add(row.getString("id"))) { "Record della collezione duplicato" }
        val category = row.getString("category").also { require(it.isNotBlank()) { "Categoria della collezione mancante" } }
        val quantity = row.get("quantity")
        require(quantity is Number) { "Quantità della collezione non valida" }
        val pieces = try { BigDecimal(quantity.toString()).longValueExact() } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Quantità della collezione non valida")
        }
        require(pieces in 0..Int.MAX_VALUE.toLong()) { "Quantità della collezione non valida" }
        // Missing/null currency is the legacy EUR default; an explicit invalid code is an error.
        val currency = if (kind == CollectionKind.LIBRARY || row.isNull("currency")) "EUR"
            else row.getString("currency").trim().uppercase(Locale.ROOT).also {
                require(Regex("[A-Z]{3}").matches(it)) { "Valuta della collezione non valida" }
            }
        val purchase = price(row, "purchase_price", kind == CollectionKind.LIBRARY)
        val current = if (kind == CollectionKind.LIBRARY) price(row, "reselling_value", true)
            else if (row.isNull("current_price")) purchase else price(row, "current_price", false)
        val multiplier = BigDecimal.valueOf(pieces)
        Entry(category, currency, pieces, purchase * multiplier, current * multiplier)
    }
    fun totals(values: List<Entry>) = values.groupBy { it.currency }.toSortedMap().map { (currency, group) ->
        CollectionCurrencyTotal(currency, group.sumOf { it.pieces },
            group.fold(BigDecimal.ZERO) { sum, row -> sum + row.investment },
            group.fold(BigDecimal.ZERO) { sum, row -> sum + row.value })
    }
    val byCategory = entries.groupBy { it.category }
    val categories = kind.categories + (byCategory.keys - kind.categories.toSet()).sorted()
    return CollectionOverview(owner, rows.size, entries.sumOf { it.pieces }, totals(entries), categories.map { category ->
        val group = byCategory[category].orEmpty()
        CollectionCategoryTotal(category, group.size, group.sumOf { it.pieces }, totals(group))
    })
}
