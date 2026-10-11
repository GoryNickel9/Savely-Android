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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate

@Composable
internal fun PortfolioSummary(data: FinanceData, saving: Boolean, updatePrices: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        portfolioComposition(data.assets, data.tcgCards).forEach { total ->
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Portafoglio ${formatMoney(total.total, total.currency)}", style = MaterialTheme.typography.titleLarge)
                Text("Valore liquido ${formatMoney(total.liquid, total.currency)}")
                Text("Valore illiquido ${formatMoney(total.illiquid, total.currency)}")
                Text("TCG incluso ${formatMoney(total.tcg, total.currency)}")
            } }
        }
        portfolioTotals(data.assets).forEach { total ->
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Posizioni aperte ${formatMoney(total.value, total.currency)}", style = MaterialTheme.typography.titleLarge)
                Text("Costo ${formatMoney(total.cost, total.currency)}")
                Text("Profitto non realizzato ${formatMoney(total.gain, total.currency)}")
                Text("Profitto realizzato ${formatMoney(total.realized, total.currency)}")
            } }
        }
        if (data.tcgCards.isNotEmpty()) {
            Text("Collezioni TCG", style = MaterialTheme.typography.titleLarge)
            Text("Carte con prezzo corrente positivo, classificate illiquide. Il risultato TCG è mostrato separatamente dal profitto delle posizioni.", style = MaterialTheme.typography.bodySmall)
            tcgPortfolioTotals(data.tcgCards).forEach { total ->
                Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${total.label} · ${total.pieces} carte", style = MaterialTheme.typography.titleMedium)
                    Text("Valore ${formatMoney(total.value, "EUR")}")
                    Text("Costo ${formatMoney(total.cost, "EUR")}")
                    Text("Profitto/perdita ${formatMoney(total.gain, "EUR")}")
                } }
            }
            val unpriced = data.tcgCards.count { it.currentPrice == null || it.currentPrice.signum() <= 0 }
            if (unpriced > 0) Text((if (unpriced == 1) "1 voce TCG esclusa" else "$unpriced voci TCG escluse") + ": prezzo corrente assente o non positivo.")
            val foreign = data.tcgCards.count { it.currency != "EUR" }
            if (foreign > 0) Text((if (foreign == 1) "1 voce TCG in valuta diversa da EUR esclusa" else "$foreign voci TCG in valuta diversa da EUR escluse") + ": il servizio prezzi salva valori EUR e non permette di confrontarli con il costo originale.")
            Text("Gestisci le carte dal modulo TCG. Il servizio settimanale esistente aggiorna i prezzi delle collezioni; il pulsante seguente aggiorna le posizioni del portafoglio.", style = MaterialTheme.typography.bodySmall)
        }
        val next = data.lastManualPriceUpdate?.plusSeconds(86_400)
        val canUpdate = next == null || next <= Instant.now()
        Button(onClick = updatePrices, enabled = canUpdate && !saving) { Text(if (saving) "Aggiornamento…" else "Aggiorna prezzi") }
        if (!canUpdate) Text("Aggiornamento disponibile dalle ${next!!.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()}", style = MaterialTheme.typography.bodySmall)
        Text("I valori seguono prezzi e valute memorizzati. La liquidità in valuta usa il cambio EUR salvato in current_price. Il prezzo può essere precedente all’ultima lettura.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun NetWorthCard(value: NetWorthComponents) {
    Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(formatMoney(value.total, value.currency), style = MaterialTheme.typography.headlineMedium)
        Text("Saldo ${formatMoney(value.cashflow, value.currency)}")
        Text("Profitto investimenti ${formatMoney(value.portfolioGain, value.currency)}")
        Text("Immobili al 75% ${formatMoney(value.discountedRealEstate, value.currency)}")
    } }
}

