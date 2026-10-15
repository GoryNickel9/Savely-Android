package cc.savely.android.finance

import cc.savely.android.ui.localized
import cc.savely.android.data.couplePartnerAmount
import cc.savely.android.data.coupleSplit
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
internal fun FinanceEditor(
    section: String, id: String?, data: FinanceData, saving: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: (suspend FinanceRepository.() -> Unit) -> Unit,
    suggestion: RecurringSuggestion? = null,
) {
    if (section == "portfolio") {
        AssetEditor(id, data, saving, error, onDismiss, onSave)
        return
    }
    val tx = data.transactions.find { it.id == id }
    val cat = data.categories.find { it.id == id }
    val budget = data.budgets.find { it.id == id }
    val recurring = data.recurring.find { it.id == id }
    var name by rememberSaveable(section, id, suggestion?.key) { mutableStateOf(cat?.name ?: recurring?.name ?: suggestion?.description ?: "") }
    var amount by rememberSaveable(section, id, suggestion?.key) { mutableStateOf((tx?.amount ?: budget?.amount ?: recurring?.amount ?: suggestion?.medianAmount)?.toPlainString().orEmpty()) }
    var typeWire by rememberSaveable(section, id) { mutableStateOf((tx?.type ?: cat?.type ?: TransactionType.EXPENSE).wire) }
    var currency by rememberSaveable(section, id, suggestion?.key) { mutableStateOf(tx?.currency ?: recurring?.currency ?: suggestion?.currency ?: data.defaultCurrency) }
    var rate by rememberSaveable(section, id) { mutableStateOf(tx?.exchangeRateEur?.toPlainString() ?: if (currency == "EUR") "1" else "") }
    var categoryId by rememberSaveable(section, id) { mutableStateOf(tx?.categoryId ?: budget?.categoryId ?: recurring?.categoryId ?: "") }
    var description by rememberSaveable(section, id) { mutableStateOf(tx?.description ?: "") }
    var date by rememberSaveable(section, id, suggestion?.key) { mutableStateOf((tx?.date ?: recurring?.nextDueDate ?: suggestion?.nextDueDate ?: LocalDate.now()).toString()) }
    var icon by rememberSaveable(section, id) { mutableStateOf(cat?.icon ?: "📦") }
    var color by rememberSaveable(section, id) { mutableStateOf(cat?.color ?: "#6b7280") }
    var frequencyWire by rememberSaveable(section, id, suggestion?.key) { mutableStateOf(recurring?.frequency?.wire ?: suggestion?.frequency?.wire ?: "monthly") }
    var interval by rememberSaveable(section, id) { mutableStateOf(recurring?.weekInterval?.toString() ?: "1") }
    var active by rememberSaveable(section, id) { mutableStateOf(recurring?.active ?: true) }
    var validationError by rememberSaveable(section, id) { mutableStateOf<String?>(null) }
    var automaticRate by rememberSaveable(section, id) { mutableStateOf(id == null) }
    var shared by rememberSaveable(section, id) { mutableStateOf(false) }
    var customSplit by rememberSaveable(section, id) { mutableStateOf(false) }
    var partnerAmount by rememberSaveable(section, id) { mutableStateOf("") }
    val type = TransactionType.fromWire(typeWire)
    val frequency = RecurringFrequency.fromWire(frequencyWire)
    val availableCategories = data.categories.filter {
        it.type == type && (section != "budget" || budget?.categoryId == it.id || data.budgets.none { b -> b.categoryId == it.id })
    }

    fun save() {
        val validation = runCatching {
            if (section != "categories") parseMoney(amount, section == "budget")
            if (section == "transactions" || section == "recurring") LocalDate.parse(date)
            if (section == "transactions" && currency != "EUR" && !automaticRate) parseExchangeRate(rate)
            if (section == "transactions" && shared && type == TransactionType.EXPENSE && id == null) {
                require(data.coupleConnection != null) { "La connessione non è più attiva. Aggiorna la schermata." }
                coupleSplit(parseMoney(amount), if (customSplit) couplePartnerAmount(partnerAmount) else null)
            }
            if (section == "budget") require(categoryId.isNotEmpty()) { "Seleziona una categoria di uscita" }
            if (section == "categories" || section == "recurring") require(name.trim().isNotEmpty()) { "Inserisci il nome" }
            if (section == "categories") require(Regex("#[0-9a-fA-F]{6}").matches(color)) { "Colore non valido: usa #RRGGBB" }
            if (section == "recurring" && frequency == RecurringFrequency.WEEKLY) require(interval.toIntOrNull() in 1..520) { "Inserisci da 1 a 520 settimane" }
        }
        if (validation.isFailure) { validationError = validation.exceptionOrNull()?.message ?: "Controlla i campi"; return }
        validationError = null
        onSave {
            when (section) {
                "transactions" -> saveTransaction(id, type, amount, currency, if (automaticRate) "" else rate, categoryId.ifBlank { null }, description, date,
                    if (shared && type == TransactionType.EXPENSE && id == null) TransactionShareRequest(requireNotNull(data.coupleConnection).id,
                        data.categories.find { it.id == categoryId }?.name, if (customSplit) partnerAmount else null) else null)
                "categories" -> saveCategory(id, name, type, icon, color)
                "budget" -> saveBudget(id, categoryId, amount)
                "recurring" -> saveRecurring(id, name, amount, currency, categoryId.ifBlank { null }, frequency, interval, date, active)
            }
        }
    }

    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(localized(if (id == null) "Nuovo elemento" else "Modifica elemento")) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (section == "categories" || section == "recurring") OutlinedTextField(name, { name = it },
                    label = { Text(localized("Nome")) }, enabled = !saving, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (section == "transactions" || section == "categories") Selection("Tipo", typeWire,
                    TransactionType.entries.map { it.wire to localized(it.label) }, enabled = !saving) { typeWire = it; categoryId = "" }
                if (section != "categories") OutlinedTextField(amount, { amount = it }, label = { Text(localized("Importo")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !saving,
                    modifier = Modifier.fillMaxWidth(), singleLine = true, supportingText = { Text(localized("Arrotondamento a 2 decimali")) })
                if (section == "transactions" || section == "recurring") Selection("Valuta", currency,
                    supportedCurrencies.map { it to it }, enabled = !saving) { currency = it; rate = if (it == "EUR") "1" else "" }
                if (section == "transactions" && currency != "EUR") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(localized("Cambio automatico al salvataggio"), Modifier.weight(1f))
                        val rateLabel = localized("Cambio automatico al salvataggio")
                        Switch(checked = automaticRate, onCheckedChange = { automaticRate = it }, enabled = !saving,
                            modifier = Modifier.semantics { contentDescription = rateLabel })
                    }
                    if (automaticRate) Text(localized("Usa l’ultima quotazione disponibile, anche per una data passata. Se il servizio non risponde, scegli il cambio manuale."))
                    else OutlinedTextField(rate, { rate = it },
                    label = { Text(localized("Valore di 1 {{value0}} in EUR", "value0" to (currency))) }, enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(localized("Inserisci il cambio della data della transazione")) }, singleLine = true)
                }
                if (section != "categories" && !(section == "budget" && id != null)) {
                    Selection("Categoria", categoryId,
                        (if (section == "budget") emptyList() else listOf("" to localized("Senza categoria"))) + availableCategories.map { it.id to it.name }, enabled = !saving) { categoryId = it }
                    if (section == "budget" && availableCategories.isEmpty()) Text(localized("Crea una categoria di uscita senza budget per continuare."))
                }
                if (section == "transactions") {
                    OutlinedTextField(description, { description = it }, label = { Text(localized("Descrizione facoltativa")) },
                        enabled = !saving, modifier = Modifier.fillMaxWidth())
                    DateField("Data", date, enabled = !saving) { date = it }
                    if (id == null && type == TransactionType.EXPENSE && data.coupleConnection != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(localized("Condividi con il partner"), Modifier.weight(1f))
                            val shareLabel = localized("Condividi con il partner")
                            Switch(checked = shared, onCheckedChange = { shared = it }, enabled = !saving,
                                modifier = Modifier.semantics { contentDescription = shareLabel })
                        }
                        if (shared) {
                            Selection("Divisione", if (customSplit) "custom" else "equal",
                                listOf("equal" to localized("50 / 50 (predefinito)"), "custom" to localized("Importi personalizzati")), enabled = !saving) { customSplit = it == "custom" }
                            if (customSplit) OutlinedTextField(partnerAmount, { partnerAmount = it },
                                label = { Text(localized("Quota partner ({{currency}})", "currency" to currency)) }, enabled = !saving,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                            val split = runCatching { coupleSplit(parseMoney(amount), if (customSplit) couplePartnerAmount(partnerAmount) else null) }.getOrNull()
                            if (split != null) {
                                Text(localized("Quota tua {{mine}} · Quota partner {{partner}}", "mine" to formatMoney(split.creator, currency), "partner" to formatMoney(split.partner, currency)))
                                if (split.roundingDifference.signum() != 0) Text(localized("Le due quote arrotondate dal server differiscono dal totale di {{amount}}.", "amount" to formatMoney(split.roundingDifference, currency)))
                            }
                        }
                    }
                }
                if (section == "categories") {
                    Text(localized("Icona"))
                    categoryEmojiOptions.chunked(6).forEach { options ->
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            options.forEach { emoji -> FilterChip(icon == emoji, { icon = emoji }, enabled = !saving, label = { Text(emoji) }) }
                        }
                    }
                    Text(localized("Colore"))
                    categoryColorOptions.chunked(2).forEach { options ->
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            options.forEach { option -> FilterChip(color.equals(option, true), { color = option }, enabled = !saving,
                                leadingIcon = { Box(Modifier.size(20.dp).background(Color(0xff000000L or option.drop(1).toLong(16)), CircleShape).clearAndSetSemantics {}) },
                                label = { Text(option) }) }
                        }
                    }
                    OutlinedTextField(icon, { icon = it }, label = { Text(localized("Simbolo facoltativo")) }, enabled = !saving,
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(color, { color = it }, label = { Text(localized("Colore #RRGGBB")) }, enabled = !saving,
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                if (section == "recurring") {
                    Selection("Frequenza", frequencyWire, RecurringFrequency.entries.map { it.wire to localized(it.label) }, enabled = !saving) { frequencyWire = it }
                    if (frequency == RecurringFrequency.WEEKLY) OutlinedTextField(interval, { interval = it }, label = { Text(localized("Ogni quante settimane")) },
                        enabled = !saving, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    DateField("Prossima scadenza", date, enabled = !saving) { date = it }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(localized("Ricorrenza attiva"), Modifier.weight(1f))
                        Switch(checked = active, onCheckedChange = { active = it }, enabled = !saving)
                    }
                }
                (validationError ?: error)?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
                Text(localized("Le modifiche sono sincronizzate con l’account Savely."), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = ::save, enabled = !saving && (section != "budget" || id != null || availableCategories.isNotEmpty())) {
            Text(localized(if (saving) "Salvataggio…" else "Salva"))
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(localized("Annulla")) } })
}
