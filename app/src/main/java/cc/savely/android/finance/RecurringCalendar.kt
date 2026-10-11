package cc.savely.android.finance

import java.time.LocalDate
import java.time.YearMonth

data class RecurringOccurrence(val recurring: FinanceRecurring, val date: LocalDate) {
    val key: String get() = "${recurring.id}:$date"
}

/** Read-only projections from the next saved due date, never before that first occurrence.
 * Repeated nextDueDate calls intentionally retain month-end clamp drift used by actual processing.
 * Paused records generate no dates; this does not promise that backend monthly dedup creates every weekly occurrence.
 */
fun recurringCalendarOccurrences(rows: List<FinanceRecurring>, month: YearMonth): List<RecurringOccurrence> {
    val from = month.atDay(1)
    val until = month.atEndOfMonth()
    val result = mutableListOf<RecurringOccurrence>()
    rows.filter { it.active }.forEach { recurring ->
        var date = recurring.nextDueDate
        var steps = 0
        while (date <= until) {
            require(steps++ < 120_000) { "Proiezione calendario troppo estesa" }
            if (date >= from) result.add(RecurringOccurrence(recurring, date))
            val next = nextDueDate(date, recurring.frequency, recurring.weekInterval)
            check(next > date) { "Scadenza ricorrente non valida" }
            date = next
        }
    }
    return result.sortedWith(compareBy<RecurringOccurrence> { it.date }.thenBy { it.recurring.id })
}

/** Seven Monday-first columns with null placeholders outside the selected month. */
fun recurringCalendarWeeks(month: YearMonth): List<List<LocalDate?>> {
    val days = mutableListOf<LocalDate?>()
    repeat(month.atDay(1).dayOfWeek.value - 1) { days.add(null) }
    for (day in 1..month.lengthOfMonth()) days.add(month.atDay(day))
    while (days.size % 7 != 0) days.add(null)
    return days.chunked(7)
}
