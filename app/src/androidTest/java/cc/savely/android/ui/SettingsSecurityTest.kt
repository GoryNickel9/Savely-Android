package cc.savely.android.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.data.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Account controls against a local TLS fixture; no email, password or real backend is changed. */
@RunWith(AndroidJUnit4::class)
class SettingsSecurityTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var api: SupabaseApi
    private lateinit var store: MemorySessionStore
    private lateinit var session: Session
    private lateinit var client: OkHttpClient
    private val revokedId = AtomicReference("")
    private val passwordWrites = AtomicInteger()
    private val passwordBody = AtomicReference("")
    private val signedOut = AtomicBoolean(false)

    @Before fun setup() {
        val cert = HeldCertificate.Builder().commonName("Savely account fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        server = MockWebServer().apply { useHttps(serverCert.sslSocketFactory(), false); dispatcher = fixture(); start() }
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"session_id":"$SID"}""".toByteArray())
        session = Session("header.$payload.signature", "refresh", System.currentTimeMillis() / 1000 + 3600, USER, "account@example.test")
        store = MemorySessionStore(session)
        api = SupabaseApi(BackendConfig(server.url("/").toString(), "account-public-key"), store, client)
    }
    @After fun teardown() {
        api.close(); server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }

    @Test fun revocationUsesSessionIdDisablesLegacyRowsAndClosesCurrentDevice() {
        render()
        compose.onNodeWithText("Sicurezza", substring = false).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("revoke-session-$SID") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("revoke-session-unavailable-1").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("revoke-session-$SID").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Conferma", substring = false).performClick()
        compose.waitUntil(15_000) { signedOut.get() }
        assertEquals(SID, revokedId.get())
        assertNotEquals(ACTIVITY_ID, revokedId.get())
        assertNull(store.load())
    }

    @Test fun passwordChangeNeedsPolicyAndConfirmationAndClearsBothFields() {
        render()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Salva profilo") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Nuova password", substring = false).performScrollTo().performTextInput("weak")
        compose.onNodeWithText("Conferma nuova password", substring = false).performScrollTo().performTextInput("weak")
        compose.onNodeWithText("Modifica password", substring = false).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Nuova password", substring = false).performScrollTo().performTextReplacement("StrongPassword12!")
        compose.onNodeWithText("Modifica password", substring = false).performScrollTo().assertIsNotEnabled()
        assertEquals(0, passwordWrites.get())
        compose.onNodeWithText("Conferma nuova password", substring = false).performScrollTo().performTextReplacement("StrongPassword12!")
        compose.onNodeWithText("Modifica password", substring = false).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Password aggiornata.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, passwordWrites.get())
        assertEquals("StrongPassword12!", JSONObject(passwordBody.get()).getString("password"))
        compose.onNodeWithText("Modifica password", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("Nuova password", substring = false)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Conferma nuova password", substring = false)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    private fun render() = compose.setContent {
        SavelyTheme("light") { SettingsScreen(api, session, "light", {}, { signedOut.set(true) }, {}) }
    }

    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
            "/rest/v1/profiles" -> json("""[{"full_name":"Account fixture","default_currency":"EUR"}]""")
            "/auth/v1/user" -> if (request.method == "PUT") {
                passwordBody.set(request.body.readUtf8()); passwordWrites.incrementAndGet()
                json("""{"id":"$USER","email":"account@example.test"}""")
            } else json("""{"factors":[]}""")
            "/rest/v1/rpc/get_active_sessions" -> json("""[{"id":"$ACTIVITY_ID","session_id":"$SID","user_agent":"Native fixture","created_at":"2026-10-11T10:00:00Z"},{"id":"$SID","session_id":null,"user_agent":"Legacy fixture","created_at":"2026-10-10T10:00:00Z"}]""")
            "/rest/v1/rpc/delete_session" -> {
                revokedId.set(JSONObject(request.body.readUtf8()).getString("p_session_id")); json("null")
            }
            else -> json("{}", 404)
        }
    }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    companion object {
        private const val USER = "10000000-0000-4000-8000-000000000010"
        private const val SID = "20000000-0000-4000-8000-000000000010"
        private const val ACTIVITY_ID = "30000000-0000-4000-8000-000000000010"
    }
}
