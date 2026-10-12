package cc.savely.android.transfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.savely.android.ui.localized

@Composable
internal fun IsinFields(mapping: TransferIsinMapping, enabled: Boolean, change: (TransferIsinMapping) -> Unit) {
    OutlinedTextField(mapping.symbol, { change(mapping.copy(symbol = it.uppercase())) },
        label = { Text(localized("Simbolo")) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(mapping.name, { change(mapping.copy(name = it)) }, label = { Text(localized("Nome asset")) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    CategoryChoice("Tipo asset", mapping.assetType, TransferIsinMapping.ASSET_TYPES.map { value ->
        value to localized(when (value) { "stock" -> "Azione"; "etf" -> "ETF"; "crypto" -> "Crypto"; "bond" -> "Obbligazione";
            "cash" -> "Liquidità"; "real_estate" -> "Immobiliare"; else -> "Altro" })
    }, enabled) { change(mapping.copy(assetType = it)) }
}

@Composable
internal fun EditTransferIsinDialog(initial: TransferIsinMapping, busy: Boolean, notice: String?, onDismiss: () -> Unit, save: (TransferIsinMapping) -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(localized("Modifica mapping ISIN")) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(initial.isin)
            IsinFields(draft, !busy) { draft = it; error = null }
            error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = {
            try { draft.body("validation-only"); save(draft) }
            catch (failure: TransferException) { error = failure.message }
        }, enabled = !busy) { Text(localized("Salva mapping")) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(localized("Annulla")) } })
}

@Composable
internal fun NewTransferCategoryDialog(onDismiss: () -> Unit, save: (String, String, String) -> String?) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("💰") }
    var color by remember { mutableStateOf("#22c55e") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(localized("Nuova categoria")) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it; error = null }, label = { Text(localized("Nome categoria")) }, singleLine = true)
            OutlinedTextField(icon, { icon = it; error = null }, label = { Text(localized("Icona categoria")) }, singleLine = true)
            OutlinedTextField(color, { color = it; error = null }, label = { Text(localized("Colore categoria (#RRGGBB)")) }, singleLine = true)
            Text(localized("La categoria viene preparata nell'anteprima e salvata soltanto con la conferma importazione."))
            error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = {
            try { TransferCategoryDraft(name = name, type = "expense", icon = icon, color = color).body("validation-only"); error = save(name, icon, color) }
            catch (failure: TransferException) { error = failure.message }
        }) { Text(localized("Prepara categoria")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(localized("Annulla")) } })
}
