package cc.savely.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.savely.android.data.PasswordPolicy

@Composable
fun AuthScreen(
    state: AppState,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit,
    onReset: (String) -> Unit,
    onRetry: () -> Unit,
    onLegal: ((String) -> Unit)? = null,
) {
    var mode by rememberSaveable { mutableStateOf("login") }
    var email by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    // Passwords are never written into saved instance state.
    var password by remember { mutableStateOf("") }
    var passwordConfirmation by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Savely", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text("I tuoi conti, sempre con te.", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text(when (mode) { "signup" -> "Crea il tuo account"; "reset" -> "Recupera la password"; else -> "Accedi" },
                style = MaterialTheme.typography.headlineSmall)
            if (mode == "signup") OutlinedTextField(name, { name = it }, label = { Text("Nome completo") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
            OutlinedTextField(email, { email = it }, label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
            if (mode != "reset") OutlinedTextField(password, { password = it }, label = { Text("Password") },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { TextButton({ showPassword = !showPassword }) { Text(if (showPassword) "Nascondi" else "Mostra") } },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
            if (mode == "signup") {
                Text(PasswordPolicy.requirements, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(passwordConfirmation, { passwordConfirmation = it }, label = { Text("Conferma password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy,
                    isError = passwordConfirmation.isNotEmpty() && passwordConfirmation != password)
                if (passwordConfirmation.isNotEmpty() && passwordConfirmation != password)
                    Text("Le password non coincidono.", color = MaterialTheme.colorScheme.error)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (mode == "signup") Text("La conferma email apre il sito Savely. Dopo la conferma puoi accedere qui.",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                when (mode) { "signup" -> onSignUp(email, password, name); "reset" -> onReset(email); else -> onSignIn(email, password) }
            }, enabled = !state.busy && email.isNotBlank() && when (mode) {
                "signup" -> PasswordPolicy.error(password) == null && passwordConfirmation == password
                "reset" -> true
                else -> password.isNotBlank()
            },
                modifier = Modifier.fillMaxWidth()) {
                Text(when { state.busy -> "Attendi…"; mode == "signup" -> "Crea account"; mode == "reset" -> "Invia email di recupero"; else -> "Accedi" })
            }
            if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            if (mode == "login") {
                TextButton({ mode = "reset" }) { Text("Password dimenticata?") }
                TextButton({ mode = "signup" }) { Text("Crea un account") }
            } else TextButton({ mode = "login"; password = ""; passwordConfirmation = "" }) { Text("Torna all’accesso") }
            if (state.error != null && mode == "login") TextButton(onRetry, enabled = !state.busy) { Text("Ripristina sessione") }
            if (onLegal != null) {
                TextButton({ onLegal("privacy") }) { Text("Privacy") }
                TextButton({ onLegal("terms") }) { Text("Termini di servizio") }
            }
        }
    }
}

@Composable
fun MfaChallengeScreen(state: AppState, onVerify: (String) -> Unit, onSignOut: () -> Unit) {
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Verifica in due passaggi", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("Inserisci il codice della tua app di autenticazione.")
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Codice a 6 cifre") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
            modifier = Modifier.padding(vertical = 16.dp), enabled = !state.busy)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({ onVerify(code) }, enabled = !state.busy && code.length == 6) { Text("Verifica") }
        TextButton(onSignOut, enabled = !state.busy) { Text("Esci") }
    }
}
