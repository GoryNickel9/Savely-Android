package cc.savely.android.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

class SupabaseApiTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemorySessionStore
    private lateinit var api: SupabaseApi

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        store = MemorySessionStore()
        api = SupabaseApi(BackendConfig(server.url("/").toString(), "public-key", server.url("/").toString()), store)
    }
    @After fun tearDown() { api.close(); server.shutdown() }

    @Test fun signInSavesSessionAndUsesPublicKey() = runBlocking {
        server.enqueue(json(authResponse()))
        val session = api.signIn(" person@example.com ", "password")
        assertEquals("user-1", session.userId)
        assertEquals(session, store.load())
        val request = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=password", request.path)
        assertEquals("public-key", request.getHeader("apikey"))
        assertEquals("Bearer public-key", request.getHeader("Authorization"))
        assertEquals("person@example.com", JSONObject(request.body.readUtf8()).getString("email"))
    }

    @Test fun failedLoginDoesNotDestroyExistingSessionOrExposeServerMessage() = runBlocking {
        val old = liveSession().also { store.save(it) }
        server.enqueue(json("""{"code":"invalid_credentials","msg":"private database detail password"}""", 400))
        val error = failure { api.signIn("person@example.com", "wrong") }
        assertEquals("Email o password non corrette.", error.message)
        assertEquals(old, store.load())
        assertFalse(error.toString().contains("private database"))
    }

    @Test fun expiredSessionIsRefreshedExactlyOnceAcrossConcurrentCalls() = runBlocking {
        store.save(liveSession().copy(expiresAt = 1))
        server.enqueue(json(authResponse("renewed")))
        val sessions = coroutineScope { (1..12).map { async { api.session() } }.awaitAll() }
        assertTrue(sessions.all { it?.accessToken == "renewed" })
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=refresh_token", request.path)
        assertEquals("refresh", JSONObject(request.body.readUtf8()).getString("refresh_token"))
    }

    @Test fun refreshServerFailurePreservesCredentials() = runBlocking {
        val old = liveSession().copy(expiresAt = 1).also { store.save(it) }
        server.enqueue(json("""{"message":"internal secret"}""", 503))
        assertEquals(ApiError.SERVER, failure { api.session() }.kind)
        assertEquals(old, store.load())
    }

    @Test fun disconnectedRefreshDoesNotClearLocalSession() = runBlocking {
        val old = liveSession().copy(expiresAt = 1).also { store.save(it) }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(ApiError.NETWORK, failure { api.session() }.kind)
        assertEquals(old, store.load())
    }

    @Test fun explicitlyRevokedRefreshClearsSession() = runBlocking {
        store.save(liveSession().copy(expiresAt = 1))
        server.enqueue(json("""{"code":"refresh_token_not_found"}""", 400))
        assertEquals(ApiError.AUTHENTICATION, failure { api.session() }.kind)
        assertNull(store.load())
    }

    @Test fun unauthorizedQueryRefreshesAndRetriesWithRenewedUserToken() = runBlocking {
        store.save(liveSession())
        server.enqueue(json("""{"code":"PGRST301"}""", 401))
        server.enqueue(json(authResponse("renewed")))
        server.enqueue(json("[]"))
        assertEquals(0, api.select("transactions", "select=id&order=date.desc").length())
        assertEquals("Bearer access", server.takeRequest().getHeader("Authorization"))
        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        assertEquals("Bearer renewed", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun writesCarryOwnerAndIdFiltersAndRequireReturnedRecord() = runBlocking {
        store.save(liveSession())
        server.enqueue(json("""[{"id":"record-1","amount":12.50}]"""))
        val result = api.update("transactions", "record-1", JSONObject().put("amount", 12.50), "user_id=eq.user-1")
        assertEquals("record-1", result.getString("id"))
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("eq.user-1", request.requestUrl?.queryParameter("user_id"))
        assertEquals("eq.record-1", request.requestUrl?.queryParameter("id"))
        assertEquals("return=representation", request.getHeader("Prefer"))
        server.enqueue(json("[]"))
        assertEquals(ApiError.PERMISSION, failure { api.delete("transactions", "record-1") }.kind)
    }

    @Test fun rejectsPathAndFilterInjectionBeforeNetworkRequest() = runBlocking {
        store.save(liveSession())
        assertEquals(ApiError.VALIDATION, failure { api.select("../auth/v1/user") }.kind)
        assertEquals(ApiError.VALIDATION, failure { api.delete("transactions", "abc&user_id=neq.x") }.kind)
        assertEquals(ApiError.VALIDATION, failure { api.update("transactions", "abc", JSONObject(), "id=neq.x") }.kind)
        assertEquals(ApiError.VALIDATION, failure { api.webPost("https://evil.test", JSONObject()) }.kind)
        assertEquals(0, server.requestCount)
    }

    @Test fun resetUsesExistingBrandedWebEndpointWithoutToken() = runBlocking {
        store.save(liveSession())
        server.enqueue(json("""{"ok":true}"""))
        api.requestPasswordReset("person@example.com")
        val request = server.takeRequest()
        assertEquals("/api/reset-password", request.path)
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("apikey"))
        assertEquals(server.url("/").toString().removeSuffix("/"), request.getHeader("Origin"))
    }

    @Test fun signupChecksPublicRegistrationToggleAndDoesNotClaimNativeCallback() = runBlocking {
        server.enqueue(json("true"))
        server.enqueue(json("""{"user":{"id":"user-1"}}"""))
        assertTrue(api.signUp("person@example.com", "StrongPassword12!", "Person").contains("email"))
        assertNull(store.load())
        assertEquals("/rest/v1/rpc/get_registrations_enabled", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals(server.url("/auth/callback").toString(), request.requestUrl?.queryParameter("redirect_to"))
        assertEquals("Person", JSONObject(request.body.readUtf8()).getJSONObject("data").getString("full_name"))
    }

    @Test fun registrationDisabledDoesNotAttemptSignup() = runBlocking {
        server.enqueue(json("false"))
        assertEquals(ApiError.PERMISSION, failure { api.signUp("person@example.com", "StrongPassword12!") }.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun rpcPreservesScalarAndObjectAndArrayResults() = runBlocking {
        store.save(liveSession())
        server.enqueue(json("true"))
        server.enqueue(json("""{"pending":2}"""))
        server.enqueue(json("""[{"id":"one"}]"""))
        server.enqueue(json("null"))
        assertEquals(true, api.rpc("get_registrations_enabled"))
        assertEquals(2, (api.rpc("example_object") as JSONObject).getInt("pending"))
        assertEquals(1, (api.rpc("example_array") as JSONArray).length())
        assertEquals(JSONObject.NULL, api.rpc("example_void"))
    }

    @Test fun permissionsRequireActualBooleanAndNetworkFailureIsNotDefaultPermission() = runBlocking {
        assertEquals(false, SupabaseApi.parsePermissions(JSONObject("""{"admin":"true","poker":1,"tcg":true}"""))["admin"])
        assertEquals(false, SupabaseApi.parsePermissions(JSONObject("""{"poker":1}"""))["poker"])
        assertEquals(true, SupabaseApi.parsePermissions(JSONObject("""{"tcg":true}"""))["tcg"])
        store.save(liveSession())
        server.enqueue(json("""[{"permissions":{"fire":true}}]"""))
        assertEquals(true, api.permissions()["fire"])
        assertEquals("eq.user-1", server.takeRequest().requestUrl?.queryParameter("user_id"))
        server.enqueue(json("{}", 503))
        assertEquals(ApiError.SERVER, failure { api.permissions() }.kind)
    }

    @Test fun mfaListsVerifiedTotpOnlyAndVerificationPersistsNewTokens() = runBlocking {
        store.save(liveSession())
        server.enqueue(json("""{"factors":[{"id":"yes","factor_type":"totp","status":"verified"},{"id":"no","factor_type":"totp","status":"unverified"},{"id":"phone","factor_type":"phone","status":"verified"}]}"""))
        assertEquals(1, api.listMfaFactors().length())
        server.takeRequest()
        server.enqueue(json("""{"id":"challenge-1"}"""))
        server.enqueue(json(authResponse("aal2-token")))
        val session = api.verifyTotp("factor-1", "123-456")
        assertEquals("aal2-token", session.accessToken)
        assertEquals(session, store.load())
        assertEquals("/auth/v1/factors/factor-1/challenge", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("/auth/v1/factors/factor-1/verify", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("123456", body.getString("code"))
        assertEquals("challenge-1", body.getString("challenge_id"))
    }

    @Test fun invalidMfaCodeFailsBeforeChallenge() = runBlocking {
        store.save(liveSession())
        assertEquals(ApiError.VALIDATION, failure { api.verifyTotp("factor-1", "12345") }.kind)
        assertEquals(0, server.requestCount)
    }

    @Test fun minimalSoftDeleteChecksAffectedCountWithoutSelectingHiddenRow() = runBlocking {
        store.save(liveSession())
        server.enqueue(MockResponse().setResponseCode(204).setHeader("Content-Range", "*/1"))
        api.updateMinimal("categories", "record-1", JSONObject().put("deleted_at", "2026-10-11T12:00:00Z"), "user_id=eq.user-1")
        val request = server.takeRequest()
        assertEquals("return=minimal,count=exact", request.getHeader("Prefer"))
        assertEquals("PATCH", request.method)
        server.enqueue(MockResponse().setResponseCode(204).setHeader("Content-Range", "*/0"))
        assertEquals(ApiError.PERMISSION, failure { api.updateMinimal("categories", "missing", JSONObject()) }.kind)
    }

    @Test fun edgeFunctionGetPreservesQueryAndSendsCurrentUserCredentials() = runBlocking {
        store.save(liveSession())
        server.enqueue(json("""{"blueprints":[]}"""))
        assertTrue(api.getFunction("cardtrader-proxy", "action=blueprints&expansion_id=23") is JSONObject)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/functions/v1/cardtrader-proxy?action=blueprints&expansion_id=23", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization"))
    }

    @Test fun logoutAlwaysClearsLocalSessionAndPreservesFailureSignal() = runBlocking {
        store.save(liveSession())
        server.enqueue(json("{}", 503))
        assertEquals(ApiError.SERVER, failure { api.signOut() }.kind)
        assertNull(store.load())
        assertEquals("/auth/v1/logout?scope=local", server.takeRequest().path)
    }

    @Test fun redirectsDoNotLeakUserTokenToAnotherOrigin() = runBlocking {
        store.save(liveSession())
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://untrusted.invalid/collect"))
        assertEquals(302, failure { api.select("profiles") }.status)
        assertEquals(1, server.requestCount)
    }

    @Test fun malformedSuccessfulPayloadIsReportedWithoutLosingSession() = runBlocking {
        val current = liveSession().also { store.save(it) }
        server.enqueue(json("[] trailing"))
        assertEquals(ApiError.RESPONSE, failure { api.select("profiles") }.kind)
        assertEquals(current, store.load())
    }

    @Test fun privilegedConfigurationAndRemoteCleartextAreRejected() {
        val serviceJwt = "eyJ0eXAiOiJKV1QifQ." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"role":"service_role"}""".toByteArray()) + ".signature"
        for (key in listOf("sb_secret_private", serviceJwt)) {
            try {
                SupabaseApi(BackendConfig("https://example.supabase.co", key), store)
                fail("Privileged key accepted")
            } catch (error: ApiException) { assertEquals(ApiError.CONFIGURATION, error.kind) }
        }
        try {
            SupabaseApi(BackendConfig("http://example.supabase.co", "public"), store)
            fail("Cleartext remote URL accepted")
        } catch (error: ApiException) { assertEquals(ApiError.CONFIGURATION, error.kind) }
        assertFalse(liveSession().toString().contains("refresh"))
        assertFalse(BackendConfig("https://example.supabase.co", "private-key").toString().contains("private-key"))
    }

    private suspend fun failure(block: suspend () -> Any?): ApiException {
        try { block(); fail("Expected ApiException") }
        catch (error: ApiException) { return error }
        throw AssertionError("Unreachable")
    }

    private fun json(body: String, status: Int = 200): MockResponse = MockResponse()
        .setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun liveSession() = Session("access", "refresh", System.currentTimeMillis() / 1000 + 3600, "user-1", "person@example.com")
    private fun authResponse(token: String = "access"): String = JSONObject()
        .put("access_token", token).put("refresh_token", "refresh").put("expires_in", 3600)
        .put("user", JSONObject().put("id", "user-1").put("email", "person@example.com")).toString()
}
