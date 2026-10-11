package cc.savely.android.release

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cc.savely.android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ReleaseScreen(repository: ReleaseRepository, latest: PublishedRelease?, checked: Boolean, onChecked: (PublishedRelease?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val installed = AppVersion.parse(BuildConfig.VERSION_NAME)
    val newer = latest != null && installed != null && latest.version > installed
    fun openRelease() {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latest?.url ?: PublishedRelease.RELEASES_URL))) }
        catch (_: ActivityNotFoundException) { error = "Nessun browser disponibile per aprire il download." }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Versione e aggiornamenti", style = MaterialTheme.typography.headlineSmall)
        Text("Installata: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text(when {
            newer -> "È disponibile ${latest!!.tag}."
            latest != null -> "La versione installata è aggiornata rispetto alla release stabile ${latest.tag}."
            checked -> "Non ci sono release stabili pubbliche disponibili."
            else -> "Controlla le release stabili pubblicate su GitHub."
        })
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        Button(enabled = !busy, onClick = {
            busy = true; error = null
            scope.launch {
                try { onChecked(repository.latest()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Controllo non riuscito. Verifica la connessione." }
                finally { busy = false }
            }
        }) { Text(if (busy) "Controllo…" else "Controlla aggiornamenti") }
        OutlinedButton(::openRelease) { Text(if (newer) "Apri note e download" else "Apri le release") }
        Text("L’installazione richiede la tua conferma in Android. Gli aggiornamenti devono usare la stessa firma per conservare i dati dell’app.")
        Text("Le release private richiedono l’accesso a GitHub nel browser. Il controllo automatico vede solo le release pubbliche.")
        if (!latest?.notes.isNullOrBlank()) {
            HorizontalDivider()
            Text("Note della release", style = MaterialTheme.typography.titleMedium)
            Text(latest!!.notes)
        }
    }
}
