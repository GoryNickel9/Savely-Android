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
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import cc.savely.android.BuildConfig
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.data.PasswordPolicy
import cc.savely.android.data.authSessionId
import cc.savely.android.data.revocableSessionId
import cc.savely.android.data.ApiError
import cc.savely.android.data.ApiException
import cc.savely.android.transfer.TransferScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.io.StringReader

@Composable
fun SettingsScreen(api: SupabaseApi, session: Session, theme: String, onTheme: (String) -> Unit, onSignOut: () -> Unit, onLegal: (String) -> Unit) {
    key(session.userId, session.authSessionId()) {
        SettingsContent(api, session, theme, onTheme, onSignOut, onLegal)
    }
}

@Composable
private fun SettingsContent(api: SupabaseApi, session: Session, theme: String, onTheme: (String) -> Unit, onSignOut: () -> Unit, onLegal: (String) -> Unit) {
    val revision = remember(api, session.userId, session.authSessionId()) { api.sessionOwnerRevision(session) }
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
            try { requireSettingsOwner(api, revision); block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is ApiException && failure.kind == ApiError.PERMISSION) {
                    profile = null; deleteDialog = false; deleteConfirmation = ""
                    password = ""; passwordConfirmation = ""
                }
                error = userMessage(failure)
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(api) {
        run {
            val fresh = api.profile(expectedOwner = session.userId, expectedRevision = revision)
            requireSettingsOwner(api, revision)
            api.withCurrentSessionOwner(requireNotNull(revision)) {
                profile = fresh
                fullName = fresh?.optString("full_name")?.takeUnless { it == "null" } ?: ""
                currency = fresh?.optString("default_currency", "EUR") ?: "EUR"
            }
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
            "security" -> SecurityPane(api, session.userId, revision, Modifier.weight(1f))
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
                    api.updateProfile(JSONObject().put("full_name", fullName.trim()).put("default_currency", currency),
                        expectedOwner = session.userId, expectedRevision = revision)
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
                Button({ run { api.updateAccount(email = email, expectedOwner = session.userId, expectedRevision = revision); message = "Controlla le email di conferma prima di usare il nuovo indirizzo." } },
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
                    api.updateAccount(password = password, expectedOwner = session.userId, expectedRevision = revision); password = ""; passwordConfirmation = ""; message = "Password aggiornata."
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
                TextButton({ deleteConfirmation = ""; error = null; deleteDialog = true }, enabled = !busy) { Text(localized("Elimina definitivamente l’account"), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { if (!busy) { deleteDialog = false; deleteConfirmation = "" } },
        title = { Text(localized("Eliminare l’account?")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized("L’account e i dati saranno eliminati anche da Savely web. Questa operazione non può essere annullata. Scrivi ELIMINA per continuare."))
            OutlinedTextField(deleteConfirmation, { deleteConfirmation = it }, label = { Text(localized("Conferma")) }, enabled = !busy)
            error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = {
            TextButton({ run { api.deleteAccount(expectedOwner = session.userId, expectedRevision = revision); deleteDialog = false; deleteConfirmation = "" } },
                enabled = !busy && deleteConfirmation == "ELIMINA") { Text(localized("Elimina account")) }
        }, dismissButton = { TextButton({ deleteDialog = false; deleteConfirmation = "" }, enabled = !busy) { Text(localized("Annulla")) } })
}

@Composable
private fun SecurityPane(api: SupabaseApi, owner: String, revision: Long?, modifier: Modifier) {
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
        requireSettingsOwner(api, revision)
        val sessionId = api.session()?.authSessionId()
        val freshFactors = api.listMfaFactors(includeUnverified = true, expectedOwner = owner, expectedRevision = revision)
        val freshSessions = api.rpc("get_active_sessions", expectedOwner = owner, expectedRevision = revision) as? JSONArray
            ?: throw IllegalArgumentException("Risposta sessioni non valida.")
        api.withCurrentSessionOwner(requireNotNull(revision)) {
            currentSessionId = sessionId; factors = freshFactors; sessions = freshSessions
        }
    }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; message = null
        scope.launch {
            try { requireSettingsOwner(api, revision); block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is ApiException && failure.kind == ApiError.PERMISSION) {
                    factors = JSONArray(); sessions = JSONArray(); enrollment = null
                    code = ""; removalCode = ""; confirmation = null; currentSessionId = null
                }
                error = userMessage(failure)
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(api) { run { refresh() } }
    Column(modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(localized("Verifica in due passaggi"), style = MaterialTheme.typography.titleLarge)
        if (factors.length() == 0 && enrollment == null) {
            Text(localized("Proteggi l’accesso con un codice della tua app di autenticazione."))
            Button({ run { enrollment = api.enrollTotp(expectedOwner = owner, expectedRevision = revision) } }, enabled = !busy) { Text(localized("Configura autenticatore")) }
        }
        enrollment?.let { pending ->
            val totp = pending.getJSONObject("totp")
            TotpEnrollmentQr(totp.optString("qr_code"))
            Text(localized("Aggiungi questa chiave manuale alla tua app di autenticazione:"))
            androidx.compose.foundation.text.selection.SelectionContainer { Text(totp.getString("secret")) }
            TextButton({
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(totp.getString("uri")))) }
                    .onFailure { error = "Nessuna app di autenticazione disponibile. Inserisci la chiave manualmente." }
            }) { Text(localized("Apri autenticatore")) }
            OutlinedTextField(code, { code = it.filter { digit -> digit in '0'..'9' }.take(6) }, label = { Text(localized("Codice a 6 cifre")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Button({ run { api.verifyTotp(pending.getString("id"), code, expectedOwner = owner, expectedRevision = revision); enrollment = null; code = ""; refresh(); message = "Autenticatore attivato." } },
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
        OutlinedButton({ confirmation = "other-sessions" to "" }, enabled = !busy,
            modifier = Modifier.testTag("sign-out-other-sessions")) { Text(localized("Chiudi le altre sessioni")) }
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
            "other-sessions" -> "Chiudere le altre sessioni?"
            else -> "Revocare questa sessione?"
        })) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized(when (kind) {
                "factor" -> "Inserisci un nuovo codice della tua app di autenticazione per rimuovere la verifica in due passaggi."
                "pending-factor" -> "Rimuovi soltanto questa configurazione incompleta. Gli autenticatori già attivi resteranno disponibili."
                "other-sessions" -> "Gli altri dispositivi dovranno accedere nuovamente. Questa sessione rimane aperta."
                else -> "Il dispositivo dovrà accedere nuovamente. Anche la sessione corrente potrebbe essere chiusa."
            }))
            if (kind == "factor") {
                OutlinedTextField(removalCode, { value -> removalCode = value.filter { it in '0'..'9' }.take(6) },
                    label = { Text(localized("Codice per rimuovere l’autenticatore")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy)
            }
            if (kind == "factor" || kind == "pending-factor" || kind == "other-sessions") error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton({
            if (kind == "factor") {
                val submittedCode = removalCode
                run {
                    try {
                        api.verifyTotp(id, submittedCode, expectedOwner = owner, expectedRevision = revision)
                        api.unenrollTotp(id, expectedOwner = owner, expectedRevision = revision)
                        confirmation = null
                        refresh()
                        message = "Autenticatore rimosso."
                    } finally { removalCode = "" }
                }
            } else if (kind == "pending-factor") {
                run {
                    api.unenrollPendingTotp(id, expectedOwner = owner, expectedRevision = revision)
                    if (enrollment?.optString("id") == id) { enrollment = null; code = "" }
                    confirmation = null
                    refresh()
                    message = "Configurazione incompleta rimossa."
                }
            } else if (kind == "other-sessions") {
                run {
                    api.signOutOtherSessions(expectedOwner = owner, expectedRevision = revision)
                    confirmation = null
                    refresh()
                    message = "Altre sessioni chiuse."
                }
            } else {
                confirmation = null
                run {
                    // Revoking this session already clears it and notifies the central UI collector.
                    // Never issue another logout that could target a replacement login.
                    if (!api.revokeSession(id, expectedOwner = owner, expectedRevision = revision)) {
                        revokedSessionIds = revokedSessionIds + id; refresh(); message = "Accesso revocato."
                    }
                }
            }
        }, enabled = !busy && (kind != "factor" || removalCode.length == 6)) { Text(localized("Conferma")) } },
        dismissButton = { TextButton({ confirmation = null; removalCode = "" }, enabled = !busy) { Text(localized("Annulla")) } }) }
}

private fun requireSettingsOwner(api: SupabaseApi, revision: Long?) {
    if (revision == null || !api.isSessionOwnerCurrent(revision))
        throw ApiException(ApiError.PERMISSION, "La sessione è cambiata. Aggiorna la schermata prima di continuare.")
}

/** QR payloads contain the TOTP secret: local bytes only, no image caches or external SVG resources. */
@Composable
private fun TotpEnrollmentQr(raw: String) {
    val bytes = remember(raw) { boundedTotpSvg(raw) } ?: return
    val context = LocalContext.current
    val loader = remember(context) { ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build() }
    DisposableEffect(loader) { onDispose { loader.shutdown() } }
    val request = remember(bytes, context) {
        ImageRequest.Builder(context).data(bytes).size(256)
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
    }
    Text(localized("Scansiona questo codice con la tua app di autenticazione."))
    AsyncImage(request, localized("Codice QR per configurare l’autenticatore"), imageLoader = loader,
        modifier = Modifier.size(240.dp).testTag("totp-enrollment-qr"))
}

internal fun boundedTotpSvg(raw: String): ByteArray? = runCatching {
    require(raw.length in 1..262_144 && raw.trimStart().startsWith("<svg"))
    val bytes = raw.toByteArray(Charsets.UTF_8)
    require(bytes.size <= 262_144)
    // QR output needs only geometric shapes; reject XML entities and every reference-bearing element/attribute.
    require(!raw.contains("<!") && !raw.contains("<?"))
    val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(StringReader(raw)) }
    val number = Regex("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?(?:px|%)?")
    val geometry = Regex("[-+0-9.eE,\\sMmLlHhVvCcSsQqTtAaZz]+")
    val color = Regex("#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?(?:[0-9a-fA-F]{2})?|black|white|none")
    var elements = 0
    var roots = 0
    var rootClosed = false
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                require(!rootClosed)
                require(++elements <= 4096 && parser.depth <= 8 && parser.attributeCount <= 20)
                require(parser.name in setOf("svg", "g", "path", "rect"))
                require(parser.namespace.isNullOrEmpty() || parser.namespace == "http://www.w3.org/2000/svg")
                if (parser.depth == 1) require(parser.name == "svg" && ++roots == 1)
                else require(roots == 1)
                for (index in 0 until parser.attributeCount) {
                    require(parser.getAttributeNamespace(index).isNullOrEmpty())
                    val value = parser.getAttributeValue(index)
                    require(when (parser.getAttributeName(index)) {
                        "width", "height", "x", "y", "stroke-width" -> number.matches(value)
                        "viewBox" -> value.trim().split(Regex("[\\s,]+")).let { it.size == 4 && it.all(number::matches) }
                        "d" -> geometry.matches(value)
                        "fill", "stroke" -> color.matches(value)
                        "version" -> value == "1.0" || value == "1.1"
                        "shape-rendering" -> value in setOf("auto", "crispEdges", "geometricPrecision")
                        "stroke-linecap" -> value in setOf("butt", "round", "square")
                        "stroke-linejoin" -> value in setOf("miter", "round", "bevel")
                        else -> false
                    })
                }
            }
            XmlPullParser.END_TAG -> if (parser.depth == 1) {
                require(roots == 1 && parser.name == "svg" && !rootClosed)
                rootClosed = true
            }
            XmlPullParser.TEXT -> require(parser.text.isBlank())
            XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF, XmlPullParser.PROCESSING_INSTRUCTION -> error("Unsupported SVG")
        }
        parser.nextToken()
    }
    require(elements > 0 && roots == 1 && rootClosed)
    bytes
}.getOrNull()
