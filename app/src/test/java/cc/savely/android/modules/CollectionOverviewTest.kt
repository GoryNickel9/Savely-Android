package cc.savely.android.modules

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class CollectionOverviewTest {
    @Test fun tcgSeparatesCurrenciesAndUsesPurchaseWhenCurrentPriceIsNull() {
        val rows = listOf(card("a", "magic", 2, "10", "15", "EUR"),
            card("b", "pokemon", 3, "5", null, "EUR"), card("c", "yugioh", 4, "2.50", "3", "USD"))
        val before = rows.map(JSONObject::toString)
        val result = collectionOverview(CollectionKind.TCG, OWNER, rows)
        assertEquals(9L, result.pieces)
        assertEquals(listOf("EUR", "USD"), result.currencies.map { it.currency })
        assertTotal(result.currencies[0], 5, "35", "45", "10")
        assertTotal(result.currencies[1], 4, "10", "12", "2")
        assertAmount("20", result.currencies[1].gainPercent!!)
        assertTotal(result.categories.first { it.category == "magic" }.currencies.single(), 2, "20", "30", "10")
        assertTotal(result.categories.first { it.category == "pokemon" }.currencies.single(), 3, "15", "15", "0")
        assertEquals(before, rows.map(JSONObject::toString))
    }

    @Test fun libraryNullablePricesContributeZeroWithoutOverwritingSourceMetadata() {
        val rows = listOf(book("a", "libri", 2, null, "6"), book("b", "fumetti", 3, "4.50", null),
            book("c", "manga", 1, "10", "12"))
        val before = rows.map(JSONObject::toString)
        val result = collectionOverview(CollectionKind.LIBRARY, OWNER, rows)
        assertEquals(6L, result.pieces)
        assertTotal(result.currencies.single(), 6, "23.50", "24", "0.50")
        assertTotal(result.categories.first { it.category == "libri" }.currencies.single(), 2, "0", "12", "12")
        assertNull(result.categories.first { it.category == "libri" }.currencies.single().gainPercent)
        assertTotal(result.categories.first { it.category == "fumetti" }.currencies.single(), 3, "13.50", "0", "-13.50")
        assertEquals(before, rows.map(JSONObject::toString))
        assertSame(JSONObject.NULL, rows[0].get("purchase_price"))
        assertEquals("api-a", rows[0].getString("api_id"))
    }

    @Test fun emptyOverviewHasNoFabricatedAmountsAndRetainsCategoryNavigation() {
        val result = collectionOverview(CollectionKind.LIBRARY, OWNER, emptyList())
        assertEquals(0, result.records)
        assertEquals(0L, result.pieces)
        assertTrue(result.currencies.isEmpty())
        assertEquals(listOf("libri", "fumetti", "manga"), result.categories.map { it.category })
        assertTrue(result.categories.all { it.records == 0 && it.pieces == 0L && it.currencies.isEmpty() })
    }

    @Test fun legacyCurrencyAndExtraCategoriesAreRetainedWithoutInventedConversion() {
        val legacy = card("a", "lorcana", 2, "4", null, "EUR").apply { remove("currency") }
        val result = collectionOverview(CollectionKind.TCG, OWNER, listOf(legacy, card("b", "magic", 1, "3", "5", " usd ")))
        assertEquals(3L, result.pieces)
        assertEquals(listOf("EUR", "USD"), result.currencies.map { it.currency })
        assertEquals(listOf("magic", "pokemon", "yugioh", "lorcana"), result.categories.map { it.category })
        assertTotal(result.categories.last().currencies.single(), 2, "8", "8", "0")
    }

    @Test fun foreignDuplicateMalformedAndInvalidUnitsRejectTheWholeOverview() {
        val valid = card("a", "magic", 1, "10", "15", "EUR")
        val invalid = listOf(
            JSONObject(valid.toString()).put("user_id", "foreign-owner"),
            JSONObject(valid.toString()).put("currency", ""),
            JSONObject(valid.toString()).put("quantity", 1.5),
            JSONObject(valid.toString()).put("quantity", -1),
            JSONObject(valid.toString()).put("purchase_price", "10"),
            JSONObject(valid.toString()).put("current_price", -1),
            JSONObject(valid.toString()).put("current_price", BigDecimal("1.234")),
        )
        invalid.forEach { row ->
            assertThrows(IllegalArgumentException::class.java) { collectionOverview(CollectionKind.TCG, OWNER, listOf(row)) }
        }
        assertThrows(IllegalArgumentException::class.java) { collectionOverview(CollectionKind.TCG, OWNER, listOf(valid, valid)) }
    }

    private fun assertTotal(total: CollectionCurrencyTotal, pieces: Long, investment: String, value: String, profit: String) {
        assertEquals(pieces, total.pieces)
        assertAmount(investment, total.investment); assertAmount(value, total.value); assertAmount(profit, total.profit)
    }
    private fun assertAmount(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual))
    private fun card(id: String, category: String, quantity: Int, purchase: String, current: String?, currency: String) =
        JSONObject().put("id", id).put("user_id", OWNER).put("category", category).put("quantity", quantity)
            .put("purchase_price", BigDecimal(purchase)).put("current_price", current?.let(::BigDecimal) ?: JSONObject.NULL)
            .put("currency", currency)
    private fun book(id: String, category: String, quantity: Int, purchase: String?, reselling: String?) =
        JSONObject().put("id", id).put("user_id", OWNER).put("category", category).put("quantity", quantity)
            .put("purchase_price", purchase?.let(::BigDecimal) ?: JSONObject.NULL)
            .put("reselling_value", reselling?.let(::BigDecimal) ?: JSONObject.NULL).put("api_id", "api-$id")
    private companion object { const val OWNER = "collection-test-owner" }
}
