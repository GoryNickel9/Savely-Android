package cc.savely.android.finance

import android.app.DatePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.savely.android.data.SupabaseApi
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val financeTitles = mapOf(
    "dashboard" to "La tua finanza", "transactions" to "Transazioni", "categories" to "Categorie",
    "budget" to "Budget", "recurring" to "Uscite ricorrenti", "charts" to "Entrate e uscite",
    "portfolio" to "Portafoglio", "net-worth" to "Patrimonio", "insights" to "Insights",
)

private data class DeleteRequest(val table: String, val id: String, val label: String)

@Composable
fun FinanceScreen(api: SupabaseApi, section: String, modifier: Modifier = Modifier, refreshKey: Int = 0, accountKey: String = "") {
    if (section !in financeTitles) {
        Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Funzione in costruzione", style = MaterialTheme.typography.headlineSmall)
            Text("Questa sezione non è ancora disponibile nella versione Android.")
        }
        return
    }
    val applicationContext = LocalContext.current.applicationContext
    val factory = remember(api, section, applicationContext) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FinanceViewModel(FinanceRepository(api, PreferenceRecurringDismissals(applicationContext)), section) as T
        }
    }
    val model: FinanceViewModel = viewModel(key = "finance-$section-${api.hashCode()}-$accountKey", factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(model, refreshKey) { model.refresh() }
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var editorOpen by rememberSaveable(section) { mutableStateOf(false) }
    var editingId by rememberSaveable(section) { mutableStateOf<String?>(null) }
    var suggestionKey by rememberSaveable(section) { mutableStateOf<String?>(null) }
    var deleteRequest by remember { mutableStateOf<DeleteRequest?>(null) }
    var closingId by rememberSaveable { mutableStateOf<String?>(null) }
    var showClosedAssets by rememberSaveable { mutableStateOf(false) }
    var insightFilter by rememberSaveable(section) { mutableStateOf("all") }
    val data = state.data
    val suggestions = remember(data, section) { if (section == "recurring" && data != null) availableRecurringSuggestions(data) else emptyList() }
    val writable = section in setOf("transactions", "categories", "budget", "recurring", "portfolio")
    fun edit(id: String?) { editingId = id; suggestionKey = null; editorOpen = true }

    if (section == "transactions" && data != null) {
        TransactionList(data, !state.saving, { edit(it) }, {
            deleteRequest = DeleteRequest("transactions", it.id, it.description.ifBlank { "transazione del ${it.date}" })
        }, modifier) { FinanceHeading(section, state, writable, model::refresh) { edit(null) } }
    } else LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { FinanceHeading(section, state, writable, model::refresh) { edit(null) } }
        if (data == null && state.loading) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
        if (data != null) when (section) {
            "dashboard" -> {
                item { Dashboard(data) }
                item { Text("Movimenti recenti", style = MaterialTheme.typography.titleLarge) }
                items(data.transactions.take(5), key = { it.id }) { transaction ->
                    TransactionCard(transaction, data, false, {}, {})
                }
                if (data.transactions.isEmpty()) item { EmptyCard("Nessuna transazione. Aggiungi il primo movimento dalla sezione Transazioni.") }
            }
            "categories" -> {
                if (data.categories.isEmpty()) item { EmptyCard("Nessuna categoria") }
                items(data.categories, key = { it.id }) { category ->
                    FinanceRow(category.name, category.type.label, "", !state.saving,
                        { edit(category.id) }, { deleteRequest = DeleteRequest("categories", category.id, category.name) })
                }
            }
            "budget" -> {
                item { Text("Mediana dei mesi con movimenti negli ultimi 730 giorni. Importi separati per valuta.", style = MaterialTheme.typography.bodyMedium) }
                if (data.budgets.isNotEmpty()) {
                    items(budgetCurrencySummaries(data.budgets, data.transactions, LocalDate.now()), key = { "summary-${it.currency}" }) { summary ->
                        BudgetSummaryCard(summary)
                    }
                    item { Text("Il riepilogo include tutte le uscite, anche senza categoria o senza un budget dedicato.", style = MaterialTheme.typography.bodySmall) }
                }
                if (data.budgets.isEmpty()) item { EmptyCard("Nessun budget impostato") }
                items(data.budgets, key = { it.id }) { budget ->
                    val median = medianMonthlyExpense(data.transactions, LocalDate.now(), budget.categoryId, budget.currency)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FinanceRow(data.categories.find { it.id == budget.categoryId }?.name ?: "Categoria rimossa",
                            "Mediana ${formatMoney(median, budget.currency)}", formatMoney(budget.amount, budget.currency), !state.saving,
                            { edit(budget.id) }, { deleteRequest = DeleteRequest("budgets", budget.id, "budget") })
                        LinearProgressIndicator(progress = {
                            if (budget.amount.signum() == 0) { if (median.signum() > 0) 1f else 0f }
                            else median.divide(budget.amount, 6, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
                        }, modifier = Modifier.fillMaxWidth(),
                            color = if (median > budget.amount) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }
            "recurring" -> {
                item { Text("Il servizio automatico esistente elabora le scadenze. Puoi elaborare ora le ricorrenze personali scadute, una scadenza per ricorrenza.") }
                item { Button(onClick = model::processDueRecurring, enabled = !state.saving && !state.loading) {
                    Text(if (state.saving) "Operazione in corso…" else "Elabora ora")
                } }
                item { Text("Come sul sito, una transazione con lo stesso nome nel mese della scadenza impedisce una nuova creazione, anche per le ricorrenze settimanali.", style = MaterialTheme.typography.bodySmall) }
                if (suggestions.isNotEmpty()) {
                    item { Text("Suggerimenti dalla cronologia", style = MaterialTheme.typography.titleLarge) }
                    item { Text("Importi e cadenze simili in almeno tre uscite. Verifica i dettagli prima di salvare. I suggerimenti ignorati restano nascosti su questo dispositivo per questo account.", style = MaterialTheme.typography.bodySmall) }
                    items(suggestions, key = { "suggestion-${it.key}" }) { suggestion ->
                        Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(suggestion.description, style = MaterialTheme.typography.titleMedium)
                            Text("${formatMoney(suggestion.medianAmount, suggestion.currency)} · ${suggestion.frequency.label} · ${suggestion.occurrenceCount} uscite")
                            Text("Corrispondenza ${if (suggestion.highConfidence) "alta" else "media"} · Prossima scadenza proposta ${suggestion.nextDueDate}")
                            Button(onClick = { editingId = null; suggestionKey = suggestion.key; editorOpen = true }, enabled = !state.saving && !state.loading) { Text("Crea ricorrenza") }
                            TextButton(onClick = { model.dismissRecurringSuggestion(suggestion.key) }, enabled = !state.saving && !state.loading) { Text("Non proporre") }
                        } }
                    }
                    item { Text("Ricorrenze salvate", style = MaterialTheme.typography.titleLarge) }
                }
                if (data.recurring.isEmpty()) item { EmptyCard("Nessuna uscita ricorrente") }
                items(data.recurring, key = { it.id }) { recurring ->
                    Column {
                    FinanceRow(recurring.name,
                        "${recurring.nextDueDate} · ${recurring.frequency.label}" +
                            (if (recurring.frequency == RecurringFrequency.WEEKLY) " (${recurring.weekInterval} settimane)" else "") +
                            (if (recurring.active) " · Attiva" else " · In pausa"),
                        formatMoney(recurring.amount, recurring.currency), !state.saving,
                        { edit(recurring.id) }, { deleteRequest = DeleteRequest("recurring_expenses", recurring.id, recurring.name) })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${recurring.name}: ${if (recurring.active) "attiva" else "in pausa"}", Modifier.weight(1f))
                        Switch(checked = recurring.active, onCheckedChange = { model.setRecurringActive(recurring.id, it) },
                            enabled = !state.saving && !state.loading,
                            modifier = Modifier.semantics { contentDescription = "Attiva o metti in pausa ${recurring.name}" })
                    }
                    }
                }
            }
            "charts" -> item { MonthlyCharts(data.transactions) }
            "portfolio" -> {
                item { PortfolioSummary(data, state.saving, { model.mutate({ updatePrices() }) {} }) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showClosedAssets, onClick = { showClosedAssets = false }, label = { Text("Aperte") })
                    FilterChip(selected = showClosedAssets, onClick = { showClosedAssets = true }, label = { Text("Chiuse") })
                } }
                val assets = data.assets.filter { it.closed == showClosedAssets }
                if (assets.isEmpty()) item { EmptyCard("Nessuna posizione ${if (showClosedAssets) "chiusa" else "aperta"}") }
                items(assets, key = { it.id }) { asset ->
                    Column {
                        FinanceRow(asset.name, "${asset.type.label} · ${asset.symbol ?: "Senza simbolo"} · Quantità ${asset.quantity.stripTrailingZeros().toPlainString()}" +
                            (asset.soldAt?.let { " · Vendita $it" } ?: " · Acquisto ${asset.purchaseDate}"),
                            formatMoney(if (asset.closed) asset.soldPrice!! * asset.quantity else asset.marketValue, asset.valuationCurrency), !state.saving,
                            { edit(asset.id) }, { deleteRequest = DeleteRequest("portfolio_assets", asset.id, asset.name) })
                        if (!asset.closed) TextButton(onClick = { closingId = asset.id }, enabled = !state.saving) { Text("Chiudi posizione") }
                    }
                }
            }
            "net-worth" -> {
                item { Text("Formula Savely per valuta: saldo transazioni + profitto investimenti aperti + 75% del valore immobiliare.") }
                val components = netWorthComponents(data.transactions, data.assets)
                if (components.isEmpty()) item { EmptyCard("Nessun dato disponibile") }
                items(components, key = { it.currency }) { NetWorthCard(it) }
                item { Text("Storico registrato dal server", style = MaterialTheme.typography.titleLarge) }
                item { Text("Il server applica la formula web senza separare le valute. Questi valori sono mostrati come registrati e non sono direttamente confrontabili con i totali per valuta.", style = MaterialTheme.typography.bodySmall) }
                if (data.snapshots.isEmpty()) item { EmptyCard("Nessuna snapshot disponibile") }
                items(data.snapshots, key = { it.id }) { snapshot ->
                    Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(snapshot.date.toString(), style = MaterialTheme.typography.titleMedium)
                        Text(snapshot.value.setScale(2, RoundingMode.HALF_UP).toPlainString(), style = MaterialTheme.typography.titleLarge)
                    } }
                }
            }
            "insights" -> {
                item { Text("Controlli disponibili (3 su 8): budget superati, posizioni con perdita di almeno 15% e mesi di risparmio sopra la media. Gli altri rilevatori web sono in costruzione.") }
                val insights = financeInsights(data)
                val filtered = insights.filter { insightFilter == "all" || it.severity.name == insightFilter }
                item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = insightFilter == "all", onClick = { insightFilter = "all" }, label = { Text("Tutti (${insights.size})") })
                    InsightSeverity.entries.forEach { severity ->
                        val label = when (severity) { InsightSeverity.WARNING -> "Attenzione"; InsightSeverity.POSITIVE -> "Positivi"; InsightSeverity.INFO -> "Info" }
                        FilterChip(selected = insightFilter == severity.name, onClick = { insightFilter = severity.name },
                            label = { Text("$label (${insights.count { it.severity == severity }})") })
                    }
                } }
                if (filtered.isEmpty()) item { EmptyCard(if (insights.isEmpty()) "Nessun segnale nei tre controlli disponibili" else "Nessun segnale nel filtro selezionato") }
                items(filtered, key = { it.id }) { insight ->
                    FinanceInsightCard(insight)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (editorOpen && data != null) FinanceEditor(section, editingId, data, state.saving, state.error,
        onDismiss = { if (!state.saving) { editorOpen = false; suggestionKey = null } },
        onSave = { operation -> model.mutate(operation) { editorOpen = false; suggestionKey = null } },
        suggestion = suggestions.find { it.key == suggestionKey })
    if (data != null) closingId?.let { id -> data.assets.find { it.id == id }?.let { asset ->
        CloseAssetDialog(asset, state.saving, state.error, { if (!state.saving) closingId = null }) { date, price ->
            model.mutate({ closeAsset(asset, date, price) }) { closingId = null }
        }
    } }
    deleteRequest?.let { request ->
        AlertDialog(onDismissRequest = { if (!state.saving) deleteRequest = null },
            title = { Text("Eliminare ${request.label}?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (request.table == "categories")
                    "La categoria sarà rimossa e le sue transazioni resteranno senza categoria. La modifica sarà visibile anche sul sito."
                    else "La modifica sarà visibile anche sul sito Savely.")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !state.saving, onClick = {
                model.mutate({ delete(request.table, request.id) }) { deleteRequest = null }
            }) { Text(if (state.saving) "Eliminazione…" else "Elimina") } },
            dismissButton = { TextButton(enabled = !state.saving, onClick = { deleteRequest = null }) { Text("Annulla") } })
    }
}

