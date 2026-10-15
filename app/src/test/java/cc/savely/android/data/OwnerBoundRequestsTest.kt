package cc.savely.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

class OwnerBoundRequestsTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemorySessionStore
    private lateinit var api: SupabaseApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        store = MemorySessionStore()
        api = SupabaseApi(BackendConfig(server.url("/").toString(), "public-key"), store)
    }
    @After fun tearDown() { api.close(); server.shutdown() }

    @Test fun staleOwnerRejectsEveryBoundWriteBeforeHttpIncludingNewLoginOfSameUser() = runBlocking {
        for (newUser in listOf(USER, OTHER_USER)) {
            val original = session().also(store::save)
            val revision = requireNotNull(api.sessionOwnerRevision(requireNotNull(api.session())))
            val replacement = session(newUser, OTHER_SID).also(store::save)
            val body = JSONObject().put("user_id", original.userId)
            assertChanged { api.invokeFunction("update-prices", body, original.userId, revision) }
            assertChanged { api.rpc("delete_shared_expense_by_partner", body, original.userId, revision) }
            assertChanged { api.insert("transactions", body, original.userId, revision) }
            assertChanged { api.update("transactions", RECORD, body, expectedOwner = original.userId, expectedRevision = revision) }
            assertChanged { api.updateMinimal("transactions", RECORD, body, expectedOwner = original.userId, expectedRevision = revision) }
            assertChanged { api.delete("transactions", RECORD, expectedOwner = original.userId, expectedRevision = revision) }
            assertEquals(replacement, store.load())
            assertNull(api.sessionInvalidation.value)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun incompleteBindingIsRejectedBeforeHttpOrSessionRefresh() = runBlocking {
        store.save(session().copy(expiresAt = 1))
        try {
            api.invokeFunction("update-prices", expectedOwner = USER)
            fail("Expected validation")
        } catch (error: ApiException) { assertEquals(ApiError.VALIDATION, error.kind) }
        assertEquals(0, server.requestCount)
    }

    @Test fun boundResponseCannotPublishAcrossOwnerChangeAndUsesOnlyOriginalToken() = runBlocking {
        val original = session().also(store::save)
        val revision = requireNotNull(api.sessionOwnerRevision(requireNotNull(api.session())))
        val replacement = session(OTHER_USER, OTHER_SID)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                store.save(replacement)
                return json("{\"updated\":1}")
            }
        }
        assertChanged { api.invokeFunction("update-prices", JSONObject().put("user_id", USER), USER, revision) }
        assertEquals("Bearer ${original.accessToken}", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, server.requestCount)
        assertEquals(replacement, store.load())
        assertNull(api.sessionInvalidation.value)
    }

    @Test fun sharingPreflightCannotAdoptNewLoginBeforeSharedInsert() = runBlocking {
        val original = session().also(store::save)
        val replacement = session(USER, OTHER_SID)
        var reads = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                reads++
                return when (reads) {
                    1 -> json("[{\"id\":\"$RECORD\"}]")
                    2 -> json("[{\"id\":\"$RECORD\",\"amount\":1.01,\"currency\":\"EUR\"}]")
                    else -> { store.save(replacement); json("[]") }
                }
            }
        }
        assertChanged { CoupleRepository(api).shareExpense(RECORD, RECORD, "Casa", "0.50") }
        repeat(3) {
            val read = server.takeRequest()
            assertEquals("GET", read.method)
            assertEquals("Bearer ${original.accessToken}", read.getHeader("Authorization"))
        }
        assertEquals(3, server.requestCount)
        assertEquals(replacement, store.load())
        assertNull(api.sessionInvalidation.value)
    }

    @Test fun rejectedOldRequestCannotRetryOrRefreshWithDifferentUserOrNewSessionId() = runBlocking {
        for (newUser in listOf(USER, OTHER_USER)) {
            val original = session().also(store::save)
            val revision = requireNotNull(api.sessionOwnerRevision(requireNotNull(api.session())))
            val replacement = session(newUser, OTHER_SID)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    store.save(replacement)
                    return json("{}", 401)
                }
            }
            assertChanged { api.rpc("delete_shared_expense_by_partner", JSONObject().put("p_id", RECORD), USER, revision) }
            val request = server.takeRequest()
            assertEquals("/rest/v1/rpc/delete_shared_expense_by_partner", request.path)
            assertEquals("Bearer ${original.accessToken}", request.getHeader("Authorization"))
            assertEquals(replacement, store.load())
            assertNull(api.sessionInvalidation.value)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun unboundLegacyRequestAlsoRefuses401RetryWithAnotherLogin() = runBlocking {
        val original = session().also(store::save)
        val replacement = session(OTHER_USER, OTHER_SID)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                store.save(replacement)
                return json("{}", 401)
            }
        }
        assertChanged { api.invokeFunction("update-prices") }
        assertEquals("Bearer ${original.accessToken}", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, server.requestCount)
        assertEquals(replacement, store.load())
    }

    @Test fun lateSecond401DoesNotExposeAuthenticationFailureForNewOwner() = runBlocking {
        val original = session().also(store::save)
        val renewed = session(tokenVersion = 2)
        val replacement = session(OTHER_USER, OTHER_SID)
        var calls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls++
                return when (calls) {
                    1 -> json("{}", 401)
                    2 -> json(renewed.toJson().toString())
                    else -> { store.save(replacement); json("{}", 401) }
                }
            }
        }
        assertChanged { api.select("transactions") }
        assertEquals("Bearer ${original.accessToken}", server.takeRequest().getHeader("Authorization"))
        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        assertEquals("Bearer ${renewed.accessToken}", server.takeRequest().getHeader("Authorization"))
        assertEquals(3, server.requestCount)
        assertEquals(replacement, store.load())
        assertNull(api.sessionInvalidation.value)
    }

    @Test fun sameSessionTokenRotationAllowsOneExplicit401RetryWithoutRefresh() = runBlocking {
        val original = session().also(store::save)
        val revision = requireNotNull(api.sessionOwnerRevision(requireNotNull(api.session())))
        val rotated = session(tokenVersion = 2)
        var calls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls++
                return if (calls == 1) { store.save(rotated); json("{}", 401) } else json("{\"updated\":1}")
            }
        }
        assertEquals(1, (api.invokeFunction("update-prices", expectedOwner = USER, expectedRevision = revision) as JSONObject).getInt("updated"))
        assertEquals("Bearer ${original.accessToken}", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer ${rotated.accessToken}", server.takeRequest().getHeader("Authorization"))
        assertEquals(2, server.requestCount)
        assertEquals(revision, api.sessionOwnerRevision())
        assertEquals(rotated, store.load())
    }

    @Test fun expiredSnapshotRefreshesSameSessionWithoutChangingItsBinding() = runBlocking {
        val original = session().also(store::save)
        val revision = requireNotNull(api.sessionOwnerRevision(requireNotNull(api.session())))
        store.save(original.copy(expiresAt = 1))
        val renewed = session(tokenVersion = 2)
        server.enqueue(json(renewed.toJson().toString()))
        server.enqueue(json("{\"updated\":1}"))
        api.invokeFunction("update-prices", expectedOwner = USER, expectedRevision = revision)
        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        val write = server.takeRequest()
        assertEquals("/functions/v1/update-prices", write.path)
        assertEquals("Bearer ${renewed.accessToken}", write.getHeader("Authorization"))
        assertEquals(2, server.requestCount)
        assertEquals(revision, api.sessionOwnerRevision())
        assertEquals(renewed, store.load())
    }

    @Test fun serverFailureAndLostResponseNeverRetryBoundWriteOrClearSession() = runBlocking {
        val original = session().also(store::save)
        val revision = requireNotNull(api.sessionOwnerRevision(requireNotNull(api.session())))
        server.enqueue(json("{}", 503))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        for (expectedKind in listOf(ApiError.SERVER, ApiError.NETWORK)) {
            try {
                api.invokeFunction("update-prices", expectedOwner = USER, expectedRevision = revision)
                fail("Expected transport failure")
            } catch (error: ApiException) { assertEquals(expectedKind, error.kind) }
            assertEquals(original, store.load())
            assertNull(api.sessionInvalidation.value)
        }
        assertEquals(2, server.requestCount)
    }

    private suspend fun assertChanged(block: suspend () -> Any?) {
        try { block(); fail("Expected owner-change refusal") }
        catch (error: ApiException) { assertEquals(ApiError.PERMISSION, error.kind) }
    }
    private fun session(user: String = USER, id: String = SID, tokenVersion: Int = 1): Session {
        val payload = JSONObject().put("session_id", id).put("version", tokenVersion)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())
        return Session("header.$encoded.signature", "refresh-$tokenVersion", System.currentTimeMillis() / 1000 + 3600, user, "person@example.test")
    }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    companion object {
        private const val USER = "10000000-0000-4000-8000-000000000001"
        private const val OTHER_USER = "10000000-0000-4000-8000-000000000002"
        private const val SID = "20000000-0000-4000-8000-000000000001"
        private const val OTHER_SID = "20000000-0000-4000-8000-000000000002"
        private const val RECORD = "30000000-0000-4000-8000-000000000001"
    }
}
