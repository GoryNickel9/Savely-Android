package cc.savely.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(document: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val language = LocalAppLanguage.current
    var current by rememberSaveable(document) { mutableStateOf(document) }
    var retry by remember { mutableIntStateOf(0) }
    var text by remember(current, language) { mutableStateOf<String?>(null) }
    var links by remember(current, language) { mutableStateOf(emptyList<Pair<String, String>>()) }
    var error by remember(current, language) { mutableStateOf(false) }
    var linkError by remember(current, language) { mutableStateOf(false) }
    val back = { if (current == document) onBack() else current = document }
    BackHandler(current != document, back)
    LaunchedEffect(current, language, retry) {
        require(current in setOf("privacy", "terms", "cookies"))
        try {
            val prefix = if (language == AppLanguage.ENGLISH) "legal/en" else "legal"
            val content = withContext(Dispatchers.IO) {
                val body = context.assets.open("$prefix/$current.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = context.assets.open("$prefix/$current-links.json").bufferedReader(Charsets.UTF_8).use { JSONArray(it.readText()) }
                body to (0 until json.length()).map { json.getJSONObject(it).let { link -> link.getString("label") to link.getString("href") } }
            }
            text = content.first; links = content.second; error = false
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(localized(when (current) { "privacy" -> "Privacy"; "terms" -> "Termini"; else -> "Memorizzazione locale" })) },
        navigationIcon = { TextButton(back) { Text(localized("Indietro")) } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(localized("Documento Savely web, versione 27 agosto 2026."), style = MaterialTheme.typography.bodySmall)
            if (current == "cookies") Text(localized("Su Android la sessione è cifrata con Android Keystore e salvata nello spazio privato dell’app; il tema è una preferenza locale. L’app non usa cookie del browser, analytics o pubblicità."))
            if (error) {
                Text(localized("Documento non disponibile. Riprova."), color = MaterialTheme.colorScheme.error)
                Button({ retry++ }) { Text(localized("Riprova")) }
            } else text?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyLarge) } } ?: CircularProgressIndicator()
            links.forEach { (label, href) ->
                TextButton(onClick = {
                    when (href) {
                        "/privacy" -> current = "privacy"
                        "/terms" -> current = "terms"
                        "/cookies" -> current = "cookies"
                        else -> runCatching { uriHandler.openUri(href) }.onFailure { linkError = true }
                    }
                }) { Text(label) }
            }
            if (linkError) Text(localized("Impossibile aprire il collegamento."), color = MaterialTheme.colorScheme.error)
        }
    }
}
