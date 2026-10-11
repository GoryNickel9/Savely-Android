package cc.savely.android.modules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.savely.android.ui.localized
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.math.BigDecimal

internal enum class FireCalculator(val path: String, val label: String) {
    STANDARD("/fire/standard", "Standard"), BARISTA("/fire/barista", "Barista"),
}

internal data class FireSharedConfiguration(
    val calculator: FireCalculator,
    val parameters: FireParameters,
    val inheritedKeys: List<String>,
    val unsupportedKeys: List<String>,
)

/** useFireCalculatorParams.ts uses fractional rates and these query keys, for both FIRE pages. */
internal object FireSharing {
    private val keys = listOf("age", "retire", "savings", "contrib", "income", "return", "inflation", "swr", "expenses", "parttime")
    private val numeric = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

    private fun validate(parameters: FireParameters) {
        parameters.encode()
        require(listOf(parameters.inputs.expectedReturn, parameters.inputs.inflationRate, parameters.inputs.withdrawalRate)
            .all { (it * 100).isFinite() }) { "Il link contiene un parametro FIRE non valido" }
    }

    private fun origin(webUrl: String): HttpUrl {
        val url = webUrl.toHttpUrlOrNull() ?: error("Origine web FIRE non valida")
        require((url.scheme == "https" || url.host in setOf("localhost", "127.0.0.1", "::1")) &&
            url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null) {
            "Origine web FIRE non valida"
        }
        return url
    }

    /** Include defaults too: a recipient's localStorage/DB must not replace omitted values. */
    fun link(webUrl: String, calculator: FireCalculator, parameters: FireParameters): String {
        validate(parameters)
        val input = parameters.inputs
        val values = listOf(input.currentAge.toString(), input.retirementAge.toString(),
            input.currentSavings.toString(), input.annualContribution.toString(), input.annualIncome.toString(),
            input.expectedReturn.toString(), input.inflationRate.toString(), input.withdrawalRate.toString(),
            input.annualExpenses.toString(), parameters.partTimeIncome.toString())
        return origin(webUrl).newBuilder().encodedPath(calculator.path).apply {
            keys.zip(values).forEach { (key, value) -> addQueryParameter(key, value) }
        }.build().toString()
    }

    /** Parse only, with no URL opening, network request or preference mutation.
     * Missing values are inherited from the explicitly previewed local configuration.
     * Web-only portfolio/years and other unknown keys are disclosed but never applied.
     */
    fun preview(webUrl: String, pasted: String, baseline: FireParameters): FireSharedConfiguration {
        require(pasted.length <= 8192) { "Link FIRE troppo lungo" }
        val expected = origin(webUrl)
        val url = pasted.trim().toHttpUrlOrNull() ?: error("Inserisci un link FIRE valido")
        val brandedAlias = expected.host in setOf("savely.cc", "www.savely.cc") && url.host in setOf("savely.cc", "www.savely.cc")
        require(url.scheme == expected.scheme && url.port == expected.port && (url.host == expected.host || brandedAlias) &&
            url.username.isEmpty() && url.password.isEmpty()) { "Il link deve appartenere al sito Savely configurato" }
        val calculator = FireCalculator.entries.firstOrNull { it.path == url.encodedPath.trimEnd('/') }
            ?: error("Il link deve aprire FIRE Standard o Barista")
        baseline.encode()
        val inherited = mutableListOf<String>()
        fun value(key: String, fallback: Double): Double {
            val values = url.queryParameterValues(key)
            require(values.size <= 1) { "Il link contiene parametri FIRE duplicati" }
            if (values.isEmpty()) { inherited.add(key); return fallback }
            val text = values.single().orEmpty().trim()
            require(numeric.matches(text)) { "Il link contiene un parametro FIRE non valido" }
            return text.toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: error("Il link contiene un parametro FIRE non valido")
        }
        fun age(key: String, fallback: Int): Int {
            val value = value(key, fallback.toDouble())
            require(value in 0.0..120.0 && value % 1.0 == 0.0) { "Il link contiene un'età FIRE non valida" }
            return value.toInt()
        }
        val input = baseline.inputs
        val parameters = FireParameters(FireCalculations.Inputs(
            age("age", input.currentAge), age("retire", input.retirementAge),
            value("savings", input.currentSavings), value("contrib", input.annualContribution), value("income", input.annualIncome),
            value("return", input.expectedReturn), value("inflation", input.inflationRate), value("swr", input.withdrawalRate),
            value("expenses", input.annualExpenses)), value("parttime", baseline.partTimeIncome)).also { validate(it) }
        return FireSharedConfiguration(calculator, parameters, inherited, (url.queryParameterNames - keys.toSet()).sorted())
    }
}

@Composable
internal fun FireSharingImportDialog(
    webUrl: String, baseline: FireParameters, busy: Boolean, applyError: String?,
    onEdited: () -> Unit, close: () -> Unit, apply: (FireSharedConfiguration) -> Unit,
) {
    var pasted by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<FireSharedConfiguration?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(localized("Importa configurazione FIRE")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(pasted, { pasted = it; preview = null; parseError = null; onEdited() },
                Modifier.fillMaxWidth(), label = { Text(localized("Link FIRE condiviso")) }, enabled = !busy, maxLines = 4)
            OutlinedButton(onClick = {
                val parsed = runCatching { FireSharing.preview(webUrl, pasted, baseline) }
                preview = parsed.getOrNull()
                parseError = parsed.exceptionOrNull()?.message
                onEdited()
            }, enabled = !busy && pasted.isNotBlank()) { Text(localized("Anteprima")) }
            (parseError ?: applyError)?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
            preview?.let { shared ->
                Text(localized("Anteprima della configurazione"), style = MaterialTheme.typography.titleMedium)
                Text(localized(shared.calculator.label))
                val input = shared.parameters.inputs
                fun decimal(value: Double) = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
                fun percentage(value: Double) = BigDecimal.valueOf(value).multiply(BigDecimal(100)).stripTrailingZeros().toPlainString()
                val values = listOf("Età attuale" to input.currentAge.toString(), "Età pensionamento" to input.retirementAge.toString(),
                    "Risparmi attuali (€)" to decimal(input.currentSavings), "Contributo annuo (€)" to decimal(input.annualContribution),
                    "Reddito netto annuo (€)" to decimal(input.annualIncome), "Spese annue (€)" to decimal(input.annualExpenses),
                    "Rendimento nominale (%)" to percentage(input.expectedReturn), "Inflazione (%)" to percentage(input.inflationRate),
                    "Prelievo annuo (%)" to percentage(input.withdrawalRate), "Reddito part-time annuo (€)" to decimal(shared.parameters.partTimeIncome))
                values.forEach { (label, value) -> Text(localized("{{field}}: {{value}}", "field" to localized(label), "value" to value)) }
                if (shared.inheritedKeys.isNotEmpty()) Text(localized("Parametri assenti nel link: {{keys}}. Sono mantenuti i valori locali mostrati nell'anteprima.",
                    "keys" to shared.inheritedKeys.joinToString(", ")))
                if (shared.unsupportedKeys.isNotEmpty()) Text(localized("Parametri non supportati: {{keys}}. Non verranno applicati.",
                    "keys" to shared.unsupportedKeys.joinToString(", ")))
                Text(localized("Applica sostituisce e salva i dieci parametri FIRE soltanto per questo account."))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        TextButton(onClick = { preview?.let(apply) }, enabled = !busy && preview != null) { Text(localized("Applica configurazione")) }
    }, dismissButton = { TextButton(close, enabled = !busy) { Text(localized("Annulla")) } })
}
