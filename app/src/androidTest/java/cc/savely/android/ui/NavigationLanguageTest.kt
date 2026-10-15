package cc.savely.android.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
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
import org.junit.Assert.assertTrue
import org.junit.rules.Timeout
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import androidx.compose.runtime.collectAsState
import org.json.JSONObject

class NavigationLanguageTest {
    @get:Rule(order = 1) val compose = createComposeRule()
    @get:Rule(order = 0) val timeout = Timeout.seconds(180)
    private val models = ViewModelStore()
    private val ownedModels = mutableListOf<AppViewModel>()
    private val visible = mutableStateOf(true)
    private var mounted = false
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
        if (mounted) { compose.runOnIdle { visible.value = false }; compose.waitForIdle() }
        runBlocking { withTimeout(10_000) { ownedModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } } }
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
        val model = AppViewModel(api).also { models.put("language-navigation", it); ownedModels.add(it) }
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
        mounted = true
        compose.setContent { if (visible.value) LanguageNavigation(model, session, releases, theme) }
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

    @Test fun allNineteenRoutesKeepBackAndLanguageThemeAtLargeFontWithoutWrites() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = TranslationCatalog.load(context)
        val session = Session("all-routes-access", "all-routes-refresh", System.currentTimeMillis() / 1000 + 3600,
            "00000000-0000-4000-8000-000000000001", "all-routes@example.test")
        val store = MemorySessionStore(session)
        val unexpected = AtomicInteger()
        val permissions = JSONObject().apply { destinations.mapNotNull { it.permission }.forEach { put(it, true) } }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "localhost" && request.url.scheme == "https")
            val body = when {
                request.method == "GET" && request.url.encodedPath == "/auth/v1/user" -> """{"factors":[]}"""
                request.method == "GET" && request.url.encodedPath == "/rest/v1/profiles" ->
                    """[{"id":"00000000-0000-4000-8000-000000000002","user_id":"${session.userId}","permissions":$permissions,"currency":"EUR"}]"""
                request.method == "GET" && request.url.encodedPath.startsWith("/rest/v1/") -> "[]"
                request.method == "POST" && request.url.encodedPath == "/api/admin" && request.body?.let {
                    val buffer = okio.Buffer(); it.writeTo(buffer); JSONObject(buffer.readUtf8()).optString("action") == "get-users"
                } == true -> """{"users":[]}"""
                request.method == "POST" && request.url.encodedPath == "/rest/v1/rpc/get_registrations_enabled" -> "false"
                else -> { unexpected.incrementAndGet(); error("Navigation fixture refuses mutation or unknown path") }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(body.toResponseBody()).build()
        }.build().also { clients.add(it) }
        val api = SupabaseApi(BackendConfig("https://localhost", "all-routes-public", "https://localhost"), store, client)
        val model = AppViewModel(api).also { models.put("all-routes", it); ownedModels.add(it) }
        val checks = AtomicInteger()
        val publicClient = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("GET", chain.request().method)
            assertEquals("api.github.com", chain.request().url.host)
            assertEquals(null, chain.request().header("Authorization")); assertEquals(null, chain.request().header("apikey"))
            checks.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Fixture")
                .body("{}".toResponseBody()).build()
        }.build().also { clients.add(it) }
        val releases = ReleaseRepository(publicClient)
        var theme by mutableStateOf("light")
        lateinit var back: OnBackPressedDispatcher
        mounted = true
        compose.setContent {
            if (visible.value) LocalizationProvider(preferences) {
                val actual = LocalDensity.current
                val dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                SideEffect { back = dispatcher }
                val state by model.state.collectAsState()
                CompositionLocalProvider(LocalDensity provides Density(actual.density, 2f)) {
                    SavelyTheme(theme) { Column(Modifier.width(320.dp).fillMaxSize()) {
                        LanguageSelector()
                        if (state.session != null) Box(Modifier.weight(1f)) {
                            MainShell(model, state, theme, { theme = it }, {}, releases)
                        }
                    } }
                }
            }
        }
        compose.waitUntil(15_000) { model.state.value.session != null && !model.state.value.busy }
        val routes = destinations + Destination("modules", "Moduli")
        assertEquals(19, routes.map { it.route }.distinct().size)
        fun title(value: String) {
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("screen-title") and hasText(value)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("screen-title").assertTextEquals(value).assertIsDisplayed()
        }
        for (language in AppLanguage.entries) {
            if (language == AppLanguage.ENGLISH) {
                compose.onAllNodesWithTag("language-en").onFirst().performClick()
                title("Modules")
            }
            for (destination in routes) {
                val label = catalog.translate(language, destination.label)
                compose.onNodeWithContentDescription(catalog.translate(language, "Apri menu")).performClick()
                compose.onNode(hasText(label, substring = false) and hasClickAction() and
                    hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))).performScrollTo().performClick()
                title(label)
                compose.onNodeWithContentDescription(catalog.translate(language, "Apri menu")).performClick()
                compose.runOnIdle { back.onBackPressed() }
                title(label)
                if (destination.route != "dashboard" && destination.route != "modules") {
                    compose.runOnIdle { back.onBackPressed() }
                    title("Dashboard")
                }
            }
            compose.runOnIdle { theme = if (language == AppLanguage.ITALIAN) "dark" else "light" }
            title(catalog.translate(language, "Moduli"))
        }
        compose.waitForIdle()
        assertEquals(AppLanguage.ENGLISH, preferences.read())
        assertEquals(session, store.load())
        assertEquals(0, unexpected.get())
        assertEquals(1, checks.get())
        listOf("dashboard", "transactions", "budget", "settings", "more").forEach {
            assertTrue(compose.onNodeWithTag("nav-$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.width > 0)
        }
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
