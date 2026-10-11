package cc.savely.android.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import cc.savely.android.data.*
import cc.savely.android.release.ReleaseRepository
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NavigationScreenTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()

    @After fun cleanup() { compose.runOnIdle { models.clear() } }

    @Test fun modulesIndexStaysOpenWithoutPermissionsAndReleaseCheckIsPublic() {
        val session = Session("synthetic-access", "synthetic-refresh", System.currentTimeMillis() / 1000 + 3600,
            "00000000-0000-4000-8000-000000000001", "navigation@example.test")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().url.encodedPath == "/auth/v1/user") "{\"factors\":[]}" else "[]"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(body.toResponseBody()).build()
        }.build()
        val api = SupabaseApi(BackendConfig("https://localhost", "public-test-key", "https://localhost"), MemorySessionStore(session), client)
        val model = AppViewModel(api).also { models.put("navigation", it) }
        val checks = AtomicInteger()
        val releaseClient = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("GET", chain.request().method)
            assertEquals("https", chain.request().url.scheme)
            assertEquals("api.github.com", chain.request().url.host)
            assertEquals("/repos/GoryNickel9/Savely-Android-Releases/releases", chain.request().url.encodedPath)
            assertEquals("20", chain.request().url.queryParameter("per_page"))
            assertEquals(null, chain.request().header("Authorization"))
            assertEquals(null, chain.request().header("apikey"))
            checks.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("No published public release")
                .body("{}".toResponseBody()).build()
        }.build()
        compose.setContent {
            SavelyTheme("light") {
                MainShell(model, AppState(initialized = true, session = session), "light", {}, {}, ReleaseRepository(releaseClient))
            }
        }
        compose.onNodeWithTag("nav-more").performClick()
        compose.onAllNodesWithText("Moduli", substring = false).filterToOne(hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Il tuo Savely").assertIsDisplayed()
        compose.waitForIdle()
        compose.onNodeWithText("Il tuo Savely").assertIsDisplayed()

        compose.onNodeWithContentDescription("Apri menu").performClick()
        compose.onAllNodesWithText("Versione e aggiornamenti").filterToOne(hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Non ci sono release pubbliche disponibili.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Non ci sono release pubbliche disponibili.").assertIsDisplayed()
        compose.onNodeWithText("Controlla aggiornamenti").performClick()
        compose.waitUntil(10_000) { checks.get() == 2 }
        compose.waitForIdle()
        assertEquals(2, checks.get())
    }
}
