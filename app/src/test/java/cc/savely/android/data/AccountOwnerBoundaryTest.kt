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

/** Synthetic localhost fixtures only; never authenticates against the configured backend. */
class AccountOwnerBoundaryTest {
    @Test fun oldSecurityAndAccountConfirmationsCannotUseANewLoginOfTheSameUser() = runBlocking {
        val original = session(SID_A)
        val replacement = session(SID_B)
        val store = MemorySessionStore(original)
        val server = MockWebServer().apply { start() }
        val api = SupabaseApi(BackendConfig(server.url("/").toString(), "fixture-public"), store)
        try {
            val revision = requireNotNull(api.sessionOwnerRevision(requireNotNull(api.session())))
            store.save(replacement)
            val actions: List<suspend () -> Unit> = listOf(
                { api.updateProfile(JSONObject().put("full_name", "Synthetic"), USER, revision) },
                { api.updateAccount(email = "changed@example.test", expectedOwner = USER, expectedRevision = revision) },
                { api.updateAccount(password = "NewSyntheticPass!123", expectedOwner = USER, expectedRevision = revision) },
                { api.deleteAccount(USER, revision) },
                { api.signOutOtherSessions(USER, revision) },
                { api.revokeSession(SID_A, USER, revision) },
                { api.enrollTotp(USER, revision) },
                { api.verifyTotp(FACTOR, "123456", USER, revision) },
                { api.unenrollTotp(FACTOR, USER, revision) },
                { api.unenrollPendingTotp(FACTOR, USER, revision) },
            )
            actions.forEach { action -> rejected(action) }
            assertEquals(0, server.requestCount)
            assertEquals(replacement, store.load())
        } finally { api.close(); server.shutdown() }
    }

    @Test fun pendingFactorReadCannotRemoveAFactorWithAReplacementToken() = runBlocking {
        val store = MemorySessionStore(session(SID_A))
        val replacement = session(SID_B)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("GET", request.method)
                    assertEquals("/auth/v1/user", request.requestUrl!!.encodedPath)
                    store.save(replacement)
                    return MockResponse().setBody("""{"factors":[{"id":"$FACTOR","factor_type":"totp","status":"unverified"}]}""")
                }
            }
            start()
        }
        val api = SupabaseApi(BackendConfig(server.url("/").toString(), "fixture-public"), store)
        try {
            rejected { api.unenrollPendingTotp(FACTOR) }
            assertEquals(1, server.requestCount)
            assertEquals(replacement, store.load())
        } finally { api.close(); server.shutdown() }
    }

    @Test fun profileReadRejectsReplacementBeforePublishingEvenWhenTheUserIsUnchanged() = runBlocking {
        val store = MemorySessionStore(session(SID_A))
        val replacement = session(SID_B)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("eq.$USER", request.requestUrl!!.queryParameter("user_id"))
                    assertEquals("Bearer ${session(SID_A).accessToken}", request.getHeader("Authorization"))
                    store.save(replacement)
                    return MockResponse().setBody("""[{"user_id":"$USER","full_name":"Old profile"}]""")
                }
            }
            start()
        }
        val api = SupabaseApi(BackendConfig(server.url("/").toString(), "fixture-public"), store)
        try {
            rejected { api.profile() }
            assertEquals(1, server.requestCount)
            assertEquals(replacement, store.load())
        } finally { api.close(); server.shutdown() }
    }

    @Test fun invitationStopsAfterLookupWhenTheLoginChanges() = runBlocking {
        val store = MemorySessionStore(session(SID_A))
        val replacement = session(SID_B)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("/rest/v1/rpc/find_user_by_couple_code", request.requestUrl!!.encodedPath)
                    store.save(replacement)
                    return MockResponse().setBody("\"$PARTNER\"")
                }
            }
            start()
        }
        val api = SupabaseApi(BackendConfig(server.url("/").toString(), "fixture-public"), store)
        try {
            rejected { CoupleRepository(api).sendInvitation("ABCD2345") }
            assertEquals(1, server.requestCount)
            assertEquals(replacement, store.load())
        } finally { api.close(); server.shutdown() }
    }

    @Test fun invitationStopsAfterPendingReadWithoutAnyInsert() = runBlocking {
        val store = MemorySessionStore(session(SID_A))
        val replacement = session(SID_B)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return if (request.requestUrl!!.encodedPath.endsWith("find_user_by_couple_code"))
                        MockResponse().setBody("\"$PARTNER\"")
                    else {
                        assertEquals("GET", request.method)
                        store.save(replacement)
                        MockResponse().setBody("[]")
                    }
                }
            }
            start()
        }
        val api = SupabaseApi(BackendConfig(server.url("/").toString(), "fixture-public"), store)
        try {
            rejected { CoupleRepository(api).sendInvitation("ABCD2345") }
            assertEquals(2, server.requestCount)
            assertEquals(replacement, store.load())
        } finally { api.close(); server.shutdown() }
    }

    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Expected stale owner rejection") }
        catch (error: ApiException) { assertEquals(ApiError.PERMISSION, error.kind) }
    }

    private fun session(id: String): Session {
        val claims = Base64.getUrlEncoder().withoutPadding().encodeToString(
            JSONObject().put("session_id", id).put("aal", "aal1").toString().toByteArray())
        return Session("synthetic.$claims.signature", "synthetic-refresh-$id", System.currentTimeMillis() / 1000 + 3600, USER, "qa@example.test")
    }

    private companion object {
        const val USER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val PARTNER = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val SID_A = "11111111-1111-4111-8111-111111111111"
        const val SID_B = "22222222-2222-4222-8222-222222222222"
        const val FACTOR = "33333333-3333-4333-8333-333333333333"
    }
}
