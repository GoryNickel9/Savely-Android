package cc.savely.android.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic Admin UI against a guarded local TLS server; never contacts production or deletes an account. */
@RunWith(AndroidJUnit4::class)
class AdminScreenTest {
    @get:Rule val compose = createComposeRule()
    private val visible = mutableStateOf(true)
    private var mounted = false
    private lateinit var languageStorage: SharedPreferences
    private lateinit var languagePreferences: LanguagePreferences
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
    private val resetRequests = AtomicInteger()
    private val changeOwnerOnTargetRead = AtomicBoolean(false)
    private val revokeRoleOnTargetRead = AtomicBoolean(false)
    private val revokeRoleOnUserListRead = AtomicBoolean(false)
    private val permissionWrites = AtomicInteger()
    private val targetProfileReads = AtomicInteger()
    private val permissionStatus = AtomicInteger(200)
    private val permissionBody = AtomicReference("")
    private val listedPermissions = AtomicReference(JSONObject().put("poker", true).put("future_module", false))
    private val holdPermissionWrite = AtomicBoolean(false)
    private val permissionWriteEntered = CountDownLatch(1)
    private val releasePermissionWrite = CountDownLatch(1)
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        languageStorage = context.getSharedPreferences("admin-dialog-fixture", Context.MODE_PRIVATE)
        languageStorage.edit().clear().commit()
        languagePreferences = LanguagePreferences(languageStorage).also { it.write(AppLanguage.ITALIAN) }
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
        releasePermissionWrite.countDown()
        try {
            if (mounted) {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
            }
        } finally {
            api.close()
            server.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            languageStorage.edit().clear().commit()
        }
    }

    @Test fun revokedAdminProfileClearsCachedUsersAndRegistrationControlsWithoutLogout() {
        renderAndAwaitLoaded()
        compose.onNodeWithText(OTHER_EMAIL).assertExists()
        compose.onNodeWithText("Registrazioni abilitate").assertExists()
        adminAllowed.set(false)

        compose.onNodeWithText("Aggiorna utenti").performScrollTo().performClick()
        awaitError("Sono necessari i permessi di amministratore.")
        assertNoAdminDataOrDialogs()
        assertEquals(3, profileReads.get())
        assertEquals(1, userListReads.get()) // The denied profile stops before calling the web Admin API.
        assertEquals(1, registrationReads.get())
        assertEquals(0, deleteAttempts.get())
        assertEquals(5, server.requestCount)
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
        assertEquals(6, server.requestCount)

        deleteStatus.set(403)
        compose.onNodeWithText("Elimina definitivamente").performClick()
        awaitError("Non hai il permesso per questa operazione.")
        assertNoAdminDataOrDialogs()
        assertEquals(2, deleteAttempts.get()) // Both fixture attempts were denied; no account was removed.
        assertEquals(1, userListReads.get())
        assertEquals(1, registrationReads.get())
        assertEquals(8, server.requestCount)
        assertSessionAndLocalRequestsPreserved()
    }

    private fun render() {
        mounted = true
        compose.setContent {
            if (visible.value) LocalizationProvider(languagePreferences) { SavelyTheme("light") { AdminScreen(api) } }
        }
    }

    private fun renderAndAwaitLoaded() {
        render()
        val registrations = if (languagePreferences.read() == AppLanguage.ENGLISH) "Registration enabled" else "Registrazioni abilitate"
        val refresh = if (languagePreferences.read() == AppLanguage.ENGLISH) "Refresh users" else "Aggiorna utenti"
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(registrations).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText(refresh) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun passwordResetNeedsExplicitConfirmationAndCancelMakesNoRequest() {
        renderAndAwaitLoaded()
        compose.onNodeWithText("Gestisci").performScrollTo().performClick()
        compose.onNodeWithText("Invia recupero password").performScrollTo().performClick()
        compose.onNodeWithText("Inviare il recupero password?").assertIsDisplayed()
        assertEquals(0, resetRequests.get())
        compose.onNodeWithText("Annulla", substring = false).performClick()
        assertEquals(0, resetRequests.get())
        compose.onNodeWithText("Gestisci").performScrollTo().performClick()
        compose.onNodeWithText("Invia recupero password").performScrollTo().performClick()
        compose.onNodeWithText("Invia email", substring = false).assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Email di recupero inviata.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, resetRequests.get())
        val reset = requests.single { it.requestUrl?.encodedPath == "/api/reset-password" }
        assertEquals("POST", reset.method)
        assertEquals(OTHER_EMAIL, JSONObject(reset.body.clone().readUtf8()).getString("email"))
        assertNull(reset.getHeader("Authorization"))
        assertNull(reset.getHeader("apikey"))
        assertEquals(session, store.load())
        assertEquals(0, deleteAttempts.get())
        assertEquals(0, unexpectedRequests.get())
        assertEquals(0, nonLocalRequests.get())
    }

    @Test fun accountChangeWhileReadingTargetProfilePreventsPermissionWrite() {
        renderAndAwaitLoaded()
        compose.onNodeWithText("Gestisci").performScrollTo().performClick()
        changeOwnerOnTargetRead.set(true)
        compose.onNodeWithText("Salva permessi").performClick()
        awaitError("La sessione è cambiata. Aggiorna la schermata prima di continuare.")
        assertNoAdminDataOrDialogs()
        assertEquals(0, permissionWrites.get())
        assertEquals(6, server.requestCount)
        assertEquals(OTHER_ID, store.load()?.userId)
        assertNull(api.sessionInvalidation.value)
        assertEquals(0, unexpectedRequests.get())
        assertEquals(0, nonLocalRequests.get())
    }

    @Test fun italianPermissionDialogCancelWritesNothingAndSavePreservesFreshUnknownKeys() {
        permissionDialogCancelAndSave(AppLanguage.ITALIAN)
    }

    @Test fun englishPermissionDialogCancelWritesNothingAndSavePreservesFreshUnknownKeys() {
        permissionDialogCancelAndSave(AppLanguage.ENGLISH)
    }

    private fun permissionDialogCancelAndSave(language: AppLanguage) {
        languagePreferences.write(language)
        renderAndAwaitLoaded()
        val english = language == AppLanguage.ENGLISH
        val manage = if (english) "Manage" else "Gestisci"
        val save = if (english) "Save permissions" else "Salva permessi"
        val close = if (english) "Close" else "Chiudi"
        val couple = if (english) "Couple" else "Coppia"
        compose.onNodeWithText(manage).performScrollTo().performClick()
        compose.onNode(hasText(OTHER_EMAIL) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        for (label in listOf("Admin", "Poker", "Fire", if (english) "Smoking" else "Fumo", "Tcg", if (english) "Library" else "Libreria", couple)) {
            compose.onNode(hasText(label) and hasAnyAncestor(isDialog())).assertExists()
        }
        permissionCheckboxes().assertCountEquals(7)
        permissionCheckboxes()[1].assertIsOn()
        permissionCheckboxes()[6].performScrollTo().assertIsOff().performClick()
        permissionCheckboxes()[6].assertIsOn()
        compose.onNodeWithText(close, substring = false).performClick()
        assertEquals(0, targetProfileReads.get())
        assertEquals(0, permissionWrites.get())

        compose.onNodeWithText(manage).performScrollTo().performClick()
        permissionCheckboxes()[6].performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText(save, substring = false).performClick()
        awaitError(if (english) "Permissions updated." else "Permessi aggiornati.")
        compose.onNodeWithText(save, substring = false).assertDoesNotExist()
        assertEquals(1, targetProfileReads.get())
        assertEquals(1, permissionWrites.get())
        assertSavedPermissions(couple = true)
        assertSessionAndLocalRequestsPreserved()
    }

    @Test fun permissionSaveServerFailureKeepsDialogEditsAndSessionUntilExplicitRetry() {
        renderAndAwaitLoaded()
        compose.onNodeWithText("Gestisci").performScrollTo().performClick()
        permissionCheckboxes()[6].performScrollTo().performClick()
        permissionStatus.set(503)
        compose.onNodeWithText("Salva permessi", substring = false).performClick()
        awaitError("Servizio temporaneamente non disponibile. Riprova.")
        compose.onNodeWithText("Salva permessi", substring = false).assertIsEnabled()
        permissionCheckboxes()[6].performScrollTo().assertIsOn()
        assertEquals(1, permissionWrites.get())
        assertEquals(1, targetProfileReads.get())
        assertSessionAndLocalRequestsPreserved()

        permissionStatus.set(200)
        compose.onNodeWithText("Salva permessi", substring = false).performClick()
        awaitError("Permessi aggiornati.")
        assertEquals(2, permissionWrites.get())
        assertEquals(2, targetProfileReads.get())
        assertSavedPermissions(couple = true)
        assertSessionAndLocalRequestsPreserved()
    }

    @Test fun lostAdminRoleBeforePermissionSaveClearsDialogWithoutReadingOrWritingTheTarget() {
        renderAndAwaitLoaded()
        compose.onNodeWithText("Gestisci").performScrollTo().performClick()
        permissionCheckboxes()[6].performScrollTo().performClick()
        adminAllowed.set(false)
        compose.onNodeWithText("Salva permessi", substring = false).performClick()
        awaitError("Sono necessari i permessi di amministratore.")
        assertNoAdminDataOrDialogs()
        assertEquals(3, profileReads.get())
        assertEquals(0, targetProfileReads.get())
        assertEquals(0, permissionWrites.get())
        assertEquals(5, server.requestCount)
        assertSessionAndLocalRequestsPreserved()
    }

    @Test fun lostAdminRoleDuringEmptyTargetReadClearsDialogBeforePatch() {
        renderAndAwaitLoaded()
        compose.onNodeWithText("Gestisci").performScrollTo().performClick()
        revokeRoleOnTargetRead.set(true)
        compose.onNodeWithText("Salva permessi", substring = false).performClick()
        awaitError("Sono necessari i permessi di amministratore.")
        assertNoAdminDataOrDialogs()
        assertEquals(4, profileReads.get()) // Two initial checks, before target read, and after role changes during that read.
        assertEquals(1, targetProfileReads.get())
        assertEquals(0, permissionWrites.get())
        assertEquals(7, server.requestCount)
        assertSessionAndLocalRequestsPreserved()
    }

    @Test fun lostAdminRoleDuringUserListReadPreventsTheFirstAdminDataPublication() {
        revokeRoleOnUserListRead.set(true)
        render()
        awaitError("Sono necessari i permessi di amministratore.")
        assertNoAdminDataOrDialogs()
        assertEquals(2, profileReads.get())
        assertEquals(1, userListReads.get())
        assertEquals(1, registrationReads.get())
        assertEquals(0, targetProfileReads.get())
        assertEquals(0, permissionWrites.get())
        assertEquals(0, deleteAttempts.get())
        assertEquals(4, server.requestCount)
        assertSessionAndLocalRequestsPreserved()
    }

    @Test fun permissionWriteBusyBlocksCheckboxesCloseAndRepeatedSaveBeforeResponse() {
        holdPermissionWrite.set(true)
        renderAndAwaitLoaded()
        compose.onNodeWithText("Gestisci").performScrollTo().performClick()
        permissionCheckboxes()[6].performScrollTo().performClick()
        val click = compose.onNodeWithText("Salva permessi", substring = false).fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { click(); click() }
        compose.waitUntil(15_000) { permissionWriteEntered.count == 0L }
        compose.onNodeWithText("Salva permessi", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("Chiudi", substring = false).assertIsNotEnabled()
        permissionCheckboxes().assertCountEquals(7)
        for (index in 0..6) permissionCheckboxes()[index].assertIsNotEnabled()
        compose.onNodeWithText("Invia recupero password").assertIsNotEnabled()
        compose.onNodeWithText("Elimina utente").assertIsNotEnabled()
        compose.onNodeWithText("Aggiorna utenti").assertIsNotEnabled()
        assertEquals(1, targetProfileReads.get())
        assertEquals(1, permissionWrites.get())
        assertEquals(session, store.load())
        releasePermissionWrite.countDown()
        awaitError("Permessi aggiornati.")
        assertEquals(1, permissionWrites.get())
        assertSavedPermissions(couple = true)
        assertSessionAndLocalRequestsPreserved()
    }

    @Test fun stalePermissionDialogCannotSaveAfterAnotherLoginOfTheSameUser() {
        renderAndAwaitLoaded()
        compose.onNodeWithText("Gestisci").performScrollTo().performClick()
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"aal":"aal2","session_id":"$REPLACEMENT_SESSION_ID"}""".toByteArray(Charsets.UTF_8))
        val replacement = session.copy(accessToken = "header.$payload.signature", refreshToken = "replacement-refresh")
        store.save(replacement)
        val readsBeforeClick = server.requestCount
        compose.onNodeWithText("Salva permessi", substring = false).performClick()
        awaitError("La sessione è cambiata. Aggiorna la schermata prima di continuare.")
        assertNoAdminDataOrDialogs()
        assertEquals(readsBeforeClick, server.requestCount)
        assertEquals(0, targetProfileReads.get())
        assertEquals(0, permissionWrites.get())
        assertEquals(replacement, store.load())
        assertNull(api.sessionInvalidation.value)
        assertEquals(0, unexpectedRequests.get())
        assertEquals(0, nonLocalRequests.get())
    }

    private fun permissionCheckboxes() = compose.onAllNodes(isToggleable() and hasAnyAncestor(isDialog()))

    private fun assertSavedPermissions(couple: Boolean) {
        val body = JSONObject(permissionBody.get())
        assertEquals(setOf("permissions"), body.keys().asSequence().toSet())
        val permissions = body.getJSONObject("permissions")
        assertTrue(permissions.getBoolean("poker"))
        assertEquals(couple, permissions.getBoolean("couple_expenses"))
        for (key in listOf("admin", "fire", "fumo", "tcg", "libreria")) assertFalse(permissions.getBoolean(key))
        assertTrue(permissions.getBoolean("future_module")) // Fresh profile value, not stale get-users cache.
        assertEquals("fresh", permissions.getJSONObject("external_object").getString("mode"))
        assertEquals("Opaque {{value}}", permissions.getString("external_text"))
        assertTrue(permissions.has("external_null") && permissions.isNull("external_null"))
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
                } else if (request.method == "GET" && request.requestUrl?.queryParameter("user_id") == "eq.$OTHER_ID" &&
                    request.requestUrl?.queryParameter("select") == "id,permissions") {
                    targetProfileReads.incrementAndGet()
                    if (revokeRoleOnTargetRead.get()) { adminAllowed.set(false); json("[]") }
                    else if (!adminAllowed.get()) json("[]")
                    else {
                        if (changeOwnerOnTargetRead.get()) {
                            store.save(session.copy(userId = OTHER_ID, email = OTHER_EMAIL,
                                accessToken = "synthetic-replacement-token", refreshToken = "synthetic-replacement-refresh"))
                        }
                        json("""[{"id":"$PROFILE_ID","user_id":"$OTHER_ID","permissions":{"poker":true,"future_module":true,"external_object":{"mode":"fresh"},"external_text":"Opaque {{value}}","external_null":null}}]""")
                    }
                } else if (request.method == "PATCH") {
                    val body = runCatching { JSONObject(request.body.clone().readUtf8()) }.getOrNull()
                    if (request.requestUrl?.queryParameter("id") != "eq.$PROFILE_ID" ||
                        request.requestUrl?.queryParameter("user_id") != "eq.$OTHER_ID" ||
                        request.getHeader("Authorization") != "Bearer ${session.accessToken}" ||
                        body == null || body.keys().asSequence().toSet() != setOf("permissions") || body.optJSONObject("permissions") == null) unexpected()
                    else {
                        permissionBody.set(body.toString()); permissionWrites.incrementAndGet(); permissionWriteEntered.countDown()
                        if (holdPermissionWrite.get()) check(releasePermissionWrite.await(15, TimeUnit.SECONDS))
                        val status = permissionStatus.get()
                        if (status != 200) json("{}", status)
                        else {
                            listedPermissions.set(body.getJSONObject("permissions"))
                            json("""[{"id":"$PROFILE_ID","user_id":"$OTHER_ID","permissions":${listedPermissions.get()}}]""")
                        }
                    }
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
                            if (revokeRoleOnUserListRead.get()) adminAllowed.set(false)
                            json("""{"users":[{"user_id":"$OTHER_ID","email":"$OTHER_EMAIL","full_name":"Synthetic account","permissions":${listedPermissions.get()}}]}""")
                        }
                        "delete-user" -> if (targetUser == OTHER_ID) {
                            deleteAttempts.incrementAndGet()
                            json("""{"error":"private fixture diagnostic"}""", deleteStatus.get())
                        } else unexpected()
                        else -> unexpected()
                    }
                }
                "/api/reset-password" -> if (request.method == "POST" &&
                    runCatching { JSONObject(request.body.clone().readUtf8()).getString("email") }.getOrNull() == OTHER_EMAIL) {
                    resetRequests.incrementAndGet(); json("{}")
                } else unexpected()
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
        const val REPLACEMENT_SESSION_ID = "20000000-0000-4000-8000-000000000043"
        const val PROFILE_ID = "30000000-0000-4000-8000-000000000042"
        const val OTHER_EMAIL = "other-admin-fixture@example.test"
    }
}
