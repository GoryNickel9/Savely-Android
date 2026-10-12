package cc.savely.android.modules

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.savely.android.ui.localized
import java.text.NumberFormat
import java.util.Locale

internal fun switchFireAmountUnit(raw: String, toMonthly: Boolean): String = runCatching {
    val normalized = if (raw.contains(',') && raw.contains('.')) raw.replace(".", "").replace(',', '.') else raw.replace(',', '.')
    if (toMonthly) (normalized.toDouble() / 12).toString() else fireMonthlyToAnnual(raw)
}.getOrDefault(raw)

@Composable
internal fun FireAmountInput(annualLabel: String, monthlyLabel: String, value: String, monthly: Boolean,
    canToggle: Boolean, tag: String, onChange: (String) -> Unit, toggle: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(localized(if (monthly) monthlyLabel else annualLabel)) },
            singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
        if (canToggle) TextButton(toggle, Modifier.testTag(tag)) { Text(localized(if (monthly) "Usa importi annuali" else "Usa importi mensili")) }
    }
}

@Composable
internal fun FireProjectionChart(points: List<FireCalculations.Projection>, savings: Double, target: Double) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var showReal by rememberSaveable { mutableStateOf(true) }
    val money = remember { NumberFormat.getCurrencyInstance(Locale.ITALY) }
    val valid = points.isNotEmpty() && target.isFinite() && savings.isFinite() &&
        points.all { it.portfolio.isFinite() && it.inflationAdjusted.isFinite() && it.totalContributions.isFinite() }
    Card(Modifier.fillMaxWidth().testTag("fire-projection")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(localized("Progressione al FIRE"), style = MaterialTheme.typography.titleMedium)
            if (!valid) {
                Text(localized("Proiezione non rappresentabile: controlla importi e tassi."), color = MaterialTheme.colorScheme.error)
            } else {
                val rawProgress = savings / (if (target > 0) target else 1.0) * 100
                val progress = rawProgress.coerceIn(0.0, 100.0)
                Text(localized("{{progress}}% dell’obiettivo · {{savings}} su {{target}}", "progress" to if (rawProgress > 999) ">999" else String.format(Locale.ITALY, "%.1f", rawProgress),
                    "savings" to money.format(savings), "target" to money.format(target)))
                LinearProgressIndicator(progress = { progress.toFloat() / 100 }, modifier = Modifier.fillMaxWidth().testTag("fire-progress-bar"))
                Text(localized("Traguardi: 25% · 50% · 75% · FIRE 100%"), style = MaterialTheme.typography.bodySmall)
                Text(localized("Proiezione Portfolio"), style = MaterialTheme.typography.titleMedium)
                Text(localized("Linea continua: valore nominale · linea tratteggiata: valore reale"), style = MaterialTheme.typography.bodySmall)
                val primary = MaterialTheme.colorScheme.onPrimaryContainer
                val secondary = MaterialTheme.colorScheme.secondary
                val guide = MaterialTheme.colorScheme.outline
                val chartDescription = localized("Proiezione FIRE. I valori per anno sono disponibili sotto il grafico.")
                val maximum = maxOf(target, points.maxOf { maxOf(it.portfolio, if (showReal) it.inflationAdjusted else 0.0) }, 1.0)
                Text(localized("Scala: {{minimum}} – {{maximum}}", "minimum" to money.format(0), "maximum" to money.format(maximum)),
                    modifier = Modifier.fillMaxWidth().testTag("fire-projection-scale"), style = MaterialTheme.typography.bodySmall)
                Canvas(Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = chartDescription }) {
                    fun y(value: Double) = (size.height * (1 - (value / maximum).coerceIn(0.0, 1.0))).toFloat()
                    listOf(0.25, 0.50, 0.75, 1.0).forEach { milestone ->
                        drawLine(guide, Offset(0f, y(target * milestone)), Offset(size.width, y(target * milestone)),
                            strokeWidth = if (milestone == 1.0) 2.dp.toPx() else 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                    }
                    fun path(real: Boolean) = Path().apply {
                        points.forEachIndexed { index, point ->
                            val x = size.width * index / (points.size - 1).coerceAtLeast(1)
                            val py = y(if (real) point.inflationAdjusted else point.portfolio)
                            if (index == 0) moveTo(x, py) else lineTo(x, py)
                        }
                    }
                    drawPath(path(false), primary, style = Stroke(2.dp.toPx()))
                    if (showReal) drawPath(path(true), secondary, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                    val index = selected.coerceIn(points.indices)
                    drawLine(guide, Offset(size.width * index / (points.size - 1).coerceAtLeast(1), 0f),
                        Offset(size.width * index / (points.size - 1).coerceAtLeast(1), size.height))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(localized("{{year}} · {{age}} anni", "year" to points.first().year, "age" to points.first().age),
                        modifier = Modifier.weight(1f).testTag("fire-projection-start"), style = MaterialTheme.typography.bodySmall)
                    Text(localized("{{year}} · {{age}} anni", "year" to points.last().year, "age" to points.last().age),
                        modifier = Modifier.weight(1f).testTag("fire-projection-end"), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(showReal, { showReal = it }, Modifier.testTag("fire-projection-real"))
                    Text(localized("Mostra valore reale"))
                }
                val point = points[selected.coerceIn(points.indices)]
                Text(localized("Anno selezionato: {{year}} · {{age}} anni", "year" to point.year, "age" to point.age))
                if (points.size > 1) Slider(selected.coerceIn(points.indices).toFloat(), { selected = it.toInt() },
                    valueRange = 0f..points.lastIndex.toFloat(), steps = (points.size - 2).coerceAtLeast(0), modifier = Modifier.testTag("fire-projection-year"))
                Text(localized("{{amount}} · valore reale {{real}}\nVersamento {{contribution}} · totale versato {{total}}",
                    "amount" to money.format(point.portfolio), "real" to money.format(point.inflationAdjusted),
                    "contribution" to money.format(point.contributions), "total" to money.format(point.totalContributions)))
            }
        }
    }
}
