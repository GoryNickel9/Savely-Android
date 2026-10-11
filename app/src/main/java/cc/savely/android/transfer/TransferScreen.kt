package cc.savely.android.transfer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
            if (ownerError == null) CircularProgressIndicator() else Text(ownerError!!)
        }
        return
    }
    val model: TransferViewModel = viewModel(key = "transfer-${System.identityHashCode(api)}-$owner", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = TransferViewModel(api) as T
    })
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    var format by remember { mutableStateOf(ImportFormat.REVOLUT) }
    var bankCurrency by remember { mutableStateOf("EUR") }
    var confirm by remember { mutableStateOf(false) }
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
            Text("Importa ed esporta", style = MaterialTheme.typography.headlineSmall)
            Text("Anteprima e controllo duplicati prima di salvare. CSV fino a 5 MiB e 10.000 righe.", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Text("Formato importazione", style = MaterialTheme.typography.titleMedium)
            ImportFormat.entries.forEach { choice ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = choice == format, onClick = { format = choice }, enabled = !state.busy)
                    TextButton(onClick = { format = choice }, enabled = !state.busy) { Text(choice.label) }
                }
            }
            if (format == ImportFormat.BANK) CategoryChoice("Valuta del conto se assente nel file", bankCurrency,
                TransferCsv.currencies.sorted().map { it to it }, !state.busy) { bankCurrency = it }
            OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel")) }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()) { Text("Scegli CSV e prepara anteprima") }
            Text("XLSX e investimenti con mapping ISIN non sono ancora supportati. Nessuna posizione viene creata.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            HorizontalDivider()
            Text("Export completo dell'account", style = MaterialTheme.typography.titleMedium)
            Text("Tutte le 25 tabelle previste dal sito, con filtri sull'account e lettura di tutte le pagine. Se una tabella fallisce, l'export si interrompe.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = model::prepareExport, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Prepara export CSV") }
            if (state.export != null) Button(onClick = { saver.launch("savely_export_${LocalDate.now()}.csv") }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()) { Text("Salva CSV (${state.export!!.counts.values.sum()} righe)") }
        }
        if (state.busy) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.progress, style = MaterialTheme.typography.bodyMedium)
        }
        state.message?.let { message -> item { Text(message, style = MaterialTheme.typography.bodyMedium) } }
        if (state.preview.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text("${state.preview.size} transazioni in anteprima", style = MaterialTheme.typography.titleLarge)
                Text("${state.preview.count { it.status == ImportStatus.DUPLICATE }} duplicate; ${state.skipped} righe escluse dal formato; ${state.problems.size} righe non valide.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { model.selectAll(true) }, enabled = !state.busy) { Text("Seleziona tutte") }
                    TextButton(onClick = { model.selectAll(false) }, enabled = !state.busy) { Text("Deseleziona") }
                }
                Button(onClick = { confirm = true }, enabled = selected > 0 && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Importa $selected transazioni selezionate") }
                if (state.preview.any { it.status == ImportStatus.UNCERTAIN }) OutlinedButton(onClick = model::recheckDuplicates,
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Verifica gli esiti incerti") }
            }
            items(state.preview, key = { "transaction-${it.transaction.row}" }) { row ->
                val editable = !state.busy && row.status in setOf(ImportStatus.READY, ImportStatus.FAILED)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(row.selected, onCheckedChange = { model.select(row.transaction.row, it) }, enabled = editable)
                            Column(Modifier.weight(1f)) {
                                Text(row.transaction.description, style = MaterialTheme.typography.titleMedium)
                                Text("${row.transaction.date} · ${row.transaction.amount.toPlainString()} ${row.transaction.currency} · ${if (row.transaction.type == "expense") "Uscita" else "Entrata"}")
                            }
                        }
                        Text("Riga ${row.transaction.row}: ${statusLabel(row.status)}", style = MaterialTheme.typography.labelLarge)
                        row.message?.let { Text(it, color = if (row.status == ImportStatus.FAILED || row.status == ImportStatus.UNCERTAIN) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                        if (row.status in setOf(ImportStatus.READY, ImportStatus.FAILED)) {
                            CategoryChoice("Categoria", row.categoryId.orEmpty(), state.context?.categories.orEmpty().filter { it.type == row.transaction.type }.map { it.id to it.name },
                                editable) { model.category(row.transaction.row, it) }
                            if (row.transaction.currency != "EUR") OutlinedTextField(value = row.rate,
                                onValueChange = { model.rate(row.transaction.row, it) }, label = { Text("1 ${row.transaction.currency} in EUR") },
                                supportingText = { Text("Inserisci il cambio della data della transazione. Non converto l'importo originale.") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        if (state.problems.isNotEmpty()) {
            item { Text("Righe non importabili", style = MaterialTheme.typography.titleMedium) }
            items(state.problems, key = { "problem-${it.row}" }) { Text("Riga ${it.row}: ${it.message}", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Conferma importazione") },
        text = { Text("Salverò $selected transazioni nel tuo account Savely. Saranno visibili anche sul sito. Le righe duplicate o non selezionate verranno saltate.") },
        confirmButton = { TextButton(onClick = { confirm = false; model.confirmImport() }) { Text("Conferma e importa") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annulla") } })
}

@Composable
private fun CategoryChoice(label: String, selected: String, choices: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled && choices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(choices.firstOrNull { it.first == selected }?.second ?: if (choices.isEmpty()) "Crea una categoria prima di importare" else "Scegli categoria")
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                choices.forEach { choice -> DropdownMenuItem(text = { Text(choice.second) }, onClick = { expanded = false; onSelect(choice.first) }) }
            }
        }
    }
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
