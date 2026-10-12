package cc.savely.android.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.savely.android.BuildConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Opt-in, exact new QA A only. Password and OTP secret never enter reports or test errors. */
@RunWith(AndroidJUnit4::class)
class RealAuthQualificationTest {
    @Test fun passwordTotpAndOtherSessions() = runBlocking {
        val fixture = fixture()
        val context = fixture.context
        requireSafe(!context.getSharedPreferences("savely_session", Context.MODE_PRIVATE).contains("session_v1"), "LOCAL_SESSION_PRESENT")
        requireSafe(!fixture.journal.file.exists(), "CHECKPOINT_PRESENT")
        val input = context.filesDir.resolve(INPUT)
        val source = bounded(input)
        requireSafe(source.optString("run_id") == fixture.run && source.optString("user_id") == OWNER &&
            source.optString("email") == EMAIL && source.optString("project") == PROJECT &&
            source.optBoolean("server_preflight_empty") && source.optString("provisioning_run") == PROVISIONING_RUN,
            "INPUT_BINDING_INVALID")
        val password = source.optString("password")
        requireSafe(password.length in 12..256, "PASSWORD_INPUT_INVALID")
        // Remove the transient private input before any network request. No password is checkpointed.
        requireSafe(input.delete(), "PRIVATE_INPUT_DELETE_FAILED")
        source.remove("password")
        fixture.journal.initialize()
        val store = AndroidSessionStore(context)
        val secondStore = MemorySessionStore()
        val guard = Guard(fixture.journal)
        val client = OkHttpClient.Builder().addInterceptor(guard).followRedirects(false)
            .followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(35, TimeUnit.SECONDS).build()
        val api = api(store, client)
        val secondApi = api(secondStore, client)
        var primaryId: String? = null
        var secondaryId: String? = null
        var succeeded = false
        var failureCode: String? = null
        try {
            val primary = api.signIn(EMAIL, password)
            primaryId = canonical(claims(primary).optString("session_id"))
            fixture.journal.set("primary_session_id", primaryId)
            requireSafe(identity(client, primary) == primaryId, "PRIMARY_IDENTITY_FAILED")
            requireSafe(api.listMfaFactors(true).length() == 0, "PREEXISTING_FACTOR_PRESENT")
            fixture.journal.set("password_login", true)
            val enrollment = api.enrollTotp()
            val factor = canonical(enrollment.optString("id"))
            fixture.journal.set("factor_id", factor)
            requireSafe(enrollment.optString("type", "totp") == "totp", "FACTOR_TYPE_INVALID")
            val secret = enrollment.optJSONObject("totp")?.optString("secret").orEmpty()
            requireSafe(secret.length in 16..128, "TOTP_SECRET_INVALID")
            val elevated = api.verifyTotp(factor, totp(secret, Instant.now().epochSecond))
            requireSafe(identity(client, elevated) == primaryId && claims(elevated).optString("aal") == "aal2", "AAL2_IDENTITY_INVALID")
            val factors = api.listMfaFactors(true)
            requireSafe(factors.length() == 1 && factors.getJSONObject(0).optString("id") == factor &&
                factors.getJSONObject(0).optString("status") == "verified", "VERIFIED_FACTOR_NOT_EXACT")
            fixture.journal.set("totp_verified", true)
            val secondary = secondApi.signIn(EMAIL, password)
            secondaryId = canonical(claims(secondary).optString("session_id"))
            requireSafe(secondaryId != primaryId, "SECOND_SESSION_NOT_DISTINCT")
            fixture.journal.set("secondary_session_id", secondaryId)
            requireSafe(identity(client, secondary) == secondaryId, "SECONDARY_IDENTITY_FAILED")
            api.signOutOtherSessions()
            fixture.journal.set("others_confirmed", true)
            // Access JWTs can remain valid until expiry: only an actual refresh proves revocation.
            secondStore.save(secondary.copy(expiresAt = 1))
            val rejected = try { secondApi.session(); null } catch (error: ApiException) { error }
            requireSafe(rejected != null && rejected.kind == ApiError.AUTHENTICATION && rejected.status in setOf(400, 401, 403) &&
                secondStore.load() == null, "OTHER_SESSION_REFRESH_NOT_REJECTED")
            fixture.journal.set("secondary_revoked", true)
            val current = requireNotNull(store.load())
            store.save(current.copy(expiresAt = 1))
            val renewed = requireNotNull(api.session())
            requireSafe(identity(client, renewed) == primaryId && claims(renewed).optString("aal") == "aal2", "CURRENT_SESSION_REFRESH_FAILED")
            fixture.journal.set("current_refreshed", true)
            succeeded = true
        } catch (failure: Throwable) {
            failureCode = if (failure is SafeFailure) failure.code else "NATIVE_AUTH_FLOW_FAILED"
        } finally {
            withContext(NonCancellable) {
                api.cancelPendingAudit(); secondApi.cancelPendingAudit()
                try {
                    val saved = store.load()
                    val expectedPrimary = primaryId ?: fixture.journal.read().optString("primary_session_id").takeIf { it.isNotEmpty() }
                    if (saved != null && owned(saved, expectedPrimary)) {
                        val factor = fixture.journal.read().optString("factor_id")
                        if (factor.isNotEmpty()) {
                            val factors = api.listMfaFactors(true)
                            requireSafe((0 until factors.length()).all { factors.getJSONObject(it).optString("id") == factor }, "FOREIGN_FACTOR_PRESERVE")
                            if (factors.length() == 1) api.unenrollTotp(factor)
                            requireSafe(api.listMfaFactors(true).length() == 0, "FACTOR_DELETE_NOT_CONFIRMED")
                            fixture.journal.set("factor_removed", true)
                        } else if (!fixture.journal.read().optBoolean("enroll_attempted")) fixture.journal.set("factor_removed", true)
                        // An unknown factor from an ambiguous enrollment is deliberately preserved.
                        if (fixture.journal.read().optBoolean("factor_removed")) {
                            try { api.signOut(); fixture.journal.set("logout_primary", true) }
                            finally { fixture.journal.set("cleanup_local", store.load() == null) }
                        }
                    } else if (saved == null && primaryId == null && !fixture.journal.read().optBoolean("login_attempted")) {
                        fixture.journal.set("factor_removed", true)
                        fixture.journal.set("logout_primary", true)
                        fixture.journal.set("cleanup_local", true)
                    }
                    val secondary = secondStore.load()
                    if (secondary != null && owned(secondary, secondaryId)) secondApi.signOut()
                    val secondaryNeverCreated = fixture.journal.read().optInt("password_requests") < 2 ||
                        fixture.journal.read().optBoolean("secondary_login_rejected")
                    fixture.journal.set("cleanup_secondary", secondStore.load() == null &&
                        (secondaryNeverCreated || fixture.journal.read().optBoolean("secondary_revoked") || guard.secondaryLogoutConfirmed))
                } catch (_: Throwable) { failureCode = failureCode ?: "EXACT_CLEANUP_FAILED" }
                val result = fixture.journal.read()
                val clean = result.optBoolean("factor_removed") && result.optBoolean("logout_primary") &&
                    result.optBoolean("cleanup_local") && result.optBoolean("cleanup_secondary")
                result.put("cleanup_pending", !clean).put("stage", if (succeeded && clean) "passed" else "failed")
                    .put("failure_code", failureCode ?: JSONObject.NULL)
                    .put("scope", "native-password-api-totp-and-refresh-revocation-no-email-no-pkce-no-ui-form")
                write(context.filesDir.resolve(RESULT), result)
                if (clean) requireSafe(fixture.journal.file.delete(), "CHECKPOINT_DELETE_FAILED")
                api.close(); secondApi.close()
                client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            }
        }
        val result = bounded(context.filesDir.resolve(RESULT))
        requireSafe(result.optString("stage") == "passed" && !result.optBoolean("cleanup_pending", true), failureCode ?: "QUALIFICATION_FAILED")
    }

