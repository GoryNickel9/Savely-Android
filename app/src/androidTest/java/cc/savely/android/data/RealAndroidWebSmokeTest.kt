package cc.savely.android.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.util.AtomicFile
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.savely.android.BuildConfig
import cc.savely.android.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Opt-in only. The host reads the retained marker in an isolated browser between these methods. */
@RunWith(AndroidJUnit4::class)
class RealAndroidWebSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun prepareNativeMarker() = runBlocking {
        val fixture = guardedFixture()
        val store = AndroidSessionStore(fixture.context)
        val client = client()
        val api = api(store, client)
        val checkpoint = fixture.context.filesDir.resolve(CHECKPOINT)
        val input = fixture.context.filesDir.resolve("android-web-session-${fixture.runId}.json")
        val result = metadata(fixture).put("stage", "preparing").put("first_pid", Process.myPid())
        var creationAttempted = false
        var sessionSaveAttempted = false
        var handedOff = false
        var activity: ActivityScenario<MainActivity>? = null
        var failureCode: String? = null
        try {
            requireSmoke(store.load() == null, "EXISTING_LOCAL_SESSION_PRESERVED")
            requireSmoke(!checkpoint.exists() && !File("${checkpoint.path}.bak").exists() && !File("${checkpoint.path}.new").exists(), "EXISTING_CHECKPOINT_PRESERVED")
            requireSmoke(fixture.day == LocalDate.now(ZoneId.of("Europe/Rome")), "CREATION_DATE_MISMATCH")
            val bootstrap = Session.fromJson(boundedJson(input, 16_384))
            requireSmoke(isOwned(bootstrap, fixture), "BOOTSTRAP_OWNER_MISMATCH")
            requireSmoke(bootstrap.expiresAt > Instant.now().epochSecond + 600, "BOOTSTRAP_VALIDITY_TOO_SHORT")
            assertServerIdentity(client, bootstrap)
            sessionSaveAttempted = true
            withContext(Dispatchers.IO) { store.save(bootstrap) }
            requireSmoke(store.load() == bootstrap, "ENCRYPTED_SESSION_REREAD_FAILED")
            requireSmoke(input.delete(), "PRIVATE_INPUT_REMOVAL_FAILED")
            requireSmoke(selectMarker(api, fixture).length() == 0, "PREEXISTING_UUID_PRESERVED")

            // Record the explicit UUID and complete payload before the only INSERT attempt.
            val beforeInsert = metadata(fixture).put("stage", "creating").put("creation_attempted", true)
                .put("first_pid", Process.myPid())
            writeJson(checkpoint, beforeInsert)
            creationAttempted = true
            result.put("creation_attempted", true).put("stage", "creating").put("cleanup_pending", true)
            writeResult(fixture, result)
            requireOwnedStore(store, fixture)
            var insertFailure: String? = null
            try { api.insert("transactions", expectedPayload(fixture)) }
            catch (failure: Throwable) { insertFailure = safeFailure(failure) }
            // A committed INSERT can lose its response. Reconcile by UUID/owner without another INSERT.
            val rows = selectMarker(api, fixture)
            requireSmoke(rows.length() == 1 && ownRow(rows.getJSONObject(0), fixture), "INSERT_NOT_CONFIRMED_OR_PAYLOAD_CHANGED")
            result.put("native_created", true)
            insertFailure?.let { result.put("insert_response_error", it) }

            val launched = ActivityScenario.launch(MainActivity::class.java)
            activity = launched
            compose.waitUntil(45_000) { compose.onAllNodesWithText("Dashboard", substring = false).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(45_000) { compose.onAllNodesWithText("Mese corrente fino a oggi", substring = false).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Movimenti", substring = false).performClick()
            compose.waitUntil(45_000) { compose.onAllNodesWithText("Periodo: Questo mese", substring = false).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(fixture.marker))
            compose.waitUntil(45_000) { compose.onAllNodesWithText(fixture.marker, substring = false).fetchSemanticsNodes().size == 1 }
            launched.close()
            activity = null
            requireOwnedStore(store, fixture)
            requireSmoke(selectMarker(api, fixture).let { it.length() == 1 && ownRow(it.getJSONObject(0), fixture) }, "NATIVE_READ_PAYLOAD_CHANGED")
            result.put("native_ui_read", true).put("stage", "native_prepared").put("cleanup_pending", true)
            writeJson(checkpoint, JSONObject(result.toString()))
            writeResult(fixture, result)
            // Retain only the encrypted session and non-secret checkpoint until the browser finishes.
            handedOff = true
        } catch (failure: Throwable) {
            failureCode = safeFailure(failure)
        } finally {
            try { activity?.close() } catch (_: Throwable) { failureCode = failureCode ?: "NATIVE_ACTIVITY_CLOSE_FAILED" }
            if (!handedOff) {
                val outcome = if (sessionSaveAttempted) cleanupOwned(fixture, store, api, client, creationAttempted)
                    else Cleanup(!creationAttempted, false, false, "NO_OWNED_SESSION_INSTALLED")
                result.put("stage", "prepare_failed").put("failure_code", failureCode ?: "NATIVE_PREPARE_FAILED")
                applyCleanup(result, outcome)
                // A run-qualified input can be removed without touching another invocation's credentials.
                result.put("private_input_removed", !input.exists() || input.delete())
                if (outcome.removed && outcome.cleared && outcome.loggedOut && checkpoint.exists()) {
                    val removed = checkpoint.delete()
                    result.put("checkpoint_removed", removed)
                    if (!removed) result.put("cleanup_pending", true).put("cleanup_failure_code", "CHECKPOINT_REMOVAL_FAILED")
                }
                writeResult(fixture, result)
            }
            api.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        requireSmoke(handedOff && failureCode == null, failureCode ?: "NATIVE_PREPARE_FAILED")
    }

    @Test fun cleanupNativeMarker() = runBlocking {
        val fixture = guardedFixture()
        val store = AndroidSessionStore(fixture.context)
        val client = client()
        val api = api(store, client)
        val checkpointFile = fixture.context.filesDir.resolve(CHECKPOINT)
        val result = metadata(fixture).put("stage", "cleanup_failed").put("cleanup_pid", Process.myPid())
        var validated = false
        var failureCode: String? = null
        var outcome = Cleanup(false, false, false, null)
        try {
            val checkpoint = boundedJson(checkpointFile, 4_096)
            requireCheckpoint(checkpoint, fixture)
            validated = true
            result.put("creation_attempted", true).put("native_created", checkpoint.opt("native_created") == true)
                .put("native_ui_read", checkpoint.opt("native_ui_read") == true)
            outcome = cleanupOwned(fixture, store, api, client, creationAttempted = true)
            failureCode = outcome.failure
            if (outcome.removed && outcome.cleared && outcome.loggedOut) {
                requireSmoke(checkpointFile.delete(), "CHECKPOINT_REMOVAL_FAILED")
            }
        } catch (failure: Throwable) {
            failureCode = safeFailure(failure)
        } finally {
            result.put("checkpoint_validated", validated)
            result.put("stage", if (failureCode == null && outcome.removed && outcome.cleared && outcome.loggedOut) "cleanup_passed" else "cleanup_failed")
            failureCode?.let { result.put("failure_code", it) }
            applyCleanup(result, outcome)
            result.put("cleanup_pending", failureCode != null || !outcome.removed || !outcome.cleared || !outcome.loggedOut)
            writeResult(fixture, result)
            api.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        requireSmoke(failureCode == null && outcome.removed && outcome.cleared && outcome.loggedOut, failureCode ?: "NATIVE_CLEANUP_INCOMPLETE")
    }

    private suspend fun cleanupOwned(
        fixture: Fixture, store: AndroidSessionStore, api: SupabaseApi, client: OkHttpClient, creationAttempted: Boolean,
    ): Cleanup = withContext(NonCancellable + Dispatchers.IO) {
        val current = try { store.load() } catch (_: Throwable) { return@withContext Cleanup(false, false, false, "LOCAL_SESSION_READ_FAILED") }
        if (current == null) return@withContext Cleanup(!creationAttempted, true, false, "OWNED_SESSION_ABSENT")
        if (!isOwned(current, fixture)) return@withContext Cleanup(false, false, false, "DIFFERENT_LOCAL_SESSION_PRESERVED")
        var removed = !creationAttempted
        var loggedOut = false
        var cleared = false
        var failureCode: String? = null
        try {
            assertServerIdentity(client, current)
            if (creationAttempted) {
                val rows = selectMarker(api, fixture)
                requireSmoke(rows.length() <= 1 && (rows.length() == 0 || ownRow(rows.getJSONObject(0), fixture)), "CHANGED_CLEANUP_PAYLOAD_PRESERVED")
                if (rows.length() == 1) {
                    requireOwnedStore(store, fixture)
                    // Freeze every expected mutable field; a concurrent change must prevent this DELETE.
                    try { api.delete("transactions", fixture.runId, deleteQuery(fixture)) }
                    catch (failure: Throwable) { failureCode = safeFailure(failure) }
                }
                // Read UUID/owner without a marker filter, so changed payload cannot appear absent.
                requireSmoke(selectMarker(api, fixture).length() == 0, "MARKER_CLEANUP_PENDING")
                removed = true
                // A lost DELETE response is reconciled; no second DELETE is issued.
                failureCode = null
            }
        } catch (failure: Throwable) {
            failureCode = safeFailure(failure)
        } finally {
            try {
                val saved = store.load()
                requireSmoke(saved != null && isOwned(saved, fixture), "LOGOUT_OWNER_CHANGED_PRESERVED")
                val request = Request.Builder().url("${BuildConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/logout?scope=local")
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY).header("Authorization", "Bearer ${saved!!.accessToken}")
                    .post("{}".toRequestBody("application/json".toMediaType())).build()
                client.newCall(request).execute().use { response ->
                    requireSmoke(response.isSuccessful, "LOCAL_SERVER_LOGOUT_NOT_CONFIRMED")
                }
                loggedOut = true
            } catch (failure: Throwable) { failureCode = failureCode ?: safeFailure(failure) }
            finally {
                try {
                    val after = store.load()
                    if (after == null) cleared = true
                    else if (isOwned(after, fixture)) { store.clear(); cleared = store.load() == null }
                    else failureCode = failureCode ?: "DIFFERENT_LOCAL_SESSION_PRESERVED"
                } catch (_: Throwable) { failureCode = failureCode ?: "LOCAL_SESSION_CLEAR_FAILED" }
            }
        }
        Cleanup(removed, cleared, loggedOut, failureCode)
    }

    private fun guardedFixture(): Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        requireSmoke(args.getString("realAndroidWebSmoke") == "true", "EXPLICIT_OPT_IN_REQUIRED")
        val runId = canonicalAuthSessionId(args.getString("androidWebRunId")) ?: throw SmokeFailure("INVALID_RUN_UUID")
        val sessionId = canonicalAuthSessionId(args.getString("androidWebSessionId")) ?: throw SmokeFailure("INVALID_SESSION_UUID")
        val dayText = args.getString("androidWebDate") ?: throw SmokeFailure("DATE_ARGUMENT_REQUIRED")
        val day = runCatching { LocalDate.parse(dayText) }.getOrNull() ?: throw SmokeFailure("INVALID_DATE_ARGUMENT")
        requireSmoke(day.toString() == dayText, "INVALID_DATE_ARGUMENT")
        val context = instrumentation.targetContext
        requireSmoke(BuildConfig.DEBUG && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0, "DEBUG_APPLICATION_REQUIRED")
        val uri = URI(BuildConfig.SUPABASE_URL)
        requireSmoke(context.packageName == "cc.savely.android" && uri.scheme == "https" && uri.host == PROJECT_HOST &&
            uri.port == -1 && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in listOf("", "/"), "APPLICATION_OR_PROJECT_MISMATCH")
        return Fixture(context, runId, sessionId, day)
    }

    private suspend fun assertServerIdentity(client: OkHttpClient, session: Session) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${BuildConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/user")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY).header("Authorization", "Bearer ${session.accessToken}").build()
        val user = client.newCall(request).execute().use { response ->
            requireSmoke(response.isSuccessful, "SERVER_IDENTITY_NOT_CONFIRMED")
            val source = response.body?.source() ?: throw SmokeFailure("EMPTY_IDENTITY_RESPONSE")
            requireSmoke(!source.request(65_537), "IDENTITY_RESPONSE_TOO_LARGE")
            ExactJson.parse(source.readUtf8()) as? JSONObject ?: throw SmokeFailure("INVALID_IDENTITY_RESPONSE")
        }
        requireSmoke(user.optString("id") == QA_USER && user.optString("email") == QA_EMAIL &&
            user.has("email_confirmed_at") && !user.isNull("email_confirmed_at"), "SERVER_QA_IDENTITY_MISMATCH")
        val factors = user.optJSONArray("factors")
        requireSmoke(!user.has("factors") || user.isNull("factors") || factors != null, "INVALID_MFA_IDENTITY_RESPONSE")
        requireSmoke(factors == null || (0 until factors.length()).none { factors.optJSONObject(it)?.optString("status") == "verified" }, "QA_MFA_REQUIRED")
    }

    private fun requireCheckpoint(checkpoint: JSONObject, fixture: Fixture) {
        requireSmoke(checkpoint.optString("run_id") == fixture.runId && checkpoint.optString("session_id") == fixture.sessionId &&
            checkpoint.optString("user_id") == QA_USER && checkpoint.optString("email") == QA_EMAIL && checkpoint.optString("project_host") == PROJECT_HOST &&
            checkpoint.optString("transaction_id") == fixture.runId && checkpoint.optString("marker") == fixture.marker &&
            checkpoint.optString("date") == fixture.day.toString() && checkpoint.opt("amount") == "0.01" && checkpoint.opt("currency") == "EUR" &&
            checkpoint.opt("type") == "expense" && checkpoint.has("category_id") && checkpoint.isNull("category_id") &&
            checkpoint.opt("exchange_rate_eur") == "1" && checkpoint.opt("creation_attempted") == true &&
            checkpoint.optString("stage") in listOf("creating", "native_prepared"), "CHECKPOINT_OWNER_OR_PAYLOAD_MISMATCH")
    }

    private suspend fun selectMarker(api: SupabaseApi, fixture: Fixture): JSONArray = api.select("transactions",
        "select=id,user_id,description,amount,currency,date,type,category_id,exchange_rate_eur&id=eq.${fixture.runId}&user_id=eq.$QA_USER&limit=2")
    private fun deleteQuery(fixture: Fixture) = "user_id=eq.$QA_USER&description=eq.${fixture.marker}&amount=eq.0.01&currency=eq.EUR&" +
        "date=eq.${fixture.day}&type=eq.expense&category_id=is.null&exchange_rate_eur=eq.1"
    private fun ownRow(row: JSONObject, fixture: Fixture): Boolean = row.optString("id") == fixture.runId && row.optString("user_id") == QA_USER &&
        row.optString("description") == fixture.marker && decimalEquals(row.opt("amount"), "0.01") && row.optString("currency") == "EUR" &&
        row.optString("date") == fixture.day.toString() && row.optString("type") == "expense" && row.has("category_id") && row.isNull("category_id") &&
        decimalEquals(row.opt("exchange_rate_eur"), "1")
    private fun decimalEquals(value: Any?, expected: String) = runCatching { BigDecimal(value.toString()).compareTo(BigDecimal(expected)) == 0 }.getOrDefault(false)
    private fun expectedPayload(fixture: Fixture) = JSONObject().put("id", fixture.runId).put("user_id", QA_USER).put("description", fixture.marker)
        .put("amount", "0.01").put("currency", "EUR").put("date", fixture.day.toString()).put("type", "expense")
        .put("category_id", JSONObject.NULL).put("exchange_rate_eur", "1")
    private fun metadata(fixture: Fixture) = JSONObject().put("run_id", fixture.runId).put("session_id", fixture.sessionId)
        .put("user_id", QA_USER).put("email", QA_EMAIL).put("project_host", PROJECT_HOST).put("transaction_id", fixture.runId)
        .put("marker", fixture.marker).put("amount", "0.01").put("currency", "EUR").put("date", fixture.day.toString()).put("type", "expense")
        .put("category_id", JSONObject.NULL).put("exchange_rate_eur", "1").put("creation_attempted", false)
        .put("native_created", false).put("native_ui_read", false).put("transaction_removed", false)
        .put("cleanup_local", false).put("logout_server", false).put("cleanup_pending", true)
    private fun applyCleanup(result: JSONObject, outcome: Cleanup) {
        result.put("transaction_removed", outcome.removed).put("cleanup_local", outcome.cleared).put("logout_server", outcome.loggedOut)
            .put("cleanup_pending", !outcome.removed || !outcome.cleared || !outcome.loggedOut)
        outcome.failure?.let { result.put("cleanup_failure_code", it) }
    }
    private fun isOwned(session: Session, fixture: Fixture) = session.userId == QA_USER && session.email == QA_EMAIL && session.authSessionId() == fixture.sessionId
    private fun requireOwnedStore(store: AndroidSessionStore, fixture: Fixture) {
        requireSmoke(store.load()?.let { isOwned(it, fixture) } == true, "LOCAL_SESSION_OWNER_CHANGED")
    }
    private fun boundedJson(file: File, maxBytes: Long): JSONObject {
        requireSmoke(file.isFile && file.length() in 1..maxBytes, "PRIVATE_INPUT_OR_CHECKPOINT_INVALID")
        // Keep the read bounded even if another host operation changes the file after the length check.
        val buffer = ByteArray(maxBytes.toInt() + 1)
        var count = 0
        file.inputStream().use { input ->
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
        }
        requireSmoke(count in 1..maxBytes.toInt(), "PRIVATE_INPUT_OR_CHECKPOINT_INVALID")
        return ExactJson.parse(String(buffer, 0, count, Charsets.UTF_8)) as? JSONObject ?: throw SmokeFailure("PRIVATE_JSON_OBJECT_REQUIRED")
    }
    private fun writeResult(fixture: Fixture, result: JSONObject) = writeJson(fixture.context.filesDir.resolve(RESULT), result)
    private fun writeJson(file: File, value: JSONObject) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
    private fun safeFailure(failure: Throwable) = when (failure) {
        is SmokeFailure -> failure.code
        is ApiException -> "API_${failure.kind.name}_${failure.status ?: 0}"
        else -> "NATIVE_OPERATION_FAILED"
    }
    private fun requireSmoke(condition: Boolean, code: String) { if (!condition) throw SmokeFailure(code) }
    private fun client() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()
    private fun api(store: SessionStore, client: OkHttpClient) = SupabaseApi(
        BackendConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, BuildConfig.WEB_URL), store, client)
    private class SmokeFailure(val code: String) : IllegalStateException(code)
    private data class Fixture(val context: Context, val runId: String, val sessionId: String, val day: LocalDate) {
        val marker get() = "Android-Web-QA-$runId"
    }
    private data class Cleanup(val removed: Boolean, val cleared: Boolean, val loggedOut: Boolean, val failure: String?)
    private companion object {
        const val CHECKPOINT = "android-web-checkpoint.json"
        const val RESULT = "android-web-native-result.json"
        const val PROJECT_HOST = "crqnfbahytzenisospcx.supabase.co"
        const val QA_USER = "9daffd41-39bf-4b1e-be68-abf12549c4a7"
        const val QA_EMAIL = "e2e-savely@example.com"
    }
}
