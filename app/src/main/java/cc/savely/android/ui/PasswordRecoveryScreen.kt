package cc.savely.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cc.savely.android.data.PasswordPolicy

@Composable
fun PasswordRecoveryScreen(state: AppState, onSubmit: (String, String) -> Unit, onCancel: () -> Unit) {
    // Neither passwords nor their confirmation enter saved instance state.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(localized("Imposta una nuova password"), style = MaterialTheme.typography.headlineSmall)
        Text(localized("Completa il recupero prima di accedere ai dati del tuo account."))
        Text(localized(PasswordPolicy.requirements))
        OutlinedTextField(password, { password = it }, label = { Text(localized("Nuova password")) },
            modifier = Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        OutlinedTextField(confirmation, { confirmation = it }, label = { Text(localized("Conferma password")) },
            modifier = Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = confirmation.isNotEmpty() && confirmation != password)
        if (confirmation.isNotEmpty() && confirmation != password) Text(localized("Le password non coincidono."), color = MaterialTheme.colorScheme.error)
        state.error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
        Button({ onSubmit(password, confirmation) }, enabled = !state.busy && PasswordPolicy.error(password) == null && password == confirmation) {
            Text(localized(if (state.busy) "Attendi…" else "Salva nuova password"))
        }
        TextButton(onCancel, enabled = !state.busy) { Text(localized("Annulla recupero ed esci")) }
    }
}