@Composable
private fun FinanceInsightCard(insight: FinanceInsight) {
    val colors = when (insight.severity) {
        InsightSeverity.WARNING -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        InsightSeverity.POSITIVE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        InsightSeverity.INFO -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Insight ${insight.id}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(insight.title, style = MaterialTheme.typography.titleMedium)
            Surface(color = colors.first, contentColor = colors.second, shape = MaterialTheme.shapes.small) {
                Text(insight.severity.label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
            Text(insight.message)
        }
    }
}

@Composable
private fun BudgetSummaryCard(summary: BudgetCurrencySummary) {
    Card(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Riepilogo budget ${summary.currency}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Riepilogo mensile ${summary.currency}", style = MaterialTheme.typography.titleMedium)
            Text("Spesa mensile prevista: ${formatMoney(summary.totalBudget, summary.currency)}")
            Text("Spesa mediana mensile reale: ${formatMoney(summary.monthlyMedianExpense, summary.currency)}")
            Text("Differenza: ${if (summary.difference.signum() > 0) "+" else ""}${formatMoney(summary.difference, summary.currency)}",
                color = if (summary.difference.signum() > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun FinanceHeading(section: String, state: FinanceUiState, writable: Boolean, refresh: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(financeTitles.getValue(section), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = refresh, enabled = !state.loading && !state.saving) {
                Icon(Icons.Default.Refresh, contentDescription = "Aggiorna i dati")
            }
        }
        if (writable) Button(onClick = add, enabled = state.data != null && !state.saving) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(when (section) { "categories" -> "Nuova categoria"; "budget" -> "Nuovo budget"; "recurring" -> "Nuova ricorrenza"; "portfolio" -> "Nuovo acquisto"; else -> "Nuova transazione" })
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.updatedAt?.let { instant ->
            val time = DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(instant)
            Text("Ultimo aggiornamento $time", style = MaterialTheme.typography.labelMedium)
        }
        if (state.error != null) ErrorCard(state.error, refresh)
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            Text("I dati già visibili potrebbero non essere aggiornati.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = retry) { Text("Riprova") }
        }
    }
}

@Composable
private fun EmptyCard(message: String) {
    Card { Text(message, Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun Dashboard(data: FinanceData) {
    val today = LocalDate.now()
    val monthTransactions = data.transactions.filter { it.date >= today.withDayOfMonth(1) && it.date <= today }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mese corrente fino a oggi", style = MaterialTheme.typography.titleMedium)
        if (monthTransactions.isEmpty()) EmptyCard("Nessun movimento nel periodo")
        currencyTotals(monthTransactions).forEach { totals ->
            Card {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(totals.currency, style = MaterialTheme.typography.labelLarge)
                    Text(formatMoney(totals.savings, totals.currency), style = MaterialTheme.typography.headlineMedium)
                    Text("Risparmio del periodo", style = MaterialTheme.typography.bodyMedium)
                    Text("Entrate ${formatMoney(totals.income, totals.currency)}")
                    Text("Uscite ${formatMoney(totals.expenses, totals.currency)}")
                }
            }
        }
        val balances = currencyTotals(data.transactions.filter { it.date <= today })
        if (balances.isNotEmpty()) Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saldo cumulato delle transazioni", style = MaterialTheme.typography.titleMedium)
                balances.forEach { Text(formatMoney(it.savings, it.currency)) }
            }
        }
        Text("Ogni valuta è mostrata separatamente; il saldo non include il portafoglio.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FinanceRow(title: String, detail: String, amount: String, enabled: Boolean, edit: () -> Unit, delete: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (amount.isNotEmpty()) Text(amount, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(enabled = enabled, onClick = edit) { Icon(Icons.Default.Edit, "Modifica $title") }
                IconButton(enabled = enabled, onClick = delete) { Icon(Icons.Default.Delete, "Elimina $title") }
            }
        }
    }
}

@Composable
private fun TransactionCard(transaction: FinanceTransaction, data: FinanceData, editable: Boolean, edit: () -> Unit, delete: () -> Unit) {
    val category = data.categories.find { it.id == transaction.categoryId }?.name ?: "Senza categoria"
    val title = transaction.description.ifBlank { category }
    val amount = (if (transaction.type == TransactionType.INCOME) "+" else "−") + formatMoney(transaction.amount, transaction.currency)
    if (editable) FinanceRow(title, "${transaction.date} · $category · ${transaction.type.label}", amount, true, edit, delete)
    else Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(amount, style = MaterialTheme.typography.titleLarge)
        Text("${transaction.date} · $category", style = MaterialTheme.typography.bodyMedium)
    } }
}

