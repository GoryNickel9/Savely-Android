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
import cc.savely.android.ui.localized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ReleaseScreen(repository: ReleaseRepository, latest: PublishedRelease?, checked: Boolean, onChecked: (PublishedRelease?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val installed = AppVersion.parse(BuildConfig.VERSION_NAME)
    val previewChannel = installed?.preview == true
    val newer = latest != null && installed != null && latest.version > installed
    fun openRelease() {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latest?.url ?: PublishedRelease.RELEASES_URL))) }
        catch (_: ActivityNotFoundException) { error = "Nessun browser disponibile per aprire il download." }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(localized("Versione e aggiornamenti"), style = MaterialTheme.typography.headlineSmall)
        Text(localized("Installata: {{version}} ({{code}})", "version" to BuildConfig.VERSION_NAME, "code" to BuildConfig.VERSION_CODE))
        Text(when {
            newer -> localized("È disponibile {{version}}.", "version" to latest!!.tag)
            latest != null -> localized(if (latest.version.preview) "La versione installata è aggiornata rispetto all’anteprima {{version}}." else "La versione installata è aggiornata rispetto alla release stabile {{version}}.", "version" to latest.tag)
            checked -> localized(if (previewChannel) "Non ci sono release pubbliche disponibili." else "Non ci sono release stabili pubbliche disponibili.")
            else -> localized(if (previewChannel) "Questa alpha controlla anche le anteprime pubblicate su GitHub." else "Controlla le release stabili pubblicate su GitHub.")
        })
        if (error != null) Text(localized(error!!), color = MaterialTheme.colorScheme.error)
        Button(enabled = !busy, onClick = {
            busy = true; error = null
            scope.launch {
                try { onChecked(repository.latest()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Controllo non riuscito. Verifica la connessione." }
                finally { busy = false }
            }
        }) { Text(localized(if (busy) "Controllo…" else "Controlla aggiornamenti")) }
        OutlinedButton(::openRelease) { Text(localized(if (newer) "Apri note e download" else "Apri le release")) }
        Text(localized("L’installazione richiede la tua conferma in Android. Gli aggiornamenti devono usare la stessa firma per conservare i dati dell’app."))
        Text(localized("APK e note sono pubblici; il controllo aggiornamenti non richiede credenziali. Le versioni stabili escludono le anteprime."))
        if (!latest?.notes.isNullOrBlank()) {
            HorizontalDivider()
            Text(localized("Note della release"), style = MaterialTheme.typography.titleMedium)
            Text(latest!!.notes)
        }
    }
}
