package cc.savely.android.modules

import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.ZonedDateTime

internal data class FireDatabaseDefaults(val savings: Double, val income: Double, val expenses: Double, val contribution: Double)

/** useFireDefaultsFromDB: Date.setMonth preserves time-of-day and rolls an invalid day into the next month. */
internal fun fireDatabaseDefaults(assets: List<JSONObject>, transactions: List<JSONObject>, now: ZonedDateTime): FireDatabaseDefaults? {
    val month = YearMonth.from(now).minusMonths(24)
    val threshold = month.atDay(1).plusDays(now.dayOfMonth.toLong() - 1)
        .atTime(now.toLocalTime()).atZone(now.zone).toInstant()
    val recent = transactions.filter {
        it.isNull("deleted_at") && LocalDate.parse(it.getString("date")).atStartOfDay(ZoneOffset.UTC).toInstant() >= threshold
    }
    if (recent.isEmpty()) return null
    val income = recent.filter { it.getString("type") == "income" }.sumOf { it.getDouble("amount") } / 2
    val expenses = recent.filter { it.getString("type") == "expense" }.sumOf { it.getDouble("amount") } / 2
    val savings = assets.filter { it.isNull("sold_at") || it.optString("sold_at").isEmpty() || it.isNull("sold_price") }.sumOf {
        (if (it.isNull("current_price")) it.getDouble("purchase_price") else it.getDouble("current_price")) * it.getDouble("quantity")
    }
    require(listOf(income, expenses, savings).all { it.isFinite() && it >= 0 }) { "Dati FIRE non validi" }
    return FireDatabaseDefaults(savings, income, expenses, (income - expenses).coerceAtLeast(0.0))
}

internal fun fireMonthlyToAnnual(value: String): String {
    val normalized = if (value.contains(',') && value.contains('.')) value.replace(".", "").replace(',', '.') else value.replace(',', '.')
    val number = normalized.trim().toDoubleOrNull()
    return if (number != null && number.isFinite() && number >= 0 && (number * 12).isFinite()) (number * 12).toString()
        else if (value.isBlank()) "" else error("Importo mensile non valido")
}
