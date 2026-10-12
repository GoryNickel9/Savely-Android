package cc.savely.android.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual Activity recreation against loopback TLS; no real account, email or backend is changed. */
class SettingsProfileRecreationTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(90)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private val first = session(USER_A, "20000000-0000-4000-8000-000000000010")
    private val second = session(USER_B, "20000000-0000-4000-8000-000000000011")
    private val relogin = session(USER_A, "20000000-0000-4000-8000-000000000012")
    private val current = mutableStateOf(first)
    private val visible = mutableStateOf(true)
    private val store = MemorySessionStore(first)
    private val reads = AtomicInteger()
    private val denied = AtomicInteger()
    private val writes = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    private val releaseRead = CountDownLatch(1)
    @Volatile private var gateReads = false
    @Volatile private var profileStatus = 200
    @Volatile private var missingProfile = false
    private lateinit var api: SupabaseApi
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var focus: FocusManager
    private var keyboard: SoftwareKeyboardController? = null
    private var mounted = false
    private val lifecycle = ActivityLifecycleCallback { activity, stage ->
        if (mounted && activity is ComponentActivity && stage == Stage.CREATED) {
            installContent(activity)
        }
    }

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            dispatcher = fixture()
            start()
        }
        api = SupabaseApi(BackendConfig(server.url("/").toString(), "profile-public-fixture"), store, client)
        runBlocking { assertEquals(first, api.session()) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle)
        }
    }

    @After fun cleanup() {
        releaseRead.countDown()
        if (mounted) { compose.runOnIdle { visible.value = false }; compose.waitForIdle() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
        }
        api.close()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        assertEquals("Only owned profile GET/PATCH requests are allowed", 0, denied.get())
    }

    @Test fun restoredDraftSurvivesServerReloadAndOnlyExplicitSaveWritesIt() {
        mount()
        awaitProfile("Profile A", "EUR")
        editDraft("Draft A", "USD")
        compose.onNodeWithText("Nuova password", substring = false).performScrollTo().performTextInput("Unsubmitted12!")
        compose.onNodeWithText("Conferma nuova password", substring = false).performScrollTo().performTextInput("Unsubmitted12!")
        clearInput()
        assertEquals(0, writes.size)
        val readsBefore = reads.get()
        compose.activityRule.scenario.recreate()
        awaitProfile("Draft A", "USD", readsBefore + 1)
        compose.onNodeWithText("Nuova password", substring = false).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Conferma nuova password", substring = false).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertEquals(0, writes.size)
        compose.onNodeWithText("Salva profilo", substring = false).performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Profilo salvato.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, writes.size)
        val (owner, body) = writes.single()
        assertEquals(USER_A, owner)
        assertEquals(setOf("full_name", "default_currency"), body.keys().asSequence().toSet())
        assertEquals("Draft A", body.getString("full_name"))
        assertEquals("USD", body.getString("default_currency"))
        assertEquals(first, store.load())
    }

    @Test fun draftResetsForAnotherAccountAndForANewLoginOfTheSameAccount() {
        mount()
        awaitProfile("Profile A", "EUR")
        editDraft("Private draft A", "USD")
        switchSession(relogin)
        awaitProfile("Profile A", "EUR", 2)
        editDraft("Another private draft A", "GBP")
        switchSession(second)
        awaitProfile("Profile B", "CHF", 3)
        assertEquals(0, writes.size)
        assertEquals(second, store.load())
    }

    @Test fun profileControlsStayDisabledUntilInitialProfileArrives() {
        gateReads = true
        mount()
        compose.waitUntil(15_000) { reads.get() == 1 }
        assertProfileDisabled()
        releaseRead.countDown()
        awaitProfile("Profile A", "EUR")
        assertEquals(0, writes.size)
    }

    @Test fun missingProfileDoesNotEnableDraftOrIssueWrites() {
        missingProfile = true
        mount()
        awaitFinishedRead()
        assertProfileDisabled()
        assertEquals(0, writes.size)
    }

    @Test fun profileReadFailureDoesNotEnableDraftOrRetryAutomatically() {
        profileStatus = 503
        mount()
        awaitFinishedRead()
        compose.onNodeWithText("Servizio temporaneamente non disponibile. Riprova.").performScrollTo().assertExists()
        assertProfileDisabled()
        assertEquals(1, reads.get())
        assertEquals(0, writes.size)
    }

    private fun mount() {
        mounted = true
        compose.activityRule.scenario.onActivity { installContent(it) }
    }

    private fun installContent(activity: ComponentActivity) {
        // The same composition call site keeps rememberSaveable keys stable across recreation.
        activity.setContent { FixtureContent() }
    }

    @Composable private fun FixtureContent() {
        if (visible.value) {
            val manager = LocalFocusManager.current
            val controller = LocalSoftwareKeyboardController.current
            SideEffect { focus = manager; keyboard = controller }
            SavelyTheme("light") { SettingsScreen(api, current.value, "light", {}, {}, {}) }
        }
    }

    private fun clearInput() = compose.runOnIdle { focus.clearFocus(force = true); keyboard?.hide() }

    private fun awaitProfile(name: String, currency: String, minimumReads: Int = 1) {
        compose.waitUntil(15_000) {
            reads.get() >= minimumReads && compose.onAllNodes(hasText("Salva profilo") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Nome completo", substring = false).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(name)))
        compose.onNodeWithText(currency, substring = false).performScrollTo().assertIsSelected().assertIsEnabled()
    }

    private fun editDraft(name: String, currency: String) {
        compose.onNodeWithText("Nome completo", substring = false).performScrollTo().performTextReplacement(name)
        clearInput()
        compose.onNodeWithText(currency, substring = false).performScrollTo().performClick()
    }

    private fun switchSession(next: Session) {
        store.save(next)
        runBlocking { assertEquals(next, api.session()) }
        compose.runOnIdle { current.value = next }
    }

    private fun awaitFinishedRead() {
        compose.waitUntil(15_000) {
            reads.get() == 1 && compose.onAllNodes(hasText("Nuova email") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertProfileDisabled() {
        compose.onNodeWithText("Nome completo", substring = false).performScrollTo().assertIsNotEnabled()
        listOf("EUR", "USD", "GBP", "CHF", "JPY", "CNY", "IDR").forEach {
            compose.onNodeWithText(it, substring = false).performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithText("Salva profilo", substring = false).performScrollTo().assertIsNotEnabled()
    }

    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val identity = listOf(first, second, relogin).singleOrNull {
                request.getHeader("Authorization") == "Bearer ${it.accessToken}"
            } ?: return reject()
            val url = request.requestUrl ?: return reject()
            if (url.encodedPath != "/rest/v1/profiles" || url.queryParameter("user_id") != "eq.${identity.userId}" ||
                request.getHeader("apikey") != "profile-public-fixture") return reject()
            if (request.method == "GET" && url.queryParameter("select") == "*") {
                reads.incrementAndGet()
                if (gateReads && !releaseRead.await(15, TimeUnit.SECONDS)) return json("{}", 504)
                if (profileStatus != 200) return json("{}", profileStatus)
                return json(if (missingProfile) "[]" else profile(identity.userId))
            }
            if (request.method == "PATCH") {
                val body = runCatching { JSONObject(request.body.readUtf8()) }.getOrElse { return reject() }
                if (body.keys().asSequence().toSet() != setOf("full_name", "default_currency")) return reject()
                writes += identity.userId to body
                return json("[$body]")
            }
            return reject()
        }
    }

    private fun profile(owner: String) = if (owner == USER_A)
        """[{"full_name":"Profile A","default_currency":"EUR"}]""" else
        """[{"full_name":"Profile B","default_currency":"CHF"}]"""
    private fun reject(): MockResponse { denied.incrementAndGet(); return json("{}", 403) }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun session(owner: String, login: String): Session {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"session_id":"$login"}""".toByteArray())
        return Session("header.$payload.signature", "refresh-$login", System.currentTimeMillis() / 1000 + 3600,
            owner, if (owner == USER_A) "a@example.test" else "b@example.test")
    }

    companion object {
        private const val USER_A = "10000000-0000-4000-8000-000000000010"
        private const val USER_B = "10000000-0000-4000-8000-000000000011"
    }
}
