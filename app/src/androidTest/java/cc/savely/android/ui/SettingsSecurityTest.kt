package cc.savely.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.data.*
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Account controls against a local TLS fixture; no email, password or real backend is changed. */
@RunWith(AndroidJUnit4::class)
class SettingsSecurityTest {
    @get:Rule val compose = createComposeRule()
    private val visible = mutableStateOf(true)
    private var mounted = false
    private lateinit var server: MockWebServer
    private lateinit var api: SupabaseApi
    private lateinit var store: MemorySessionStore
    private lateinit var session: Session
    private lateinit var client: OkHttpClient
    private val revokedId = AtomicReference("")
    private val passwordWrites = AtomicInteger()
    private val passwordBody = AtomicReference("")
    private val signedOut = AtomicBoolean(false)
    private val otherLogouts = AtomicInteger()
    private val localLogouts = AtomicInteger()
    private val otherLogoutStatus = AtomicInteger(204)
    private val accountDeletes = AtomicInteger()
    private val revocationStatus = AtomicInteger(200)
    private val revocations = AtomicInteger()
    private val holdRevocation = AtomicBoolean(false)
    private val revocationEntered = CountDownLatch(1)
    private val releaseRevocation = CountDownLatch(1)

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
        runBlocking { assertEquals(session, api.session()) } // Match the observed owner before MainShell renders settings.
    }
    @After fun teardown() {
        releaseRevocation.countDown()
        try {
            if (mounted) {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
            }
        } finally {
            api.close(); server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun revocationUsesSessionIdDisablesLegacyRowsAndClosesCurrentDevice() {
        render()
        compose.onNodeWithText("Sicurezza", substring = false).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("revoke-session-$SID") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("revoke-session-unavailable-1").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("revoke-session-$SID").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Conferma", substring = false).performClick()
        compose.waitUntil(15_000) { store.load() == null && api.sessionInvalidation.value != null }
        assertEquals(SID, revokedId.get())
        assertNotEquals(ACTIVITY_ID, revokedId.get())
        assertNull(store.load())
        assertFalse(signedOut.get())
        assertEquals(0, localLogouts.get())
    }

    @Test fun anotherDeviceRevocationNeedsConsentAndServerFailureAllowsOnlyExplicitRetry() {
        openSecurity()
        compose.onNodeWithTag("revoke-session-$OTHER_SID").performScrollTo().performClick()
        compose.onNodeWithText("Revocare questa sessione?").assertExists()
        assertEquals(0, revocations.get())
        compose.onNode(hasText("Annulla", substring = false) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(0, revocations.get())
        assertEquals(session, store.load())

        revocationStatus.set(503)
        compose.onNodeWithTag("revoke-session-$OTHER_SID").performScrollTo().performClick()
        sessionConfirm().performClick()
        compose.waitUntil(15_000) {
            revocations.get() == 1 && compose.onAllNodesWithText("Servizio temporaneamente non disponibile. Riprova.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("revoke-session-$OTHER_SID").performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertEquals(1, revocations.get())
        assertEquals(OTHER_SID, revokedId.get())
        assertEquals(session, store.load())
        assertNull(api.sessionInvalidation.value)
        assertFalse(signedOut.get())
        assertEquals(0, localLogouts.get())
        assertEquals(0, otherLogouts.get())

        revocationStatus.set(200)
        compose.onNodeWithTag("revoke-session-$OTHER_SID").performClick()
        sessionConfirm().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Accesso revocato.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("revoke-session-$OTHER_SID").assertDoesNotExist()
        compose.onNodeWithTag("revoke-session-$SID").assertIsEnabled()
        assertEquals(2, revocations.get())
        assertEquals(session, store.load())
        assertNull(api.sessionInvalidation.value)
        assertFalse(signedOut.get())
        assertEquals(0, localLogouts.get())
        assertEquals(0, otherLogouts.get())
    }

    @Test fun currentDeviceRevocationBusyBlocksRepeatedConfirmationAndDoesNotSendExtraLogout() {
        holdRevocation.set(true)
        openSecurity()
        compose.onNodeWithTag("revoke-session-$SID").performScrollTo().performClick()
        val confirmClick = sessionConfirm().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { confirmClick(); confirmClick() }
        compose.waitUntil(15_000) { revocationEntered.count == 0L }
        compose.onNodeWithText("Revocare questa sessione?").assertDoesNotExist()
        compose.onNodeWithTag("revoke-session-$SID").assertIsNotEnabled()
        compose.onNodeWithTag("revoke-session-$OTHER_SID").assertIsNotEnabled()
        compose.onNodeWithTag("sign-out-other-sessions").assertIsNotEnabled()
        compose.onNodeWithText("Aggiorna sicurezza", substring = false).assertIsNotEnabled()
        assertEquals(1, revocations.get())
        assertEquals(session, store.load())
        releaseRevocation.countDown()
        compose.waitUntil(15_000) { store.load() == null && api.sessionInvalidation.value != null }
        assertEquals(SID, revokedId.get())
        assertEquals(1, revocations.get())
        assertFalse(signedOut.get())
        assertEquals(0, localLogouts.get())
        assertEquals(0, otherLogouts.get())
    }

    @Test fun staleRevocationDialogCannotTargetAReplacementLoginOfTheSameUser() {
        openSecurity()
        compose.onNodeWithTag("revoke-session-$OTHER_SID").performScrollTo().performClick()
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"session_id":"$REPLACEMENT_SID"}""".toByteArray())
        val replacement = session.copy(accessToken = "header.$payload.signature", refreshToken = "replacement-refresh")
        runBlocking { store.save(replacement); assertEquals(replacement, api.session()) }
        val requestsBeforeClick = server.requestCount
        sessionConfirm().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("La sessione è cambiata. Aggiorna la schermata prima di continuare.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("revoke-session-$OTHER_SID").assertDoesNotExist()
        compose.onNodeWithText("Revocare questa sessione?").assertDoesNotExist()
        assertEquals(requestsBeforeClick, server.requestCount)
        assertEquals(0, revocations.get())
        assertEquals(replacement, store.load())
        assertNull(api.sessionInvalidation.value)
        assertFalse(signedOut.get())
        assertEquals(0, localLogouts.get())
        assertEquals(0, otherLogouts.get())
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

    @Test fun otherSessionsNeedConfirmationAndServerFailurePreservesCurrentSessionWithoutRetry() {
        render()
        compose.onNodeWithText("Sicurezza", substring = false).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("sign-out-other-sessions") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sign-out-other-sessions").performScrollTo().performClick()
        compose.onNodeWithText("Chiudere le altre sessioni?").assertExists()
        assertEquals(0, otherLogouts.get())
        compose.onNodeWithText("Annulla", substring = false).performClick()
        assertEquals(0, otherLogouts.get())

        otherLogoutStatus.set(503)
        compose.onNodeWithTag("sign-out-other-sessions").performScrollTo().performClick()
        compose.onNodeWithText("Conferma", substring = false).performClick()
        compose.waitUntil(15_000) {
            otherLogouts.get() == 1 && compose.onAllNodes(hasText("Conferma") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Chiudere le altre sessioni?").assertExists()
        assertEquals(session, store.load())
        assertFalse(signedOut.get())
        assertEquals(1, otherLogouts.get())

        otherLogoutStatus.set(204)
        compose.onNodeWithText("Conferma", substring = false).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Altre sessioni chiuse.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, otherLogouts.get())
        assertEquals(session, store.load())
        assertFalse(signedOut.get())
        assertEquals("", revokedId.get())
        compose.onNodeWithText("Chiudere le altre sessioni?").assertDoesNotExist()
    }

    @Test fun accountDeletionBodyErrorKeepsSessionAndConfirmationForExplicitRetry() {
        render()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Salva profilo") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Elimina definitivamente l’account").performScrollTo().performClick()
        compose.onNodeWithText("Elimina account", substring = false).assertIsNotEnabled()
        assertEquals(0, accountDeletes.get())
        compose.onNodeWithText("Conferma", substring = false).performTextInput("ELIMINA")
        compose.onNodeWithText("Elimina account", substring = false).assertIsEnabled().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Impossibile eliminare l’account. Riprova.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Eliminare l’account?").assertExists()
        compose.onNodeWithText("Elimina account", substring = false).assertIsEnabled()
        assertEquals(1, accountDeletes.get())
        assertEquals(session, store.load())
        assertFalse(signedOut.get())
        assertEquals(0, otherLogouts.get())
        compose.onNodeWithText("Annulla", substring = false).performClick()
        compose.onNodeWithText("Elimina definitivamente l’account").performScrollTo().performClick()
        compose.onNodeWithText("Conferma", substring = false)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Elimina account", substring = false).assertIsNotEnabled()
        assertEquals(1, accountDeletes.get())
    }

    private fun render() {
        mounted = true
        compose.setContent {
            if (visible.value) SavelyTheme("light") { SettingsScreen(api, session, "light", {}, { signedOut.set(true) }, {}) }
        }
    }

    private fun openSecurity() {
        render()
        compose.onNodeWithText("Sicurezza", substring = false).performClick()
        compose.waitForIdle()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("revoke-session-$OTHER_SID") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun sessionConfirm() = compose.onNode(hasText("Conferma", substring = false) and hasAnyAncestor(isDialog()))

    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
            "/rest/v1/profiles" -> json("""[{"full_name":"Account fixture","default_currency":"EUR"}]""")
            "/auth/v1/user" -> if (request.method == "PUT") {
                passwordBody.set(request.body.readUtf8()); passwordWrites.incrementAndGet()
                json("""{"id":"$USER","email":"account@example.test"}""")
            } else json("""{"factors":[]}""")
            "/rest/v1/rpc/get_active_sessions" -> json("""[{"id":"$ACTIVITY_ID","session_id":"$SID","user_agent":"Native fixture","created_at":"2026-10-11T10:00:00Z"},{"id":"$SID","session_id":null,"user_agent":"Legacy fixture","created_at":"2026-10-10T10:00:00Z"},{"id":"$OTHER_ACTIVITY_ID","session_id":"$OTHER_SID","user_agent":"Another native fixture","created_at":"2026-10-09T10:00:00Z"}]""")
            "/rest/v1/rpc/delete_session" -> {
                val body = JSONObject(request.body.readUtf8())
                val id = body.optString("p_session_id")
                if (request.method != "POST" || request.getHeader("Authorization") != "Bearer ${session.accessToken}" ||
                    body.keys().asSequence().toSet() != setOf("p_session_id") || id !in setOf(SID, OTHER_SID)) {
                    json("{}", 400)
                } else {
                    revokedId.set(id); revocations.incrementAndGet(); revocationEntered.countDown()
                    if (holdRevocation.get()) check(releaseRevocation.await(15, TimeUnit.SECONDS))
                    json("null", revocationStatus.get())
                }
            }
            "/auth/v1/logout" -> if (request.method == "POST" && request.requestUrl?.queryParameter("scope") == "others" &&
                request.getHeader("Authorization") == "Bearer ${session.accessToken}" && request.body.readUtf8() == "{}") {
                otherLogouts.incrementAndGet()
                val status = otherLogoutStatus.get()
                json(if (status == 204) "" else "{}", status)
            } else { localLogouts.incrementAndGet(); json("{}", 400) }
            "/functions/v1/delete-account" -> if (request.method == "POST" &&
                request.getHeader("Authorization") == "Bearer ${session.accessToken}" && request.body.readUtf8() == "{}") {
                accountDeletes.incrementAndGet()
                json("""{"error":"fixture private failure"}""")
            } else json("{}", 400)
            else -> json("{}", 404)
        }
    }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    companion object {
        private const val USER = "10000000-0000-4000-8000-000000000010"
        private const val SID = "20000000-0000-4000-8000-000000000010"
        private const val ACTIVITY_ID = "30000000-0000-4000-8000-000000000010"
        private const val OTHER_SID = "20000000-0000-4000-8000-000000000011"
        private const val REPLACEMENT_SID = "20000000-0000-4000-8000-000000000012"
        private const val OTHER_ACTIVITY_ID = "30000000-0000-4000-8000-000000000011"
    }
}
