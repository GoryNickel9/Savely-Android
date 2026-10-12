package cc.savely.android.transfer

import cc.savely.android.ui.localized
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.savely.android.data.SupabaseApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate

/** Native document picker, bounded preview and explicit confirmation before importing. */
@Composable
fun TransferScreen(api: SupabaseApi, modifier: Modifier = Modifier) {
    var owner by remember(api) { mutableStateOf<String?>(null) }
    var ownerError by remember(api) { mutableStateOf<String?>(null) }
    LaunchedEffect(api) {
        try { owner = api.session()?.userId ?: throw TransferException("Accedi per trasferire i dati.") }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { ownerError = TransferViewModel.safeMessage(error) }
    }
    if (owner == null) {
        Column(modifier.padding(20.dp)) {
            if (ownerError == null) CircularProgressIndicator() else Text(localized(ownerError!!))
        }
        return
    }
    val model: TransferViewModel = viewModel(key = "transfer-${System.identityHashCode(api)}-$owner", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = TransferViewModel(api) as T
    })
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    var formatName by rememberSaveable(owner) { mutableStateOf(ImportFormat.REVOLUT.name) }
    val format = ImportFormat.valueOf(formatName)
    var bankCurrency by rememberSaveable(owner) { mutableStateOf("EUR") }
    var confirm by remember { mutableStateOf(false) }
    var categoryRow by remember { mutableStateOf<PreviewTransaction?>(null) }
    var editingMapping by remember { mutableStateOf<TransferIsinMapping?>(null) }
    var mappingSaveRequested by remember { mutableStateOf(false) }
    var deletingMapping by remember { mutableStateOf<TransferIsinMapping?>(null) }
    var showMappings by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.prepare({ readCsv(context, uri) }, format, bankCurrency)
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) model.saveExport { csv ->
            withContext(Dispatchers.IO) {
                try {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw TransferException("Impossibile aprire il documento.")
                    output.use { it.write(csv.toByteArray(Charsets.UTF_8)); it.flush() }
                } catch (error: Exception) {
                    // Delete only the newly created output document when writing did not finish.
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    throw error
                }
            }
        }
    }
    val selected = state.preview.count { it.selected && it.status in setOf(ImportStatus.READY, ImportStatus.FAILED) }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(localized("Importa ed esporta"), style = MaterialTheme.typography.headlineSmall)
            Text(localized("Anteprima e controllo duplicati prima di salvare. CSV fino a 5 MiB e 10.000 righe."), style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Text(localized("Formato importazione"), style = MaterialTheme.typography.titleMedium)
            ImportFormat.entries.forEach { choice ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = choice == format, onClick = { formatName = choice.name }, enabled = !state.busy)
                    TextButton(onClick = { formatName = choice.name }, enabled = !state.busy) { Text(localized(choice.label)) }
                }
            }
            if (format == ImportFormat.BANK) CategoryChoice("Valuta del conto se assente nel file", bankCurrency,
                TransferCsv.currencies.sorted().map { it to it }, !state.busy) { bankCurrency = it }
            OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel")) }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()) { Text(localized("Scegli CSV e prepara anteprima")) }
            TextButton(onClick = { uriHandler.openUri("https://bank.savely.cc") }) { Text(localized("Converti l'estratto conto con Bank2CSV")) }
            Text(localized("Il convertitore si apre nel browser esterno. Questa app importa CSV, come il sito Savely."), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { showMappings = true; model.loadMappings() }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()) { Text(localized("Gestisci Mapping ISIN")) }
        }
        item {
            HorizontalDivider()
            Text(localized("Export completo dell'account"), style = MaterialTheme.typography.titleMedium)
            Text(localized("Tutte le 25 tabelle previste dal sito, con filtri sull'account e lettura di tutte le pagine. Se una tabella fallisce, l'export si interrompe."), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = model::prepareExport, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(localized("Prepara export CSV")) }
            if (state.export != null) Button(onClick = { saver.launch("savely_export_${LocalDate.now()}.csv") }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()) { Text(localized("Salva CSV ({{value0}} righe)", "value0" to (state.export!!.counts.values.sum()))) }
        }
        if (state.busy) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.progressNotice?.let { localized(it.template, *it.values.toTypedArray()) } ?: localized(state.progress), style = MaterialTheme.typography.bodyMedium)
        }
        state.message?.let { message -> item { Text(state.messageNotice?.let { renderTransferNotice(it) } ?: localized(message), style = MaterialTheme.typography.bodyMedium) } }
        if (state.preview.isEmpty() && state.mutations.any { it.status in setOf(TransferMutationStatus.UNCERTAIN, TransferMutationStatus.BLOCKED) }) item {
            OutlinedButton(onClick = model::recheckDuplicates, enabled = !state.busy) { Text(localized("Verifica gli esiti incerti")) }
        }
        if (state.preview.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text(localized(if (state.preview.any { it.transaction.type == "investment" }) "{{value0}} righe in anteprima" else "{{value0}} transazioni in anteprima", "value0" to (state.preview.size)), style = MaterialTheme.typography.titleLarge)
                Text(localized("{{value0}} duplicate; {{value1}} righe escluse dal formato; {{value2}} righe non valide.", "value0" to (state.preview.count { it.status == ImportStatus.DUPLICATE }), "value1" to (state.skipped), "value2" to (state.problems.size)))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { model.selectAll(true) }, enabled = !state.busy) { Text(localized("Seleziona tutte")) }
                    TextButton(onClick = { model.selectAll(false) }, enabled = !state.busy) { Text(localized("Deseleziona")) }
                }
                Button(onClick = { confirm = true }, enabled = selected > 0 && !state.busy, modifier = Modifier.fillMaxWidth()) { Text(localized(if (state.preview.any { it.selected && it.transaction.type == "investment" }) "Importa {{value0}} righe selezionate" else "Importa {{value0}} transazioni selezionate", "value0" to (selected))) }
                if (state.preview.any { it.status == ImportStatus.UNCERTAIN }) OutlinedButton(onClick = model::recheckDuplicates,
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(localized("Verifica gli esiti incerti")) }
            }
            items(state.preview, key = { "transaction-${it.transaction.row}" }) { row ->
                val editable = !state.busy && row.status in setOf(ImportStatus.READY, ImportStatus.FAILED)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(row.selected, onCheckedChange = { model.select(row.transaction.row, it) }, enabled = editable)
                            Column(Modifier.weight(1f)) {
                                Text(row.transaction.description, style = MaterialTheme.typography.titleMedium)
                                Text("${row.transaction.date} · ${row.transaction.amount.toPlainString()} ${row.transaction.currency} · ${localized(when (row.transaction.type) { "expense" -> "Uscita"; "investment" -> "Investimento"; else -> "Entrata" })}")
                            }
                        }
                        Text(localized("Riga {{value0}}: {{value1}}", "value0" to (row.transaction.row), "value1" to localized(statusLabel(row.status))), style = MaterialTheme.typography.labelLarge)
                        row.message?.let { Text(row.messageNotice?.let { renderTransferNotice(it) } ?: localized(it), color = if (row.status == ImportStatus.FAILED || row.status == ImportStatus.UNCERTAIN) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                        if (row.status in setOf(ImportStatus.READY, ImportStatus.FAILED) && row.transaction.type == "investment") {
                            val draft = row.transaction.isin?.let { state.isinDrafts[it] }
                            Text(localized("ISIN {{isin}} · quantità 1 · prezzo di acquisto {{amount}} {{currency}}", "isin" to row.transaction.isin,
                                "amount" to row.transaction.amount.toPlainString(), "currency" to row.transaction.currency))
                            if (draft != null) {
                                IsinFields(draft, editable, model::isinDraft)
                                CategoryChoice("Associa asset esistente", "", state.context?.assets.orEmpty().filter { !it.isNull("symbol") && it.optString("symbol").isNotBlank() }
                                    .map { it.getString("id") to "${it.optString("symbol")} · ${it.optString("name")}" }, editable) { id ->
                                    state.context?.assets?.firstOrNull { it.getString("id") == id }?.let { asset ->
                                        model.isinDraft(draft.copy(symbol = asset.getString("symbol"), name = asset.getString("name"), assetType = asset.getString("type")))
                                    }
                                }
                            }
                        } else if (row.status in setOf(ImportStatus.READY, ImportStatus.FAILED)) {
                            val choices = state.context?.categories.orEmpty().filter { it.type == row.transaction.type }.map { it.id to it.name } +
                                state.newCategories.filter { it.type == row.transaction.type }.map { it.id to it.name }
                            CategoryChoice("Categoria", row.categoryId.orEmpty(), choices,
                                editable) { model.category(row.transaction.row, it) }
                            TextButton(onClick = { categoryRow = row }, enabled = editable) { Text(localized("Nuova categoria")) }
                            if (state.format == ImportFormat.BANK) {
                                OutlinedTextField(row.description, { model.description(row.transaction.row, it) }, label = { Text(localized("Descrizione / keyword")) },
                                    enabled = editable, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(row.note, { model.note(row.transaction.row, it) }, label = { Text(localized("Nota importazione")) },
                                    enabled = editable, modifier = Modifier.fillMaxWidth())
                            }
                            if (state.format != ImportFormat.NORMALIZED) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(row.rememberMapping, { model.rememberMapping(row.transaction.row, it) }, enabled = editable,
                                    modifier = Modifier.testTag("import-remember-category-${row.transaction.row}"))
                                Text(localized("Ricorda questa categoria per le importazioni future"), modifier = Modifier.weight(1f))
                            }
                            if (row.transaction.currency != "EUR") OutlinedTextField(value = row.rate,
                                onValueChange = { model.rate(row.transaction.row, it) }, label = { Text(localized("1 {{value0}} in EUR", "value0" to (row.transaction.currency))) },
                                supportingText = { Text(localized("Inserisci il cambio della data della transazione. Non converto l'importo originale.")) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        if (state.problems.isNotEmpty()) {
            item { Text(localized("Righe non importabili"), style = MaterialTheme.typography.titleMedium) }
            items(state.problems, key = { "problem-${it.row}" }) { Text(localized("Riga {{value0}}: {{value1}}", "value0" to (it.row), "value1" to localized(it.message)), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(localized("Conferma importazione")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(localized(if (state.preview.any { it.selected && it.transaction.type == "investment" })
                "Salverò {{value0}} righe selezionate nel tuo account: transazioni e posizioni ISIN. Le righe duplicate o non selezionate vengono escluse."
            else "Salverò {{value0}} transazioni nel tuo account Savely. Saranno visibili anche sul sito. Le righe duplicate o non selezionate verranno saltate.", "value0" to (selected)))
            if (state.format == ImportFormat.BANK || state.preview.any { it.rememberMapping }) Text(localized("La conferma salva anche le nuove categorie e i mapping selezionati. Le posizioni ISIN hanno quantità 1; un simbolo già presente viene escluso, come sul sito."))
        } },
        confirmButton = { TextButton(onClick = { confirm = false; model.confirmImport() }) { Text(localized("Conferma e importa")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(localized("Annulla")) } })
    categoryRow?.let { row ->
        NewTransferCategoryDialog(onDismiss = { categoryRow = null }) { name, icon, color ->
            model.newCategory(row.transaction.row, name, icon, color).also { if (it == null) categoryRow = null }
        }
    }
    if (showMappings) AlertDialog(onDismissRequest = { if (!state.busy) showMappings = false }, title = { Text(localized("Gestione Mapping ISIN")) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(localized(it)) }
            if (state.context?.isinMappings.isNullOrEmpty()) Text(localized("Nessun mapping ISIN salvato."))
            state.context?.isinMappings.orEmpty().forEach { mapping ->
                Text("${mapping.isin} · ${mapping.symbol} · ${mapping.name}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { mappingSaveRequested = false; editingMapping = mapping }, enabled = !state.busy) { Text(localized("Modifica")) }
                    TextButton(onClick = { deletingMapping = mapping }, enabled = !state.busy) { Text(localized("Elimina")) }
                }
            }
            if (state.mutations.any { it.status in setOf(TransferMutationStatus.UNCERTAIN, TransferMutationStatus.BLOCKED) }) OutlinedButton(onClick = model::recheckDuplicates, enabled = !state.busy) { Text(localized("Verifica gli esiti incerti")) }
        } }, confirmButton = { TextButton(onClick = { showMappings = false }, enabled = !state.busy) { Text(localized("Chiudi")) } })
    editingMapping?.let { initial ->
        EditTransferIsinDialog(initial, state.busy, state.message?.takeIf { it != "Mapping aggiornati." }, onDismiss = { mappingSaveRequested = false; editingMapping = null }) { mapping ->
            mappingSaveRequested = true
            editingMapping = mapping
            model.saveIsinMapping(mapping)
        }
    }
    LaunchedEffect(state.busy, state.context?.isinMappings, editingMapping, state.message, mappingSaveRequested) {
        val edited = editingMapping
        if (mappingSaveRequested && !state.busy && state.message == "Mapping aggiornati." && edited != null && state.context?.isinMappings.orEmpty().any {
            it.id == edited.id && it.symbol == edited.symbol.trim().uppercase() && it.name == edited.name.trim() && it.assetType == edited.assetType
        }) { mappingSaveRequested = false; editingMapping = null }
    }
    deletingMapping?.let { mapping -> AlertDialog(onDismissRequest = { deletingMapping = null },
        title = { Text(localized("Eliminare questo mapping?")) }, text = { Text("${mapping.isin} · ${mapping.symbol} · ${mapping.name}") },
        confirmButton = { TextButton(onClick = { model.deleteIsinMapping(mapping.id); deletingMapping = null }, enabled = !state.busy) { Text(localized("Elimina")) } },
        dismissButton = { TextButton(onClick = { deletingMapping = null }) { Text(localized("Annulla")) } }) }
}

@Composable
internal fun CategoryChoice(label: String, selected: String, choices: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(localized(label), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled && choices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(choices.firstOrNull { it.first == selected }?.second ?: localized(if (choices.isEmpty()) "Crea una categoria prima di importare" else "Scegli categoria"))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                choices.forEach { choice -> DropdownMenuItem(text = { Text(choice.second) }, onClick = { expanded = false; onSelect(choice.first) }) }
            }
        }
    }
}

@Composable
private fun renderTransferNotice(notice: TransferNotice): String {
    val values = notice.values.map { (key, value) -> key to if (key in notice.localizedValueKeys) localized(value.toString()) else value }
    return localized(notice.template, *values.toTypedArray())
}

private fun statusLabel(status: ImportStatus): String = when (status) {
    ImportStatus.READY -> "Da importare"
    ImportStatus.DUPLICATE -> "Duplicata, esclusa"
    ImportStatus.IMPORTED -> "Importata"
    ImportStatus.FAILED -> "Fallita"
    ImportStatus.UNCERTAIN -> "Esito da verificare"
}

private fun readCsv(context: Context, uri: Uri): String {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }.orEmpty()
    if (name.endsWith(".xlsx", true) || name.endsWith(".xls", true)) throw TransferException("File Excel non supportato. Esporta prima un CSV dalla banca.")
    if (!name.endsWith(".csv", true)) throw TransferException("Formato file non supportato. Usa CSV.")
    val input = context.contentResolver.openInputStream(uri) ?: throw TransferException("Impossibile leggere il file.")
    val bytes = input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            if (output.size() + count > TransferCsv.MAX_BYTES) throw TransferException("Il file supera 5 MiB.")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { throw TransferException("Il file non è un CSV UTF-8. Esportalo con questa codifica.") }
}