@Composable
private fun TransactionList(data: FinanceData, enabled: Boolean, edit: (String) -> Unit, delete: (FinanceTransaction) -> Unit,
    modifier: Modifier, header: @Composable () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var period by rememberSaveable { mutableStateOf("month") }
    var category by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    var fromText by rememberSaveable { mutableStateOf(LocalDate.now().withDayOfMonth(1).toString()) }
    var toText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var year by rememberSaveable { mutableStateOf(LocalDate.now().year.toString()) }
    val today = LocalDate.now()
    val interval = runCatching {
        when (period) {
            "month" -> today.withDayOfMonth(1) to YearMonth.from(today).atEndOfMonth()
            "previous" -> YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
            "semester" -> today.minusMonths(5).withDayOfMonth(1) to YearMonth.from(today).atEndOfMonth()
            "twelve" -> today.minusMonths(11).withDayOfMonth(1) to YearMonth.from(today).atEndOfMonth()
            "year" -> LocalDate.of(year.toInt(), 1, 1) to LocalDate.of(year.toInt(), 12, 31)
            "custom" -> LocalDate.parse(fromText) to LocalDate.parse(toText)
            else -> null to null
        }
    }
    val filtered = runCatching {
        val (from, to) = interval.getOrThrow()
        filterTransactions(data.transactions, from, to, category.ifBlank { null },
            type.ifBlank { null }?.let(TransactionType::fromWire), search, data.categories)
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { header() }
        item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(search, { search = it }, label = { Text("Cerca descrizione o categoria") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Selection("Periodo", period, listOf("month" to "Questo mese", "previous" to "Mese precedente",
            "semester" to "Ultimi 6 mesi", "twelve" to "Ultimi 12 mesi", "year" to "Anno", "all" to "Tutte", "custom" to "Personalizzato")) { period = it }
        if (period == "year") OutlinedTextField(year, { year = it }, label = { Text("Anno") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        if (period == "custom") {
            DateField("Da", fromText) { fromText = it }
            DateField("A", toText) { toText = it }
        }
        Selection("Categoria", category, listOf("" to "Tutte") + data.categories.map { it.id to it.name }) { category = it }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "Tutti i tipi", "income" to "Entrate", "expense" to "Uscite").forEach { (value, label) ->
                FilterChip(selected = type == value, onClick = { type = value }, label = { Text(label) })
            }
        }
        } }
        if (filtered.isFailure) item { Text("Periodo non valido: controlla le date e l’anno.", color = MaterialTheme.colorScheme.error) }
        else {
            val transactions = filtered.getOrThrow()
            item { Text("${transactions.size} transazioni", style = MaterialTheme.typography.labelLarge) }
            if (transactions.isEmpty()) item { EmptyCard("Nessuna transazione corrisponde ai filtri") }
            items(transactions, key = { it.id }) { transaction ->
                FinanceRow(transaction.description.ifBlank { data.categories.find { it.id == transaction.categoryId }?.name ?: "Senza categoria" },
                    "${transaction.date} · ${data.categories.find { it.id == transaction.categoryId }?.name ?: "Senza categoria"}",
                    (if (transaction.type == TransactionType.INCOME) "+" else "−") + formatMoney(transaction.amount, transaction.currency), enabled,
                    { edit(transaction.id) }, { delete(transaction) })
            }
        }
    }
}

