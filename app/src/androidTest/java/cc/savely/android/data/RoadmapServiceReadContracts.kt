package cc.savely.android.data

import cc.savely.android.modules.CatalogRepository
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/** Opt-in smoke component. Caller owns QA bootstrap, transport allow-list, reporting and cleanup.
 * No credentials, rows, factor IDs, prices, user agents or provider response bodies enter its reports.
 */
internal object RoadmapServiceReadContracts {
    suspend fun run(api: SupabaseApi, expectedSessionId: String, contract: (JSONObject) -> Unit) {
        require(canonicalAuthSessionId(expectedSessionId) == expectedSessionId)
        val initial = requireNotNull(api.session())
        check(initial.userId == OWNER && initial.email == EMAIL && initial.authSessionId() == expectedSessionId)
        check(initial.expiresAt > Instant.now().epochSecond + 300)
        val revision = requireNotNull(api.sessionOwnerRevision(initial))
        fun owned() { check(api.isSessionOwnerCurrent(revision)) }
        suspend fun read(name: String, action: suspend () -> JSONObject) {
            try {
                owned()
                val details = action()
                owned()
                contract(details.put("contract", name).put("status", "passed"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                contract(JSONObject().put("contract", name).put("status", "failed").put("code", failureCode(name, failure)))
            }
        }

        read("active_sessions_readonly") {
            val rows = api.rpc("get_active_sessions", JSONObject()) as? JSONArray ?: error("SESSION_ARRAY_REQUIRED")
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                check(canonicalAuthSessionId(row.getString("id")) != null)
                Instant.parse(row.getString("created_at"))
                if (!row.isNull("session_id")) check(canonicalAuthSessionId(row.getString("session_id")) != null)
            }
            // This RPC may omit this bootstrap: the harness deliberately writes no login_activity.
            JSONObject().put("rows", rows.length()).put("current_session_listed",
                (0 until rows.length()).any { rows.getJSONObject(it).optString("session_id") == expectedSessionId })
        }
        read("registrations_setting_readonly") {
            check(api.rpc("get_registrations_enabled", JSONObject()) is Boolean)
            JSONObject()
        }
        read("admin_denied_for_qa") {
            check(api.permissions()["admin"] != true)
            var denied = false
            try { api.webPost("/api/admin", JSONObject().put("action", "get-users")) }
            catch (failure: ApiException) {
                if (failure.kind == ApiError.PERMISSION && failure.status == 403) denied = true else throw failure
            }
            check(denied)
            JSONObject().put("http_status", 403)
        }

        // Public catalog requests use a separate client: no Supabase header can cross this boundary.
        val publicClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).addInterceptor { chain ->
                val request = chain.request()
                val url = request.url
                if (request.method != "GET" || url.scheme != "https" || url.host != "www.googleapis.com" || url.port != 443 ||
                    url.encodedPath != "/books/v1/volumes" || url.queryParameterNames != setOf("q", "maxResults", "orderBy", "langRestrict") ||
                    url.queryParameterValues("q") != listOf("intitle:Dune") || url.queryParameterValues("maxResults") != listOf("20") ||
                    url.queryParameterValues("orderBy") != listOf("relevance") || url.queryParameterValues("langRestrict") != listOf("it") ||
                    request.header("Authorization") != null || request.header("apikey") != null || request.header("Cookie") != null)
                    throw IOException("PUBLIC_CATALOG_REQUEST_REJECTED")
                chain.proceed(request)
            }.build()
        try {
            val catalog = CatalogRepository(api, publicClient)
            var priceId: String? = null
            read("cardtrader_catalog_search") {
                val cards = catalog.searchCards("Black Lotus")
                priceId = cards.firstOrNull { Regex("[0-9]{1,20}").matches(it.getString("id")) }?.getString("id")
                check(priceId != null)
                JSONObject().put("rows", cards.size)
            }
            read("cardtrader_price_readonly") {
                val id = requireNotNull(priceId)
                val price = catalog.cardPrice(id, "near_mint")
                check(price == null || price.isFinite() && price >= 0)
                // A null price is a valid no-listing response, not a verified market valuation.
                JSONObject().put("quote_available", price != null)
            }
            read("google_books_catalog_search") {
                val books = catalog.searchBooks("Dune", comics = false)
                check(books.isNotEmpty())
                JSONObject().put("rows", books.size).put("credentials_sent", false)
            }
        } finally {
            publicClient.dispatcher.executorService.shutdown()
            publicClient.connectionPool.evictAll()
        }
    }

    internal fun failureCode(name: String, failure: Exception): String {
        if (failure is ApiException) return "API_${failure.kind}_${failure.status ?: 0}"
        val booksStatus = if (name == "google_books_catalog_search" && failure is IllegalArgumentException)
            Regex("Ricerca libri non disponibile \\(HTTP ([1-5][0-9]{2})\\)").matchEntire(failure.message.orEmpty())?.groupValues?.get(1) else null
        return booksStatus?.let { "GOOGLE_BOOKS_HTTP_$it" }
            ?: "READ_${failure.javaClass.simpleName.filter(Char::isLetterOrDigit).take(48)}"
    }

    private const val OWNER = "9daffd41-39bf-4b1e-be68-abf12549c4a7"
    private const val EMAIL = "e2e-savely@example.com"
}
