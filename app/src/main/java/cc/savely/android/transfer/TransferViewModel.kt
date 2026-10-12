package cc.savely.android.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.savely.android.data.ApiError
import cc.savely.android.data.ApiException
import cc.savely.android.data.SupabaseApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import org.json.JSONObject

enum class ImportStatus { READY, DUPLICATE, IMPORTED, FAILED, UNCERTAIN }
data class TransferNotice(val template: String, val values: List<Pair<String, Any?>>, val localizedValueKeys: Set<String> = emptySet())
data class PreviewTransaction(
    val transaction: ImportTransaction,
    val selected: Boolean,
    val categoryId: String?,
    val rate: String,
    val status: ImportStatus,
    val message: String? = null,
    val insertId: String = UUID.randomUUID().toString(),
    val description: String = transaction.description,
    val note: String = "",
    val rememberMapping: Boolean = false,
    val messageNotice: TransferNotice? = null,
)
data class TransferState(
    val busy: Boolean = false,
    val progress: String = "",
    val context: ImportContext? = null,
    val preview: List<PreviewTransaction> = emptyList(),
    val problems: List<ImportProblem> = emptyList(),
    val skipped: Int = 0,
    val message: String? = null,
    val export: ExportData? = null,
    val progressNotice: TransferNotice? = null,
    val messageNotice: TransferNotice? = null,
    val format: ImportFormat = ImportFormat.NORMALIZED,
    val newCategories: List<TransferCategoryDraft> = emptyList(),
    val isinDrafts: Map<String, TransferIsinMapping> = emptyMap(),
    val mutations: List<TransferMutation> = emptyList(),
)

class TransferViewModel(private val api: SupabaseApi) : ViewModel() {
    private val repository = TransferRepository(api)
    private val mutable = MutableStateFlow(TransferState())
    val state = mutable.asStateFlow()

    fun prepare(read: suspend () -> String, format: ImportFormat, currency: String) {
        if (mutable.value.busy) return
        if (mutable.value.mutations.any { it.status in setOf(TransferMutationStatus.UNCERTAIN, TransferMutationStatus.BLOCKED) }) {
            mutable.update { it.copy(message = "Verifica le scritture precedenti prima di aprire un altro file.", messageNotice = null) }
            return
        }
        val previousContext = mutable.value.context
        mutable.update { it.copy(progressNotice = null, messageNotice = null, busy = true, message = null, progress = "Leggo e controllo il file…",
            preview = emptyList(), problems = emptyList(), skipped = 0, newCategories = emptyList(), isinDrafts = emptyMap(), mutations = emptyList()) }
        viewModelScope.launch {
            try {
                val snapshot = api.session() ?: throw TransferException("Accedi prima di importare.")
                val owner = snapshot.userId
                val revision = api.sessionOwnerRevision(snapshot) ?: throw TransferException("Account cambiato. Riapri il file.")
                requireTransfer(previousContext == null || previousContext.userId == owner && previousContext.ownerRevision == revision, "Account cambiato. Riapri la schermata.")
                val parsed = withContext(Dispatchers.IO) { TransferCsv.parse(read(), format, currency) }
                val context = repository.importContext(format == ImportFormat.BANK, owner, revision)
                requireTransfer(context.userId == owner, "Account cambiato durante la lettura. Riapri il file.")
                val seen = context.existingKeys.toMutableSet()
                val seenInvestments = mutableSetOf<String>()
                val interestCategory = context.categories.firstOrNull { it.type == "income" && it.name.equals("Dividendi", true) }
                val interestDraft = if (parsed.transactions.any { it.bankInterest } && interestCategory == null)
                    TransferCategoryDraft(name = "Dividendi", type = "income") else null
                val preview = parsed.transactions.map { transaction ->
                    val mapping = TransferRules.matchingCategory(transaction, format, context)
                    val isinMapping = context.isinMappings.firstOrNull { it.isin == transaction.isin }
                    val description = if (transaction.bankInterest) "Interessi" else if (format == ImportFormat.BANK)
                        mapping?.description ?: transaction.description else transaction.description
                    val duplicate = (transaction.type != "investment" && !seen.add(transaction.copy(description = description).duplicateKey())) ||
                        (transaction.type == "investment" && !seenInvestments.add(transaction.duplicateKey())) ||
                        (isinMapping != null && context.assets.any {
                        it.optString("symbol").equals(isinMapping.symbol, true)
                    })
                    val validCategory = (transaction.categoryId ?: mapping?.categoryId)?.takeIf { id ->
                        context.categories.any { it.id == id && it.type == transaction.type }
                    }
                    PreviewTransaction(transaction, !duplicate,
                        if (transaction.bankInterest) interestCategory?.id ?: interestDraft?.id else validCategory,
                        transaction.exchangeRateEur?.toPlainString().orEmpty(),
                        if (duplicate) ImportStatus.DUPLICATE else ImportStatus.READY,
                        message = if (duplicate && transaction.isin != null) "Simbolo già presente nel portafoglio: posizione esclusa come sul sito." else null,
                        description = description,
                        rememberMapping = format == ImportFormat.REVOLUT)
                }
                mutable.update { it.copy(context = context, preview = preview, problems = parsed.problems, skipped = parsed.skipped,
                    message = "Anteprima pronta. Nessun dato è stato scritto.", format = format,
                    newCategories = listOfNotNull(interestDraft), mutations = emptyList(),
                    isinDrafts = parsed.transactions.mapNotNull { row -> row.isin?.let { isin ->
                        isin to (context.isinMappings.firstOrNull { it.isin == isin }
                            ?: TransferIsinMapping(isin = isin, symbol = "", name = row.investmentName.orEmpty()))
                    } }.toMap()) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(messageNotice = null, message = safeMessage(error)) } }
            finally { mutable.update { it.copy(progressNotice = null, busy = false, progress = "") } }
        }
    }

