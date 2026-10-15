package cc.savely.android.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.savely.android.ui.LocalAppLanguage
import cc.savely.android.ui.localized
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private enum class FinanceChartGraphic(val label: String) { BAR("Barre"), PIE("Torta"), LINE("Linea") }

/** Content for an existing scroll container; this screen does not own networking or create a repository. */
@Composable
fun FinanceChartsScreen(data: FinanceData, modifier: Modifier = Modifier, today: LocalDate = LocalDate.now()) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var periodName by rememberSaveable { mutableStateOf(FinanceChartPeriod.YEAR.name) }
    var year by rememberSaveable { mutableStateOf(today.year) }
    var month by rememberSaveable { mutableStateOf(today.monthValue) }
    var fromText by rememberSaveable { mutableStateOf(today.withDayOfYear(1).toString()) }
    var toText by rememberSaveable { mutableStateOf(today.withMonth(12).withDayOfMonth(31).toString()) }
    var appliedFrom by rememberSaveable { mutableStateOf(fromText) }
    var appliedTo by rememberSaveable { mutableStateOf(toText) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var typeKey by rememberSaveable { mutableStateOf("all") }
    var currencyKey by rememberSaveable { mutableStateOf("all") }
    var categoryKey by rememberSaveable { mutableStateOf("all") }
    var granularityName by rememberSaveable { mutableStateOf(FinanceChartGranularity.MONTHLY.name) }
    var categoryGraphicName by rememberSaveable { mutableStateOf(FinanceChartGraphic.BAR.name) }
    var timeGraphicName by rememberSaveable { mutableStateOf(FinanceChartGraphic.BAR.name) }
    val period = FinanceChartPeriod.valueOf(periodName)
    val range = DashboardRange(LocalDate.parse(appliedFrom), LocalDate.parse(appliedTo))
    val granularity = FinanceChartGranularity.valueOf(granularityName)
    val locale = Locale.forLanguageTag(LocalAppLanguage.current.tag)
    val availableCurrencies = data.transactions.map { it.currency }.distinct().sorted()
    val availableCategories = data.categories.filter { typeKey == "all" || it.type.wire == typeKey }
    val activeCurrency = currencyKey.takeIf { it == "all" || it in availableCurrencies } ?: "all"
    val activeCategory = categoryKey.takeIf { it == "all" || it == "none" || availableCategories.any { category -> category.id == it } } ?: "all"
    LaunchedEffect(activeCurrency, activeCategory) {
        if (currencyKey != activeCurrency) currencyKey = activeCurrency
        if (categoryKey != activeCategory) categoryKey = activeCategory
    }
    val activeFilter = FinanceChartFilter(
        type = TransactionType.entries.firstOrNull { it.wire == typeKey },
        currency = activeCurrency.takeUnless { it == "all" },
        categoryId = activeCategory.takeUnless { it == "all" || it == "none" },
        uncategorizedOnly = activeCategory == "none",
    )
    val filtered = remember(data.transactions, range, activeFilter) { filterFinanceChartTransactions(data.transactions, range, activeFilter) }
    val currencies = if (activeCurrency == "all") filtered.map { it.currency }.distinct().sorted() else listOf(activeCurrency)

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(localized("Analisi Entrate e Uscite"), style = MaterialTheme.typography.headlineSmall)
        Text(localized("Ogni valuta è mostrata separatamente; nessuna conversione o somma tra valute."), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "Entrate e uscite", "income" to "Analisi Entrate", "expense" to "Analisi Uscite").forEach { (key, label) ->
                FilterChip(typeKey == key, { typeKey = key; categoryKey = "all" }, label = { Text(localized(label)) })
            }
        }
        Selection("Periodo", periodName, FinanceChartPeriod.entries.map { it.name to localized(it.label) }) { periodName = it; error = null }
        if (period == FinanceChartPeriod.YEAR || period == FinanceChartPeriod.MONTH) {
            val years = (data.transactions.map { it.date.year } + today.year + year).distinct().sortedDescending()
            Selection("Anno", year.toString(), years.map { it.toString() to it.toString() }) { year = it.toInt() }
        }
        if (period == FinanceChartPeriod.MONTH) Selection("Mese", month.toString(), Month.values().map {
            it.value.toString() to it.getDisplayName(TextStyle.FULL, locale)
        }) { month = it.toInt() }
        if (period == FinanceChartPeriod.SINCE || period == FinanceChartPeriod.BETWEEN) {
            OutlinedTextField(fromText, { fromText = it }, label = { Text(localized("Dal")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (period == FinanceChartPeriod.BETWEEN) OutlinedTextField(toText, { toText = it },
            label = { Text(localized("Al")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            try {
                val candidate = resolveFinanceChartRange(period, data.transactions, today, year, month, fromText, toText)
                financeChartBucketDates(candidate, granularity)
                financeChartBucketDates(candidate, financeChartCumulativeGranularity(candidate))
                appliedFrom = candidate.from.toString(); appliedTo = candidate.to.toString()
                error = null
                focusManager.clearFocus()
                keyboardController?.hide()
            } catch (failure: IllegalArgumentException) { error = failure.message }
        }) { Text(localized("Applica intervallo")) }
        error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
        Text(localized("Periodo selezionato: {{from}} – {{to}}", "from" to range.from, "to" to range.to))
        Selection("Valuta", activeCurrency, listOf("all" to localized("Tutte le valute")) + availableCurrencies.map { it to it }) { currencyKey = it }
        Selection("Categoria", activeCategory, listOf("all" to localized("Tutte le categorie"), "none" to localized("Senza categoria")) +
            availableCategories.map { it.id to it.name }) { categoryKey = it }
        GranularitySelection(granularity, range) { granularityName = it.name }
        if (filtered.isEmpty()) Text(localized("Nessuna transazione nel periodo selezionato"))
        currencies.forEach { currency ->
            val rows = filtered.filter { it.currency == currency }
            val totals = currencyTotals(rows).firstOrNull() ?: CurrencyTotals(currency, BigDecimal.ZERO, BigDecimal.ZERO)
            val points = remember(rows, range, granularity, currency) { financeChartSeries(rows, range, currency, granularity) }
            ChartCard(Modifier.testTag("chart-time-$currency")) {
                Text(currency, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.testTag("chart-summary-$currency")) {
                    Text(localized("Entrate {{amount}}", "amount" to formatMoney(totals.income, currency)))
                    Text(localized("Uscite {{amount}}", "amount" to formatMoney(totals.expenses, currency)))
                    Text(localized("Netto {{amount}}", "amount" to formatMoney(totals.savings, currency)))
                    Text("${localized("Tasso di risparmio")}: ${financeChartSavingRate(totals).toPlainString().replace('.', ',')}%")
                }
                Text(localized("Andamento entrate e uscite"), style = MaterialTheme.typography.titleMedium)
                GraphicSelection(FinanceChartGraphic.valueOf(timeGraphicName), includePie = false) { timeGraphicName = it.name }
                TimeGraphic(points, currency, FinanceChartGraphic.valueOf(timeGraphicName))
                TimeDataRows(points, currency, granularity)
            }
            val cumulativeGranularity = financeChartCumulativeGranularity(range)
            val cumulative = remember(rows, range, currency, cumulativeGranularity) { financeChartSeries(rows, range, currency, cumulativeGranularity) }
            ChartCard(Modifier.testTag("chart-cumulative-$currency")) {
                Text(localized("Andamento cumulativo del bilancio"), style = MaterialTheme.typography.titleMedium)
                Text(localized("Il saldo cumulativo parte da zero all’inizio del periodo; non è il saldo totale del conto."), style = MaterialTheme.typography.bodySmall)
                Text(localized("Granularità del cumulativo: {{cadence}}", "cadence" to localized(cumulativeGranularity.label)), style = MaterialTheme.typography.labelSmall)
                TimeGraphic(cumulative, currency, FinanceChartGraphic.LINE, cumulative = true)
                TimeDataRows(cumulative, currency, cumulativeGranularity, cumulative = true)
            }
            val categories = remember(rows, data.categories, currency) { financeChartCategoryBreakdown(rows, data.categories, currency) }
            TransactionType.entries.filter { activeFilter.type == null || activeFilter.type == it }.forEach { type ->
                val slices = categories.filter { it.type == type }
                ChartCard(Modifier.testTag("chart-category-$currency-${type.wire}")) {
                    Text(localized(if (type == TransactionType.INCOME) "Distribuzione delle entrate per categoria" else "Distribuzione delle spese per categoria"), style = MaterialTheme.typography.titleMedium)
                    Text(localized("Sono inclusi anche i movimenti senza categoria o con categoria rimossa."), style = MaterialTheme.typography.bodySmall)
                    if (slices.isEmpty()) Text(localized("Nessuna transazione nel periodo selezionato")) else {
                        GraphicSelection(FinanceChartGraphic.valueOf(categoryGraphicName), includePie = true) { categoryGraphicName = it.name }
                        if (FinanceChartGraphic.valueOf(categoryGraphicName) == FinanceChartGraphic.LINE) {
                            CategoryCumulativeComparison(slices, rows.filter { it.type == type }, range, currency, cumulativeGranularity)
                        } else CategoryGraphic(slices, currency, FinanceChartGraphic.valueOf(categoryGraphicName))
                        CategoryDataRows(slices, currency)
                    }
                }
            }
        }
    }
}

/** Reuses the dashboard's applied inclusive range without owning or resetting its period selection. */
@Composable
fun DashboardRangedChart(transactions: List<FinanceTransaction>, range: DashboardRange, modifier: Modifier = Modifier) {
    var granularityName by rememberSaveable { mutableStateOf(FinanceChartGranularity.DAILY.name) }
    var currencyKey by rememberSaveable { mutableStateOf("all") }
    val granularity = FinanceChartGranularity.valueOf(granularityName)
    val rows = remember(transactions, range) { filterFinanceChartTransactions(transactions, range) }
    val currencies = rows.map { it.currency }.distinct().sorted()
    val activeCurrency = currencyKey.takeIf { it == "all" || it in currencies } ?: "all"
    LaunchedEffect(activeCurrency) { if (currencyKey != activeCurrency) currencyKey = activeCurrency }
    ChartCard(modifier) {
        Text(localized("Andamento entrate e uscite"), style = MaterialTheme.typography.titleLarge)
        Selection("Valuta", activeCurrency, listOf("all" to localized("Tutte le valute")) + currencies.map { it to it }) { currencyKey = it }
        GranularitySelection(granularity, range, includeYearly = false) { granularityName = it.name }
        val result = remember(rows, range, granularity, activeCurrency) {
            runCatching { currencies.filter { activeCurrency == "all" || it == activeCurrency }.associateWith { financeChartSeries(rows, range, it, granularity) } }
        }
        if (result.isFailure) Text(localized(result.exceptionOrNull()?.message ?: "Grafico non disponibile"), color = MaterialTheme.colorScheme.error)
        else if (rows.isEmpty()) Text(localized("Nessuna transazione nel periodo selezionato"))
        else result.getOrThrow().forEach { (currency, points) ->
            Text(currency, style = MaterialTheme.typography.titleMedium)
            TimeGraphic(points, currency, FinanceChartGraphic.LINE)
            TimeDataRows(points, currency, granularity)
        }
    }
}

@Composable
private fun ChartCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } }
}