    @Test fun preflightEmptyLocalStateWithoutCredentials() {
        val f = fixture()
        requireSafe(!f.context.getSharedPreferences("savely_session", Context.MODE_PRIVATE).contains("session_v1"), "LOCAL_SESSION_PRESENT")
        requireSafe(!f.journal.file.exists() && !f.context.filesDir.resolve(INPUT).exists(), "PRIVATE_RUN_PRESENT")
    }

    @Test fun ledgerAndTotpWithoutCredentials() {
        val f = fixture()
        requireSafe(!f.journal.file.exists(), "CHECKPOINT_PRESENT")
        f.journal.initialize()
        try {
            requireSafe(totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59) == "287082", "RFC6238_VECTOR_FAILED")
            f.journal.reserve("enroll_attempted")
            val repeat = runCatching { f.journal.reserve("enroll_attempted") }.exceptionOrNull()
            requireSafe(repeat is SafeFailure && repeat.code == "REPEAT_MUTATION_DENIED", "LEDGER_REPEAT_ALLOWED")
            val state = f.journal.read()
            requireSafe(!state.has("password") && !state.has("access_token") && !state.has("secret"), "CHECKPOINT_SECRET_PRESENT")
            write(f.journal.file, state.put("user_id", UUID.randomUUID().toString()))
            requireSafe(runCatching { f.journal.read() }.exceptionOrNull() is SafeFailure, "FOREIGN_CHECKPOINT_ACCEPTED")
        } finally { requireSafe(f.journal.file.delete(), "FIXTURE_CHECKPOINT_DELETE_FAILED") }
    }

    private class Guard(private val journal: Journal) : Interceptor {
        @Volatile var secondaryLogoutConfirmed = false
        override fun intercept(chain: Interceptor.Chain): Response {
            return try { guarded(chain) }
            catch (failure: IOException) { throw failure }
            catch (_: Exception) { throw SafeFailure("AUTH_TRANSPORT_GUARD_FAILED") }
        }
        private fun guarded(chain: Interceptor.Chain): Response {
            var request = chain.request()
            requireSafe(request.url.scheme == "https" && request.url.host == HOST && request.url.port == 443, "ORIGIN_DENIED")
            val path = request.url.encodedPath
            val method = request.method
            val body = request.body?.let { Buffer().apply { it.writeTo(this) }.readUtf8() }?.let(::JSONObject) ?: JSONObject()
            val token = request.header("Authorization")?.removePrefix("Bearer ").orEmpty()
            val tokenClaims = if (token.isNotEmpty() && token != BuildConfig.SUPABASE_ANON_KEY) jwt(token) else null
            if (tokenClaims != null) requireSafe(tokenClaims.optString("sub") == OWNER && tokenClaims.optString("role") == "authenticated", "JWT_OWNER_DENIED")
            if (tokenClaims != null) {
                val state = journal.read()
                val sid = canonical(tokenClaims.optString("session_id"))
                requireSafe(sid in setOf(state.optString("primary_session_id"), state.optString("secondary_session_id")), "JWT_SESSION_DENIED")
                if (path.startsWith("/auth/v1/factors")) requireSafe(sid == state.optString("primary_session_id"), "FACTOR_SESSION_DENIED")
            }
            when {
                path == "/auth/v1/token" && method == "POST" && request.url.queryParameter("grant_type") == "password" -> {
                    requireSafe(body.optString("email") == EMAIL, "LOGIN_OWNER_DENIED")
                    journal.reserveLogin()
                }
                path == "/auth/v1/token" && method == "POST" && request.url.queryParameter("grant_type") == "refresh_token" -> journal.reserveCount("refresh_attempts", 2)
                path == "/auth/v1/user" && method == "GET" -> requireSafe(tokenClaims != null && request.url.query == null, "USER_READ_DENIED")
                path == "/rest/v1/login_activity" && method == "POST" -> requireSafe(tokenClaims != null && body.optString("user_id") == OWNER &&
                    body.optString("session_id") == tokenClaims.optString("session_id") && body.optString("event_type") == "sign_in", "AUDIT_DENIED")
                path == "/auth/v1/factors" && method == "POST" -> {
                    requireSafe(tokenClaims != null && body.optString("factor_type") == "totp", "ENROLL_DENIED")
                    journal.reserve("enroll_attempted")
                    body.put("friendly_name", "Android-Auth-QA-${journal.run}")
                    request = request.newBuilder().method(method, body.toString().toRequestBody("application/json".toMediaType())).build()
                }
                method == "POST" && path.matches(Regex("/auth/v1/factors/[0-9a-f-]{36}/(challenge|verify)")) -> {
                    requireSafe(tokenClaims != null && path.split('/')[4] == journal.read().optString("factor_id"), "FACTOR_VERIFY_DENIED")
                    journal.reserve(if (path.endsWith("/challenge")) "challenge_attempted" else "verify_attempted")
                }
                method == "DELETE" && path == "/auth/v1/factors/${journal.read().optString("factor_id")}" -> {
                    requireSafe(tokenClaims != null && journal.read().optString("factor_id").isNotEmpty(), "FACTOR_DELETE_DENIED")
                    journal.reserve("delete_attempted")
                }
                path == "/auth/v1/logout" && method == "POST" -> {
                    requireSafe(tokenClaims != null, "LOGOUT_OWNER_DENIED")
                    val sid = canonical(tokenClaims!!.optString("session_id"))
                    val state = journal.read()
                    when (request.url.queryParameter("scope")) {
                        "others" -> { requireSafe(sid == state.optString("primary_session_id"), "OTHERS_SESSION_DENIED"); journal.reserve("others_attempted") }
                        "local" -> { requireSafe(sid in setOf(state.optString("primary_session_id"), state.optString("secondary_session_id")), "LOCAL_SESSION_DENIED"); journal.reserve("logout_$sid") }
                        else -> throw SafeFailure("LOGOUT_SCOPE_DENIED")
                    }
                }
                else -> throw SafeFailure("ENDPOINT_DENIED")
            }
            val response = chain.proceed(request)
            if (path == "/auth/v1/token" && request.url.queryParameter("grant_type") == "password" &&
                journal.read().optInt("password_requests") == 2 && response.code in 400..499 && response.code != 408) {
                journal.set("secondary_login_rejected", true)
            }
            if (response.isSuccessful && path == "/auth/v1/token" && request.url.queryParameter("grant_type") == "password") {
                val result = JSONObject(response.peekBody(65_536).string())
                val user = result.getJSONObject("user")
                val claims = jwt(result.getString("access_token"))
                requireSafe(user.optString("id") == OWNER && user.optString("email") == EMAIL && claims.optString("sub") == OWNER &&
                    user.optJSONObject("user_metadata")?.optString("savely_android_qa_run") == PROVISIONING_RUN, "LOGIN_RESPONSE_IDENTITY_INVALID")
                val sid = canonical(claims.optString("session_id"))
                journal.set(if (journal.read().optInt("password_requests") == 1) "primary_session_id" else "secondary_session_id", sid)
            }
            if (response.isSuccessful && path == "/auth/v1/factors" && method == "POST") {
                // Record the exact returned UUID before releasing the response to the coroutine.
                // Never checkpoint the returned OTP secret or QR payload.
                val id = runCatching { JSONObject(response.peekBody(65_536).string()).optString("id") }.getOrNull()
                if (id != null && runCatching { canonical(id) }.isSuccess) journal.set("factor_id", id)
            }
            if (path == "/auth/v1/logout" && request.url.queryParameter("scope") == "local" &&
                tokenClaims?.optString("session_id") == journal.read().optString("secondary_session_id") && response.code in setOf(200, 204, 401, 403)) secondaryLogoutConfirmed = true
            return response
        }
    }

    private class Journal(val file: File, val run: String) {
        @Synchronized fun initialize() { requireSafe(!file.exists(), "CHECKPOINT_PRESENT"); write(file, JSONObject()
            .put("run_id", run).put("user_id", OWNER).put("email", EMAIL).put("project", PROJECT)
            .put("provisioning_run", PROVISIONING_RUN).put("stage", "running").put("cleanup_pending", true)) }
        @Synchronized fun read(): JSONObject = bounded(file).also { requireSafe(it.optString("run_id") == run &&
            it.optString("user_id") == OWNER && it.optString("email") == EMAIL && it.optString("project") == PROJECT, "CHECKPOINT_BINDING_INVALID") }
        @Synchronized fun set(key: String, value: Any?) { write(file, read().put(key, value ?: JSONObject.NULL)) }
        @Synchronized fun reserve(key: String) { val state = read(); requireSafe(!state.optBoolean(key), "REPEAT_MUTATION_DENIED"); write(file, state.put(key, true)) }
        @Synchronized fun reserveCount(key: String, max: Int) { val state = read(); val count = state.optInt(key); requireSafe(count < max, "REPEAT_REQUEST_DENIED"); write(file, state.put(key, count + 1)) }
        @Synchronized fun reserveLogin() { reserveCount("password_requests", 2); set("login_attempted", true) }
    }

    private data class Fixture(val context: Context, val run: String, val journal: Journal)
    private fun fixture(): Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        requireSafe(args.getString("realAuthQualification") == "true" && BuildConfig.DEBUG &&
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && context.packageName == "cc.savely.android" &&
            BuildConfig.SUPABASE_URL.trimEnd('/') == "https://$HOST", "OPT_IN_OR_PROJECT_INVALID")
        val run = canonical(args.getString("authQualificationRunId").orEmpty())
        return Fixture(context, run, Journal(context.filesDir.resolve(CHECKPOINT), run))
    }
    private fun api(store: SessionStore, client: OkHttpClient) = SupabaseApi(
        BackendConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, BuildConfig.WEB_URL), store, client)
    private fun identity(client: OkHttpClient, session: Session): String {
        requireSafe(session.userId == OWNER && session.email == EMAIL, "SESSION_OWNER_INVALID")
        val sid = canonical(claims(session).optString("session_id"))
        client.newCall(Request.Builder().url("https://$HOST/auth/v1/user").header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer ${session.accessToken}").build()).execute().use {
            requireSafe(it.isSuccessful, "SERVER_IDENTITY_HTTP_FAILED")
            val body = it.body ?: throw SafeFailure("SERVER_IDENTITY_BODY_MISSING")
            requireSafe(body.source().request(65_537).let { body.source().buffer.size <= 65_536 }, "SERVER_IDENTITY_TOO_LARGE")
            val user = JSONObject(body.string())
            requireSafe(user.optString("id") == OWNER && user.optString("email") == EMAIL &&
                user.optJSONObject("user_metadata")?.optString("savely_android_qa_run") == PROVISIONING_RUN, "SERVER_IDENTITY_MISMATCH")
        }
        return sid
    }
    private fun owned(session: Session, expected: String?) = expected != null && session.userId == OWNER && session.email == EMAIL && session.authSessionId() == expected
    // OkHttp's asynchronous interceptor boundary must receive IOException, not an uncaught runtime error.
    private class SafeFailure(val code: String) : IOException(code)
    private companion object {
        const val OWNER = "d46cee52-3d3b-45ae-9bc3-c95931a82339"
        const val EMAIL = "android-qa-5ef2838c-a@example.invalid"
        const val PROJECT = "crqnfbahytzenisospcx"
        const val HOST = "$PROJECT.supabase.co"
        const val PROVISIONING_RUN = "5ef2838c-ae5b-47d9-ac05-72dc5362b6a9"
        const val INPUT = "auth-qualification-input.json"
        const val CHECKPOINT = "auth-qualification-checkpoint.json"
        const val RESULT = "auth-qualification-result.json"
        fun requireSafe(ok: Boolean, code: String) { if (!ok) throw SafeFailure(code) }
        fun canonical(value: String): String { requireSafe(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false), "UUID_INVALID"); return value }
        fun claims(session: Session) = jwt(session.accessToken)
        fun jwt(token: String): JSONObject { val parts = token.split('.'); requireSafe(parts.size == 3 && parts[1].length <= 16_384, "JWT_INVALID"); return JSONObject(String(java.util.Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)) }
        fun bounded(file: File): JSONObject { requireSafe(file.isFile && file.length() in 1..16_384, "PRIVATE_JSON_INVALID"); return JSONObject(file.readText()) }
        fun write(file: File, value: JSONObject) { val atomic = AtomicFile(file); val out = atomic.startWrite(); try { out.write(value.toString().toByteArray()); atomic.finishWrite(out) } catch (failure: Throwable) { atomic.failWrite(out); throw SafeFailure("CHECKPOINT_WRITE_FAILED") } }
        fun totp(secret: String, seconds: Long): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var buffer = 0; var bits = 0; val bytes = ArrayList<Byte>()
            for (char in secret.trimEnd('=')) { val digit = alphabet.indexOf(char); requireSafe(digit >= 0, "TOTP_ENCODING_INVALID"); buffer = (buffer shl 5) or digit; bits += 5; if (bits >= 8) { bits -= 8; bytes.add((buffer shr bits).toByte()) } }
            val key = bytes.toByteArray()
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
            val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(seconds / 30).array())
            val offset = hash.last().toInt() and 15
            val value = ((hash[offset].toInt() and 127) shl 24) or ((hash[offset + 1].toInt() and 255) shl 16) or
                ((hash[offset + 2].toInt() and 255) shl 8) or (hash[offset + 3].toInt() and 255)
            key.fill(0); hash.fill(0)
            return (value % 1_000_000).toString().padStart(6, '0')
        }
    }
}
