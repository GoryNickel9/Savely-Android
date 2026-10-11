package cc.savely.android.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.savely.android.ui.LocalAppLanguage
import cc.savely.android.ui.localized
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun PortfolioHistoryPanel(assets: List<FinanceAsset>, history: List<FinancePriceHistory>) {
    var modeName by rememberSaveable { mutableStateOf(PortfolioHistoryMode.RECONSTRUCTED.name) }
    var selectedCurrency by rememberSaveable { mutableStateOf("") }
    val mode = PortfolioHistoryMode.valueOf(modeName)
    val today = LocalDate.now()
    val calculation = remember(assets, history, mode, today) { runCatching { portfolioHistory(assets, history, mode, today) } }
    val allPoints = calculation.getOrNull().orEmpty()
    val locale = Locale.forLanguageTag(LocalAppLanguage.current.tag)
    val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy", locale)
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized("Andamento investimenti"), style = MaterialTheme.typography.titleLarge)
            Selection("Serie", modeName, PortfolioHistoryMode.entries.map { it.name to localized(it.label) }) { modeName = it }
            val window = HistoryPeriodControls(allPoints.minOfOrNull { it.date } ?: today)
            val points = allPoints.filter { window == null || it.date in window }
            val currencies = points.map { it.currency }.distinct()
            val currency = selectedCurrency.takeIf { it in currencies } ?: currencies.firstOrNull()
            val series = points.filter { it.currency == currency }
            var selection by rememberSaveable(modeName, currency, window) { mutableIntStateOf(Int.MAX_VALUE) }
            val selected = if (series.isEmpty()) 0 else selection.coerceIn(0, series.lastIndex)
            Text(localized("Posizioni attualmente aperte, esclusi liquidità, altro e TCG. Quantità e costo usano i dati oggi memorizzati; non ricostruiscono le modifiche passate alle posizioni."), style = MaterialTheme.typography.bodySmall)
            Text(localized("Le variazioni del valore includono acquisti e cambiamenti di copertura: non sono un rendimento percentuale."), style = MaterialTheme.typography.bodySmall)
            Text(localized(if (mode == PortfolioHistoryMode.RECONSTRUCTED)
                "I prezzi mancanti sono interpolati tra acquisto e prezzo corrente su giorni di calendario. La curva è una ricostruzione, non un flusso storico di quotazioni. L’ultimo giorno usa il prezzo corrente salvato."
            else "Ogni data include soltanto le posizioni con un prezzo registrato quel giorno. Il cambiamento del valore può dipendere dalla copertura dei dati; non rappresenta il rendimento di un paniere costante."), style = MaterialTheme.typography.bodySmall)
            if (calculation.isFailure) Text(localized("Impossibile calcolare lo storico con i dati salvati."), color = MaterialTheme.colorScheme.error)
            else if (series.isEmpty()) Text(localized(if (allPoints.isNotEmpty()) "Nessun punto disponibile nel periodo selezionato."
            else if (mode == PortfolioHistoryMode.RECORDED)
                "Nessun prezzo storico registrato per le posizioni considerate."
            else "Nessuna posizione con data di acquisto entro oggi per ricostruire l’andamento."))
            else {
                val unit = requireNotNull(currency)
                Selection("Valuta", unit, currencies.map { it to it }) { selectedCurrency = it }
                val point = series[selected]
                HistoryChangeSummary(historicalValueChange(series.map { it.value })!!, { formatMoney(it, unit) })
                window?.let { selectedRange ->
                    val previous = selectedRange.previous()
                    val prior = allPoints.filter { it.currency == unit && it.date in previous }
                    HistoryPeriodComparison(previous, historicalValueChange(prior.map { it.value }), series.last().value, { formatMoney(it, unit) }, mode == PortfolioHistoryMode.RECORDED)
                }
                HistoryChart(series.map { it.date }, listOf(
                    HistoryLine(series.map { it.invested }, MaterialTheme.colorScheme.tertiary),
                    HistoryLine(series.map { it.value }, MaterialTheme.colorScheme.primary),
                ), selected, { formatMoney(it, unit) })
                Text(localized("Investito · Valore attuale"), style = MaterialTheme.typography.labelMedium)
                HistoryPointPicker(series.size, selected, dateFormat.format(point.date)) { selection = it }
                Text(localized("Investito: {{amount}}", "amount" to formatMoney(point.invested, unit)))
                Text(localized("Valore: {{amount}}", "amount" to formatMoney(point.value, unit)))
                Text(localized("Profitto: {{amount}}", "amount" to formatMoney(point.profit, unit)),
                    color = if (point.profit.signum() < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(localized("Copertura: {{recorded}} prezzi registrati, {{interpolated}} interpolati, {{current}} prezzi correnti.",
                    "recorded" to point.recordedAssets, "interpolated" to point.interpolatedAssets, "current" to point.currentAssets), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun NetWorthHistoryPanel(snapshots: List<FinanceSnapshot>) {
    val calculation = remember(snapshots) { runCatching { serverNetWorthHistory(snapshots) } }
    val allHistory = calculation.getOrNull()
    val locale = Locale.forLanguageTag(LocalAppLanguage.current.tag)
    val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy", locale)
    var selection by rememberSaveable { mutableIntStateOf(Int.MAX_VALUE) }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized("Andamento del valore registrato dal server"), style = MaterialTheme.typography.titleLarge)
            Text(localized("La serie server non distingue le valute. I valori e i componenti sono mostrati come registrati, senza simbolo monetario e senza confronto con il patrimonio nativo per valuta."), style = MaterialTheme.typography.bodySmall)
            val window = HistoryPeriodControls(allHistory?.first?.date ?: LocalDate.now())
            val history = serverNetWorthHistory(allHistory?.snapshots.orEmpty().filter { window == null || it.date in window })
            if (calculation.isFailure) Text(localized("Impossibile calcolare lo storico con i dati salvati."), color = MaterialTheme.colorScheme.error)
            else if (history == null) Text(localized(if (allHistory == null) "Nessuno storico registrato dal server disponibile." else "Nessun punto disponibile nel periodo selezionato."))
            else {
                val series = history.snapshots
                val selected = selection.coerceIn(0, series.lastIndex)
                val point = series[selected]
                Text(localized("Periodo registrato: {{from}} – {{to}}", "from" to dateFormat.format(history.first.date), "to" to dateFormat.format(history.latest.date)))
                Text(localized("Ultimo valore registrato: {{value}}", "value" to formatRecordedValue(history.latest.value, locale)), style = MaterialTheme.typography.titleMedium)
                val variation = (if (history.variation.signum() >= 0) "+" else "") + formatRecordedValue(history.variation, locale)
                Text(localized("Variazione del periodo: {{value}}", "value" to variation))
                Text(if (history.variationPercent == null) localized("Percentuale non disponibile: primo valore registrato zero.")
                else localized("Variazione percentuale: {{percent}}", "percent" to formatDashboardPercent(history.variationPercent!!)))
                Text(localized("Minimo {{minimum}} · Massimo {{maximum}}", "minimum" to formatRecordedValue(history.minimum, locale),
                    "maximum" to formatRecordedValue(history.maximum, locale)), style = MaterialTheme.typography.bodySmall)
                window?.let { selectedRange ->
                    val previous = selectedRange.previous()
                    val prior = allHistory?.snapshots.orEmpty().filter { it.date in previous }
                    HistoryPeriodComparison(previous, historicalValueChange(prior.map { it.value }), history.latest.value, { formatRecordedValue(it, locale) })
                }
                HistoryChart(series.map { it.date }, listOf(HistoryLine(series.map { it.value }, MaterialTheme.colorScheme.primary)),
                    selected, { formatRecordedValue(it, locale) })
                HistoryPointPicker(series.size, selected, dateFormat.format(point.date)) { selection = it }
                Text(localized("Valore registrato: {{value}}", "value" to formatRecordedValue(point.value, locale)), style = MaterialTheme.typography.titleMedium)
                val components = remember(point) { runCatching { serverSnapshotComponents(point) } }
                if (components.isFailure) Text(localized("Componenti dello snapshot non validi."), color = MaterialTheme.colorScheme.error)
                else if (components.getOrNull() == null) Text(localized("Componenti non registrati per questa data."), style = MaterialTheme.typography.bodySmall)
                else {
                    val values = components.getOrThrow()!!
                    ServerComponent("Cashflow registrato", values.cashflow, locale)
                    ServerComponent("Profitto investimenti registrato", values.portfolioGain, locale)
                    ServerComponent("Immobili scontati registrati", values.discountedEstate, locale)
                }
                if (series.size == 1) Text(localized("Una sola registrazione: la variazione zero non misura una tendenza."), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HistoryPeriodControls(first: LocalDate): DashboardRange? {
    val today = LocalDate.now()
    var periodName by rememberSaveable { mutableStateOf(HistoryPeriod.ALL.name) }
    var fromText by rememberSaveable { mutableStateOf(minOf(first, today).toString()) }
    var toText by rememberSaveable { mutableStateOf(today.toString()) }
    var appliedFrom by rememberSaveable { mutableStateOf(fromText) }
    var appliedTo by rememberSaveable { mutableStateOf(toText) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val period = HistoryPeriod.valueOf(periodName)
    Selection("Periodo storico", periodName, HistoryPeriod.entries.map { it.name to localized(it.label) }) { periodName = it; error = null }
    if (period == HistoryPeriod.CUSTOM) {
        OutlinedTextField(fromText, { fromText = it }, label = { Text(localized("Dal")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(toText, { toText = it }, label = { Text(localized("Al")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            try {
                val range = resolveDashboardRange(DashboardPreset.CUSTOM, today, fromText, toText)
                appliedFrom = range.from.toString(); appliedTo = range.to.toString()
                fromText = appliedFrom; toText = appliedTo; error = null
            } catch (_: IllegalArgumentException) { error = "Inserisci date valide nel formato YYYY-MM-DD" }
        }) { Text(localized("Applica intervallo")) }
        error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
    }
    val range = historyPeriodRange(period, today, appliedFrom, appliedTo)
    range?.let { Text(localized("Periodo selezionato: {{from}} – {{to}}", "from" to it.from, "to" to it.to), style = MaterialTheme.typography.bodySmall) }
    return range
}

@Composable
private fun HistoryChangeSummary(change: HistoricalValueChange, formatValue: (BigDecimal) -> String) {
    Text(localized("Variazione del valore coperto: {{amount}}", "amount" to formatValue(change.change)))
    Text(if (change.percent == null) localized("Percentuale non disponibile: valore iniziale zero.")
        else localized("Variazione percentuale: {{percent}}", "percent" to formatDashboardPercent(change.percent!!)))
    if (change.count == 1) Text(localized("Un solo punto: la variazione zero non misura una tendenza."), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun HistoryPeriodComparison(previousRange: DashboardRange, previous: HistoricalValueChange?, latest: BigDecimal, formatValue: (BigDecimal) -> String, recordedOnly: Boolean = true) {
    Text(localized("Periodo precedente: {{from}} – {{to}}", "from" to previousRange.from, "to" to previousRange.to), style = MaterialTheme.typography.bodySmall)
    if (previous == null || previous.count < 2) Text(localized("Confronto non disponibile: il periodo precedente richiede almeno due punti disponibili."), style = MaterialTheme.typography.bodySmall)
    else {
        Text(localized("Variazione nel periodo precedente: {{amount}}", "amount" to formatValue(previous.change)))
        Text(localized("Differenza rispetto all’ultimo valore del periodo precedente: {{amount}}", "amount" to formatValue(latest - previous.latest)))
        val percentage = dashboardPercentChange(latest, previous.latest)
        Text(if (percentage == null) localized("Confronto non disponibile: valore precedente zero")
            else localized("{{percent}} rispetto al periodo precedente", "percent" to formatDashboardPercent(percentage)), style = MaterialTheme.typography.bodySmall)
    }
    Text(localized(if (recordedOnly) "Le percentuali usano il valore assoluto del denominatore. Non vengono aggiunti punti nei giorni senza registrazioni."
    else "Le percentuali usano il valore assoluto del denominatore. Il confronto dell’andamento ricostruito include anche prezzi interpolati."), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ServerComponent(label: String, value: BigDecimal?, locale: Locale) {
    Text(localized("{{label}}: {{value}}", "label" to localized(label),
        "value" to (value?.let { formatRecordedValue(it, locale) } ?: localized("Non registrato"))))
}

@Composable
private fun HistoryPointPicker(count: Int, selected: Int, date: String, choose: (Int) -> Unit) {
    Text(localized("{{date}} · punto {{index}} di {{count}}", "date" to date, "index" to selected + 1, "count" to count), style = MaterialTheme.typography.titleMedium)
    if (count > 1) {
        val description = localized("Seleziona un punto dello storico")
        Slider(value = selected.toFloat(), onValueChange = { choose(it.roundToInt().coerceIn(0, count - 1)) },
            valueRange = 0f..(count - 1).toFloat(), modifier = Modifier.semantics { contentDescription = description })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = selected > 0, onClick = { choose(selected - 1) }) { Text(localized("Punto precedente")) }
            TextButton(enabled = selected < count - 1, onClick = { choose(selected + 1) }) { Text(localized("Punto successivo")) }
        }
    }
}

private data class HistoryLine(val values: List<BigDecimal>, val color: Color)

/** Float coordinates only after bounded decimal normalization; monetary calculations retain BigDecimal. */
@Composable
private fun HistoryChart(dates: List<LocalDate>, lines: List<HistoryLine>, selected: Int, formatValue: (BigDecimal) -> String) {
    val minimum = lines.minOf { line -> line.values.minOrNull()!! }
    val maximum = lines.maxOf { line -> line.values.maxOrNull()!! }
    val range = (maximum - minimum).takeIf { it.signum() != 0 } ?: BigDecimal.ONE
    val span = maxOf(1L, ChronoUnit.DAYS.between(dates.first(), dates.last()))
    val description = localized("Grafico dello storico dal {{from}} al {{to}}", "from" to dates.first(), "to" to dates.last())
    val outline = MaterialTheme.colorScheme.outlineVariant
    // All records remain available in the picker; cap drawing work independently from repository pagination.
    val step = maxOf(1, (dates.size + 179) / 180)
    val drawn = (dates.indices.filter { it % step == 0 } + listOf(dates.lastIndex, selected)).distinct().sorted()
    Text(localized("Scala: {{minimum}} – {{maximum}}", "minimum" to formatValue(minimum), "maximum" to formatValue(maximum)), style = MaterialTheme.typography.labelSmall)
    Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }) {
        val margin = 8.dp.toPx()
        val width = (size.width - margin * 2).coerceAtLeast(1f)
        val height = (size.height - margin * 2).coerceAtLeast(1f)
        fun x(index: Int): Float = margin + width * BigDecimal(ChronoUnit.DAYS.between(dates.first(), dates[index]))
            .divide(BigDecimal(span), 12, RoundingMode.HALF_UP).toFloat()
        fun y(value: BigDecimal): Float = margin + height * (1f - (value - minimum).divide(range, 12, RoundingMode.HALF_UP).toFloat())
        if (minimum.signum() <= 0 && maximum.signum() >= 0) drawLine(outline, Offset(margin, y(BigDecimal.ZERO)), Offset(margin + width, y(BigDecimal.ZERO)))
        lines.forEach { line ->
            val path = Path()
            drawn.forEachIndexed { index, point -> if (index == 0) path.moveTo(x(point), y(line.values[point])) else path.lineTo(x(point), y(line.values[point])) }
            drawPath(path, line.color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
            drawCircle(line.color, radius = 4.dp.toPx(), center = Offset(x(selected), y(line.values[selected])))
        }
    }
    Text(localized("Il grafico collega punti campionati; i dettagli permettono di selezionare ogni punto disponibile."), style = MaterialTheme.typography.bodySmall)
}
