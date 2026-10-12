package cc.savely.android.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.runBlocking
import cc.savely.android.data.*
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
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic MFA removal over guarded localhost TLS; no real factor, credential or backend is used. */
@RunWith(AndroidJUnit4::class)
class SettingsMfaTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private lateinit var store: MemorySessionStore
    private lateinit var session: Session
    private val verifyStatus = AtomicInteger(200)
    private val factorPresent = AtomicBoolean(true)
    private val pendingPresent = AtomicBoolean(false)
    private val otherFactorsPresent = AtomicBoolean(false)
    private val pendingDeleteStatus = AtomicInteger(200)
    private val enrollments = AtomicInteger()
    private val pendingDeletionAttempts = AtomicInteger()
    private val holdVerification = AtomicBoolean(false)
    private val verifyEntered = CountDownLatch(1)
    private val releaseVerification = CountDownLatch(1)
    private val challenges = AtomicInteger()
    private val verifications = AtomicInteger()
    private val deletions = AtomicInteger()
    private val unexpectedRequests = AtomicInteger()
    private val nonLocalRequests = AtomicInteger()
    private val signedOut = AtomicBoolean(false)
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val enrollmentQr = AtomicReference(VALID_QR)

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Savely MFA removal fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(certificates.sslSocketFactory(), false); dispatcher = fixture(); start() }
        val origin = server.url("/")
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val url = chain.request().url
                if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port) {
                    nonLocalRequests.incrementAndGet()
                    throw IOException("MFA fixture refuses a non-local origin")
                }
                chain.proceed(chain.request())
            }.build()
        session = Session(token("initial"), "synthetic-refresh", System.currentTimeMillis() / 1000 + 3600, USER, EMAIL)
        store = MemorySessionStore(session)
        api = SupabaseApi(BackendConfig(origin.toString(), "mfa-public-key", origin.toString()), store, client)
    }

    @After fun teardown() {
        releaseVerification.countDown()
        api.close()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun wrongCodeDoesNotDeleteFactorOrInvalidateSessionAndClearsTheCode() {
        verifyStatus.set(400)
        openRemoval()
        code().performTextInput("12345")
        confirm().assertIsNotEnabled()
        assertEquals(0, challenges.get())
        code().performTextInput("6")
        confirm().assertIsEnabled().performClick()
        awaitDialogError("Codice di verifica non corretto o scaduto.")
        assertRejectedAttemptPreservesSession()
    }

    @Test fun serverFailureDoesNotDeleteFactorRetryOrInvalidateSessionAndClearsTheCode() {
        verifyStatus.set(503)
        openRemoval()
        code().performTextInput("123456")
        confirm().performClick()
        awaitDialogError("Servizio temporaneamente non disponibile. Riprova.")
        assertRejectedAttemptPreservesSession()
    }

    @Test fun freshVerificationPrecedesExactlyOneDeletionAndKeepsTheElevatedSession() {
        holdVerification.set(true)
        openRemoval()
        code().performTextInput("123456")
        // Invoke the same button twice before recomposition; the synchronous busy guard blocks the second submit.
        val click = confirm().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { click(); click() }
        compose.waitUntil(15_000) { verifyEntered.count == 0L }
        confirm().assertIsNotEnabled()
        code().assertIsNotEnabled()
        compose.onNode(hasText("Annulla", substring = false) and hasAnyAncestor(isDialog())).assertIsNotEnabled()
        assertEquals(1, challenges.get())
        assertEquals(1, verifications.get())
        assertEquals(0, deletions.get())
        releaseVerification.countDown()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Autenticatore rimosso.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Rimuovere l’autenticatore?").assertDoesNotExist()
        compose.onNodeWithText("Rimuovi autenticatore").assertDoesNotExist()
        assertEquals(1, challenges.get())
        assertEquals(1, verifications.get())
        assertEquals(1, deletions.get())
        assertEquals(token("verified"), store.load()?.accessToken)
        assertEquals("synthetic-refreshed", store.load()?.refreshToken)
        assertEquals(SESSION_ID, store.load()?.authSessionId())
        assertEquals("aal2", store.load()?.let(::assuranceLevel))
        val mutationPaths = requests.filter { it.method in setOf("POST", "DELETE") && it.requestUrl?.encodedPath?.startsWith("/auth/v1/factors/") == true }
        assertEquals(listOf("/auth/v1/factors/$FACTOR/challenge", "/auth/v1/factors/$FACTOR/verify", "/auth/v1/factors/$FACTOR"),
            mutationPaths.map { it.requestUrl?.encodedPath })
        assertEquals("Bearer ${token("verified")}", mutationPaths.last().getHeader("Authorization"))
        assertNoLogoutOrForeignRequests()
    }

    @Test fun unfinishedEnrollmentSurvivesLeavingSecurityAndOnlyConfirmedRemovalDeletesItsFactor() {
        factorPresent.set(false)
        openSecurity()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Configura autenticatore") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Configura autenticatore").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(PENDING_SECRET).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("totp-enrollment-qr").performScrollTo().assertIsDisplayed()
        assertEquals(1, enrollments.get())
        assertEquals(0, pendingDeletionAttempts.get())
        // Another authenticator already exists by the next server read; removing our pending factor must leave it intact.
        factorPresent.set(true)
        otherFactorsPresent.set(true)
        compose.onNodeWithText("Account", substring = false).performClick()
        compose.onNodeWithText(PENDING_SECRET).assertDoesNotExist()
        compose.onNodeWithText("Sicurezza", substring = false).performClick()
        awaitPendingFactor()
        compose.onNodeWithText(PENDING_SECRET).assertDoesNotExist()
        compose.onNodeWithText("Codice a 6 cifre", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Configura autenticatore").assertDoesNotExist()
        compose.onNodeWithText("Rimuovi autenticatore").assertExists()
        compose.onNodeWithTag("remove-pending-factor-$OTHER_PENDING").assertExists()
        assertEquals(0, pendingDeletionAttempts.get())
        openPendingRemoval()
        compose.onNodeWithText("Codice per rimuovere l’autenticatore").assertDoesNotExist()
        compose.onNode(hasText("Annulla", substring = false) and hasAnyAncestor(isDialog())).performClick()
        assertTrue(pendingPresent.get())
        assertEquals(0, pendingDeletionAttempts.get())
        openPendingRemoval()
        val click = confirm().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { click(); click() }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Configurazione incompleta rimossa.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("remove-pending-factor-$PENDING").assertDoesNotExist()
        compose.onNodeWithTag("remove-pending-factor-$OTHER_PENDING").assertExists()
        compose.onNodeWithText("Rimuovi autenticatore").assertExists()
        assertEquals(1, pendingDeletionAttempts.get())
        assertFalse(pendingPresent.get())
        assertTrue(factorPresent.get())
        assertTrue(otherFactorsPresent.get())
        assertEquals(listOf("/auth/v1/factors/$PENDING"), requests.filter { it.method == "DELETE" }.map { it.requestUrl?.encodedPath })
        assertEquals(0, challenges.get())
        assertEquals(0, verifications.get())
        assertEquals(0, deletions.get())
        assertEquals(session, store.load())
        assertNoLogoutOrForeignRequests()
    }

    @Test fun qrGuardAllowsOnlyBoundedLocalGeometryAndRejectsExternalResources() {
        assertArrayEquals(VALID_QR.toByteArray(), boundedTotpSvg(VALID_QR))
        for ((index, unsafe) in listOf(
            "<!DOCTYPE svg [<!ENTITY payload SYSTEM 'https://invalid.example/secret'>]><svg>&payload;</svg>",
            """<svg xmlns="http://www.w3.org/2000/svg"><image href="https://invalid.example/secret"/></svg>""",
            """<svg xmlns="http://www.w3.org/2000/svg"><path href="https://invalid.example/secret" d="M0 0h1v1z"/></svg>""",
            "<svg><script>unsafe()</script></svg>",
            "<svg><rect width='1' height='1' fill='url(https://invalid.example/secret)'/></svg>",
            "<svg><foreignObject/></svg>", "https://invalid.example/qr.svg", "<svg>" + " ".repeat(262_144) + "</svg>",
            "<svg>" + "\u2003".repeat(90_000) + "</svg>",
            "<svg><g>".repeat(9) + "</g></svg>".repeat(9), "<svg><path d='M0 0'/></svg><svg/>",
            "<svg/><svg/>", "<svg/><rect width='1' height='1'/>"
        ).withIndex()) assertNull("Unsafe SVG fixture #$index", boundedTotpSvg(unsafe))
        assertEquals(0, server.requestCount)
    }

    @Test fun qrDecodesFromLocalBytesAtBoundedSizeWithoutAnyHttp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = ImageLoader.Builder(context).okHttpClient(client).components { add(SvgDecoder.Factory()) }.build()
        try {
            val hugeIntrinsic = VALID_QR.replace("width=\"256\"", "width=\"99999999\"").replace("height=\"256\"", "height=\"99999999\"")
            val bytes = requireNotNull(boundedTotpSvg(hugeIntrinsic))
            val result = loader.execute(ImageRequest.Builder(context).data(bytes).size(256)
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build())
            assertTrue(result is SuccessResult)
            val drawable = (result as SuccessResult).drawable
            assertTrue(drawable.intrinsicWidth in 1..256)
            assertTrue(drawable.intrinsicHeight in 1..256)
            assertEquals(0, server.requestCount)
            assertEquals(0, nonLocalRequests.get())
        } finally { loader.shutdown() }
    }

    @Test fun rejectedQrKeepsManualKeyAndDoesNotVerifyOrDeleteAutomatically() {
        factorPresent.set(false)
        enrollmentQr.set("<svg><script>unsafe()</script></svg>")
        openSecurity()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Configura autenticatore") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Configura autenticatore").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(PENDING_SECRET).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(PENDING_SECRET).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("totp-enrollment-qr").assertDoesNotExist()
        compose.onNodeWithText("Apri autenticatore").assertExists()
        assertEquals(1, enrollments.get())
        assertEquals(0, challenges.get())
        assertEquals(0, verifications.get())
        assertEquals(0, pendingDeletionAttempts.get())
        assertEquals(0, deletions.get())
        assertEquals(session, store.load())
        assertNoLogoutOrForeignRequests()
    }

    @Test fun pendingRemovalServerFailureKeepsTheFactorAndSessionWithoutRetry() {
        pendingPresent.set(true)
        otherFactorsPresent.set(true)
        pendingDeleteStatus.set(503)
        openSecurity()
        awaitPendingFactor()
        openPendingRemoval()
        confirm().assertIsEnabled().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("Servizio temporaneamente non disponibile. Riprova.") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Annullare la configurazione?").assertExists()
        confirm().assertIsEnabled()
        assertEquals(1, pendingDeletionAttempts.get())
        assertTrue(pendingPresent.get())
        assertTrue(factorPresent.get())
        assertTrue(otherFactorsPresent.get())
        assertEquals(0, challenges.get())
        assertEquals(0, verifications.get())
        assertEquals(0, deletions.get())
        assertEquals(session, store.load())
        assertNoLogoutOrForeignRequests()
    }

    private fun openSecurity() {
        compose.setContent { SavelyTheme("light") { SettingsScreen(api, session, "light", {}, { signedOut.set(true) }, {}) } }
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Salva profilo") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Sicurezza", substring = false).performClick()
    }

    private fun awaitPendingFactor() {
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("remove-pending-factor-$PENDING") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openPendingRemoval() {
        compose.onNodeWithTag("remove-pending-factor-$PENDING").performScrollTo().performClick()
        compose.onNodeWithText("Annullare la configurazione?").assertExists()
    }

    private fun openRemoval() {
        openSecurity()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Rimuovi autenticatore") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Rimuovi autenticatore", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Rimuovere l’autenticatore?").assertExists()
        confirm().assertIsNotEnabled()
    }

    private fun code() = compose.onNodeWithText("Codice per rimuovere l’autenticatore", substring = false)
    private fun confirm() = compose.onNode(hasText("Conferma", substring = false) and hasAnyAncestor(isDialog()))
    private fun awaitDialogError(message: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText(message) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Rimuovere l’autenticatore?").assertExists()
        code().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        confirm().assertIsNotEnabled()
    }

    private fun assertRejectedAttemptPreservesSession() {
        assertEquals(1, challenges.get())
        assertEquals(1, verifications.get())
        assertEquals(0, deletions.get())
        assertTrue(factorPresent.get())
        assertEquals(session, store.load())
        assertNoLogoutOrForeignRequests()
    }

    private fun assertNoLogoutOrForeignRequests() {
        assertFalse(signedOut.get())
        assertNull(api.sessionInvalidation.value)
        assertEquals(0, unexpectedRequests.get())
        assertEquals(0, nonLocalRequests.get())
        assertTrue(requests.none { it.requestUrl?.encodedPath in setOf("/auth/v1/logout", "/auth/v1/token") })
    }

    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add(request)
            if (request.getHeader("apikey") != "mfa-public-key" || request.getHeader("Authorization") != "Bearer ${store.load()?.accessToken}") return unexpected()
            return when (request.requestUrl?.encodedPath) {
                "/rest/v1/profiles" -> if (request.method == "GET" && request.requestUrl?.queryParameter("user_id") == "eq.$USER")
                    json("""[{"full_name":"MFA fixture","default_currency":"EUR"}]""") else unexpected()
                "/auth/v1/user" -> if (request.method == "GET") {
                    val factors = JSONArray()
                    fun factor(id: String, type: String, status: String) = JSONObject().put("id", id).put("factor_type", type).put("status", status)
                    if (factorPresent.get()) factors.put(factor(FACTOR, "totp", "verified"))
                    if (pendingPresent.get()) factors.put(factor(PENDING, "totp", "unverified"))
                    if (otherFactorsPresent.get()) {
                        factors.put(factor(OTHER_PENDING, "totp", "unverified"))
                        factors.put(factor(PHONE, "phone", "verified"))
                    }
                    json(JSONObject().put("factors", factors).toString())
                } else unexpected()
                "/auth/v1/factors" -> {
                    val body = runCatching { JSONObject(request.body.readUtf8()) }.getOrNull()
                    if (request.method != "POST" || body == null || body.keys().asSequence().toSet() != setOf("factor_type", "friendly_name") ||
                        body.optString("factor_type") != "totp" || body.optString("friendly_name") != "Savely Android" || pendingPresent.get()) return unexpected()
                    enrollments.incrementAndGet()
                    pendingPresent.set(true)
                    json(JSONObject().put("id", PENDING).put("type", "totp")
                        .put("totp", JSONObject().put("secret", PENDING_SECRET).put("uri", "otpauth://totp/Synthetic?secret=$PENDING_SECRET")
                            .put("qr_code", enrollmentQr.get())).toString())
                }
                "/rest/v1/rpc/get_active_sessions" -> if (request.method == "POST" && request.body.readUtf8() == "{}") json("[]") else unexpected()
                "/auth/v1/factors/$FACTOR/challenge" -> if (request.method == "POST" && request.body.readUtf8() == "{}") {
                    challenges.incrementAndGet()
                    json("""{"id":"synthetic-challenge"}""")
                } else unexpected()
                "/auth/v1/factors/$FACTOR/verify" -> {
                    val body = runCatching { JSONObject(request.body.readUtf8()) }.getOrNull()
                    if (request.method != "POST" || body == null || body.keys().asSequence().toSet() != setOf("challenge_id", "code") ||
                        body.optString("challenge_id") != "synthetic-challenge" || body.optString("code") != "123456") return unexpected()
                    verifications.incrementAndGet()
                    verifyEntered.countDown()
                    if (holdVerification.get() && !releaseVerification.await(10, TimeUnit.SECONDS)) return json("{}", 503)
                    when (verifyStatus.get()) {
                        400 -> json("""{"code":"mfa_verification_failed","message":"private fixture diagnostic"}""", 400)
                        503 -> json("""{"message":"private fixture diagnostic"}""", 503)
                        else -> json(JSONObject().put("access_token", token("verified")).put("refresh_token", "synthetic-refreshed")
                            .put("expires_in", 3600).put("user", JSONObject().put("id", USER).put("email", EMAIL)).toString())
                    }
                }
                "/auth/v1/factors/$FACTOR" -> if (request.method == "DELETE" && request.getHeader("Authorization") == "Bearer ${token("verified")}") {
                    deletions.incrementAndGet()
                    factorPresent.set(false)
                    json("{}")
                } else unexpected()
                "/auth/v1/factors/$PENDING" -> if (request.method == "DELETE" && pendingPresent.get()) {
                    pendingDeletionAttempts.incrementAndGet()
                    if (pendingDeleteStatus.get() == 503) json("{}", 503)
                    else { pendingPresent.set(false); json("{}") }
                } else unexpected()
                else -> unexpected()
            }
        }
    }

    private fun unexpected(): MockResponse { unexpectedRequests.incrementAndGet(); return json("{}", 403) }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun token(revision: String): String {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            JSONObject().put("aal", "aal2").put("session_id", SESSION_ID).put("fixture", revision).toString().toByteArray(Charsets.UTF_8))
        return "header.$payload.signature"
    }
    private companion object {
        const val USER = "10000000-0000-4000-8000-000000000071"
        const val SESSION_ID = "20000000-0000-4000-8000-000000000071"
        const val FACTOR = "30000000-0000-4000-8000-000000000071"
        const val PENDING = "30000000-0000-4000-8000-000000000072"
        const val OTHER_PENDING = "30000000-0000-4000-8000-000000000073"
        const val PHONE = "30000000-0000-4000-8000-000000000074"
        const val PENDING_SECRET = "SYNTHETICMFASECRET"
        const val EMAIL = "mfa-removal@example.test"
        const val VALID_QR = """<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256"><rect width="256" height="256" fill="#fff"/><path stroke="#000" stroke-width="8" d="M8 8h64v64h-64z"/></svg>"""
    }
}
