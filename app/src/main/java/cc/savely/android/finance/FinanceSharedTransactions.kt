package cc.savely.android.finance

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.json.JSONObject

data class FinanceCoupleConnection(val id: String, val userA: String, val userB: String) {
    fun includes(user: String): Boolean = user == userA || user == userB
}

data class FinanceSharedExpense(
    val id: String, val connectionId: String, val originalTransactionId: String, val createdBy: String,
    val categoryName: String?, val creatorShare: BigDecimal, val partnerShare: BigDecimal,
    val total: BigDecimal, val currency: String, val exchangeRateEur: BigDecimal,
    val description: String, val date: LocalDate,
)

data class TransactionShareRequest(val connectionId: String, val categoryName: String?, val customPartner: String? = null)

/** A personal INSERT may have committed; sharing failure must not replay the editor's INSERT. */
class TransactionSharingException(val transactionId: String, val transactionConfirmed: Boolean, cause: Exception) : Exception(
    if (transactionConfirmed) "Spesa salvata, ma condivisione non confermata. Aggiorna i dati e verifica la transazione prima di riprovare."
    else "Salvataggio o condivisione non confermati. Aggiorna i dati e verifica la transazione prima di riprovare.", cause,
)

data class FinanceTransactionItem(val transaction: FinanceTransaction? = null, val share: FinanceSharedExpense? = null) {
    val date: LocalDate get() = transaction?.date ?: requireNotNull(share).date
    val key: String get() = transaction?.let { "tx-${it.id}" } ?: "share-${requireNotNull(share).id}"
}

fun transactionDateRange(period: String, today: LocalDate, year: String = today.year.toString(), from: String = "", to: String = ""): Pair<LocalDate?, LocalDate?> {
    fun date(raw: String): LocalDate? = raw.takeIf { it.isNotBlank() }?.let {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(it)) { "Formato data non valido" }
        LocalDate.parse(it).also { parsed -> require(parsed.year in 1..9999) { "Anno non valido" } }
    }
    val range = when (period) {
        "month" -> today.withDayOfMonth(1) to YearMonth.from(today).atEndOfMonth()
        "previous" -> YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
        "semester" -> today.minusMonths(5).withDayOfMonth(1) to YearMonth.from(today).atEndOfMonth()
        "twelve" -> today.minusMonths(11).withDayOfMonth(1) to YearMonth.from(today).atEndOfMonth()
        "year" -> {
            val value = year.toIntOrNull()
            require(value != null && value in 1..9999) { "Anno non valido" }
            LocalDate.of(value, 1, 1) to LocalDate.of(value, 12, 31)
        }
        "custom" -> date(from) to date(to)
        "all" -> null to null
        else -> error("Periodo non riconosciuto")
    }
    require(range.first == null || range.second == null || range.first!! <= range.second!!) { "La data iniziale supera quella finale" }
    return range
}

/** Web applies the category-ID selector to owned rows only; partner rows have category names, not IDs.
 * Partner rows remain visible with "only shared" and are expenses regardless of the owned type selector.
 */
fun transactionDisplayItems(data: FinanceData, from: LocalDate? = null, to: LocalDate? = null,
    categoryId: String? = null, type: TransactionType? = null, search: String = "", onlyShared: Boolean = false,
): List<FinanceTransactionItem> {
    require(from == null || to == null || from <= to) { "La data iniziale supera quella finale" }
    val availableShares = if (data.coupleConnection == null) emptyList() else data.sharedExpenses
        .filter { it.connectionId == data.coupleConnection.id }
    val ownShares = availableShares.filter { it.createdBy == data.userId }.associateBy { it.originalTransactionId }
    val owned = filterTransactions(data.transactions, from, to, categoryId, type, search, data.categories)
        .filter { !onlyShared || it.id in ownShares }
        .map { FinanceTransactionItem(it, ownShares[it.id]) }
    val query = search.trim()
    val partner = availableShares
        .filter { it.createdBy != data.userId && (from == null || it.date >= from) && (to == null || it.date <= to) &&
            (query.isBlank() || it.description.contains(query, true) || it.categoryName?.contains(query, true) == true) }
        .map { FinanceTransactionItem(share = it) }
    return (owned + partner).sortedWith(compareByDescending<FinanceTransactionItem> { it.date }.thenBy { it.key })
}

internal fun financeConnectionFromJson(row: JSONObject, owner: String): FinanceCoupleConnection {
    val connection = FinanceCoupleConnection(UUID.fromString(row.getString("id")).toString(),
        UUID.fromString(row.getString("user_a")).toString(), UUID.fromString(row.getString("user_b")).toString())
    require(connection.includes(owner) && row.has("revoked_at") && row.isNull("revoked_at")) { "Connessione non attiva o non appartenente all’account" }
    return connection
}

internal fun financeSharedExpenseFromJson(row: JSONObject, connection: FinanceCoupleConnection): FinanceSharedExpense {
    val createdBy = UUID.fromString(row.getString("created_by")).toString()
    require(row.getString("connection_id") == connection.id && connection.includes(createdBy)) { "Spesa condivisa di una connessione diversa" }
    fun amount(key: String): BigDecimal = row.decimal(key).also {
        require(it.signum() >= 0 && it < BigDecimal("10000000000000")) { "Importo condiviso non valido" }
    }
    val currency = row.nullableString("currency") ?: "EUR"
    require(currency in supportedCurrencies) { "Valuta condivisa non supportata" }
    val rate = if (row.isNull("exchange_rate_eur")) BigDecimal.ONE else row.decimal("exchange_rate_eur")
    require(rate.signum() > 0) { "Cambio condiviso non valido" }
    return FinanceSharedExpense(UUID.fromString(row.getString("id")).toString(), connection.id,
        UUID.fromString(row.getString("original_tx_id")).toString(), createdBy, row.nullableString("couple_category_name"),
        amount("creator_share_amount"), amount("my_share_amount"), amount("total_amount"), currency, rate,
        row.nullableString("description").orEmpty(), LocalDate.parse(row.getString("date")))
}