@Composable
private fun GranularitySelection(current: FinanceChartGranularity, range: DashboardRange, includeYearly: Boolean = true, choose: (FinanceChartGranularity) -> Unit) {
    var error by remember(range, current) { mutableStateOf<String?>(null) }
    Selection("Granularità", current.name, FinanceChartGranularity.entries.filter { includeYearly || it != FinanceChartGranularity.YEARLY }.map {
        it.name to localized(it.label)
    }) { selected ->
        try { val candidate = FinanceChartGranularity.valueOf(selected); financeChartBucketDates(range, candidate); choose(candidate); error = null }
        catch (failure: IllegalArgumentException) { error = failure.message }
    }
    error?.let { Text(localized(it), color = MaterialTheme.colorScheme.error) }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun GraphicSelection(selected: FinanceChartGraphic, includePie: Boolean, choose: (FinanceChartGraphic) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FinanceChartGraphic.entries.filter { includePie || it != FinanceChartGraphic.PIE }.forEach { graphic ->
            FilterChip(selected == graphic, { choose(graphic) }, label = { Text(localized(graphic.label)) })
        }
    }
}

/** Float conversion happens only after bounded decimal scaling for pixels; totals and accessible rows remain decimal. */
@Composable
private fun TimeGraphic(points: List<FinanceChartPoint>, currency: String, kind: FinanceChartGraphic, cumulative: Boolean = false) {
    if (points.isEmpty()) return
    val series = if (cumulative) listOf(points.map { it.cumulativeBalance }) else listOf(points.map { it.income }, points.map { it.expenses })
    val maximum = series.maxOf { it.maxOrNull() ?: BigDecimal.ZERO }.max(BigDecimal.ZERO)
    val minimum = series.minOf { it.minOrNull() ?: BigDecimal.ZERO }.min(BigDecimal.ZERO)
    val span = (maximum - minimum).takeIf { it.signum() > 0 } ?: BigDecimal.ONE
    val colors = if (cumulative) listOf(MaterialTheme.colorScheme.primary) else listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.error)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Text(localized("Scala {{min}} – {{max}}", "min" to formatMoney(minimum, currency), "max" to formatMoney(maximum, currency)), style = MaterialTheme.typography.bodySmall)
    if (!cumulative) Text("${localized("Entrate")} · ${localized("Uscite")}", style = MaterialTheme.typography.labelSmall)
    Canvas(Modifier.fillMaxWidth().height(180.dp).testTag(if (cumulative) "chart-cumulative-graphic" else "chart-time-graphic").clearAndSetSemantics { }) {
        val margin = 8.dp.toPx()
        val plotWidth = (size.width - 2 * margin).coerceAtLeast(1f)
        val plotHeight = (size.height - 2 * margin).coerceAtLeast(1f)
        fun y(value: BigDecimal) = margin + plotHeight * (1f - (value - minimum).divide(span, 12, RoundingMode.HALF_UP).toFloat())
        val zeroY = y(BigDecimal.ZERO)
        drawLine(grid, Offset(margin, zeroY), Offset(margin + plotWidth, zeroY), 1.dp.toPx())
        series.forEachIndexed { seriesIndex, values ->
            if (kind == FinanceChartGraphic.BAR) {
                val cell = plotWidth / points.size
                val width = cell * .7f / series.size
                values.forEachIndexed { index, value ->
                    val valueY = y(value)
                    drawRect(colors[seriesIndex], Offset(margin + index * cell + seriesIndex * width, minOf(zeroY, valueY)),
                        Size(width, kotlin.math.abs(valueY - zeroY)))
                }
            } else {
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = margin + if (points.size == 1) plotWidth / 2 else index.toFloat() / (points.size - 1) * plotWidth
                    if (index == 0) path.moveTo(x, y(value)) else path.lineTo(x, y(value))
                    if (points.size == 1) drawCircle(colors[seriesIndex], 3.dp.toPx(), Offset(x, y(value)))
                }
                drawPath(path, colors[seriesIndex], style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        }
    }
    Text(localized("Asse temporale {{from}} – {{to}}", "from" to points.first().date, "to" to points.last().date), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TimeDataRows(points: List<FinanceChartPoint>, currency: String, granularity: FinanceChartGranularity, cumulative: Boolean = false) {
    var page by remember(points, cumulative) { mutableStateOf(0) }
    val locale = Locale.forLanguageTag(LocalAppLanguage.current.tag)
    val formatter = DateTimeFormatter.ofPattern(when (granularity) {
        FinanceChartGranularity.MONTHLY -> "MMM yyyy"
        FinanceChartGranularity.YEARLY -> "yyyy"
        else -> "dd MMM yyyy"
    }, locale)
    val pages = ((points.size + 19) / 20).coerceAtLeast(1)
    Text(localized("Dati del grafico"), style = MaterialTheme.typography.titleSmall)
    points.drop(page * 20).take(20).forEach { point ->
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
            Text(point.date.format(formatter), style = MaterialTheme.typography.labelLarge)
            if (cumulative) Text(localized("Cumulativo {{amount}}", "amount" to formatMoney(point.cumulativeBalance, currency)))
            else {
                Text(localized("Entrate {{amount}}", "amount" to formatMoney(point.income, currency)))
                Text(localized("Uscite {{amount}}", "amount" to formatMoney(point.expenses, currency)))
                Text(localized("Netto {{amount}}", "amount" to formatMoney(point.net, currency)))
            }
        }
    }
    DataPagination(page, pages, points.size) { page = it }
}

private data class CategoryGraphicSlice(val name: String, val amount: BigDecimal, val color: Color)

private data class CategoryTimeLine(val name: String, val color: Color, val points: List<FinanceChartPoint>)

/** Comparison is bounded visually, while every individual category remains selectable in the exact data table. */
@Composable
private fun CategoryCumulativeComparison(
    slices: List<FinanceChartCategory>, rows: List<FinanceTransaction>, range: DashboardRange,
    currency: String, granularity: FinanceChartGranularity,
) {
    val palette = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.error,
        Color(0xFF00796B), Color(0xFF795548), Color(0xFF5C6BC0), Color(0xFFEF6C00), Color(0xFF607D8B), Color(0xFFAD1457))
    val labels = slices.map { it.name ?: localized(if (it.categoryId == null) "Senza categoria" else "Categoria rimossa") }
    val othersLabel = localized("Altre categorie")
    val type = slices.first().type
    fun cumulative(categoryRows: List<FinanceTransaction>): List<FinanceChartPoint> =
        financeChartSeries(categoryRows, range, currency, granularity).let { points ->
            // A category expense is a positive accumulation; account balance expenses subtract instead.
            if (type == TransactionType.EXPENSE) points.map { it.copy(cumulativeBalance = -it.cumulativeBalance) } else points
        }
    val series = remember(slices, rows, range, currency, granularity, labels, othersLabel, palette) {
        slices.take(10).mapIndexed { index, slice ->
            CategoryTimeLine(labels[index], chartCategoryColor(slice.color, palette[index]),
                cumulative(rows.filter { it.categoryId == slice.categoryId }))
        } + if (slices.size > 10) {
            val otherIds = slices.drop(10).map { it.categoryId }.toSet()
            listOf(CategoryTimeLine(othersLabel, palette.first(), cumulative(rows.filter { it.categoryId in otherIds })))
        } else emptyList()
    }
    Text(localized("Andamento cumulativo per categoria"), style = MaterialTheme.typography.labelLarge)
    Text(localized("Granularità del cumulativo: {{cadence}}", "cadence" to localized(granularity.label)), style = MaterialTheme.typography.labelSmall)
    if (slices.size > 10) Text(localized("Il grafico raggruppa le categorie oltre le prime 10; il dettaglio contiene tutte le categorie."), style = MaterialTheme.typography.bodySmall)
    val maximum = series.maxOf { line -> line.points.maxOf { it.cumulativeBalance } }.max(BigDecimal.ZERO)
    val minimum = series.minOf { line -> line.points.minOf { it.cumulativeBalance } }.min(BigDecimal.ZERO)
    val span = (maximum - minimum).takeIf { it.signum() > 0 } ?: BigDecimal.ONE
    Text(localized("Scala {{min}} – {{max}}", "min" to formatMoney(minimum, currency), "max" to formatMoney(maximum, currency)), style = MaterialTheme.typography.bodySmall)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(200.dp).testTag("chart-category-lines").clearAndSetSemantics { }) {
        val margin = 8.dp.toPx()
        val width = (size.width - 2 * margin).coerceAtLeast(1f)
        val height = (size.height - 2 * margin).coerceAtLeast(1f)
        fun y(value: BigDecimal) = margin + height * (1f - (value - minimum).divide(span, 12, RoundingMode.HALF_UP).toFloat())
        drawLine(grid, Offset(margin, y(BigDecimal.ZERO)), Offset(margin + width, y(BigDecimal.ZERO)), 1.dp.toPx())
        series.forEach { line ->
            val path = Path()
            line.points.forEachIndexed { index, point ->
                val x = margin + if (line.points.size == 1) width / 2 else index.toFloat() / (line.points.size - 1) * width
                if (index == 0) path.moveTo(x, y(point.cumulativeBalance)) else path.lineTo(x, y(point.cumulativeBalance))
                if (line.points.size == 1) drawCircle(line.color, 3.dp.toPx(), Offset(x, y(point.cumulativeBalance)))
            }
            drawPath(path, line.color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }
    series.forEach { line ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics(mergeDescendants = true) { }) {
            Canvas(Modifier.height(18.dp).weight(.08f).clearAndSetSemantics { }) {
                drawLine(line.color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx())
            }
            Text(localized("{{name}}: {{amount}}", "name" to line.name,
                "amount" to formatMoney(line.points.last().cumulativeBalance, currency)), modifier = Modifier.weight(.92f))
        }
    }
    var selectedIndex by remember(slices, range) { mutableStateOf(0) }
    Selection("Dettaglio temporale della categoria", selectedIndex.toString(), slices.mapIndexed { index, _ -> index.toString() to labels[index] }) {
        selectedIndex = it.toInt()
    }
    val selected = slices[selectedIndex]
    val selectedPoints = remember(rows, selected, range, currency, granularity) { cumulative(rows.filter { it.categoryId == selected.categoryId }) }
    TimeDataRows(selectedPoints, currency, granularity, cumulative = true)
}

