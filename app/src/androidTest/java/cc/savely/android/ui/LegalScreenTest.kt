package cc.savely.android.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.UUID

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class LegalScreenTest {
    private val effects = StandardTestDispatcher()
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(90)
    @get:Rule(order = 1) val compose = createComposeRule(effectContext = effects)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferenceName = "legal-fixture-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    @After fun clear() { context.deleteSharedPreferences(preferenceName) }

    @Test fun englishUsesTheWebTranslationAndCrossDocumentLinksStayNative() {
        preferences.edit().putString("language", "en").commit()
        var back = 0
        val external = mutableListOf<String>()
        compose.setContent { LocalizationProvider(LanguagePreferences(preferences)) { SavelyTheme("light") {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { external += uri } }) {
                LegalScreen("privacy") { back++ }
            }
        } } }
        awaitText("This notice describes", substring = true)
        compose.onNodeWithText("This notice describes", substring = true).assertTextContains("This notice describes", substring = true)
        val cookieLink = hasText("Cookie Policy") and hasClickAction()
        repeat(24) {
            compose.runOnUiThread { effects.scheduler.runCurrent() }
            if (!compose.onNode(cookieLink).isDisplayed()) compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        }
        compose.onNode(cookieLink).assertIsDisplayed().performTouchInput { click() }
        awaitText("This notice describes cookies", substring = true)
        compose.onNode(hasText("Back") and hasClickAction()).performClick()
        awaitText("This notice describes how", substring = true)
        assertEquals(0, back)
        assertTrue(external.isEmpty())
        compose.onNode(hasText("Back") and hasClickAction()).performClick()
        assertEquals(1, back)
    }

    @Test fun italianLegalBodiesAndLinkFilesAreBundledAndDoNotLoseTemplateValues() {
        listOf("legal", "legal/en").forEach { prefix -> listOf("privacy", "cookies", "terms").forEach { document ->
            val text = context.assets.open("$prefix/$document.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val links = context.assets.open("$prefix/$document-links.json").bufferedReader(Charsets.UTF_8).use { org.json.JSONArray(it.readText()) }
            assertTrue(text.length > 1000)
            assertFalse(text.contains("{{"))
            assertTrue(links.length() > 0)
            (0 until links.length()).forEach { index ->
                val href = links.getJSONObject(index).getString("href")
                assertTrue(href.startsWith("https://") || href.startsWith("mailto:") || href in setOf("/privacy", "/terms", "/cookies"))
            }
        } }
    }

    private fun awaitText(text: String, substring: Boolean) {
        compose.waitUntil(10_000) {
            compose.runOnUiThread { effects.scheduler.runCurrent() }
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
