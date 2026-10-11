package cc.savely.android.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import okhttp3.OkHttpClient
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
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Native Couple flows against an isolated HTTPS fixture, with no real account or backend mutation. */
@RunWith(AndroidJUnit4::class)
class CoupleScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private val connected = AtomicBoolean(false)
    private val invited = AtomicBoolean(false)
    private val shared = AtomicBoolean(false)
    private val lookups = AtomicInteger()
    private val sharePosts = AtomicInteger()
    private val shareBody = AtomicReference("")

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Savely Couple fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); dispatcher = fixture(); start() }
        api = SupabaseApi(BackendConfig(server.url("/").toString(), "couple-public-key"),
            MemorySessionStore(Session("couple-ui-token", "refresh", System.currentTimeMillis() / 1000 + 3600, USER, "couple-ui@example.test")), client)
    }
    @After fun teardown() {
        server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }

    @Test fun validatesPartnerCodeBeforeLookupAndDisablesAnotherPendingInvitation() {
        compose.setContent { SavelyTheme("light") { CoupleScreen(api) } }
        waitFor("ABCD2345")
        compose.onNodeWithText("Codice del partner").performTextInput("OABC2345")
        compose.onNodeWithText("Invita partner").performClick()
        waitFor("Non usare O", substring = true)
        assertEquals(0, lookups.get())
        compose.onNodeWithText("Codice del partner").performTextClearance()
        compose.onNodeWithText("Codice del partner").performTextInput("abcd2345")
        compose.onNodeWithText("Invita partner").performClick()
        waitFor("Invito inviato")
        assertEquals(1, lookups.get())
        compose.onNodeWithText("Invita partner").assertIsNotEnabled()
    }

    @Test fun rendersBudgetMedianAndValidatesCustomSplitBeforeSharing() {
        connected.set(true)
        compose.setContent { SavelyTheme("light") { CoupleScreen(api) } }
        waitFor("Mediana mensile EUR:", substring = true)
        compose.onNodeWithText("Mediana 15,00", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Scegli transazione").performScrollTo().performClick()
        compose.onNodeWithText("${LocalDate.now()} · 1.01 EUR · Spesa nuova").performClick()
        compose.onNodeWithText("Le quote uguali superano", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Quota personalizzata").performScrollTo().performClick()
        compose.onNodeWithText("Importo del partner nella valuta della transazione").performScrollTo().performTextInput("0")
        compose.onNodeWithText("Condividi spesa").performScrollTo().performClick()
        waitFor("Inserisci un importo positivo", substring = true)
        assertEquals(0, sharePosts.get())
        compose.onNodeWithText("Importo del partner nella valuta della transazione").performScrollTo().performTextClearance()
        compose.onNodeWithText("Importo del partner nella valuta della transazione").performTextInput("0,50")
        compose.onNodeWithText("Condividi spesa").performScrollTo().performClick()
        // The selection is cleared before the async reload ends; wait for refreshed data and an enabled button.
        compose.waitUntil(15_000) {
            sharePosts.get() == 1 &&
                compose.onAllNodesWithText("3 condivisioni caricate.", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("Scegli transazione") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        val body = JSONObject(shareBody.get())
        assertEquals("custom", body.getString("split_mode"))
        assertEquals("0.50", body.getString("partner_amount"))
        assertEquals(USER, body.getString("created_by"))
        assertFalse(body.has("category_id"))
        compose.onNodeWithText("Scegli transazione").assertIsEnabled().performScrollTo().performClick()
        waitFor("${LocalDate.now()} · 1.01 EUR · Spesa nuova")
        compose.onNodeWithText("${LocalDate.now()} · 1.01 EUR · Spesa nuova").assertIsNotEnabled()
    }

    private fun waitFor(text: String, substring: Boolean = false) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.getHeader("Authorization") != "Bearer couple-ui-token" || request.getHeader("apikey") != "couple-public-key") return json("{}", 401)
            val url = request.requestUrl ?: return json("{}", 400)
            return when (url.encodedPath) {
                "/rest/v1/profiles" -> json("""[{"couple_code":"ABCD2345","permissions":{"couple_expenses":true}}]""")
                "/rest/v1/couple_connections" -> json(if (connected.get()) """[{"id":"$CONNECTION","user_a":"$USER","user_b":"$PARTNER"}]""" else "[]")
                "/rest/v1/couple_connection_requests" -> when (request.method) {
                    "POST" -> { invited.set(true); json("""[{"id":"$REQUEST","sender_id":"$USER","receiver_id":"$PARTNER","status":"pending"}]""") }
                    else -> json(if (invited.get() && url.queryParameter("id") == null) """[{"id":"$REQUEST","sender_id":"$USER","receiver_id":"$PARTNER","status":"pending"}]""" else "[]")
                }
                "/rest/v1/rpc/find_user_by_couple_code" -> {
                    lookups.incrementAndGet()
                    if (JSONObject(request.body.readUtf8()).getString("p_code") != "ABCD2345") json("null") else json("\"$PARTNER\"")
                }
                "/rest/v1/shared_expenses_view" -> {
                    if (url.queryParameter("id") != null) json("[]") else {
                        val rows = JSONArray().put(expense(SHARE1, OLD_TX, "2026-01-01", "10.00", "Casa"))
                            .put(expense(SHARE2, OLD_TX2, "2026-02-01", "20.00", "Casa"))
                        if (shared.get()) rows.put(expense(SHARE3, TX, LocalDate.now().toString(), "0.50", null))
                        json(rows.toString())
                    }
                }
                "/rest/v1/couple_budgets" -> json(if (url.queryParameter("id") == null) """[{"id":"$BUDGET","couple_category_name":"Casa","amount":12,"currency":"EUR"}]""" else "[]")
                "/rest/v1/transactions" -> json("""[{"id":"$TX","amount":1.01,"currency":"EUR","date":"${LocalDate.now()}","description":"Spesa nuova","user_id":"$USER","type":"expense"}]""")
                "/rest/v1/shared_expenses" -> if (request.method == "POST") {
                    shareBody.set(request.body.readUtf8()); sharePosts.incrementAndGet(); shared.set(true); json("""[{"id":"$SHARE3"}]""")
                } else json(if (shared.get()) """[{"id":"$SHARE3"}]""" else "[]")
                else -> json("{}", 404)
            }
        }
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun expense(id: String, tx: String, date: String, share: String, category: String?) = JSONObject()
        .put("id", id).put("original_tx_id", tx).put("created_by", USER).put("date", date).put("my_share_amount", share)
        .put("currency", "EUR").put("description", if (tx == TX) "Spesa nuova" else "Spesa precedente")
        .put("couple_category_name", category ?: JSONObject.NULL).put("tx_deleted_at", JSONObject.NULL)

    private companion object {
        const val USER = "10000000-0000-0000-0000-000000000001"
        const val PARTNER = "10000000-0000-0000-0000-000000000002"
        const val CONNECTION = "20000000-0000-0000-0000-000000000001"
        const val TX = "30000000-0000-0000-0000-000000000003"
        const val OLD_TX = "30000000-0000-0000-0000-000000000001"
        const val OLD_TX2 = "30000000-0000-0000-0000-000000000002"
        const val SHARE1 = "40000000-0000-0000-0000-000000000001"
        const val SHARE2 = "40000000-0000-0000-0000-000000000002"
        const val SHARE3 = "40000000-0000-0000-0000-000000000003"
        const val BUDGET = "50000000-0000-0000-0000-000000000001"
        const val REQUEST = "60000000-0000-0000-0000-000000000001"
    }
}
