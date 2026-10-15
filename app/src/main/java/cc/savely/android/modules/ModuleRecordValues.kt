package cc.savely.android.modules

import org.json.JSONObject
import cc.savely.android.data.ApiException
import cc.savely.android.data.parseBackendInstant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.URISyntaxException
import java.text.NumberFormat
import java.util.Locale

/** Only an explicit HTTP rejection proves a generic form save did not commit. */
internal fun recordMutationRequiresRefresh(failure: Exception): Boolean {
    val status = (failure as? ApiException)?.status
    return status == null || status !in 400..499 || status == 408
}

/** Fumo's toFixed(2) preserves the exact binary input, including 0.125 -> 0.13. */
internal fun fumoMoney(value: Double): String {
    require(value.isFinite())
    return NumberFormat.getCurrencyInstance(Locale.ITALY).apply {
        minimumFractionDigits = 2; maximumFractionDigits = 2; roundingMode = RoundingMode.HALF_UP
    }.format(BigDecimal(value))
}

/** CardTrader's proxy reports euros; a non-EUR record must retain its manually declared unit. */
internal fun tcgRecordCurrency(row: JSONObject): String = if (row.isNull("currency")) "EUR"
    else row.getString("currency").trim().uppercase(Locale.ROOT)

internal fun tcgAllowsCardTraderPrice(row: JSONObject): Boolean = tcgRecordCurrency(row) == "EUR"

/** Manga.tsx saves the trimmed nullable URL; validation must not rewrite stored metadata. */
internal fun mangaCoverUrl(value: String): String? {
    val text = value.trim()
    if (text.isEmpty()) return null
    val uri = try { URI(text) } catch (_: URISyntaxException) {
        throw IllegalArgumentException("URL copertina: inserisci un URL HTTP o HTTPS valido")
    }
    require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port in 1..65535)) {
        "URL copertina: inserisci un URL HTTP o HTTPS valido, senza credenziali"
    }
    return text
}

/** Only monthly details are filtered; the caller keeps the original rows for yearly summaries. */
internal fun currentYearDetails(table: String, rows: List<JSONObject>, year: Int): List<JSONObject> {
    val dateField = when (table) {
        "poker_hourly_earnings", "poker_rakeback" -> "date"
        "thc" -> "data_acquisto"
        "liquido_sigaretta" -> "data_arrivo"
        else -> return rows
    }
    return rows.filter { moduleRecordDate(it, dateField).take(4) == year.toString() }
}

/** readArrivo in fumoCrud.ts uses the configured date, then its legacy aliases. */
internal fun moduleRecordDate(row: JSONObject, field: String): String =
    listOf(field, "data_arrivo", "data_acquisto").firstNotNullOfOrNull { key ->
        if (row.isNull(key)) null else row.getString(key)
    } ?: error("Data del record mancante")

/** JS Date parses date-only values as UTC and truncates fractional timestamps to milliseconds. */
private fun fumoBackendEpochMillis(raw: String): Long =
    if (Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(raw))
        LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    else parseBackendInstant(raw).toEpochMilli()

/** FumoCrud.computeDerived uses ceil(elapsed milliseconds / 86400000), including offsets. */
internal fun fumoLegacyDerived(arrival: String, finished: String?, quantity: Double?, cost: Double): FumoCalculations.Derived {
    require(cost.isFinite() && (quantity == null || quantity.isFinite())) { "Dati Fumo non validi" }
    if (finished == null) return FumoCalculations.Derived(null, null, null, null)
    val elapsed = Math.subtractExact(fumoBackendEpochMillis(finished), fumoBackendEpochMillis(arrival))
    val unit = 86_400_000L
    val days = Math.floorDiv(elapsed, unit) + if (Math.floorMod(elapsed, unit) == 0L) 0 else 1
    return if (days > 0) FumoCalculations.Derived(days, quantity?.div(days), cost / days, cost / days * 30)
        else FumoCalculations.Derived(null, null, null, null)
}

/** SQL DATE stays civil; TIMESTAMPTZ follows the device day with its raw instant preserved separately. */
internal fun fumoEndDateForEditor(raw: String, zone: ZoneId = ZoneId.systemDefault()): String =
    if (Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(raw)) LocalDate.parse(raw).toString()
    else parseBackendInstant(raw).atZone(zone).toLocalDate().toString()

/** Validate the retained instant, not its potentially different editor day in a local zone. */
internal fun fumoEndNotBeforeArrival(arrival: String, finished: String?): Boolean =
    finished == null || fumoBackendEpochMillis(finished) >= fumoBackendEpochMillis(arrival)

