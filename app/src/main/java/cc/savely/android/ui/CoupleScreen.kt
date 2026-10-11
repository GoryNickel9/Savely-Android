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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    var archivedConnections by remember { mutableStateOf(emptyList<CoupleArchivedConnection>()) }
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
        archivedConnections = loaded.archivedConnections
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
                    comparisons = emptyList(); medians = emptyMap(); archivedConnections = emptyList(); selectedTransaction = null; editingBudget = null; myId = ""; myCode = ""
                }
                error = userMessage(failure)
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(api) { run { refresh() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(localized("Gestisci le spese insieme"), style = MaterialTheme.typography.headlineSmall)
        if (myCode.isNotBlank()) {
            Text(localized("Il tuo codice"))
            androidx.compose.foundation.text.selection.SelectionContainer { Text(myCode, style = MaterialTheme.typography.titleLarge) }
            TextButton({
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Codice Coppia Savely", myCode))
                message = "Codice copiato."
            }) { Text(localized("Copia codice")) }
        }
        if (connection == null) {
            OutlinedTextField(partnerCode, { partnerCode = it }, label = { Text(localized("Codice del partner")) }, singleLine = true,
                enabled = !busy, modifier = Modifier.fillMaxWidth())
            Button({ run {
                repository.sendInvitation(partnerCode)
                partnerCode = ""; refresh()
            } }, enabled = !busy && myId.isNotBlank() && partnerCode.isNotBlank() && (0 until requests.length()).none { requests.getJSONObject(it).getString("sender_id") == myId }) { Text(localized("Invita partner")) }
        } else {
            Text(localized("Connessione attiva"), color = MaterialTheme.colorScheme.primary)
            TextButton({ confirmation = "revoke" to connection!! }, enabled = !busy) { Text(localized("Revoca connessione")) }
        }
        for (i in 0 until requests.length()) {
            val request = requests.getJSONObject(i)
            val incoming = request.getString("receiver_id") == myId
            Text(localized(if (incoming) "Invito ricevuto" else "Invito inviato"), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (incoming) Button({ run {
                    api.rpc("accept_couple_request", JSONObject().put("p_request_id", request.getString("id"))); refresh()
                } }, enabled = !busy) { Text(localized("Accetta")) }
                TextButton({ run {
                    api.update("couple_connection_requests", request.getString("id"),
                        JSONObject().put("status", if (incoming) "rejected" else "cancelled"),
                        "${if (incoming) "receiver_id" else "sender_id"}=eq.$myId&status=eq.pending")
                    refresh()
                } }, enabled = !busy) { Text(localized(if (incoming) "Rifiuta" else "Annulla invito")) }
            }
        }
        if (connection != null) {
            HorizontalDivider()
            Text(localized("Budget comuni"), style = MaterialTheme.typography.titleLarge)
            Text(localized("Mediana dei mesi con movimenti, sulla quota del partner riportata nelle spese condivise. Le valute sono separate."), style = MaterialTheme.typography.bodySmall)
            medians.forEach { (currency, median) ->
                Text(localized("Mediana mensile {{value0}}: {{value1}}", "value0" to (currency), "value1" to (formatMoney(median, currency))), style = MaterialTheme.typography.titleMedium)
            }
            for (i in 0 until budgets.length()) {
                val row = budgets.getJSONObject(i)
                ListItem(headlineContent = { Text(row.getString("couple_category_name")) }, supportingContent = {
                    Text("${row.get("amount")} ${row.optString("currency", "EUR")}")
                }, trailingContent = { Row {
                    TextButton({ editingBudget = row; budgetName = row.getString("couple_category_name"); budgetAmount = row.get("amount").toString() }, enabled = !busy) { Text(localized("Modifica")) }
                    TextButton({ confirmation = "budget" to row }, enabled = !busy) { Text(localized("Elimina")) }
                } })
                comparisons.find { it.id == row.getString("id") }?.let { comparison ->
                    Text(localized("Mediana {{value0}} / budget {{value1}}", "value0" to (formatMoney(comparison.median, comparison.currency)), "value1" to (formatMoney(comparison.limit, comparison.currency))),
                        color = if (comparison.exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    LinearProgressIndicator(progress = { comparison.progress }, modifier = Modifier.fillMaxWidth(),
                        color = if (comparison.exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
            val availableCategories = remember(shares, budgets) { availableCoupleCategories(shares, budgets) }
            var categoriesExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton({ categoriesExpanded = true }, enabled = !busy && editingBudget == null && availableCategories.isNotEmpty()) {
                    Text(budgetName.ifBlank { localized("Scegli categoria comune") })
                }
                DropdownMenu(categoriesExpanded, { categoriesExpanded = false }) {
                    availableCategories.forEach { category -> DropdownMenuItem(text = { Text(category) }, onClick = { budgetName = category; categoriesExpanded = false }) }
                }
            }
            if (availableCategories.isEmpty() && editingBudget == null) Text(localized("Condividi prima una spesa con una categoria senza budget."), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(budgetAmount, { budgetAmount = it }, label = { Text(localized("Limite mensile {{value0}}", "value0" to (editingBudget?.optString("currency") ?: "EUR"))) },
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
            } }, enabled = !busy && budgetName.isNotBlank() && budgetAmount.isNotBlank()) { Text(localized("Salva budget")) }
            if (editingBudget != null) TextButton({ editingBudget = null; budgetName = ""; budgetAmount = "" }) { Text(localized("Annulla modifica")) }
            HorizontalDivider()
            Text(localized("Condividi una transazione"), style = MaterialTheme.typography.titleLarge)
            Text(localized("Scegli tra le tue 500 uscite più recenti."), style = MaterialTheme.typography.bodySmall)
            val sharedTransactionIds = remember(shares) { (0 until shares.length()).map { shares.getJSONObject(it).getString("original_tx_id") }.toSet() }
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton({ expanded = true }, enabled = !busy && transactions.length() > 0) {
                    Text(selectedTransaction?.optString("description")?.takeUnless { it.isBlank() || it == "null" } ?: localized("Scegli transazione"))
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
            OutlinedTextField(shareCategory, { shareCategory = it }, label = { Text(localized("Categoria comune (facoltativa)")) }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!customSplit, { customSplit = false }, label = { Text(localized("Metà ciascuno")) }, enabled = !busy)
                FilterChip(customSplit, { customSplit = true }, label = { Text(localized("Quota personalizzata")) }, enabled = !busy)
            }
            if (customSplit) OutlinedTextField(partnerAmount, { partnerAmount = it }, label = { Text(localized("Importo del partner nella valuta della transazione")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), enabled = !busy)
            selectedTransaction?.let { tx ->
                val preview = runCatching { coupleSplit(tx.get("amount").toString().toBigDecimal(), if (customSplit) couplePartnerAmount(partnerAmount) else null) }
                preview.getOrNull()?.let { split ->
                    val currency = tx.getString("currency")
                    Text(localized("La tua quota: {{value0}} · Quota partner: {{value1}}", "value0" to (formatMoney(split.creator, currency)), "value1" to (formatMoney(split.partner, currency))))
                    if (split.roundingDifference.signum() != 0) Text(localized("Le quote uguali superano il totale di {{value0}} per l’arrotondamento. Usa una quota personalizzata per mantenere il totale.", "value0" to (formatMoney(split.roundingDifference, currency))), style = MaterialTheme.typography.bodySmall)
                }
                if (customSplit && partnerAmount.isNotBlank() && preview.isFailure) Text(localized(preview.exceptionOrNull()?.message ?: "Quota non valida."), color = MaterialTheme.colorScheme.error)
            }
            Button({ run {
                val tx = selectedTransaction ?: throw IllegalArgumentException("Scegli una transazione.")
                repository.shareExpense(connection!!.getString("id"), tx.getString("id"), shareCategory, if (customSplit) partnerAmount else null)
                selectedTransaction = null; partnerAmount = ""; shareCategory = ""; refresh()
            } }, enabled = !busy && selectedTransaction != null) { Text(localized("Condividi spesa")) }
            HorizontalDivider()
            Text(localized("Spese condivise"), style = MaterialTheme.typography.titleLarge)
            Text(localized("{{value0}} condivisioni caricate. {{value1}}La quota partner è riferita a chi ha creato la spesa. Le spese eliminate restano visibili.", "value0" to (shares.length()), "value1" to (if (shares.length() > 1000) localized("Qui sono mostrate le 1.000 più recenti; le mediane usano tutte le righe. ") else "")), style = MaterialTheme.typography.bodySmall)
            for (i in 0 until minOf(shares.length(), 1000)) {
                val row = shares.getJSONObject(i)
                val removed = !row.isNull("tx_deleted_at")
                ListItem(headlineContent = { Text(row.optString("description").takeUnless { it == "null" } ?: localized("Spesa")) },
                    supportingContent = { Text(localized("{{value0}} · Quota partner: {{value1}} {{value2}} {{value3}}", "value0" to (row.optString("date")), "value1" to (row.get("my_share_amount")), "value2" to (row.optString("currency")), "value3" to (if (removed) localized("(eliminata)") else ""))) },
                    trailingContent = { TextButton({ confirmation = "share" to row }, enabled = !busy) { Text(localized("Rimuovi")) } })
            }
        }
        if (myId.isNotBlank()) {
            HorizontalDivider()
            Text(localized("Connessioni archiviate"), style = MaterialTheme.typography.titleLarge)
            Text(localized("Le connessioni revocate sono in sola lettura. Per privacy, spese condivise e budget della connessione revocata non sono accessibili."),
                style = MaterialTheme.typography.bodySmall)
            val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.forLanguageTag(LocalAppLanguage.current.tag)).withZone(ZoneId.systemDefault())
            if (archivedConnections.isEmpty()) Text(localized("Nessuna connessione archiviata"))
            archivedConnections.forEach { archived ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(localized("Connessione revocata il {{date}}", "date" to dateFormat.format(archived.revokedAt)), style = MaterialTheme.typography.titleMedium)
                    Text(localized("Creata il {{date}}", "date" to dateFormat.format(archived.createdAt)))
                    Text(localized("Sola lettura"), style = MaterialTheme.typography.labelMedium)
                } }
            }
        }
        error?.let {
            Text(localized(it), color = MaterialTheme.colorScheme.error)
            if (myId.isNotBlank()) Text(localized("I dati mostrati potrebbero non essere aggiornati."), style = MaterialTheme.typography.bodySmall)
        }
        message?.let { Text(localized(it), color = MaterialTheme.colorScheme.primary) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton({ run { refresh() } }, enabled = !busy) { Text(localized("Aggiorna")) }
    }
    confirmation?.let { (kind, row) -> AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text(localized(when (kind) { "revoke" -> "Revocare la connessione?"; "budget" -> "Eliminare il budget?"; else -> "Rimuovere la condivisione?" })) },
        text = { Text(localized(if (kind == "share") "La transazione originale rimane nel conto di chi l’ha creata." else "Conferma l’operazione sui dati condivisi.")) },
        confirmButton = { TextButton({ confirmation = null; run {
            when (kind) {
                "revoke" -> {
                    require(api.session()?.userId == myId) { "Account cambiato. Aggiorna i dati prima di continuare." }
                    api.update("couple_connections", row.getString("id"), JSONObject().put("revoked_at", Instant.now().toString()),
                        "or=(user_a.eq.$myId,user_b.eq.$myId)&revoked_at=is.null")
                }
                "budget" -> api.delete("couple_budgets", row.getString("id"), "connection_id=eq.${connection!!.getString("id")}")
                else -> if (row.getString("created_by") == myId) api.delete("shared_expenses", row.getString("id"), "created_by=eq.$myId")
                    else api.rpc("delete_shared_expense_by_partner", JSONObject().put("p_id", row.getString("id")))
            }
            refresh()
        } }) { Text(localized("Conferma")) } }, dismissButton = { TextButton({ confirmation = null }) { Text(localized("Annulla")) } }) }
}

internal fun positiveAmount(raw: String): BigDecimal {
    return couplePartnerAmount(raw)
}
