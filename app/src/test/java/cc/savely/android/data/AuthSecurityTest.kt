package cc.savely.android.data

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AuthSecurityTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemorySessionStore
    private lateinit var api: SupabaseApi

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        store = MemorySessionStore()
        api = SupabaseApi(BackendConfig(server.url("/").toString(), "public-key"), store)
    }
    @After fun teardown() { api.close(); server.shutdown() }

    @Test fun newPasswordPolicyRejectsEachMissingRequirementBeforeAnyHttpOrRefresh() = runBlocking {
        val previous = session().copy(expiresAt = 1).also(store::save)
        val invalid = listOf("Short1!", "lowercase123!", "UPPERCASE123!", "NoNumbersHere!", "NoSymbolHere123", "Password12345💰")
        for (password in invalid) {
            assertNotNull(PasswordPolicy.error(password))
            assertValidation { api.signUp("person@example.test", password) }
            assertValidation { api.updateAccount(password = password) }
        }
        assertEquals(0, server.requestCount)
        assertEquals(previous, store.load())
    }

    @Test fun webSymbolSetAndAsciiCompositionArePreserved() {
        for (symbol in "!@#$%^&*()_+-=[]{};':\"\\|,.<>/?") assertNull(PasswordPolicy.error("Abcdefghij1$symbol"))
        assertNotNull(PasswordPolicy.error("àbcdefghij1!"))
        assertNotNull(PasswordPolicy.error("ABCDEFGHIJ١!"))
    }

    @Test fun legacyPasswordLoginStillWorksAndAuditFailureDoesNotDestroyIt() = runBlocking {
        server.enqueue(json(authResponse()))
        server.enqueue(json("""{"message":"private backend secret"}""", 503))
        val signedIn = withTimeout(5_000) { api.signIn("person@example.test", "old") }
        assertEquals(signedIn, store.load())
        val login = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("old", JSONObject(login.body.readUtf8()).getString("password"))
        val audit = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/rest/v1/login_activity", audit.path)
        assertEquals("Bearer ${signedIn.accessToken}", audit.getHeader("Authorization"))
        val body = JSONObject(audit.body.readUtf8())
        assertEquals(USER, body.getString("user_id"))
        assertEquals("sign_in", body.getString("event_type"))
        assertEquals(SID, body.getString("session_id"))
        assertEquals("Savely Android · session $SID", body.getString("user_agent"))
        assertFalse(body.toString().contains(signedIn.accessToken))
        val warning = withTimeout(5_000) { api.accessAuditWarning.filterNotNull().first() }
        assertFalse(warning.contains("secret"))
        assertEquals(signedIn, api.session())
        api.dismissAccessAuditWarning()
        assertNull(api.accessAuditWarning.value)
    }

    @Test fun auditCannotDelayLoginAndCancelledOldOwnerCannotPublishWarning() = runBlocking {
        val auditStarted = CountDownLatch(1)
        val releaseAudit = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/auth/v1/token" -> json(authResponse())
                "/rest/v1/login_activity" -> {
                    auditStarted.countDown()
                    check(releaseAudit.await(5, TimeUnit.SECONDS))
                    json("{}", 503)
                }
                else -> json("{}", 404)
            }
        }
        val signedIn = withTimeout(3_000) { api.signIn("person@example.test", "old") }
        assertTrue(auditStarted.await(5, TimeUnit.SECONDS))
        api.cancelPendingAudit()
        store.save(session(OTHER_SID, OTHER_USER))
        releaseAudit.countDown()
        assertNotEquals(signedIn.userId, store.load()?.userId)
        // Cancellation invalidates the epoch before the former owner's in-flight response is consumed.
        api.close()
        assertNull(api.accessAuditWarning.value)
    }

    @Test fun sessionIdIsJwtSessionIdOnlyNeverSidOrLoginActivityId() {
        assertEquals(SID, session().authSessionId())
        assertNull(session().copy(accessToken = jwt(JSONObject().put("sid", SID))).authSessionId())
        assertNull(session().copy(accessToken = jwt(JSONObject().put("session_id", "1-2-3-4-5"))).authSessionId())
        assertNull(session().copy(accessToken = "malformed").authSessionId())
        assertEquals(SID, revocableSessionId(JSONObject().put("id", ACTIVITY_ID).put("session_id", SID.uppercase())))
        assertNull(revocableSessionId(JSONObject().put("id", SID)))
        assertNull(revocableSessionId(JSONObject().put("id", SID).put("session_id", JSONObject.NULL)))
        assertNull(revocableSessionId(JSONObject().put("session_id", "not-a-uuid")))
    }

    @Test fun revokingCurrentAuthSessionUsesItsIdAndClearsLocalCredentials() = runBlocking {
        store.save(session())
        server.enqueue(json("null"))
        assertTrue(api.revokeSession(requireNotNull(revocableSessionId(JSONObject().put("id", ACTIVITY_ID).put("session_id", SID)))))
        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/rest/v1/rpc/delete_session", request.path)
        assertEquals(SID, JSONObject(request.body.readUtf8()).getString("p_session_id"))
        assertNull(store.load())
        assertNull(api.accessAuditWarning.value)
    }

    @Test fun revokingAnotherSessionPreservesCurrentAndInvalidIdDoesNotCallHttp() = runBlocking {
        val previous = session().also(store::save)
        assertValidation { api.revokeSession("1-2-3-4-5") }
        assertEquals(0, server.requestCount)
        server.enqueue(json("null"))
        assertFalse(api.revokeSession(OTHER_SID))
        assertEquals(previous, store.load())
    }

    @Test fun failedRevocationPreservesCurrentCredentials() = runBlocking {
        val previous = session().also(store::save)
        server.enqueue(json("{}", 503))
        try { api.revokeSession(SID); fail("Expected server error") }
        catch (error: ApiException) { assertEquals(ApiError.SERVER, error.kind) }
        assertEquals(previous, store.load())
    }

    @Test fun refreshForExplicitlyBannedOrMissingUserClearsSessionAndRequiresAuthentication() = runBlocking {
        for (code in listOf("user_banned", "user_not_found")) for (status in listOf(400, 403)) {
            store.save(session().copy(expiresAt = 1))
            server.enqueue(json(JSONObject().put("code", code).toString(), status))
            try { api.session(); fail("Expected authentication failure") }
            catch (failure: ApiException) { assertEquals(ApiError.AUTHENTICATION, failure.kind) }
            assertNull(store.load())
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun refreshNetworkAndServerFailuresPreserveSession() = runBlocking {
        for (kind in listOf(ApiError.SERVER, ApiError.NETWORK)) {
            val previous = session().copy(expiresAt = 1).also(store::save)
            server.enqueue(if (kind == ApiError.SERVER) json("{}", 503)
                else MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            try { api.session(); fail("Expected temporary refresh failure") }
            catch (failure: ApiException) { assertEquals(kind, failure.kind) }
            assertEquals(previous, store.load())
        }
    }

    @Test fun invalidationAndUiPublicationGuardsRejectAnEarlierOwnerAfterNewLogin() = runBlocking {
        for (newUser in listOf(USER, OTHER_USER)) {
            val oldSession = session().also(store::save)
            api.session()
            val oldOwner = requireNotNull(api.sessionOwnerRevision(oldSession))
            store.save(oldSession.copy(expiresAt = 1))
            server.enqueue(json("""{"code":"refresh_token_not_found"}""", 400))
            try { api.session(); fail("Expected revoked refresh") }
            catch (error: ApiException) { assertEquals(ApiError.AUTHENTICATION, error.kind) }
            val event = requireNotNull(api.sessionInvalidation.value)
            assertTrue(api.isSessionInvalidationCurrent(event))

            val replacement = session(OTHER_SID, newUser)
            server.enqueue(json(replacement.toJson().toString()))
            server.enqueue(json("", 201))
            assertEquals(replacement, withTimeout(5_000) { api.signIn(replacement.email, "old") })
            assertFalse(api.isSessionInvalidationCurrent(event))
            assertFalse(api.isSessionOwnerCurrent(oldOwner))
            assertNull(api.sessionOwnerRevision(oldSession))
            assertNotNull(api.sessionOwnerRevision(replacement))
            var stalePublication = false
            api.withCurrentSessionOwner(oldOwner) { stalePublication = true }
            api.withCurrentSessionInvalidation(event) { stalePublication = true }
            assertFalse(stalePublication)
            assertEquals(replacement, store.load())
            // Drain refresh, login and audit before another iteration changes the queued responses.
            repeat(3) { requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        }
    }

    @Test fun eachSuccessfulLoginRevisesOwnershipEvenWhenAnAuthResponseRepeatsAnId() = runBlocking {
        val original = session().also(store::save)
        api.session()
        val revision = requireNotNull(api.sessionOwnerRevision(original))
        server.enqueue(json(authResponse()))
        server.enqueue(json("", 201))
        withTimeout(5_000) { api.signIn(original.email, "old") }

        assertNotEquals(revision, api.sessionOwnerRevision(original))
        assertFalse(api.isSessionOwnerCurrent(revision))
        assertNull(api.sessionInvalidation.value)
        assertEquals(original, store.load())
        repeat(2) { requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
    }

    private suspend fun assertValidation(action: suspend () -> Unit) {
        try { action(); fail("Expected validation failure") }
        catch (error: ApiException) { assertEquals(ApiError.VALIDATION, error.kind) }
    }

    @Test fun signingOutOtherSessionsUsesOthersScopeAndKeepsCurrentOwner() = runBlocking {
        val previous = session().also(store::save)
        api.session()
        val revision = api.sessionOwnerRevision()
        server.enqueue(json("", 204))
        api.signOutOtherSessions()
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/auth/v1/logout?scope=others", request.path)
        assertEquals("Bearer ${previous.accessToken}", request.getHeader("Authorization"))
        assertEquals(previous, store.load())
        assertEquals(revision, api.sessionOwnerRevision())
        assertNull(api.sessionInvalidation.value)
        assertEquals(1, server.requestCount)
    }

    @Test fun signingOutOthersPreservesSessionOnServerFailureOrLostResponseWithoutRetry() = runBlocking {
        val previous = session().also(store::save)
        server.enqueue(json("{}", 503))
        try { api.signOutOtherSessions(); fail("Expected server failure") }
        catch (failure: ApiException) { assertEquals(ApiError.SERVER, failure.kind) }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try { withTimeout(5_000) { api.signOutOtherSessions() }; fail("Expected lost response") }
        catch (failure: ApiException) { assertEquals(ApiError.NETWORK, failure.kind) }
        assertEquals(previous, store.load())
        assertNull(api.sessionInvalidation.value)
        assertEquals(2, server.requestCount)
    }

    @Test fun signingOutOthersNeverOverwritesANewSessionArrivingBeforeResponse() = runBlocking {
        val previous = session().also(store::save)
        val replacement = session(OTHER_SID, OTHER_USER)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                store.save(replacement)
                return json("", 204)
            }
        }
        try { api.signOutOtherSessions(); fail("Expected owner change") }
        catch (failure: ApiException) { assertEquals(ApiError.VALIDATION, failure.kind) }
        assertEquals("Bearer ${previous.accessToken}", server.takeRequest().getHeader("Authorization"))
        assertEquals(replacement, store.load())
        assertNull(api.sessionInvalidation.value)
        assertEquals(1, server.requestCount)
    }

    @Test fun accountDeletionRequiresExplicitSuccessfulBodyAndPreservesSessionForLogicalErrors() = runBlocking {
        val previous = session().also(store::save)
        for (response in listOf("""{"error":"provider-private-diagnostic"}""", "{}", """{"success":false}""", """{"success":"true"}""", """{"success":true,"error":"failed"}""")) {
            server.enqueue(json(response))
            try { api.deleteAccount(); fail("Expected rejected deletion result") }
            catch (failure: ApiException) { assertFalse(failure.message!!.contains("provider-private")) }
            assertEquals(previous, store.load())
            assertNull(api.sessionInvalidation.value)
        }
        server.enqueue(json("{}", 503))
        try { api.deleteAccount(); fail("Expected server failure") }
        catch (failure: ApiException) { assertEquals(ApiError.SERVER, failure.kind) }
        assertEquals(previous, store.load())
        assertEquals(6, server.requestCount)
    }

    @Test fun confirmedAccountDeletionClearsOnlyOwnSessionAndDoesNotSendExtraLogout() = runBlocking {
        val previous = session().also(store::save)
        server.enqueue(json("""{"success":true}"""))
        assertTrue(api.deleteAccount())
        val request = server.takeRequest()
        assertEquals("/functions/v1/delete-account", request.path)
        assertEquals("Bearer ${previous.accessToken}", request.getHeader("Authorization"))
        assertEquals("{}", request.body.readUtf8())
        assertNull(store.load())
        assertTrue(api.isSessionInvalidationCurrent(requireNotNull(api.sessionInvalidation.value)))
        assertEquals(1, server.requestCount)
    }

    @Test fun confirmedDeletionOfOldSessionPreservesReplacementOfSameOrAnotherUser() = runBlocking {
        for (replacementUser in listOf(USER, OTHER_USER)) {
            store.save(session())
            val replacement = session(OTHER_SID, replacementUser)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    store.save(replacement)
                    return json("""{"success":true}""")
                }
            }
            assertFalse(api.deleteAccount())
            assertEquals(replacement, store.load())
            assertNull(api.sessionInvalidation.value)
            assertEquals("/functions/v1/delete-account", server.takeRequest().path)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun confirmedDeletionFinishesLocalCleanupWhenCallerIsCancelled() = runBlocking {
        val previous = session().also(store::save)
        val checkingOwner = CountDownLatch(1)
        val releaseOwner = CountDownLatch(1)
        val reads = AtomicInteger()
        val guardedStore = object : SessionStore {
            override fun load(): Session? {
                if (reads.incrementAndGet() == 2) {
                    checkingOwner.countDown()
                    check(releaseOwner.await(5, TimeUnit.SECONDS))
                }
                return store.load()
            }
            override fun save(session: Session) = store.save(session)
            override fun clear() = store.clear()
        }
        val guardedApi = SupabaseApi(BackendConfig(server.url("/").toString(), "public-key"), guardedStore)
        try {
            server.enqueue(json("""{"success":true}"""))
            val deletion = launch(Dispatchers.Default) { guardedApi.deleteAccount() }
            assertTrue(checkingOwner.await(5, TimeUnit.SECONDS))
            deletion.cancel()
            releaseOwner.countDown()
            withTimeout(5_000) { deletion.join() }
            assertNull(store.load())
            assertTrue(guardedApi.isSessionInvalidationCurrent(requireNotNull(guardedApi.sessionInvalidation.value)))
            assertEquals("Bearer ${previous.accessToken}", server.takeRequest().getHeader("Authorization"))
            assertEquals(1, server.requestCount)
        } finally { releaseOwner.countDown(); guardedApi.close() }
    }

    @Test fun registrationAvailabilityReadUsesPublicRpcWithoutSessionOrUserToken() = runBlocking {
        server.enqueue(json("false"))
        assertFalse(api.registrationsEnabled())
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/get_registrations_enabled", request.path)
        assertEquals("{}", request.body.readUtf8())
        assertEquals("Bearer public-key", request.getHeader("Authorization"))
        assertNull(store.load())
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationDuringPendingLogoutClearsOnlyTheOriginalSessionWithoutRetry() = runBlocking {
        val original = session().also(store::save)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val logout = launch(Dispatchers.Default) { api.signOut() }
        try {
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/auth/v1/logout?scope=local", request.path)
            assertEquals("POST", request.method)
            assertEquals("{}", request.body.readUtf8())
            assertEquals("Bearer ${original.accessToken}", request.getHeader("Authorization"))
        } finally { logout.cancel(); withTimeout(5_000) { logout.join() } }
        assertTrue(logout.isCancelled)
        assertNull(store.load())
        assertTrue(api.isSessionInvalidationCurrent(requireNotNull(api.sessionInvalidation.value)))
        assertEquals(1, server.requestCount)
    }

    @Test fun pendingLogoutCancellationPreservesReplacementSessionOfSameOrAnotherUser() = runBlocking {
        for (replacementUser in listOf(USER, OTHER_USER)) {
            val original = session().also(store::save)
            val replacement = session(OTHER_SID, replacementUser)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val logout = launch(Dispatchers.Default) { api.signOut() }
            try {
                val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("Bearer ${original.accessToken}", request.getHeader("Authorization"))
                assertEquals("/auth/v1/logout?scope=local", request.path)
                store.save(replacement)
            } finally { logout.cancel(); withTimeout(5_000) { logout.join() } }
            assertEquals(replacement, store.load())
            assertNull(api.sessionInvalidation.value)
            assertNotNull(api.sessionOwnerRevision(replacement))
        }
        assertEquals(2, server.requestCount)
    }
    private fun session(id: String = SID, user: String = USER) = Session(jwt(JSONObject().put("session_id", id)), "refresh",
        System.currentTimeMillis() / 1000 + 3600, user, "person@example.test")
    private fun authResponse(): String = session().toJson().toString()
    private fun jwt(body: JSONObject): String = "header.${Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().toByteArray())}.signature"
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    companion object {
        private const val USER = "10000000-0000-4000-8000-000000000001"
        private const val OTHER_USER = "10000000-0000-4000-8000-000000000002"
        private const val SID = "20000000-0000-4000-8000-000000000001"
        private const val OTHER_SID = "20000000-0000-4000-8000-000000000002"
        private const val ACTIVITY_ID = "30000000-0000-4000-8000-000000000001"
    }
}
