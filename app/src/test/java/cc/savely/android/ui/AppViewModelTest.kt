package cc.savely.android.ui

import androidx.lifecycle.ViewModelStore
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.ApiError
import cc.savely.android.data.ApiException
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SessionStore
import cc.savely.android.data.SupabaseApi
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
    private fun liveSession(sessionId: String = "11111111-1111-4111-8111-111111111111"): Session {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"aal":"aal1","session_id":"$sessionId"}""".toByteArray(Charsets.UTF_8))
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
