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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic Admin UI against a guarded local TLS server; never contacts production or deletes an account. */
@RunWith(AndroidJUnit4::class)
class AdminScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private lateinit var store: MemorySessionStore
    private lateinit var session: Session
    private val adminAllowed = AtomicBoolean(true)
    private val deleteStatus = AtomicInteger(403)
    private val profileReads = AtomicInteger()
    private val userListReads = AtomicInteger()
    private val registrationReads = AtomicInteger()
    private val deleteAttempts = AtomicInteger()
    private val unexpectedRequests = AtomicInteger()
    private val nonLocalRequests = AtomicInteger()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Savely Admin fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(certificates.sslSocketFactory(), false); dispatcher = fixture(); start() }
        val origin = server.url("/")
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val url = chain.request().url
                if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port) {
                    nonLocalRequests.incrementAndGet()
                    throw IOException("Admin fixture refuses a non-local origin")
                }
                chain.proceed(chain.request())
            }.build()
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"aal":"aal2","session_id":"$SESSION_ID"}""".toByteArray(Charsets.UTF_8))
        session = Session("header.$payload.signature", "synthetic-refresh", System.currentTimeMillis() / 1000 + 3600,
            USER_ID, "admin-fixture@example.test")
        store = MemorySessionStore(session)
        api = SupabaseApi(BackendConfig(origin.toString(), "fixture-public-key", origin.toString()), store, client)
    }

    @After fun teardown() {
        api.close()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun revokedAdminProfileClearsCachedUsersAndRegistrationControlsWithoutLogout() {
        renderAndAwaitLoaded()
        compose.onNodeWithText(OTHER_EMAIL).assertExists()
        compose.onNodeWithText("Registrazioni abilitate").assertExists()
        adminAllowed.set(false)

        compose.onNodeWithText("Aggiorna utenti").performScrollTo().performClick()
        awaitError("Sono necessari i permessi di amministratore.")
        assertNoAdminDataOrDialogs()
        assertEquals(2, profileReads.get())
        assertEquals(1, userListReads.get()) // The denied profile stops before calling the web Admin API.
        assertEquals(1, registrationReads.get())
        assertEquals(0, deleteAttempts.get())
        assertEquals(4, server.requestCount)
        assertSessionAndLocalRequestsPreserved()
    }

    @Test fun webPermissionDenialClosesDeleteDialogButServerFailurePreservesItAndSession() {
        renderAndAwaitLoaded()
        compose.onNodeWithText("Gestisci").performScrollTo().performClick()
        compose.onNodeWithText("Elimina utente").performScrollTo().performClick()
        compose.onNodeWithText("Eliminare questo utente?").assertExists()
        deleteStatus.set(503)

        compose.onNodeWithText("Elimina definitivamente").performClick()
        awaitError("Servizio temporaneamente non disponibile. Riprova.")
        compose.onNodeWithText("Eliminare questo utente?").assertExists()
        compose.onNodeWithText("Elimina definitivamente").assertIsEnabled()
        assertEquals(session, store.load())
        assertEquals(1, deleteAttempts.get()) // No automatic retry of the failed local POST.
        assertEquals(4, server.requestCount)

        deleteStatus.set(403)
        compose.onNodeWithText("Elimina definitivamente").performClick()
        awaitError("Non hai il permesso per questa operazione.")
        assertNoAdminDataOrDialogs()
        assertEquals(2, deleteAttempts.get()) // Both fixture attempts were denied; no account was removed.
        assertEquals(1, userListReads.get())
        assertEquals(1, registrationReads.get())
        assertEquals(5, server.requestCount)
        assertSessionAndLocalRequestsPreserved()
    }

    private fun renderAndAwaitLoaded() {
        compose.setContent { SavelyTheme("light") { AdminScreen(api) } }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Registrazioni abilitate").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("Aggiorna utenti") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitError(message: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun assertNoAdminDataOrDialogs() {
        for (text in listOf(OTHER_EMAIL, "Registrazioni abilitate", "Gestisci", "Salva permessi", "Elimina definitivamente")) {
            compose.onAllNodesWithText(text).assertCountEquals(0)
        }
        compose.onNodeWithText("Eliminare questo utente?").assertDoesNotExist()
        compose.onNodeWithText("Aggiorna utenti").assertIsEnabled()
    }

    private fun assertSessionAndLocalRequestsPreserved() {
        assertEquals(session, store.load())
        assertNull(api.sessionInvalidation.value)
        assertEquals(0, unexpectedRequests.get())
        assertEquals(0, nonLocalRequests.get())
        assertTrue(requests.none { it.requestUrl?.encodedPath == "/api/reset-password" || it.requestUrl?.encodedPath == "/auth/v1/logout" })
        for (request in requests) {
            assertEquals("Bearer ${session.accessToken}", request.getHeader("Authorization"))
            if (request.requestUrl?.encodedPath == "/api/admin") {
                assertNull(request.getHeader("apikey"))
                assertEquals(server.url("/").toString().removeSuffix("/"), request.getHeader("Origin"))
            }
        }
    }

    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add(request)
            return when (request.requestUrl?.encodedPath) {
                "/rest/v1/profiles" -> if (request.method == "GET" && request.requestUrl?.queryParameter("user_id") == "eq.$USER_ID") {
                    profileReads.incrementAndGet()
                    json("""[{"user_id":"$USER_ID","permissions":{"admin":${adminAllowed.get()}}}]""")
                } else unexpected()
                "/rest/v1/rpc/get_registrations_enabled" -> if (request.method == "POST" && request.body.readUtf8() == "{}") {
                    registrationReads.incrementAndGet()
                    json("true")
                } else unexpected()
                "/api/admin" -> {
                    val body = runCatching { JSONObject(request.body.readUtf8()) }.getOrNull()
                    val action = body?.optString("action")
                    val targetUser = body?.optString("userId")
                    if (request.method != "POST") unexpected()
                    else when (action) {
                        "get-users" -> {
                            userListReads.incrementAndGet()
                            json("""{"users":[{"user_id":"$OTHER_ID","email":"$OTHER_EMAIL","full_name":"Synthetic account","permissions":{"poker":true}}]}""")
                        }
                        "delete-user" -> if (targetUser == OTHER_ID) {
                            deleteAttempts.incrementAndGet()
                            json("""{"error":"private fixture diagnostic"}""", deleteStatus.get())
                        } else unexpected()
                        else -> unexpected()
                    }
                }
                else -> unexpected()
            }
        }
    }

    private fun unexpected(): MockResponse {
        unexpectedRequests.incrementAndGet()
        return json("{}", 400)
    }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val USER_ID = "10000000-0000-4000-8000-000000000041"
        const val OTHER_ID = "10000000-0000-4000-8000-000000000042"
        const val SESSION_ID = "20000000-0000-4000-8000-000000000041"
        const val OTHER_EMAIL = "other-admin-fixture@example.test"
    }
}
