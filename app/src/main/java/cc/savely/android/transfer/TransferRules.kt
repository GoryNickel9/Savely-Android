package cc.savely.android.transfer

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale
import java.util.UUID

data class TransferCategoryDraft(
    val id: String = UUID.randomUUID().toString(), val name: String, val type: String,
    val icon: String = "💰", val color: String = "#22c55e",
) {
    fun body(owner: String): JSONObject {
        requireTransfer(name.isNotBlank() && name.trim().length <= 100, "Nome categoria richiesto, massimo 100 caratteri.")
        requireTransfer(type in setOf("income", "expense"), "Tipo categoria non valido.")
        requireTransfer(icon.isNotBlank() && icon.length <= 32 && Regex("#[0-9a-fA-F]{6}").matches(color), "Icona o colore non validi.")
        return JSONObject().put("id", id).put("user_id", owner).put("name", name.trim())
            .put("type", type).put("icon", icon).put("color", color)
    }
}

data class TransferCategoryMapping(val id: String, val description: String, val categoryId: String?, val source: String)
data class TransferIsinMapping(
    val id: String = UUID.randomUUID().toString(), val isin: String, val symbol: String,
    val name: String, val assetType: String = "etf",
) {
    fun body(owner: String): JSONObject {
        requireTransfer(Regex("[A-Z]{2}[A-Z0-9]{10}").matches(isin), "ISIN non valido: servono 12 caratteri.")
        requireTransfer(symbol.trim().isNotEmpty() && symbol.trim().length <= 32 &&
            symbol.trim().none { it.isWhitespace() || it.isISOControl() }, "Simbolo richiesto, massimo 32 caratteri senza spazi.")
        requireTransfer(name.trim().isNotEmpty() && name.trim().length <= 250, "Nome asset richiesto, massimo 250 caratteri.")
        requireTransfer(assetType in ASSET_TYPES, "Tipo asset non valido.")
        return JSONObject().put("id", id).put("user_id", owner).put("isin", isin)
            .put("symbol", symbol.trim().uppercase(Locale.ROOT)).put("name", name.trim()).put("asset_type", assetType)
    }
    companion object { val ASSET_TYPES = setOf("stock", "etf", "crypto", "bond", "cash", "real_estate", "other") }
}

enum class TransferMutationKind { INSERT, UPDATE, DELETE }
enum class TransferMutationStatus { READY, CONFIRMED, UNCERTAIN, ABSENT, BLOCKED }

/** Immutable attempted payload; retries may only reuse the same UUID and request after an explicit read. */
data class TransferMutation(
    val key: String, val table: String, val id: String, val owner: String, val payload: String,
    val ownerRevision: Long,
    val kind: TransferMutationKind = TransferMutationKind.INSERT,
    val status: TransferMutationStatus = TransferMutationStatus.READY,
    val expectedBefore: String? = null,
)

object TransferRules {
    fun matchingCategory(transaction: ImportTransaction, format: ImportFormat, context: ImportContext): TransferCategoryMapping? {
        val description = transaction.description.lowercase(Locale.ROOT)
        return context.categoryMappings.firstOrNull { mapping ->
            context.categories.any { it.id == mapping.categoryId && it.type == transaction.type } &&
                when (format) {
                    ImportFormat.REVOLUT -> mapping.source == "revolut" && mapping.description.lowercase(Locale.ROOT) == description
                    ImportFormat.BANK -> mapping.description.isNotBlank() && description.contains(mapping.description.lowercase(Locale.ROOT))
                    ImportFormat.NORMALIZED -> mapping.description.lowercase(Locale.ROOT) == description
                }
        }
    }

    fun description(keyword: String, note: String): String {
        requireTransfer(keyword.isNotBlank() && keyword.trim().length <= 1000 && note.trim().length <= 1000,
            "Descrizione richiesta; descrizione e nota massimo 1.000 caratteri ciascuna.")
        val result = keyword.trim() + note.trim().takeIf { it.isNotEmpty() }?.let { " - $it" }.orEmpty()
        requireTransfer(result.length <= 1000, "Descrizione e nota insieme non possono superare 1.000 caratteri.")
        return result
    }

    fun assetBody(transaction: ImportTransaction, mapping: TransferIsinMapping, owner: String, id: String): JSONObject {
        requireTransfer(transaction.type == "investment" && transaction.isin == mapping.isin, "Mapping ISIN incompatibile.")
        mapping.body(owner)
        return JSONObject().put("id", id).put("user_id", owner).put("name", mapping.name.trim())
            .put("symbol", mapping.symbol.trim().uppercase(Locale.ROOT)).put("type", mapping.assetType)
            .put("quantity", 1).put("purchase_price", transaction.amount).put("purchase_date", transaction.date.toString())
            .put("currency", transaction.currency).put("notes", "Importato da banca - ISIN: ${mapping.isin}")
    }

    /** Compare only requested fields, preserving unknown server metadata; numeric JSON types compare by value. */
    fun payloadMatches(actual: JSONObject, expected: JSONObject): Boolean = expected.keys().asSequence().all { key ->
        actual.has(key) && equalJson(actual.get(key), expected.get(key))
    }
    private fun equalJson(left: Any?, right: Any?): Boolean = when {
        left === JSONObject.NULL || right === JSONObject.NULL -> left === JSONObject.NULL && right === JSONObject.NULL
        left is Number && right is Number -> runCatching { BigDecimal(left.toString()).compareTo(BigDecimal(right.toString())) == 0 }.getOrDefault(false)
        left is JSONObject && right is JSONObject -> left.length() == right.length() && payloadMatches(left, right)
        left is JSONArray && right is JSONArray -> left.length() == right.length() && (0 until left.length()).all { equalJson(left.get(it), right.get(it)) }
        else -> left == right
    }
}

internal fun requireTransfer(condition: Boolean, message: String) { if (!condition) throw TransferException(message) }
