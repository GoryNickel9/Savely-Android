package cc.savely.android.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

data class RecurringSuggestion(
    val key: String,
    val description: String,
    val currency: String,
    val medianAmount: BigDecimal,
    val frequency: RecurringFrequency,
    val lastDate: LocalDate,
    val nextDueDate: LocalDate,
    val occurrenceCount: Int,
    val highConfidence: Boolean,
)

private val whitespace = Regex("\\s+")
private val references = Regex("\\b\\d{4,}\\b")
private val paymentPrefix = Regex("^(pagamento|payment|acquisto|purchase|addebito|charge)\\s+")

/** Matches web recurringDetection normalization, including reference and payment-prefix removal. */
internal fun normalizeRecurringDescription(description: String): String = description.lowercase(Locale.ROOT).trim()
    .replace(whitespace, " ").replace(references, "").trim().replace(paymentPrefix, "").trim()

internal fun recurringSuggestionKey(description: String, currency: String): String =
    "$currency:${normalizeRecurringDescription(description)}"

private data class Cadence(val frequency: RecurringFrequency, val days: Long, val tolerance: Long)
private val cadences = listOf(
    Cadence(RecurringFrequency.WEEKLY, 7, 2),
    Cadence(RecurringFrequency.MONTHLY, 30, 4),
    Cadence(RecurringFrequency.QUARTERLY, 90, 10),
    Cadence(RecurringFrequency.YEARLY, 365, 20),
)

/** Web heuristic: at least 3 expenses, max/min <= 1.10, mean calendar-day gap within tolerance.
 * Currency is part of the grouping key so unrelated monetary units never form one candidate.
 * Confidence describes this heuristic; it is not a statistical probability.
 */
fun detectRecurringSuggestions(transactions: List<FinanceTransaction>): List<RecurringSuggestion> = transactions
    .filter { it.type == TransactionType.EXPENSE && normalizeRecurringDescription(it.description).isNotEmpty() }
    .groupBy { recurringSuggestionKey(it.description, it.currency) }
    .mapNotNull { (key, group) ->
        if (group.size < 3) return@mapNotNull null
        val amounts = group.map { it.amount }.sorted()
        val min = amounts.first()
        val max = amounts.last()
        if (min.signum() <= 0 || max > min * BigDecimal("1.10")) return@mapNotNull null
        val ordered = group.sortedWith(compareBy<FinanceTransaction> { it.date }.thenBy { it.id })
        // Sum of adjacent gaps telescopes; compare exact integer bounds rather than a rounded mean.
        val span = ChronoUnit.DAYS.between(ordered.first().date, ordered.last().date)
        val gapCount = (ordered.size - 1).toLong()
        val cadence = cadences.firstOrNull { span in (it.days - it.tolerance) * gapCount..(it.days + it.tolerance) * gapCount }
            ?: return@mapNotNull null
        val middle = amounts.size / 2
        val median = if (amounts.size % 2 == 0) (amounts[middle - 1] + amounts[middle]).divide(BigDecimal("2")) else amounts[middle]
        val last = ordered.last()
        RecurringSuggestion(key, last.description, last.currency, median.setScale(2, RoundingMode.HALF_UP),
            cadence.frequency, last.date, last.date.plusDays(cadence.days), group.size,
            group.size >= 4 && max <= min * BigDecimal("1.001"))
    }.sortedWith(compareByDescending<RecurringSuggestion> { it.highConfidence }.thenByDescending { it.occurrenceCount }.thenBy { it.key })

fun availableRecurringSuggestions(data: FinanceData): List<RecurringSuggestion> {
    // Inactive expenses also remain tracked, as in useRecurringCandidates.ts.
    val tracked = data.recurring.map { recurringSuggestionKey(it.name, it.currency) }.toSet()
    return detectRecurringSuggestions(data.transactions).filter { it.key !in tracked && it.key !in data.ignoredRecurringSuggestions }
}

interface RecurringDismissals {
    fun keys(userId: String): Set<String>
    fun ignore(userId: String, key: String)
}

class MemoryRecurringDismissals : RecurringDismissals {
    private val values = mutableMapOf<String, Set<String>>()
    @Synchronized override fun keys(userId: String): Set<String> = values[userId].orEmpty().toSet()
    @Synchronized override fun ignore(userId: String, key: String) { values[userId] = keys(userId) + key }
}
