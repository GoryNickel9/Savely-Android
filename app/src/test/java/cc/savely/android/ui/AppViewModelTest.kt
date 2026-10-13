package cc.savely.android.ui

import androidx.lifecycle.ViewModelStore
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.ApiError
import cc.savely.android.data.ApiException
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SessionStore
import cc.savely.android.data.SupabaseApi
import cc.savely.android.data.authSessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Tests the UI account boundary with the real HTTP client, not a mocked authentication layer. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private lateinit var server: MockWebServer
    private lateinit var store: MemorySessionStore
    private lateinit var api: SupabaseApi
    private val viewModels = ViewModelStore()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        store = MemorySessionStore(liveSession())
        val origin = server.url("/").toString()
        api = SupabaseApi(BackendConfig(origin, "public-test-key", origin), store)
    }

    @After fun tearDown() {
        viewModels.clear()
        server.shutdown()
    }

    @Test fun normalRestoreLoadsCurrentAccountPermissions() = runTest {
        enqueueAcceptedAccount()
        val model = createViewModel()
        val state = awaitIdle(model)
        assertEquals(store.load(), state.session)
        assertEquals(true, state.permissions["poker"])
        assertEquals(true, state.permissions["fire"])
        assertEquals(false, state.permissions["admin"])
        assertEquals(false, state.permissions["tcg"])
        assertNull(state.mfaFactor)
        assertNull(state.error)
        assertEquals(2, server.requestCount)
        assertEquals("/auth/v1/user", takeRequest().requestUrl?.encodedPath)
        val profileRequest = takeRequest()
        assertEquals("/rest/v1/profiles", profileRequest.requestUrl?.encodedPath)
        assertEquals("eq.user-1", profileRequest.requestUrl?.queryParameter("user_id"))
        assertEquals("Bearer ${liveSession().accessToken}", profileRequest.getHeader("Authorization"))
    }

    @Test fun acceptedPermissionReloadRemovesDeniedRoutesWithoutReplacingTheSessionRevision() = runTest {
        enqueueAcceptedAccount()
        val model = createViewModel()
        val previous = awaitIdle(model)
        val revision = requireNotNull(previous.sessionRevision)
        assertTrue(canNavigate("poker", previous.permissions))
        assertTrue(canNavigate("fire", previous.permissions))
        repeat(2) { takeRequest() }
        server.enqueue(json("""{"factors":[]}"""))
        server.enqueue(json("""[{"user_id":"user-1","permissions":{"poker":false,"fire":false,"admin":false,"couple_expenses":true}}]"""))

        model.restore()
        val reloaded = awaitIdle(model)
        assertEquals(previous.session, reloaded.session)
        assertEquals(revision, reloaded.sessionRevision)
        assertEquals(revision, api.sessionOwnerRevision())
        assertFalse(canNavigate("poker", reloaded.permissions))
        assertFalse(canNavigate("fire", reloaded.permissions))
        assertFalse(canNavigate("admin", reloaded.permissions))
        assertTrue(canNavigate("couple", reloaded.permissions))
        assertNull(reloaded.mfaFactor)
        assertNull(reloaded.error)
        assertEquals(4, server.requestCount)
        assertEquals("/auth/v1/user", takeRequest().requestUrl?.encodedPath)
        assertEquals("eq.user-1", takeRequest().requestUrl?.queryParameter("user_id"))
    }

    @Test fun sameUserReplacementSessionPublishesItsRevisionAndRemovesOldPermissionsAtTheMfaGate() = runTest {
        enqueueAcceptedAccount()
        val model = createViewModel()
        val previous = awaitIdle(model)
        val revision = requireNotNull(previous.sessionRevision)
        repeat(2) { takeRequest() }
        val replacement = liveSession("22222222-2222-4222-8222-222222222222").copy(refreshToken = "replacement-refresh")
        store.save(replacement)
        server.enqueue(json("""{"factors":[{"id":"replacement-factor","factor_type":"totp","status":"verified"}]}"""))

        model.restore()
        val gated = awaitIdle(model)
        assertEquals(replacement, gated.session)
        assertNotEquals(revision, gated.sessionRevision)
        assertEquals(api.sessionOwnerRevision(replacement), gated.sessionRevision)
        assertEquals("replacement-factor", gated.mfaFactor)
        assertTrue(gated.permissions.isEmpty())
        assertFalse(canNavigate("poker", gated.permissions))
        assertFalse(canNavigate("fire", gated.permissions))
        assertNull(gated.error)
        assertEquals(replacement, store.load())
        assertNull(api.sessionInvalidation.value)
        assertEquals(3, server.requestCount) // No new profile, logout or data request before MFA.
        val factors = takeRequest()
        assertEquals("/auth/v1/user", factors.requestUrl?.encodedPath)
        assertEquals("Bearer ${replacement.accessToken}", factors.getHeader("Authorization"))
    }

    @Test fun repeatedLegacyLoginPublishesANewRevisionEvenWhenTheSessionPayloadIsIdentical() = runTest {
        val legacy = liveSession().copy(accessToken = "synthetic-legacy-access")
        assertNull(legacy.authSessionId())
        store.save(legacy)
        enqueueAcceptedAccount()
        val model = createViewModel()
        val previous = awaitIdle(model)
        val revision = requireNotNull(previous.sessionRevision)
        repeat(2) { takeRequest() }
        server.enqueue(json(legacy.toJson().toString()))
        server.enqueue(json("""{"factors":[]}"""))
        server.enqueue(json("""[{"user_id":"user-1","permissions":{"poker":false,"fire":true}}]"""))

        model.signIn(legacy.email, "legacy-password")
        val accepted = awaitIdle(model)
        assertEquals(previous.session, accepted.session)
        assertNotEquals(revision, accepted.sessionRevision)
        assertEquals(api.sessionOwnerRevision(legacy), accepted.sessionRevision)
        assertFalse(canNavigate("poker", accepted.permissions))
        assertTrue(canNavigate("fire", accepted.permissions))
        assertNull(accepted.mfaFactor)
        assertNull(accepted.error)
        assertEquals(legacy, store.load())
        assertEquals(5, server.requestCount)
        val login = takeRequest()
        assertEquals("password", login.requestUrl?.queryParameter("grant_type"))
        assertEquals("legacy-password", JSONObject(login.body.readUtf8()).getString("password"))
        assertEquals("/auth/v1/user", takeRequest().requestUrl?.encodedPath)
        assertEquals("/rest/v1/profiles", takeRequest().requestUrl?.encodedPath)
    }

    @Test fun acceptedTokenRefreshOfTheSameAuthSessionKeepsItsRevision() = runTest {
        enqueueAcceptedAccount()
        val model = createViewModel()
        val previous = awaitIdle(model)
        val revision = requireNotNull(previous.sessionRevision)
        repeat(2) { takeRequest() }
        val rotated = previous.session!!.copy(accessToken = previous.session.accessToken + "-rotated", refreshToken = "rotated-refresh")
        store.save(previous.session.copy(expiresAt = 1))
        server.enqueue(json(rotated.toJson().toString()))
        enqueueAcceptedAccount()

        model.restore()
        val accepted = awaitIdle(model)
        assertEquals(rotated, accepted.session)
        assertEquals(previous.session.authSessionId(), accepted.session?.authSessionId())
        assertEquals(revision, accepted.sessionRevision)
        assertEquals(revision, api.sessionOwnerRevision(rotated))
        assertEquals(previous.permissions, accepted.permissions)
        assertNull(accepted.error)
        assertEquals(rotated, store.load())
        assertEquals(5, server.requestCount)
        val refresh = takeRequest()
        assertEquals("refresh_token", refresh.requestUrl?.queryParameter("grant_type"))
        assertEquals(previous.session.refreshToken, JSONObject(refresh.body.readUtf8()).getString("refresh_token"))
        repeat(2) { assertEquals("Bearer ${rotated.accessToken}", takeRequest().getHeader("Authorization")) }
    }

    @Test fun staleLogoutNeverDispatchesForAnotherAcceptedSessionOrRepeatedLegacyLogin() = runTest {
        for (kind in listOf("other-user", "same-user-new-sid", "same-user-legacy-login")) {
            val displayed = if (kind == "same-user-legacy-login") liveSession().copy(accessToken = "synthetic-legacy-access") else liveSession()
            store.save(displayed)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/auth/v1/user" -> json("""{"factors":[]}""")
                    "/rest/v1/profiles" -> json("""[{"user_id":"user-1","permissions":{"poker":true,"admin":true}}]""")
                    "/auth/v1/token" -> json(displayed.toJson().toString())
                    "/auth/v1/logout" -> json("", 204)
                    else -> json("{}", 404)
                }
            }
            val origin = server.url("/").toString()
            val caseApi = SupabaseApi(BackendConfig(origin, "public-test-key", origin), store)
            val model = AppViewModel(caseApi).also { viewModels.put("stale-logout-$kind", it) }
            val previous = awaitIdle(model)
            val revision = requireNotNull(previous.sessionRevision)
            assertTrue(previous.permissions.getValue("admin"))
            repeat(2) { takeRequest() }
            val replacement = if (kind == "same-user-legacy-login") {
                assertEquals(displayed, caseApi.signIn(displayed.email, "legacy-password"))
                val login = takeRequest()
                assertEquals("password", login.requestUrl?.queryParameter("grant_type"))
                displayed // Same payload; only the new-login revision distinguishes the owner.
            } else {
                liveSession("22222222-2222-4222-8222-222222222222").copy(
                    userId = if (kind == "other-user") "user-2" else displayed.userId,
                    refreshToken = "replacement-refresh",
                ).also(store::save)
            }
            val requestsBeforeClick = server.requestCount
            val warningBeforeClick = caseApi.accessAuditWarning.value
            if (kind == "same-user-legacy-login") assertNotNull(warningBeforeClick)
            model.signOut()
            val refused = awaitIdle(model)
            assertEquals(requestsBeforeClick, server.requestCount) // No logout, profile, refresh or retry.
            assertEquals(replacement, store.load())
            assertEquals(warningBeforeClick, caseApi.accessAuditWarning.value)
            assertNull(caseApi.sessionInvalidation.value)
            assertFalse(caseApi.isSessionOwnerCurrent(revision))
            assertNull(refused.session)
            assertNull(refused.sessionRevision)
            assertTrue(refused.permissions.isEmpty())
            assertNull(refused.mfaFactor)
            assertNotNull(refused.error)
        }
    }

    @Test fun logoutFromAcceptedSnapshotAfterTransparentRefreshUsesTheCurrentTokenOfTheSameSession() = runTest {
        enqueueAcceptedAccount()
        val model = createViewModel()
        val previous = awaitIdle(model)
        val revision = requireNotNull(previous.sessionRevision)
        repeat(2) { takeRequest() }
        val rotated = previous.session!!.copy(accessToken = previous.session.accessToken + "-rotated", refreshToken = "rotated-refresh")
        store.save(previous.session.copy(expiresAt = 1))
        server.enqueue(json(rotated.toJson().toString()))
        assertEquals(rotated, api.session())
        assertEquals(previous.session, model.state.value.session)
        assertEquals(revision, api.sessionOwnerRevision(rotated))
        server.enqueue(json("", 204))

        model.signOut()
        val signedOut = awaitIdle(model)
        assertNull(signedOut.session)
        assertNull(signedOut.sessionRevision)
        assertTrue(signedOut.permissions.isEmpty())
        assertNull(signedOut.mfaFactor)
        assertNull(signedOut.error)
        assertNull(store.load())
        val event = requireNotNull(api.sessionInvalidation.value)
        assertEquals(revision, event.ownerRevision)
        assertTrue(api.isSessionInvalidationCurrent(event))
        assertEquals(4, server.requestCount)
        assertEquals("refresh_token", takeRequest().requestUrl?.queryParameter("grant_type"))
        val logout = takeRequest()
        assertEquals("POST", logout.method)
        assertEquals("/auth/v1/logout?scope=local", logout.path)
        assertEquals("Bearer ${rotated.accessToken}", logout.getHeader("Authorization"))
        assertEquals("{}", logout.body.readUtf8())
    }

    @Test fun replacementReadFailureCannotRetainOldAcceptedAccountDataEvenBeforeTheResponse() = runTest {
        for (replacementUser in listOf("user-1", "user-2")) {
            for (failedPath in listOf("/auth/v1/user", "/rest/v1/profiles")) {
                store.save(liveSession())
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                        "/auth/v1/user" -> json("""{"factors":[]}""")
                        "/rest/v1/profiles" -> json("""[{"user_id":"user-1","permissions":{"poker":true,"admin":true}}]""")
                        else -> json("{}", 404)
                    }
                }
                val origin = server.url("/").toString()
                val caseApi = SupabaseApi(BackendConfig(origin, "public-test-key", origin), store)
                val model = AppViewModel(caseApi).also { viewModels.put("replacement-$replacementUser-$failedPath", it) }
                val previous = awaitIdle(model)
                assertTrue(previous.permissions.getValue("poker"))
                assertTrue(previous.permissions.getValue("admin"))
                repeat(2) { takeRequest() }
                val requestCount = server.requestCount
                val replacement = liveSession("22222222-2222-4222-8222-222222222222")
                    .copy(userId = replacementUser, refreshToken = "replacement-refresh")
                val readEntered = CountDownLatch(1)
                val releaseRead = CountDownLatch(1)
                val readTimedOut = AtomicBoolean(false)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.requestUrl?.encodedPath == failedPath) {
                            readEntered.countDown()
                            if (!releaseRead.await(5, TimeUnit.SECONDS)) readTimedOut.set(true)
                            return json("{}", 503)
                        }
                        return if (request.requestUrl?.encodedPath == "/auth/v1/user") json("""{"factors":[]}""") else json("{}", 404)
                    }
                }
                store.save(replacement)
                model.restore()
                try {
                    assertTrue(withContext(Dispatchers.IO) { readEntered.await(5, TimeUnit.SECONDS) })
                    val pending = model.state.value
                    assertTrue(pending.busy)
                    assertNull(pending.session)
                    assertNull(pending.sessionRevision)
                    assertTrue(pending.permissions.isEmpty())
                    assertNull(pending.mfaFactor)
                } finally {
                    releaseRead.countDown()
                    awaitIdle(model)
                }
                val refused = model.state.value
                assertFalse(readTimedOut.get())
                assertNull(refused.session)
                assertNull(refused.sessionRevision)
                assertTrue(refused.permissions.isEmpty())
                assertNull(refused.mfaFactor)
                assertNotNull(refused.error)
                assertEquals(replacement, store.load())
                assertNull(caseApi.sessionInvalidation.value)
                val reads = if (failedPath == "/auth/v1/user") 1 else 2
                assertEquals(requestCount + reads, server.requestCount)
                repeat(reads) {
                    val request = takeRequest()
                    assertEquals("GET", request.method)
                    assertEquals("Bearer ${replacement.accessToken}", request.getHeader("Authorization"))
                }
            }
        }
    }

    @Test fun revokedRefreshClearsPreviousSessionPermissionsAndMfaFromAppState() = runTest {
        enqueueAcceptedAccount()
        val model = createViewModel()
        val previous = awaitIdle(model)
        assertEquals(true, previous.permissions["poker"])
        store.save(previous.session!!.copy(expiresAt = 1))
        server.enqueue(json("""{"code":"refresh_token_not_found"}""", 400))

        model.restore()
        val state = awaitIdle(model)
        assertTrue(state.initialized)
        assertFalse(state.busy)
        assertNull(state.session)
        assertTrue(state.permissions.isEmpty())
        assertNull(state.mfaFactor)
        assertNull(state.message)
        assertNotNull(state.error)
        assertNull(store.load())
        assertEquals(3, server.requestCount)
        takeRequest()
        takeRequest()
        val refresh = takeRequest()
        assertEquals("refresh_token", refresh.requestUrl?.queryParameter("grant_type"))
        assertEquals("/auth/v1/token", refresh.requestUrl?.encodedPath)
    }

    @Test fun registrationAvailabilityReadAndTransientFailurePreserveExistingAccountState() = runTest {
        enqueueAcceptedAccount()
        val model = createViewModel()
        val previous = awaitIdle(model)
        assertNull(previous.registrationsEnabled)
        assertEquals(2, server.requestCount) // Restore does not add a public availability request.
        takeRequest(); takeRequest()
        server.enqueue(json("false"))
        model.checkRegistrations()
        val checked = awaitIdle(model)
        assertEquals(false, checked.registrationsEnabled)
        assertEquals(previous.session, checked.session)
        assertEquals(previous.permissions, checked.permissions)
        val availability = takeRequest()
        assertEquals("/rest/v1/rpc/get_registrations_enabled", availability.requestUrl?.encodedPath)
        assertEquals("Bearer public-test-key", availability.getHeader("Authorization"))
        server.enqueue(json("{}", 503))
        model.checkRegistrations()
        val failed = awaitIdle(model)
        assertNotNull(failed.error)
        assertEquals(false, failed.registrationsEnabled)
        assertEquals(previous.session, failed.session)
        assertEquals(previous.permissions, failed.permissions)
        assertEquals(previous.session, store.load())
        assertEquals(4, server.requestCount)
    }

    @Test fun refreshServerFailurePreservesPreviouslyLoadedAccountState() = runTest {
        enqueueAcceptedAccount()
        val model = createViewModel()
        val previous = awaitIdle(model)
        val expiredStoredSession = previous.session!!.copy(expiresAt = 1)
        store.save(expiredStoredSession)
        server.enqueue(json("""{"message":"private server diagnostic"}""", 503))

        model.restore()
        val state = awaitIdle(model)
        assertEquals(previous.session, state.session)
        assertEquals(previous.permissions, state.permissions)
        assertEquals(previous.mfaFactor, state.mfaFactor)
        assertEquals(expiredStoredSession, store.load())
        assertTrue(state.initialized)
        assertFalse(state.busy)
        assertNotNull(state.error)
        assertFalse(state.error!!.contains("private server diagnostic"))
        assertEquals(3, server.requestCount)
    }

    @Test fun successfulLogoutUsesCentralInvalidationToRemoveAccountState() = runTest {
        enqueueAcceptedAccount()
        val model = createViewModel()
        val previous = awaitIdle(model)
        assertTrue(previous.permissions.isNotEmpty())
        server.enqueue(json("", 204))
        model.signOut()
        val state = model.state.first { it.initialized && !it.busy && it.session == null }
        assertTrue(state.permissions.isEmpty())
        assertNull(state.mfaFactor)
        assertNull(state.error)
        assertNull(store.load())
        assertTrue(api.isSessionInvalidationCurrent(requireNotNull(api.sessionInvalidation.value)))
        assertEquals(3, server.requestCount)
        takeRequest(); takeRequest()
        val logout = takeRequest()
        assertEquals("/auth/v1/logout?scope=local", logout.path)
        assertEquals("Bearer ${previous.session!!.accessToken}", logout.getHeader("Authorization"))
    }

    @Test fun failedLogoutStillClearsBoundAccountStateAndPreservesTheFailureMessage() = runTest {
        enqueueAcceptedAccount()
        val model = createViewModel()
        awaitIdle(model)
        server.enqueue(json("""{"message":"private logout diagnostic"}""", 503))
        model.signOut()
        val state = model.state.first { it.initialized && !it.busy && it.session == null && it.error != null }
        assertTrue(state.permissions.isEmpty())
        assertNull(state.mfaFactor)
        assertFalse(state.error!!.contains("private logout"))
        assertNull(store.load())
        assertTrue(api.isSessionInvalidationCurrent(requireNotNull(api.sessionInvalidation.value)))
        assertEquals(3, server.requestCount)
    }

    @Test fun verifiedTotpAtAal1BlocksProfileQueryAndAccountData() = runTest {
        server.enqueue(json("""{"factors":[{"id":"factor-1","factor_type":"totp","status":"verified"}]}"""))
        val model = createViewModel()
        val state = awaitIdle(model)
        assertEquals("aal1", assuranceLevel(state.session!!))
        assertEquals("factor-1", state.mfaFactor)
        assertTrue(state.permissions.isEmpty())
        assertNull(state.error)
        assertTrue(state.initialized)
        assertFalse(state.busy)
        assertEquals(1, server.requestCount)
        assertEquals("/auth/v1/user", takeRequest().requestUrl?.encodedPath)
    }

    @Test fun rejectedMfaCodeKeepsGateAndRetryLoadsPermissionsOnlyAfterAal2() = runTest {
        val original = requireNotNull(store.load())
        val verifiedFactor = """{"factors":[{"id":"factor-1","factor_type":"totp","status":"verified"}]}"""
        server.enqueue(json(verifiedFactor))
        val model = createViewModel()
        val pending = awaitIdle(model)
        val owner = requireNotNull(api.sessionOwnerRevision(original))
        assertEquals(original, pending.session)
        assertEquals("factor-1", pending.mfaFactor)
        assertTrue(pending.permissions.isEmpty())
        assertEquals("/auth/v1/user", takeRequest().requestUrl?.encodedPath)

        server.enqueue(json("""{"id":"challenge-first"}"""))
        server.enqueue(json("""{"code":"mfa_verification_failed","message":"private MFA diagnostic"}""", 400))
        model.verifyMfa("000000")
        val rejected = awaitIdle(model)
        assertEquals(original, rejected.session)
        assertEquals(original, store.load())
        assertEquals("factor-1", rejected.mfaFactor)
        assertTrue(rejected.permissions.isEmpty())
        assertEquals("Codice di verifica non corretto o scaduto.", rejected.error)
        assertNull(api.sessionInvalidation.value)
        assertEquals(3, server.requestCount) // No profile read and no automatic verification retry.
        val firstChallenge = takeRequest()
        assertEquals("POST", firstChallenge.method)
        assertEquals("/auth/v1/factors/factor-1/challenge", firstChallenge.requestUrl?.encodedPath)
        val firstVerify = takeRequest()
        val firstBody = JSONObject(firstVerify.body.readUtf8())
        assertEquals("POST", firstVerify.method)
        assertEquals("/auth/v1/factors/factor-1/verify", firstVerify.requestUrl?.encodedPath)
        assertEquals("challenge-first", firstBody.getString("challenge_id"))
        assertEquals("000000", firstBody.getString("code"))
        assertEquals("Bearer ${original.accessToken}", firstVerify.getHeader("Authorization"))

        val elevated = original.copy(accessToken = liveSession(aal = "aal2").accessToken, refreshToken = "mfa-refresh")
        server.enqueue(json("""{"id":"challenge-retry"}"""))
        server.enqueue(json(elevated.toJson().toString()))
        server.enqueue(json(verifiedFactor))
        server.enqueue(json("""[{"user_id":"user-1","permissions":{"poker":true,"admin":false}}]"""))
        model.verifyMfa("123456")
        val accepted = awaitIdle(model)
        assertEquals("aal2", assuranceLevel(requireNotNull(accepted.session)))
        assertEquals(elevated, accepted.session)
        assertEquals(elevated, store.load())
        assertEquals(requireNotNull(original.authSessionId()), elevated.authSessionId())
        assertEquals(owner, api.sessionOwnerRevision(elevated))
        assertNull(accepted.mfaFactor)
        assertNull(accepted.error)
        assertEquals(true, accepted.permissions["poker"])
        assertEquals(false, accepted.permissions["admin"])
        assertEquals(7, server.requestCount)
        assertEquals("/auth/v1/factors/factor-1/challenge", takeRequest().requestUrl?.encodedPath)
        val retryVerify = takeRequest()
        val retryBody = JSONObject(retryVerify.body.readUtf8())
        assertEquals("/auth/v1/factors/factor-1/verify", retryVerify.requestUrl?.encodedPath)
        assertEquals("challenge-retry", retryBody.getString("challenge_id"))
        assertEquals("123456", retryBody.getString("code"))
        assertEquals("Bearer ${original.accessToken}", retryVerify.getHeader("Authorization"))
        val factorRead = takeRequest()
        assertEquals("/auth/v1/user", factorRead.requestUrl?.encodedPath)
        assertEquals("Bearer ${elevated.accessToken}", factorRead.getHeader("Authorization"))
        val profileRead = takeRequest()
        assertEquals("/rest/v1/profiles", profileRead.requestUrl?.encodedPath)
        assertEquals("eq.${original.userId}", profileRead.requestUrl?.queryParameter("user_id"))
        assertEquals("Bearer ${elevated.accessToken}", profileRead.getHeader("Authorization"))
    }

    @Test fun mfaVerificationServerFailurePreservesGateAndSessionWithoutRetry() = runTest {
        val original = requireNotNull(store.load())
        server.enqueue(json("""{"factors":[{"id":"factor-1","factor_type":"totp","status":"verified"}]}"""))
        val model = createViewModel()
        val pending = awaitIdle(model)
        val owner = requireNotNull(api.sessionOwnerRevision(original))
        assertEquals("factor-1", pending.mfaFactor)
        assertTrue(pending.permissions.isEmpty())
        server.enqueue(json("""{"id":"challenge-server-failure"}"""))
        server.enqueue(json("""{"message":"private MFA server diagnostic"}""", 503))

        model.verifyMfa("123456")
        val failed = awaitIdle(model)
        assertTrue(failed.initialized)
        assertFalse(failed.busy)
        assertEquals(original, failed.session)
        assertEquals(original, store.load())
        assertEquals("aal1", assuranceLevel(requireNotNull(failed.session)))
        assertEquals("factor-1", failed.mfaFactor)
        assertTrue(failed.permissions.isEmpty())
        assertEquals("Servizio temporaneamente non disponibile. Riprova.", failed.error)
        assertEquals(owner, api.sessionOwnerRevision(original))
        assertNull(api.sessionInvalidation.value)
        assertEquals(3, server.requestCount) // Only restore, challenge and verification; no profile or refresh/logout.
        assertEquals("/auth/v1/user", takeRequest().requestUrl?.encodedPath)
        assertEquals("/auth/v1/factors/factor-1/challenge", takeRequest().requestUrl?.encodedPath)
        val verification = takeRequest()
        assertEquals("POST", verification.method)
        assertEquals("/auth/v1/factors/factor-1/verify", verification.requestUrl?.encodedPath)
        assertEquals("challenge-server-failure", JSONObject(verification.body.readUtf8()).getString("challenge_id"))
    }

    @Test fun staleMfaGateCannotElevateAReplacementLoginOfTheSameUserBeforeRestore() = runTest {
        val factor = """{"factors":[{"id":"factor-1","factor_type":"totp","status":"verified"}]}"""
        server.enqueue(json(factor))
        val model = createViewModel()
        val shown = awaitIdle(model)
        val original = requireNotNull(shown.session)
        assertEquals("factor-1", shown.mfaFactor)
        assertEquals("aal1", assuranceLevel(original))
        assertEquals("/auth/v1/user", takeRequest().requestUrl?.encodedPath)

        val replacement = liveSession("22222222-2222-4222-8222-222222222222").copy(refreshToken = "replacement-refresh")
        val elevatedReplacement = replacement.copy(accessToken = liveSession(requireNotNull(replacement.authSessionId()), "aal2").accessToken)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/auth/v1/factors/factor-1/challenge" -> json("""{"id":"replacement-challenge"}""")
                "/auth/v1/factors/factor-1/verify" -> json(elevatedReplacement.toJson().toString())
                "/auth/v1/user" -> json(factor)
                "/rest/v1/profiles" -> json("""[{"permissions":{"poker":true}}]""")
                else -> json("{}", 404)
            }
        }
        // The store changes before the old gate's click; no restore observes the new session first.
        store.save(replacement)
        model.verifyMfa("123456")
        val refused = awaitIdle(model)
        assertEquals("La sessione è cambiata. Aggiorna la schermata prima di continuare.", refused.error)
        assertNull(refused.session)
        assertNull(refused.sessionRevision)
        assertNull(refused.mfaFactor)
        assertTrue(refused.permissions.isEmpty())
        assertEquals(replacement, store.load())
        assertEquals("aal1", assuranceLevel(requireNotNull(store.load())))
        assertNotEquals(original.authSessionId(), store.load()?.authSessionId())
        assertNull(api.sessionInvalidation.value)
        assertEquals(1, server.requestCount) // No challenge, verify, profile, refresh or logout after the old gate click.
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        assertNull(api.sessionOwnerRevision(original))
        assertNotNull(api.sessionOwnerRevision(replacement))

        model.restore()
        val freshGate = awaitIdle(model)
        assertEquals(replacement, freshGate.session)
        assertEquals(api.sessionOwnerRevision(replacement), freshGate.sessionRevision)
        assertEquals("factor-1", freshGate.mfaFactor)
        assertTrue(freshGate.permissions.isEmpty())
        assertNull(freshGate.error)
        assertEquals(2, server.requestCount) // Fresh gate reads factors only, never profile data before aal2.
        assertEquals("Bearer ${replacement.accessToken}", takeRequest().getHeader("Authorization"))

        model.verifyMfa("123456")
        val accepted = awaitIdle(model)
        assertEquals(elevatedReplacement, accepted.session)
        assertEquals(freshGate.sessionRevision, accepted.sessionRevision)
        assertNull(accepted.mfaFactor)
        assertEquals(true, accepted.permissions["poker"])
        assertNull(accepted.error)
        assertEquals(elevatedReplacement, store.load())
        assertEquals(6, server.requestCount)
        val challenge = takeRequest()
        assertEquals("/auth/v1/factors/factor-1/challenge", challenge.requestUrl?.encodedPath)
        assertEquals("Bearer ${replacement.accessToken}", challenge.getHeader("Authorization"))
        val verification = takeRequest()
        assertEquals("/auth/v1/factors/factor-1/verify", verification.requestUrl?.encodedPath)
        assertEquals("Bearer ${replacement.accessToken}", verification.getHeader("Authorization"))
        val body = JSONObject(verification.body.readUtf8())
        assertEquals("replacement-challenge", body.getString("challenge_id"))
        assertEquals("123456", body.getString("code"))
        val factors = takeRequest()
        assertEquals("/auth/v1/user", factors.requestUrl?.encodedPath)
        assertEquals("Bearer ${elevatedReplacement.accessToken}", factors.getHeader("Authorization"))
        val profile = takeRequest()
        assertEquals("/rest/v1/profiles", profile.requestUrl?.encodedPath)
        assertEquals("eq.${replacement.userId}", profile.requestUrl?.queryParameter("user_id"))
        assertEquals("Bearer ${elevatedReplacement.accessToken}", profile.getHeader("Authorization"))
    }

    @Test fun paneRefreshRevocationClearsForegroundAccountWithoutRestoreOrResume() = runTest {
        enqueueAcceptedAccount()
        val model = createViewModel()
        val previous = awaitIdle(model)
        store.save(previous.session!!.copy(expiresAt = 1))
        server.enqueue(json("""{"code":"refresh_token_not_found"}""", 400))

        // The settings pane calls the API directly, outside AppViewModel.runAction.
        expectApiError(ApiError.AUTHENTICATION) { api.session() }
        val state = model.state.first { it.session == null }
        assertTrue(state.initialized)
        assertFalse(state.busy)
        assertTrue(state.permissions.isEmpty())
        assertNull(state.mfaFactor)
        assertNotNull(state.error)
        assertNull(store.load())
        assertEquals(3, server.requestCount)
    }

    @Test fun paneRefreshServerFailurePreservesForegroundAccount() = runTest {
        enqueueAcceptedAccount()
        val model = createViewModel()
        val previous = awaitIdle(model)
        val expired = previous.session!!.copy(expiresAt = 1)
        store.save(expired)
        server.enqueue(json("""{"message":"private diagnostic"}""", 503))

        expectApiError(ApiError.SERVER) { api.session() }
        assertEquals(previous, model.state.value)
        assertEquals(expired, store.load())
        assertNull(api.sessionInvalidation.value)
        assertEquals(3, server.requestCount)
    }

    @Test fun paneRefreshNetworkFailurePreservesForegroundAccount() = runTest {
        enqueueAcceptedAccount()
        val model = createViewModel()
        val previous = awaitIdle(model)
        val expired = previous.session!!.copy(expiresAt = 1)
        store.save(expired)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        expectApiError(ApiError.NETWORK) { api.session() }
        assertEquals(previous, model.state.value)
        assertEquals(expired, store.load())
        assertNull(api.sessionInvalidation.value)
    }

    @Test fun transparentTokenRotationStillInvalidatesTheCachedForegroundSession() = runTest {
        enqueueAcceptedAccount()
        val model = createViewModel()
        val previous = awaitIdle(model)
        val owner = api.sessionOwnerRevision(previous.session!!)
        val rotated = previous.session.copy(accessToken = previous.session.accessToken + "-rotated", refreshToken = "rotated-refresh")
        store.save(previous.session.copy(expiresAt = 1))
        server.enqueue(json(rotated.toJson().toString()))

        assertEquals(rotated, api.session())
        assertEquals(owner, api.sessionOwnerRevision(rotated))
        assertEquals(previous.session, model.state.value.session)
        store.save(rotated.copy(expiresAt = 1))
        server.enqueue(json("""{"code":"refresh_token_not_found"}""", 400))
        expectApiError(ApiError.AUTHENTICATION) { api.session() }

        val state = model.state.first { it.session == null }
        assertTrue(state.permissions.isEmpty())
        assertNull(store.load())
        assertEquals(4, server.requestCount)
    }

    @Test fun unreadableOwnedStoreClearsForegroundWithoutMakingAnHttpRequest() = runTest {
        val unreadable = AtomicBoolean(false)
        val failingStore = object : SessionStore {
            override fun load(): Session? {
                if (unreadable.get()) {
                    store.clear() // AndroidSessionStore removes an unreadable ciphertext before throwing.
                    throw ApiException(ApiError.STORAGE, "Sessione locale non leggibile. Accedi di nuovo.")
                }
                return store.load()
            }
            override fun save(session: Session) = store.save(session)
            override fun clear() = store.clear()
        }
        api.close()
        val origin = server.url("/").toString()
        api = SupabaseApi(BackendConfig(origin, "public-test-key", origin), failingStore)
        enqueueAcceptedAccount()
        val model = createViewModel()
        awaitIdle(model)
        unreadable.set(true)

        expectApiError(ApiError.STORAGE) { api.session() }
        val state = model.state.first { it.session == null }
        assertTrue(state.permissions.isEmpty())
        assertNull(state.mfaFactor)
        assertNotNull(state.error)
        assertNull(store.load())
        assertEquals(2, server.requestCount)
    }

    @Test fun profileResponseAfterPaneInvalidationCannotRestoreAccountState() = runTest {
        enqueueAcceptedAccount()
        val model = createViewModel()
        val previous = awaitIdle(model)
        takeRequest()
        takeRequest()
        val profileRequested = CountDownLatch(1)
        val releaseProfile = CountDownLatch(1)
        val profileTimedOut = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/auth/v1/user" -> json("""{"factors":[]}""")
                "/rest/v1/profiles" -> {
                    profileRequested.countDown()
                    if (!releaseProfile.await(5, TimeUnit.SECONDS)) profileTimedOut.set(true)
                    json("""[{"permissions":{"poker":true,"admin":true}}]""")
                }
                "/auth/v1/token" -> json("""{"code":"refresh_token_not_found"}""", 400)
                else -> json("{}", 404)
            }
        }

        try {
            model.restore()
            assertTrue(withContext(Dispatchers.IO) { profileRequested.await(5, TimeUnit.SECONDS) })
            store.save(previous.session!!.copy(expiresAt = 1))
            expectApiError(ApiError.AUTHENTICATION) { api.session() }
            model.state.first { it.session == null }
        } finally { releaseProfile.countDown() }

        val state = awaitIdle(model)
        assertFalse(profileTimedOut.get())
        assertNull(state.session)
        assertTrue(state.permissions.isEmpty())
        assertNull(state.mfaFactor)
        assertNull(store.load())
        assertEquals(5, server.requestCount)
    }

    @Test fun invalidationWaitingOnMainCannotClearANewerLoginOfTheSameUser() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        enqueueAcceptedAccount()
        val model = createViewModel()
        val previous = awaitIdle(model)
        val newSession = previous.session!!.copy(
            accessToken = liveSession("22222222-2222-4222-8222-222222222222").accessToken,
            refreshToken = "new-login-refresh",
        )

        // Hold the Main test scheduler while I/O invalidates the old owner and completes a new login.
        runBlocking {
            store.save(previous.session.copy(expiresAt = 1))
            server.enqueue(json("""{"code":"refresh_token_not_found"}""", 400))
            expectApiError(ApiError.AUTHENTICATION) { api.session() }
            val oldEvent = requireNotNull(api.sessionInvalidation.value)
            server.enqueue(json(newSession.toJson().toString()))
            server.enqueue(json("", 201)) // Session-specific best-effort access audit.
            assertEquals(newSession, api.signIn(newSession.email, "old"))
            assertFalse(api.isSessionInvalidationCurrent(oldEvent))
            assertNull(api.sessionOwnerRevision(previous.session))
            assertNotNull(api.sessionOwnerRevision(newSession))
            repeat(5) { takeRequest() } // Wait for the audit request before enqueuing restore responses.
        }
        enqueueAcceptedAccount()
        model.restore()
        val state = awaitIdle(model)
        assertEquals(newSession, state.session)
        assertEquals(true, state.permissions["poker"])
        assertNull(state.error)
        assertEquals(newSession, store.load())
        assertNull(api.sessionInvalidation.value)
    }

    private fun createViewModel(): AppViewModel = AppViewModel(api).also { viewModels.put("app", it) }
    private suspend fun awaitIdle(model: AppViewModel): AppState = model.state.first { it.initialized && !it.busy }
    private fun enqueueAcceptedAccount() {
        server.enqueue(json("""{"factors":[]}"""))
        server.enqueue(json("""[{"user_id":"user-1","permissions":{"poker":true,"fire":true,"admin":false,"tcg":"true"}}]"""))
    }
    private fun takeRequest() = server.takeRequest(1, TimeUnit.SECONDS) ?: throw AssertionError("Expected a local mock request")
    private fun json(body: String, status: Int = 200): MockResponse = MockResponse()
        .setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun liveSession(sessionId: String = "11111111-1111-4111-8111-111111111111", aal: String = "aal1"): Session {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"aal":"$aal","session_id":"$sessionId"}""".toByteArray(Charsets.UTF_8))
        return Session("header.$payload.signature", "synthetic-refresh", System.currentTimeMillis() / 1000 + 3600, "user-1", "device@example.test")
    }

    private suspend fun expectApiError(kind: ApiError, action: suspend () -> Unit) {
        try { action(); fail("Expected a typed API error") }
        catch (error: ApiException) { assertEquals(kind, error.kind) }
    }

    class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
        override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
        override fun finished(description: Description) { Dispatchers.resetMain() }
    }
}
