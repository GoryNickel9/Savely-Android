package cc.savely.android.finance

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RecurringSuggestionsTest {
    private fun expenses(days: Long = 30, count: Int = 3, name: String = "Netflix", currency: String = "EUR", amounts: List<String> = emptyList()): List<FinanceTransaction> =
        (0 until count).map { index -> FinanceTransaction("tx-$index", TransactionType.EXPENSE,
            BigDecimal(amounts.getOrElse(index) { "12.99" }), currency,
            LocalDate.parse("2026-01-15").plusDays(index * days), name, null) }

    @Test fun `normalization follows payment prefixes references case and whitespace`() {
        assertEquals("netflix com", normalizeRecurringDescription("  NETFLIX   COM  "))
        assertEquals("spesa amazon", normalizeRecurringDescription("Spesa Amazon 1234567890"))
        assertEquals("spotify", normalizeRecurringDescription("ADDEBITO Spotify"))
        assertEquals("netflix", normalizeRecurringDescription("Pagamento Netflix"))
        assertEquals("", normalizeRecurringDescription("123456"))
    }

    @Test fun `three occurrences suffice and four stable amounts increase confidence`() {
        val three = detectRecurringSuggestions(expenses()).single()
        assertEquals(RecurringFrequency.MONTHLY, three.frequency)
        assertEquals(3, three.occurrenceCount)
        assertFalse(three.highConfidence)
        assertTrue(detectRecurringSuggestions(expenses(count = 4)).single().highConfidence)
        assertTrue(detectRecurringSuggestions(expenses(count = 2)).isEmpty())
    }

    @Test fun `each web cadence and inclusive mean tolerance is recognized`() {
        listOf(7L to RecurringFrequency.WEEKLY, 30L to RecurringFrequency.MONTHLY,
            90L to RecurringFrequency.QUARTERLY, 365L to RecurringFrequency.YEARLY).forEach { (days, frequency) ->
            assertEquals(frequency, detectRecurringSuggestions(expenses(days)).single().frequency)
        }
        assertEquals(RecurringFrequency.MONTHLY, detectRecurringSuggestions(expenses(34)).single().frequency)
        assertTrue(detectRecurringSuggestions(expenses(35)).isEmpty())
    }

    @Test fun `mean gap matches web heuristic even when individual gaps vary`() {
        val dates = listOf("2026-01-01", "2026-01-06", "2026-03-02")
        val rows = expenses().mapIndexed { index, tx -> tx.copy(date = LocalDate.parse(dates[index])) }
        assertEquals(RecurringFrequency.MONTHLY, detectRecurringSuggestions(rows).single().frequency)
        val irregular = expenses().mapIndexed { index, tx -> tx.copy(date = LocalDate.parse("2026-01-01").plusDays(listOf(0L, 3L, 153L)[index])) }
        assertTrue(detectRecurringSuggestions(irregular).isEmpty())
    }

    @Test fun `amount variation compares exact decimal boundary`() {
        assertEquals(1, detectRecurringSuggestions(expenses(amounts = listOf("10", "10.50", "11"))).size)
        assertTrue(detectRecurringSuggestions(expenses(amounts = listOf("10", "10.50", "11.01"))).isEmpty())
        assertTrue(detectRecurringSuggestions(expenses(amounts = listOf("0", "0", "0"))).isEmpty())
        assertTrue(detectRecurringSuggestions(expenses(amounts = listOf("-1", "-1", "-1"))).isEmpty())
    }

    @Test fun `high confidence boundary and even median preserve half cent before rounding`() {
        assertTrue(detectRecurringSuggestions(expenses(count = 4, amounts = listOf("100", "100.10", "100", "100"))).single().highConfidence)
        assertFalse(detectRecurringSuggestions(expenses(count = 4, amounts = listOf("100", "100.11", "100", "100"))).single().highConfidence)
        val median = detectRecurringSuggestions(expenses(count = 4, amounts = listOf("10.00", "10.01", "10.00", "10.01"))).single().medianAmount
        assertEquals(BigDecimal("10.01"), median)
    }

    @Test fun `calendar dates fixed interval and latest description match web`() {
        val dates = listOf("2026-01-15", "2026-02-15", "2026-03-15")
        val names = listOf("Pagamento Netflix 123456", "NETFLIX", "Addebito Netflix 654321")
        val rows = expenses().mapIndexed { index, tx -> tx.copy(date = LocalDate.parse(dates[index]), description = names[index]) }
        val suggestion = detectRecurringSuggestions(rows.reversed()).single()
        assertEquals("Addebito Netflix 654321", suggestion.description)
        assertEquals(LocalDate.parse("2026-04-14"), suggestion.nextDueDate)
        assertEquals("EUR:netflix", suggestion.key)
    }

    @Test fun `income empty description and insufficient same currency evidence are excluded`() {
        assertTrue(detectRecurringSuggestions(expenses().map { it.copy(type = TransactionType.INCOME) }).isEmpty())
        assertTrue(detectRecurringSuggestions(expenses(name = " ")).isEmpty())
        assertTrue(detectRecurringSuggestions(expenses().mapIndexed { i, tx -> tx.copy(currency = if (i == 0) "USD" else "EUR") }).isEmpty())
        val suggestions = detectRecurringSuggestions(expenses() + expenses(currency = "USD"))
        assertEquals(setOf("EUR", "USD"), suggestions.map { it.currency }.toSet())
    }

    @Test fun `inactive tracked recurring hides only matching normalized name and currency`() {
        val recurring = FinanceRecurring("r", "Payment NETFLIX", BigDecimal("12.99"), "EUR", null,
            RecurringFrequency.MONTHLY, 1, LocalDate.parse("2026-05-01"), false)
        val data = FinanceData(transactions = expenses() + expenses(currency = "USD"), recurring = listOf(recurring))
        assertEquals("USD", availableRecurringSuggestions(data).single().currency)
        assertTrue(availableRecurringSuggestions(data.copy(ignoredRecurringSuggestions = setOf("USD:netflix"))).isEmpty())
    }

    @Test fun `suggestions ordered confidence count then stable key`() {
        val suggestions = detectRecurringSuggestions(expenses(count = 3, name = "Gym") + expenses(count = 5, name = "Netflix") + expenses(count = 4, name = "Music"))
        assertEquals(listOf("Netflix", "Music", "Gym"), suggestions.map { it.description })
    }

    @Test fun `local ignore store isolates accounts and returns defensive snapshots`() {
        val store = MemoryRecurringDismissals()
        store.ignore("owner-a", "EUR:netflix")
        val before = store.keys("owner-a")
        store.ignore("owner-a", "USD:gym")
        assertEquals(setOf("EUR:netflix"), before)
        assertEquals(2, store.keys("owner-a").size)
        assertTrue(store.keys("owner-b").isEmpty())
    }
}
