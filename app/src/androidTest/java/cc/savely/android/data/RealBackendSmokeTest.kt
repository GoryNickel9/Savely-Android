package cc.savely.android.data

import android.content.pm.ApplicationInfo
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.savely.android.BuildConfig
import cc.savely.android.MainActivity
import cc.savely.android.finance.FinanceRepository
import cc.savely.android.finance.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in real backend smoke. Standard instrumentation must exclude this class with notClass. */
@RunWith(AndroidJUnit4::class)
class RealBackendSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun existingQaSessionNativeUiAndIsolatedTransactionRoundTrip() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("realBackendSmoke") == "true") {
            "Explicit realBackendSmoke=true instrumentation argument is required."
        }
        val context = instrumentation.targetContext
        check(BuildConfig.DEBUG && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "Real backend smoke is restricted to an installed debug application."
        }
        check(URI(BuildConfig.SUPABASE_URL).host == PROJECT_HOST) { "Unexpected Supabase project; no backend operations allowed." }
        val input = context.filesDir.resolve("backend-smoke-session.json")
        check(input.isFile && input.length() in 1..16_384) { "Private backend-smoke-session.json is absent or invalid." }
        val json = ExactJson.parse(input.readText(Charsets.UTF_8)) as? JSONObject ?: error("Invalid session input.")
        val bootstrap = Session.fromJson(json)
        check(bootstrap.userId == QA_USER && bootstrap.email == QA_EMAIL) { "Bootstrap must belong to the documented QA account." }
        check(bootstrap.expiresAt > Instant.now().epochSecond + 120) { "Bootstrap access token requires at least two minutes of validity." }
        if (json.has("project_url")) check(URI(json.getString("project_url")).host == PROJECT_HOST) { "Bootstrap project does not match this build." }
        val store = AndroidSessionStore(context)
        check(store.load() == null) { "An existing local session must not be overwritten; use a clean dedicated QA installation." }
        val config = BackendConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, BuildConfig.WEB_URL)
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()
        val api = SupabaseApi(config, store, client)

        val marker = "Android-QA-${UUID.randomUUID()}"
        val resultFile = context.filesDir.resolve("backend-smoke-result.json")
        var createdId: String? = null
        var creationAttempted = false
        var hardDeleted = false
        var softDeleted = false
        var sessionInstalled = false
        var activity: ActivityScenario<MainActivity>? = null
        var primaryFailure: Throwable? = null
        fun record(stage: String, cleanupPending: Boolean = false) {
            resultFile.writeText(JSONObject().put("stage", stage).put("user_id", QA_USER).put("marker", marker)
                .put("transaction_id", createdId ?: JSONObject.NULL).put("hard_deleted", hardDeleted).put("soft_deleted", softDeleted)
                .put("cleanup_pending", cleanupPending).put("project_host", PROJECT_HOST).toString(), Charsets.UTF_8)
        }
        try {
            // Validate server identity before accepting caller-supplied session metadata or writing.
            val user = withContext(Dispatchers.IO) {
                val request = Request.Builder().url("${BuildConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/user")
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY).header("Authorization", "Bearer ${bootstrap.accessToken}").build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Backend identity verification failed (${response.code})." }
                    val body = response.body ?: error("Empty user response.")
                    val source = body.source()
                    check(!source.request(65_537)) { "Unexpectedly large user response." }
                    ExactJson.parse(source.readUtf8()) as? JSONObject ?: error("Invalid user response.")
                }
            }
            check(user.optString("id") == QA_USER && user.optString("email") == QA_EMAIL) { "Authenticated server identity does not match the QA fixture." }
            check(user.has("email_confirmed_at") && !user.isNull("email_confirmed_at")) { "The existing QA email must already be confirmed." }
            store.save(bootstrap)
            sessionInstalled = true
            check(input.delete()) { "Unable to remove the consumed private session input." }
            assertEquals(QA_USER, api.session()?.userId)
            check(api.listMfaFactors().length() == 0) { "QA account now requires MFA; stop and use the normal MFA flow." }
            val permissions = api.permissions()
            assertEquals(SupabaseApi.PERMISSION_NAMES.toSet(), permissions.keys)
            assertEquals(bootstrap, AndroidSessionStore(context).load())
            val reopened = SupabaseApi(config, AndroidSessionStore(context), client)
            assertEquals(QA_USER, reopened.session()?.userId)
            assertEquals(permissions, reopened.permissions())
            record("authenticated")

            val repository = FinanceRepository(reopened)
            val day = LocalDate.now().toString()
            creationAttempted = true
            record("creating", cleanupPending = true)
            repository.saveTransaction(null, TransactionType.EXPENSE, "0.01", "EUR", "1", null, marker, day)
            val created = reopened.select("transactions", "select=*&user_id=eq.$QA_USER&description=eq.$marker&deleted_at=is.null")
            check(created.length() == 1) { "Expected exactly one isolated transaction after create; check the recorded marker." }
            createdId = created.getJSONObject(0).getString("id")
            UUID.fromString(createdId)
            assertEquals(QA_USER, created.getJSONObject(0).getString("user_id"))
            assertEquals(0, BigDecimal("0.01").compareTo(created.getJSONObject(0).get("amount").toString().toBigDecimal()))
            assertEquals("EUR", created.getJSONObject(0).getString("currency"))
            record("created", cleanupPending = true)

            // Launch the actual application only after real encrypted credentials are installed.
            val launched = ActivityScenario.launch(MainActivity::class.java)
            activity = launched
            compose.waitUntil(45_000) { compose.onAllNodesWithText("Dashboard", substring = false).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(45_000) { compose.onAllNodesWithText("Mese corrente fino a oggi").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Movimenti", substring = false).performClick()
            compose.waitUntil(45_000) { compose.onAllNodesWithText("Periodo: Questo mese").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(marker))
            compose.waitUntil(45_000) { compose.onAllNodesWithText(marker, substring = false).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            launched.recreate()
            compose.waitUntil(45_000) { compose.onAllNodesWithText("Periodo: Questo mese").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(marker))
            compose.waitUntil(45_000) { compose.onAllNodesWithText(marker, substring = false).fetchSemanticsNodes().isNotEmpty() }
            launched.close()
            activity = null
            record("native_ui_and_reopen_verified", cleanupPending = true)

            repository.saveTransaction(createdId, TransactionType.EXPENSE, "0.02", "EUR", "1", null, marker, day)
            val updated = reopened.select("transactions", "select=*&user_id=eq.$QA_USER&id=eq.$createdId&description=eq.$marker&deleted_at=is.null")
            assertEquals(1, updated.length())
            assertEquals(0, BigDecimal("0.02").compareTo(updated.getJSONObject(0).get("amount").toString().toBigDecimal()))
            check(repository.load("transactions").transactions.any { it.id == createdId && it.description == marker })

            repository.delete("transactions", createdId!!)
            hardDeleted = true
            assertEquals(0, reopened.select("transactions", "select=id&user_id=eq.$QA_USER&id=eq.$createdId&description=eq.$marker&deleted_at=is.null").length())
            assertFalse(repository.load("transactions").transactions.any { it.id == createdId })
            record("passed", cleanupPending = false)
        } catch (error: Throwable) {
            primaryFailure = error
            record("failed", cleanupPending = creationAttempted && !hardDeleted)
            throw error
        } finally {
            try {
                activity?.close()
                // A timed-out create may have committed. Reconcile by this run's unique marker, without retrying the write.
                if (creationAttempted && createdId == null) {
                    val rows = api.select("transactions", "select=id&user_id=eq.$QA_USER&description=eq.$marker&deleted_at=is.null")
                    check(rows.length() <= 1) { "Multiple rows match the isolated marker; manual cleanup is required." }
                    if (rows.length() == 1) createdId = rows.getJSONObject(0).getString("id")
                    else record("failed_no_transaction", cleanupPending = false)
                }
                // Failure cleanup touches only this run's exact marker and UUID, never existing fixtures.
                if (createdId != null && !hardDeleted && !softDeleted) {
                    val remaining = api.select("transactions", "select=id&user_id=eq.$QA_USER&id=eq.$createdId&description=eq.$marker&deleted_at=is.null")
                    if (remaining.length() == 0) {
                        // A failed delete response can still follow a committed deletion; never retry it blindly.
                        hardDeleted = true
                        record("failed_no_transaction", cleanupPending = false)
                    } else {
                        check(remaining.length() == 1) { "Unexpected cleanup result for the isolated transaction." }
                        api.updateMinimal("transactions", createdId!!, JSONObject().put("deleted_at", Instant.now().toString()), "user_id=eq.$QA_USER&description=eq.$marker")
                        softDeleted = true
                        record("failed_and_soft_deleted", cleanupPending = true)
                    }
                }
            } catch (cleanup: Throwable) {
                record("cleanup_required", cleanupPending = true)
                if (primaryFailure != null) primaryFailure?.addSuppressed(cleanup) else {
                    primaryFailure = cleanup
                    throw cleanup
                }
            } finally {
                try {
                    if (sessionInstalled) {
                        try { api.signOut() }
                        catch (logout: Throwable) {
                            if (primaryFailure != null) primaryFailure?.addSuppressed(logout) else throw logout
                        }
                    }
                } finally {
                    client.dispatcher.executorService.shutdown()
                    client.connectionPool.evictAll()
                }
            }
        }
    }

    private companion object {
        const val PROJECT_HOST = "crqnfbahytzenisospcx.supabase.co"
        const val QA_USER = "9daffd41-39bf-4b1e-be68-abf12549c4a7"
        const val QA_EMAIL = "e2e-savely@example.com"
    }
}
