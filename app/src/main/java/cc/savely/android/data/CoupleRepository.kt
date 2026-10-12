package cc.savely.android.data

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth
import java.util.Locale
import java.util.UUID

data class CoupleSnapshot(
    val userId: String,
    val code: String,
    val connection: JSONObject?,
    val requests: JSONArray,
    val shares: JSONArray,
    val budgets: JSONArray,
    val transactions: JSONArray,
    val comparisons: List<CoupleBudgetComparison>,
    val medians: Map<String, BigDecimal>,
    val archivedConnections: List<CoupleArchivedConnection> = emptyList(),
)

data class CoupleArchivedConnection(val id: String, val createdAt: Instant, val revokedAt: Instant)

data class CoupleBudgetComparison(
    val id: String,
    val category: String,
    val currency: String,
    val limit: BigDecimal,
    val median: BigDecimal,
) {
    val exceeded: Boolean get() = median > limit
    val progress: Float get() = if (limit.signum() <= 0) 0f
        else median.divide(limit, 8, RoundingMode.HALF_UP).coerceIn(BigDecimal.ZERO, BigDecimal.ONE).toFloat()
}

data class CoupleSplit(val creator: BigDecimal, val partner: BigDecimal, val total: BigDecimal) {
    val roundingDifference: BigDecimal get() = creator + partner - total
}

data class CoupleBudgetSummary(val currency: String, val planned: BigDecimal, val median: BigDecimal) {
    val difference: BigDecimal get() = median - planned
}

/** Same budget totals as the web, calculated separately for each currency. */
fun coupleBudgetSummaries(budgets: JSONArray, shares: JSONArray): List<CoupleBudgetSummary> {
    val planned = sortedMapOf<String, BigDecimal>()
    for (index in 0 until budgets.length()) {
        val row = budgets.getJSONObject(index)
        val amount = row.get("amount").toString().toBigDecimal()
        require(amount.signum() >= 0 && amount < MAX_COUPLE_AMOUNT) { "Budget condiviso non valido." }
        val currency = row.getString("currency")
        planned[currency] = (planned[currency] ?: BigDecimal.ZERO) + amount
    }
    return planned.map { (currency, amount) -> CoupleBudgetSummary(currency, amount, medianMonthlyCoupleShare(shares, currency)) }
}

/** Matches the repository's SQL view in migration 20260703070000, including equal-split rounding. */
fun coupleSplit(total: BigDecimal, customPartner: BigDecimal? = null): CoupleSplit {
    require(total.signum() >= 0 && total.stripTrailingZeros().scale() <= 2 && total < MAX_COUPLE_AMOUNT) { "Importo della transazione non valido." }
    if (customPartner != null) {
        require(customPartner > BigDecimal.ZERO && customPartner < total && customPartner.stripTrailingZeros().scale() <= 2) {
            "La quota del partner deve essere maggiore di zero, minore del totale e avere al massimo 2 decimali."
        }
        return CoupleSplit((total - customPartner).setScale(2), customPartner.setScale(2), total.setScale(2))
    }
    val half = total.divide(BigDecimal("2"), 2, RoundingMode.HALF_UP)
    return CoupleSplit(half, half, total.setScale(2))
}

fun normalizedCoupleCode(raw: String): String {
    val code = raw.trim().uppercase(Locale.ROOT)
    require(code.length == 8) { "Il codice del partner deve contenere 8 caratteri." }
    require(code.all { it in "ABCDEFGHJKMNPQRSTUVWXYZ23456789" }) { "Il codice contiene caratteri non validi. Non usare O, I, L, 0 o 1." }
    return code
}

fun couplePartnerAmount(raw: String): BigDecimal {
    val cleaned = raw.trim().replace(',', '.')
    require(cleaned.length in 1..32 && Regex("[0-9]+(?:\\.[0-9]{1,2})?").matches(cleaned)) { "Inserisci un importo con al massimo 2 decimali." }
    val amount = cleaned.toBigDecimal()
    require(amount > BigDecimal.ZERO && amount < MAX_COUPLE_AMOUNT) { "Inserisci un importo positivo entro il limite del database." }
    return amount
}