@Composable
internal fun AssetEditor(id: String?, data: FinanceData, saving: Boolean, error: String?, onDismiss: () -> Unit,
    onSave: (suspend FinanceRepository.() -> Unit) -> Unit) {
    val asset = data.assets.find { it.id == id }
    var name by rememberSaveable(id) { mutableStateOf(asset?.name ?: "") }
    var symbol by rememberSaveable(id) { mutableStateOf(asset?.symbol ?: "") }
    var typeWire by rememberSaveable(id) { mutableStateOf(asset?.type?.wire ?: "stock") }
    var quantity by rememberSaveable(id) { mutableStateOf(asset?.quantity?.toPlainString() ?: "") }
    var purchasePrice by rememberSaveable(id) { mutableStateOf(asset?.purchasePrice?.toPlainString() ?: "") }
    var currentPrice by rememberSaveable(id) { mutableStateOf(asset?.currentPrice?.toPlainString() ?: "") }
    var currency by rememberSaveable(id) { mutableStateOf(asset?.currency ?: "EUR") }
    var date by rememberSaveable(id) { mutableStateOf((asset?.purchaseDate ?: LocalDate.now()).toString()) }
    var notes by rememberSaveable(id) { mutableStateOf(asset?.notes ?: "") }
    var validationError by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val type = FinanceAssetType.fromWire(typeWire)
    val simpleValue = id == null && type in setOf(FinanceAssetType.CASH, FinanceAssetType.REAL_ESTATE, FinanceAssetType.OTHER)
    val foreignCash = simpleValue && type == FinanceAssetType.CASH && currency != "EUR"

    fun save() {
        val normalizedQuantity = if (simpleValue) { if (foreignCash) purchasePrice else "1" } else quantity
        val normalizedPrice = if (foreignCash) "1" else purchasePrice
        val normalizedCurrent = if (simpleValue && !foreignCash) purchasePrice else currentPrice
        val validation = runCatching {
            require(name.trim().isNotEmpty()) { "Inserisci il nome" }
            parseQuantity(normalizedQuantity)
            parseMoney(normalizedPrice, true)
            if (normalizedCurrent.isNotBlank()) parseMoney(normalizedCurrent, true)
            if (foreignCash) require(parseMoney(normalizedCurrent).signum() > 0) { "Inserisci il cambio EUR" }
            LocalDate.parse(date)
        }
        if (validation.isFailure) { validationError = validation.exceptionOrNull()?.message ?: "Controlla i campi"; return }
        validationError = null
        onSave { saveAsset(id, name, if (simpleValue) "" else symbol, type, normalizedQuantity,
            normalizedPrice, normalizedCurrent, currency, date, notes) }
    }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (id == null) "Nuovo acquisto" else "Modifica acquisto") },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nome") }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
            Selection("Tipo", typeWire, FinanceAssetType.entries.map { it.wire to it.label }, enabled = !saving) { typeWire = it }
            Selection("Valuta", currency, supportedCurrencies.map { it to it }, enabled = !saving) { currency = it }
            if (!simpleValue) {
                OutlinedTextField(symbol, { symbol = it }, label = { Text("Simbolo facoltativo") }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(quantity, { quantity = it }, label = { Text("Quantità") }, enabled = !saving, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(purchasePrice, { purchasePrice = it }, label = { Text(if (simpleValue) "Valore totale in $currency" else "Prezzo acquisto unitario") },
                enabled = !saving, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            if (!simpleValue || foreignCash) OutlinedTextField(currentPrice, { currentPrice = it },
                label = { Text(if (type == FinanceAssetType.CASH && currency != "EUR") "Cambio: valore di 1 $currency in EUR" else "Prezzo corrente facoltativo") },
                enabled = !saving, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Il database salva il prezzo a 2 decimali") })
            DateField("Acquisto", date, enabled = !saving) { date = it }
            OutlinedTextField(notes, { notes = it }, label = { Text("Note facoltative") }, enabled = !saving, modifier = Modifier.fillMaxWidth())
            (validationError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = ::save, enabled = !saving) { Text(if (saving) "Salvataggio…" else "Salva") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Annulla") } })
}

@Composable
internal fun CloseAssetDialog(asset: FinanceAsset, saving: Boolean, error: String?, dismiss: () -> Unit, confirm: (String, String) -> Unit) {
    var date by rememberSaveable(asset.id) { mutableStateOf(LocalDate.now().toString()) }
    var price by rememberSaveable(asset.id) { mutableStateOf(asset.marketPrice.toPlainString()) }
    var validationError by rememberSaveable(asset.id) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Chiudi ${asset.name}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("La chiusura riguarda questo acquisto di ${asset.quantity.stripTrailingZeros().toPlainString()} unità.")
            DateField("Vendita", date, enabled = !saving) { date = it }
            OutlinedTextField(price, { price = it }, label = { Text("Prezzo unitario vendita in ${asset.valuationCurrency}") },
                enabled = !saving, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            (validationError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            val validation = runCatching { validatePositionClosure(LocalDate.parse(date), parseMoney(price), asset.purchaseDate) }
            if (validation.isFailure) validationError = validation.exceptionOrNull()?.message
            else { validationError = null; confirm(date, price) }
        }) { Text(if (saving) "Salvataggio…" else "Conferma chiusura") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !saving) { Text("Annulla") } })
}
