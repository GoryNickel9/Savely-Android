package cc.savely.android.release

import cc.savely.android.data.ExactJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Public release metadata only: never attach a Savely session or GitHub credential. */
class ReleaseRepository(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) {
    suspend fun latest(): PublishedRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://api.github.com/repos/GoryNickel9/Savely-Android/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Savely-Android-release-check").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (response.code == 403 || response.code == 429) throw IOException("GitHub ha limitato le richieste. Riprova più tardi.")
            if (!response.isSuccessful) throw IOException("Controllo aggiornamenti non riuscito (${response.code}).")
            val source = response.body?.source() ?: throw IOException("Risposta GitHub vuota.")
            if (source.request(262_145)) throw IOException("Risposta GitHub troppo grande.")
            val json = ExactJson.parse(source.readUtf8()) as? JSONObject ?: throw IOException("Risposta GitHub non valida.")
            PublishedRelease.parse(json)
        }
    }
}

data class PublishedRelease(val version: AppVersion, val tag: String, val notes: String) {
    val url: String get() = "$RELEASES_URL/tag/$tag"

    companion object {
        const val RELEASES_URL = "https://github.com/GoryNickel9/Savely-Android/releases"
        fun parse(json: JSONObject): PublishedRelease {
            require(json.has("draft") && !json.getBoolean("draft") &&
                json.has("prerelease") && !json.getBoolean("prerelease")) { "La risposta non contiene una release stabile pubblicata." }
            val tag = json.getString("tag_name")
            val version = AppVersion.parse(tag) ?: throw IllegalArgumentException("Versione della release non riconosciuta.")
            require(!version.preview) { "La release contiene una versione di anteprima." }
            require(json.getString("html_url") == "$RELEASES_URL/tag/$tag") { "Il link della release non appartiene al progetto." }
            return PublishedRelease(version, tag, if (json.isNull("body")) "" else json.optString("body").take(16_384))
        }
    }
}

/** The product uses three numeric components and an optional preview suffix. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val preview: Boolean) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        // At the same numeric version, a stable release supersedes an installed preview.
        return compareValues(other.preview, preview)
    }
    companion object {
        fun parse(raw: String): AppVersion? {
            val match = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*))?").matchEntire(raw) ?: return null
            val components = (1..3).map { match.groupValues[it].toIntOrNull() ?: return null }
            return AppVersion(components[0], components[1], components[2], match.groupValues[4].isNotEmpty())
        }
    }
}
