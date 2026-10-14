package cc.savely.android.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.savely.android.ui.localized
import java.math.BigDecimal
import java.math.RoundingMode

// Portfolio.tsx palette; stable type indices keep each swatch consistent with its legend.
private val allocationColors = listOf(Color(0xFF22C55E), Color(0xFF8B5CF6), Color(0xFFF59E0B),
    Color(0xFF3B82F6), Color(0xFFEC4899), Color(0xFF14B8A6), Color(0xFF6B7280))

private data class AllocationSlice(val key: String, val label: String, val value: BigDecimal,
    val percent: BigDecimal?, val color: Color)

/** The existing per-currency decimal calculations supply both the chart and its accessible detail. */
@Composable
internal fun PortfolioAllocationCharts(currency: String, rows: List<PortfolioAllocation>, composition: PortfolioComposition?) {
    val types = rows.map { row -> AllocationSlice(row.type?.wire ?: "tcg", localized(row.type?.label ?: "TCG"),
        row.value, row.percent, allocationColors[(row.type?.ordinal ?: allocationColors.size) % allocationColors.size]) }
    AllocationPie(localized("Allocazione per tipo"), currency, "type", types)
    if (composition != null) {
        fun percent(value: BigDecimal): BigDecimal? = if (composition.total.signum() > 0)
            value.multiply(BigDecimal("100")).divide(composition.total, 12, RoundingMode.HALF_UP) else null
        AllocationPie(localized("Distribuzione Liquido/Illiquido"), currency, "liquidity", listOf(
            AllocationSlice("liquid", localized("Liquido"), composition.liquid, percent(composition.liquid), allocationColors[0]),
            AllocationSlice("illiquid", localized("Illiquido"), composition.illiquid, percent(composition.illiquid), allocationColors[1]),
        ))
        if (composition.total.signum() > 0) Text(localized("Liquido {{liquid}} · Illiquido {{illiquid}}",
            "liquid" to formatDashboardPercent(requireNotNull(percent(composition.liquid))),
            "illiquid" to formatDashboardPercent(requireNotNull(percent(composition.illiquid)))))
    }
}

@Composable
private fun AllocationPie(title: String, currency: String, kind: String, slices: List<AllocationSlice>) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    val total = slices.fold(BigDecimal.ZERO) { sum, slice -> sum + slice.value }
    if (total.signum() > 0 && slices.all { it.value.signum() >= 0 }) {
        // Normalize exactly before converting only the bounded drawing angles to Float.
        val sweeps = slices.map { it.value.divide(total, 16, RoundingMode.HALF_UP).multiply(BigDecimal("360")).toFloat() }
        val description = localized("{{label}}: {{value}}", "label" to title, "value" to currency)
        Canvas(Modifier.fillMaxWidth().height(220.dp).testTag("portfolio-$kind-pie-$currency")
            .semantics { contentDescription = description }) {
            val diameter = (minOf(size.width, size.height) - 16.dp.toPx()).coerceAtLeast(0f)
            val origin = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            var start = -90f
            slices.forEachIndexed { index, slice ->
                drawArc(slice.color, start, sweeps[index], useCenter = true, topLeft = origin, size = Size(diameter, diameter))
                start += sweeps[index]
            }
        }
    } else Text(localized("Distribuzione non disponibile: servono importi non negativi e un totale positivo."),
        color = MaterialTheme.colorScheme.error)
    slices.forEach { slice ->
        Row(Modifier.fillMaxWidth().testTag("portfolio-$kind-legend-$currency-${slice.key}")
            .semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(slice.color, CircleShape))
            Text(localized("{{type}}: {{amount}} · {{percent}}", "type" to slice.label,
                "amount" to formatMoney(slice.value, currency),
                "percent" to (slice.percent?.let(::formatDashboardPercent) ?: localized("Non disponibile"))), Modifier.weight(1f))
        }
    }
}
