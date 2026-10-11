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
                FilterChip(pane == key, { pane = key }, label = { Text(title) })
            }
        }
        when (pane) {
            "transfer" -> TransferScreen(api, Modifier.weight(1f))
            "security" -> SecurityPane(api, Modifier.weight(1f), onSignOut)
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(session.email, style = MaterialTheme.typography.titleLarge)
                Text("Profilo", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(fullName, { fullName = it }, label = { Text("Nome completo") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Text("Valuta predefinita")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("EUR", "USD", "GBP", "CHF").forEach { code -> FilterChip(currency == code, { currency = code }, label = { Text(code) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("JPY", "CNY", "IDR").forEach { code -> FilterChip(currency == code, { currency = code }, label = { Text(code) }) }
                }
                Button({ run {
                    api.updateProfile(JSONObject().put("full_name", fullName.trim()).put("default_currency", currency))
                    message = "Profilo salvato."
                } }, enabled = !busy && profile != null) { Text("Salva profilo") }
                HorizontalDivider()
                Text("Aspetto", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("dark" to "Scuro", "light" to "Chiaro", "system" to "Sistema").forEach { (value, label) ->
                        FilterChip(theme == value, { onTheme(value) }, label = { Text(label) })
                    }
                }
                HorizontalDivider()
                Text("Credenziali", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(email, { email = it }, label = { Text("Nuova email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Button({ run { api.updateAccount(email = email); message = "Controlla le email di conferma prima di usare il nuovo indirizzo." } },
                    enabled = !busy && email.isNotBlank() && email != session.email) { Text("Modifica email") }
                OutlinedTextField(password, { password = it }, label = { Text("Nuova password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Text(PasswordPolicy.requirements, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(passwordConfirmation, { passwordConfirmation = it }, label = { Text("Conferma nuova password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    isError = passwordConfirmation.isNotEmpty() && passwordConfirmation != password)
                if (passwordConfirmation.isNotEmpty() && passwordConfirmation != password)
                    Text("Le password non coincidono.", color = MaterialTheme.colorScheme.error)
                Button({ run {
                    require(password == passwordConfirmation) { "Le password non coincidono." }
                    api.updateAccount(password = password); password = ""; passwordConfirmation = ""; message = "Password aggiornata."
                } }, enabled = !busy && PasswordPolicy.error(password) == null && passwordConfirmation == password) { Text("Modifica password") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                HorizontalDivider()
                Text("Savely Android ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                TextButton({ onLegal("privacy") }) { Text("Privacy") }
                TextButton({ onLegal("terms") }) { Text("Termini di servizio") }
                TextButton({ onLegal("cookies") }) { Text("Memorizzazione locale") }
                Text("Le operazioni sincronizzate sono visibili anche nel tuo account web.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onSignOut, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Esci dall’account") }
                TextButton({ deleteDialog = true }, enabled = !busy) { Text("Elimina definitivamente l’account", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { if (!busy) deleteDialog = false },
        title = { Text("Eliminare l’account?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("L’account e i dati saranno eliminati anche da Savely web. Questa operazione non può essere annullata. Scrivi ELIMINA per continuare.")
            OutlinedTextField(deleteConfirmation, { deleteConfirmation = it }, label = { Text("Conferma") }, enabled = !busy)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = {
            TextButton({ run { api.invokeFunction("delete-account"); deleteDialog = false; onSignOut() } },
                enabled = !busy && deleteConfirmation == "ELIMINA") { Text("Elimina account") }
        }, dismissButton = { TextButton({ deleteDialog = false; deleteConfirmation = "" }, enabled = !busy) { Text("Annulla") } })
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
    var currentSessionId by remember { mutableStateOf<String?>(null) }
    var revokedSessionIds by remember { mutableStateOf(emptySet<String>()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        currentSessionId = api.session()?.authSessionId()
        factors = api.listMfaFactors()
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
        Text("Verifica in due passaggi", style = MaterialTheme.typography.titleLarge)
        if (factors.length() == 0 && enrollment == null) {
            Text("Proteggi l’accesso con un codice della tua app di autenticazione.")
            Button({ run { enrollment = api.enrollTotp() } }, enabled = !busy) { Text("Configura autenticatore") }
        }
        enrollment?.let { pending ->
            val totp = pending.getJSONObject("totp")
            Text("Aggiungi questa chiave manuale alla tua app di autenticazione:")
            androidx.compose.foundation.text.selection.SelectionContainer { Text(totp.getString("secret")) }
            TextButton({
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(totp.getString("uri")))) }
                    .onFailure { error = "Nessuna app di autenticazione disponibile. Inserisci la chiave manualmente." }
            }) { Text("Apri autenticatore") }
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Codice a 6 cifre") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Button({ run { api.verifyTotp(pending.getString("id"), code); enrollment = null; code = ""; refresh(); message = "Autenticatore attivato." } },
                enabled = !busy && code.length == 6) { Text("Attiva verifica") }
            TextButton({ run { api.unenrollTotp(pending.getString("id")); enrollment = null; code = "" } }, enabled = !busy) { Text("Annulla configurazione") }
        }
        for (index in 0 until factors.length()) {
            val factor = factors.getJSONObject(index)
            Text("Autenticatore attivo")
            TextButton({ confirmation = "factor" to factor.getString("id") }, enabled = !busy) { Text("Rimuovi autenticatore") }
        }
        HorizontalDivider()
        Text("Accessi registrati", style = MaterialTheme.typography.titleLarge)
        Text("L’elenco mostra gli accessi degli ultimi 30 giorni. Revoca l’accesso di un dispositivo per impedirne il rinnovo.", style = MaterialTheme.typography.bodySmall)
        for (index in 0 until sessions.length()) {
            val row = sessions.getJSONObject(index)
            val sessionId = revocableSessionId(row)
            if (sessionId in revokedSessionIds) continue
            ListItem(headlineContent = { Text(row.optString("user_agent").takeUnless { it == "null" || it.isBlank() } ?: "Dispositivo") },
                supportingContent = { Column {
                    Text(row.optString("created_at"))
                    if (sessionId == currentSessionId && sessionId != null) Text("Questo dispositivo")
                    if (sessionId == null) Text("Sessione non revocabile da questo elenco.")
                } }, trailingContent = {
                    TextButton({ sessionId?.let { confirmation = "session" to it } }, enabled = !busy && sessionId != null,
                        modifier = Modifier.testTag("revoke-session-${sessionId ?: "unavailable-$index"}")) { Text("Revoca") }
                })
        }
        if (sessions.length() == 0 && !busy) Text("Nessuna sessione da mostrare.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton({ run { refresh() } }, enabled = !busy) { Text("Aggiorna sicurezza") }
    }
    confirmation?.let { (kind, id) -> AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text(if (kind == "factor") "Rimuovere l’autenticatore?" else "Revocare questa sessione?") },
        text = { Text(if (kind == "factor") "La verifica in due passaggi sarà disattivata per questo autenticatore." else "Il dispositivo dovrà accedere nuovamente. Anche la sessione corrente potrebbe essere chiusa.") },
        confirmButton = { TextButton({ confirmation = null; run {
            if (kind == "factor") { api.unenrollTotp(id); refresh() }
            else if (api.revokeSession(id)) onSignOut()
            else { revokedSessionIds = revokedSessionIds + id; refresh(); message = "Accesso revocato." }
        } }) { Text("Conferma") } }, dismissButton = { TextButton({ confirmation = null }) { Text("Annulla") } }) }
}
