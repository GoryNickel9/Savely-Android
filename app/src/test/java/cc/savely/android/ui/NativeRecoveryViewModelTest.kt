package cc.savely.android.ui

import androidx.lifecycle.ViewModelStore
import cc.savely.android.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class NativeRecoveryViewModelTest {
    @get:Rule val main = AppViewModelTest.MainDispatcherRule()
    private lateinit var server: MockWebServer
    private lateinit var api: SupabaseApi
    private val ordinary = MemorySessionStore()
    private val links = MemoryAuthLinkStore()
    private val models = ViewModelStore()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var updateStatus = 200
    private var mfa = false
    private var level = "aal1"
    @Before fun setup() {
        links.saveRecovery(NativeRecoveryContext(session(), System.currentTimeMillis() / 1000))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.requestUrl!!.encodedPath
                    val body = when {
                        request.method == "PUT" -> "{}"
                        path.endsWith("/challenge") -> "{\"id\":\"challenge\"}"
                        path.endsWith("/verify") -> { level = "aal2"; session().toJson().toString() }
                        path == "/auth/v1/user" -> JSONObject().put("factors", JSONArray().apply {
                            if (mfa) put(JSONObject().put("id", "factor-1").put("status", "verified").put("factor_type", "totp"))
                        }).toString()
                        path == "/rest/v1/profiles" -> "[{\"permissions\":{\"fire\":true}}]"
                        else -> "{}"
                    }
                    return MockResponse().setResponseCode(if (request.method == "PUT") updateStatus else 200)
                        .setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        api = SupabaseApi(BackendConfig(server.url("/").toString(), "public-key"), ordinary, authLinks = links)
    }
    @After fun cleanup() { models.clear(); api.close(); server.shutdown() }

    @Test fun restoredRecoveryNeverQueriesProfilesUntilPasswordUpdateConfirmed() = runTest {
        val model = model()
        val initial = idle(model)
        assertTrue(initial.recovery); assertNotNull(initial.session); assertTrue(initial.permissions.isEmpty())
        assertNull(ordinary.load()); assertEquals(0, profiles())
        updateStatus = 503
        model.completePasswordRecovery(PASSWORD, PASSWORD)
        val failed = idle(model)
        assertTrue(failed.recovery); assertEquals(initial.session, failed.session); assertNotNull(failed.error)
        assertNull(ordinary.load()); assertEquals(0, profiles())
        assertEquals(1, requests.count { it.method == "PUT" })
        updateStatus = 200
        model.completePasswordRecovery(PASSWORD, PASSWORD)
        val finished = idle(model)
        assertFalse(finished.recovery); assertEquals(true, finished.permissions["fire"])
        assertNotNull(ordinary.load()); assertNull(links.recovery()); assertEquals(1, profiles())
    }

    @Test fun recoveryMfaKeepsGateAfterElevationAndPasswordIsStillRequired() = runTest {
        mfa = true
        val model = model()
        val pending = idle(model)
        assertTrue(pending.recovery); assertEquals("factor-1", pending.mfaFactor); assertEquals(0, profiles())
        model.verifyMfa("123456")
        val elevated = idle(model)
        assertTrue(elevated.recovery); assertNull(elevated.mfaFactor); assertTrue(elevated.permissions.isEmpty())
        assertNull(ordinary.load()); assertEquals(0, profiles())
        model.completePasswordRecovery(PASSWORD, PASSWORD)
        assertFalse(idle(model).recovery); assertEquals(1, profiles())
    }

    @Test fun malformedOrReusedCallbackCannotRemoveExistingRecoveryGate() = runTest {
        val model = model(); val previous = idle(model)
        val inbox = AuthCallbackInbox()
        inbox.submit("savely://auth/callback?access_token=private")
        model.handleAuthCallback(inbox.events.value!!)
        val current = idle(model)
        assertTrue(current.recovery); assertEquals(previous.session, current.session); assertNotNull(current.error)
        assertFalse(current.error!!.contains("private")); assertEquals(0, profiles())
        assertNotNull(links.recovery())
    }

    @Test fun inboxConfigurationRetentionAndConsumeNeverRemoveNewerWarmEvent() {
        val inbox = AuthCallbackInbox()
        inbox.submit("savely://auth/callback?state=$SID&code=$CODE")
        val cold = inbox.events.value!!
        inbox.submit("savely://auth/callback?state=$SID&code=$CODE")
        val warm = inbox.events.value!!
        assertNotEquals(cold.id, warm.id)
        inbox.consume(cold.id); assertSame(warm, inbox.events.value)
        inbox.consume(warm.id); assertNull(inbox.events.value)
        assertFalse(cold.toString().contains(CODE))
    }

    private fun model() = AppViewModel(api).also { models.put("app", it) }
    private suspend fun idle(model: AppViewModel) = model.state.first { it.initialized && !it.busy }
    private fun profiles() = requests.count { it.requestUrl!!.encodedPath == "/rest/v1/profiles" }
    private fun session(): Session {
        val jwt = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"session_id\":\"$SID\",\"aal\":\"$level\"}".toByteArray())
        return Session("header.$jwt.signature", "synthetic-refresh", System.currentTimeMillis() / 1000 + 3600, USER, "qa@example.test")
    }
    private companion object {
        const val USER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SID = "11111111-1111-4111-8111-111111111111"
        const val CODE = "22222222-2222-4222-8222-222222222222"
        const val PASSWORD = "SyntheticPassword123!"
    }
}
