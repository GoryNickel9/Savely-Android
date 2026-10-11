package cc.savely.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.savely.android.data.*
import cc.savely.android.finance.formatMoney
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant

@Composable
fun CoupleScreen(api: SupabaseApi) {
    val repository = remember(api) { CoupleRepository(api) }
    val context = LocalContext.current
    var myId by remember { mutableStateOf("") }
    var myCode by remember { mutableStateOf("") }
    var partnerCode by rememberSaveable { mutableStateOf("") }
    var connection by remember { mutableStateOf<JSONObject?>(null) }
    var requests by remember { mutableStateOf(JSONArray()) }
    var shares by remember { mutableStateOf(JSONArray()) }
    var budgets by remember { mutableStateOf(JSONArray()) }
    var transactions by remember { mutableStateOf(JSONArray()) }
    var comparisons by remember { mutableStateOf(emptyList<CoupleBudgetComparison>()) }
    var medians by remember { mutableStateOf(emptyMap<String, BigDecimal>()) }
    var selectedTransaction by remember { mutableStateOf<JSONObject?>(null) }
    var shareCategory by rememberSaveable { mutableStateOf("") }
    var customSplit by rememberSaveable { mutableStateOf(false) }
    var partnerAmount by rememberSaveable { mutableStateOf("") }
    var budgetName by rememberSaveable { mutableStateOf("") }
    var budgetAmount by rememberSaveable { mutableStateOf("") }
    var editingBudget by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        val loaded = repository.load()
        myId = loaded.userId; myCode = loaded.code; connection = loaded.connection
        requests = loaded.requests; shares = loaded.shares; budgets = loaded.budgets; transactions = loaded.transactions
        comparisons = loaded.comparisons; medians = loaded.medians
        selectedTransaction = null; editingBudget = null
    }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; message = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is ApiException && failure.kind in setOf(ApiError.AUTHENTICATION, ApiError.PERMISSION)) {
                    connection = null; requests = JSONArray(); shares = JSONArray(); budgets = JSONArray(); transactions = JSONArray()
                    comparisons = emptyList(); medians = emptyMap(); selectedTransaction = null; editingBudget = null; myId = ""; myCode = ""
                }
                error = userMessage(failure)
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(api) { run { refresh() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Gestisci le spese insieme", style = MaterialTheme.typography.headlineSmall)
        if (myCode.isNotBlank()) {
            Text("Il tuo codice")
            androidx.compose.foundation.text.selection.SelectionContainer { Text(myCode, style = MaterialTheme.typography.titleLarge) }
            TextButton({
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Codice Coppia Savely", myCode))
                message = "Codice copiato."
            }) { Text("Copia codice") }
        }
        if (connection == null) {
            OutlinedTextField(partnerCode, { partnerCode = it }, label = { Text("Codice del partner") }, singleLine = true,
                enabled = !busy, modifier = Modifier.fillMaxWidth())
            Button({ run {
                repository.sendInvitation(partnerCode)
                partnerCode = ""; refresh()
            } }, enabled = !busy && myId.isNotBlank() && partnerCode.isNotBlank() && (0 until requests.length()).none { requests.getJSONObject(it).getString("sender_id") == myId }) { Text("Invita partner") }
        } else {
            Text("Connessione attiva", color = MaterialTheme.colorScheme.primary)
            TextButton({ confirmation = "revoke" to connection!! }, enabled = !busy) { Text("Revoca connessione") }
        }
        for (i in 0 until requests.length()) {
            val request = requests.getJSONObject(i)
            val incoming = request.getString("receiver_id") == myId
            Text(if (incoming) "Invito ricevuto" else "Invito inviato", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (incoming) Button({ run {
                    api.rpc("accept_couple_request", JSONObject().put("p_request_id", request.getString("id"))); refresh()
                } }, enabled = !busy) { Text("Accetta") }
                TextButton({ run {
                    api.update("couple_connection_requests", request.getString("id"),
                        JSONObject().put("status", if (incoming) "rejected" else "cancelled"),
                        "${if (incoming) "receiver_id" else "sender_id"}=eq.$myId&status=eq.pending")
                    refresh()
                } }, enabled = !busy) { Text(if (incoming) "Rifiuta" else "Annulla invito") }
            }
        }
        if (connection != null) {
            HorizontalDivider()
            Text("Budget comuni", style = MaterialTheme.typography.titleLarge)
            Text("Mediana dei mesi con movimenti, sulla quota del partner riportata nelle spese condivise. Le valute sono separate.", style = MaterialTheme.typography.bodySmall)
            medians.forEach { (currency, median) ->
                Text("Mediana mensile $currency: ${formatMoney(median, currency)}", style = MaterialTheme.typography.titleMedium)
            }
            for (i in 0 until budgets.length()) {
                val row = budgets.getJSONObject(i)
                ListItem(headlineContent = { Text(row.getString("couple_category_name")) }, supportingContent = {
                    Text("${row.get("amount")} ${row.optString("currency", "EUR")}")
                }, trailingContent = { Row {
                    TextButton({ editingBudget = row; budgetName = row.getString("couple_category_name"); budgetAmount = row.get("amount").toString() }, enabled = !busy) { Text("Modifica") }
                    TextButton({ confirmation = "budget" to row }, enabled = !busy) { Text("Elimina") }
                } })
                comparisons.find { it.id == row.getString("id") }?.let { comparison ->
                    Text("Mediana ${formatMoney(comparison.median, comparison.currency)} / budget ${formatMoney(comparison.limit, comparison.currency)}",
                        color = if (comparison.exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    LinearProgressIndicator(progress = { comparison.progress }, modifier = Modifier.fillMaxWidth(),
                        color = if (comparison.exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
            val availableCategories = remember(shares, budgets) { availableCoupleCategories(shares, budgets) }
            var categoriesExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton({ categoriesExpanded = true }, enabled = !busy && editingBudget == null && availableCategories.isNotEmpty()) {
                    Text(budgetName.ifBlank { "Scegli categoria comune" })
                }
                DropdownMenu(categoriesExpanded, { categoriesExpanded = false }) {
                    availableCategories.forEach { category -> DropdownMenuItem(text = { Text(category) }, onClick = { budgetName = category; categoriesExpanded = false }) }
                }
            }
            if (availableCategories.isEmpty() && editingBudget == null) Text("Condividi prima una spesa con una categoria senza budget.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(budgetAmount, { budgetAmount = it }, label = { Text("Limite mensile ${editingBudget?.optString("currency") ?: "EUR"}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy, modifier = Modifier.fillMaxWidth())
            Button({ run {
                val amount = positiveAmount(budgetAmount)
                val connectionId = connection!!.getString("id")
                if (editingBudget != null) api.update("couple_budgets", editingBudget!!.getString("id"), JSONObject().put("amount", amount), "connection_id=eq.$connectionId")
                else {
                    require(budgetName in availableCategories) { "Scegli una categoria condivisa senza budget." }
                    api.insert("couple_budgets", JSONObject().put("connection_id", connectionId).put("couple_category_name", budgetName)
                        .put("amount", amount.toPlainString()).put("currency", "EUR").put("month", 1).put("year", 2000).put("created_by", myId))
                }
                editingBudget = null; budgetName = ""; budgetAmount = ""; refresh()
            } }, enabled = !busy && budgetName.isNotBlank() && budgetAmount.isNotBlank()) { Text("Salva budget") }
            if (editingBudget != null) TextButton({ editingBudget = null; budgetName = ""; budgetAmount = "" }) { Text("Annulla modifica") }
            HorizontalDivider()
            Text("Condividi una transazione", style = MaterialTheme.typography.titleLarge)
            Text("Scegli tra le tue 500 uscite più recenti.", style = MaterialTheme.typography.bodySmall)
            val sharedTransactionIds = remember(shares) { (0 until shares.length()).map { shares.getJSONObject(it).getString("original_tx_id") }.toSet() }
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton({ expanded = true }, enabled = !busy && transactions.length() > 0) {
                    Text(selectedTransaction?.optString("description")?.takeUnless { it.isBlank() || it == "null" } ?: "Scegli transazione")
                }
                DropdownMenu(expanded, { expanded = false }) {
                    for (i in 0 until transactions.length()) {
                        val tx = transactions.getJSONObject(i)
                        val alreadyShared = tx.getString("id") in sharedTransactionIds
                        DropdownMenuItem(text = { Text("${tx.optString("date")} · ${tx.get("amount")} ${tx.optString("currency", "EUR")} · ${tx.optString("description")}") },
                            enabled = !alreadyShared, onClick = { selectedTransaction = tx; expanded = false })
                    }
                }
            }
            OutlinedTextField(shareCategory, { shareCategory = it }, label = { Text("Categoria comune (facoltativa)") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!customSplit, { customSplit = false }, label = { Text("Metà ciascuno") }, enabled = !busy)
                FilterChip(customSplit, { customSplit = true }, label = { Text("Quota personalizzata") }, enabled = !busy)
            }
            if (customSplit) OutlinedTextField(partnerAmount, { partnerAmount = it }, label = { Text("Importo del partner nella valuta della transazione") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), enabled = !busy)
            selectedTransaction?.let { tx ->
                val preview = runCatching { coupleSplit(tx.get("amount").toString().toBigDecimal(), if (customSplit) couplePartnerAmount(partnerAmount) else null) }
                preview.getOrNull()?.let { split ->
                    val currency = tx.getString("currency")
                    Text("La tua quota: ${formatMoney(split.creator, currency)} · Quota partner: ${formatMoney(split.partner, currency)}")
                    if (split.roundingDifference.signum() != 0) Text("Le quote uguali superano il totale di ${formatMoney(split.roundingDifference, currency)} per l’arrotondamento. Usa una quota personalizzata per mantenere il totale.", style = MaterialTheme.typography.bodySmall)
                }
                if (customSplit && partnerAmount.isNotBlank() && preview.isFailure) Text(preview.exceptionOrNull()?.message ?: "Quota non valida.", color = MaterialTheme.colorScheme.error)
            }
            Button({ run {
                val tx = selectedTransaction ?: throw IllegalArgumentException("Scegli una transazione.")
                repository.shareExpense(connection!!.getString("id"), tx.getString("id"), shareCategory, if (customSplit) partnerAmount else null)
                selectedTransaction = null; partnerAmount = ""; shareCategory = ""; refresh()
            } }, enabled = !busy && selectedTransaction != null) { Text("Condividi spesa") }
            HorizontalDivider()
            Text("Spese condivise", style = MaterialTheme.typography.titleLarge)
            Text("${shares.length()} condivisioni caricate. ${if (shares.length() > 1000) "Qui sono mostrate le 1.000 più recenti; le mediane usano tutte le righe. " else ""}La quota partner è riferita a chi ha creato la spesa. Le spese eliminate restano visibili.", style = MaterialTheme.typography.bodySmall)
            for (i in 0 until minOf(shares.length(), 1000)) {
                val row = shares.getJSONObject(i)
                val removed = !row.isNull("tx_deleted_at")
                ListItem(headlineContent = { Text(row.optString("description").takeUnless { it == "null" } ?: "Spesa") },
                    supportingContent = { Text("${row.optString("date")} · Quota partner: ${row.get("my_share_amount")} ${row.optString("currency")} ${if (removed) "(eliminata)" else ""}") },
                    trailingContent = { TextButton({ confirmation = "share" to row }, enabled = !busy) { Text("Rimuovi") } })
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton({ run { refresh() } }, enabled = !busy) { Text("Aggiorna") }
    }
    confirmation?.let { (kind, row) -> AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text(when (kind) { "revoke" -> "Revocare la connessione?"; "budget" -> "Eliminare il budget?"; else -> "Rimuovere la condivisione?" }) },
        text = { Text(if (kind == "share") "La transazione originale rimane nel conto di chi l’ha creata." else "Conferma l’operazione sui dati condivisi.") },
        confirmButton = { TextButton({ confirmation = null; run {
            when (kind) {
                "revoke" -> api.update("couple_connections", row.getString("id"), JSONObject().put("revoked_at", Instant.now().toString()))
                "budget" -> api.delete("couple_budgets", row.getString("id"), "connection_id=eq.${connection!!.getString("id")}")
                else -> if (row.getString("created_by") == myId) api.delete("shared_expenses", row.getString("id"), "created_by=eq.$myId")
                    else api.rpc("delete_shared_expense_by_partner", JSONObject().put("p_id", row.getString("id")))
            }
            refresh()
        } }) { Text("Conferma") } }, dismissButton = { TextButton({ confirmation = null }) { Text("Annulla") } }) }
}

internal fun positiveAmount(raw: String): BigDecimal {
    return couplePartnerAmount(raw)
}
