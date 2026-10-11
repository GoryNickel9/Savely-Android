package cc.savely.android.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cc.savely.android.data.*
import cc.savely.android.release.ReleaseRepository
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NavigationLanguageTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private lateinit var preferences: LanguagePreferences
    private lateinit var preferenceStorage: SharedPreferences
    private val clients = mutableListOf<OkHttpClient>()

    @Before fun prepareLanguage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferenceStorage = context.getSharedPreferences("navigation-language-fixture", Context.MODE_PRIVATE)
        preferenceStorage.edit().clear().commit()
        preferences = LanguagePreferences(preferenceStorage)
        preferences.write(AppLanguage.ITALIAN)
    }

    @After fun cleanup() {
        compose.runOnIdle { models.clear() }
        clients.forEach { it.dispatcher.executorService.shutdown(); it.connectionPool.evictAll() }
        preferenceStorage.edit().clear().commit()
    }

    @Test fun switchingLanguageKeepsTheOpenRouteAndAccountWithoutRepeatingReleaseCheck() {
        val session = Session("language-access", "language-refresh", System.currentTimeMillis() / 1000 + 3600,
            "00000000-0000-4000-8000-000000000001", "language@example.test")
        val store = MemorySessionStore(session)
        val mutations = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "localhost")
            if (request.method != "GET") mutations.incrementAndGet()
            val body = if (request.url.encodedPath == "/auth/v1/user") "{\"factors\":[]}" else "[]"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(body.toResponseBody()).build()
        }.build().also { clients.add(it) }
        val api = SupabaseApi(BackendConfig("https://localhost", "language-public-key", "https://localhost"), store, client)
        val model = AppViewModel(api).also { models.put("language-navigation", it) }
        val checks = AtomicInteger()
        val releaseClient = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals(null, chain.request().header("Authorization"))
            assertEquals(null, chain.request().header("apikey"))
            checks.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Fixture")
                .body("{}".toResponseBody()).build()
        }.build().also { clients.add(it) }
        val releases = ReleaseRepository(releaseClient)
        var theme by mutableStateOf("light")
        compose.setContent { LanguageNavigation(model, session, releases, theme) }
        compose.onNodeWithTag("nav-more").performClick()
        compose.onAllNodesWithText("Moduli", substring = false).filterToOne(hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Il tuo Savely").assertIsDisplayed()
        compose.onNodeWithTag("nav-more").assertIsSelected()
        compose.waitUntil(10_000) { checks.get() == 1 }
        compose.runOnIdle { theme = "dark" }
        compose.onNodeWithText("Il tuo Savely").assertIsDisplayed()
        compose.onNodeWithTag("screen-title").assertTextEquals("Moduli")
        compose.onNodeWithTag("nav-more").assertIsSelected()

        compose.onNodeWithTag("language-en").performClick()
        compose.onNodeWithText("Your Savely").assertIsDisplayed()
        compose.onNodeWithTag("screen-title").assertTextEquals("Modules").assertIsDisplayed()
        compose.onNodeWithTag("nav-more").assertTextEquals("More").assertIsSelected()
        assertEquals(AppLanguage.ENGLISH, preferences.read())
        assertEquals(session, store.load())
        assertEquals(0, mutations.get())
        assertEquals(1, checks.get())

        compose.onNodeWithTag("language-it").performClick()
        compose.onNodeWithText("Il tuo Savely").assertIsDisplayed()
        assertEquals(session, store.load())
        assertEquals(0, mutations.get())
        assertEquals(1, checks.get())
    }

    @Composable
    private fun LanguageNavigation(model: AppViewModel, session: Session, releases: ReleaseRepository, theme: String) {
        LocalizationProvider(preferences) {
            SavelyTheme(theme) {
                Column(Modifier.fillMaxSize()) {
                    LanguageSelector()
                    Box(Modifier.weight(1f)) {
                        MainShell(model, AppState(initialized = true, session = session), theme, {}, {}, releases)
                    }
                }
            }
        }
    }
}
