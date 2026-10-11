package cc.savely.android.finance

import cc.savely.android.ui.localized
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
import androidx.compose.material3.LinearProgressIndicator
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

@Composable
internal fun PortfolioSummary(data: FinanceData, saving: Boolean, updatePrices: () -> Unit, writeAllowed: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        portfolioComposition(data.assets, data.tcgCards).forEach { total ->
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localized("Portafoglio {{value0}}", "value0" to (formatMoney(total.total, total.currency))), style = MaterialTheme.typography.titleLarge)
                Text(localized("Valore liquido {{value0}}", "value0" to (formatMoney(total.liquid, total.currency))))
                Text(localized("Valore illiquido {{value0}}", "value0" to (formatMoney(total.illiquid, total.currency))))
                Text(localized("TCG incluso {{value0}}", "value0" to (formatMoney(total.tcg, total.currency))))
            } }
        }
        portfolioTotals(data.assets).forEach { total ->
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localized("Posizioni aperte {{value0}}", "value0" to (formatMoney(total.value, total.currency))), style = MaterialTheme.typography.titleLarge)
                Text(localized("Costo {{value0}}", "value0" to (formatMoney(total.cost, total.currency))))
                Text(localized("Profitto non realizzato {{value0}}", "value0" to (formatMoney(total.gain, total.currency))))
                Text(localized("Profitto realizzato {{value0}}", "value0" to (formatMoney(total.realized, total.currency))))
            } }
        }
        portfolioReturnSummaries(data.assets).forEach { total ->
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localized("Rendimento degli strumenti {{currency}}", "currency" to total.currency), style = MaterialTheme.typography.titleMedium)
                Text(localized("Investito in strumenti: {{amount}}", "amount" to formatMoney(total.invested, total.currency)))
                Text(localized("Profitto degli strumenti: {{amount}}", "amount" to formatMoney(total.investmentGain, total.currency)))
                Text(if (total.returnPercent == null) localized("Rendimento non disponibile: costo degli strumenti zero.")
                    else localized("Rendimento: {{percent}}", "percent" to formatDashboardPercent(total.returnPercent!!)))
                Text(localized("Profitto posizioni, immobili inclusi: {{amount}}", "amount" to formatMoney(total.gainIncludingEstate, total.currency)))
                Text(localized("Il rendimento esclude liquidità, immobili, altro e TCG. Il profitto con immobili esclude liquidità, altro e TCG."), style = MaterialTheme.typography.bodySmall)
            } }
        }
        val allocations = portfolioAllocations(data.assets, data.tcgCards)
        if (allocations.isNotEmpty()) {
            Text(localized("Allocazione per valuta"), style = MaterialTheme.typography.titleLarge)
            allocations.groupBy { it.currency }.forEach { (currency, rows) ->
                Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(currency, style = MaterialTheme.typography.titleMedium)
                    rows.forEach { row ->
                        Text(localized("{{type}}: {{amount}} · {{percent}}", "type" to localized(row.type?.label ?: "TCG"),
                            "amount" to formatMoney(row.value, currency), "percent" to (row.percent?.let(::formatDashboardPercent) ?: localized("Non disponibile"))))
                        row.percent?.let { percent -> LinearProgressIndicator(progress = {
                            percent.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
                        }, modifier = Modifier.fillMaxWidth()) }
                    }
                    val composition = portfolioComposition(data.assets, data.tcgCards).find { it.currency == currency }
                    if (composition != null && composition.total.signum() > 0) {
                        val liquidPercent = composition.liquid.multiply(BigDecimal("100")).divide(composition.total, 12, RoundingMode.HALF_UP)
                        val illiquidPercent = composition.illiquid.multiply(BigDecimal("100")).divide(composition.total, 12, RoundingMode.HALF_UP)
                        Text(localized("Liquido {{liquid}} · Illiquido {{illiquid}}", "liquid" to formatDashboardPercent(liquidPercent), "illiquid" to formatDashboardPercent(illiquidPercent)))
                    }
                } }
            }
        }
        if (data.tcgCards.isNotEmpty()) {
            Text(localized("Collezioni TCG"), style = MaterialTheme.typography.titleLarge)
            Text(localized("Carte con prezzo corrente positivo, classificate illiquide. Il risultato TCG è mostrato separatamente dal profitto delle posizioni."), style = MaterialTheme.typography.bodySmall)
            tcgPortfolioTotals(data.tcgCards).forEach { total ->
                Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(localized("{{value0}} · {{value1}} carte", "value0" to (total.label), "value1" to (total.pieces)), style = MaterialTheme.typography.titleMedium)
                    Text(localized("Valore {{value0}}", "value0" to (formatMoney(total.value, "EUR"))))
                    Text(localized("Costo {{value0}}", "value0" to (formatMoney(total.cost, "EUR"))))
                    Text(localized("Profitto/perdita {{value0}}", "value0" to (formatMoney(total.gain, "EUR"))))
                } }
            }
            val unpriced = data.tcgCards.count { it.currentPrice == null || it.currentPrice.signum() <= 0 }
            if (unpriced > 0) Text(if (unpriced == 1) localized("1 voce TCG esclusa: prezzo corrente assente o non positivo.") else localized("{{count}} voci TCG escluse: prezzo corrente assente o non positivo.", "count" to unpriced))
            val foreign = data.tcgCards.count { it.currency != "EUR" }
            if (foreign > 0) Text(if (foreign == 1) localized("1 voce TCG in valuta diversa da EUR esclusa: il servizio prezzi salva valori EUR e non permette di confrontarli con il costo originale.") else localized("{{count}} voci TCG in valuta diversa da EUR escluse: il servizio prezzi salva valori EUR e non permette di confrontarli con il costo originale.", "count" to foreign))
            Text(localized("Gestisci le carte dal modulo TCG. Il servizio settimanale esistente aggiorna i prezzi delle collezioni; il pulsante seguente aggiorna le posizioni del portafoglio."), style = MaterialTheme.typography.bodySmall)
        }
        val next = data.lastManualPriceUpdate?.plusSeconds(86_400)
        val canUpdate = next == null || next <= Instant.now()
        Button(onClick = updatePrices, enabled = canUpdate && !saving && writeAllowed) { Text(localized(if (saving) "Aggiornamento…" else "Aggiorna prezzi")) }
        if (!canUpdate) Text(localized("Aggiornamento disponibile dalle {{value0}}", "value0" to (next!!.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime())), style = MaterialTheme.typography.bodySmall)
        Text(localized("I valori seguono prezzi e valute memorizzati. La liquidità in valuta usa il cambio EUR salvato in current_price. Il prezzo può essere precedente all’ultima lettura."), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun NetWorthCard(value: NetWorthComponents) {
    Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(formatMoney(value.total, value.currency), style = MaterialTheme.typography.headlineMedium)
        Text(localized("Saldo {{value0}}", "value0" to (formatMoney(value.cashflow, value.currency))))
        Text(localized("Profitto investimenti {{value0}}", "value0" to (formatMoney(value.portfolioGain, value.currency))))
        Text(localized("Immobili al 75% {{value0}}", "value0" to (formatMoney(value.discountedRealEstate, value.currency))))
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
    var existingKey by rememberSaveable(id) { mutableStateOf("_new") }
    // Reuse identity only. A new purchase always remains a distinct row with its own quantity/cost/date.
    val existing = data.assets.filter { !it.symbol.isNullOrBlank() }.distinctBy {
        Triple(it.symbol!!.trim().uppercase(Locale.ROOT), it.type, it.currency)
    }
    fun instrumentKey(asset: FinanceAsset): String = "${asset.type.wire}|${asset.currency}|${asset.symbol!!.trim().uppercase(Locale.ROOT)}"
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

    AlertDialog(onDismissRequest = onDismiss, title = { Text(localized(if (id == null) "Nuovo acquisto" else "Modifica acquisto")) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (id == null && existing.isNotEmpty()) Selection("Strumento esistente", existingKey,
                listOf("_new" to localized("Nuovo strumento")) + existing.map { asset ->
                    instrumentKey(asset) to "${asset.name} · ${asset.symbol} · ${localized(asset.type.label)} · ${asset.currency}"
                }, enabled = !saving) { selected ->
                    existingKey = selected
                    val selectedAsset = existing.find { instrumentKey(it) == selected }
                    if (selectedAsset == null) { name = ""; symbol = ""; typeWire = "stock"; currency = "EUR" }
                    else { name = selectedAsset.name; symbol = selectedAsset.symbol.orEmpty(); typeWire = selectedAsset.type.wire; currency = selectedAsset.currency }
                }
            OutlinedTextField(name, { name = it }, label = { Text(localized("Nome")) }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
            Selection("Tipo", typeWire, FinanceAssetType.entries.map { it.wire to localized(it.label) }, enabled = !saving) { typeWire = it }
            Selection("Valuta", currency, supportedCurrencies.map { it to it }, enabled = !saving) { currency = it }
            if (!simpleValue) {
                OutlinedTextField(symbol, { symbol = it }, label = { Text(localized("Simbolo facoltativo")) }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(quantity, { quantity = it }, label = { Text(localized("Quantità")) }, enabled = !saving, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(purchasePrice, { purchasePrice = it }, label = { Text(if (simpleValue) localized("Valore totale in {{currency}}", "currency" to currency) else localized("Prezzo acquisto unitario")) },
                enabled = !saving, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            if (!simpleValue || foreignCash) OutlinedTextField(currentPrice, { currentPrice = it },
                label = { Text(if (type == FinanceAssetType.CASH && currency != "EUR") localized("Cambio: valore di 1 {{currency}} in EUR", "currency" to currency) else localized("Prezzo corrente facoltativo")) },
                enabled = !saving, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(localized("Il database salva il prezzo a 2 decimali")) })
            DateField("Acquisto", date, enabled = !saving) { date = it }
            OutlinedTextField(notes, { notes = it }, label = { Text(localized("Note facoltative")) }, enabled = !saving, modifier = Modifier.fillMaxWidth())
            (validationError ?: error)?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = ::save, enabled = !saving) { Text(localized(if (saving) "Salvataggio…" else "Salva")) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(localized("Annulla")) } })
}

@Composable
internal fun CloseAssetDialog(asset: FinanceAsset, saving: Boolean, error: String?, dismiss: () -> Unit, confirm: (String, String) -> Unit) {
    var date by rememberSaveable(asset.id) { mutableStateOf(LocalDate.now().toString()) }
    var price by rememberSaveable(asset.id) { mutableStateOf(asset.marketPrice.toPlainString()) }
    var validationError by rememberSaveable(asset.id) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(localized("Chiudi {{value0}}", "value0" to (asset.name))) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized("La chiusura riguarda questo acquisto di {{value0}} unità.", "value0" to (asset.quantity.stripTrailingZeros().toPlainString())))
            DateField("Vendita", date, enabled = !saving) { date = it }
            OutlinedTextField(price, { price = it }, label = { Text(localized("Prezzo unitario vendita in {{value0}}", "value0" to (asset.valuationCurrency))) },
                enabled = !saving, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            (validationError ?: error)?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            val validation = runCatching { validatePositionClosure(LocalDate.parse(date), parseMoney(price), asset.purchaseDate) }
            if (validation.isFailure) validationError = validation.exceptionOrNull()?.message
            else { validationError = null; confirm(date, price) }
        }) { Text(localized(if (saving) "Salvataggio…" else "Conferma chiusura")) } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !saving) { Text(localized("Annulla")) } })
}
