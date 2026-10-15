package cc.savely.android.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises real Android HTTP/TLS, JSON and Keystore against an isolated in-memory backend. */
@RunWith(AndroidJUnit4::class)
class DeviceBackendFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var context: Context
    private lateinit var store: AndroidSessionStore

    @Before fun setUp() {
        context = IsolatedTestContext(InstrumentationRegistry.getInstrumentation().targetContext)
        store = AndroidSessionStore(context).also { it.clear() }
        val certificate = HeldCertificate.Builder()
            .commonName("Savely local instrumentation backend")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
    }

    @After fun tearDown() {
        store.clear()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun loginPersistsEncryptedSessionAndNewClientRefreshesOnceOnAndroid() = runBlocking {
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl?.queryParameter("grant_type") == "password") return json(authResponse("device-access", "device-refresh"))
                if (request.requestUrl?.queryParameter("grant_type") == "refresh_token") {
                    refreshes.incrementAndGet()
                    if (JSONObject(request.body.readUtf8()).optString("refresh_token") != "device-refresh") return json("{}", 400)
                    return json(authResponse("renewed-access", "renewed-refresh"))
                }
                return json("{}", 404)
            }
        }
        val signedIn = api().signIn("device@example.test", "synthetic-test-password")
        assertEquals(USER_ID, signedIn.userId)
        val ciphertext = context.getSharedPreferences("savely_session", Context.MODE_PRIVATE).getString("session_v1", null)
        assertNotNull(ciphertext)
        assertFalse(ciphertext!!.contains("device-access"))
        assertFalse(ciphertext.contains("device-refresh"))

        val recreatedStore = AndroidSessionStore(context)
        assertEquals(signedIn, recreatedStore.load())
        val reopened = api(recreatedStore)
        assertEquals(signedIn, reopened.session())
        assertEquals(1, server.requestCount)

        // Make expiry deterministic without changing the production clock or waiting an hour.
        recreatedStore.save(signedIn.copy(expiresAt = 1))
        val refreshed = coroutineScope { (1..8).map { async { reopened.session() } }.awaitAll() }
        assertTrue(refreshed.all { it != null && it.accessToken == "renewed-access" && it.refreshToken == "renewed-refresh" })
        assertEquals(1, refreshes.get())
        assertEquals("renewed-refresh", AndroidSessionStore(context).load()?.refreshToken)
        assertEquals(2, server.requestCount)
    }

    @Test fun authenticatedTransactionRoundTripUsesOwnerFiltersAndAffectedCount() = runBlocking {
        var stored: JSONObject? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl?.encodedPath == "/auth/v1/token") return json(authResponse())
                if (request.requestUrl?.encodedPath != "/rest/v1/transactions") return json("{}", 404)
                if (request.getHeader("Authorization") != "Bearer device-access" || request.getHeader("apikey") != "public-test-key") return json("{}", 401)
                return when (request.method) {
                    "POST" -> {
                        val body = JSONObject(request.body.readUtf8())
                        if (body.optString("user_id") != USER_ID) return json("{}", 403)
                        stored = body.put("id", TRANSACTION_ID)
                        json(JSONArray().put(stored).toString())
                    }
                    "GET" -> {
                        if (request.requestUrl?.queryParameter("user_id") != "eq.$USER_ID") return json("{}", 403)
                        json(if (stored == null || !stored!!.isNull("deleted_at")) "[]" else JSONArray().put(stored).toString())
                    }
                    "PATCH" -> {
                        if (request.requestUrl?.queryParameter("id") != "eq.$TRANSACTION_ID" ||
                            request.requestUrl?.queryParameter("user_id") != "eq.$USER_ID") return json("{}", 403)
                        val body = JSONObject(request.body.readUtf8())
                        val record = stored ?: return MockResponse().setResponseCode(204).setHeader("Content-Range", "*/0")
                        body.keys().forEach { record.put(it, body.get(it)) }
                        if (request.getHeader("Prefer") == "return=minimal,count=exact") {
                            MockResponse().setResponseCode(204).setHeader("Content-Range", "*/1")
                        } else json(JSONArray().put(record).toString())
                    }
                    else -> json("{}", 405)
                }
            }
        }
        val api = api()
        api.signIn("device@example.test", "synthetic-test-password")
        val created = api.insert("transactions", JSONObject().put("user_id", USER_ID).put("type", "expense")
            .put("amount", BigDecimal("12.50")).put("currency", "EUR").put("description", "Synthetic lunch")
            .put("date", "2026-10-11").put("deleted_at", JSONObject.NULL))
        assertEquals(TRANSACTION_ID, created.getString("id"))
        assertTrue(created.get("amount") is BigDecimal)
        assertEquals(0, BigDecimal("12.50").compareTo(created.get("amount") as BigDecimal))
        assertEquals(1, api.select("transactions", "user_id=eq.$USER_ID&deleted_at=is.null").length())
        val updated = api.update("transactions", TRANSACTION_ID, JSONObject().put("description", "Edited lunch"), "user_id=eq.$USER_ID")
        assertEquals("Edited lunch", updated.getString("description"))
        api.updateMinimal("transactions", TRANSACTION_ID, JSONObject().put("deleted_at", "2026-10-11T12:00:00Z"), "user_id=eq.$USER_ID")
        assertEquals(0, api.select("transactions", "user_id=eq.$USER_ID&deleted_at=is.null").length())
        assertEquals(6, server.requestCount)
    }

    @Test fun nativePlatformParserPreservesLargeDecimalsWithoutDoubleRounding() = runBlocking {
        store.save(Session("device-access", "device-refresh", now() + 3600, USER_ID, "device@example.test"))
        server.enqueue(json("""{"quantity":999999999999.12345678,"price":0.10000001,"integer":9223372036854775808,"metadata":{"description":"Numeric-looking text 12.34"}}"""))
        val response = api().rpc("synthetic_portfolio") as JSONObject
        assertTrue(response.get("quantity") is BigDecimal)
        assertEquals(BigDecimal("999999999999.12345678"), response.get("quantity"))
        assertEquals(BigDecimal("0.10000001"), response.get("price"))
        assertEquals(BigDecimal("9223372036854775808"), response.get("integer"))
        assertEquals("Numeric-looking text 12.34", response.getJSONObject("metadata").getString("description"))
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/synthetic_portfolio", request.path)
        assertEquals("Bearer device-access", request.getHeader("Authorization"))
    }

    @Test fun failedRefreshRetainsEncryptedCredentialsButRevokedRefreshClearsThem() = runBlocking {
        val saved = Session("device-access", "device-refresh", 1, USER_ID, "device@example.test")
        store.save(saved)
        server.enqueue(json("{}", 503))
        val api = api()
        try { api.session(); fail("Expected refresh failure") }
        catch (error: ApiException) { assertEquals(ApiError.SERVER, error.kind) }
        assertEquals(saved, AndroidSessionStore(context).load())
        server.enqueue(json("""{"code":"refresh_token_not_found"}""", 400))
        try { api.session(); fail("Expected revoked session") }
        catch (error: ApiException) { assertEquals(ApiError.AUTHENTICATION, error.kind) }
        assertNull(AndroidSessionStore(context).load())
    }

    private fun api(sessionStore: SessionStore = store): SupabaseApi {
        val url = server.url("/").toString()
        return SupabaseApi(BackendConfig(url, "public-test-key", url), sessionStore, client)
    }
    private fun authResponse(access: String = "device-access", refresh: String = "device-refresh"): String = JSONObject()
        .put("access_token", access).put("refresh_token", refresh).put("expires_in", 3600)
        .put("user", JSONObject().put("id", USER_ID).put("email", "device@example.test")).toString()
    private fun json(body: String, status: Int = 200): MockResponse = MockResponse()
        .setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun now() = System.currentTimeMillis() / 1000

    /** Production preferences and any existing user's session stay untouched. */
    private class IsolatedTestContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("device_backend_flow_$name", mode)
    }
    private companion object {
        const val USER_ID = "00000000-0000-4000-8000-000000000001"
        const val TRANSACTION_ID = "00000000-0000-4000-8000-000000000002"
    }
}
