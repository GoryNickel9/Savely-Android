package cc.savely.android.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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

    @Test fun productionApiAliasUsesCanonicalTlsTransportWithoutChangingSignupCallbackOrRecoveryOrigin() = runBlocking {
        withWebTlsFixture { webClient, fixture, observed ->
            store.save(liveSession())
            fixture.enqueue(json("""{"ok":true}"""))
            webClient.requestPasswordReset("person@example.com")
            val reset = requireNotNull(fixture.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("https://www.savely.cc/api/reset-password", observed[0].toString())
            assertEquals("POST", reset.method)
            assertEquals("/api/reset-password", reset.path)
            assertEquals("https://savely.cc", reset.getHeader("Origin"))
            assertNull(reset.getHeader("Authorization"))
            assertNull(reset.getHeader("apikey"))

            fixture.enqueue(json("""{"users":[]}"""))
            webClient.webPost("/api/admin", JSONObject().put("action", "get-users"))
            val admin = requireNotNull(fixture.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("https://www.savely.cc/api/admin", observed[1].toString())
            assertEquals("Bearer access", admin.getHeader("Authorization"))
            assertEquals("https://savely.cc", admin.getHeader("Origin"))
            assertNull(admin.getHeader("apikey"))

            fixture.enqueue(json("true"))
            fixture.enqueue(json("""{"user":{"id":"user-1"}}"""))
            webClient.signUp("person@example.com", "StrongPassword12!")
            assertEquals("/rest/v1/rpc/get_registrations_enabled", fixture.takeRequest(5, TimeUnit.SECONDS)?.path)
            val signup = requireNotNull(fixture.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/auth/v1/signup", signup.requestUrl?.encodedPath)
            assertEquals("https://savely.cc/auth/callback", signup.requestUrl?.queryParameter("redirect_to"))
            assertEquals(fixture.url("/").host, observed[3].host)
            assertEquals(4, fixture.requestCount)
        }
    }

    @Test fun canonicalWebApiRejectsInvalidPathsAndRedirectsWithoutForwardingCredentials() = runBlocking {
        withWebTlsFixture { webClient, fixture, observed ->
            store.save(liveSession())
            for (path in listOf("https://untrusted.invalid/api/admin", "/api/../admin", "/api/admin?other=1", "//untrusted.invalid/api/admin")) {
                assertEquals(ApiError.VALIDATION, failure { webClient.webPost(path, JSONObject()) }.kind)
            }
            assertEquals(0, fixture.requestCount)
            assertTrue(observed.isEmpty())
            fixture.enqueue(MockResponse().setResponseCode(308).setHeader("Location", "https://untrusted.invalid/collect"))
            assertEquals(308, failure { webClient.webPost("/api/admin", JSONObject()) }.status)
            val request = requireNotNull(fixture.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("Bearer access", request.getHeader("Authorization"))
            assertEquals("https://www.savely.cc/api/admin", observed.single().toString())
            assertEquals(1, fixture.requestCount)
            assertEquals(liveSession().accessToken, store.load()?.accessToken)
        }
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

    @Test fun settingsCanReadPendingTotpWithoutChangingTheAccessGateOrSession() = runBlocking {
        val saved = liveSession().also { store.save(it) }
        val factors = """{"factors":[{"id":"active","factor_type":"totp","status":"verified"},{"id":"pending","factor_type":"totp","status":"unverified"},{"id":"phone","factor_type":"phone","status":"verified"},{"id":"unknown","factor_type":"totp","status":"unknown"}]}"""
        server.enqueue(json(factors))
        val settingsFactors = api.listMfaFactors(includeUnverified = true)
        assertEquals(listOf("active", "pending"), (0 until settingsFactors.length()).map { settingsFactors.getJSONObject(it).getString("id") })
        server.enqueue(json(factors))
        val gateFactors = api.listMfaFactors()
        assertEquals(1, gateFactors.length())
        assertEquals("active", gateFactors.getJSONObject(0).getString("id"))
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/auth/v1/user", request.path)
            assertEquals("Bearer ${saved.accessToken}", request.getHeader("Authorization"))
        }
        assertEquals(2, server.requestCount)
        assertEquals(saved, store.load())
    }

    @Test fun pendingRemovalRefusesAFactorThatHasAlreadyBecomeVerified() = runBlocking {
        val saved = liveSession().also { store.save(it) }
        server.enqueue(json("""{"factors":[{"id":"pending-1","factor_type":"totp","status":"verified"}]}"""))
        assertEquals(ApiError.VALIDATION, failure { api.unenrollPendingTotp("pending-1") }.kind)
        assertEquals("GET", server.takeRequest().method)
        assertEquals(1, server.requestCount)
        assertEquals(saved, store.load())
    }

    @Test fun invalidMfaCodeFailsBeforeChallenge() = runBlocking {
        store.save(liveSession())
        assertEquals(ApiError.VALIDATION, failure { api.verifyTotp("factor-1", "12345") }.kind)
        assertEquals(0, server.requestCount)
    }

    @Test fun malformedEnrollmentFieldsFailBeforePublishingSetupWithoutRetryOrSecretDisclosure() = runBlocking {
        val saved = liveSession().also { store.save(it) }
        val privateSecret = "PRIVATE_SYNTHETIC_SECRET"
        val uri = "otpauth://totp/Synthetic?secret=$privateSecret"
        fun valid() = JSONObject().put("id", "factor-1")
            .put("totp", JSONObject().put("secret", privateSecret).put("uri", uri))
        val malformed = mutableListOf<String>("[]", "{}", "{not-json", """{"id":"factor-1"}""")
        malformed += valid().apply { remove("id") }.toString()
        for (invalid in listOf(JSONObject.NULL, 42, true, JSONObject(), JSONArray(), "", " \t")) {
            malformed += valid().put("id", invalid).toString()
            malformed += valid().put("totp", invalid).toString()
            malformed += valid().apply { getJSONObject("totp").put("secret", invalid) }.toString()
            malformed += valid().apply { getJSONObject("totp").put("uri", invalid) }.toString()
        }
        malformed += valid().apply { getJSONObject("totp").remove("secret") }.toString()
        malformed += valid().apply { getJSONObject("totp").remove("uri") }.toString()
        malformed += valid().put("id", "factor/foreign").toString()
        malformed += valid().put("id", "x".repeat(129)).toString()
        for ((index, body) in malformed.withIndex()) {
            server.enqueue(json(body))
            val error = failure { api.enrollTotp() }
            assertEquals("Payload $index", ApiError.RESPONSE, error.kind)
            assertEquals("Il servizio ha restituito dati non validi.", error.message)
            assertFalse(error.toString().contains(privateSecret))
            assertFalse(error.toString().contains("otpauth"))
            assertEquals(saved, store.load())
            assertEquals(index + 1, server.requestCount)
            assertEnrollmentPost()
        }
    }

    @Test fun enrollmentRequiredTextBoundsRejectOversizeAndKeepBoundaryValuesUnchanged() = runBlocking {
        val saved = liveSession().also { store.save(it) }
        for ((secretLength, uriLength) in listOf(4097 to 1, 1 to 16_385)) {
            server.enqueue(json(JSONObject().put("id", "factor-1").put("totp", JSONObject()
                .put("secret", "S".repeat(secretLength)).put("uri", "U".repeat(uriLength))).toString()))
            assertEquals(ApiError.RESPONSE, failure { api.enrollTotp() }.kind)
            assertEnrollmentPost()
        }
        val boundary = JSONObject().put("id", "factor-1").put("totp", JSONObject()
            .put("secret", "S".repeat(4096)).put("uri", "U".repeat(16_384)))
        server.enqueue(json(boundary.toString()))
        val accepted = api.enrollTotp()
        assertEquals(boundary.toString(), accepted.toString())
        assertEnrollmentPost()
        assertEquals(3, server.requestCount)
        assertEquals(saved, store.load())
    }

    @Test fun validEnrollmentPreservesManualSetupWithAbsentOrInvalidOptionalQr() = runBlocking {
        val saved = liveSession().also { store.save(it) }
        val secret = "JBSWY3DPEHPK3PXP"
        val uri = "otpauth://totp/Synthetic%20Issuer:person?secret=$secret&issuer=Synthetic%20Issuer"
        for ((index, qr) in listOf(null, JSONObject.NULL, "invalid SVG").withIndex()) {
            val totp = JSONObject().put("secret", secret).put("uri", uri)
            if (qr != null) totp.put("qr_code", qr)
            val enrollment = JSONObject().put("id", "factor-1").put("totp", totp)
            server.enqueue(json(enrollment.toString()))
            val accepted = api.enrollTotp()
            assertEquals("factor-1", accepted.getString("id"))
            assertEquals(secret, accepted.getJSONObject("totp").getString("secret"))
            assertEquals(uri, accepted.getJSONObject("totp").getString("uri"))
            assertEquals(enrollment.toString(), accepted.toString())
            assertEquals(index + 1, server.requestCount)
            assertEnrollmentPost()
            assertEquals(saved, store.load())
        }
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

    private fun assertEnrollmentPost() {
        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/auth/v1/factors", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization"))
        assertEquals("public-key", request.getHeader("apikey"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(setOf("factor_type", "friendly_name"), body.keys().asSequence().toSet())
        assertEquals("totp", body.getString("factor_type"))
        assertEquals("Savely Android", body.getString("friendly_name"))
    }

    private suspend fun withWebTlsFixture(block: suspend (SupabaseApi, MockWebServer, List<HttpUrl>) -> Unit) {
        val certificate = HeldCertificate.Builder().commonName("Savely web API fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val fixture = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); start() }
        val observed = CopyOnWriteArrayList<HttpUrl>()
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                observed.add(request.url)
                // Capture the production URL, then route only the known API host to the local trusted TLS server.
                val localRequest = when (request.url.host) {
                    "www.savely.cc" -> request.newBuilder().url(request.url.newBuilder()
                        .host(fixture.url("/").host).port(fixture.port).build()).build()
                    fixture.url("/").host -> request
                    else -> throw java.io.IOException("Unexpected fixture origin")
                }
                chain.proceed(localRequest)
            }.build()
        val webClient = SupabaseApi(BackendConfig(fixture.url("/").toString(), "public-key", "https://savely.cc"), store, client)
        try { block(webClient, fixture, observed) }
        finally {
            webClient.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            fixture.shutdown()
        }
    }

    private fun json(body: String, status: Int = 200): MockResponse = MockResponse()
        .setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun liveSession() = Session("access", "refresh", System.currentTimeMillis() / 1000 + 3600, "user-1", "person@example.com")
    private fun authResponse(token: String = "access"): String = JSONObject()
        .put("access_token", token).put("refresh_token", "refresh").put("expires_in", 3600)
        .put("user", JSONObject().put("id", "user-1").put("email", "person@example.com")).toString()
}
