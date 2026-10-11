package cc.savely.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.savely.android.data.SupabaseApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun AdminScreen(api: SupabaseApi) {
    var users by remember { mutableStateOf(JSONArray()) }
    var registrations by remember { mutableStateOf<Boolean?>(null) }
    var myId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var editedPermissions by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var deleteUser by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        require(api.permissions()["admin"] == true) { "Sono necessari i permessi di amministratore." }
        myId = api.session()?.userId ?: throw IllegalArgumentException("Accedi di nuovo.")
        users = api.webPost("/api/admin", JSONObject().put("action", "get-users")).getJSONArray("users")
        registrations = api.rpc("get_registrations_enabled") as? Boolean
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Amministrazione", style = MaterialTheme.typography.headlineSmall)
        registrations?.let { enabled ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Registrazioni abilitate", modifier = Modifier.weight(1f))
                Switch(enabled, { next -> run { api.rpc("set_registrations_enabled", JSONObject().put("p_enabled", next)); refresh() } }, enabled = !busy)
            }
        }
        for (i in 0 until users.length()) {
            val user = users.getJSONObject(i)
            ListItem(headlineContent = { Text(user.optString("email").takeUnless { it == "null" || it.isBlank() } ?: user.getString("user_id")) },
                supportingContent = { Text(user.optString("full_name").takeUnless { it == "null" } ?: "") },
                trailingContent = { TextButton({
                    selected = user
                    val raw = user.optJSONObject("permissions")
                    editedPermissions = listOf("admin", "poker", "fire", "fumo", "tcg", "libreria", "couple_expenses")
                        .associateWith { raw?.opt(it) == true }
                }, enabled = !busy) { Text("Gestisci") } })
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton({ run { refresh() } }, enabled = !busy) { Text("Aggiorna utenti") }
    }
    selected?.let { user -> AlertDialog(onDismissRequest = { if (!busy) selected = null },
        title = { Text(user.optString("email")) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                editedPermissions.forEach { (key, enabled) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (key == "couple_expenses") "Coppia" else key.replaceFirstChar(Char::uppercase), Modifier.weight(1f))
                    Checkbox(enabled, { checked -> editedPermissions = editedPermissions + (key to checked) }, enabled = !busy)
                } }
                TextButton({ run {
                    api.requestPasswordReset(user.getString("email")); message = "Email di recupero inviata."; selected = null
                } }, enabled = !busy) { Text("Invia recupero password") }
                if (user.getString("user_id") != myId) TextButton({ selected = null; deleteUser = user }, enabled = !busy) { Text("Elimina utente", color = MaterialTheme.colorScheme.error) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton({ run {
            val userId = user.getString("user_id")
            val profiles = api.select("profiles", "select=id,permissions&user_id=eq.$userId")
            require(profiles.length() == 1) { "Profilo utente non disponibile." }
            val permissions = profiles.getJSONObject(0).optJSONObject("permissions") ?: JSONObject()
            editedPermissions.forEach { (key, value) -> permissions.put(key, value) }
            api.update("profiles", profiles.getJSONObject(0).getString("id"), JSONObject().put("permissions", permissions), "user_id=eq.$userId")
            selected = null; refresh(); message = "Permessi aggiornati."
        } }, enabled = !busy) { Text("Salva permessi") } }, dismissButton = { TextButton({ selected = null }, enabled = !busy) { Text("Chiudi") } }) }
    deleteUser?.let { user -> AlertDialog(onDismissRequest = { if (!busy) deleteUser = null },
        title = { Text("Eliminare questo utente?") }, text = { Column {
            Text("${user.optString("email")} e i suoi dati saranno eliminati definitivamente anche dal sito.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton({ run {
            api.webPost("/api/admin", JSONObject().put("action", "delete-user").put("userId", user.getString("user_id")))
            deleteUser = null; refresh(); message = "Utente eliminato."
        } }, enabled = !busy) { Text("Elimina definitivamente") } }, dismissButton = { TextButton({ deleteUser = null }, enabled = !busy) { Text("Annulla") } }) }
}