    fun select(row: Int, selected: Boolean) = edit(row) { it.copy(selected = selected) }
    fun category(row: Int, categoryId: String) = edit(row) { it.copy(categoryId = categoryId) }
    fun rate(row: Int, rate: String) = edit(row) { it.copy(rate = rate) }
    fun description(row: Int, text: String) = edit(row) { it.copy(description = text) }
    fun note(row: Int, text: String) = edit(row) { it.copy(note = text) }
    fun rememberMapping(row: Int, remember: Boolean) = edit(row) { it.copy(rememberMapping = remember) }
    fun isinDraft(mapping: TransferIsinMapping) {
        if (!mutable.value.busy) mutable.update { it.copy(isinDrafts = it.isinDrafts + (mapping.isin to mapping)) }
    }
    fun newCategory(row: Int, name: String, icon: String, color: String): String? {
        val state = mutable.value
        if (state.busy) return "Attendi la fine dell'operazione."
        val target = state.preview.firstOrNull { it.transaction.row == row } ?: return "Riga non disponibile. Riapri il file."
        try {
            val context = state.context ?: throw TransferException("Riapri il file.")
            val draft = TransferCategoryDraft(name = name.trim(), type = target.transaction.type, icon = icon, color = color)
            draft.body(context.userId)
            requireTransfer(context.categories.none { it.type == draft.type && it.name.equals(draft.name, true) } &&
                state.newCategories.none { it.type == draft.type && it.name.equals(draft.name, true) }, "Categoria già presente: selezionala dall'elenco.")
            mutable.update { it.copy(newCategories = it.newCategories + draft,
                preview = it.preview.map { item -> if (item.transaction.row == row) item.copy(categoryId = draft.id) else item }, message = "Categoria preparata. Verrà creata soltanto con la conferma importazione.") }
            return null
        } catch (error: Exception) {
            val message = safeMessage(error)
            mutable.update { it.copy(message = message, messageNotice = null) }
            return message
        }
    }
    fun selectAll(selected: Boolean) {
        if (!mutable.value.busy) mutable.update { state -> state.copy(preview = state.preview.map {
            if (it.status in setOf(ImportStatus.READY, ImportStatus.FAILED)) it.copy(selected = selected) else it
        }) }
    }
    private fun edit(row: Int, change: (PreviewTransaction) -> PreviewTransaction) {
        if (!mutable.value.busy) mutable.update { state -> state.copy(preview = state.preview.map {
            if (it.transaction.row == row && it.status in setOf(ImportStatus.READY, ImportStatus.FAILED)) change(it) else it
        }) }
    }

