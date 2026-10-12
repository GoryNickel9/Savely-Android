package cc.savely.android.finance

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FinanceSharedTransactionsTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val partner = "22222222-2222-4222-8222-222222222222"
    private val connection = FinanceCoupleConnection("33333333-3333-4333-8333-333333333333", owner, partner)
    private val today = LocalDate.parse("2026-03-31")
    private fun transaction(id: String, date: String = "2026-03-15") = FinanceTransaction(id, TransactionType.EXPENSE,
        BigDecimal("1.01"), "EUR", LocalDate.parse(date), "Cena", "food")
    private fun share(id: String, creator: String = owner, tx: String = "mine", currency: String = "EUR") = FinanceSharedExpense(
        id, connection.id, tx, creator, "Casa", BigDecimal("0.51"), BigDecimal("0.51"), BigDecimal("1.01"), currency,
        BigDecimal.ONE, "Affitto partner", LocalDate.parse("2026-03-16"))

    @Test fun `periods include calendar end and custom dates can have either bound absent`() {
        assertEquals(LocalDate.parse("2026-03-01") to today, transactionDateRange("month", today))
        assertEquals(LocalDate.parse("2026-02-01") to LocalDate.parse("2026-02-28"), transactionDateRange("previous", today))
        assertEquals(null to null, transactionDateRange("custom", today))
        assertEquals(LocalDate.parse("2026-02-01") to null, transactionDateRange("custom", today, from = "2026-02-01"))
        assertEquals(null to LocalDate.parse("2026-02-28"), transactionDateRange("custom", today, to = "2026-02-28"))
        assertEquals(LocalDate.parse("2024-01-01") to LocalDate.parse("2024-12-31"), transactionDateRange("year", today, "2024"))
    }
    @Test fun `invalid years dates and reversed intervals never silently expose all rows`() {
        listOf("0", "-1", "10000", "abcd").forEach { assertThrows(IllegalArgumentException::class.java) { transactionDateRange("year", today, it) } }
        listOf("2026-02-29", "2026-2-01", "0000-01-01").forEach {
            assertThrows(Exception::class.java) { transactionDateRange("custom", today, from = it) }
        }
        assertThrows(IllegalArgumentException::class.java) { transactionDateRange("custom", today, from = "2026-03-31", to = "2026-03-01") }
    }
    @Test fun `unified list keeps SQL split rounding and original amounts without summing currencies`() {
        val data = FinanceData(transactions = listOf(transaction("mine"), transaction("private")), userId = owner,
            coupleConnection = connection, sharedExpenses = listOf(share("own"), share("theirs", partner, "partner-tx", "USD")))
        val rows = transactionDisplayItems(data, onlyShared = true)
        assertEquals(listOf("share-theirs", "tx-mine"), rows.map { it.key })
        assertEquals(BigDecimal("1.01"), rows.last().transaction!!.amount)
        assertEquals(BigDecimal("0.51"), rows.last().share!!.creatorShare)
        assertEquals(BigDecimal("0.51"), rows.first().share!!.partnerShare)
        assertEquals("USD", rows.first().share!!.currency)
        assertEquals(3, transactionDisplayItems(data).size)
    }
    @Test fun `partner search period and category handling match Transactions web`() {
        val data = FinanceData(transactions = listOf(transaction("mine")), userId = owner, coupleConnection = connection,
            sharedExpenses = listOf(share("own"), share("theirs", partner)))
        assertEquals(listOf("share-theirs"), transactionDisplayItems(data, categoryId = "different", search = "CASA").map { it.key })
        assertEquals(listOf("share-theirs"), transactionDisplayItems(data, type = TransactionType.INCOME).map { it.key })
        assertTrue(transactionDisplayItems(data, to = LocalDate.parse("2026-03-14")).isEmpty())
        assertTrue(transactionDisplayItems(data, search = "not present").isEmpty())
        assertEquals(listOf("tx-mine"), transactionDisplayItems(data.copy(coupleConnection = null)).map { it.key })
        assertNull(transactionDisplayItems(data.copy(coupleConnection = null)).single().share)
        assertTrue(transactionDisplayItems(data.copy(coupleConnection = null), onlyShared = true).isEmpty())
    }
    @Test fun `shared parser preserves view partner meaning and rejects foreign connection creator or amount`() {
        val json = JSONObject().put("id", "44444444-4444-4444-8444-444444444444").put("connection_id", connection.id)
            .put("original_tx_id", "55555555-5555-4555-8555-555555555555").put("created_by", owner)
            .put("couple_category_name", JSONObject.NULL).put("creator_share_amount", "6.01").put("my_share_amount", "4.00")
            .put("total_amount", "10.01").put("currency", "GBP").put("exchange_rate_eur", "1.123456")
            .put("description", JSONObject.NULL).put("date", "2026-03-16")
        val parsed = financeSharedExpenseFromJson(json, connection)
        assertEquals(BigDecimal("4.00"), parsed.partnerShare)
        assertEquals(BigDecimal("6.01"), parsed.creatorShare)
        assertEquals(BigDecimal("1.123456"), parsed.exchangeRateEur)
        assertNull(parsed.categoryName)
        listOf("connection_id" to "55555555-5555-4555-8555-555555555555", "created_by" to "55555555-5555-4555-8555-555555555555",
            "my_share_amount" to "-0.01", "currency" to "UNKNOWN").forEach { (key, value) ->
            assertThrows(IllegalArgumentException::class.java) { financeSharedExpenseFromJson(JSONObject(json.toString()).put(key, value), connection) }
        }
    }
    @Test fun `budget names are case insensitive with deterministic id ties and removed category first`() {
        val data = FinanceData(categories = listOf(FinanceCategory("z", "Zoo", TransactionType.EXPENSE), FinanceCategory("a", "Alimentari", TransactionType.EXPENSE),
            FinanceCategory("e", "Élettronica", TransactionType.EXPENSE)),
            budgets = listOf(FinanceBudget("2", "z", BigDecimal.ONE, "USD"), FinanceBudget("3", "a", BigDecimal.ONE, "EUR"),
                FinanceBudget("1", "a", BigDecimal.ONE, "EUR"), FinanceBudget("4", "missing", BigDecimal.ONE, "EUR"), FinanceBudget("5", "e", BigDecimal.ONE, "EUR")))
        assertEquals(listOf("4", "1", "3", "5", "2"), sortedFinanceBudgets(data).map { it.id })
        assertEquals(37, categoryEmojiOptions.size)
        assertEquals(10, categoryColorOptions.size)
        assertTrue("📦" in categoryEmojiOptions && "#6b7280" in categoryColorOptions)
    }
}
