package cc.savely.android.modules

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import cc.savely.android.data.*
import cc.savely.android.ui.SavelyTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Database reset uses Android TLS and disk persistence; every request targets a synthetic owner. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class FireDatabaseScreenTest {
    private val effects = StandardTestDispatcher()
    @get:Rule val compose = createComposeRule(effectContext = effects)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val owner = UUID.randomUUID().toString()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private val preferences get() = context.getSharedPreferences(FireParameters.preferencesName(owner), Context.MODE_PRIVATE)

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("FIRE database fixture").addSubjectAlternativeName("localhost").build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
        server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (request.method != "GET" || request.requestUrl?.queryParameter("user_id") != "eq.$owner") return MockResponse().setResponseCode(403)
                    val offset = request.requestUrl?.queryParameter("offset")?.toIntOrNull() ?: 0
                    val body = if (offset > 0) JSONArray() else when (request.requestUrl?.encodedPath) {
                        "/rest/v1/portfolio_assets" -> JSONArray().put(JSONObject().put("id", "asset-fixture").put("user_id", owner)
                            .put("quantity", 2).put("purchase_price", 100).put("current_price", 150))
                        "/rest/v1/transactions" -> JSONArray().put(transaction("income-fixture", "income", 120000)).put(transaction("expense-fixture", "expense", 48000))
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
                }
            }
            start()
        }
        val base = server.url("/").toString()
        api = SupabaseApi(BackendConfig(base, "fixture-public", base), MemorySessionStore(Session("fixture-token", "fixture-refresh", System.currentTimeMillis() / 1000 + 3600, owner, "fire@example.test")), client)
        val initial = FireParameters(inputs = FireCalculations.Inputs(currentAge = 42, annualExpenses = 12345.0, expectedReturn = 0.07), partTimeIncome = 17000.0)
        assertTrue(preferences.edit().putString(FireParameters.STORAGE_KEY, initial.encode()).commit())
    }

    @After fun cleanup() {
        preferences.edit().clear().commit()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun databaseResetPreservesExpensesAgeRatesAndPartTimeThenUseMyDataAlsoReplacesExpenses() {
        compose.setContent { SavelyTheme("light") { ModuleScreen(api, "fire", Modifier.fillMaxSize()) } }
        assertEquals(0, requests.size)
        click("Reset ai valori dal database")
        awaitParameters { it.inputs.currentSavings == 300.0 && it.inputs.annualIncome == 60000.0 }
        var saved = readParameters()
        assertEquals(36000.0, saved.inputs.annualContribution, 0.0)
        assertEquals(12345.0, saved.inputs.annualExpenses, 0.0)
        assertEquals(42, saved.inputs.currentAge)
        assertEquals(0.07, saved.inputs.expectedReturn, 0.0)
        assertEquals(17000.0, saved.partTimeIncome, 0.0)
        click("Usa i miei dati (24 mesi)")
        awaitParameters { it.inputs.annualExpenses == 24000.0 }
        saved = readParameters()
        assertEquals(300.0, saved.inputs.currentSavings, 0.0)
        assertEquals(42, saved.inputs.currentAge)
        assertEquals(17000.0, saved.partTimeIncome, 0.0)
        assertEquals(8, requests.size)
        assertTrue(requests.all { it.method == "GET" && it.requestUrl?.queryParameter("user_id") == "eq.$owner" })
    }

    private fun transaction(id: String, type: String, amount: Int) = JSONObject().put("id", id).put("user_id", owner)
        .put("date", LocalDate.now().minusDays(1).toString()).put("type", type).put("amount", amount).put("deleted_at", JSONObject.NULL)
    private fun readParameters() = FireParameters.decode(preferences.getString(FireParameters.STORAGE_KEY, null)!!)
    private fun awaitParameters(predicate: (FireParameters) -> Boolean) {
        compose.waitUntil(15_000) {
            compose.runOnUiThread { effects.scheduler.runCurrent() }
            predicate(readParameters())
        }
    }
    private fun click(label: String) {
        compose.waitUntil(10_000) {
            compose.runOnUiThread { effects.scheduler.runCurrent() }
            compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
    }
}
