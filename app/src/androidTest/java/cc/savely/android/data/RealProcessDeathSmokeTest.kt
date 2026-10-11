package cc.savely.android.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.savely.android.BuildConfig
import cc.savely.android.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Opt-in two-process smoke on the dedicated SavelyTest AVD. The host must force-stop the target
 * between these individually selected test methods. Standard instrumentation excludes this class.
 * No password, email, financial record or backend configuration is changed.
 */
@RunWith(AndroidJUnit4::class)
class RealProcessDeathSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun persistQaSessionBeforeProcessDeath() = runBlocking {
        val fixture = guardedFixture()
        val input = fixture.context.filesDir.resolve(INPUT)
        val checkpointFile = fixture.context.filesDir.resolve(CHECKPOINT)
        val store = AndroidSessionStore(fixture.context)
        // Preserve any pre-existing installation, including a different session of the same QA account.
        check(store.load() == null) { "A pre-existing local session must not be overwritten." }
        check(!checkpointFile.exists()) { "An existing process-death checkpoint requires explicit host cleanup." }
        val source = boundedJson(input, 16_384)
        val bootstrap = Session.fromJson(source)
        check(isQa(bootstrap)) { "Bootstrap identity does not match the documented QA account." }
        val sessionId = requireNotNull(bootstrap.authSessionId()) { "Bootstrap requires a canonical JWT session_id." }
        check(bootstrap.expiresAt > Instant.now().epochSecond + 120) { "Bootstrap requires at least two minutes of validity." }
        if (source.has("project_url")) check(isProject(source.getString("project_url"))) { "Bootstrap belongs to an unexpected project." }
        val client = client()
        val api = api(store, client)
        val metadata = metadata(fixture.runId).put("first_pid", Process.myPid())
            .put("first_process_start_elapsed_ms", Process.getStartElapsedRealtime())
            .put("checkpoint_elapsed_ms", SystemClock.elapsedRealtime()).put("session_id", sessionId)
        var persisted = false
        var saveAttempted = false
        var primaryFailure: Throwable? = null
        try {
            assertServerIdentity(client, bootstrap)
            saveAttempted = true
            withContext(Dispatchers.IO) { store.save(bootstrap) }
            check(input.delete()) { "Unable to remove the consumed private session input." }
            check(AndroidSessionStore(fixture.context).load() == bootstrap) { "Encrypted session reread failed." }
            writeJson(checkpointFile, JSONObject(metadata.toString()).put("stage", "persisted"))
            writeJson(fixture.context.filesDir.resolve(RESULT), JSONObject(metadata.toString()).put("stage", "persisted")
                .put("cleanup_pending", true).put("local_session_cleared", false).put("server_logout_confirmed", false)
                .put("cleanup_local", false).put("logout_server", false))
            // Intentionally retain only the Keystore-backed session until phase two.
            persisted = true
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                if (!persisted) {
                    val outcome = if (saveAttempted) cleanupOwnSession(api, store, sessionId) else Cleanup(false, false, false, null)
                    input.delete()
                    checkpointFile.delete()
                    writeJson(fixture.context.filesDir.resolve(RESULT), JSONObject(metadata.toString()).put("stage", "persist_failed")
                        .put("cleanup_pending", outcome.owned && !outcome.serverLogoutConfirmed)
                        .put("local_session_cleared", outcome.localCleared).put("server_logout_confirmed", outcome.serverLogoutConfirmed)
                        .put("cleanup_local", outcome.localCleared).put("logout_server", outcome.serverLogoutConfirmed))
                    outcome.failure?.let { if (primaryFailure != null) primaryFailure?.addSuppressed(it) else throw it }
                }
            } finally {
                api.close()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun restoreQaSessionAfterProcessDeath() = runBlocking {
        val fixture = guardedFixture()
        val checkpointFile = fixture.context.filesDir.resolve(CHECKPOINT)
        val checkpoint = boundedJson(checkpointFile, 4_096)
        check(checkpoint.optString("run_id") == fixture.runId && checkpoint.optString("stage") == "persisted") { "Checkpoint does not match this invocation." }
        check(checkpoint.optString("user_id") == QA_USER && checkpoint.optString("email") == QA_EMAIL &&
            checkpoint.optString("project_host") == PROJECT_HOST) { "Checkpoint identity or project does not match the QA fixture." }
        val sessionId = requireNotNull(canonicalAuthSessionId(checkpoint.optString("session_id"))) { "Checkpoint session_id is invalid." }
        val store = AndroidSessionStore(fixture.context)
        val restored = requireNotNull(store.load()) { "No encrypted session survived the process termination." }
        // Do not clear or revoke a foreign user or another session if the dedicated installation changed.
        check(isQa(restored) && restored.authSessionId() == sessionId) { "Stored session belongs to another owner or invocation; preserve it." }
        val client = client()
        val api = api(store, client)
        val result = JSONObject(checkpoint.toString()).put("second_pid", Process.myPid())
            .put("second_process_start_elapsed_ms", Process.getStartElapsedRealtime())
            .put("restore_elapsed_ms", SystemClock.elapsedRealtime())
        var activity: ActivityScenario<MainActivity>? = null
        var primaryFailure: Throwable? = null
        var verified = false
        try {
            check(!fixture.context.filesDir.resolve(INPUT).exists()) { "Phase two must not receive a new plaintext bootstrap." }
            check(Process.myPid() != checkpoint.getInt("first_pid")) { "Phase two must run in a different target PID." }
            check(Process.getStartElapsedRealtime() > checkpoint.getLong("first_process_start_elapsed_ms")) { "The target process was not restarted." }
            check(Process.getStartElapsedRealtime() > checkpoint.getLong("checkpoint_elapsed_ms")) { "The second process started before the persistence checkpoint." }
            val current = requireNotNull(api.session()) { "Restored session is no longer valid." }
            check(isQa(current) && current.authSessionId() == sessionId) { "Session identity changed while restoring." }
            assertServerIdentity(client, current)
            val launched = ActivityScenario.launch(MainActivity::class.java)
            activity = launched
            compose.waitUntil(45_000) { compose.onAllNodesWithText("Dashboard", substring = false).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(45_000) { compose.onAllNodesWithText("Mese corrente fino a oggi", substring = false).fetchSemanticsNodes().isNotEmpty() }
            val afterLaunch = requireNotNull(AndroidSessionStore(fixture.context).load()) { "Native application did not retain the restored session." }
            check(isQa(afterLaunch) && afterLaunch.authSessionId() == sessionId) { "Native application changed the expected QA session." }
            assertServerIdentity(client, afterLaunch)
            verified = true
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            try { activity?.close() }
            catch (closeFailure: Throwable) {
                if (primaryFailure == null) primaryFailure = closeFailure else primaryFailure?.addSuppressed(closeFailure)
            }
            val outcome = cleanupOwnSession(api, store, sessionId)
            outcome.failure?.let { if (primaryFailure == null) primaryFailure = it else primaryFailure?.addSuppressed(it) }
            if (!outcome.owned && primaryFailure == null) primaryFailure = IllegalStateException("Cleanup preserved a different stored session.")
            result.put("stage", if (verified && primaryFailure == null && outcome.localCleared && outcome.serverLogoutConfirmed) "passed" else "restore_failed")
                .put("new_process_verified", Process.myPid() != checkpoint.getInt("first_pid") && Process.getStartElapsedRealtime() > checkpoint.getLong("checkpoint_elapsed_ms"))
                .put("native_dashboard_verified", verified).put("local_session_cleared", outcome.localCleared)
                .put("server_logout_confirmed", outcome.serverLogoutConfirmed).put("cleanup_pending", !outcome.serverLogoutConfirmed)
                .put("cleanup_local", outcome.localCleared).put("logout_server", outcome.serverLogoutConfirmed)
            try { writeJson(fixture.context.filesDir.resolve(RESULT), result) }
            finally {
                // The result preserves the non-secret process proof; this invocation consumes its checkpoint.
                if (outcome.localCleared) checkpointFile.delete()
                api.close()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
        primaryFailure?.let { throw it }
        check(result.getString("stage") == "passed") { "Process-death restore or cleanup did not complete." }
    }

    /** Host fallback after an interrupted two-phase run. Never clears an unrelated local session. */
    @Test fun cleanupQaSessionAfterInterruptedProcessDeath() = runBlocking {
        val fixture = guardedFixture()
        val checkpointFile = fixture.context.filesDir.resolve(CHECKPOINT)
        val checkpoint = boundedJson(checkpointFile, 4_096)
        check(checkpoint.optString("run_id") == fixture.runId && checkpoint.optString("stage") == "persisted") {
            "Cleanup requires this invocation's persistence checkpoint."
        }
        check(checkpoint.optString("user_id") == QA_USER && checkpoint.optString("email") == QA_EMAIL &&
            checkpoint.optString("project_host") == PROJECT_HOST) { "Cleanup checkpoint does not belong to the documented QA fixture." }
        val sessionId = requireNotNull(canonicalAuthSessionId(checkpoint.optString("session_id"))) { "Cleanup checkpoint session_id is invalid." }
        val store = AndroidSessionStore(fixture.context)
        val saved = requireNotNull(store.load()) { "No checkpoint-owned session is available for cleanup." }
        check(isQa(saved) && saved.authSessionId() == sessionId) { "Cleanup must preserve a different owner or session." }
        val client = client()
        val api = api(store, client)
        val result = JSONObject(checkpoint.toString()).put("cleanup_pid", Process.myPid())
            .put("cleanup_process_start_elapsed_ms", Process.getStartElapsedRealtime())
        try {
            val outcome = cleanupOwnSession(api, store, sessionId)
            result.put("stage", if (outcome.owned && outcome.localCleared && outcome.serverLogoutConfirmed) "cleanup_passed" else "cleanup_failed")
                .put("cleanup_local", outcome.localCleared).put("logout_server", outcome.serverLogoutConfirmed)
                .put("local_session_cleared", outcome.localCleared).put("server_logout_confirmed", outcome.serverLogoutConfirmed)
                .put("cleanup_pending", !outcome.localCleared || !outcome.serverLogoutConfirmed)
            writeJson(fixture.context.filesDir.resolve(RESULT), result)
            if (outcome.localCleared) check(checkpointFile.delete()) { "Unable to remove this invocation's cleanup checkpoint." }
            outcome.failure?.let { throw it }
            check(result.getString("stage") == "cleanup_passed") { "Checkpoint-owned QA cleanup was not completed." }
        } finally {
            api.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun guardedFixture(): Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("realProcessDeathSmoke") == "true") { "Explicit realProcessDeathSmoke=true is required." }
        val runId = requireNotNull(canonicalAuthSessionId(arguments.getString("processDeathRunId"))) { "An explicit processDeathRunId UUID is required." }
        val context = instrumentation.targetContext
        check(BuildConfig.DEBUG && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "An installed debug application is required." }
        check(context.packageName == "cc.savely.android" && isProject(BuildConfig.SUPABASE_URL)) { "Unexpected application or Supabase project." }
        return Fixture(context, runId)
    }

    private suspend fun assertServerIdentity(client: OkHttpClient, session: Session) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${BuildConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/user")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY).header("Authorization", "Bearer ${session.accessToken}").build()
        val user = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Backend identity verification failed (${response.code})." }
            val source = requireNotNull(response.body) { "Empty identity response." }.source()
            check(!source.request(65_537)) { "Unexpectedly large identity response." }
            ExactJson.parse(source.readUtf8()) as? JSONObject ?: error("Invalid identity response.")
        }
        check(user.optString("id") == QA_USER && user.optString("email") == QA_EMAIL) { "Server identity does not match the existing QA account." }
        check(user.has("email_confirmed_at") && !user.isNull("email_confirmed_at")) { "The QA email must already be confirmed." }
        val factors = user.optJSONArray("factors")
        check(factors == null || (0 until factors.length()).none { factors.optJSONObject(it)?.optString("status") == "verified" }) {
            "The QA account now requires MFA; stop and use normal authentication."
        }
    }

    private suspend fun cleanupOwnSession(api: SupabaseApi, store: AndroidSessionStore, sessionId: String): Cleanup = withContext(NonCancellable + Dispatchers.IO) {
        val current = try { store.load() } catch (failure: Throwable) { return@withContext Cleanup(false, false, false, failure) }
        if (current == null || !isQa(current) || current.authSessionId() != sessionId) return@withContext Cleanup(false, current == null, false, null)
        var failure: Throwable? = null
        var loggedOut = false
        var cleared = false
        try { api.signOut(); loggedOut = true }
        catch (logoutFailure: Throwable) { failure = logoutFailure }
        finally {
            // signOut also clears on HTTP failure. Repeat only local deletion, never the server request.
            try { store.clear(); cleared = store.load() == null }
            catch (clearFailure: Throwable) { if (failure == null) failure = clearFailure else failure.addSuppressed(clearFailure) }
        }
        Cleanup(true, cleared, loggedOut, failure)
    }

    private fun metadata(runId: String) = JSONObject().put("run_id", runId).put("user_id", QA_USER)
        .put("email", QA_EMAIL).put("project_host", PROJECT_HOST)
    private fun isQa(session: Session) = session.userId == QA_USER && session.email == QA_EMAIL
    private fun isProject(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == PROJECT_HOST && uri.port == -1 && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path in listOf("", "/")
    }.getOrDefault(false)
    private fun boundedJson(file: File, maxBytes: Long): JSONObject {
        check(file.isFile && file.length() in 1..maxBytes) { "Private process-death input/checkpoint is absent or invalid." }
        return ExactJson.parse(file.readText(Charsets.UTF_8)) as? JSONObject ?: error("Private input/checkpoint must be a JSON object.")
    }
    private fun writeJson(file: File, json: JSONObject) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
    private fun client() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()
    private fun api(store: SessionStore, client: OkHttpClient) = SupabaseApi(
        BackendConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, BuildConfig.WEB_URL), store, client)
    private data class Fixture(val context: Context, val runId: String)
    private data class Cleanup(val owned: Boolean, val localCleared: Boolean, val serverLogoutConfirmed: Boolean, val failure: Throwable?)
    private companion object {
        const val INPUT = "backend-process-death-session.json"
        const val CHECKPOINT = "backend-process-death-checkpoint.json"
        const val RESULT = "backend-process-death-result.json"
        const val PROJECT_HOST = "crqnfbahytzenisospcx.supabase.co"
        const val QA_USER = "9daffd41-39bf-4b1e-be68-abf12549c4a7"
        const val QA_EMAIL = "e2e-savely@example.com"
    }
}
