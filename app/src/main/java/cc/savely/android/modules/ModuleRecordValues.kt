package cc.savely.android.modules

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.URISyntaxException
import java.text.NumberFormat
import java.util.Locale

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
    return rows.filter { it.getString(dateField).take(4) == year.toString() }
}

/** FumoCrudPage uses toFixed(2), including exact binary Double ties, for daily figures. */
internal data class FumoDetailValues(val days: Long, val quantity: String, val dailyCost: String, val monthlyCost: String)

internal fun fumoDetailValues(derived: FumoCalculations.Derived): FumoDetailValues? {
    val days = derived.days ?: return null
    fun decimal(value: Double, currency: Boolean): String {
        require(value.isFinite())
        val formatter = if (currency) NumberFormat.getCurrencyInstance(Locale.ITALY)
            else NumberFormat.getNumberInstance(Locale.ITALY).apply { isGroupingUsed = false }
        return formatter.apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
            roundingMode = RoundingMode.HALF_UP
        }.format(BigDecimal(value))
    }
    val quantity = derived.quantityPerDay?.let { decimal(it, false) } ?: "-"
    // The web displays a dash for a missing or zero daily euro amount.
    val dailyCost = derived.euroPerDay?.takeIf { it != 0.0 }?.let { decimal(it, true) } ?: "-"
    val monthlyCost = derived.monthlyCost?.let { decimal(it, true) } ?: "-"
    return FumoDetailValues(days, quantity, dailyCost, monthlyCost)
}

internal fun fumoDetail(derived: FumoCalculations.Derived): String? = fumoDetailValues(derived)?.let {
    "${it.days} giorni · ${it.quantity}/giorno · ${it.dailyCost}/giorno · ${it.monthlyCost}/mese"
}