@Composable
private fun CategoryGraphic(slices: List<FinanceChartCategory>, currency: String, kind: FinanceChartGraphic) {
    val fallbackColor = MaterialTheme.colorScheme.secondary
    val othersLabel = localized("Altre categorie")
    val visible = slices.take(10).map { CategoryGraphicSlice(it.name ?: localized(if (it.categoryId == null) "Senza categoria" else "Categoria rimossa"), it.amount, chartCategoryColor(it.color, fallbackColor)) } +
        if (slices.size > 10) listOf(CategoryGraphicSlice(othersLabel, slices.drop(10).fold(BigDecimal.ZERO) { sum, row -> sum + row.amount }, fallbackColor)) else emptyList()
    val total = slices.fold(BigDecimal.ZERO) { sum, row -> sum + row.amount }
    if (slices.size > 10) Text(localized("Il grafico raggruppa le categorie oltre le prime 10; il dettaglio contiene tutte le categorie."), style = MaterialTheme.typography.bodySmall)
    if (kind == FinanceChartGraphic.PIE && (total.signum() <= 0 || slices.any { it.amount.signum() < 0 })) {
        Text(localized("La torta richiede importi non negativi e un totale positivo. Usa Barre o il dettaglio."))
        return
    }
    if (kind == FinanceChartGraphic.PIE) Canvas(Modifier.fillMaxWidth().height(200.dp).testTag("chart-category-pie").clearAndSetSemantics { }) {
        val diameter = minOf(size.width, size.height) - 12.dp.toPx()
        var angle = -90f
        visible.forEach { slice ->
            val sweep = slice.amount.divide(total, 16, RoundingMode.HALF_UP).multiply(BigDecimal("360")).toFloat()
            drawArc(slice.color, angle, sweep, true, Offset((size.width - diameter) / 2, (size.height - diameter) / 2), Size(diameter, diameter))
            angle += sweep
        }
    }
    val maxAbs = visible.maxOfOrNull { it.amount.abs() }?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
    visible.forEach { slice ->
        Text(localized("{{name}}: {{amount}}", "name" to slice.name, "amount" to formatMoney(slice.amount, currency)), style = MaterialTheme.typography.labelMedium)
        if (kind == FinanceChartGraphic.BAR) Canvas(Modifier.fillMaxWidth().height(12.dp).clearAndSetSemantics { }) {
            val width = slice.amount.abs().divide(maxAbs, 12, RoundingMode.HALF_UP).toFloat() * size.width / 2
            val center = size.width / 2
            drawLine(Color.Gray, Offset(center, 0f), Offset(center, size.height), 1.dp.toPx())
            drawRect(slice.color, Offset(if (slice.amount.signum() < 0) center - width else center, 0f), Size(width, size.height))
        }
    }
}

