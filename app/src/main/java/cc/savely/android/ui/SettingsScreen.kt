package cc.savely.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cc.savely.android.BuildConfig
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.data.PasswordPolicy
import cc.savely.android.data.authSessionId
import cc.savely.android.data.revocableSessionId
import cc.savely.android.transfer.TransferScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun SettingsScreen(api: SupabaseApi, session: Session, theme: String, onTheme: (String) -> Unit, onSignOut: () -> Unit, onLegal: (String) -> Unit) {
    var pane by rememberSaveable { mutableStateOf("account") }
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fullName by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("EUR") }
    var email by rememberSaveable { mutableStateOf(session.email) }
    var password by remember { mutableStateOf("") }
    var passwordConfirmation by remember { mutableStateOf("") }
    var deleteDialog by remember { mutableStateOf(false) }
    var deleteConfirmation by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; message = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = userMessage(failure) }
            finally { busy = false }
        }
    }
    LaunchedEffect(api) {
        run {
            profile = api.profile()
            fullName = profile?.optString("full_name")?.takeUnless { it == "null" } ?: ""
            currency = profile?.optString("default_currency", "EUR") ?: "EUR"
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("account" to "Account", "security" to "Sicurezza", "transfer" to "Dati").forEach { (key, title) ->
                FilterChip(pane == key, { pane = key }, label = { Text(localized(title)) })
            }
        }
        when (pane) {
            "transfer" -> TransferScreen(api, Modifier.weight(1f))
            "security" -> SecurityPane(api, Modifier.weight(1f), onSignOut)
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(session.email, style = MaterialTheme.typography.titleLarge)
                Text(localized("Profilo"), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(fullName, { fullName = it }, label = { Text(localized("Nome completo")) }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Text(localized("Valuta predefinita"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("EUR", "USD", "GBP", "CHF").forEach { code -> FilterChip(currency == code, { currency = code }, label = { Text(code) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("JPY", "CNY", "IDR").forEach { code -> FilterChip(currency == code, { currency = code }, label = { Text(code) }) }
                }
                Button({ run {
                    api.updateProfile(JSONObject().put("full_name", fullName.trim()).put("default_currency", currency))
                    message = "Profilo salvato."
                } }, enabled = !busy && profile != null) { Text(localized("Salva profilo")) }
                HorizontalDivider()
                Text(localized("Lingua"), style = MaterialTheme.typography.titleMedium)
                LanguageSelector()
                Text(localized("Aspetto"), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("dark" to "Scuro", "light" to "Chiaro", "system" to "Sistema").forEach { (value, label) ->
                        FilterChip(theme == value, { onTheme(value) }, label = { Text(localized(label)) })
                    }
                }
                HorizontalDivider()
                Text(localized("Credenziali"), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(email, { email = it }, label = { Text(localized("Nuova email")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Button({ run { api.updateAccount(email = email); message = "Controlla le email di conferma prima di usare il nuovo indirizzo." } },
                    enabled = !busy && email.isNotBlank() && email != session.email) { Text(localized("Modifica email")) }
                OutlinedTextField(password, { password = it }, label = { Text(localized("Nuova password")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Text(localized(PasswordPolicy.requirements), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(passwordConfirmation, { passwordConfirmation = it }, label = { Text(localized("Conferma nuova password")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    isError = passwordConfirmation.isNotEmpty() && passwordConfirmation != password)
                if (passwordConfirmation.isNotEmpty() && passwordConfirmation != password)
                    Text(localized("Le password non coincidono."), color = MaterialTheme.colorScheme.error)
                Button({ run {
                    require(password == passwordConfirmation) { "Le password non coincidono." }
                    api.updateAccount(password = password); password = ""; passwordConfirmation = ""; message = "Password aggiornata."
                } }, enabled = !busy && PasswordPolicy.error(password) == null && passwordConfirmation == password) { Text(localized("Modifica password")) }
                error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
                message?.let { Text(localized(it), color = MaterialTheme.colorScheme.primary) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                HorizontalDivider()
                Text("Savely Android ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                TextButton({ onLegal("privacy") }) { Text(localized("Privacy")) }
                TextButton({ onLegal("terms") }) { Text(localized("Termini di servizio")) }
                TextButton({ onLegal("cookies") }) { Text(localized("Memorizzazione locale")) }
                Text(localized("Le operazioni sincronizzate sono visibili anche nel tuo account web."), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onSignOut, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(localized("Esci dall’account")) }
                TextButton({ deleteDialog = true }, enabled = !busy) { Text(localized("Elimina definitivamente l’account"), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { if (!busy) deleteDialog = false },
        title = { Text(localized("Eliminare l’account?")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized("L’account e i dati saranno eliminati anche da Savely web. Questa operazione non può essere annullata. Scrivi ELIMINA per continuare."))
            OutlinedTextField(deleteConfirmation, { deleteConfirmation = it }, label = { Text(localized("Conferma")) }, enabled = !busy)
            error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = {
            TextButton({ run { api.invokeFunction("delete-account"); deleteDialog = false; onSignOut() } },
                enabled = !busy && deleteConfirmation == "ELIMINA") { Text(localized("Elimina account")) }
        }, dismissButton = { TextButton({ deleteDialog = false; deleteConfirmation = "" }, enabled = !busy) { Text(localized("Annulla")) } })
}

@Composable
private fun SecurityPane(api: SupabaseApi, modifier: Modifier, onSignOut: () -> Unit) {
    var factors by remember { mutableStateOf(JSONArray()) }
    var sessions by remember { mutableStateOf(JSONArray()) }
    var enrollment by remember { mutableStateOf<JSONObject?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<Pair<String, String>?>(null) }
    var removalCode by remember { mutableStateOf("") }
    var currentSessionId by remember { mutableStateOf<String?>(null) }
    var revokedSessionIds by remember { mutableStateOf(emptySet<String>()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        currentSessionId = api.session()?.authSessionId()
        factors = api.listMfaFactors(includeUnverified = true)
        sessions = api.rpc("get_active_sessions") as? JSONArray ?: throw IllegalArgumentException("Risposta sessioni non valida.")
    }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; message = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = userMessage(failure) }
            finally { busy = false }
        }
    }
    LaunchedEffect(api) { run { refresh() } }
    Column(modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(localized("Verifica in due passaggi"), style = MaterialTheme.typography.titleLarge)
        if (factors.length() == 0 && enrollment == null) {
            Text(localized("Proteggi l’accesso con un codice della tua app di autenticazione."))
            Button({ run { enrollment = api.enrollTotp() } }, enabled = !busy) { Text(localized("Configura autenticatore")) }
        }
        enrollment?.let { pending ->
            val totp = pending.getJSONObject("totp")
            Text(localized("Aggiungi questa chiave manuale alla tua app di autenticazione:"))
            androidx.compose.foundation.text.selection.SelectionContainer { Text(totp.getString("secret")) }
            TextButton({
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(totp.getString("uri")))) }
                    .onFailure { error = "Nessuna app di autenticazione disponibile. Inserisci la chiave manualmente." }
            }) { Text(localized("Apri autenticatore")) }
            OutlinedTextField(code, { code = it.filter { digit -> digit in '0'..'9' }.take(6) }, label = { Text(localized("Codice a 6 cifre")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Button({ run { api.verifyTotp(pending.getString("id"), code); enrollment = null; code = ""; refresh(); message = "Autenticatore attivato." } },
                enabled = !busy && code.length == 6) { Text(localized("Attiva verifica")) }
            TextButton({ error = null; confirmation = "pending-factor" to pending.getString("id") }, enabled = !busy) { Text(localized("Annulla configurazione")) }
        }
        for (index in 0 until factors.length()) {
            val factor = factors.getJSONObject(index)
            val factorId = factor.getString("id")
            if (factorId == enrollment?.optString("id")) continue
            if (factor.optString("status") == "unverified") {
                Text(localized("Configurazione autenticatore incompleta"))
                Text(localized("La verifica non è attiva. Se non hai più la chiave, annulla questa configurazione e avviane una nuova."),
                    style = MaterialTheme.typography.bodySmall)
                TextButton({ error = null; confirmation = "pending-factor" to factorId }, enabled = !busy,
                    modifier = Modifier.testTag("remove-pending-factor-$factorId")) { Text(localized("Annulla configurazione incompleta")) }
            } else {
                Text(localized("Autenticatore attivo"))
                TextButton({ removalCode = ""; error = null; confirmation = "factor" to factorId }, enabled = !busy) { Text(localized("Rimuovi autenticatore")) }
            }
        }
        HorizontalDivider()
        Text(localized("Accessi registrati"), style = MaterialTheme.typography.titleLarge)
        Text(localized("L’elenco mostra gli accessi degli ultimi 30 giorni. Revoca l’accesso di un dispositivo per impedirne il rinnovo."), style = MaterialTheme.typography.bodySmall)
        for (index in 0 until sessions.length()) {
            val row = sessions.getJSONObject(index)
            val sessionId = revocableSessionId(row)
            if (sessionId in revokedSessionIds) continue
            val device = row.optString("user_agent").takeUnless { it == "null" || it.isBlank() }
            ListItem(headlineContent = { Text(device ?: localized("Dispositivo")) },
                supportingContent = { Column {
                    Text(row.optString("created_at"))
                    if (sessionId == currentSessionId && sessionId != null) Text(localized("Questo dispositivo"))
                    if (sessionId == null) Text(localized("Sessione non revocabile da questo elenco."))
                } }, trailingContent = {
                    TextButton({ sessionId?.let { confirmation = "session" to it } }, enabled = !busy && sessionId != null,
                        modifier = Modifier.testTag("revoke-session-${sessionId ?: "unavailable-$index"}")) { Text(localized("Revoca")) }
                })
        }
        if (sessions.length() == 0 && !busy) Text(localized("Nessuna sessione da mostrare."))
        error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
        message?.let { Text(localized(it), color = MaterialTheme.colorScheme.primary) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton({ run { refresh() } }, enabled = !busy) { Text(localized("Aggiorna sicurezza")) }
    }
    confirmation?.let { (kind, id) -> AlertDialog(onDismissRequest = {
        if (!busy) { confirmation = null; removalCode = "" }
    },
        title = { Text(localized(when (kind) {
            "factor" -> "Rimuovere l’autenticatore?"
            "pending-factor" -> "Annullare la configurazione?"
            else -> "Revocare questa sessione?"
        })) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized(when (kind) {
                "factor" -> "Inserisci un nuovo codice della tua app di autenticazione per rimuovere la verifica in due passaggi."
                "pending-factor" -> "Rimuovi soltanto questa configurazione incompleta. Gli autenticatori già attivi resteranno disponibili."
                else -> "Il dispositivo dovrà accedere nuovamente. Anche la sessione corrente potrebbe essere chiusa."
            }))
            if (kind == "factor") {
                OutlinedTextField(removalCode, { value -> removalCode = value.filter { it in '0'..'9' }.take(6) },
                    label = { Text(localized("Codice per rimuovere l’autenticatore")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy)
            }
            if (kind == "factor" || kind == "pending-factor") error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton({
            if (kind == "factor") {
                val submittedCode = removalCode
                run {
                    try {
                        api.verifyTotp(id, submittedCode)
                        api.unenrollTotp(id)
                        confirmation = null
                        refresh()
                        message = "Autenticatore rimosso."
                    } finally { removalCode = "" }
                }
            } else if (kind == "pending-factor") {
                run {
                    api.unenrollPendingTotp(id)
                    if (enrollment?.optString("id") == id) { enrollment = null; code = "" }
                    confirmation = null
                    refresh()
                    message = "Configurazione incompleta rimossa."
                }
            } else {
                confirmation = null
                run {
                    if (api.revokeSession(id)) onSignOut()
                    else { revokedSessionIds = revokedSessionIds + id; refresh(); message = "Accesso revocato." }
                }
            }
        }, enabled = !busy && (kind != "factor" || removalCode.length == 6)) { Text(localized("Conferma")) } },
        dismissButton = { TextButton({ confirmation = null; removalCode = "" }, enabled = !busy) { Text(localized("Annulla")) } }) }
}