/** An unrelated edit must not erase the original end timestamp or its offset/fraction. */
internal fun fumoEndValueForSave(original: String?, edited: String, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (edited.isBlank()) return null
    val day = LocalDate.parse(edited).toString()
    return if (original != null && day == fumoEndDateForEditor(original, zone)) original else day
}

/** The web preserves saved derived fields; only finished records with missing days are recomputed. */
internal fun fumoDisplayedDerived(row: JSONObject, liquid: Boolean): FumoCalculations.Derived {
    fun optional(key: String): Double? = if (row.isNull(key)) null else row.getDouble(key).also {
        require(it.isFinite()) { "Valore Fumo non valido" }
    }
    val fallbackQuantity = optional("quantita") ?: optional("grammi")
    // FumoCrudPage's legacy fallback excludes millilitri-only records and leaves the daily quantity column unchanged.
    val fallback = if (!row.isNull("data_finito") && fallbackQuantity != null && row.isNull("giorni_durata"))
        fumoLegacyDerived(moduleRecordDate(row, if (liquid) "data_arrivo" else "data_acquisto"),
            row.getString("data_finito"), fallbackQuantity, row.getDouble("costo")) else null
    val days = (fallback?.days?.toDouble() ?: optional("giorni_durata"))?.also { require(it % 1 == 0.0 && it >= 0 && it <= Int.MAX_VALUE) }
    val daily = if (fallback != null) fallback.euroPerDay else optional("euro_al_giorno")
    val savedMonthly = if (fallback != null) fallback.monthlyCost else optional("costo_mensile")
    val monthly = savedMonthly?.takeIf { it != 0.0 }
        ?: days?.takeIf { it > 0 && row.getDouble("costo") != 0.0 }?.let { row.getDouble("costo") / it * 30 }
    return FumoCalculations.Derived(days?.toLong()?.takeIf { it > 0 },
        optional(if (liquid) "millilitri_al_giorno" else "grammi_al_giorno"), daily, monthly)
}

internal fun fumoQuantity(row: JSONObject): Double = listOf("quantita", "grammi", "millilitri").firstNotNullOfOrNull {
    if (row.isNull(it)) null else row.getDouble(it)
} ?: 0.0

/** Like JavaScript toFixed(2): preserve the binary input before rounding away from zero. */
internal fun moduleTwoDecimals(value: Double): String {
    require(value.isFinite())
    return NumberFormat.getNumberInstance(Locale.ITALY).apply {
        isGroupingUsed = false; minimumFractionDigits = 2; maximumFractionDigits = 2; roundingMode = RoundingMode.HALF_UP
    }.format(BigDecimal(value))
}

/** PokerHourlyEarnings displays floor(hours) and Math.round(fraction * 60), including legacy 60m. */
internal fun pokerHours(value: Double): String {
    require(value.isFinite() && value >= 0)
    val whole = kotlin.math.floor(value)
    val minutes = kotlin.math.floor((value % 1) * 60 + 0.5).toInt()
    return "${whole.toLong()}h ${minutes.toString().padStart(2, '0')}m"
}

/** Use the web's displayed entries: saved days stay intact, missing days follow its legacy fallback. */
internal fun fumoAnnualLiquidAverage(rows: List<JSONObject>): Double {
    val quantity = rows.sumOf(::fumoQuantity)
    val days = rows.sumOf { fumoDisplayedDerived(it, true).days?.toDouble() ?: 0.0 }
    require(quantity.isFinite() && days.isFinite()) { "Riepilogo Fumo non valido" }
    return if (days > 0) quantity / days else 0.0
}

/** FumoCrudPage uses toFixed(2), including exact binary Double ties, for daily figures. */
internal data class FumoDetailValues(val days: Long?, val quantity: String, val dailyCost: String, val monthlyCost: String)

internal fun fumoDetailValues(derived: FumoCalculations.Derived): FumoDetailValues? {
    if (derived.days == null && derived.quantityPerDay == null && derived.euroPerDay == null && derived.monthlyCost == null) return null
    val days = derived.days
    val quantity = derived.quantityPerDay?.let(::moduleTwoDecimals) ?: "-"
    // The web displays a dash for a missing or zero daily euro amount.
    val dailyCost = derived.euroPerDay?.takeIf { it != 0.0 }?.let(::fumoMoney) ?: "-"
    val monthlyCost = derived.monthlyCost?.let(::fumoMoney) ?: "-"
    return FumoDetailValues(days, quantity, dailyCost, monthlyCost)
}

internal fun fumoDetail(derived: FumoCalculations.Derived): String? = fumoDetailValues(derived)?.let {
    "${it.days ?: "-"} giorni · ${it.quantity}/giorno · ${it.dailyCost}/giorno · ${it.monthlyCost}/mese"
}
