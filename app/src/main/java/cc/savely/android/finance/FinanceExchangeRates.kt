package cc.savely.android.finance

import cc.savely.android.data.ExactJson
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Public quote transport is separate from Supabase; no account credentials leave its origin. */
class FinanceExchangeRates(
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(15, TimeUnit.SECONDS).build(),
    private val endpoint: HttpUrl = "https://api.frankfurter.app/latest".toHttpUrl(),
) {
    suspend fun toEuro(currency: String): BigDecimal {
        require(currency in supportedCurrencies) { "Valuta non supportata" }
        if (currency == "EUR") return BigDecimal.ONE
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(endpoint.newBuilder().addQueryParameter("from", currency).addQueryParameter("to", "EUR").build()).build()
                client.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "Servizio cambio non disponibile" }
                    val bytes = response.peekBody(16_385).bytes()
                    require(bytes.size in 1..16_384) { "Risposta del cambio non valida" }
                    val json = ExactJson.parse(bytes.toString(Charsets.UTF_8)) as? JSONObject
                        ?: error("Risposta del cambio non valida")
                    require(json.optString("base") == currency) { "Valuta della quotazione non corrispondente" }
                    parseExchangeRate(json.getJSONObject("rates").get("EUR").toString())
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                throw IllegalStateException("Cambio automatico non disponibile. Inserisci il cambio manualmente.", error)
            }
        }
    }
}