/** Like the web median: months with movements only, deleted originals excluded; currencies never summed. */
fun medianMonthlyCoupleShare(shares: JSONArray, currency: String, category: String? = null): BigDecimal {
    val monthly = sortedMapOf<YearMonth, BigDecimal>()
    for (index in 0 until shares.length()) {
        val row = shares.getJSONObject(index)
        if (!row.isNull("tx_deleted_at") || row.getString("currency") != currency) continue
        if (category != null && (row.isNull("couple_category_name") || row.getString("couple_category_name") != category)) continue
        val date = LocalDate.parse(row.getString("date"))
        val amount = row.get("my_share_amount").toString().toBigDecimal()
        require(amount.signum() >= 0 && amount < MAX_COUPLE_AMOUNT) { "Quota condivisa non valida." }
        val month = YearMonth.from(date)
        monthly[month] = (monthly[month] ?: BigDecimal.ZERO) + amount
    }
    val totals = monthly.values.sorted()
    if (totals.isEmpty()) return BigDecimal.ZERO.setScale(2)
    val middle = totals.size / 2
    return if (totals.size % 2 == 1) totals[middle].setScale(2, RoundingMode.HALF_UP)
    else (totals[middle - 1] + totals[middle]).divide(BigDecimal("2"), 2, RoundingMode.HALF_UP)
}

fun availableCoupleCategories(shares: JSONArray, budgets: JSONArray): List<String> {
    val used = (0 until budgets.length()).map { budgets.getJSONObject(it).getString("couple_category_name") }.toSet()
    return (0 until shares.length()).map { shares.getJSONObject(it) }
        .filter { it.isNull("tx_deleted_at") && !it.isNull("couple_category_name") }
        .map { it.getString("couple_category_name") }.filter { it.isNotBlank() && it !in used }.distinct().sorted()
}

class CoupleRepository(private val api: SupabaseApi) {
    suspend fun load(): CoupleSnapshot {
        val user = userId()
        val revision = api.sessionOwnerRevision()
        val profile = api.profile()
        if (SupabaseApi.parsePermissions(profile?.optJSONObject("permissions"))["couple_expenses"] != true) {
            throw ApiException(ApiError.PERMISSION, "Le spese di coppia non sono abilitate per il tuo account.")
        }
        val connections = api.select("couple_connections", "or=(user_a.eq.$user,user_b.eq.$user)&revoked_at=is.null")
        require(connections.length() <= 1) { "Più connessioni attive: verifica il tuo account." }
        val connection = connections.optJSONObject(0)
        val requests = pages("couple_connection_requests", "or=(sender_id.eq.$user,receiver_id.eq.$user)&status=eq.pending")
        val shares: JSONArray
        val budgets: JSONArray
        val transactions: JSONArray
        if (connection == null) {
            shares = JSONArray(); budgets = JSONArray(); transactions = JSONArray()
        } else {
            val id = uuid(connection.getString("id"))
            shares = sortedRows(pages("shared_expenses_view", "connection_id=eq.$id"), "date", descending = true)
            budgets = sortedRows(pages("couple_budgets", "connection_id=eq.$id"), "couple_category_name")
            transactions = sortedRows(pages("transactions", "user_id=eq.$user&type=eq.expense&deleted_at=is.null"), "date", descending = true)
        }
        val comparisons = (0 until budgets.length()).map { index ->
            val row = budgets.getJSONObject(index)
            val category = row.getString("couple_category_name")
            val currency = row.getString("currency")
            val limit = row.get("amount").toString().toBigDecimal()
            require(limit.signum() >= 0 && limit < MAX_COUPLE_AMOUNT) { "Budget condiviso non valido." }
            CoupleBudgetComparison(row.getString("id"), category, currency, limit, medianMonthlyCoupleShare(shares, currency, category))
        }
        val currencies = (0 until shares.length()).map { shares.getJSONObject(it) }
            .filter { it.isNull("tx_deleted_at") }.map { it.getString("currency") }.distinct().sorted()
        val medians = currencies.associateWith { medianMonthlyCoupleShare(shares, it) }
        // Privacy migrations allow archived connection metadata, not the former partner's expenses or budgets.
        val archiveRows = pages("couple_connections", "or=(user_a.eq.$user,user_b.eq.$user)&revoked_at=not.is.null")
        val archived = (0 until archiveRows.length()).map { index ->
            val row = archiveRows.getJSONObject(index)
            require(uuid(row.getString("user_a")) == user || uuid(row.getString("user_b")) == user) { "Connessione archiviata non appartenente all'account." }
            CoupleArchivedConnection(uuid(row.getString("id")), Instant.parse(row.getString("created_at")), Instant.parse(row.getString("revoked_at")))
        }.sortedWith(compareByDescending<CoupleArchivedConnection> { it.revokedAt }.thenBy { it.id })
        if (userId() != user || api.sessionOwnerRevision() != revision) {
            throw ApiException(ApiError.AUTHENTICATION, "Account cambiato durante il caricamento. Accedi nuovamente.")
        }
        val code = profile?.optString("couple_code")?.takeUnless { it == "null" } ?: ""
        return CoupleSnapshot(user, code, connection, requests, shares, budgets, transactions, comparisons, medians, archived)
    }

