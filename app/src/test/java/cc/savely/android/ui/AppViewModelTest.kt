package cc.savely.android.ui

import androidx.lifecycle.ViewModelStore
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.Base64
import java.util.concurrent.TimeUnit

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

    private fun createViewModel(): AppViewModel = AppViewModel(api).also { viewModels.put("app", it) }
    private suspend fun awaitIdle(model: AppViewModel): AppState = model.state.first { it.initialized && !it.busy }
    private fun enqueueAcceptedAccount() {
        server.enqueue(json("""{"factors":[]}"""))
        server.enqueue(json("""[{"user_id":"user-1","permissions":{"poker":true,"fire":true,"admin":false,"tcg":"true"}}]"""))
    }
    private fun takeRequest() = server.takeRequest(1, TimeUnit.SECONDS) ?: throw AssertionError("Expected a local mock request")
    private fun json(body: String, status: Int = 200): MockResponse = MockResponse()
        .setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun liveSession(): Session {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"aal":"aal1"}""".toByteArray(Charsets.UTF_8))
        return Session("header.$payload.signature", "synthetic-refresh", System.currentTimeMillis() / 1000 + 3600, "user-1", "device@example.test")
    }

    class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
        override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
        override fun finished(description: Description) { Dispatchers.resetMain() }
    }
}
