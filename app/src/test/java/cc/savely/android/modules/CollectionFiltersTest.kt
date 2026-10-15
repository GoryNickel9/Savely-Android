package cc.savely.android.modules

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CollectionFiltersTest {
    @Test fun magicNormalizesSetsAndCombinesTextWithExactSet() {
        val rows = listOf(card("a", "Black Lotus", "lea"), card("b", "Island", "LEA"), card("c", "Forest", "10e"))
        assertEquals(listOf("10E", "LEA"), CollectionFilters.cardSets(rows, "magic"))
        assertEquals(listOf("a", "b"), ids(CollectionFilters.cards(rows, "magic", text = "lea")))
        assertEquals(listOf("a"), ids(CollectionFilters.cards(rows, "magic", text = "LOTUS", set = "LEA")))
        assertTrue(CollectionFilters.cards(rows, "magic", text = "LOTUS", set = "10E").isEmpty())
    }

    @Test fun pokemonSetSelectionRemainsCaseSensitiveWhileTextMatchesSet() {
        val rows = listOf(card("a", "Pikachu", "Base"), card("b", "Charizard", "base"))
        assertEquals(listOf("Base", "base"), CollectionFilters.cardSets(rows, "pokemon"))
        assertEquals(listOf("a", "b"), ids(CollectionFilters.cards(rows, "pokemon", text = "BASE")))
        assertEquals(listOf("a"), ids(CollectionFilters.cards(rows, "pokemon", set = "Base")))
    }

    @Test fun yugiohSearchesOnlyNameAndStillSupportsExactSet() {
        val rows = listOf(card("a", "Dark Magician", "Legend of Blue Eyes"), card("b", "Blue Eyes", "Starter"))
        assertEquals(listOf("b"), ids(CollectionFilters.cards(rows, "yugioh", text = "blue")))
        assertEquals(listOf("a"), ids(CollectionFilters.cards(rows, "yugioh", set = "Legend of Blue Eyes")))
        assertTrue(CollectionFilters.cards(rows, "yugioh", text = "blue", set = "Legend of Blue Eyes").isEmpty())
    }

    @Test fun nullMetadataDoesNotCreateFakeNullOptionOrTextMatch() {
        val rows = listOf(card("a", "Island", null), card("b", "Forest", ""))
        assertTrue(CollectionFilters.cardSets(rows, "magic").isEmpty())
        assertEquals(listOf("a", "b"), ids(CollectionFilters.cards(rows, "magic")))
        assertTrue(CollectionFilters.cards(rows, "magic", text = "null").isEmpty())
    }

    @Test fun libraryCombinesTitleAuthorPublisherAndYear() {
        val rows = listOf(book("a", "Dune", "Frank Herbert", "A", 1965),
            book("b", "Dune Messiah", "Frank Herbert", "B", 1969), book("c", "Other", "Dune", "A", 1965))
        assertEquals(listOf("a", "b"), ids(CollectionFilters.library(rows, text = "DUNE")))
        assertEquals(listOf("a"), ids(CollectionFilters.library(rows, text = "dune", author = "Frank Herbert", publisher = "A", year = 1965)))
        assertTrue(CollectionFilters.library(rows, author = "frank herbert").isEmpty())
        assertTrue(CollectionFilters.library(rows, text = "Frank").isEmpty())
    }

    @Test fun libraryOptionsExcludeEmptyMetadataAndSortYearsDescending() {
        val rows = listOf(book("a", "One", "Z", "Publisher B", 2000), book("b", "Two", "A", "Publisher A", 2024),
            book("c", "Three", "A", "Publisher A", 2000), book("d", "Four", "", null, 0), book("e", "Five", null, "", null))
        assertEquals(listOf("A", "Z"), CollectionFilters.libraryValues(rows, "author"))
        assertEquals(listOf("Publisher A", "Publisher B"), CollectionFilters.libraryValues(rows, "publisher"))
        assertEquals(listOf(2024, 2000), CollectionFilters.libraryYears(rows))
        assertEquals(5, CollectionFilters.library(rows).size)
        assertThrows(IllegalArgumentException::class.java) { CollectionFilters.libraryValues(rows, "title") }
    }

    @Test fun filteringDoesNotMutateRecordsAndNullSelectionsRestoreAll() {
        val rows = listOf(book("a", "Dune", "Frank", "A", 1965), book("b", "Other", null, null, null))
        val before = rows.map(JSONObject::toString)
        assertEquals(listOf("a"), ids(CollectionFilters.library(rows, author = "Frank")))
        assertEquals(listOf("a", "b"), ids(CollectionFilters.library(rows)))
        assertEquals(before, rows.map(JSONObject::toString))
        assertSame(rows[0], CollectionFilters.library(rows, author = "Frank").single())
    }

    @Test fun cardSearchResultSetFilterUsesReturnedExpansionName() {
        val a = JSONObject().put("id", "a").put("set", JSONObject().put("name", "Alpha"))
        val b = JSONObject().put("id", "b").put("set", JSONObject().put("name", "Beta"))
        val c = JSONObject().put("id", "c").put("set", JSONObject().put("name", "Alpha"))
        val rows = listOf(a, b, c)
        assertEquals(listOf("Alpha", "Beta"), CollectionFilters.searchSets(rows))
        assertEquals(listOf("a", "c"), ids(CollectionFilters.searchCards(rows, "Alpha")))
        assertEquals(3, CollectionFilters.searchCards(rows, null).size)
        assertTrue(CollectionFilters.searchCards(rows, "alpha").isEmpty())
    }

    private fun card(id: String, name: String, set: String?) = JSONObject().put("id", id).put("name", name).put("set_code", set ?: JSONObject.NULL)
    private fun book(id: String, title: String, author: String?, publisher: String?, year: Int?) = JSONObject().put("id", id)
        .put("title", title).put("author", author ?: JSONObject.NULL).put("publisher", publisher ?: JSONObject.NULL).put("year", year ?: JSONObject.NULL)
    private fun ids(rows: List<JSONObject>) = rows.map { it.getString("id") }
}