    /** Called only by the UI's explicit confirmation button. No automatic retry of a write. */
    fun confirmImport() {
        val initial = mutable.value
        if (initial.busy) return
        val context = initial.context ?: return
        if (initial.mutations.any { it.status in setOf(TransferMutationStatus.UNCERTAIN, TransferMutationStatus.BLOCKED) }) {
            mutable.update { it.copy(message = "Verifica gli esiti incerti prima di continuare l'importazione.", messageNotice = null) }
            return
        }
        val selected = initial.preview.filter { it.selected && it.status in setOf(ImportStatus.READY, ImportStatus.FAILED) }
        if (selected.isEmpty()) { mutable.update { it.copy(messageNotice = null, message = "Seleziona almeno una transazione.") }; return }
        try {
            selected.forEach { validate(it, context) }
            val mappingGroups = selected.filter { it.rememberMapping && it.transaction.type != "investment" }.groupBy {
                if (initial.format == ImportFormat.REVOLUT) it.transaction.description.lowercase() else it.description.trim().lowercase()
            }
            requireTransfer(mappingGroups.values.none { group -> group.map { it.categoryId }.distinct().size > 1 },
                "Una keyword è associata a categorie diverse nell'anteprima. Correggila prima di confermare.")
        } catch (error: Exception) {
            mutable.update { it.copy(messageNotice = (error as? TransferException)?.notice, message = safeMessage(error)) }
            return
        }
        mutable.update { it.copy(progressNotice = null, messageNotice = null, busy = true, message = null, progress = "Ricontrollo i duplicati…") }
        viewModelScope.launch {
            try {
                val fresh = repository.importContext(context.includesInvestments, context.userId, context.ownerRevision)
                if (fresh.userId != context.userId) throw TransferException("Account cambiato. Riapri il file con l'account corretto.")
                val seen = fresh.existingKeys.toMutableSet()
                for ((index, row) in selected.withIndex()) {
                    mutable.update { it.copy(progress = "Importazione ${index + 1} / ${selected.size}",
                        progressNotice = TransferNotice("Importazione {{current}} / {{total}}", listOf("current" to index + 1, "total" to selected.size))) }
                    val effective = row.transaction.copy(description = TransferRules.description(row.description, row.note))
                    if (row.transaction.type != "investment" && !seen.add(effective.duplicateKey()) &&
                        mutable.value.mutations.none { it.key == "row:${row.insertId}" }) {
                        result(row.transaction.row, ImportStatus.DUPLICATE, "Già presente; saltata.")
                        continue
                    }
                    if (!currentOwner(context)) throw TransferException("Account cambiato durante l'import. Le righe già importate restano visibili.")
                    try {
                        validate(row, fresh)
                        if (row.transaction.type == "investment") {
                            val mapping = mutable.value.isinDrafts.getValue(row.transaction.isin!!)
                            saveMappingPlan(mapping, fresh)
                            if (fresh.assets.any { it.optString("symbol").equals(mapping.symbol.trim(), true) } &&
                                mutable.value.mutations.none { it.key == "row:${row.insertId}" }) {
                                result(row.transaction.row, ImportStatus.DUPLICATE, "Simbolo già presente nel portafoglio: posizione esclusa come sul sito.")
                                continue
                            }
                            write("row:${row.insertId}", "portfolio_assets", row.insertId,
                                TransferRules.assetBody(row.transaction, mapping, context.userId, row.insertId))
                        } else {
                            mutable.value.newCategories.firstOrNull { it.id == row.categoryId }?.let { draft ->
                                val concurrent = fresh.categories.firstOrNull { it.type == draft.type && it.name.equals(draft.name, true) }
                                requireTransfer(concurrent == null || concurrent.id == draft.id, "Una categoria con questo nome è stata creata su un altro dispositivo. Riapri il file.")
                                write("category:${draft.id}", "categories", draft.id, draft.body(context.userId))
                            }
                            if (row.rememberMapping) saveCategoryMapping(row, fresh)
                            val body = effective.body(context.userId, row.categoryId!!, TransferCsv.decimal(row.rate)).put("id", row.insertId)
                            write("row:${row.insertId}", "transactions", row.insertId, body)
                        }
                        result(row.transaction.row, ImportStatus.IMPORTED, "Importata.")
                    } catch (error: CancellationException) {
                        result(row.transaction.row, ImportStatus.UNCERTAIN, "Operazione interrotta: verifica i duplicati prima di riprovare.")
                        throw error
                    } catch (error: Exception) {
                        val uncertain = isUncertain(error) || mutable.value.mutations.any { it.status in setOf(TransferMutationStatus.UNCERTAIN, TransferMutationStatus.BLOCKED) }
                        result(row.transaction.row, if (uncertain) ImportStatus.UNCERTAIN else ImportStatus.FAILED,
                            if (uncertain) "Esito non verificato. Rileggi i dati prima di riprovare." else safeMessage(error),
                            if (uncertain) null else (error as? TransferException)?.notice)
                        if (uncertain) break
                    }
                }
                val current = mutable.value.preview
                mutable.update { it.copy(message = "${current.count { it.status == ImportStatus.IMPORTED }} importate, " +
                    "${current.count { it.status == ImportStatus.DUPLICATE }} duplicate, " +
                    "${current.count { it.status == ImportStatus.FAILED }} fallite, " +
                    "${current.count { it.status == ImportStatus.UNCERTAIN }} con esito da verificare.",
                    messageNotice = TransferNotice("{{imported}} importate, {{duplicates}} duplicate, {{failed}} fallite, {{uncertain}} con esito da verificare.",
                        listOf("imported" to current.count { it.status == ImportStatus.IMPORTED }, "duplicates" to current.count { it.status == ImportStatus.DUPLICATE },
                            "failed" to current.count { it.status == ImportStatus.FAILED }, "uncertain" to current.count { it.status == ImportStatus.UNCERTAIN }))) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(messageNotice = null, message = safeMessage(error)) } }
            finally { mutable.update { it.copy(progressNotice = null, busy = false, progress = "") } }
        }
    }

