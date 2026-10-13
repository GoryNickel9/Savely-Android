package cc.savely.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

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

    @Test fun stalePermissionSaveCannotDispatchAfterSameUserStartsAnotherAuthSession() =
        fixture(original.copy(accessToken = jwt(SESSION_ID))) { server, store, api ->
            val displayed = requireNotNull(api.session())
            val revision = requireNotNull(api.sessionOwnerRevision())
            val nextLogin = displayed.copy(accessToken = jwt(REPLACEMENT_SESSION_ID), refreshToken = "replacement-refresh")
            store.save(nextLogin)
            try {
                api.update("profiles", PROFILE_ID, JSONObject().put("permissions", JSONObject().put("poker", false)),
                    "user_id=eq.${replacement.userId}", expectedOwner = displayed.userId, expectedRevision = revision)
                fail("Stale permission save accepted")
            } catch (failure: ApiException) { assertEquals(ApiError.PERMISSION, failure.kind) }
            assertEquals(0, server.requestCount)
            assertEquals(nextLogin, store.load())
            assertNull(api.sessionInvalidation.value)
        }

    @Test fun latePermissionSaveSuccessCannotPublishIntoAnotherAuthSessionOfTheSameUser() =
        fixture(original.copy(accessToken = jwt(SESSION_ID))) { server, store, api ->
            val displayed = requireNotNull(api.session())
            val revision = requireNotNull(api.sessionOwnerRevision())
            val nextLogin = displayed.copy(accessToken = jwt(REPLACEMENT_SESSION_ID), refreshToken = "replacement-refresh")
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    store.save(nextLogin)
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""[{"id":"$PROFILE_ID","permissions":{"poker":false}}]""")
                }
            }
            try {
                api.update("profiles", PROFILE_ID, JSONObject().put("permissions", JSONObject().put("poker", false)),
                    "user_id=eq.${replacement.userId}", expectedOwner = displayed.userId, expectedRevision = revision)
                fail("Late permission save accepted")
            } catch (failure: ApiException) { assertEquals(ApiError.PERMISSION, failure.kind) }
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/rest/v1/profiles", request.requestUrl?.encodedPath)
            assertEquals("eq.$PROFILE_ID", request.requestUrl?.queryParameter("id"))
            assertEquals("eq.${replacement.userId}", request.requestUrl?.queryParameter("user_id"))
            assertEquals("Bearer ${displayed.accessToken}", request.getHeader("Authorization"))
            assertEquals(false, JSONObject(request.body.readUtf8()).getJSONObject("permissions").getBoolean("poker"))
            assertEquals(1, server.requestCount)
            assertEquals(nextLogin, store.load())
            assertNull(api.sessionInvalidation.value)
        }

    private fun fixture(initial: Session = original, block: suspend (MockWebServer, MemorySessionStore, SupabaseApi) -> Unit) = runBlocking {
        val server = MockWebServer().apply { start() }
        val store = MemorySessionStore(initial)
        val api = SupabaseApi(BackendConfig(server.url("/").toString(), "fixture-public", server.url("/").toString()), store)
        try { block(server, store, api) }
        finally { api.close(); server.shutdown() }
    }

    private fun jwt(id: String) = "header.${Base64.getUrlEncoder().withoutPadding().encodeToString(
        JSONObject().put("session_id", id).toString().toByteArray(Charsets.UTF_8))}.signature"

    private companion object {
        const val SESSION_ID = "20000000-0000-4000-8000-000000000071"
        const val REPLACEMENT_SESSION_ID = "20000000-0000-4000-8000-000000000072"
        const val PROFILE_ID = "30000000-0000-4000-8000-000000000072"
    }
}