@Composable
private fun CategoryDataRows(slices: List<FinanceChartCategory>, currency: String) {
    var page by remember(slices) { mutableStateOf(0) }
    val pages = ((slices.size + 19) / 20).coerceAtLeast(1)
    Text(localized("Dettaglio per Categoria"), style = MaterialTheme.typography.titleSmall)
    slices.drop(page * 20).take(20).forEach { slice ->
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
            Text(slice.name ?: localized(if (slice.categoryId == null) "Senza categoria" else "Categoria rimossa"))
            Text(formatMoney(slice.amount, currency))
            Text(formatDashboardPercent(slice.percent))
        }
    }
    DataPagination(page, pages, slices.size) { page = it }
}

@Composable
private fun DataPagination(page: Int, pages: Int, count: Int, choose: (Int) -> Unit) {
    Text(localized("Pagina {{page}} di {{pages}} · {{count}} righe", "page" to page + 1, "pages" to pages, "count" to count), style = MaterialTheme.typography.bodySmall)
    if (pages > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton({ choose(page - 1) }, enabled = page > 0) { Text(localized("Pagina precedente")) }
        TextButton({ choose(page + 1) }, enabled = page + 1 < pages) { Text(localized("Pagina successiva")) }
    }
}

private fun chartCategoryColor(value: String?, fallback: Color): Color =
    if (value != null && Regex("#[0-9a-fA-F]{6}").matches(value)) Color(android.graphics.Color.parseColor(value)) else fallback