    fun recheckDuplicates() {
        if (mutable.value.busy) return
        val old = mutable.value.context ?: return
        mutable.update { it.copy(progressNotice = null, messageNotice = null, busy = true, progress = "Verifico le righe con esito incerto…", message = null) }
        viewModelScope.launch {
            try {
                // Reconcile exact UUID + requested payload, never infer success from a similar transaction.
                for (plan in mutable.value.mutations.filter { it.status in setOf(TransferMutationStatus.UNCERTAIN, TransferMutationStatus.BLOCKED) }) {
                    val status = repository.reconcile(plan)
                    mutationStatus(plan.key, status)
                }
                val fresh = if (mutable.value.preview.isEmpty()) repository.mappingContext(old)
                    else repository.importContext(old.includesInvestments, old.userId, old.ownerRevision)
                if (fresh.userId != old.userId) throw TransferException("Account cambiato. Riapri il file.")
                mutable.update { state -> state.copy(context = fresh, preview = state.preview.map { row ->
                    val ownPlan = state.mutations.firstOrNull { it.key == "row:${row.insertId}" }
                    if (row.status != ImportStatus.UNCERTAIN) row else when {
                        ownPlan?.status == TransferMutationStatus.CONFIRMED -> row.copy(status = ImportStatus.IMPORTED, message = "Presente nel backend dopo verifica.", selected = false)
                        state.mutations.any { it.status in setOf(TransferMutationStatus.UNCERTAIN, TransferMutationStatus.BLOCKED) } -> row
                        else -> row.copy(status = ImportStatus.FAILED, message = "Scrittura assente dopo verifica. Seleziona la riga e conferma per ritentare con lo stesso UUID.", selected = false)
                    }
                }, message = "Dati riletti. Nessun retry automatico. Payload diversi o esiti ancora incerti restano bloccati.") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(messageNotice = null, message = safeMessage(error)) } }
            finally { mutable.update { it.copy(progressNotice = null, busy = false, progress = "") } }
        }
    }

    fun loadMappings() = mappingOperation { repository.mappingContext(mutable.value.context) }

