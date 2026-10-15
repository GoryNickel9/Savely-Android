package cc.savely.android.modules

import cc.savely.android.data.SupabaseApi
import cc.savely.android.data.ExactJson
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public book search never carries Supabase credentials; CardTrader uses its authenticated proxy. */
class CatalogRepository(
    private val api: SupabaseApi,
    client: OkHttpClient = OkHttpClient(),
    private val booksEndpoint: HttpUrl = "https://www.googleapis.com/books/v1/volumes".toHttpUrl(),
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun searchCards(query: String): List<JSONObject> {
        require(query.trim().isNotEmpty()) { "Inserisci il nome della carta" }
        val result = api.getFunction("cardtrader-proxy", "action=search&name=${encode(query.trim())}")
        require(result is JSONArray) { "Risposta CardTrader non valida" }
        return (0 until result.length()).map { result.getJSONObject(it) }.filter {
            it.optString("id").isNotBlank() && it.optString("name").isNotBlank()
        }
    }

    suspend fun cardPrice(blueprintId: String, condition: String): Double? {
        require(Regex("[1-9][0-9]{0,14}").matches(blueprintId)) { "La carta non ha un identificativo CardTrader" }
        require(condition in CONDITIONS) { "Condizione carta non valida" }
        val result = api.getFunction("cardtrader-proxy", "action=prices&id=${encode(blueprintId)}&condition=${encode(condition)}")
        require(result is JSONObject) { "Risposta prezzi non valida" }
        return cardPriceFromResponse(result, blueprintId)
    }

    suspend fun searchBooks(query: String, comics: Boolean): List<JSONObject> {
        require(query.trim().isNotEmpty()) { "Inserisci il titolo" }
        val first = booksRequest("intitle:${query.trim()}" + if (comics) "+subject:fumetti" else "", true)
        if (!comics || first.isNotEmpty()) return first
        return booksRequest("intitle:${query.trim()}", false)
    }

    private suspend fun booksRequest(query: String, italian: Boolean): List<JSONObject> {
        val url = booksEndpoint.newBuilder().addQueryParameter("q", query)
            .addQueryParameter("maxResults", "20").addQueryParameter("orderBy", "relevance")
            .apply { if (italian) addQueryParameter("langRestrict", "it") }.build()
        val json = requestJson(Request.Builder().url(url).get().build())
        val items = json.optJSONArray("items") ?: return emptyList()
        val seen = mutableSetOf<String>()
        return (0 until items.length()).map { items.getJSONObject(it) }.filter {
            it.optJSONObject("volumeInfo")?.optString("title")?.isNotBlank() == true && seen.add(it.getString("id"))
        }
    }

    private suspend fun requestJson(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("Ricerca libri non disponibile", e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        require(response.isSuccessful) { "Ricerca libri non disponibile (HTTP ${response.code})" }
                        val source = response.body?.source() ?: error("Risposta libri vuota")
                        require(!source.request(2_097_153)) { "Risposta libri troppo grande" }
                        val result = try { ExactJson.parse(source.readUtf8()) as? JSONObject }
                            catch (_: IllegalArgumentException) { null }
                            ?: throw IllegalArgumentException("Risposta libri non valida")
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            }
        })
    }

    companion object {
        val CONDITIONS = listOf("near_mint", "lightly_played", "moderately_played", "heavily_played", "damaged")
        private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

        /** A legacy unitless response is not evidence that its cents are EUR. Fail before any save. */
        internal fun cardPriceFromResponse(result: JSONObject, blueprintId: String): Double? {
            require(Regex("[1-9][0-9]{0,14}").matches(blueprintId) &&
                result.opt("blueprintId") == blueprintId && result.opt("currency") == "EUR" &&
                priceInteger(result, "quoteContractVersion", 2, 2) == 2L &&
                priceInteger(result, "minorUnit", 2, 2) == 2L && result.has("ctZeroLowPriceCents")) {
                "Risposta prezzi non valida"
            }
            val listings = priceInteger(result, "totalListings", 0, Int.MAX_VALUE.toLong())
            if (result.isNull("ctZeroLowPriceCents")) {
                require(listings == 0L) { "Risposta prezzi non valida" }
                return null
            }
            require(listings > 0) { "Risposta prezzi non valida" }
            val cents = priceInteger(result, "ctZeroLowPriceCents", 0, 9_999_999_999L)
            return BigDecimal.valueOf(cents).movePointLeft(2).toDouble()
        }

        private fun priceInteger(result: JSONObject, key: String, minimum: Long, maximum: Long): Long {
            val value = result.opt(key)
            require(value is Number) { "Risposta prezzi non valida" }
            val integer = try { BigDecimal(value.toString()).longValueExact() }
                catch (_: NumberFormatException) { throw IllegalArgumentException("Prezzo ricevuto non valido") }
                catch (_: ArithmeticException) { throw IllegalArgumentException("Prezzo ricevuto non valido") }
            require(integer in minimum..maximum) { "Prezzo ricevuto non valido" }
            return integer
        }

        fun cardSeed(card: JSONObject): JSONObject = JSONObject().put("name", card.getString("name"))
            .put("card_id", card.getString("id")).put("set_code", card.optJSONObject("set")?.optString("name") ?: JSONObject.NULL)
            .put("image_url", card.optString("image").takeIf { it.isNotBlank() } ?: JSONObject.NULL)

        fun bookSeed(book: JSONObject): JSONObject {
            val info = book.getJSONObject("volumeInfo")
            return JSONObject().put("title", info.getString("title")).put("api_id", book.getString("id"))
                .put("author", info.optJSONArray("authors")?.optString(0)?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("publisher", info.optString("publisher").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("year", info.optString("publishedDate").take(4).toIntOrNull() ?: JSONObject.NULL)
                .put("cover_image", info.optJSONObject("imageLinks")?.optString("thumbnail")?.takeIf { it.isNotBlank() }?.replace("http://", "https://") ?: JSONObject.NULL)
        }
    }
}