    suspend fun sendInvitation(rawCode: String) {
        val code = normalizedCoupleCode(rawCode)
        val sender = userId()
        val resolved = api.rpc("find_user_by_couple_code", JSONObject().put("p_code", code))
        require(resolved is String && resolved.isNotBlank()) { "Nessun utente trovato con questo codice." }
        val receiver = uuid(resolved)
        require(receiver != sender) { "Non puoi invitare te stesso." }
        val pending = api.select("couple_connection_requests", "select=id&sender_id=eq.$sender&status=eq.pending&limit=1")
        require(pending.length() == 0) { "Hai già un invito in attesa. Annullalo prima di inviarne un altro." }
        api.insert("couple_connection_requests", JSONObject().put("sender_id", sender).put("receiver_id", receiver))
    }

    suspend fun shareExpense(connectionId: String, transactionId: String, category: String, customPartner: String? = null,
        expectedOwner: String? = null, expectedRevision: Long? = null) {
        val id = uuid(connectionId)
        val txId = uuid(transactionId)
        val partner = customPartner?.let(::couplePartnerAmount)
        require((expectedOwner == null) == (expectedRevision == null)) { "Il vincolo della sessione non è completo." }
        val session = api.session() ?: throw ApiException(ApiError.AUTHENTICATION, "Accedi di nuovo.")
        val creator = uuid(session.userId)
        val revision = api.sessionOwnerRevision(session)
            ?: throw ApiException(ApiError.PERMISSION, "La sessione è cambiata. Aggiorna la schermata prima di continuare.")
        if (expectedOwner != null && (expectedOwner != creator || expectedRevision != revision)) {
            throw ApiException(ApiError.PERMISSION, "La sessione è cambiata. Aggiorna la schermata prima di continuare.")
        }
        val active = api.select("couple_connections", "select=id&id=eq.$id&or=(user_a.eq.$creator,user_b.eq.$creator)&revoked_at=is.null")
        require(active.length() == 1) { "La connessione non è più attiva. Aggiorna la schermata." }
        val owned = api.select("transactions", "select=id,amount,currency&id=eq.$txId&user_id=eq.$creator&type=eq.expense&deleted_at=is.null")
        require(owned.length() == 1) { "La transazione non è disponibile per la condivisione." }
        val total = owned.getJSONObject(0).get("amount").toString().toBigDecimal()
        coupleSplit(total, partner)
        val existing = api.select("shared_expenses", "select=id&connection_id=eq.$id&original_tx_id=eq.$txId&limit=1")
        require(existing.length() == 0) { "Questa transazione è già condivisa." }
        api.insert("shared_expenses", JSONObject().put("connection_id", id).put("original_tx_id", txId).put("created_by", creator)
            .put("couple_category_name", category.trim().ifBlank { null } ?: JSONObject.NULL)
            .put("split_mode", if (partner == null) "equal" else "custom").put("partner_amount", partner?.toPlainString() ?: JSONObject.NULL),
            expectedOwner = creator, expectedRevision = revision)
    }

    /** Keyset pagination continues after server-truncated pages. Refuse totals if the dataset exceeds the bound. */
    private suspend fun pages(table: String, scope: String): JSONArray {
        val result = JSONArray()
        var cursor: String? = null
        while (true) {
            val rows = api.select(table, "select=*&$scope&order=id.asc&limit=500" + (cursor?.let { "&id=gt.$it" } ?: ""))
            if (rows.length() == 0) return result
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val next = uuid(row.getString("id"))
                require(cursor == null || next > cursor!!) { "Ordine dei dati condivisi incoerente. Nessun totale calcolato." }
                require(result.length() < 10_000) { "Più di 10.000 righe condivise: nessun totale parziale viene mostrato." }
                result.put(row)
                cursor = next
            }
        }
    }

    private suspend fun userId(): String = uuid(api.session()?.userId ?: throw ApiException(ApiError.AUTHENTICATION, "Accedi di nuovo."))
    private fun uuid(value: String): String {
        require(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value)) { "ID condiviso non valido." }
        return UUID.fromString(value).toString()
    }
    private fun sortedRows(rows: JSONArray, field: String, descending: Boolean = false): JSONArray {
        val items = (0 until rows.length()).map { rows.getJSONObject(it) }.sortedBy { it.getString(field) }
        return JSONArray(if (descending) items.reversed() else items)
    }
}

private val MAX_COUPLE_AMOUNT = BigDecimal("10000000000000")
