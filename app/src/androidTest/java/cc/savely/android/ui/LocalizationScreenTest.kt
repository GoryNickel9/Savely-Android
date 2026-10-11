package cc.savely.android.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.R
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device resources and isolated preferences only; callbacks do not create a client or issue requests. */
@RunWith(AndroidJUnit4::class)
class LocalizationScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var preferences: SharedPreferences
    private lateinit var languages: LanguagePreferences

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = context.getSharedPreferences("localization-fixture", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        languages = LanguagePreferences(preferences)
    }

    @After fun cleanup() { preferences.edit().clear().commit() }

    @Test fun selectingEnglishPersistsLocallyWithoutChangingEnteredCredentials() {
        var submitted: Pair<String, String>? = null
        compose.setContent {
            LocalizationProvider(languages) {
                SavelyTheme("light") { AuthScreen(AppState(initialized = true),
                    { email, password -> submitted = email to password }, { _, _, _ -> }, {}, {}) }
            }
        }
        compose.onNodeWithText("Email", substring = false).performTextInput("Accedi@example.test")
        compose.onNodeWithText("Password", substring = false).performTextInput("synthetic{{name}}€")
        compose.onNodeWithTag("language-en").performScrollTo().performClick()
        compose.onNodeWithTag("language-en").assertIsSelected()
        compose.onNode(hasText("Sign in") and hasClickAction()).performScrollTo().performClick()
        assertEquals("Accedi@example.test" to "synthetic{{name}}€", submitted)
        assertEquals(AppLanguage.ENGLISH, LanguagePreferences(preferences).read())
        assertEquals(setOf("language"), preferences.all.keys)
    }

    @Test fun persistedEnglishLoadsTheResourceCatalogAndPreservesTemplateData() {
        languages.write(AppLanguage.ENGLISH)
        compose.setContent {
            LocalizationProvider(LanguagePreferences(preferences)) {
                SavelyTheme("light") {
                    Text(localized("Installata: {{version}} ({{code}})", "version" to "0.1.7", "code" to 8))
                    Text(localized("{{value0}} e i suoi dati saranno eliminati definitivamente anche dal sito.", "value0" to "Accedi {{id}}"))
                }
            }
        }
        compose.onNodeWithText("Installed: 0.1.7 (8)").assertExists()
        compose.onNodeWithText("Accedi {{id}} and their data will also be permanently deleted from the website.").assertExists()
    }

    @Test fun aScreenOutsideTheProviderKeepsTheItalianDefault() {
        languages.write(AppLanguage.ENGLISH)
        compose.setContent { SavelyTheme("light") { ItalianDefaultFixture() } }
        compose.onNodeWithText("Accedi").assertExists()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
    }

    @Test fun catalogPreservesPlaceholdersAndIncludesTheNewNativeFeatures() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val json = context.resources.openRawResource(R.raw.ui_translations_en).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val placeholder = Regex("\\{\\{([A-Za-z][A-Za-z0-9_]*)\\}\\}")
        json.keys().asSequence().forEach { source ->
            assertEquals(source, placeholder.findAll(source).map { it.groupValues[1] }.toSet(),
                placeholder.findAll(json.getString(source)).map { it.groupValues[1] }.toSet())
        }
        assertEquals("Previous period: {{from}} – {{to}}", json.getString("Periodo precedente: {{from}} – {{to}}"))
        assertEquals("Calendar {{month}}", json.getString("Calendario {{month}}"))
        assertEquals("Overview", json.getString("Panoramica"))
        assertEquals("Language", json.getString("Lingua"))
    }

    @Test fun invalidPreferenceFallsBackToItalianWithoutTouchingOtherPreferences() {
        preferences.edit().putInt("language", 42).putString("sentinel", "opaque-data").commit()
        assertEquals(AppLanguage.ITALIAN, languages.read())
        languages.write(AppLanguage.ENGLISH)
        assertEquals("opaque-data", preferences.getString("sentinel", null))
        assertEquals(AppLanguage.ENGLISH, LanguagePreferences(preferences).read())
    }

    @Composable private fun ItalianDefaultFixture() { Text(localized("Accedi")) }
}
