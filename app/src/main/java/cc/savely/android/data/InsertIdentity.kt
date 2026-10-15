package cc.savely.android.data

import org.json.JSONObject
import java.util.UUID

/** Explicit UUIDs also work when a deployed table lacks its migration's default. No retry is added. */
internal fun insertPayload(table: String, body: JSONObject): JSONObject {
    val uuidTables = setOf("transactions", "categories", "budgets", "recurring_expenses", "portfolio_assets",
        "manual_price_updates", "couple_budgets", "couple_connection_requests", "shared_expenses",
        "poker_next_cut", "poker_hourly_earnings", "poker_rakeback", "poker_manual_expenses",
        "liquido_sigaretta", "cbd", "thc", "tgc_cards", "library_items")
    if (table !in uuidTables) return body
    if (body.has("id")) {
        val id = body.opt("id") as? String
        if (id == null || runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) {
            throw ApiException(ApiError.VALIDATION, "Identificativo del nuovo record non valido.")
        }
        return body
    }
    // Do not mutate a form draft or a checkpoint retained by the caller.
    return JSONObject(body.toString()).put("id", UUID.randomUUID().toString())
}
