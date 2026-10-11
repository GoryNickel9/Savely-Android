package cc.savely.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** TLS localhost fixtures only. All users, tokens, links and passwords are synthetic. */
class NativeAuthApiTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private val ordinary = MemorySessionStore()
    private val links = MemoryAuthLinkStore()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var exchangeStatus = 200
    private var updateStatus = 200
    private var userEmail = EMAIL
    private var userId = USER
    private var aal = "aal1"
    private var verifiedFactor = false
    private var exchangeDisconnect = false

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Savely native PKCE fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("::1").build()
        server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path == "/rest/v1/rpc/get_registrations_enabled" -> json("true")
                        path == "/auth/v1/signup" || path == "/auth/v1/recover" -> json("{}")
                        path == "/auth/v1/token" && request.requestUrl!!.queryParameter("grant_type") == "pkce" ->
                            if (exchangeDisconnect) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                            else json(if (exchangeStatus == 200) session().toJson().toString() else "{\"code\":\"bad_code_verifier\"}", exchangeStatus)
                        path == "/auth/v1/token" -> json(session().toJson().toString())
                        path == "/auth/v1/user" && request.method == "PUT" -> json("{}", updateStatus)
                        path == "/auth/v1/user" -> json(JSONObject().put("id", userId).put("email", userEmail)
                            .put("email_confirmed_at", "2026-10-11T00:00:00Z").put("factors", JSONArray().apply {
                                if (verifiedFactor) put(JSONObject().put("id", FACTOR).put("status", "verified").put("factor_type", "totp"))
                            }).toString())
                        path.endsWith("/challenge") -> json("{\"id\":\"challenge\"}")
                        path.endsWith("/verify") -> { aal = "aal2"; json(session().toJson().toString()) }
                        path == "/auth/v1/logout" -> json("{}")
                        path == "/rest/v1/login_activity" -> json("[]", 201)
                        else -> json("{}", 500)
                    }
                }
            }
            start()
        }
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .readTimeout(2, TimeUnit.SECONDS).build()
        api = newApi()
    }
    @After fun teardown() { api.close(); server.shutdown() }
    private fun newApi() = SupabaseApi(BackendConfig(server.url("/").toString(), "public-test-key"), ordinary, client, links)

    @Test fun nativeEmailsUsePkceAndExactDeviceRedirectWithoutChangingLegacyApi() = runBlocking {
        api.signUpNative(EMAIL, PASSWORD, "QA")
        val signup = requests.single { it.requestUrl!!.encodedPath == "/auth/v1/signup" }
        assertPkce(signup, links.pending()!!)
        assertEquals("QA", JSONObject(signup.body.clone().readUtf8()).getJSONObject("data").getString("full_name"))
        assertNull(ordinary.load())
        api.requestNativePasswordReset(EMAIL)
        val recovery = requests.single { it.requestUrl!!.encodedPath == "/auth/v1/recover" }
        assertPkce(recovery, links.pending()!!)
        assertEquals("Bearer public-test-key", recovery.getHeader("Authorization"))
        assertEquals(0, requests.count { it.requestUrl!!.encodedPath.startsWith("/api/") })
    }

    @Test fun invalidPasswordOrMismatchMakesZeroHttpRequests() = runBlocking {
        expect(ApiError.VALIDATION) { api.signUpNative(EMAIL, "weak") }
        expect(ApiError.VALIDATION) { api.completePasswordRecovery(PASSWORD, "different") }
        assertEquals(0, server.requestCount)
    }

    @Test fun recoveryExchangeSurvivesApiRecreationButCannotBeReused() = runBlocking {
        val pending = startRecovery()
        api.close(); api = newApi()
        val result = api.completeNativeCallback(callback(pending))
        assertEquals(NativeAuthPurpose.RECOVERY, result.purpose)
        assertNull(ordinary.load())
        assertEquals(result.session, api.session())
        assertTrue(api.isPasswordRecovery(result.session))
        assertNull(links.pending())
        assertEquals(result.session, links.recovery()!!.session)
        val exchange = requests.single { it.requestUrl!!.queryParameter("grant_type") == "pkce" }
        val body = JSONObject(exchange.body.clone().readUtf8())
        assertEquals(CODE, body.getString("auth_code"))
        assertEquals(pending.verifier, body.getString("code_verifier"))
        expect(ApiError.VALIDATION) { api.completeNativeCallback(callback(pending)) }
        assertEquals(1, requests.count { it.requestUrl!!.queryParameter("grant_type") == "pkce" })
    }

    @Test fun expiredWrongStateAndProviderErrorDoNotRedeemCode() = runBlocking {
        val pending = startRecovery()
        expect(ApiError.VALIDATION) { api.completeNativeCallback(NativeAuthCallback.parse("savely://auth/callback?state=$SID&code=$CODE")) }
        assertSame(pending, links.pending())
        links.savePending(PendingNativeAuth(pending.state, pending.purpose, pending.email, pending.verifier, 1))
        expect(ApiError.VALIDATION) { api.completeNativeCallback(callback(pending)) }
        assertNull(links.pending())
        val fresh = startRecovery()
        expect(ApiError.VALIDATION) { api.completeNativeCallback(NativeAuthCallback.parse("savely://auth/callback?state=${fresh.state}&error=access_denied")) }
        assertEquals(0, requests.count { it.requestUrl!!.queryParameter("grant_type") == "pkce" })
    }

    @Test fun existingLoginRejectsCallbackAndRetainsThatAccount() = runBlocking {
        val pending = startRecovery()
        val existing = session()
        ordinary.save(existing)
        expect(ApiError.VALIDATION) { api.completeNativeCallback(callback(pending)) }
        assertEquals(existing, ordinary.load())
        assertSame(pending, links.pending())
        assertEquals(0, requests.count { it.requestUrl!!.queryParameter("grant_type") == "pkce" })
    }

    @Test fun serverRejectedAndAmbiguousExchangeCannotAutomaticallyRetry() = runBlocking {
        for (status in listOf(400, 503)) {
            val pending = startRecovery(); exchangeStatus = status
            expect(if (status == 400) ApiError.VALIDATION else ApiError.SERVER) { api.completeNativeCallback(callback(pending)) }
            assertNull(links.pending()); assertNull(ordinary.load()); assertNull(links.recovery())
            expect(ApiError.VALIDATION) { api.completeNativeCallback(callback(pending)) }
        }
        assertEquals(2, requests.count { it.requestUrl!!.queryParameter("grant_type") == "pkce" })
    }

    @Test fun disconnectedExchangeIsNotRepeatedEvenWhenCodeMayHaveBeenConsumed() = runBlocking {
        val pending = startRecovery(); exchangeDisconnect = true
        expect(ApiError.NETWORK) { api.completeNativeCallback(callback(pending)) }
        expect(ApiError.VALIDATION) { api.completeNativeCallback(callback(pending)) }
        assertNull(links.recovery()); assertNull(ordinary.load())
        assertEquals(1, requests.count { it.requestUrl!!.queryParameter("grant_type") == "pkce" })
    }

    @Test fun serverIdentityMismatchIsNeverStoredAndOnlyRedeemedSessionIsLoggedOut() = runBlocking {
        val pending = startRecovery(); userEmail = "other@example.test"
        expect(ApiError.VALIDATION) { api.completeNativeCallback(callback(pending)) }
        assertNull(ordinary.load()); assertNull(links.recovery())
        val logout = requests.single { it.requestUrl!!.encodedPath == "/auth/v1/logout" }
        assertEquals("local", logout.requestUrl!!.queryParameter("scope"))
        assertEquals("Bearer ${session().accessToken}", logout.getHeader("Authorization"))
    }

    @Test fun passwordFailureKeepsRecoveryGateAndSuccessPromotesOnlyConfirmedSession() = runBlocking {
        val recovered = api.completeNativeCallback(callback(startRecovery())).session
        for (status in listOf(400, 503)) {
            updateStatus = status
            expect(if (status == 400) ApiError.VALIDATION else ApiError.SERVER) { api.completePasswordRecovery(PASSWORD, PASSWORD) }
            assertNull(ordinary.load()); assertTrue(api.isPasswordRecovery(recovered))
            assertEquals(recovered, api.session())
        }
        updateStatus = 200
        assertEquals(recovered, api.completePasswordRecovery(PASSWORD, PASSWORD))
        assertEquals(recovered, ordinary.load()); assertNull(links.recovery())
        assertFalse(api.isPasswordRecovery(recovered))
        assertEquals(3, requests.count { it.method == "PUT" })
        assertEquals(0, requests.count { it.requestUrl!!.encodedPath == "/auth/v1/logout" })
    }

    @Test fun verifiedFactorBlocksPasswordPutUntilFreshChallengeElevatesSameRecoverySession() = runBlocking {
        api.completeNativeCallback(callback(startRecovery())); verifiedFactor = true
        expect(ApiError.VALIDATION) { api.completePasswordRecovery(PASSWORD, PASSWORD) }
        assertEquals(0, requests.count { it.method == "PUT" })
        val elevated = api.verifyTotp(FACTOR, "123456")
        assertTrue(api.isPasswordRecovery(elevated)); assertNull(ordinary.load())
        assertEquals(SID, elevated.authSessionId())
        api.completePasswordRecovery(PASSWORD, PASSWORD)
        assertEquals(1, requests.count { it.method == "PUT" })
        assertEquals(elevated, ordinary.load())
    }

    @Test fun cancellationUsesLocalScopeAndNeverClearsDifferentOrdinarySession() = runBlocking {
        val recovered = api.completeNativeCallback(callback(startRecovery())).session
        val other = recovered.copy(userId = "33333333-3333-4333-8333-333333333333")
        ordinary.save(other)
        assertFalse(api.cancelPasswordRecovery()); assertEquals(other, ordinary.load())
        assertEquals(0, requests.count { it.requestUrl!!.encodedPath == "/auth/v1/logout" })
        ordinary.clear()
        assertTrue(api.cancelPasswordRecovery()); assertNull(api.session()); assertNull(links.recovery())
        assertEquals("local", requests.single { it.requestUrl!!.encodedPath == "/auth/v1/logout" }.requestUrl!!.queryParameter("scope"))
    }

    @Test fun refreshRotatesRecoveryTokensWithoutPromotingAccountAccess() = runBlocking {
        api.completeNativeCallback(callback(startRecovery()))
        val old = links.recovery()!!
        links.saveRecovery(NativeRecoveryContext(old.session.copy(expiresAt = 1), old.createdAt))
        val refreshed = api.session()!!
        assertTrue(api.isPasswordRecovery(refreshed)); assertNull(ordinary.load())
        assertEquals(SID, refreshed.authSessionId())
        assertEquals(1, requests.count { it.requestUrl!!.queryParameter("grant_type") == "refresh_token" })
    }

    private suspend fun startRecovery(): PendingNativeAuth { api.requestNativePasswordReset(EMAIL); return links.pending()!! }
    private fun callback(pending: PendingNativeAuth) = NativeAuthCallback.parse("${pending.redirectUrl}&code=$CODE")
    private fun assertPkce(request: RecordedRequest, pending: PendingNativeAuth) {
        assertEquals(pending.redirectUrl, request.requestUrl!!.queryParameter("redirect_to"))
        val body = JSONObject(request.body.clone().readUtf8())
        assertEquals(pending.challenge, body.getString("code_challenge")); assertEquals("s256", body.getString("code_challenge_method"))
        assertFalse(body.toString().contains(pending.verifier))
    }
    private fun session(): Session {
        val payload = JSONObject().put("session_id", SID).put("aal", aal).toString()
        val token = "header.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())}.signature"
        return Session(token, "synthetic-refresh", System.currentTimeMillis() / 1000 + 3600, USER, EMAIL)
    }
    private suspend fun expect(kind: ApiError, action: suspend () -> Unit) {
        try { action(); fail("Expected failure") } catch (error: ApiException) { assertEquals(kind, error.kind) }
    }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private companion object {
        const val EMAIL = "qa@example.test"
        const val USER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SID = "11111111-1111-4111-8111-111111111111"
        const val CODE = "22222222-2222-4222-8222-222222222222"
        const val FACTOR = "33333333-3333-4333-8333-333333333333"
        const val PASSWORD = "SyntheticPassword123!"
    }
}
