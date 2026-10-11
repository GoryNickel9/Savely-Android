package cc.savely.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(document: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember(document) { mutableStateOf<String?>(null) }
    LaunchedEffect(document) {
        require(document in setOf("privacy", "terms", "cookies"))
        text = withContext(Dispatchers.IO) { context.assets.open("legal/$document.txt").bufferedReader().use { it.readText() } }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(localized(when (document) { "privacy" -> "Privacy"; "terms" -> "Termini"; else -> "Memorizzazione locale" })) },
        navigationIcon = { TextButton(onBack) { Text(localized("Indietro")) } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(localized("Documento originale Savely web in italiano, versione 27 agosto 2026."), style = MaterialTheme.typography.bodySmall)
            if (document == "cookies") Text(localized("Su Android la sessione è cifrata con Android Keystore e salvata nello spazio privato dell’app; il tema è una preferenza locale. L’app non usa cookie del browser, analytics o pubblicità."))
            text?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyLarge) } } ?: CircularProgressIndicator()
        }
    }
}