    /** Management is independently confirmed by its Save/Delete dialog; preview drafts do not call this. */
    fun saveIsinMapping(mapping: TransferIsinMapping) = mappingOperation {
        val context = mutable.value.context ?: throw TransferException("Carica i mapping prima di modificarli.")
        mapping.body(context.userId)
        val fresh = repository.mappingContext(context)
        requireTransfer(fresh.userId == context.userId && fresh.isinMappings.any { it.id == mapping.id }, "Mapping non disponibile per questo account. Ricarica l'elenco.")
        unchangedMapping(mapping.id, context, fresh)
        saveMappingPlan(mapping, fresh, reuseConfirmed = false)
        repository.mappingContext(context)
    }

    fun deleteIsinMapping(id: String) = mappingOperation {
        val context = mutable.value.context ?: throw TransferException("Carica i mapping prima di modificarli.")
        val fresh = repository.mappingContext(context)
        requireTransfer(fresh.userId == context.userId, "Account cambiato. Ricarica l'elenco.")
        val mapping = fresh.isinMappings.singleOrNull { it.id == id } ?: throw TransferException("Mapping non disponibile per questo account.")
        unchangedMapping(id, context, fresh)
        write("delete-isin:$id:${UUID.randomUUID()}", "isin_mappings", id, mapping.body(context.userId), TransferMutationKind.DELETE, isinSnapshot(mapping, context.userId))
        repository.mappingContext(context)
    }