@Composable
private fun MonthlyCharts(transactions: List<FinanceTransaction>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Totali mensili per valuta", style = MaterialTheme.typography.titleMedium)
        if (transactions.isEmpty()) EmptyCard("Nessun movimento disponibile")
        transactions.groupBy { YearMonth.from(it.date) }.toSortedMap(compareByDescending { it }).forEach { (month, rows) ->
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(month.toString(), style = MaterialTheme.typography.titleLarge)
                currencyTotals(rows).forEach { totals ->
                    val max = totals.income.max(totals.expenses).max(BigDecimal.ONE)
                    Text("Entrate ${formatMoney(totals.income, totals.currency)}")
                    LinearProgressIndicator(progress = { totals.income.divide(max, 6, RoundingMode.HALF_UP).toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text("Uscite ${formatMoney(totals.expenses, totals.currency)}")
                    LinearProgressIndicator(progress = { totals.expenses.divide(max, 6, RoundingMode.HALF_UP).toFloat() }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiary)
                    Text("Netto ${formatMoney(totals.savings, totals.currency)}")
                }
            } }
        }
    }
}

@Composable
internal fun Selection(label: String, selected: String, options: List<Pair<String, String>>, enabled: Boolean = true, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${options.find { it.first == selected }?.second ?: "Seleziona"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, title) -> DropdownMenuItem(text = { Text(title) }, enabled = enabled, onClick = { choose(value); expanded = false }) }
        }
    }
}

@Composable
internal fun DateField(label: String, value: String, enabled: Boolean = true, change: (String) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        val date = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
        DatePickerDialog(context, { _, year, month, day -> change(LocalDate.of(year, month + 1, day).toString()) },
            date.year, date.monthValue - 1, date.dayOfMonth).show()
    }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("$label: $value") }
}
