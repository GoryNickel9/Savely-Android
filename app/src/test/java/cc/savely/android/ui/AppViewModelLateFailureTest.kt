package cc.savely.android.ui

import androidx.lifecycle.ViewModelStore
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Hold only Main delivery, letting the real localhost HTTP client complete a replacement login. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelLateFailureTest {
    @Test fun queuedAuthenticationFailureCannotEraseAReplacementLogin() {
        scenario(status = 401, replaceLogin = true)
    }

    @Test fun authenticationFailureStillResetsTheOriginalOwnersCachedPermissions() {
        scenario(status = 401, replaceLogin = false)
    }

    @Test fun transientFailurePreservesTheOriginalOwnersCachedPermissions() {
        scenario(status = 503, replaceLogin = false)
    }

    private fun scenario(status: Int, replaceLogin: Boolean) = runBlocking {
        val main = QueuedMain()
        Dispatchers.setMain(main)
        val original = session(SID_A)
        val replacement = session(SID_B)
        val store = MemorySessionStore(original)
        val availabilityRequests = LinkedBlockingQueue<RecordedRequest>()
        val releaseAvailabilityResponse = CountDownLatch(1)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/auth/v1/user" -> json("""{"factors":[]}""")
                    "/rest/v1/profiles" -> json("""[{"user_id":"$USER","permissions":{"poker":true,"admin":false}}]""")
                    "/rest/v1/rpc/get_registrations_enabled" -> {
                        availabilityRequests.put(request)
                        check(releaseAvailabilityResponse.await(10, TimeUnit.SECONDS)) { "RPC fixture response was not released" }
                        json("{}", status)
                    }
                    "/auth/v1/token" -> json(replacement.toJson().toString())
                    "/rest/v1/login_activity" -> json("", 201)
                    else -> json("{}", 404)
                }
            }
            start()
        }
        val api = SupabaseApi(BackendConfig(server.url("/").toString(), "fixture-public"), store)
        val models = ViewModelStore()
        try {
            val model = AppViewModel(api).also { models.put("app", it) }
            main.until { model.state.value.initialized && !model.state.value.busy }
            val before = model.state.value
            assertEquals(original, before.session)
            assertEquals(true, before.permissions["poker"])

            model.checkRegistrations()
            main.next().run() // The gated HTTP response guarantees this action suspends first.
            assertNotNull(availabilityRequests.poll(10, TimeUnit.SECONDS))
            releaseAvailabilityResponse.countDown()
            val failedContinuation = main.next()
            assertTrue(model.state.value.busy)
            if (replaceLogin) {
                // No Main work is delivered during this fresh login of the same UUID.
                assertEquals(replacement, api.signIn(replacement.email, "synthetic-password"))
                assertNotEquals(api.sessionOwnerRevision(original), api.sessionOwnerRevision(replacement))
            }
            failedContinuation.run()
            main.until { !model.state.value.busy }

            if (status == 401 && !replaceLogin) {
                assertNull(model.state.value.session)
                assertTrue(model.state.value.permissions.isEmpty())
                assertNotNull(model.state.value.error)
                assertEquals(original, store.load()) // This public RPC did not revoke the server login.
            } else {
                assertEquals(before.session, model.state.value.session)
                assertEquals(before.permissions, model.state.value.permissions)
                assertEquals(if (replaceLogin) replacement else original, store.load())
                if (replaceLogin) {
                    assertNull(model.state.value.error)
                    model.restore()
                    main.until { !model.state.value.busy && model.state.value.session == replacement }
                    assertEquals(true, model.state.value.permissions["poker"])
                } else assertNotNull(model.state.value.error)
            }
        } finally {
            releaseAvailabilityResponse.countDown()
            models.clear(); api.close(); server.shutdown(); Dispatchers.resetMain()
        }
    }

    private class QueuedMain : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.put(block) }
        fun next(): Runnable = queue.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("Main fixture continuation timed out")
        fun until(done: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!done()) {
                check(System.nanoTime() < deadline) { "Main fixture did not reach its bounded state" }
                next().run()
            }
        }
    }

    private fun session(id: String): Session {
        val claims = Base64.getUrlEncoder().withoutPadding().encodeToString(
            JSONObject().put("session_id", id).put("aal", "aal1").toString().toByteArray())
        return Session("synthetic.$claims.signature", "synthetic-refresh-$id", System.currentTimeMillis() / 1000 + 3600, USER, "qa@example.test")
    }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
    private companion object {
        const val USER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SID_A = "11111111-1111-4111-8111-111111111111"
        const val SID_B = "22222222-2222-4222-8222-222222222222"
    }
}
