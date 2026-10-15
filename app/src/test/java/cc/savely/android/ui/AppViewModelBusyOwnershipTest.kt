package cc.savely.android.ui

import androidx.lifecycle.ViewModelStore
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemoryAuthLinkStore
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.NativeRecoveryContext
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.data.authSessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Regression for a StateFlow observer starting MFA as restore publishes its first idle state. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelBusyOwnershipTest {
    @get:Rule val main = AppViewModelTest.MainDispatcherRule()

    @Test fun restoreFinallyCannotReleaseBusyOwnedByReentrantMfaVerification() = runTest {
        val firstUserEntered = CountDownLatch(1)
        val allowInitialUser = CountDownLatch(1)
        val challengeEntered = CountDownLatch(1)
        val allowChallenge = CountDownLatch(1)
        val verified = AtomicBoolean(false)
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val ordinary = MemorySessionStore()
        val links = MemoryAuthLinkStore().apply {
            saveRecovery(NativeRecoveryContext(session("aal1"), System.currentTimeMillis() / 1000))
        }
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.requestUrl!!.encodedPath
                    val body = when (path) {
                        "/auth/v1/user" -> {
                            if (requests.count { it.requestUrl!!.encodedPath == path } == 1) {
                                firstUserEntered.countDown()
                                check(allowInitialUser.await(10, TimeUnit.SECONDS)) { "Initial fixture latch timed out" }
                            }
                            JSONObject().put("factors", JSONArray().put(JSONObject().put("id", FACTOR)
                                .put("factor_type", "totp").put("status", "verified"))).toString()
                        }
                        "/auth/v1/factors/$FACTOR/challenge" -> {
                            challengeEntered.countDown()
                            check(allowChallenge.await(10, TimeUnit.SECONDS)) { "Challenge fixture latch timed out" }
                            """{"id":"synthetic-challenge"}"""
                        }
                        "/auth/v1/factors/$FACTOR/verify" -> {
                            assertEquals("123456", JSONObject(request.body.readUtf8()).getString("code"))
                            verified.set(true)
                            session("aal2").toJson().toString()
                        }
                        else -> return MockResponse().setResponseCode(500).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        val models = ViewModelStore()
        val api = SupabaseApi(BackendConfig(server.url("/").toString(), "fixture-public"), ordinary, authLinks = links)
        try {
            val model = AppViewModel(api).also { models.put("app", it) }
            val startedMfa = AtomicBoolean(false)
            // Undispatched StateFlow delivery makes the next action start inside the publication
            // that releases restore's busy state. No polling delay or arbitrary sleep is involved.
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.state.collect { state ->
                    if (state.initialized && !state.busy && state.mfaFactor == FACTOR &&
                        startedMfa.compareAndSet(false, true)) model.verifyMfa("123456")
                }
            }
            assertTrue(withContext(Dispatchers.IO) { firstUserEntered.await(10, TimeUnit.SECONDS) })
            allowInitialUser.countDown()
            assertTrue(withContext(Dispatchers.IO) { challengeEntered.await(10, TimeUnit.SECONDS) })

            assertTrue("Observer must have started MFA on the first initialized idle state", startedMfa.get())
            assertTrue("Restore's finally must not clear busy while the new MFA challenge is blocked", model.state.value.busy)
            assertTrue(model.state.value.recovery)
            assertEquals(FACTOR, model.state.value.mfaFactor)
            assertFalse(verified.get())
            assertNull(ordinary.load())
            assertEquals(1, requests.count { it.requestUrl!!.encodedPath == "/auth/v1/user" })
            assertEquals(1, requests.count { it.requestUrl!!.encodedPath.endsWith("/challenge") })
            // Another action is rejected until the verification actually finishes.
            model.restore()
            assertTrue(model.state.value.busy)
            assertEquals(2, requests.size)

            allowChallenge.countDown()
            val completed = withContext(Dispatchers.Default) {
                // Bound real HTTP completion with wall-clock time, not runTest's virtual clock.
                withTimeout(10_000) { model.state.first { it.initialized && !it.busy && it.mfaFactor == null } }
            }
            assertTrue(verified.get())
            assertTrue("MFA elevation must retain the password-recovery gate", completed.recovery)
            assertEquals("aal2", assuranceLevel(requireNotNull(completed.session)))
            assertEquals(SID, completed.session!!.authSessionId())
            assertTrue(completed.permissions.isEmpty())
            assertNull(completed.error)
            assertNull(ordinary.load())
            assertNotNull(links.recovery())
            assertEquals(2, requests.count { it.requestUrl!!.encodedPath == "/auth/v1/user" })
            assertEquals(1, requests.count { it.requestUrl!!.encodedPath.endsWith("/verify") })
            assertEquals(0, requests.count { it.requestUrl!!.encodedPath == "/rest/v1/profiles" })
            observer.cancel()
        } finally {
            allowInitialUser.countDown(); allowChallenge.countDown()
            models.clear(); api.close(); server.shutdown()
        }
    }

    private fun session(level: String): Session {
        val claims = JSONObject().put("session_id", SID).put("aal", level)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().toByteArray())
        return Session("synthetic.$encoded.signature", "synthetic-refresh", System.currentTimeMillis() / 1000 + 3600,
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "qa@example.test")
    }

    private companion object {
        const val SID = "11111111-1111-4111-8111-111111111111"
        const val FACTOR = "22222222-2222-4222-8222-222222222222"
    }
}
