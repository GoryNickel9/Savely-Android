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
import okio.Buffer
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit opt-in repositories/transport qualification, never an ordinary fixture-suite test.
 * Admin's complete user response remains in RAM; only the three reserved QA identities are extracted.
 * No user deletion, recovery email, registration change, or direct privileged credential is allowed.
 */
@RunWith(AndroidJUnit4::class)
class RealAdminQualificationTest {
    @Test fun approvedAdminReadDenialAndBPermissionsRestoration() = runBlocking<Unit> { execute(false) }
    @Test fun recoverExactPermissionChange() = runBlocking<Unit> { execute(true) }

    private suspend fun execute(recovery: Boolean) {
        val args = InstrumentationRegistry.getArguments()
        safe(args.getString("adminQualification") == "approved-after-auth-couple", "EXPLICIT_OPT_IN_REQUIRED")
        val run = args.getString("adminRunId") ?: fail("RUN_ID_REQUIRED")
        safe(UUID.fromString(run).toString() == run, "INVALID_RUN_ID")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        safe(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0, "DEBUG_APP_REQUIRED")
        safe(context.getSharedPreferences("savely_session", Context.MODE_PRIVATE).all.isEmpty(), "EXISTING_APP_SESSION")
        safe(BuildConfig.SUPABASE_URL.trimEnd('/') == BASE, "UNEXPECTED_PROJECT")
        safe(!context.filesDir.resolve(RESULT).exists(), "PREVIOUS_RESULT_PRESERVED")
        val input = context.filesDir.resolve(INPUT)
        safe(input.isFile && input.length() in 1..65_536, "PRIVATE_INPUT_REQUIRED")
        val credentials = try { JSONObject(input.readText(Charsets.UTF_8)) } catch (_: Exception) { fail("PRIVATE_INPUT_INVALID") }
        safe(credentials.getString("project") == PROJECT && credentials.getString("runId") == QA_RUN, "INPUT_IDENTITY_CHANGED")
        val accounts = credentials.getJSONArray("accounts")
        safe(accounts.length() == USERS.size, "INPUT_ACCOUNTS_CHANGED")
        val privateAccounts = (0 until accounts.length()).map { accounts.getJSONObject(it) }.associateBy { it.getString("email") }
        safe(privateAccounts.keys == USERS.keys && privateAccounts.all { (email, row) ->
            row.optString("id") == USERS[email] && row.optString("password").length >= 40
        }, "INPUT_IDENTITY_CHANGED")
        safe(input.delete(), "PRIVATE_INPUT_NOT_CONSUMED")
        val journal = Journal(context.filesDir.resolve(LEDGER), run)
        safe(journal.exists() == recovery, "RECOVERY_MODE_MISMATCH")
        val store = MemorySessionStore()
        val guard = Guard(store, journal)
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).addInterceptor(guard).build()
        val api = SupabaseApi(BackendConfig(BASE, BuildConfig.SUPABASE_ANON_KEY, WEB), store, client)
        var phase = "LOGIN"
        var failureCode: String? = null
        var restored = false
        var logoutConfirmed = false
        try {
            suspend fun login(email: String) {
                guard.login = privateAccounts.getValue(email)
                val session = api.signIn(email, privateAccounts.getValue(email).getString("password"))
                safe(session.userId == USERS[email] && session.email == email && session.authSessionId() != null, "LOGIN_IDENTITY_CHANGED")
            }
            if (!recovery) {
                for (email in listOf(A_EMAIL, B_EMAIL)) {
                    phase = "STANDARD_DENIAL"
                    login(email)
                    safe(api.permissions()["admin"] != true, "STANDARD_ACCOUNT_HAS_ADMIN")
                    val owner = api.session() ?: fail("STANDARD_SESSION_MISSING")
                    val revision = api.sessionOwnerRevision(owner) ?: fail("STANDARD_SESSION_CHANGED")
                    var denied = false
                    try { api.webPost("/api/admin", JSONObject().put("action", "get-users"),
                        expectedOwner = owner.userId, expectedRevision = revision) }
                    catch (error: ApiException) { denied = error.status == 403 }
                    safe(denied, "STANDARD_ADMIN_ENDPOINT_NOT_DENIED")
                    api.signOut()
                }
            }
            phase = "ADMIN_READ"
            login(ADMIN_EMAIL)
            safe(api.permissions()["admin"] == true, "AUTHORIZED_ADMIN_MISSING")
            val registrations = api.rpc("get_registrations_enabled")
            safe(registrations == false, "REGISTRATIONS_MUST_BE_CLOSED")
            val before = qaRows(api)
            if (!recovery) {
                val profile = targetProfile(api)
                val original = profile.getJSONObject("permissions")
                safe(original.opt("admin") == false && original.opt("tcg") is Boolean &&
                    original.keys().asSequence().all { original.get(it) is Boolean }, "TARGET_NOT_STANDARD")
                val changed = JSONObject(original.toString()).put("tcg", !original.getBoolean("tcg"))
                journal.initialize(profile.getString("id"), original, changed, before, registrations as Boolean)
                phase = "CHANGE_QA_B"
                write(api, guard, journal, "change", original, changed)
                safe(equal(targetProfile(api).getJSONObject("permissions"), changed), "CHANGE_NOT_CONFIRMED")
                val after = qaRows(api)
                safe(equal(after.getValue(A_EMAIL), before.getValue(A_EMAIL)) &&
                    equal(after.getValue(ADMIN_EMAIL), before.getValue(ADMIN_EMAIL)), "OTHER_QA_PERMISSIONS_CHANGED")
            } else journal.validate()
        } catch (_: Exception) {
            // Native API error strings/causes may contain response data; reports use phase constants only.
            failureCode = phase
        } finally {
            withContext(NonCancellable) {
                try {
                    if (journal.exists() && store.load()?.userId == USERS[ADMIN_EMAIL]) {
                        phase = "RESTORE_QA_B"
                        journal.validate()
                        val saved = journal.read()
                        val original = saved.getJSONObject("original")
                        val changed = saved.getJSONObject("changed")
                        val current = targetProfile(api).getJSONObject("permissions")
                        if (equal(current, changed)) {
                            write(api, guard, journal, "restore", changed, original)
                        } else {
                            safe(equal(current, original), "TARGET_CONCURRENT_CHANGE")
                            // A lost change response plus an old read does not exclude a late commit.
                            safe(saved.optString("change_response") in listOf("accepted", "rejected") ||
                                !saved.optBoolean("change_attempted"), "CHANGE_OUTCOME_UNCERTAIN")
                        }
                        safe(equal(targetProfile(api).getJSONObject("permissions"), original), "RESTORE_NOT_CONFIRMED")
                        val after = qaRows(api)
                        val previous = saved.getJSONObject("qa_before")
                        safe(USERS.keys.all { equal(after.getValue(it), previous.getJSONObject(it)) }, "QA_PERMISSION_SNAPSHOT_CHANGED")
                        safe(api.rpc("get_registrations_enabled") == saved.getBoolean("registrations"), "REGISTRATION_STATE_CHANGED")
                        journal.mark("restored", true)
                        restored = true
                    }
                } catch (_: Exception) { failureCode = "RESTORE_PENDING" }
                try {
                    if (store.load() != null) api.signOut()
                    logoutConfirmed = true
                } catch (_: Exception) { failureCode = "LOGOUT_PENDING" }
                api.close()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
        safe(guard.denied == 0, "TRANSPORT_GUARD_REJECTED_REQUEST")
        safe(restored && logoutConfirmed && failureCode == null, failureCode ?: "QUALIFICATION_INCOMPLETE")
        // Keep only nonsecret counts/booleans, not real-user lists, credentials or HTTP bodies.
        val result = JSONObject().put("schemaVersion", 1).put("runId", run).put("status", "passed")
            .put("scope", "native-repository-admin-read-denial-and-QA-B-permission-restore")
            .put("recovery", recovery).put("permissionsRestored", true).put("localLogout", true)
            .put("deniedUnexpectedRequests", guard.denied).put("permissionPatchRequests", journal.read().optInt("patch_count"))
            .put("qaLoginAuditRequests", guard.auditRequests)
            .put("emailRequests", 0).put("deleteRequests", 0).put("registrationWrites", 0)
            .put("otherUserWrites", 0).put("fullUserListPersisted", false)
        atomicWrite(context.filesDir.resolve(RESULT), result)
        AtomicFile(context.filesDir.resolve(LEDGER)).delete()
    }

    private suspend fun qaRows(api: SupabaseApi): Map<String, JSONObject> {
        val owner = api.session() ?: fail("ADMIN_SESSION_MISSING")
        safe(owner.userId == USERS[ADMIN_EMAIL], "ADMIN_SESSION_CHANGED")
        val revision = api.sessionOwnerRevision(owner) ?: fail("ADMIN_SESSION_CHANGED")
        val users = api.webPost("/api/admin", JSONObject().put("action", "get-users"),
            expectedOwner = owner.userId, expectedRevision = revision).getJSONArray("users")
        val selected = mutableMapOf<String, JSONObject>()
        for (index in 0 until users.length()) {
            val row = users.getJSONObject(index)
            val email = row.optString("email")
            if (email !in USERS) continue
            safe(row.optString("user_id") == USERS[email] && !selected.containsKey(email), "QA_LIST_IDENTITY_CHANGED")
            selected[email] = JSONObject(row.getJSONObject("permissions").toString())
        }
        safe(selected.keys == USERS.keys, "AUTHORIZED_QA_USER_LIST_INCOMPLETE")
        return selected
    }

    private suspend fun targetProfile(api: SupabaseApi): JSONObject {
        qaRows(api) // Recheck exact UUID + email from the authenticated listing before every mutation/read.
        val rows = api.select("profiles", "select=id,user_id,permissions&user_id=eq.${USERS[B_EMAIL]}")
        safe(rows.length() == 1, "TARGET_PROFILE_COUNT")
        return rows.getJSONObject(0).also {
            safe(it.getString("user_id") == USERS[B_EMAIL] && UUID.fromString(it.getString("id")).toString() == it.getString("id"), "TARGET_PROFILE_IDENTITY")
        }
    }

    private suspend fun write(api: SupabaseApi, guard: Guard, journal: Journal, stage: String, expected: JSONObject, desired: JSONObject) {
        val snapshot = api.session() ?: fail("ADMIN_SESSION_MISSING")
        safe(snapshot.userId == USERS[ADMIN_EMAIL], "ADMIN_SESSION_CHANGED")
        val revision = api.sessionOwnerRevision(snapshot) ?: fail("ADMIN_SESSION_CHANGED")
        val profile = targetProfile(api)
        val ledger = journal.read()
        safe(profile.getString("id") == ledger.getString("profile_id") && equal(profile.getJSONObject("permissions"), expected), "TARGET_PRECONDITION_CHANGED")
        guard.plan = Plan(stage, profile.getString("id"), expected.toString(), desired.toString(), snapshot.accessToken)
        try {
            api.update("profiles", profile.getString("id"), JSONObject().put("permissions", desired),
                "user_id=eq.${USERS[B_EMAIL]}&permissions=${URLEncoder.encode("eq.$expected", "UTF-8")}",
                expectedOwner = snapshot.userId, expectedRevision = revision)
        } finally { guard.plan = null }
    }

    private data class Plan(val stage: String, val id: String, val before: String, val after: String, val token: String)
    private class Guard(val store: MemorySessionStore, val journal: Journal) : Interceptor {
        @Volatile var login: JSONObject? = null
        @Volatile var plan: Plan? = null
        var denied = 0
        var auditRequests = 0
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            try { checkRequest(request) } catch (_: Exception) { denied++; throw IOException("ADMIN_QUALIFICATION_REQUEST_BLOCKED") }
            val planned = plan.takeIf { request.method == "PATCH" }
            if (planned != null) journal.reserve(planned.stage)
            var obtained: okhttp3.Response? = null
            try {
                val response = chain.proceed(request)
                obtained = response
                if (request.url.encodedPath == "/auth/v1/token" && response.isSuccessful) {
                    val account = login ?: fail("LOGIN_NOT_PLANNED")
                    val user = JSONObject(response.peekBody(65_536).string()).getJSONObject("user")
                    safe(user.optString("id") == account.getString("id") && user.optString("email") == account.getString("email") &&
                        user.optJSONObject("user_metadata")?.optString("savely_android_qa_run") == QA_RUN, "QA_MARKER_MISSING")
                }
                if (planned != null) journal.response(planned.stage, when {
                    response.isSuccessful -> "accepted"
                    response.code in 400..499 -> "rejected"
                    else -> "uncertain"
                })
                if (request.url.encodedPath == "/rest/v1/login_activity") auditRequests++
                return response
            } catch (error: Exception) {
                obtained?.close()
                if (planned != null) journal.response(planned.stage, "uncertain")
                throw IOException("ADMIN_QUALIFICATION_TRANSPORT_FAILURE")
            }
        }
        private fun checkRequest(request: Request) {
            val url = request.url
            val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }?.let(::JSONObject)
            safe(url.scheme == "https" && url.port == 443 && url.encodedPath in ALLOWED_PATHS, "ENDPOINT_BLOCKED")
            if (url.host == "www.savely.cc") {
                safe(url.encodedPath == "/api/admin" && request.method == "POST" &&
                    body?.length() == 1 && body.optString("action") == "get-users", "WEB_OPERATION_BLOCKED")
            } else {
                safe(url.host == "$PROJECT.supabase.co", "ORIGIN_BLOCKED")
                safe(request.header("apikey") == BuildConfig.SUPABASE_ANON_KEY, "PUBLIC_KEY_CHANGED")
                when (url.encodedPath) {
                    "/auth/v1/token" -> {
                        val account = login ?: fail("LOGIN_NOT_PLANNED")
                        safe(request.method == "POST" && url.queryParameter("grant_type") == "password" && body?.length() == 2 &&
                            body.optString("email") == account.getString("email") && body.optString("password") == account.getString("password"), "LOGIN_BLOCKED")
                        return
                    }
                    "/auth/v1/user" -> safe(request.method == "GET", "USER_WRITE_BLOCKED")
                    "/auth/v1/logout" -> safe(request.method == "POST" && url.queryParameter("scope") == "local", "LOGOUT_SCOPE_BLOCKED")
                    "/rest/v1/rpc/get_registrations_enabled" -> safe(request.method == "POST", "RPC_BLOCKED")
                    "/rest/v1/login_activity" -> {
                        val session = store.load() ?: fail("AUDIT_SESSION_MISSING")
                        safe(request.method == "POST" && body?.length() == 4 && body.optString("user_id") == session.userId &&
                            body.optString("session_id") == session.authSessionId() && body.optString("event_type") == "sign_in", "AUDIT_BLOCKED")
                    }
                    "/rest/v1/profiles" -> if (request.method == "GET") {
                        safe(url.queryParameter("user_id") in listOf("eq.${store.load()?.userId}", "eq.${USERS[B_EMAIL]}"), "PROFILE_READ_BLOCKED")
                    } else {
                        val planned = plan ?: fail("WRITE_NOT_PLANNED")
                        safe(request.method == "PATCH" && store.load()?.userId == USERS[ADMIN_EMAIL] &&
                            url.queryParameter("id") == "eq.${planned.id}" && url.queryParameter("user_id") == "eq.${USERS[B_EMAIL]}" &&
                            url.queryParameter("permissions") == "eq.${planned.before}" && body?.length() == 1 &&
                            equal(body.getJSONObject("permissions"), JSONObject(planned.after)) &&
                            request.header("Authorization") == "Bearer ${planned.token}", "PROFILE_WRITE_BLOCKED")
                    }
                    else -> fail("OPERATION_BLOCKED")
                }
            }
            val session = store.load() ?: fail("SESSION_MISSING")
            safe(session.userId in USERS.values && request.header("Authorization") == "Bearer ${session.accessToken}", "SESSION_CHANGED")
        }
    }

    private class Journal(private val file: File, private val run: String) {
        fun exists() = file.isFile || File(file.path + ".bak").isFile
        @Synchronized fun read(): JSONObject = JSONObject(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }).also {
            safe(it.getInt("version") == 1 && it.getString("runId") == run && it.getString("target") == USERS[B_EMAIL], "LEDGER_IDENTITY_CHANGED")
        }
        fun validate() {
            val current = read()
            val original = current.getJSONObject("original")
            val changed = current.getJSONObject("changed")
            safe(UUID.fromString(current.getString("profile_id")).toString() == current.getString("profile_id") &&
                original.opt("admin") == false && original.opt("tcg") is Boolean &&
                equal(changed, JSONObject(original.toString()).put("tcg", !original.getBoolean("tcg"))), "LEDGER_PAYLOAD_CHANGED")
            safe(current.getJSONObject("qa_before").keys().asSequence().toSet() == USERS.keys, "LEDGER_QA_SET_CHANGED")
        }
        fun initialize(id: String, original: JSONObject, changed: JSONObject, qa: Map<String, JSONObject>, registrations: Boolean) {
            safe(!exists(), "LEDGER_ALREADY_EXISTS")
            atomicWrite(file, JSONObject().put("version", 1).put("runId", run).put("target", USERS[B_EMAIL])
                .put("profile_id", id).put("original", original).put("changed", changed).put("qa_before", JSONObject(qa))
                .put("registrations", registrations).put("patch_count", 0))
        }
        @Synchronized fun reserve(stage: String) {
            safe(stage in listOf("change", "restore"), "INVALID_STAGE")
            val current = read()
            safe(!current.optBoolean("${stage}_attempted"), "SECOND_MUTATION_ATTEMPT_BLOCKED")
            atomicWrite(file, current.put("${stage}_attempted", true).put("${stage}_response", "uncertain")
                .put("patch_count", current.getInt("patch_count") + 1))
        }
        @Synchronized fun response(stage: String, value: String) { atomicWrite(file, read().put("${stage}_response", value)) }
        @Synchronized fun mark(key: String, value: Boolean) { atomicWrite(file, read().put(key, value)) }
    }

    private companion object {
        const val PROJECT = "crqnfbahytzenisospcx"
        const val BASE = "https://crqnfbahytzenisospcx.supabase.co"
        const val WEB = "https://www.savely.cc"
        const val QA_RUN = "5ef2838c-ae5b-47d9-ac05-72dc5362b6a9"
        const val A_EMAIL = "android-qa-5ef2838c-a@example.invalid"
        const val B_EMAIL = "android-qa-5ef2838c-b@example.invalid"
        const val ADMIN_EMAIL = "android-qa-5ef2838c-admin@example.invalid"
        const val INPUT = "admin-qualification-input.json"
        const val LEDGER = "admin-qualification-ledger.json"
        const val RESULT = "admin-qualification-result.json"
        val USERS = mapOf(A_EMAIL to "d46cee52-3d3b-45ae-9bc3-c95931a82339", B_EMAIL to "ed79c6ec-5265-4b8b-9f50-4fc1f296bc5b", ADMIN_EMAIL to "3afa4bc1-0910-4f18-81b3-9e9f69c65a6e")
        val ALLOWED_PATHS = setOf("/api/admin", "/auth/v1/token", "/auth/v1/user", "/auth/v1/logout", "/rest/v1/profiles", "/rest/v1/login_activity", "/rest/v1/rpc/get_registrations_enabled")
        fun safe(condition: Boolean, code: String) { if (!condition) fail(code) }
        fun fail(code: String): Nothing = throw IllegalStateException(code)
        fun equal(first: JSONObject, second: JSONObject): Boolean = first.keys().asSequence().toSet() == second.keys().asSequence().toSet() &&
            first.keys().asSequence().all { key -> first.get(key) == second.get(key) }
        fun atomicWrite(file: File, value: JSONObject) {
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw IOException("ADMIN_LEDGER_WRITE_FAILED") }
        }
    }
}
