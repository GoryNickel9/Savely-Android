package cc.savely.android.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.savely.android.R
import org.json.JSONObject

enum class AppLanguage(val tag: String, val displayName: String) {
    ITALIAN("it", "Italiano"), ENGLISH("en", "English");

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: ITALIAN
    }
}

/** Language is a device preference, independent of profile, credentials and API configuration. */
class LanguagePreferences(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("localization", Context.MODE_PRIVATE))

    fun read(): AppLanguage = AppLanguage.fromTag(try { preferences.getString("language", null) } catch (_: ClassCastException) { null })
    fun write(language: AppLanguage) { preferences.edit().putString("language", language.tag).apply() }
}

val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.ITALIAN }
val LocalLanguageChange = staticCompositionLocalOf<(AppLanguage) -> Unit> { {} }
private val LocalTranslationCatalog = staticCompositionLocalOf { TranslationCatalog(emptyMap()) }

/** Wrap the existing app composition without key(language), preserving its session and screen state. */
@Composable
fun LocalizationProvider(languagePreferences: LanguagePreferences? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context, languagePreferences) { languagePreferences ?: LanguagePreferences(context) }
    var language by remember(preferences) { mutableStateOf(preferences.read()) }
    val catalog = remember(context) { TranslationCatalog.load(context) }
    val change: (AppLanguage) -> Unit = { value ->
        preferences.write(value)
        language = value
    }
    CompositionLocalProvider(LocalAppLanguage provides language, LocalLanguageChange provides change,
        LocalTranslationCatalog provides catalog, content = content)
}

/** Exact catalogue lookup only. Do not apply this to names, descriptions, identifiers or external content. */
@Composable
fun localized(text: String): String = LocalTranslationCatalog.current.translate(LocalAppLanguage.current, text)

/** Translate a fixed template, then insert opaque values once; values are never translated or reparsed. */
@Composable
fun localized(template: String, vararg values: Pair<String, Any?>): String =
    LocalTranslationCatalog.current.translate(LocalAppLanguage.current, template, *values)

@Composable
fun LanguageSelector() {
    val selected = LocalAppLanguage.current
    val change = LocalLanguageChange.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppLanguage.entries.forEach { language ->
            FilterChip(selected == language, { change(language) }, label = { Text(language.displayName) },
                modifier = Modifier.testTag("language-${language.tag}"))
        }
    }
}

internal class TranslationCatalog(private val english: Map<String, String>) {
    fun translate(language: AppLanguage, template: String, vararg values: Pair<String, Any?>): String {
        val translated = if (language == AppLanguage.ENGLISH) english[template] ?: template else template
        // A malformed translation must not lose a value or inject a new placeholder.
        val selected = if (placeholders(translated) == placeholders(template)) translated else template
        if (values.isEmpty()) return selected
        val supplied = values.toMap()
        return PLACEHOLDER.replace(selected) { match ->
            val key = match.groupValues[1]
            if (supplied.containsKey(key)) supplied[key]?.toString().orEmpty() else match.value
        }
    }

    private fun placeholders(text: String) = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet()

    companion object {
        private val PLACEHOLDER = Regex("\\{\\{([A-Za-z][A-Za-z0-9_]*)\\}\\}")

        fun load(context: Context): TranslationCatalog {
            val json = context.resources.openRawResource(R.raw.ui_translations_en).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            return TranslationCatalog(json.keys().asSequence().associateWith { json.getString(it) })
        }
    }
}
