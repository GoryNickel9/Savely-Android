package cc.savely.android.modules

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.savely.android.BuildConfig
import cc.savely.android.data.*
import cc.savely.android.ui.SavelyTheme
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real production form and local TLS only; no credentials, external services or production writes. */
@RunWith(AndroidJUnit4::class)
class FireSessionBoundaryTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(120)
    @get:Rule(order = 1) val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val owner = UUID.randomUUID().toString()
    private val foreign = UUID.randomUUID().toString()
    private val sid = UUID.randomUUID().toString()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val gate = AtomicBoolean(false)
    private val failRead = AtomicBoolean(false)
    private val allowRefresh = AtomicBoolean(false)
    private val visible = mutableStateOf(true)
    private val baseline = FireParameters(FireCalculations.Inputs(currentAge = 42, annualExpenses = 12345.0), 17000.0)
    private val foreignBaseline = FireParameters(FireCalculations.Inputs(currentSavings = 98765.0), 12000.0)
    private lateinit var client: OkHttpClient
    private lateinit var server: MockWebServer
    private lateinit var store: MemorySessionStore
    private lateinit var api: SupabaseApi
    private lateinit var focus: FocusManager
    private var keyboard: SoftwareKeyboardController? = null
    private var mounted = false
    private val link get() = BuildConfig.WEB_URL.trimEnd('/') +
        "/fire/barista?age=31&retire=62&savings=125000&contrib=24000&income=60000&return=0.07&inflation=0.03&swr=0.04&expenses=30000&parttime=6000"
    private val imported = FireParameters(FireCalculations.Inputs(currentAge = 31, retirementAge = 62,
        currentSavings = 125000.0, annualContribution = 24000.0, annualIncome = 60000.0,
        expectedReturn = .07, inflationRate = .03, withdrawalRate = .04, annualExpenses = 30000.0), 6000.0)

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Synthetic FIRE ownership fixture")
            .addSubjectAlternativeName("localhost").build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
        server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (request.requestUrl?.encodedPath == "/auth/v1/token") {
                        val refresh = runCatching { JSONObject(request.body.readUtf8()).optString("refresh_token") }.getOrNull()
                        if (!allowRefresh.compareAndSet(true, false) || request.method != "POST" ||
                            request.requestUrl?.queryParameter("grant_type") != "refresh_token" || refresh != "synthetic-refresh-expired")
                            return MockResponse().setResponseCode(403)
                        return MockResponse().setHeader("Content-Type", "application/json")
                            .setBody(session(owner, sid, "rotated").toJson().toString())
                    }
                    if (request.method != "GET" || request.requestUrl?.queryParameter("user_id") != "eq.$owner")
                        return MockResponse().setResponseCode(403)
                    val path = request.requestUrl?.encodedPath
                    if (path !in setOf("/rest/v1/portfolio_assets", "/rest/v1/transactions"))
                        return MockResponse().setResponseCode(404)
                    if (gate.compareAndSet(true, false)) {
                        entered.countDown()
                        if (!release.await(15, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                    }
                    if (failRead.get()) return MockResponse().setResponseCode(503).setBody("{}")
                    val rows = if ((request.requestUrl?.queryParameter("offset")?.toIntOrNull() ?: 0) > 0) JSONArray()
                    else if (path == "/rest/v1/portfolio_assets") JSONArray().put(JSONObject()
                        .put("id", UUID.nameUUIDFromBytes("fire-asset".toByteArray()).toString()).put("user_id", owner)
                        .put("quantity", 2).put("purchase_price", 100).put("current_price", 150))
                    else JSONArray().put(transaction("income", 120000)).put(transaction("expense", 48000))
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(rows.toString())
                }
            }
            start()
        }
        store = MemorySessionStore(session(owner, sid))
        val url = server.url("/").toString()
        api = SupabaseApi(BackendConfig(url, "fixture-public", url), store, client)
        assertTrue(preferences(owner).edit().putString(FireParameters.STORAGE_KEY, baseline.encode()).commit())
        assertTrue(preferences(foreign).edit().putString(FireParameters.STORAGE_KEY, foreignBaseline.encode()).commit())
    }

    @After fun disposeBeforeClosingTransport() {
        release.countDown()
        if (mounted) { compose.runOnIdle { visible.value = false }; compose.waitForIdle() }
        api.close()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        assertTrue(preferences(owner).edit().clear().commit())
        assertTrue(preferences(foreign).edit().clear().commit())
    }

    @Test fun replacementLoginOfSameUserCannotApplyOldPreviewResetShareOrLoadDatabaseDefaults() {
        mount()
        preview()
        val replacement = session(owner, UUID.randomUUID().toString())
        compose.runOnIdle { store.save(replacement) }
        dialogButton("Applica configurazione").performClick()
        awaitText("Account cambiato. Riapri FIRE per continuare.")
        assertStored(baseline)
        dialogButton("Annulla").performClick()
        header(3, "Ripristina valori di esempio").performClick()
        awaitText("Ripristino locale non riuscito. Il salvataggio precedente resta disponibile.", 3)
        assertStored(baseline)
        val sends = AtomicInteger()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                sends.incrementAndGet()
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            header(4, "Condividi link FIRE").performClick()
            awaitText("Impossibile condividere il link FIRE. Controlla i parametri e riprova.", 14)
            assertEquals(0, sends.get())
        } finally { instrumentation.removeMonitor(monitor) }
        header(4, "Reset ai valori dal database").performClick()
        awaitText("Account cambiato. Riapri FIRE per continuare.", 14)
        assertEquals(0, requests.size)
        assertEquals(replacement, store.load())
        assertStored(baseline)
    }

    @Test fun tokenRefreshWithTheSameSessionIdAllowsExplicitImportAndDatabaseReset() {
        mount()
        preview()
        val originalRevision = api.sessionOwnerRevision()
        val rotated = session(owner, sid, "rotated")
        allowRefresh.set(true)
        compose.runOnIdle { store.save(session(owner, sid, "expired").copy(expiresAt = 1)) }
        dialogButton("Applica configurazione").performClick()
        awaitStored(imported)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Importa configurazione FIRE").fetchSemanticsNodes().isEmpty() }
        assertEquals(originalRevision, api.sessionOwnerRevision())
        header(4, "Reset ai valori dal database").performClick()
        val expected = imported.copy(inputs = imported.inputs.copy(currentSavings = 300.0,
            annualIncome = 60000.0, annualContribution = 36000.0))
        awaitStored(expected)
        assertStored(expected)
        assertEquals(5, requests.size)
        assertEquals(1, requests.count { it.method == "POST" && it.requestUrl?.encodedPath == "/auth/v1/token" })
        assertTrue(requests.filter { it.method == "GET" }.all { it.getHeader("Authorization") == "Bearer ${rotated.accessToken}" })
        assertEquals(rotated, store.load())
        assertEquals(originalRevision, api.sessionOwnerRevision())
    }

    @Test fun sameUserReplacementDuringFirstDatabaseReadCannotPublishOrRequestTransactions() =
        rejectReplacementDuringRead(owner)

    @Test fun anotherUserReplacementDuringFirstDatabaseReadCannotPublishOrRequestTransactions() =
        rejectReplacementDuringRead(foreign)

    @Test fun databaseRead503KeepsPreferencesAndBusyControlsUntilAnExplicitSuccessfulRead() {
        gate.set(true)
        failRead.set(true)
        mount()
        header(4, "Usa i miei dati (24 mesi)").performClick()
        assertTrue("Synthetic read entered", entered.await(5, TimeUnit.SECONDS))
        header(4, "Caricamento…").assertIsNotEnabled()
        compose.onNode(hasText("Reset ai valori dal database") and hasClickAction()).performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText("Condividi link FIRE") and hasClickAction()).performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText("Importa link FIRE") and hasClickAction()).performScrollTo().assertIsNotEnabled()
        header(3, "Ripristina valori di esempio").assertIsNotEnabled()
        assertStored(baseline)
        release.countDown()
        awaitText("Servizio temporaneamente non disponibile. Riprova.", 14)
        assertStored(baseline)
        assertEquals(1, requests.size)
        failRead.set(false)
        header(4, "Usa i miei dati (24 mesi)").assertIsEnabled().performClick()
        awaitStored(baseline.copy(inputs = baseline.inputs.copy(currentSavings = 300.0,
            annualIncome = 60000.0, annualContribution = 36000.0, annualExpenses = 24000.0)))
        assertEquals(5, requests.size)
        assertTrue(requests.all { it.method == "GET" && it.requestUrl?.queryParameter("user_id") == "eq.$owner" })
    }

    private fun rejectReplacementDuringRead(nextOwner: String) {
        gate.set(true)
        mount()
        header(4, "Usa i miei dati (24 mesi)").performClick()
        assertTrue("Synthetic read entered", entered.await(5, TimeUnit.SECONDS))
        val replacement = session(nextOwner, UUID.randomUUID().toString())
        compose.runOnIdle { store.save(replacement) }
        runBlocking { api.session() }
        release.countDown()
        awaitText("La sessione è cambiata. Aggiorna la schermata prima di continuare.", 14)
        assertEquals(1, requests.size)
        assertEquals("/rest/v1/portfolio_assets", requests.single().requestUrl?.encodedPath)
        assertStored(baseline)
        assertEquals(replacement, store.load())
    }

    private fun mount() {
        compose.setContent { SavelyTheme("light") {
            val manager = LocalFocusManager.current
            val controller = LocalSoftwareKeyboardController.current
            SideEffect { focus = manager; keyboard = controller }
            if (visible.value) ModuleScreen(api, "fire", Modifier.fillMaxSize())
        } }
        mounted = true
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Ripristina valori di esempio").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun preview() {
        header(4, "Importa link FIRE").performClick()
        compose.onNodeWithText("Link FIRE condiviso").performScrollTo().performTextReplacement(link)
        clearFocus()
        dialogButton("Anteprima").performClick()
        compose.onNodeWithText("Anteprima della configurazione").performScrollTo().assertIsDisplayed()
        dialogButton("Applica configurazione").assertIsEnabled()
        assertStored(baseline)
    }
    private fun clearFocus() = compose.runOnIdle { focus.clearFocus(force = true); keyboard?.hide() }
    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun header(index: Int, label: String): SemanticsNodeInteraction {
        clearFocus()
        list().performScrollToIndex(index)
        return compose.onNode(hasText(label) and hasClickAction()).performScrollTo().assertIsDisplayed()
    }
    private fun dialogButton(label: String): SemanticsNodeInteraction {
        clearFocus()
        return compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed()
    }
    private fun awaitText(label: String, item: Int? = null) {
        clearFocus()
        if (item != null) list().performScrollToIndex(item)
        compose.waitUntil(15_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
    }
    private fun preferences(user: String) = context.getSharedPreferences(FireParameters.preferencesName(user), Context.MODE_PRIVATE)
    private fun restored(user: String) = FireParameters.decode(checkNotNull(preferences(user).getString(FireParameters.STORAGE_KEY, null)))
    private fun awaitStored(value: FireParameters) = compose.waitUntil(15_000) { restored(owner) == value }
    private fun assertStored(value: FireParameters) {
        assertEquals(value, restored(owner))
        assertEquals(foreignBaseline, restored(foreign))
    }
    private fun transaction(type: String, amount: Int) = JSONObject()
        .put("id", UUID.nameUUIDFromBytes(type.toByteArray()).toString()).put("user_id", owner)
        .put("date", LocalDate.now().minusDays(1).toString()).put("type", type)
        .put("amount", amount).put("deleted_at", JSONObject.NULL)
    private fun session(user: String, sessionId: String, nonce: String = "original"): Session {
        fun encoded(value: String) = Base64.encodeToString(value.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val claims = JSONObject().put("sub", user).put("session_id", sessionId).put("nonce", nonce)
        return Session("${encoded("{\"alg\":\"HS256\"}")}.${encoded(claims.toString())}.synthetic",
            "synthetic-refresh-$nonce", Long.MAX_VALUE, user, "fixture@example.test")
    }
}
