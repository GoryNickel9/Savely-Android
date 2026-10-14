package cc.savely.android.finance

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Repeat
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.savely.android.data.SupabaseApi
import cc.savely.android.ui.LocalAppLanguage
import cc.savely.android.ui.localized
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val financeTitles = mapOf(
    "dashboard" to "La tua finanza", "transactions" to "Transazioni", "categories" to "Categorie",
    "budget" to "Budget", "recurring" to "Uscite ricorrenti", "charts" to "Entrate e uscite",
    "portfolio" to "Portafoglio", "net-worth" to "Patrimonio", "insights" to "Insights",
)

private data class DeleteRequest(val table: String, val id: String, val label: String, val fallbackDate: LocalDate? = null)

@Composable
fun FinanceScreen(api: SupabaseApi, section: String, modifier: Modifier = Modifier, refreshKey: Int = 0, accountKey: String = "", today: LocalDate = LocalDate.now(), onNavigate: (String) -> Unit = {}) {
    if (section !in financeTitles) {
        Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized("Funzione in costruzione"), style = MaterialTheme.typography.headlineSmall)
            Text(localized("Questa sezione non è ancora disponibile nella versione Android."))
        }
        return
    }
    val applicationContext = LocalContext.current.applicationContext
    val financeLocale = Locale.forLanguageTag(LocalAppLanguage.current.tag)
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
    var editorOpen by rememberSaveable(section, accountKey, model) { mutableStateOf(false) }
    var editingId by rememberSaveable(section, accountKey, model) { mutableStateOf<String?>(null) }
    var suggestionKey by rememberSaveable(section, accountKey, model) { mutableStateOf<String?>(null) }
    var deleteRequest by remember(model) { mutableStateOf<DeleteRequest?>(null) }
    var closingId by rememberSaveable(accountKey, model) { mutableStateOf<String?>(null) }
    var closingGroup by remember(model) { mutableStateOf<PortfolioPositionGroup?>(null) }
    var removingShare by remember(model) { mutableStateOf<FinanceSharedExpense?>(null) }
    var showClosedAssets by rememberSaveable { mutableStateOf(false) }
    var insightFilter by rememberSaveable(section) { mutableStateOf("all") }
    val data = state.data
    val writeBlocked = state.saving || state.reconciliationRequired
    LaunchedEffect(state.reconciliationRequired) {
        if (state.reconciliationRequired) {
            if (state.positionCloseFailure == null) closingId = null
            editorOpen = false; deleteRequest = null; removingShare = null
        }
    }
    val suggestions = remember(data, section) { if (section == "recurring" && data != null) availableRecurringSuggestions(data) else emptyList() }
    val writable = section in setOf("transactions", "categories", "budget", "recurring", "portfolio")
    fun edit(id: String?) { editingId = id; suggestionKey = null; editorOpen = true }

    if (section == "transactions" && data != null) {
        TransactionList(data, !writeBlocked, { edit(it) }, {
            deleteRequest = DeleteRequest("transactions", it.id, it.description, if (it.description.isBlank()) it.date else null)
        }, { removingShare = it }, modifier, today) { FinanceHeading(section, state, writable, model::refresh) { edit(null) } }
    } else LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { FinanceHeading(section, state, writable, model::refresh) { edit(null) } }
        if (data == null && state.loading) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
        if (data != null) when (section) {
            "dashboard" -> {
                item { Dashboard(data, today, onNavigate) }
            }
            "categories" -> {
                if (data.categories.isEmpty()) item { EmptyCard("Nessuna categoria") }
                TransactionType.entries.reversed().forEach { type ->
                    val categories = data.categories.filter { it.type == type }
                    item { Text(localized(if (type == TransactionType.EXPENSE) "Categorie di uscita" else "Categorie di entrata"), style = MaterialTheme.typography.titleLarge) }
                    if (categories.isEmpty()) item { EmptyCard(if (type == TransactionType.EXPENSE) "Nessuna categoria di uscita" else "Nessuna categoria di entrata") }
                    items(categories, key = { it.id }) { category ->
                        FinanceRow(category.name, "${category.icon} · ${localized(category.type.label)} · ${category.color}", "", !writeBlocked,
                            { edit(category.id) }, { deleteRequest = DeleteRequest("categories", category.id, category.name) })
                    }
                }
            }
            "budget" -> {
                item { Text(localized("Mediana dei mesi con movimenti negli ultimi 730 giorni. Importi separati per valuta."), style = MaterialTheme.typography.bodyMedium) }
                if (data.budgets.isNotEmpty()) {
                    items(budgetCurrencySummaries(data.budgets, data.transactions, today), key = { "summary-${it.currency}" }) { summary ->
                        BudgetSummaryCard(summary)
                    }
                    item { Text(localized("Il riepilogo include tutte le uscite, anche senza categoria o senza un budget dedicato."), style = MaterialTheme.typography.bodySmall) }
                }
                if (data.budgets.isEmpty()) item { EmptyCard("Nessun budget impostato") }
                items(sortedFinanceBudgets(data, financeLocale), key = { it.id }) { budget ->
                    val median = medianMonthlyExpense(data.transactions, today, budget.categoryId, budget.currency)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FinanceRow(data.categories.find { it.id == budget.categoryId }?.name ?: localized("Categoria rimossa"),
                            localized("Mediana {{amount}}", "amount" to formatMoney(median, budget.currency)), formatMoney(budget.amount, budget.currency), !writeBlocked,
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
                item { Text(localized("Il servizio automatico esistente elabora le scadenze. Puoi elaborare ora le ricorrenze personali scadute, una scadenza per ricorrenza.")) }
                item { Button(onClick = model::processDueRecurring, enabled = !writeBlocked && !state.loading) {
                    Text(localized(if (state.saving) "Operazione in corso…" else "Elabora ora"))
                } }
                item { Text(localized("Come sul sito, una transazione con lo stesso nome nel mese della scadenza impedisce una nuova creazione, anche per le ricorrenze settimanali."), style = MaterialTheme.typography.bodySmall) }
                item { RecurringCalendarPanel(data.recurring, today) }
                if (suggestions.isNotEmpty()) {
                    item { Text(localized("Suggerimenti dalla cronologia"), style = MaterialTheme.typography.titleLarge) }
                    item { Text(localized("Importi e cadenze simili in almeno tre uscite. Verifica i dettagli prima di salvare. I suggerimenti ignorati restano nascosti su questo dispositivo per questo account."), style = MaterialTheme.typography.bodySmall) }
                    items(suggestions, key = { "suggestion-${it.key}" }) { suggestion ->
                        Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(suggestion.description, style = MaterialTheme.typography.titleMedium)
                            Text(localized("{{amount}} · {{frequency}} · {{count}} uscite", "amount" to formatMoney(suggestion.medianAmount, suggestion.currency),
                                "frequency" to localized(suggestion.frequency.label), "count" to suggestion.occurrenceCount))
                            Text(localized("Corrispondenza {{confidence}} · Prossima scadenza proposta {{date}}", "confidence" to localized(if (suggestion.highConfidence) "alta" else "media"),
                                "date" to suggestion.nextDueDate))
                            Button(onClick = { editingId = null; suggestionKey = suggestion.key; editorOpen = true }, enabled = !writeBlocked && !state.loading) { Text(localized("Crea ricorrenza")) }
                            TextButton(onClick = { model.dismissRecurringSuggestion(suggestion.key) }, enabled = !writeBlocked && !state.loading) { Text(localized("Non proporre")) }
                        } }
                    }
                    item { Text(localized("Ricorrenze salvate"), style = MaterialTheme.typography.titleLarge) }
                }
                if (data.recurring.isEmpty()) item { EmptyCard("Nessuna uscita ricorrente") }
                items(data.recurring, key = { it.id }) { recurring ->
                    Column {
                    FinanceRow(recurring.name,
                        "${recurring.nextDueDate} · ${localized(recurring.frequency.label)}" +
                            (if (recurring.frequency == RecurringFrequency.WEEKLY) " (${localized("{{count}} settimane", "count" to recurring.weekInterval)})" else "") +
                            " · ${localized(if (recurring.active) "Attiva" else "In pausa")}",
                        formatMoney(recurring.amount, recurring.currency), !writeBlocked && !state.loading,
                        { edit(recurring.id) }, { deleteRequest = DeleteRequest("recurring_expenses", recurring.id, recurring.name) })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(localized("{{name}}: {{status}}", "name" to recurring.name, "status" to localized(if (recurring.active) "attiva" else "in pausa")), Modifier.weight(1f))
                        val toggleDescription = localized("Attiva o metti in pausa {{name}}", "name" to recurring.name)
                        Switch(checked = recurring.active, onCheckedChange = { model.setRecurringActive(recurring.id, it) },
                            enabled = !writeBlocked && !state.loading,
                            modifier = Modifier.semantics { contentDescription = toggleDescription })
                    }
                    }
                }
            }
            "charts" -> item { FinanceChartsScreen(data, today = today) }
            "portfolio" -> {
                item { TextButton(onClick = { onNavigate("net-worth") }) { Text(localized("Patrimonio")) } }
                item { PortfolioSummary(data, state.saving, { model.mutate({ updatePrices() }) {} }, writeAllowed = !state.reconciliationRequired) }
                item { PortfolioHistoryPanel(data.assets, data.priceHistory, today) }
                if (!showClosedAssets) item { PortfolioGroupClosures(data.assets, writeBlocked) { closingGroup = it } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showClosedAssets, onClick = { showClosedAssets = false }, label = { Text(localized("Aperte")) })
                    FilterChip(selected = showClosedAssets, onClick = { showClosedAssets = true }, label = { Text(localized("Chiuse")) })
                } }
                val assets = data.assets.filter { it.closed == showClosedAssets }
                if (assets.isEmpty()) item { EmptyCard(localized(if (showClosedAssets) "Nessuna posizione chiusa" else "Nessuna posizione aperta")) }
                items(assets, key = { it.id }) { asset ->
                    Column {
                        FinanceRow(asset.name, localized("{{type}} · {{symbol}} · Quantità {{quantity}}", "type" to localized(asset.type.label),
                            "symbol" to (asset.symbol ?: localized("Senza simbolo")), "quantity" to asset.quantity.stripTrailingZeros().toPlainString()) +
                            " · " + (if (asset.soldAt != null) localized("Vendita {{date}}", "date" to asset.soldAt) else localized("Acquisto {{date}}", "date" to asset.purchaseDate)),
                            formatMoney(if (asset.closed) asset.soldPrice!! * asset.quantity else asset.marketValue, asset.valuationCurrency), !writeBlocked,
                            { edit(asset.id) }, { deleteRequest = DeleteRequest("portfolio_assets", asset.id, asset.name) })
                        if (!asset.closed) TextButton(onClick = { closingId = asset.id }, enabled = !writeBlocked) { Text(localized("Chiudi posizione")) }
                    }
                }
            }
            "net-worth" -> {
                item { TextButton(onClick = { onNavigate("portfolio") }) { Text(localized("Portafoglio")) } }
                item { Text(localized("Formula Savely per valuta: saldo transazioni + profitto investimenti aperti + 75% del valore immobiliare.")) }
                val components = netWorthComponents(data.transactions, data.assets)
                if (components.isEmpty()) item { EmptyCard("Nessun dato disponibile") }
                items(components, key = { it.currency }) { NetWorthCard(it) }
                item { NetWorthHistoryPanel(data.snapshots, today) }
            }
            "insights" -> {
                item { TextButton(onClick = { onNavigate("dashboard") }) { Text(localized("Dashboard")) } }
                item { Text(localized("Controlli disponibili (8 su 8): anomalie di spesa rispetto agli importi storici, budget superati, posizioni con perdita di almeno 15%, massimi del valore registrato dal server, mesi di risparmio sopra la media, categorie con importi in crescita, possibili spese ricorrenti e variazioni dei loro importi. Gli snapshot server non distinguono le valute e non sono confrontabili con i totali nativi per valuta.")) }
                val insights = financeInsights(data, today)
                val filtered = insights.filter { insightFilter == "all" || it.severity.name == insightFilter }
                item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = insightFilter == "all", onClick = { insightFilter = "all" }, label = { Text(localized("Tutti ({{count}})", "count" to insights.size)) })
                    InsightSeverity.entries.forEach { severity ->
                        val label = when (severity) { InsightSeverity.WARNING -> "Attenzione"; InsightSeverity.POSITIVE -> "Positivi"; InsightSeverity.INFO -> "Info" }
                        FilterChip(selected = insightFilter == severity.name, onClick = { insightFilter = severity.name },
                            label = { Text(localized("{{label}} ({{count}})", "label" to localized(label), "count" to insights.count { it.severity == severity })) })
                    }
                } }
                if (filtered.isEmpty()) item { EmptyCard(if (insights.isEmpty()) "Nessun segnale negli otto controlli disponibili" else "Nessun segnale nel filtro selezionato") }
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
        CloseAssetDialog(asset, state.saving, state.error, state.positionCloseFailure,
            dismiss = { if (!state.saving) closingId = null },
            refreshAndDismiss = { closingId = null; model.refresh() }) { date, price ->
            model.closePositions(listOf(asset), date, price) { closingId = null }
        }
    } }
    closingGroup?.let { group ->
        CloseAssetGroupDialog(group, state.saving, state.error, state.positionCloseFailure,
            dismiss = { if (!state.saving) closingGroup = null },
            refreshAndDismiss = { closingGroup = null; model.refresh() }) { date, price ->
                model.closePositions(group.assets, date, price) { closingGroup = null }
            }
    }
    deleteRequest?.let { request ->
        val label = if (request.fallbackDate != null) localized("transazione del {{date}}", "date" to request.fallbackDate)
            else if (request.table == "budgets") localized("budget") else request.label
        AlertDialog(onDismissRequest = { if (!state.saving) deleteRequest = null },
            title = { Text(localized("Eliminare {{name}}?", "name" to label)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(localized(if (request.table == "categories")
                    "La categoria sarà rimossa e le sue transazioni resteranno senza categoria. La modifica sarà visibile anche sul sito."
                    else "La modifica sarà visibile anche sul sito Savely."))
                state.error?.let { Text(localizedFinanceMessage(it), color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !writeBlocked, onClick = {
                model.mutate({ delete(request.table, request.id) }) { deleteRequest = null }
            }) { Text(localized(if (state.saving) "Eliminazione…" else "Elimina")) } },
            dismissButton = { TextButton(enabled = !state.saving, onClick = { deleteRequest = null }) { Text(localized("Annulla")) } })
    }
    removingShare?.let { expense ->
        AlertDialog(onDismissRequest = { if (!state.saving) removingShare = null },
            title = { Text(localized("Rimuovere la condivisione?")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(localized(if (expense.createdBy == data?.userId)
                    "La condivisione verrà rimossa. La tua transazione rimarrà nel tuo ledger ma il partner non la vedrà più."
                    else "La spesa condivisa verrà rimossa dalla tua lista. La transazione originale rimarrà nel ledger del tuo partner."))
                state.error?.let { Text(localizedFinanceMessage(it), color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !writeBlocked, onClick = {
                model.mutate({ removeTransactionShare(expense) }) { removingShare = null }
            }) { Text(localized("Rimuovi condivisione")) } },
            dismissButton = { TextButton(enabled = !state.saving, onClick = { removingShare = null }) { Text(localized("Annulla")) } })
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
            val values = insight.textValues.map { (key, value) -> key to if (key in insight.localizedValueKeys) localized(value.toString()) else value }.toTypedArray()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val icon = when (insight.kind) {
                    InsightKind.SPENDING_ANOMALY -> Icons.Default.TrendingUp
                    InsightKind.RECURRING_PRICE_CHANGE -> Icons.Default.NotificationsActive
                    InsightKind.BUDGET_EXCEEDED -> Icons.Default.Warning
                    InsightKind.PORTFOLIO_LOSS -> Icons.Default.TrendingDown
                    InsightKind.NET_WORTH_MILESTONE -> Icons.Default.EmojiEvents
                    InsightKind.SAVING_MONTH -> Icons.Default.Savings
                    InsightKind.CATEGORY_TREND -> Icons.Default.ReceiptLong
                    InsightKind.NEW_RECURRING_DETECTED -> Icons.Default.Repeat
                    null -> null
                }
                if (icon != null) Icon(icon, contentDescription = null)
                Text(localized(insight.titleTemplate ?: insight.title, *values), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
            }
            Surface(color = colors.first, contentColor = colors.second, shape = MaterialTheme.shapes.small) {
                Text(localized(insight.severity.label), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
            Text(localized(insight.messageTemplate ?: insight.message, *values))
        }
    }
}

@Composable
private fun BudgetSummaryCard(summary: BudgetCurrencySummary) {
    val description = localized("Riepilogo budget {{currency}}", "currency" to summary.currency)
    Card(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(localized("Riepilogo mensile {{currency}}", "currency" to summary.currency), style = MaterialTheme.typography.titleMedium)
            Text(localized("Spesa mensile prevista: {{amount}}", "amount" to formatMoney(summary.totalBudget, summary.currency)))
            Text(localized("Spesa mediana mensile reale: {{amount}}", "amount" to formatMoney(summary.monthlyMedianExpense, summary.currency)))
            Text(localized("Differenza: {{amount}}", "amount" to ((if (summary.difference.signum() > 0) "+" else "") + formatMoney(summary.difference, summary.currency))),
                color = if (summary.difference.signum() > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun FinanceHeading(section: String, state: FinanceUiState, writable: Boolean, refresh: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(localized(financeTitles.getValue(section)), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = refresh, enabled = !state.loading && !state.saving) {
                Icon(Icons.Default.Refresh, contentDescription = localized("Aggiorna i dati"))
            }
        }
        if (writable) Button(onClick = add, enabled = state.data != null && !state.saving && !state.reconciliationRequired) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(localized(when (section) { "categories" -> "Nuova categoria"; "budget" -> "Nuovo budget"; "recurring" -> "Nuova ricorrenza"; "portfolio" -> "Nuovo acquisto"; else -> "Nuova transazione" }))
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.updatedAt?.let { instant ->
            val time = DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(instant)
            Text(localized("Ultimo aggiornamento {{time}}", "time" to time), style = MaterialTheme.typography.labelMedium)
        }
        if (state.error != null) ErrorCard(state.error, refresh)
        if (state.reconciliationRequired) Text(localized("Le modifiche sono bloccate finché un aggiornamento dei dati non riesce."),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        state.positionCloseFailure?.let { failure ->
            Text(localized("Chiusure confermate: {{confirmed}} su {{total}}. Verifica anche l’acquisto {{id}} dopo il refresh.",
                "confirmed" to failure.confirmed, "total" to failure.total, "id" to failure.uncertainId),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        state.message?.let { Text(localizedFinanceMessage(it), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(localizedFinanceMessage(message), color = MaterialTheme.colorScheme.onErrorContainer)
            Text(localized("I dati già visibili potrebbero non essere aggiornati."), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = retry) { Text(localized("Riprova")) }
        }
    }
}

@Composable
private fun EmptyCard(message: String) {
    Card { Text(localized(message), Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun Dashboard(data: FinanceData, today: LocalDate, onNavigate: (String) -> Unit) {
    var presetName by rememberSaveable { mutableStateOf(DashboardPreset.CURRENT_MONTH.name) }
    var fromText by rememberSaveable { mutableStateOf(today.withDayOfMonth(1).toString()) }
    var toText by rememberSaveable { mutableStateOf(today.toString()) }
    var appliedFrom by rememberSaveable { mutableStateOf(fromText) }
    var appliedTo by rememberSaveable { mutableStateOf(toText) }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    val preset = DashboardPreset.valueOf(presetName)
    val range = resolveDashboardRange(preset, today, appliedFrom, appliedTo)
    val previous = range.previous()
    val stats = dashboardCurrencyStats(data.transactions, range)
    val recent = dashboardRecentTransactions(data.transactions, range)
    val budgets = dashboardBudgetRows(data.budgets, data.transactions, today)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Selection("Periodo", presetName, DashboardPreset.entries.map { it.name to localized(it.label) }) { chosen ->
            presetName = chosen
            validationError = null
            if (chosen != DashboardPreset.CUSTOM.name) {
                val resolved = resolveDashboardRange(DashboardPreset.valueOf(chosen), today)
                fromText = resolved.from.toString(); toText = resolved.to.toString()
                appliedFrom = fromText; appliedTo = toText
            }
        }
        if (preset == DashboardPreset.CUSTOM) {
            OutlinedTextField(fromText, { fromText = it }, label = { Text(localized("Dal")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(toText, { toText = it }, label = { Text(localized("Al")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                try {
                    val custom = resolveDashboardRange(DashboardPreset.CUSTOM, today, fromText, toText)
                    appliedFrom = custom.from.toString(); appliedTo = custom.to.toString()
                    fromText = appliedFrom; toText = appliedTo; validationError = null
                } catch (exception: IllegalArgumentException) { validationError = exception.message }
            }) { Text(localized("Applica intervallo")) }
            validationError?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
        }
        Text(localized("Periodo selezionato: {{from}} – {{to}}", "from" to range.from, "to" to range.to))
        Text(localized("Confronto con il periodo precedente"), style = MaterialTheme.typography.titleMedium)
        Text(localized("Periodo precedente: {{from}} – {{to}}", "from" to previous.from, "to" to previous.to), style = MaterialTheme.typography.bodySmall)
        if (stats.isEmpty()) EmptyCard(localized("Nessun movimento nel periodo"))
        stats.forEach { totals ->
            val description = localized("Statistiche {{currency}}", "currency" to totals.currency)
            Card(Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(totals.currency, style = MaterialTheme.typography.labelLarge)
                    DashboardMetric("Saldo totale", totals.current.balance, totals.currency, totals.balanceDelta)
                    DashboardMetric("Entrate", totals.current.income, totals.currency, totals.incomeDelta)
                    DashboardMetric("Uscite", totals.current.expenses, totals.currency, totals.expenseDelta)
                    DashboardMetric("Risparmio", totals.current.savings, totals.currency, totals.savingsDelta)
                    Text(localized("{{percent}} del totale entrate", "percent" to formatDashboardPercent(totals.current.savingsRate)))
                }
            }
        }
        Text(localized("Ogni valuta è mostrata separatamente; il saldo non include il portafoglio."), style = MaterialTheme.typography.bodySmall)
        DashboardRangedChart(data.transactions, range)
        DashboardCategoryDonut(data.transactions, data.categories, range)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(localized("Budget mensili"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { onNavigate("budget") }) { Text(localized("Vedi tutti")) }
        }
        Text(localized("I budget usano l’intero mese corrente, indipendentemente dal periodo selezionato."), style = MaterialTheme.typography.bodySmall)
        if (budgets.isEmpty()) EmptyCard(localized("Nessun budget configurato"))
        dashboardBudgetSummaries(budgets).forEach { summary ->
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(summary.currency, style = MaterialTheme.typography.titleMedium)
                Text(localized("Budget totale: {{amount}}", "amount" to formatMoney(summary.planned, summary.currency)))
                Text(localized("Spesa delle categorie con budget: {{amount}}", "amount" to formatMoney(summary.spent, summary.currency)))
                Text(localized("Disponibile: {{amount}}", "amount" to formatMoney(summary.remaining, summary.currency)))
            } }
        }
        budgets.take(4).forEach { row ->
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(data.categories.find { it.id == row.budget.categoryId }?.name ?: localized("Categoria rimossa"))
                Text(localized("Speso {{spent}} su {{limit}}", "spent" to formatMoney(row.spent, row.budget.currency), "limit" to formatMoney(row.budget.amount, row.budget.currency)))
                Text(formatDashboardPercent(row.percent))
                LinearProgressIndicator(progress = { row.percent.divide(BigDecimal("100"), 6, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(), color = if (row.percent >= BigDecimal("100")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            } }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(localized("Movimenti recenti"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { onNavigate("transactions") }) { Text(localized("Vedi tutte")) }
        }
        if (recent.isEmpty()) EmptyCard(localized("Nessuna transazione nel periodo selezionato"))
        recent.forEach { TransactionCard(it, data, false, {}, {}) }
    }
}

@Composable
internal fun DashboardCategoryDonut(transactions: List<FinanceTransaction>, categories: List<FinanceCategory>, range: DashboardRange) {
    val calculation = remember(transactions, categories, range) { runCatching { dashboardCategoryExpenses(transactions, categories, range) } }
    val slices = calculation.getOrDefault(emptyList())
    val palette = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.secondaryContainer)
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(localized("Spese per categoria"), style = MaterialTheme.typography.titleLarge)
        Text(localized("Ogni valuta è mostrata separatamente; nessuna conversione o somma tra valute."), style = MaterialTheme.typography.bodySmall)
        if (calculation.isFailure) Text(localized("Spese per categoria non disponibili: importo o valuta non validi."), color = MaterialTheme.colorScheme.error)
        else if (slices.isEmpty()) Text(localized("Nessuna transazione nel periodo selezionato"))
        else slices.groupBy { it.currency }.forEach { (currency, rows) ->
            val total = rows.fold(BigDecimal.ZERO) { sum, slice -> sum + slice.amount }
            Text(currency, style = MaterialTheme.typography.titleMedium)
            Text(localized("Totale spese: {{amount}}", "amount" to formatMoney(total, currency)), style = MaterialTheme.typography.titleMedium)
            if (total.signum() == 0) Text(localized("Nessuna transazione nel periodo selezionato"))
            else {
                val colors = rows.mapIndexed { index, slice ->
                    val hex = slice.color?.trim()
                    if (hex != null && Regex("#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?").matches(hex)) {
                        val expanded = if (hex.length == 4) "#" + hex.drop(1).flatMap { listOf(it, it) }.joinToString("") else hex
                        Color(android.graphics.Color.parseColor(expanded))
                    } else palette[index % palette.size]
                }
                // Exact monetary rows below provide the accessible alternative to decorative pixel arcs.
                Canvas(Modifier.fillMaxWidth().height(200.dp).clearAndSetSemantics { }) {
                    val stroke = 28.dp.toPx()
                    val diameter = (minOf(size.width, size.height) - stroke).coerceAtLeast(1f)
                    val origin = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                    var start = -90f
                    rows.forEachIndexed { index, slice ->
                        val sweep = slice.amount.divide(total, 16, RoundingMode.HALF_UP).multiply(BigDecimal("360")).toFloat()
                        if (sweep > 0) drawArc(colors[index], start, sweep, false, origin, Size(diameter, diameter), style = Stroke(stroke))
                        start += sweep
                    }
                }
                rows.forEachIndexed { index, slice ->
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(color = colors[index], shape = MaterialTheme.shapes.small, modifier = Modifier.width(12.dp).height(12.dp)) {}
                        Column(Modifier.weight(1f)) {
                            Text(slice.name ?: localized("Nessuna categoria"), style = MaterialTheme.typography.bodyMedium)
                            Text(localized("{{amount}} · {{percent}}", "amount" to formatMoney(slice.amount, currency), "percent" to formatDashboardPercent(slice.percent)))
                        }
                    }
                }
            }
        }
    } }
}

@Composable
private fun DashboardMetric(label: String, value: BigDecimal, currency: String, delta: BigDecimal?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(localized(label), style = MaterialTheme.typography.labelLarge)
        Text(formatMoney(value, currency), style = MaterialTheme.typography.titleLarge)
        Text(if (delta == null) localized("Confronto non disponibile: valore precedente zero")
            else localized("{{percent}} rispetto al periodo precedente", "percent" to formatDashboardPercent(delta)), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RecurringCalendarPanel(rows: List<FinanceRecurring>, today: LocalDate) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    val month = YearMonth.parse(monthText)
    val selected = LocalDate.parse(selectedText)
    val locale = Locale.forLanguageTag(LocalAppLanguage.current.tag)
    val title = DateTimeFormatter.ofPattern("MMMM yyyy", locale).format(month.atDay(1))
    val projection = remember(rows, month) { runCatching { recurringCalendarOccurrences(rows, month) } }
    val occurrences = projection.getOrDefault(emptyList())
    val grouped = occurrences.groupBy { it.date }
    Card { Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(localized("Calendario {{month}}", "month" to title), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(modifier = Modifier.weight(1f), enabled = month.year > 1 || month.monthValue > 1, onClick = {
                val previous = month.minusMonths(1); monthText = previous.toString(); selectedText = previous.atDay(1).toString()
            }) { Text(localized("Mese precedente")) }
            TextButton(modifier = Modifier.weight(1f), onClick = { monthText = YearMonth.from(today).toString(); selectedText = today.toString() }) { Text(localized("Oggi")) }
            TextButton(modifier = Modifier.weight(1f), enabled = month.year < 9999 || month.monthValue < 12, onClick = {
                val next = month.plusMonths(1); monthText = next.toString(); selectedText = next.atDay(1).toString()
            }) { Text(localized("Mese successivo")) }
        }
        Text(localized("Le date sono proiezioni dalla prossima scadenza salvata. Non creano transazioni; le ricorrenze in pausa sono escluse."), style = MaterialTheme.typography.bodySmall)
        if (projection.isFailure) {
            Text(localized("Calendario non disponibile: {{message}}", "message" to localized(projection.exceptionOrNull()?.message ?: "Scadenza ricorrente non valida")), color = MaterialTheme.colorScheme.error)
        } else {
            Row(Modifier.fillMaxWidth()) {
                listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom").forEach { Text(localized(it), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall) }
            }
            recurringCalendarWeeks(month).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        if (date == null) Spacer(Modifier.weight(1f)) else {
                            val count = grouped[date]?.size ?: 0
                            val description = localized("{{date}}: {{count}} scadenze", "date" to date, "count" to count)
                            TextButton(onClick = { selectedText = date.toString() }, contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.weight(1f).semantics { contentDescription = description }) {
                                Text(if (count == 0) date.dayOfMonth.toString() else "${date.dayOfMonth}·$count",
                                    color = if (date == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
            occurrences.groupBy { it.recurring.currency }.toSortedMap().forEach { (currency, dates) ->
                val amount = dates.fold(BigDecimal.ZERO) { sum, occurrence -> sum + occurrence.recurring.amount }
                Text(localized("Totale previsto {{currency}}: {{amount}}", "currency" to currency, "amount" to formatMoney(amount, currency)))
            }
            Text(localized("Scadenze del {{date}}", "date" to selected), style = MaterialTheme.typography.titleMedium)
            val selectedOccurrences = grouped[selected].orEmpty()
            if (selectedOccurrences.isEmpty()) Text(localized("Nessuna scadenza nel giorno selezionato"))
            selectedOccurrences.forEach { occurrence -> Text("${occurrence.recurring.name} · ${formatMoney(occurrence.recurring.amount, occurrence.recurring.currency)}") }
        }
    } }
}

@Composable
private fun FinanceRow(title: String, detail: String, amount: String, enabled: Boolean, edit: () -> Unit, delete: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (amount.isNotEmpty()) Text(amount, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(enabled = enabled, onClick = edit) { Icon(Icons.Default.Edit, localized("Modifica {{name}}", "name" to title)) }
                IconButton(enabled = enabled, onClick = delete) { Icon(Icons.Default.Delete, localized("Elimina {{name}}", "name" to title)) }
            }
        }
    }
}

@Composable
private fun TransactionCard(transaction: FinanceTransaction, data: FinanceData, editable: Boolean, edit: () -> Unit, delete: () -> Unit) {
    val category = data.categories.find { it.id == transaction.categoryId }?.name ?: localized("Senza categoria")
    val title = transaction.description.ifBlank { category }
    val amount = (if (transaction.type == TransactionType.INCOME) "+" else "−") + formatMoney(transaction.amount, transaction.currency)
    if (editable) FinanceRow(title, "${transaction.date} · $category · ${localized(transaction.type.label)}", amount, true, edit, delete)
    else Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(amount, style = MaterialTheme.typography.titleLarge)
        Text("${transaction.date} · $category", style = MaterialTheme.typography.bodyMedium)
    } }
}

@Composable
private fun TransactionList(data: FinanceData, enabled: Boolean, edit: (String) -> Unit, delete: (FinanceTransaction) -> Unit, removeShare: (FinanceSharedExpense) -> Unit,
    modifier: Modifier, today: LocalDate, header: @Composable () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var period by rememberSaveable { mutableStateOf("month") }
    var category by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    var onlyShared by rememberSaveable { mutableStateOf(false) }
    var fromText by rememberSaveable { mutableStateOf("") }
    var toText by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf(today.year.toString()) }
    val interval = runCatching {
        transactionDateRange(period, today, year, fromText, toText)
    }
    val filtered = runCatching {
        val (from, to) = interval.getOrThrow()
        transactionDisplayItems(data, from, to, category.ifBlank { null },
            type.ifBlank { null }?.let(TransactionType::fromWire), search, onlyShared && data.coupleConnection != null)
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { header() }
        item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(search, { search = it }, label = { Text(localized("Cerca descrizione o categoria")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Selection("Periodo", period, listOf("month" to "Questo mese", "previous" to "Mese precedente",
            "semester" to "Ultimi 6 mesi", "twelve" to "Ultimi 12 mesi", "year" to "Anno", "all" to "Tutte", "custom" to "Personalizzato").map { it.first to localized(it.second) }) { period = it }
        if (period == "year") OutlinedTextField(year, { year = it }, label = { Text(localized("Anno")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        if (period == "custom") {
            DateField("Da", fromText) { fromText = it }
            TextButton(onClick = { fromText = "" }) { Text(localized("Rimuovi data iniziale")) }
            DateField("A", toText) { toText = it }
            TextButton(onClick = { toText = "" }) { Text(localized("Rimuovi data finale")) }
        }
        Selection("Categoria", category, listOf("" to localized("Tutte")) + data.categories.map { it.id to it.name }) { category = it }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "Tutti i tipi", "income" to "Entrate", "expense" to "Uscite").forEach { (value, label) ->
                FilterChip(selected = type == value, onClick = { type = value }, label = { Text(localized(label)) })
            }
        }
        if (data.coupleConnection != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(localized("Solo condivise"), Modifier.weight(1f))
            val sharedLabel = localized("Solo condivise")
            Switch(checked = onlyShared, onCheckedChange = { onlyShared = it }, modifier = Modifier.semantics { contentDescription = sharedLabel })
        }
        } }
        if (filtered.isFailure) item { Text(localized("Periodo non valido: controlla le date e l’anno."), color = MaterialTheme.colorScheme.error) }
        else {
            val transactions = filtered.getOrThrow()
            item { Text(localized("{{count}} transazioni", "count" to transactions.size), style = MaterialTheme.typography.labelLarge) }
            if (transactions.isEmpty()) item { EmptyCard("Nessuna transazione corrisponde ai filtri") }
            items(transactions, key = { it.key }) { row ->
                val transaction = row.transaction
                val share = row.share
                Column {
                    if (transaction != null) {
                        FinanceRow(transaction.description.ifBlank { data.categories.find { it.id == transaction.categoryId }?.name ?: localized("Senza categoria") },
                            "${transaction.date} · ${data.categories.find { it.id == transaction.categoryId }?.name ?: localized("Senza categoria")}",
                            (if (transaction.type == TransactionType.INCOME) "+" else "−") + formatMoney(share?.creatorShare ?: transaction.amount, share?.currency ?: transaction.currency), enabled,
                            { edit(transaction.id) }, { delete(transaction) })
                        if (share != null) Text(localized("Condivisa · Quota {{share}} / totale {{total}}", "share" to formatMoney(share.creatorShare, share.currency), "total" to formatMoney(share.total, share.currency)))
                    } else if (share != null) Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(share.description.ifBlank { share.categoryName ?: localized("Spesa condivisa") }, style = MaterialTheme.typography.titleMedium)
                            Text("${share.date} · ${share.categoryName ?: localized("Senza categoria")}")
                            Text(localized("Dal partner"))
                            Text("−" + formatMoney(share.partnerShare, share.currency), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    if (share != null) {
                        val removeLabel = localized("Rimuovi condivisione {{name}}", "name" to share.description.ifBlank { share.categoryName.orEmpty() })
                        TextButton(onClick = { removeShare(share) }, enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = removeLabel }) { Text(localized("Rimuovi condivisione")) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Selection(label: String, selected: String, options: List<Pair<String, String>>, enabled: Boolean = true, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(localized("{{label}}: {{value}}", "label" to localized(label), "value" to (options.find { it.first == selected }?.second ?: localized("Seleziona"))))
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
    }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(localized("{{label}}: {{value}}", "label" to localized(label), "value" to value)) }
}

/** Known operation result shapes keep raw values separate from translation; unknown errors use exact lookup. */
@Composable
private fun localizedFinanceMessage(message: String): String {
    val categoryRemoval = Regex("Rimozione interrotta: ([0-9]+) transazioni scollegate con conferma\\. Aggiorna i dati e verifica la categoria prima di continuare\\.").matchEntire(message)
    if (categoryRemoval != null) return localized("Rimozione interrotta: {{count}} transazioni scollegate con conferma. Aggiorna i dati e verifica la categoria prima di continuare.",
        "count" to categoryRemoval.groupValues[1])
    val prices = Regex("Aggiornamento parziale: ([0-9]+) prezzi aggiornati, ([0-9]+) errori(?: \\((.*?)\\))?\\. Aggiorna la schermata per leggere i prezzi salvati\\.").matchEntire(message)
    if (prices != null) return if (prices.groupValues[3].isBlank())
        localized("Aggiornamento parziale: {{updated}} prezzi aggiornati, {{errors}} errori. Aggiorna la schermata per leggere i prezzi salvati.",
            "updated" to prices.groupValues[1], "errors" to prices.groupValues[2])
    else localized("Aggiornamento parziale: {{updated}} prezzi aggiornati, {{errors}} errori ({{symbols}}). Aggiorna la schermata per leggere i prezzi salvati.",
        "updated" to prices.groupValues[1], "errors" to prices.groupValues[2], "symbols" to prices.groupValues[3])
    val complete = Regex("Elaborazione completata: ([0-9]+) transazioni create, ([0-9]+) già presenti, ([0-9]+) scadenze aggiornate\\.").matchEntire(message)
    if (complete != null) return localized("Elaborazione completata: {{created}} transazioni create, {{existing}} già presenti, {{advanced}} scadenze aggiornate.",
        "created" to complete.groupValues[1], "existing" to complete.groupValues[2], "advanced" to complete.groupValues[3])
    val partial = Regex("Elaborazione interrotta durante (.*?): (.*?)\\. ([0-9]+) transazioni create, ([0-9]+) già presenti, ([0-9]+) scadenze aggiornate\\. " +
        "Aggiorna i dati e verifica i movimenti prima di elaborare nuovamente: una richiesta fallita può avere già salvato dati\\.", RegexOption.DOT_MATCHES_ALL).matchEntire(message)
    if (partial != null) return localized("Elaborazione interrotta durante {{stage}}: {{message}}. {{created}} transazioni create, {{existing}} già presenti, {{advanced}} scadenze aggiornate. Aggiorna i dati e verifica i movimenti prima di elaborare nuovamente: una richiesta fallita può avere già salvato dati.",
        "stage" to localized(partial.groupValues[1]), "message" to localized(partial.groupValues[2]), "created" to partial.groupValues[3],
        "existing" to partial.groupValues[4], "advanced" to partial.groupValues[5])
    return localized(message)
}
