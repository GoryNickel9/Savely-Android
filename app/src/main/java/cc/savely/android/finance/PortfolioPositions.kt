package cc.savely.android.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.savely.android.ui.localized
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

data class PortfolioPositionGroup(val assets: List<FinanceAsset>) {
    val first: FinanceAsset get() = assets.first()
    val quantity: BigDecimal get() = assets.fold(BigDecimal.ZERO) { total, asset -> total + asset.quantity }
    val cost: BigDecimal get() = assets.fold(BigDecimal.ZERO) { total, asset -> total + asset.cost }
    val marketValue: BigDecimal get() = assets.fold(BigDecimal.ZERO) { total, asset -> total + asset.marketValue }
}

/** Unlike the web's symbol-only grouping, types and currencies are never merged. */
fun portfolioPositionGroups(assets: List<FinanceAsset>): List<PortfolioPositionGroup> = assets.groupBy {
    val symbol = it.symbol?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
    listOf(if (symbol == null) "id:${it.id}" else "symbol:$symbol", it.type.wire, it.currency, it.closed.toString())
}.values.map(::PortfolioPositionGroup)

internal fun samePositionPurchase(expected: FinanceAsset, actual: FinanceAsset): Boolean =
    expected.id == actual.id && !actual.closed && expected.type == actual.type && expected.currency == actual.currency &&
        expected.symbol == actual.symbol && expected.purchaseDate == actual.purchaseDate &&
        expected.quantity.compareTo(actual.quantity) == 0 && expected.purchasePrice.compareTo(actual.purchasePrice) == 0 &&
        expected.soldAt == actual.soldAt && ((expected.soldPrice == null && actual.soldPrice == null) ||
        (expected.soldPrice != null && actual.soldPrice != null && expected.soldPrice.compareTo(actual.soldPrice) == 0))

class PositionCloseException(val confirmed: Int, val total: Int, val uncertainId: String, cause: Exception,
    val attemptedWrite: Boolean = true) : Exception(if (attemptedWrite)
    "Chiusura interrotta. Aggiorna i dati e verifica gli acquisti prima di riprovare: la richiesta fallita può essere stata salvata."
    else "Verifica degli acquisti non riuscita. Nessuna chiusura inviata: aggiorna i dati prima di continuare.", cause)

@Composable
internal fun PortfolioGroupClosures(assets: List<FinanceAsset>, saving: Boolean, select: (PortfolioPositionGroup) -> Unit) {
    val groups = remember(assets) { portfolioPositionGroups(assets.filterNot { it.closed }).filter { it.assets.size > 1 } }
    if (groups.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(localized("Chiusura collettiva"), style = MaterialTheme.typography.titleLarge)
        groups.forEach { group ->
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${group.first.name} · ${group.first.symbol.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                Text(localized("{{count}} acquisti · {{quantity}} unità · {{currency}}", "count" to group.assets.size,
                    "quantity" to group.quantity.stripTrailingZeros().toPlainString(), "currency" to group.first.currency))
                Text(localized("Costo {{amount}}", "amount" to formatMoney(group.cost, group.first.valuationCurrency)))
                Text(localized("Valore {{amount}}", "amount" to formatMoney(group.marketValue, group.first.valuationCurrency)))
                TextButton(enabled = !saving, onClick = { select(group) }) { Text(localized("Chiudi tutti gli acquisti")) }
            } }
        }
    }
}

@Composable
internal fun CloseAssetGroupDialog(group: PortfolioPositionGroup, saving: Boolean, error: String?, failure: PositionCloseException?,
    dismiss: () -> Unit, refreshAndDismiss: () -> Unit, confirm: (String, String) -> Unit) {
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    // A common sale price is explicit; differing quote snapshots are not averaged into a market price.
    var price by rememberSaveable { mutableStateOf(group.assets.map { it.marketPrice }.distinctBy { it.stripTrailingZeros() }
        .singleOrNull()?.toPlainString().orEmpty()) }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!saving) dismiss() },
        title = { Text(localized("Chiudi {{name}}", "name" to group.first.name)) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized("Verranno chiusi {{count}} acquisti, per {{quantity}} unità. Ogni acquisto riceverà la stessa data e lo stesso prezzo unitario di vendita.",
                "count" to group.assets.size, "quantity" to group.quantity.stripTrailingZeros().toPlainString()))
            Text(localized("Le scritture sono separate: un errore può lasciare la posizione chiusa solo in parte. Non sono previsti tentativi automatici."), style = MaterialTheme.typography.bodySmall)
            DateField("Vendita", date, enabled = !saving && !submitted) { date = it }
            OutlinedTextField(price, { price = it }, label = { Text(localized("Prezzo unitario vendita in {{currency}}", "currency" to group.first.valuationCurrency)) },
                singleLine = true, enabled = !saving && !submitted, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            group.assets.forEach { asset -> Text(localized("{{date}} · {{quantity}} unità · Costo {{cost}}", "date" to asset.purchaseDate,
                "quantity" to asset.quantity.stripTrailingZeros().toPlainString(), "cost" to formatMoney(asset.cost, asset.valuationCurrency))) }
            (validationError ?: error?.takeIf { submitted })?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
            failure?.takeIf { submitted }?.let {
                Text(localized("Chiusure confermate: {{confirmed}} su {{total}}. Verifica anche l’acquisto {{id}} dopo il refresh.",
                    "confirmed" to it.confirmed, "total" to it.total, "id" to it.uncertainId), color = MaterialTheme.colorScheme.error)
            }
        } },
        confirmButton = {
            if (submitted && !saving) TextButton(onClick = refreshAndDismiss) { Text(localized("Aggiorna e verifica")) }
            else TextButton(enabled = !saving && !submitted, onClick = {
                val validation = runCatching {
                    val parsed = parseMoney(price)
                    group.assets.forEach { validatePositionClosure(LocalDate.parse(date), parsed, it.purchaseDate) }
                }
                if (validation.isFailure) validationError = validation.exceptionOrNull()?.message
                else { validationError = null; submitted = true; confirm(date, price) }
            }) { Text(localized(if (saving) "Salvataggio…" else "Conferma chiusura collettiva")) }
        }, dismissButton = { TextButton(enabled = !saving, onClick = dismiss) { Text(localized("Annulla")) } })
}
