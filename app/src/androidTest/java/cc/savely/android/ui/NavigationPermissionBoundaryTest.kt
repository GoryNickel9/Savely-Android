package cc.savely.android.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.data.*
import cc.savely.android.release.ReleaseRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real shell and localhost TLS; synthetic identities only, no deployed permissions/RLS claim. */
@RunWith(AndroidJUnit4::class)
class NavigationPermissionBoundaryTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(120)
    @get:Rule(order = 1) val compose = createComposeRule()
    private val visible = mutableStateOf(true)
    private val models = ViewModelStore()
    private val tokens = ConcurrentHashMap<String, String>()
    private val permissions = ConcurrentHashMap<String, JSONObject>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val unexpected = AtomicInteger()
    private val nonLocal = AtomicInteger()
    private val releaseChecks = AtomicInteger()
    private val cardReads = AtomicInteger()
    private val cardName = AtomicReference("NAV_CARD_ORIGINAL")
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private lateinit var store: MemorySessionStore
    private lateinit var model: AppViewModel
    private lateinit var languageStorage: SharedPreferences
    private lateinit var languagePreferences: LanguagePreferences
    private lateinit var back: OnBackPressedDispatcher
    private lateinit var focus: FocusManager
    private var keyboard: SoftwareKeyboardController? = null
    private var mounted = false

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        languageStorage = context.getSharedPreferences("navigation-boundary-fixture", Context.MODE_PRIVATE)
        languageStorage.edit().clear().commit()
        languagePreferences = LanguagePreferences(languageStorage)
        val certificate = HeldCertificate.Builder().commonName("Navigation boundary fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); dispatcher = fixture(); start() }
        val origin = server.url("/")
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.scheme != origin.scheme || request.url.host != origin.host || request.url.port != origin.port) {
                    nonLocal.incrementAndGet()
                    throw IOException("Navigation fixture refuses non-local traffic")
                }
                chain.proceed(request)
            }.build()
        permissions[OWNER_A] = JSONObject()
        permissions[OWNER_B] = JSONObject()
        store = MemorySessionStore(session(OWNER_A, SID_A, "original"))
        api = SupabaseApi(BackendConfig(origin.toString(), "navigation-public", origin.toString()), store, client)
    }

    @After fun cleanup() {
        try {
            if (mounted) { compose.runOnIdle { visible.value = false }; compose.waitForIdle() }
            if (::model.isInitialized) runBlocking {
                withTimeout(10_000) { requireNotNull(model.viewModelScope.coroutineContext[Job]).cancelAndJoin() }
            }
            models.clear()
        } finally {
            api.close()
            server.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            languageStorage.edit().clear().commit()
        }
    }

    @Test fun italianDrawerAndModuleIndexDenyAllSevenNonBooleanOrFalsePermissions() = strictDenied(AppLanguage.ITALIAN)

    @Test fun englishDrawerAndModuleIndexDenyAllSevenNonBooleanOrFalsePermissions() = strictDenied(AppLanguage.ENGLISH)

    private fun strictDenied(language: AppLanguage) {
        permissions[OWNER_A] = JSONObject().put("admin", "true").put("poker", 1).put("fire", JSONObject.NULL)
            .put("fumo", false).put("tcg", JSONArray()).put("libreria", JSONObject()).put("couple_expenses", "false")
        mount(language)
        assertTrue(model.state.value.permissions.values.none { it })
        openDrawer(language)
        val protected = if (language == AppLanguage.ENGLISH)
            listOf("Poker", "FIRE", "Smoking", "TCG", "Library", "Couple", "Admin")
        else listOf("Poker", "FIRE", "Fumo", "TCG", "Libreria", "Coppia", "Admin")
        protected.forEach { compose.onAllNodes(hasText(it) and hasClickAction()).assertCountEquals(0) }
        compose.onAllNodes(hasText(if (language == AppLanguage.ENGLISH) "Modules" else "Moduli") and hasClickAction())
            .onFirst().performScrollTo().performClick()
        awaitText(if (language == AppLanguage.ENGLISH) "Your Savely" else "Il tuo Savely")
        protected.forEach { compose.onAllNodesWithText(it, substring = false).assertCountEquals(0) }
        compose.onNodeWithTag("nav-transactions").assertIsDisplayed().performClick()
        title(if (language == AppLanguage.ENGLISH) "Transactions" else "Transazioni")
        awaitTransaction("NAV_TRANSACTION_A")
        assertEquals(0, cardReads.get())
        assertEquals(1, releaseChecks.get())
        assertReadOnlyGuards()
    }

    @Test fun revokedTcgEntryCannotReturnViaBackAndRegrantReloadsInsteadOfRestoringItsFilter() {
        permissions[OWNER_A] = JSONObject().put("tcg", true)
        mount()
        enter("TCG")
        awaitCard("NAV_CARD_ORIGINAL")
        // Save a visible route-local filter. It must not be restored after permission loss.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("Cerca per nome o set").performScrollTo().performTextInput("NAV_CARD_ORIGINAL")
        compose.runOnIdle { focus.clearFocus(); keyboard?.hide() }
        compose.runOnIdle { permissions[OWNER_A] = JSONObject().put("tcg", false); model.restore() }
        awaitModelIdle()
        title("Dashboard")
        compose.onAllNodesWithText("NAV_CARD_ORIGINAL", substring = true).assertCountEquals(0)
        val readsAtDenial = cardReads.get()
        openDrawer()
        compose.onAllNodes(hasText("TCG") and hasClickAction()).assertCountEquals(0)
        compose.runOnIdle { back.onBackPressed() } // Close the drawer without changing the route.
        openModulesAndBack()
        assertEquals(readsAtDenial, cardReads.get())
        cardName.set("NAV_CARD_AFTER_REGRANT")
        compose.runOnIdle { permissions[OWNER_A] = JSONObject().put("tcg", true); model.restore() }
        awaitModelIdle()
        enter("TCG")
        awaitCard("NAV_CARD_AFTER_REGRANT")
        compose.onAllNodesWithText("NAV_CARD_ORIGINAL", substring = true).assertCountEquals(0)
        assertTrue(cardReads.get() > readsAtDenial)
        assertReadOnlyGuards()
    }

    @Test fun sameUserNewSessionResetsRouteModuleStateAndPublicReleaseCheck() {
        permissions[OWNER_A] = JSONObject().put("tcg", true)
        mount()
        enter("TCG")
        awaitCard("NAV_CARD_ORIGINAL")
        val reads = cardReads.get()
        cardName.set("NAV_CARD_NEW_SESSION")
        replaceSession(session(OWNER_A, SID_B, "new-login"))
        title("Dashboard")
        compose.onAllNodesWithText("NAV_CARD_ORIGINAL", substring = true).assertCountEquals(0)
        compose.waitUntil(15_000) { releaseChecks.get() == 2 }
        openModulesAndBack()
        enter("TCG")
        awaitCard("NAV_CARD_NEW_SESSION")
        assertTrue(cardReads.get() > reads)
        assertEquals(2, releaseChecks.get())
        assertReadOnlyGuards()
    }

    @Test fun tokenRotationOfTheSameSessionPreservesRouteFilterAndSinglePublicCheck() {
        permissions[OWNER_A] = JSONObject().put("tcg", true)
        mount()
        enter("TCG")
        awaitCard("NAV_CARD_ORIGINAL")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("Cerca per nome o set").performScrollTo().performTextInput("NAV_CARD_ORIGINAL")
        compose.runOnIdle { focus.clearFocus(); keyboard?.hide() }
        replaceSession(session(OWNER_A, SID_A, "rotated-token"))
        title("TCG")
        compose.onNodeWithText("Cerca per nome o set").assertTextContains("NAV_CARD_ORIGINAL", substring = true)
        assertEquals(1, releaseChecks.get())
        assertReadOnlyGuards()
    }

    @Test fun differentUserStartsDashboardAndBackNeverRestoresThePreviousUsersTransactions() {
        mount()
        compose.onNodeWithTag("nav-transactions").performClick()
        awaitTransaction("NAV_TRANSACTION_A")
        replaceSession(session(OWNER_B, SID_B, "owner-b"))
        title("Dashboard")
        compose.onAllNodesWithText("NAV_TRANSACTION_A").assertCountEquals(0)
        openModulesAndBack()
        compose.onNodeWithTag("nav-transactions").performClick()
        title("Transazioni")
        awaitTransaction("NAV_TRANSACTION_B")
        compose.onAllNodesWithText("NAV_TRANSACTION_A").assertCountEquals(0)
        val bReads = requests.filter { it.requestUrl?.encodedPath == "/rest/v1/transactions" &&
            it.getHeader("Authorization") == "Bearer ${requireNotNull(store.load()).accessToken}" }
        assertTrue(bReads.isNotEmpty())
        assertTrue(bReads.all { it.requestUrl?.queryParameter("user_id") == "eq.$OWNER_B" })
        compose.waitUntil(15_000) { releaseChecks.get() == 2 }
        assertEquals(2, releaseChecks.get())
        assertReadOnlyGuards()
    }

    private fun mount(language: AppLanguage = AppLanguage.ITALIAN) {
        languagePreferences.write(language)
        model = AppViewModel(api).also { models.put("navigation-boundary", it) }
        val releaseClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            assertEquals("https://api.github.com/repos/GoryNickel9/Savely-Android-Releases/releases?per_page=20", request.url.toString())
            assertNull(request.header("Authorization")); assertNull(request.header("apikey"))
            releaseChecks.incrementAndGet()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(404).message("Synthetic public read")
                .body("{}".toResponseBody()).build() // Intercepted locally; chain.proceed is never called.
        }.build()
        val release = ReleaseRepository(releaseClient)
        mounted = true
        compose.setContent {
            val state by model.state.collectAsState()
            val dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            val manager = LocalFocusManager.current
            val softwareKeyboard = LocalSoftwareKeyboardController.current
            SideEffect { back = dispatcher; focus = manager; keyboard = softwareKeyboard }
            if (visible.value && state.initialized && state.session != null) {
                LocalizationProvider(languagePreferences) { SavelyTheme("light") {
                    MainShell(model, state, "light", {}, {}, release)
                } }
            }
        }
        awaitModelIdle()
        title("Dashboard")
        compose.waitUntil(15_000) { releaseChecks.get() == 1 }
    }

    private fun replaceSession(next: Session) {
        runBlocking { store.save(next); assertEquals(next, api.session()) }
        compose.runOnIdle { model.restore() }
        awaitModelIdle()
    }

    private fun awaitModelIdle() {
        compose.waitUntil(15_000) { model.state.value.initialized && !model.state.value.busy }
        compose.waitForIdle()
    }

    private fun openDrawer(language: AppLanguage = AppLanguage.ITALIAN) {
        compose.onNodeWithContentDescription(if (language == AppLanguage.ENGLISH) "Open menu" else "Apri menu").performClick()
        compose.waitForIdle()
    }

    private fun enter(label: String) {
        openDrawer()
        compose.onAllNodes(hasText(label) and hasClickAction()).onFirst().performScrollTo().performClick()
        title(label)
    }

    private fun openModulesAndBack() {
        openDrawer()
        compose.onAllNodes(hasText("Moduli") and hasClickAction()).onFirst().performScrollTo().performClick()
        awaitText("Il tuo Savely")
        compose.runOnIdle { back.onBackPressed() }
        title("Dashboard")
    }

    private fun title(value: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("screen-title") and hasText(value)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("screen-title").assertTextEquals(value).assertIsDisplayed()
    }

    private fun awaitText(value: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(value, substring = false).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitCard(value: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Aggiungi") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val displayedName = "Nome carta: $value"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(displayedName))
        compose.onNodeWithText(displayedName, substring = false).assertIsDisplayed()
    }

    private fun awaitTransaction(value: String) {
        val newTransaction = if (languagePreferences.read() == AppLanguage.ENGLISH) "New transaction" else "Nuova transazione"
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(newTransaction) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(value))
        compose.onNodeWithText(value, substring = false).assertIsDisplayed()
    }

    private fun session(user: String, sid: String, rotation: String): Session {
        val claims = Base64.getUrlEncoder().withoutPadding().encodeToString(
            JSONObject().put("session_id", sid).put("aal", "aal2").put("fixture", rotation).toString().toByteArray())
        val token = "synthetic.$claims.signature"
        tokens[token] = user
        return Session(token, "synthetic-refresh-$rotation", System.currentTimeMillis() / 1000 + 3600, user, "navigation@example.test")
    }

    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add(request)
            if (request.method != "GET" || request.getHeader("apikey") != "navigation-public") return reject()
            val bearer = request.getHeader("Authorization") ?: return reject()
            val owner = tokens[bearer.removePrefix("Bearer ")] ?: return reject()
            val url = requireNotNull(request.requestUrl)
            if (url.encodedPath == "/auth/v1/user") return json(JSONObject().put("id", owner).put("factors", JSONArray()))
            if (url.queryParameter("user_id") != "eq.$owner") return reject()
            return when (url.encodedPath) {
                "/rest/v1/profiles" -> json(JSONArray().put(JSONObject().put("user_id", owner).put("default_currency", "EUR")
                    .put("permissions", requireNotNull(permissions[owner]))))
                "/rest/v1/categories", "/rest/v1/budgets" -> json(JSONArray())
                "/rest/v1/transactions" -> {
                    if (url.queryParameter("limit") != "500") return reject()
                    val first = url.queryParameter("offset") == "0"
                    json(if (!first) JSONArray() else JSONArray().put(JSONObject().put("id", TX_ID).put("user_id", owner)
                        .put("type", "expense").put("amount", 1).put("currency", "EUR").put("exchange_rate", 1)
                        .put("category_id", JSONObject.NULL).put("deleted_at", JSONObject.NULL).put("date", LocalDate.now().toString())
                        .put("description", if (owner == OWNER_A) "NAV_TRANSACTION_A" else "NAV_TRANSACTION_B")))
                }
                "/rest/v1/tgc_cards" -> {
                    if (permissions[owner]?.opt("tcg") != true || url.queryParameter("category") != "eq.magic" ||
                        url.queryParameter("limit") != "500") return reject()
                    cardReads.incrementAndGet()
                    json(if (url.queryParameter("offset") != "0") JSONArray() else JSONArray().put(JSONObject()
                        .put("id", CARD_ID).put("user_id", owner).put("category", "magic").put("name", cardName.get())
                        .put("set_code", "NAV").put("condition", "near_mint").put("language", "IT").put("currency", "EUR")
                        .put("quantity", 1).put("purchase_price", 2).put("current_price", 3)
                        .put("purchase_date", "2026-01-01").put("created_at", "2026-01-01T00:00:00Z")))
                }
                else -> reject()
            }
        }
    }

    private fun json(body: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun reject(): MockResponse { unexpected.incrementAndGet(); return MockResponse().setResponseCode(403).setBody("{}") }
    private fun assertReadOnlyGuards() {
        assertEquals(0, nonLocal.get()); assertEquals(0, unexpected.get())
        assertTrue(requests.isNotEmpty() && requests.all { it.method == "GET" })
    }

    private companion object {
        const val OWNER_A = "61000000-0000-4000-8000-000000000001"
        const val OWNER_B = "61000000-0000-4000-8000-000000000002"
        const val SID_A = "62000000-0000-4000-8000-000000000001"
        const val SID_B = "62000000-0000-4000-8000-000000000002"
        const val TX_ID = "63000000-0000-4000-8000-000000000001"
        const val CARD_ID = "64000000-0000-4000-8000-000000000001"
    }
}
