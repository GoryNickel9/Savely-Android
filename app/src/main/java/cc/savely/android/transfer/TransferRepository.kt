package cc.savely.android.transfer

import cc.savely.android.data.SupabaseApi
import cc.savely.android.data.ApiError
import cc.savely.android.data.ApiException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.net.URLEncoder

data class ImportCategory(val id: String, val name: String, val type: String)
data class ImportContext(
    val userId: String, val categories: List<ImportCategory>, val existingKeys: Set<String>, val mappings: Map<String, String>,
    val categoryMappings: List<TransferCategoryMapping> = emptyList(),
    val isinMappings: List<TransferIsinMapping> = emptyList(), val assets: List<JSONObject> = emptyList(),
    val existingTransactions: List<JSONObject> = emptyList(), val includesInvestments: Boolean = false,
    val ownerRevision: Long? = null,
)
data class ExportData(val csv: String, val counts: Map<String, Int>, val userId: String, val ownerRevision: Long)

class TransferRepository(private val api: SupabaseApi) {
    suspend fun importContext(includeInvestments: Boolean = false, expectedOwner: String? = null, expectedRevision: Long? = null): ImportContext {
        val snapshot = api.session() ?: throw TransferException("Accedi prima di importare.")
        val userId = snapshot.userId
        val revision = api.sessionOwnerRevision(snapshot) ?: throw TransferException("Account cambiato. Riapri il file.")
        requireTransfer((expectedOwner == null) == (expectedRevision == null), "Sessione import non valida.")
        requireTransfer(expectedOwner == null || expectedOwner == userId && expectedRevision == revision, "Account cambiato. Riapri il file con l'account corretto.")
        val categories = fetchAll("categories", "user_id=eq.${encoded(userId)}&deleted_at=is.null", userId, revision).map {
            ImportCategory(it.getString("id"), it.optString("name"), it.getString("type"))
        }
        val existing = fetchAll("transactions", "user_id=eq.${encoded(userId)}&deleted_at=is.null", userId, revision)
        val keys = existing.mapNotNull(::existingKey).toSet()
        if (keys.size != existing.size && existing.any { existingKey(it) == null }) throw TransferException("Dati esistenti non leggibili: controllo duplicati interrotto.")
        val mappingRows = fetchAll("category_mappings", "user_id=eq.${encoded(userId)}", userId, revision)
        val categoryMappings = mappingRows.map {
            TransferCategoryMapping(it.getString("id"), it.getString("description"), if (it.isNull("category_id")) null else it.getString("category_id"), it.getString("source"))
        }
        val isinMappings = if (includeInvestments) readIsinMappings(userId, revision) else emptyList()
        val assets = if (includeInvestments) fetchAll("portfolio_assets", "user_id=eq.${encoded(userId)}", userId, revision) else emptyList()
        checkAccount(userId, revision)
        return ImportContext(userId, categories, keys, categoryMappings.mapNotNull { mapping -> mapping.categoryId?.let { mapping.description.lowercase() to it } }.toMap(),
            categoryMappings, isinMappings, assets, existing, includeInvestments, revision)
    }

    suspend fun mappingContext(previous: ImportContext? = null): ImportContext {
        val snapshot = api.session() ?: throw TransferException("Accedi prima di gestire i mapping.")
        val owner = snapshot.userId
        val revision = api.sessionOwnerRevision(snapshot) ?: throw TransferException("Account cambiato. Riapri la schermata.")
        requireTransfer(previous == null || previous.userId == owner && previous.ownerRevision == revision, "Account cambiato. Riapri la schermata.")
        val mappings = readIsinMappings(owner, revision)
        checkAccount(owner, revision)
        return previous?.copy(isinMappings = mappings) ?: ImportContext(owner, emptyList(), emptySet(), emptyMap(), isinMappings = mappings, ownerRevision = revision)
    }

    private suspend fun readIsinMappings(owner: String, revision: Long): List<TransferIsinMapping> =
        fetchAll("isin_mappings", "user_id=eq.${encoded(owner)}", owner, revision).map {
            TransferIsinMapping(it.getString("id"), it.getString("isin"), it.getString("symbol"), it.getString("name"), it.getString("asset_type"))
        }

    suspend fun applyMutation(plan: TransferMutation) {
        checkAccount(plan.owner, plan.ownerRevision)
        requireTransfer(plan.status in setOf(TransferMutationStatus.READY, TransferMutationStatus.ABSENT), "Verifica prima l'esito della scrittura precedente.")
        requireTransfer(plan.table in MUTABLE_TABLES, "Tabella import non consentita.")
        val body = JSONObject(plan.payload)
        requireTransfer(body.optString("id") == plan.id && body.optString("user_id") == plan.owner, "Payload import non coerente con l'account.")
        val scope = "user_id=eq.${encoded(plan.owner)}" + mutationPrecondition(plan)
        val result = when (plan.kind) {
            TransferMutationKind.INSERT -> api.insert(plan.table, body, expectedOwner = plan.owner, expectedRevision = plan.ownerRevision)
            TransferMutationKind.UPDATE -> api.update(plan.table, plan.id, body, scope, expectedOwner = plan.owner, expectedRevision = plan.ownerRevision)
            TransferMutationKind.DELETE -> { api.delete(plan.table, plan.id, scope, expectedOwner = plan.owner, expectedRevision = plan.ownerRevision); null }
        }
        checkAccount(plan.owner, plan.ownerRevision)
        if (result != null && !TransferRules.payloadMatches(result, body))
            throw ApiException(ApiError.RESPONSE, "Risposta non coerente con la scrittura: verifica l'esito prima di riprovare.")
    }

