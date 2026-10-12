package cc.savely.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Session races against a local server only; no Admin, email or account mutation is real. */
class AdminOperationOwnerTest {
    private val original = Session("fixture-token-a", "fixture-refresh-a", Long.MAX_VALUE / 2,
        "10000000-0000-4000-8000-000000000071", "a@example.test")
    private val replacement = original.copy(accessToken = "fixture-token-b", refreshToken = "fixture-refresh-b",
        userId = "10000000-0000-4000-8000-000000000072", email = "b@example.test")

    @Test fun staleAdminWebMutationDoesNotUseReplacementLogin() = fixture { server, store, api ->
        api.session()
        val revision = requireNotNull(api.sessionOwnerRevision())
        store.save(replacement)
        val failure = try {
            api.webPost("/api/admin", JSONObject().put("action", "delete-user"),
                expectedOwner = original.userId, expectedRevision = revision)
            error("Stale Admin mutation accepted")
        } catch (failure: ApiException) { failure }
        assertEquals(ApiError.PERMISSION, failure.kind)
        assertEquals(0, server.requestCount)
        assertEquals(replacement, store.load())
    }

    @Test fun lateAdminSuccessDoesNotPublishForReplacementLogin() = fixture { server, store, api ->
        api.session()
        val revision = requireNotNull(api.sessionOwnerRevision())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                store.save(replacement)
                return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"users\":[]}")
            }
        }
        val failure = try {
            api.webPost("/api/admin", JSONObject().put("action", "get-users"),
                expectedOwner = original.userId, expectedRevision = revision)
            error("Late Admin result accepted")
        } catch (failure: ApiException) { failure }
        assertEquals(ApiError.PERMISSION, failure.kind)
        assertEquals(1, server.requestCount)
        assertEquals("Bearer ${original.accessToken}", server.takeRequest().getHeader("Authorization"))
        assertEquals(replacement, store.load())
    }

    @Test fun staleAdminEmailConfirmationDoesNotSendEmail() = fixture { server, store, api ->
        api.session()
        val revision = requireNotNull(api.sessionOwnerRevision())
        store.save(replacement)
        val failure = try {
            api.requestPasswordReset("target@example.test", original.userId, revision)
            error("Stale email confirmation accepted")
        } catch (failure: ApiException) { failure }
        assertEquals(ApiError.PERMISSION, failure.kind)
        assertEquals(0, server.requestCount)
        assertEquals(replacement, store.load())
    }

    @Test fun boundAdminEmailPreservesPublicEndpointHeaders() = fixture { server, store, api ->
        api.session()
        val revision = requireNotNull(api.sessionOwnerRevision())
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
        api.requestPasswordReset("target@example.test", original.userId, revision)
        val request = server.takeRequest()
        assertEquals("/api/reset-password", request.path)
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("apikey"))
        assertEquals(original, store.load())
    }

    private fun fixture(block: suspend (MockWebServer, MemorySessionStore, SupabaseApi) -> Unit) = runBlocking {
        val server = MockWebServer().apply { start() }
        val store = MemorySessionStore(original)
        val api = SupabaseApi(BackendConfig(server.url("/").toString(), "fixture-public", server.url("/").toString()), store)
        try { block(server, store, api) }
        finally { api.close(); server.shutdown() }
    }
}
