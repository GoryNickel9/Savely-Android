package cc.savely.android.modules

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class FireDatabaseDefaultsTest {
    private fun tx(date: String, type: String, amount: Int) = JSONObject().put("date", date).put("type", type).put("amount", amount)
    private fun asset(price: Int, quantity: Int) = JSONObject().put("purchase_price", price).put("quantity", quantity)

    @Test fun usesUtcTransactionDatesAgainstLocalTimestampAndDoesNotDropFutureRows() {
        val assets = listOf(asset(10, 2), asset(100, 3).put("current_price", 120),
            asset(999, 1).put("sold_at", "2026-01-01").put("sold_price", 0),
            asset(7, 2).put("sold_at", "2026-01-01").put("sold_price", JSONObject.NULL))
        val rows = listOf(tx("2024-10-12", "income", 9999), tx("2024-10-13", "income", 100),
            tx("2027-01-01", "income", 300), tx("2026-01-01", "expense", 60),
            tx("2026-01-01", "expense", 9999).put("deleted_at", "2026-02-01"))
        val result = fireDatabaseDefaults(assets, rows, ZonedDateTime.parse("2026-10-12T12:00:00+02:00[Europe/Rome]"))!!
        assertEquals(FireDatabaseDefaults(394.0, 200.0, 30.0, 170.0), result)
    }

    @Test fun dateSetMonthLeapDayRollsToMarchRatherThanClampingToFebruary() {
        val now = ZonedDateTime.parse("2024-02-29T12:00:00Z")
        assertNull(fireDatabaseDefaults(emptyList(), listOf(tx("2022-02-28", "income", 10), tx("2022-03-01", "income", 10)), now))
        assertEquals(5.0, fireDatabaseDefaults(emptyList(), listOf(tx("2022-03-02", "income", 10)), now)!!.income, 0.0)
    }

    @Test fun monthlyInputStoresAnnualAndRejectsOverflowInsteadOfCorruptingSavedParameters() {
        assertEquals("150.0", fireMonthlyToAnnual("12,50"))
        assertEquals("14814.72", fireMonthlyToAnnual("1.234,56"))
        assertEquals("0.0", fireMonthlyToAnnual("0"))
        assertEquals("", fireMonthlyToAnnual(""))
        listOf("-1", "NaN", "Infinity", "1e308", "oops").forEach {
            assertThrows(IllegalStateException::class.java) { fireMonthlyToAnnual(it) }
        }
    }
}
