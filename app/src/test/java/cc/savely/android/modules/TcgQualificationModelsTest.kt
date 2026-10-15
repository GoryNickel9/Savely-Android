package cc.savely.android.modules

import java.math.BigDecimal
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TcgQualificationModelsTest {
    @Test fun `catalog seed copies identity and image without importing owner currency or price metadata`() {
        val card = JSONObject().put("id", "42").put("name", "QA {{currency}}")
            .put("set", JSONObject().put("name", "qa set")).put("image", "https://localhost/card.png")
            .put("user_id", "foreign").put("currency", "USD").put("current_price", 999)
            .put("condition", "damaged").put("language", "JP")
        val before = card.toString()
        val seed = CatalogRepository.cardSeed(card)
        assertEquals(setOf("name", "card_id", "set_code", "image_url"), seed.keys().asSequence().toSet())
        assertEquals("42", seed.getString("card_id")); assertEquals("QA {{currency}}", seed.getString("name"))
        assertEquals("qa set", seed.getString("set_code")); assertEquals("https://localhost/card.png", seed.getString("image_url"))
        assertTrue(tcgAllowsCardTraderPrice(seed))
        assertEquals(before, card.toString())
    }

    @Test fun `piece distribution is independent of market values and keeps all three empty categories`() {
        fun row(id: String, category: String, quantity: Int, price: Int) = JSONObject().put("id", id).put("user_id", "owner")
            .put("category", category).put("quantity", quantity).put("purchase_price", 1).put("current_price", price).put("currency", "EUR")
        val result = collectionOverview(CollectionKind.TCG, "owner", listOf(row("a", "magic", 90, 1),
            row("b", "pokemon", 9, 100), row("c", "yugioh", 1, 10000)))
        assertEquals(100L, result.pieces); assertEquals(listOf(90L, 9L, 1L), result.categories.map { it.pieces })
        assertEquals(0, BigDecimal("10990").compareTo(result.currencies.single().value))
        assertEquals(0, BigDecimal("100").compareTo(result.currencies.single().investment))
        val empty = collectionOverview(CollectionKind.TCG, "owner", emptyList())
        assertEquals(0, empty.records); assertEquals(0L, empty.pieces); assertTrue(empty.currencies.isEmpty())
        assertEquals(listOf("magic", "pokemon", "yugioh"), empty.categories.map { it.category })
        assertTrue(empty.categories.all { it.records == 0 && it.pieces == 0L && it.currencies.isEmpty() })
    }
}
