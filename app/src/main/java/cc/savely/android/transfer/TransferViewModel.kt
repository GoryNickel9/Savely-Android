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

enum class ImportStatus { READY, DUPLICATE, IMPORTED, FAILED, UNCERTAIN }
data class PreviewTransaction(
    val transaction: ImportTransaction,
    val selected: Boolean,
    val categoryId: String?,
    val rate: String,
    val status: ImportStatus,
    val message: String? = null,
    val insertId: String = UUID.randomUUID().toString(),
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
)

class TransferViewModel(private val api: SupabaseApi) : ViewModel() {
    private val repository = TransferRepository(api)
    private val mutable = MutableStateFlow(TransferState())
    val state = mutable.asStateFlow()

    fun prepare(read: suspend () -> String, format: ImportFormat, currency: String) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null, progress = "Leggo e controllo il file…") }
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) { TransferCsv.parse(read(), format, currency) }
                val context = repository.importContext()
                val duplicateRows = TransferRepository.markDuplicates(parsed.transactions, context.existingKeys)
                val preview = parsed.transactions.map { transaction ->
                    val duplicate = transaction.row in duplicateRows
                    val validCategory = (transaction.categoryId ?: context.mappings[transaction.description.lowercase()])?.takeIf { id ->
                        context.categories.any { it.id == id && it.type == transaction.type }
                    }
                    PreviewTransaction(transaction, !duplicate, validCategory, transaction.exchangeRateEur?.toPlainString().orEmpty(),
                        if (duplicate) ImportStatus.DUPLICATE else ImportStatus.READY)
                }
                mutable.update { it.copy(context = context, preview = preview, problems = parsed.problems, skipped = parsed.skipped,
                    message = "Anteprima pronta. Nessun dato è stato scritto.") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(message = safeMessage(error)) } }
            finally { mutable.update { it.copy(busy = false, progress = "") } }
        }
    }

    fun select(row: Int, selected: Boolean) = edit(row) { it.copy(selected = selected) }
    fun category(row: Int, categoryId: String) = edit(row) { it.copy(categoryId = categoryId) }
    fun rate(row: Int, rate: String) = edit(row) { it.copy(rate = rate) }
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
        val selected = initial.preview.filter { it.selected && it.status in setOf(ImportStatus.READY, ImportStatus.FAILED) }
        if (selected.isEmpty()) { mutable.update { it.copy(message = "Seleziona almeno una transazione.") }; return }
        try {
            selected.forEach { validate(it, context) }
        } catch (error: Exception) { mutable.update { it.copy(message = safeMessage(error)) }; return }
        mutable.update { it.copy(busy = true, message = null, progress = "Ricontrollo i duplicati…") }
        viewModelScope.launch {
            try {
                val fresh = repository.importContext()
                if (fresh.userId != context.userId) throw TransferException("Account cambiato. Riapri il file con l'account corretto.")
                val seen = fresh.existingKeys.toMutableSet()
                for ((index, row) in selected.withIndex()) {
                    mutable.update { it.copy(progress = "Importazione ${index + 1} / ${selected.size}") }
                    if (!seen.add(row.transaction.duplicateKey())) {
                        result(row.transaction.row, ImportStatus.DUPLICATE, "Già presente; saltata.")
                        continue
                    }
                    if (api.session()?.userId != context.userId) throw TransferException("Account cambiato durante l'import. Le righe già importate restano visibili.")
                    try {
                        validate(row, fresh)
                        val body = row.transaction.body(context.userId, row.categoryId!!, TransferCsv.decimal(row.rate)).put("id", row.insertId)
                        api.insert("transactions", body)
                        result(row.transaction.row, ImportStatus.IMPORTED, "Importata.")
                    } catch (error: CancellationException) {
                        result(row.transaction.row, ImportStatus.UNCERTAIN, "Operazione interrotta: verifica i duplicati prima di riprovare.")
                        throw error
                    } catch (error: Exception) {
                        val uncertain = error is ApiException && error.kind in setOf(ApiError.NETWORK, ApiError.RESPONSE, ApiError.SERVER)
                        result(row.transaction.row, if (uncertain) ImportStatus.UNCERTAIN else ImportStatus.FAILED,
                            if (uncertain) "Esito non verificato. Rileggi i dati prima di riprovare." else safeMessage(error))
                        if (uncertain) break
                    }
                }
                val current = mutable.value.preview
                mutable.update { it.copy(message = "${current.count { it.status == ImportStatus.IMPORTED }} importate, " +
                    "${current.count { it.status == ImportStatus.DUPLICATE }} duplicate, " +
                    "${current.count { it.status == ImportStatus.FAILED }} fallite, " +
                    "${current.count { it.status == ImportStatus.UNCERTAIN }} con esito da verificare.") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(message = safeMessage(error)) } }
            finally { mutable.update { it.copy(busy = false, progress = "") } }
        }
    }

    fun recheckDuplicates() {
        if (mutable.value.busy) return
        val old = mutable.value.context ?: return
        mutable.update { it.copy(busy = true, progress = "Verifico le righe con esito incerto…", message = null) }
        viewModelScope.launch {
            try {
                val fresh = repository.importContext()
                if (fresh.userId != old.userId) throw TransferException("Account cambiato. Riapri il file.")
                mutable.update { state -> state.copy(context = fresh, preview = state.preview.map { row ->
                    if (row.status == ImportStatus.UNCERTAIN && row.transaction.duplicateKey() in fresh.existingKeys) {
                        row.copy(status = ImportStatus.IMPORTED, message = "Presente nel backend dopo verifica.", selected = false)
                    } else row
                }, message = "Dati riletti. Se una riga resta incerta, non viene ritentata: verifica prima dal conto.") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(message = safeMessage(error)) } }
            finally { mutable.update { it.copy(busy = false, progress = "") } }
        }
    }

    fun prepareExport() {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, progress = "Raccolgo tutte le tabelle dell'account…", message = null, export = null) }
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { repository.export() }
                mutable.update { it.copy(export = data, message = "${data.counts.values.sum()} righe in ${data.counts.size} tabelle. Scegli dove salvare il CSV.") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(message = safeMessage(error)) } }
            finally { mutable.update { it.copy(busy = false, progress = "") } }
        }
    }

    fun exportSaved(message: String) { mutable.update { it.copy(message = message) } }
    fun saveExport(write: suspend (String) -> Unit) {
        val initial = mutable.value
        if (initial.busy) return
        val export = initial.export ?: return
        mutable.update { it.copy(busy = true, progress = "Salvo il documento CSV…", message = null) }
        viewModelScope.launch {
            try {
                if (api.session()?.userId != export.userId) throw TransferException("Account cambiato. Prepara di nuovo l'export.")
                write(export.csv)
                mutable.update { it.copy(message = "CSV salvato: ${export.counts.values.sum()} righe da ${export.counts.size} tabelle.") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(message = safeMessage(error)) } }
            finally { mutable.update { it.copy(busy = false, progress = "") } }
        }
    }
    private fun result(row: Int, status: ImportStatus, message: String) = mutable.update { state ->
        state.copy(preview = state.preview.map { if (it.transaction.row == row) it.copy(status = status, message = message, selected = false) else it })
    }
    private fun validate(row: PreviewTransaction, context: ImportContext) {
        if (context.categories.none { it.id == row.categoryId && it.type == row.transaction.type }) throw TransferException("Riga ${row.transaction.row}: scegli una categoria ${row.transaction.type} valida.")
        val rate = TransferCsv.decimal(row.rate)
        if (rate.signum() <= 0 || rate.precision() > 18 || rate.scale() > 8) throw TransferException("Riga ${row.transaction.row}: cambio EUR non valido.")
        if (row.transaction.currency == "EUR" && rate.compareTo(java.math.BigDecimal.ONE) != 0) throw TransferException("Riga ${row.transaction.row}: il cambio EUR deve essere 1.")
    }
    companion object {
        fun safeMessage(error: Exception): String = when (error) {
            is ApiException, is TransferException -> error.message ?: "Operazione non riuscita."
            else -> "Impossibile leggere o salvare il file. Controlla il formato e i permessi."
        }
    }
}
