package cc.savely.android.transfer

import cc.savely.android.data.SupabaseApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.net.URLEncoder

data class ImportCategory(val id: String, val name: String, val type: String)
data class ImportContext(val userId: String, val categories: List<ImportCategory>, val existingKeys: Set<String>, val mappings: Map<String, String>)
data class ExportData(val csv: String, val counts: Map<String, Int>, val userId: String)

class TransferRepository(private val api: SupabaseApi) {
    suspend fun importContext(): ImportContext {
        val userId = api.session()?.userId ?: throw TransferException("Accedi prima di importare.")
        val categories = fetchAll("categories", "user_id=eq.${encoded(userId)}&deleted_at=is.null").map {
            ImportCategory(it.getString("id"), it.optString("name"), it.getString("type"))
        }
        val existing = fetchAll("transactions", "user_id=eq.${encoded(userId)}&deleted_at=is.null")
        val keys = existing.mapNotNull(::existingKey).toSet()
        if (keys.size != existing.size && existing.any { existingKey(it) == null }) throw TransferException("Dati esistenti non leggibili: controllo duplicati interrotto.")
        val mappings = fetchAll("category_mappings", "user_id=eq.${encoded(userId)}").associate {
            it.optString("description").lowercase() to it.optString("category_id")
        }
        return ImportContext(userId, categories, keys, mappings)
    }

    suspend fun export(): ExportData {
        val userId = api.session()?.userId ?: throw TransferException("Accedi prima di esportare.")
        val records = mutableListOf<JSONObject>()
        val counts = linkedMapOf<String, Int>()
        var estimatedBytes = 0L
        val connections = fetchAll("couple_connections", participantScope("couple_connections", userId))
        connections.forEach { validateExportOwner("couple_connections", it, userId, emptySet()) }
        val connectionIds = connections.map { it.getString("id") }.toSet()
        for (table in USER_TABLES) {
            currentCoroutineContext().ensureActive()
            val rows = when (table) {
                "couple_connections" -> connections
                "couple_budgets", "shared_expenses" -> connectionIds.chunked(100).flatMap { ids ->
                    fetchAll(table, "connection_id=in.(${ids.joinToString(",") { encoded(it) }})")
                }
                else -> fetchAll(table, participantScope(table, userId))
            }
            counts[table] = rows.size
            for (row in rows) {
                validateExportOwner(table, row, userId, connectionIds)
                estimatedBytes += row.toString().toByteArray(Charsets.UTF_8).size + table.length + 1
                if (estimatedBytes > MAX_EXPORT_BYTES) throw TransferException("L'export supera 25 MiB. Nessun file parziale è stato salvato.")
                records += JSONObject().put("entity", table).apply { row.keys().forEach { put(it, row.get(it)) } }
                if (records.size > MAX_EXPORT_ROWS) throw TransferException("L'export supera 50.000 righe. Nessun file parziale è stato salvato.")
            }
        }
        // Preserve the current account boundary even if a different login happened during collection.
        if (api.session()?.userId != userId) throw TransferException("Account cambiato durante l'export. Riprova.")
        if (records.isEmpty()) throw TransferException("Non ci sono dati da esportare.")
        val csv = TransferCsv.serialize(records)
        if (csv.toByteArray(Charsets.UTF_8).size > MAX_EXPORT_BYTES) throw TransferException("L'export supera 25 MiB. Nessun file parziale è stato salvato.")
        return ExportData(csv, counts, userId)
    }

    /** Keyset pagination continues until an empty page, including servers with a lower max_rows. */
    private suspend fun fetchAll(table: String, scope: String): List<JSONObject> {
        val records = mutableListOf<JSONObject>()
        var bytes = 0L
        var cursor: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val query = "select=*&order=id.asc&limit=500&$scope" + (cursor?.let { "&id=gt.${encoded(it)}" } ?: "")
            val page = api.select(table, query)
            if (page.length() == 0) return records
            for (index in 0 until page.length()) {
                val row = page.optJSONObject(index) ?: throw TransferException("Risposta export non valida per $table.")
                val id = row.optString("id")
                if (!SAFE_ID.matches(id) || (cursor != null && id <= cursor!!)) throw TransferException("Paginazione non valida per $table; export interrotto.")
                cursor = id
                bytes += row.toString().toByteArray(Charsets.UTF_8).size
                if (bytes > MAX_EXPORT_BYTES) throw TransferException("$table supera 25 MiB; operazione interrotta senza troncamento.")
                records += row
                if (records.size > MAX_EXPORT_ROWS) throw TransferException("$table supera 50.000 righe; operazione interrotta senza troncamento.")
            }
        }
    }

    companion object {
        const val MAX_EXPORT_ROWS = 50_000
        const val MAX_EXPORT_BYTES = 25 * 1024 * 1024
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val USER_TABLES = listOf(
            "transactions", "categories", "budgets", "savings_goals", "recurring_expenses", "portfolio_assets",
            "asset_price_history", "manual_price_updates", "price_update_logs", "category_mappings", "isin_mappings",
            "poker_manual_expenses", "poker_monthly_expenses", "poker_next_cut", "poker_hourly_earnings", "poker_rakeback",
            "liquido_sigaretta", "cbd", "thc", "tgc_cards", "library_items", "couple_connection_requests",
            "couple_connections", "couple_budgets", "shared_expenses",
        )
        fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8")
        fun participantScope(table: String, userId: String): String {
            if (!SAFE_ID.matches(userId)) throw TransferException("ID utente non valido.")
            return when (table) {
                "couple_connection_requests" -> "or=(sender_id.eq.$userId,receiver_id.eq.$userId)"
                "couple_connections" -> "or=(user_a.eq.$userId,user_b.eq.$userId)"
                "couple_budgets", "shared_expenses" -> throw TransferException("Servono i collegamenti della coppia per questa tabella.")
                in USER_TABLES -> "user_id=eq.${encoded(userId)}"
                else -> throw TransferException("Tabella non esportabile.")
            }
        }

        fun validateExportOwner(table: String, row: JSONObject, userId: String, connectionIds: Set<String>) {
            val authorized = when (table) {
                "couple_connection_requests" -> row.optString("sender_id") == userId || row.optString("receiver_id") == userId
                "couple_connections" -> row.optString("user_a") == userId || row.optString("user_b") == userId
                "couple_budgets", "shared_expenses" -> row.optString("connection_id") in connectionIds
                else -> row.optString("user_id") == userId
            }
            if (!authorized) throw TransferException("Il server ha restituito dati di un altro account per $table; export interrotto.")
        }

        fun existingKey(row: JSONObject): String? = runCatching {
            ImportTransaction(0, row.optString("description"), java.time.LocalDate.parse(row.getString("date")),
                row.get("amount").toString().toBigDecimal().abs(), row.optString("currency", "EUR").let { if (it == "null" || it.isBlank()) "EUR" else it },
                row.getString("type")).duplicateKey()
        }.getOrNull()

        fun markDuplicates(transactions: List<ImportTransaction>, existingKeys: Set<String>): Set<Int> {
            val seen = existingKeys.toMutableSet()
            return transactions.filter { !seen.add(it.duplicateKey()) }.map { it.row }.toSet()
        }
    }
}