    private fun mappingOperation(block: suspend () -> ImportContext) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null, messageNotice = null, progress = "Carico o salvo i mapping dell'account…") }
        viewModelScope.launch {
            try {
                val context = block()
                requireTransfer(mutable.value.context?.userId?.let { it == context.userId } != false, "Account cambiato. Riapri la schermata.")
                mutable.update { it.copy(context = context, message = "Mapping aggiornati.") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(message = safeMessage(error)) } }
            finally { mutable.update { it.copy(busy = false, progress = "") } }
        }
    }

    /** Preserve raw saved text for compare-and-set predicates; normalization belongs only to the desired write. */
    private fun isinSnapshot(mapping: TransferIsinMapping, owner: String): JSONObject = JSONObject()
        .put("id", mapping.id).put("user_id", owner).put("isin", mapping.isin).put("symbol", mapping.symbol)
        .put("name", mapping.name).put("asset_type", mapping.assetType)

    private fun unchangedMapping(id: String, previous: ImportContext, current: ImportContext) {
        val old = previous.isinMappings.singleOrNull { it.id == id }
        val fresh = current.isinMappings.singleOrNull { it.id == id }
        requireTransfer(old != null && fresh != null && isinSnapshot(old, previous.userId).let {
            TransferRules.payloadMatches(isinSnapshot(fresh, current.userId), it)
        }, "Mapping ISIN cambiato su un altro dispositivo. Ricarica prima di salvare.")
    }

    private suspend fun saveMappingPlan(mapping: TransferIsinMapping, context: ImportContext, reuseConfirmed: Boolean = true) {
        val body = mapping.body(context.userId)
        val existing = context.isinMappings.firstOrNull { it.isin == mapping.isin }
        requireTransfer(existing == null || existing.id == mapping.id, "Mapping ISIN cambiato su un altro dispositivo. Ricarica prima di salvare.")
        if (existing != null && TransferRules.payloadMatches(isinSnapshot(existing, context.userId), body)) return
        if (existing != null) mutable.value.context?.let { unchangedMapping(existing.id, it, context) }
        val prefix = "isin:${mapping.id}:${body.getString("symbol")}:${body.getString("name")}:${mapping.assetType}"
        // A new management confirmation is a new operation, even when its value occurred in older history.
        // An unconfirmed attempt still retains its immutable payload, UUID and precondition for explicit retry.
        val key = if (reuseConfirmed) prefix else mutable.value.mutations.lastOrNull {
            it.table == "isin_mappings" && it.id == mapping.id && it.status != TransferMutationStatus.CONFIRMED &&
                TransferRules.payloadMatches(JSONObject(it.payload), body)
        }?.key ?: "$prefix:${UUID.randomUUID()}"
        write(key, "isin_mappings", mapping.id, body,
            if (existing == null) TransferMutationKind.INSERT else TransferMutationKind.UPDATE, existing?.let { isinSnapshot(it, context.userId) })
    }

    private suspend fun saveCategoryMapping(row: PreviewTransaction, context: ImportContext) {
        val source = if (mutable.value.format == ImportFormat.REVOLUT) "revolut" else "shared"
        val keyword = if (source == "revolut") row.transaction.description else row.description.trim().lowercase()
        requireTransfer(keyword.isNotBlank() && keyword.length <= 1000, "Keyword richiesta, massimo 1.000 caratteri.")
        val existing = context.categoryMappings.firstOrNull { it.source == source && it.description.equals(keyword, true) }
        if (existing?.categoryId == row.categoryId) return
        val key = "category-mapping:$source:${keyword.lowercase()}"
        val id = mutable.value.mutations.firstOrNull { it.key == key }?.id ?: existing?.id ?: UUID.randomUUID().toString()
        val body = JSONObject().put("id", id).put("user_id", context.userId).put("description", existing?.description ?: keyword)
            .put("category_id", row.categoryId).put("source", source)
        val before = existing?.let { JSONObject().put("id", it.id).put("user_id", context.userId).put("description", it.description)
            .put("category_id", it.categoryId ?: JSONObject.NULL).put("source", it.source) }
        write(key, "category_mappings", id, body, if (existing == null) TransferMutationKind.INSERT else TransferMutationKind.UPDATE, before)
    }

    private suspend fun write(key: String, table: String, id: String, body: JSONObject, kind: TransferMutationKind = TransferMutationKind.INSERT, expectedBefore: JSONObject? = null) {
        val context = mutable.value.context ?: throw TransferException("Riapri il file.")
        val owner = context.userId
        val revision = context.ownerRevision ?: throw TransferException("Sessione import non valida.")
        requireTransfer(currentOwner(context), "Account cambiato. Riapri il file.")
        requireTransfer(mutable.value.mutations.none { it.status in setOf(TransferMutationStatus.UNCERTAIN, TransferMutationStatus.BLOCKED) },
            "Verifica gli esiti incerti prima di eseguire altre scritture.")
        val existingPlan = mutable.value.mutations.firstOrNull { it.key == key }
        val plan = if (existingPlan != null) {
            val attempted = JSONObject(existingPlan.payload)
            requireTransfer(existingPlan.owner == owner && existingPlan.ownerRevision == revision && existingPlan.kind == kind && attempted.length() == body.length() && TransferRules.payloadMatches(body, attempted),
                "La scrittura precedente ha un payload diverso. Rileggi gli esiti prima di cambiare i dati.")
            if (existingPlan.status == TransferMutationStatus.CONFIRMED) return
            requireTransfer(existingPlan.status in setOf(TransferMutationStatus.READY, TransferMutationStatus.ABSENT), "Verifica prima l'esito della scrittura precedente.")
            existingPlan
        } else {
            TransferMutation(key, table, id, owner, body.toString(), revision, kind, expectedBefore = expectedBefore?.toString()).also { newPlan ->
                mutable.update { it.copy(mutations = it.mutations + newPlan) }
            }
        }
        try {
            repository.applyMutation(plan)
            mutationStatus(key, TransferMutationStatus.CONFIRMED)
        } catch (error: CancellationException) {
            mutationStatus(key, TransferMutationStatus.UNCERTAIN)
            throw error
        } catch (error: Exception) {
            // A local/postflight permission failure can follow a committed HTTP 2xx.
            val knownRejection = error is ApiException && error.kind in setOf(ApiError.VALIDATION, ApiError.PERMISSION) &&
                error.status != null && error.status in 400..499
            mutationStatus(key, if (knownRejection) TransferMutationStatus.ABSENT else TransferMutationStatus.UNCERTAIN)
            throw error
        }
    }

    private suspend fun currentOwner(context: ImportContext): Boolean {
        val snapshot = api.session() ?: return false
        return snapshot.userId == context.userId && context.ownerRevision != null && api.sessionOwnerRevision(snapshot) == context.ownerRevision
    }

    private fun mutationStatus(key: String, status: TransferMutationStatus) = mutable.update { state ->
        state.copy(mutations = state.mutations.map { if (it.key == key) it.copy(status = status) else it })
    }

    private fun isUncertain(error: Exception): Boolean = error is ApiException &&
        error.kind in setOf(ApiError.NETWORK, ApiError.RESPONSE, ApiError.SERVER)

    fun prepareExport() {
        if (mutable.value.busy) return
        mutable.update { it.copy(progressNotice = null, messageNotice = null, busy = true, progress = "Raccolgo tutte le tabelle dell'account…", message = null, export = null) }
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { repository.export() }
                mutable.update { it.copy(export = data, message = "${data.counts.values.sum()} righe in ${data.counts.size} tabelle. Scegli dove salvare il CSV.",
                    messageNotice = TransferNotice("{{rows}} righe in {{tables}} tabelle. Scegli dove salvare il CSV.", listOf("rows" to data.counts.values.sum(), "tables" to data.counts.size))) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(messageNotice = null, message = safeMessage(error)) } }
            finally { mutable.update { it.copy(progressNotice = null, busy = false, progress = "") } }
        }
    }

    fun exportSaved(message: String) { mutable.update { it.copy(messageNotice = null, message = message) } }
    fun saveExport(write: suspend (String) -> Unit) {
        val initial = mutable.value
        if (initial.busy) return
        val export = initial.export ?: return
        mutable.update { it.copy(progressNotice = null, messageNotice = null, busy = true, progress = "Salvo il documento CSV…", message = null) }
        viewModelScope.launch {
            try {
                if (api.session()?.userId != export.userId) throw TransferException("Account cambiato. Prepara di nuovo l'export.")
                write(export.csv)
                mutable.update { it.copy(message = "CSV salvato: ${export.counts.values.sum()} righe da ${export.counts.size} tabelle.",
                    messageNotice = TransferNotice("CSV salvato: {{rows}} righe da {{tables}} tabelle.", listOf("rows" to export.counts.values.sum(), "tables" to export.counts.size))) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(messageNotice = null, message = safeMessage(error)) } }
            finally { mutable.update { it.copy(progressNotice = null, busy = false, progress = "") } }
        }
    }
    private fun result(row: Int, status: ImportStatus, message: String, notice: TransferNotice? = null) = mutable.update { state ->
        state.copy(preview = state.preview.map { if (it.transaction.row == row) it.copy(status = status, message = message, messageNotice = notice, selected = false) else it })
    }
    private fun validate(row: PreviewTransaction, context: ImportContext) {
        if (row.transaction.type == "investment") {
            val mapping = row.transaction.isin?.let { mutable.value.isinDrafts[it] } ?: throw TransferException("Associa l'ISIN prima di importare.")
            mapping.body(context.userId)
            return
        }
        TransferRules.description(row.description, row.note)
        if (context.categories.none { it.id == row.categoryId && it.type == row.transaction.type } &&
            mutable.value.newCategories.none { it.id == row.categoryId && it.type == row.transaction.type }) {
            val type = when (row.transaction.type) { "expense" -> "Uscita"; "income" -> "Entrata"; else -> row.transaction.type }
            throw TransferException("Riga ${row.transaction.row}: scegli una categoria ${row.transaction.type} valida.",
                TransferNotice("Riga {{row}}: scegli una categoria valida ({{type}}).",
                    listOf("row" to row.transaction.row, "type" to type),
                    if (row.transaction.type in setOf("expense", "income")) setOf("type") else emptySet()))
        }
        val rate = TransferCsv.decimal(row.rate)
        if (rate.signum() <= 0 || rate.precision() > 18 || rate.scale() > 8) throw TransferException("Riga ${row.transaction.row}: cambio EUR non valido.",
            TransferNotice("Riga {{row}}: cambio EUR non valido.", listOf("row" to row.transaction.row)))
        if (row.transaction.currency == "EUR" && rate.compareTo(java.math.BigDecimal.ONE) != 0) throw TransferException("Riga ${row.transaction.row}: il cambio EUR deve essere 1.",
            TransferNotice("Riga {{row}}: il cambio EUR deve essere 1.", listOf("row" to row.transaction.row)))
    }
    companion object {
        fun safeMessage(error: Exception): String = when (error) {
            is ApiException, is TransferException -> error.message ?: "Operazione non riuscita."
            else -> "Impossibile leggere o salvare il file. Controlla il formato e i permessi."
        }
    }
}
