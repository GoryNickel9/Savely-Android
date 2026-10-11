package cc.savely.android.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.Base64

data class BackendConfig(val url: String, val anonKey: String, val webUrl: String = "https://savely.cc") {
    internal fun backendUrl(): HttpUrl = validateUrl(url)
    internal fun websiteUrl(): HttpUrl = validateUrl(webUrl)
    internal fun websiteApiUrl(): HttpUrl = websiteUrl().let { origin ->
        // The production API lives on www; auth callback/recovery origins keep their configured allow-list.
        if (origin.toString() == "https://savely.cc/") origin.newBuilder().host("www.savely.cc").build() else origin
    }

    internal fun validate() {
        backendUrl()
        websiteUrl()
        if (anonKey.isBlank() || anonKey.any { it.isWhitespace() } || anonKey.startsWith("sb_secret_")) {
            throw ApiException(ApiError.CONFIGURATION, "Configurazione backend non valida.")
        }
        // Legacy service-role JWTs must never be accepted as application configuration.
        if (anonKey.count { it == '.' } == 2) {
            val payload = runCatching {
                JSONObject(String(Base64.getUrlDecoder().decode(anonKey.split('.')[1]), Charsets.UTF_8))
            }.getOrNull()
            if (payload?.optString("role") != "anon") {
                throw ApiException(ApiError.CONFIGURATION, "È richiesta una chiave pubblica Supabase.")
            }
        }
    }

    override fun toString(): String = "BackendConfig(url=$url, webUrl=$webUrl)"

    private fun validateUrl(value: String): HttpUrl {
        val parsed = value.toHttpUrlOrNull()
            ?: throw ApiException(ApiError.CONFIGURATION, "URL backend non valido.")
        val local = parsed.host in setOf("localhost", "127.0.0.1", "::1")
        if ((parsed.scheme != "https" && !local) || parsed.username.isNotEmpty() ||
            parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null ||
            parsed.encodedPath != "/") {
            throw ApiException(ApiError.CONFIGURATION, "URL backend non valido: usare un'origine HTTPS.")
        }
        return parsed
    }
}
