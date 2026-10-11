package cc.savely.android.modules

import org.json.JSONObject
import java.text.Collator
import java.util.Locale

/** Port of TcgCollectionPage/LibraryPage predicates; filtering never mutates the collection. */
object CollectionFilters {
    private fun value(row: JSONObject, key: String): String? = if (row.isNull(key)) null else row.getString(key)
    private fun normalizedSet(row: JSONObject, category: String): String? = value(row, "set_code")?.let {
        if (category == "magic") it.uppercase(Locale.ROOT) else it
    }
    private fun matchesText(value: String?, text: String): Boolean =
        value?.lowercase(Locale.ROOT)?.contains(text.lowercase(Locale.ROOT)) == true

    fun cardSets(rows: List<JSONObject>, category: String): List<String> = rows.mapNotNull { normalizedSet(it, category) }
        .filter { it.isNotEmpty() }.distinct().sorted()

    fun cards(rows: List<JSONObject>, category: String, text: String = "", set: String? = null): List<JSONObject> = rows.filter { row ->
        val normalized = normalizedSet(row, category)
        val matchesName = text.isEmpty() || matchesText(value(row, "name"), text) ||
            (category != "yugioh" && matchesText(normalized, text))
        matchesName && (set == null || normalized == set)
    }

    fun libraryValues(rows: List<JSONObject>, field: String): List<String> {
        require(field == "author" || field == "publisher")
        return rows.mapNotNull { value(it, field) }.filter { it.isNotEmpty() }.distinct().sorted()
    }

    fun libraryYears(rows: List<JSONObject>): List<Int> = rows.filterNot { it.isNull("year") }
        .map { it.getInt("year") }.filter { it != 0 }.distinct().sortedDescending()

    fun library(rows: List<JSONObject>, text: String = "", author: String? = null, publisher: String? = null, year: Int? = null): List<JSONObject> = rows.filter { row ->
        (text.isEmpty() || matchesText(value(row, "title"), text)) &&
            (author == null || value(row, "author") == author) &&
            (publisher == null || value(row, "publisher") == publisher) &&
            (year == null || (!row.isNull("year") && row.getInt("year") == year))
    }

    fun searchSets(results: List<JSONObject>): List<String> = results.mapNotNull { it.optJSONObject("set")?.optString("name") }
        .filter { it.isNotEmpty() }.distinct().sortedWith(Collator.getInstance())

    fun searchCards(results: List<JSONObject>, set: String?): List<JSONObject> =
        if (set == null) results else results.filter { it.optJSONObject("set")?.optString("name") == set }
}