    /** PostgREST applies these predicates atomically with PATCH/DELETE, closing the pre-read race. */
    private fun mutationPrecondition(plan: TransferMutation): String {
        if (plan.kind == TransferMutationKind.INSERT) return ""
        val previous = plan.expectedBefore?.let(::JSONObject) ?: throw TransferException("Rileggi il mapping prima di modificarlo.")
        requireTransfer(previous.optString("id") == plan.id && previous.optString("user_id") == plan.owner,
            "Payload import non coerente con l'account.")
        val fields = when (plan.table) {
            "isin_mappings" -> listOf("isin", "symbol", "name", "asset_type")
            "category_mappings" -> listOf("description", "category_id", "source")
            else -> throw TransferException("Tabella import non consentita.")
        }
        return fields.joinToString("") { field ->
            requireTransfer(previous.has(field), "Rileggi il mapping prima di modificarlo.")
            if (previous.isNull(field)) "&$field=is.null" else {
                val value = previous.getString(field)
                // Quoted PostgREST literals preserve commas, parentheses, quotes and backslashes.
                val quoted = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                "&$field=eq.${encoded(quoted)}"
            }
        }
    }

    suspend fun reconcile(plan: TransferMutation): TransferMutationStatus {
        requireTransfer(plan.table in MUTABLE_TABLES, "Tabella import non consentita.")
        val records = fetchAll(plan.table, "user_id=eq.${encoded(plan.owner)}", plan.owner, plan.ownerRevision)
        val row = records.singleOrNull { it.optString("id") == plan.id }
        checkAccount(plan.owner, plan.ownerRevision)
        return when {
            plan.kind == TransferMutationKind.DELETE && row == null -> TransferMutationStatus.CONFIRMED
            row == null && plan.kind == TransferMutationKind.INSERT -> TransferMutationStatus.ABSENT
            row != null && plan.kind != TransferMutationKind.DELETE && TransferRules.payloadMatches(row, JSONObject(plan.payload)) -> TransferMutationStatus.CONFIRMED
            else -> TransferMutationStatus.BLOCKED
        }
    }

    private suspend fun checkAccount(owner: String, revision: Long?) {
        val snapshot = api.session()
        requireTransfer(snapshot != null && snapshot.userId == owner && revision != null && api.sessionOwnerRevision(snapshot) == revision, "Account cambiato. Riapri il file con l'account corretto.")
    }

    suspend fun export(): ExportData {
        val snapshot = api.session() ?: throw TransferException("Accedi prima di esportare.")
        val userId = snapshot.userId
        val revision = api.sessionOwnerRevision(snapshot) ?: throw TransferException("Account cambiato durante l'export. Riprova.")
        val records = mutableListOf<JSONObject>()
        val counts = linkedMapOf<String, Int>()
        var estimatedBytes = 0L
        val connections = fetchAll("couple_connections", participantScope("couple_connections", userId), userId, revision)
        connections.forEach { validateExportOwner("couple_connections", it, userId, emptySet()) }
        val connectionIds = connections.map { it.getString("id") }.toSet()
        for (table in USER_TABLES) {
            currentCoroutineContext().ensureActive()
            val rows = when (table) {
                "couple_connections" -> connections
                "couple_budgets", "shared_expenses" -> connectionIds.chunked(100).flatMap { ids ->
                    fetchAll(table, "connection_id=in.(${ids.joinToString(",") { encoded(it) }})", userId, revision, connectionIds)
                }
                else -> fetchAll(table, participantScope(table, userId), userId, revision)
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
        checkAccount(userId, revision)
        if (records.isEmpty()) throw TransferException("Non ci sono dati da esportare.")
        val csv = TransferCsv.serialize(records)
        if (csv.toByteArray(Charsets.UTF_8).size > MAX_EXPORT_BYTES) throw TransferException("L'export supera 25 MiB. Nessun file parziale è stato salvato.")
        checkAccount(userId, revision)
        return ExportData(csv, counts, userId, revision)
    }

    /** Keyset pagination continues until an empty page, including servers with a lower max_rows. */
    private suspend fun fetchAll(table: String, scope: String, owner: String? = null, revision: Long? = null,
        connectionIds: Set<String> = emptySet()): List<JSONObject> {
        val records = mutableListOf<JSONObject>()
        var bytes = 0L
        var cursor: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            if (owner != null) checkAccount(owner, revision)
            val query = "select=*&order=id.asc&limit=500&$scope" + (cursor?.let { "&id=gt.${encoded(it)}" } ?: "")
            val page = api.select(table, query, expectedOwner = owner, expectedRevision = revision)
            if (owner != null) checkAccount(owner, revision)
            if (page.length() == 0) return records
            for (index in 0 until page.length()) {
                val row = page.optJSONObject(index) ?: throw TransferException("Risposta export non valida per $table.")
                if (owner != null) validateExportOwner(table, row, owner, connectionIds)
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
        private val MUTABLE_TABLES = setOf("transactions", "categories", "category_mappings", "isin_mappings", "portfolio_assets")
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
