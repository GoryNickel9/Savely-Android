package cc.savely.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.savely.android.data.SupabaseApi
import cc.savely.android.data.ApiError
import cc.savely.android.data.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun AdminScreen(api: SupabaseApi) {
    var users by remember { mutableStateOf(JSONArray()) }
    var registrations by remember { mutableStateOf<Boolean?>(null) }
    var myId by remember { mutableStateOf("") }
    var loadedRevision by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var editedPermissions by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var deleteUser by remember { mutableStateOf<JSONObject?>(null) }
    var resetUser by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    fun clearAdminState() {
        users = JSONArray()
        registrations = null
        myId = ""
        loadedRevision = null
        selected = null
        deleteUser = null
        resetUser = null
        editedPermissions = emptyMap()
        message = null
    }
    suspend fun refresh() {
        val owner = api.session() ?: throw ApiException(ApiError.PERMISSION, "Accedi di nuovo.")
        val revision = api.sessionOwnerRevision(owner) ?: throw ApiException(ApiError.PERMISSION, "Accedi di nuovo.")
        if (api.permissions()["admin"] != true) {
            throw ApiException(ApiError.PERMISSION, "Sono necessari i permessi di amministratore.")
        }
        val freshUsers = api.webPost("/api/admin", JSONObject().put("action", "get-users"),
            expectedOwner = owner.userId, expectedRevision = revision).getJSONArray("users")
        val freshRegistrations = api.rpc("get_registrations_enabled", expectedOwner = owner.userId, expectedRevision = revision) as? Boolean
        api.withCurrentSessionOwner(revision) {
            myId = owner.userId; loadedRevision = revision
            users = freshUsers; registrations = freshRegistrations
        }
    }
    suspend fun actionOwner(): Pair<String, Long> {
        val owner = api.session()
        val revision = loadedRevision
        if (owner?.userId != myId || revision == null || !api.isSessionOwnerCurrent(revision)) {
            throw ApiException(ApiError.PERMISSION, "La sessione è cambiata. Aggiorna la schermata prima di continuare.")
        }
        return myId to revision
    }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; message = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is ApiException && (failure.kind == ApiError.PERMISSION || failure.status == 403)) clearAdminState()
                error = userMessage(failure)
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(api) { run { refresh() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(localized("Amministrazione"), style = MaterialTheme.typography.headlineSmall)
        registrations?.let { enabled ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(localized("Registrazioni abilitate"), modifier = Modifier.weight(1f))
                Switch(enabled, { next -> run {
                    val (owner, revision) = actionOwner()
                    api.rpc("set_registrations_enabled", JSONObject().put("p_enabled", next), expectedOwner = owner, expectedRevision = revision)
                    refresh()
                } }, enabled = !busy)
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
                }, enabled = !busy) { Text(localized("Gestisci")) } })
        }
        error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
        message?.let { Text(localized(it), color = MaterialTheme.colorScheme.primary) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton({ run { refresh() } }, enabled = !busy) { Text(localized("Aggiorna utenti")) }
    }
    selected?.let { user -> AlertDialog(onDismissRequest = { if (!busy) selected = null },
        title = { Text(user.optString("email")) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                editedPermissions.forEach { (key, enabled) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(localized(if (key == "couple_expenses") "Coppia" else key.replaceFirstChar(Char::uppercase)), Modifier.weight(1f))
                    Checkbox(enabled, { checked -> editedPermissions = editedPermissions + (key to checked) }, enabled = !busy)
                } }
                TextButton({ selected = null; resetUser = user },
                    enabled = !busy && !user.isNull("email") && user.optString("email").isNotBlank()) { Text(localized("Invia recupero password")) }
                if (user.getString("user_id") != myId) TextButton({ selected = null; deleteUser = user }, enabled = !busy) { Text(localized("Elimina utente"), color = MaterialTheme.colorScheme.error) }
                error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton({ run {
            val (owner, revision) = actionOwner()
            val userId = user.getString("user_id")
            val profiles = api.select("profiles", "select=id,permissions&user_id=eq.$userId")
            require(profiles.length() == 1) { "Profilo utente non disponibile." }
            val permissions = profiles.getJSONObject(0).optJSONObject("permissions") ?: JSONObject()
            editedPermissions.forEach { (key, value) -> permissions.put(key, value) }
            api.update("profiles", profiles.getJSONObject(0).getString("id"), JSONObject().put("permissions", permissions), "user_id=eq.$userId",
                expectedOwner = owner, expectedRevision = revision)
            selected = null; refresh(); message = "Permessi aggiornati."
        } }, enabled = !busy) { Text(localized("Salva permessi")) } }, dismissButton = { TextButton({ selected = null }, enabled = !busy) { Text(localized("Chiudi")) } }) }
    resetUser?.let { user -> AlertDialog(onDismissRequest = { if (!busy) resetUser = null },
        title = { Text(localized("Inviare il recupero password?")) },
        text = { Column {
            Text(localized("Verrà inviata un’email di recupero a {{email}}. La password attuale non viene modificata.", "email" to user.getString("email")))
            error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton({ run {
            val (owner, revision) = actionOwner()
            api.requestPasswordReset(user.getString("email"), expectedOwner = owner, expectedRevision = revision)
            resetUser = null; message = "Email di recupero inviata."
        } }, enabled = !busy) { Text(localized("Invia email")) } },
        dismissButton = { TextButton({ resetUser = null }, enabled = !busy) { Text(localized("Annulla")) } }) }
    deleteUser?.let { user -> AlertDialog(onDismissRequest = { if (!busy) deleteUser = null },
        title = { Text(localized("Eliminare questo utente?")) }, text = { Column {
            Text(localized("{{value0}} e i suoi dati saranno eliminati definitivamente anche dal sito.", "value0" to (user.optString("email"))))
            error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton({ run {
            val (owner, revision) = actionOwner()
            api.webPost("/api/admin", JSONObject().put("action", "delete-user").put("userId", user.getString("user_id")),
                expectedOwner = owner, expectedRevision = revision)
            deleteUser = null; refresh(); message = "Utente eliminato."
        } }, enabled = !busy) { Text(localized("Elimina definitivamente")) } }, dismissButton = { TextButton({ deleteUser = null }, enabled = !busy) { Text(localized("Annulla")) } }) }
}
