package cc.savely.android.data

import android.content.pm.ApplicationInfo
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.savely.android.BuildConfig
import cc.savely.android.finance.FinanceRepository
import cc.savely.android.finance.TransactionType
import cc.savely.android.finance.transactionPayload
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Call
import okhttp3.Connection
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in qualification of the two dedicated QA accounts only. No password or admin key on device.
 * Financial and pair mutations are reserved atomically before transport and never replayed.
 * Revoked rows require exact-ledger administrative cleanup on the host; invisible is not absent.
 */
@RunWith(AndroidJUnit4::class)
class RealCoupleQualificationTest {
    @Test fun preflightWithoutCredentials() {
        need(args().getString("couplePreflight") == "true", "OPT_IN_REQUIRED")
        val context = context()
        need(!File(context.applicationInfo.dataDir, "shared_prefs/savely_session.xml").exists(), "EXISTING_SESSION_PRESERVED")
        need(context.filesDir.listFiles().orEmpty().none { it.name.startsWith("couple-qualification-") }, "EXISTING_RUN_PRESERVED")
        // No AndroidSessionStore is constructed and no ciphertext is decrypted.
    }

    @Test fun qualifyNativePairAndRevocation() = runBlocking {
        need(args().getString("realCoupleQualification") == "true", "OPT_IN_REQUIRED")
        val run = canonical(args().getString("coupleRunId") ?: failCode("RUN_REQUIRED"))
        val checkpoint = checkpoint(run)
        need(!checkpoint.exists() && !report(run).exists(), "EXISTING_EVIDENCE_PRESERVED")
        val input = JSONObject(input().readText())
        need(input.getString("run_id") == run && input.getString("project_host") == HOST, "BOOTSTRAP_SCOPE")
        val a = Session.fromJson(input.getJSONObject("a")); val b = Session.fromJson(input.getJSONObject("b"))
        need(a.userId == A && a.email == EMAIL_A && b.userId == B && b.email == EMAIL_B, "QA_IDENTITY")
        need(a.expiresAt > Instant.now().epochSecond + 900 && b.expiresAt > Instant.now().epochSecond + 900, "SESSION_TOO_OLD")
        val ledger = Ledger(checkpoint, run)
        ledger.initialize(a.authSessionId() ?: failCode("SESSION_ID"), b.authSessionId() ?: failCode("SESSION_ID"))
        val guardA = Guard(A, a.accessToken, ledger); val guardB = Guard(B, b.accessToken, ledger)
        val clients = listOf(guardA, guardB).map { guard -> OkHttpClient.Builder()
            .addInterceptor(guard).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(30, TimeUnit.SECONDS).build() }
        val apiA = SupabaseApi(config(), MemorySessionStore(a), clients[0])
        val apiB = SupabaseApi(config(), MemorySessionStore(b), clients[1])
        var phase = "identity"
        var verified = false
        try {
            identity(apiA, A, EMAIL_A); identity(apiB, B, EMAIL_B)
            val startA = CoupleRepository(apiA).load(); val startB = CoupleRepository(apiB).load()
            need(startA.connection == null && startB.connection == null && startA.requests.length() == 0 && startB.requests.length() == 0,
                "NONEMPTY_PAIR_BASELINE")
            need(startA.archivedConnections.isEmpty() && startB.archivedConnections.isEmpty(), "EXISTING_ARCHIVE_PRESERVED")
            ledger.flag("empty_pair_baseline")
            phase = "invitation"
            guardA.partnerCode = startB.code
            guardA.plan("invitation", "couple_connection_requests", "POST", JSONObject().put("sender_id", A).put("receiver_id", B), autoId = true)
            CoupleRepository(apiA).sendInvitation(startB.code)
            val request = ledger.record("invitation").getString("id")
            need(CoupleRepository(apiB).load().requests.let { it.length() == 1 && it.getJSONObject(0).getString("id") == request }, "INVITATION_NOT_VISIBLE_TO_B")
            phase = "acceptance"
            guardB.plan("acceptance", "rpc/accept_couple_request", "POST", JSONObject().put("p_request_id", request))
            val connection = canonical(apiB.rpc("accept_couple_request", JSONObject().put("p_request_id", request), B, revision(apiB)) as? String ?: failCode("ACCEPT_RESPONSE"))
            adoptConnection(apiA, ledger, connection)
            guardA.connection = connection; guardB.connection = connection
            need(CoupleRepository(apiA).load().connection?.getString("id") == connection &&
                CoupleRepository(apiB).load().connection?.getString("id") == connection, "PAIR_NOT_VISIBLE")
            phase = "shares"
            val date = LocalDate.parse(input.getString("date"))
            createExpense(apiA, guardA, ledger, "equal_tx", "10.01", "EUR", date)
            createExpense(apiB, guardB, ledger, "custom_tx", "7.00", "USD", date)
            val marker = ledger.marker
            guardA.plan("equal_share", "shared_expenses", "POST", shareBody(connection, ledger.record("equal_tx").getString("id"), A, marker, null), autoId = true)
            CoupleRepository(apiA).shareExpense(connection, ledger.record("equal_tx").getString("id"), marker)
            guardB.plan("custom_share", "shared_expenses", "POST", shareBody(connection, ledger.record("custom_tx").getString("id"), B, marker, "2.01"), autoId = true)
            CoupleRepository(apiB).shareExpense(connection, ledger.record("custom_tx").getString("id"), marker, "2.01")
            for (api in listOf(apiA, apiB)) {
                val snapshot = CoupleRepository(api).load()
                need(snapshot.shares.length() == 2, "SHARE_COUNT")
                val equal = row(snapshot.shares, ledger.record("equal_share").getString("id"))
                val custom = row(snapshot.shares, ledger.record("custom_share").getString("id"))
                money(equal, "creator_share_amount", "5.01"); money(equal, "my_share_amount", "5.01"); money(equal, "total_amount", "10.01")
                money(custom, "creator_share_amount", "4.99"); money(custom, "my_share_amount", "2.01"); money(custom, "total_amount", "7.00")
                need(equal.getString("currency") == "EUR" && custom.getString("currency") == "USD", "SHARE_CURRENCIES")
                need(snapshot.medians["EUR"]?.compareTo(BigDecimal("5.01")) == 0 && snapshot.medians["USD"]?.compareTo(BigDecimal("2.01")) == 0, "NATIVE_MEDIANS")
            }
            phase = "isolation"
            val txA = ledger.record("equal_tx").getString("id")
            val txB = ledger.record("custom_tx").getString("id")
            need(apiB.select("transactions", "id=eq.$txA&user_id=eq.$A").length() == 0 && apiA.select("transactions", "id=eq.$txB&user_id=eq.$B").length() == 0, "PRIVATE_TRANSACTIONS_LEAKED")
            guardB.plan("denied_private_update", "transactions", "PATCH", JSONObject().put("description", "$marker-denied"),
                mapOf("id" to "eq.$txA", "user_id" to "eq.$A"))
            val rejected = runCatching { apiB.update("transactions", txA, JSONObject().put("description", "$marker-denied"), "user_id=eq.$A", B, revision(apiB)) }.exceptionOrNull()
            need(rejected is ApiException && rejected.kind == ApiError.PERMISSION, "PRIVATE_UPDATE_NOT_DENIED")
            need(row(apiA.select("transactions", "id=eq.$txA&user_id=eq.$A"), txA).getString("description") == "$marker-equal_tx", "PRIVATE_UPDATE_CHANGED_ROW")
            phase = "budget"
            val budget = JSONObject().put("connection_id", connection).put("couple_category_name", marker).put("amount", "3.00")
                .put("currency", "EUR").put("month", 1).put("year", 2000).put("created_by", A)
            guardA.plan("budget", "couple_budgets", "POST", budget, autoId = true)
            apiA.insert("couple_budgets", budget, A, revision(apiA))
            val budgetId = ledger.record("budget").getString("id")
            guardB.plan("budget_change", "couple_budgets", "PATCH", JSONObject().put("amount", "4.00"), mapOf("id" to "eq.$budgetId", "connection_id" to "eq.$connection", "amount" to "eq.3.00"))
            apiB.update("couple_budgets", budgetId, JSONObject().put("amount", "4.00"), "connection_id=eq.$connection&amount=eq.3.00", B, revision(apiB))
            for (api in listOf(apiA, apiB)) {
                val comparison = CoupleRepository(api).load().comparisons.single()
                need(comparison.limit.compareTo(BigDecimal("4")) == 0 && comparison.median.compareTo(BigDecimal("5.01")) == 0 && comparison.exceeded, "NATIVE_BUDGET_COMPARISON")
            }
            phase = "revocation"
            revoke(apiA, guardA, ledger)
            phase = "revoked_privacy"
            for (api in listOf(apiA, apiB)) {
                for (table in listOf("shared_expenses", "shared_expenses_view", "couple_budgets", "couple_audit_log"))
                    need(api.select(table, "connection_id=eq.$connection").length() == 0, "REVOKED_DATA_VISIBLE")
                phase = if (api === apiA) "revoked_native_load_a" else "revoked_native_load_b"
                val snapshot = CoupleRepository(api).load()
                phase = if (api === apiA) "revoked_archive_a" else "revoked_archive_b"
                need(snapshot.archivedConnections.size == 1, "NATIVE_ARCHIVE_COUNT")
                need(snapshot.connection == null && snapshot.archivedConnections[0].id == connection &&
                    snapshot.shares.length() == 0 && snapshot.budgets.length() == 0, "NATIVE_ARCHIVE_PRIVACY")
            }
            ledger.flag("revocation_privacy_verified")
            verified = true
        } catch (error: Exception) {
            ledger.failure(phase, safeFailureCode(error))
        } finally {
            // Do not delete invisible rows or logout credentials needed for exact recovery on the host.
            if (ledger.read().optString("connection_id").isNotBlank() && !ledger.read().optBoolean("revocation_confirmed")) {
                guardA.discardUnsubmittedPlan()
                runCatching { revoke(apiA, guardA, ledger) }.onFailure { ledger.failure("cleanup_revocation", "REVOCATION_PENDING") }
            }
            write(report(run), ledger.read().put("stage", if (verified) "native_verified_cleanup_pending" else "failed_cleanup_pending")
                .put("cleanup_complete", false).put("cleanup_pending", true))
            apiA.close(); apiB.close()
            clients.forEach { it.dispatcher.executorService.shutdown(); it.connectionPool.evictAll() }
        }
        need(verified, "QUALIFICATION_FAILED_SEE_SAFE_REPORT")
    }

    @Test fun durableReservationsWithoutCredentials() {
        need(args().getString("coupleGuardFixtures") == "true", "OPT_IN_REQUIRED")
        val run = UUID.randomUUID().toString(); val file = checkpoint(run); val ledger = Ledger(file, run)
        try {
            ledger.initialize(UUID.randomUUID().toString(), UUID.randomUUID().toString())
            val id = UUID.randomUUID().toString()
            ledger.reserve("invitation", "couple_connection_requests", "POST", A, JSONObject().put("id", id).put("sender_id", A).put("receiver_id", B))
            assertThrows(Failure::class.java) { ledger.reserve("invitation", "couple_connection_requests", "POST", A, JSONObject().put("id", id)) }
            assertEquals(id, ledger.record("invitation").getString("id"))
            assertFalse(ledger.read().optBoolean("revocation_confirmed"))
            val changed = ledger.read().put("run_id", UUID.randomUUID().toString())
            assertThrows(Failure::class.java) { ledger.validate(changed) }
        } finally { AtomicFile(file).delete() }
    }

    @Test fun diagnoseExactRevokedRunReadOnly() = runBlocking {
        need(args().getString("coupleDiagnosis") == "true", "OPT_IN_REQUIRED")
        val run = canonical(args().getString("coupleRunId") ?: failCode("RUN_REQUIRED"))
        val diagnosticId = canonical(args().getString("coupleDiagnosticId") ?: failCode("DIAGNOSTIC_ID_REQUIRED"))
        val ledger = Ledger(checkpoint(run), run)
        val state = ledger.read()
        need(state.optBoolean("revocation_confirmed"), "REVOCATION_NOT_CONFIRMED")
        val connection = canonical(state.getString("connection_id"))
        val bootstrap = JSONObject(input().readText())
        need(bootstrap.getString("run_id") == run && bootstrap.getString("project_host") == HOST, "BOOTSTRAP_SCOPE")
        val report = context().filesDir.resolve("couple-qualification-diagnostic-$run-$diagnosticId.json")
        need(!report.exists(), "DIAGNOSTIC_EVIDENCE_PRESERVED")
        val result = JSONObject().put("run_id", run).put("diagnostic_id", diagnosticId).put("project_host", HOST)
            .put("session_a", state.getString("session_a")).put("session_b", state.getString("session_b"))
        for ((label, owner, email) in listOf(Triple("a", A, EMAIL_A), Triple("b", B, EMAIL_B))) {
            val session = Session.fromJson(bootstrap.getJSONObject(label))
            need(session.userId == owner && session.email == email && session.authSessionId() == state.getString("session_$label"), "SESSION_SCOPE")
            val guard = Guard(owner, session.accessToken, ledger) // No mutation plan is ever installed.
            val client = OkHttpClient.Builder().addInterceptor(guard).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()
            val api = SupabaseApi(config(), MemorySessionStore(session), client)
            val actor = JSONObject().put("user_id", owner)
            result.put(label, actor)
            var phase = "identity"
            try {
                identity(api, owner, email)
                phase = "archived_metadata"
                val rows = api.select("couple_connections", "id=eq.$connection&or=(user_a.eq.$owner,user_b.eq.$owner)&revoked_at=not.is.null")
                actor.put("archive_row_count", rows.length())
                if (rows.length() == 1) {
                    for (field in listOf("created_at", "revoked_at")) {
                        actor.put("${field}_is_null", rows.getJSONObject(0).isNull(field))
                        val parsed = runCatching { Instant.parse(rows.getJSONObject(0).getString(field)) }
                        actor.put("${field}_parse_ok", parsed.isSuccess)
                        parsed.exceptionOrNull()?.let { actor.put("${field}_error", safeFailureCode(it)) }
                    }
                }
                phase = "native_load"
                val loaded = CoupleRepository(api).load()
                actor.put("native_archive_count", loaded.archivedConnections.size)
                    .put("native_active_connection", loaded.connection != null)
                    .put("native_share_count", loaded.shares.length()).put("native_budget_count", loaded.budgets.length())
                    .put("status", "read_complete")
            } catch (error: Exception) {
                actor.put("status", "read_failed").put("phase", phase).put("code", safeFailureCode(error))
            } finally {
                api.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
                write(report, result) // Only counts, constants and exception class names; never bodies or tokens.
            }
        }
    }

    @Test fun recoverExactRunRevocation() = runBlocking {
        need(args().getString("coupleRecovery") == "true", "OPT_IN_REQUIRED")
        val run = canonical(args().getString("coupleRunId") ?: failCode("RUN_REQUIRED"))
        val ledger = Ledger(checkpoint(run), run)
        val state = ledger.read()
        val bootstrap = JSONObject(input().readText())
        need(bootstrap.getString("run_id") == run && bootstrap.getString("project_host") == HOST, "BOOTSTRAP_SCOPE")
        val session = Session.fromJson(bootstrap.getJSONObject("a"))
        need(session.userId == A && session.email == EMAIL_A && session.authSessionId() == state.getString("session_a"), "SESSION_SCOPE")
        val guard = Guard(A, session.accessToken, ledger)
        val client = OkHttpClient.Builder().addInterceptor(guard).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()
        val api = SupabaseApi(config(), MemorySessionStore(session), client)
        try {
            identity(api, A, EMAIL_A)
            if (state.optString("connection_id").isBlank()) {
                need(state.optBoolean("empty_pair_baseline") && state.getJSONObject("operations").has("acceptance"), "ACCEPTANCE_NOT_RESERVED")
                val request = state.getJSONObject("operations").getJSONObject("invitation").getString("id")
                val requests = api.select("couple_connection_requests", "id=eq.$request&sender_id=eq.$A&receiver_id=eq.$B&status=eq.accepted")
                need(requests.length() == 1, "ACCEPTANCE_UNCONFIRMED")
                val connections = api.select("couple_connections", "user_a=eq.$A&user_b=eq.$B")
                need(connections.length() == 1 && parseBackendInstant(connections.getJSONObject(0).getString("created_at")) >=
                    Instant.parse(state.getString("started_at")).minusSeconds(60), "CONNECTION_RECOVERY_SCOPE")
                ledger.connection(connections.getJSONObject(0))
            }
            // A reserved but unconfirmed revocation is never replayed. A read showing it committed suffices.
            revoke(api, guard, ledger)
        } finally {
            api.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun transportGuardsWithoutCredentials() {
        need(args().getString("coupleGuardFixtures") == "true", "OPT_IN_REQUIRED")
        val run = UUID.randomUUID().toString(); val file = checkpoint(run); val ledger = Ledger(file, run)
        val client = OkHttpClient.Builder().build()
        try {
            ledger.initialize(UUID.randomUUID().toString(), UUID.randomUUID().toString())
            val token = "offline-only-token"; val guard = Guard(A, token, ledger)
            val expected = JSONObject().put("sender_id", A).put("receiver_id", B)
            val id = UUID.randomUUID().toString()
            val body = JSONObject(expected.toString()).put("id", id)
            fun request(value: JSONObject) = Request.Builder().url("https://$HOST/rest/v1/couple_connection_requests")
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY).header("Authorization", "Bearer $token")
                .post(value.toString().toRequestBody("application/json".toMediaType())).build()
            val transport = OfflineChain(request(body), client) {
                assertEquals(id, ledger.record("invitation").getString("id"))
                throw IOException("Synthetic response lost")
            }
            guard.plan("invitation", "couple_connection_requests", "POST", expected, autoId = true)
            assertThrows(IOException::class.java) { guard.intercept(transport) }
            assertEquals(1, transport.calls)
            guard.plan("invitation", "couple_connection_requests", "POST", expected, autoId = true)
            assertThrows(IOException::class.java) { guard.intercept(transport) }
            assertEquals(1, transport.calls)
            val foreign = OfflineChain(request(JSONObject(body.toString()).put("receiver_id", UUID.randomUUID().toString())), client) { fail("Foreign write dispatched") }
            guard.plan("different_label", "couple_connection_requests", "POST", expected, autoId = true)
            assertThrows(IOException::class.java) { guard.intercept(foreign) }
            assertEquals(0, foreign.calls)
        } finally { AtomicFile(file).delete(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    private class OfflineChain(private val original: Request, client: OkHttpClient, private val transport: () -> Unit) : Interceptor.Chain {
        private val offlineCall = client.newCall(original)
        var calls = 0
        override fun request(): Request = original
        override fun proceed(request: Request): Response { calls++; transport(); error("Offline transport must terminate") }
        override fun connection(): Connection? = null
        override fun call(): Call = offlineCall
        override fun connectTimeoutMillis(): Int = 30_000
        override fun readTimeoutMillis(): Int = 30_000
        override fun writeTimeoutMillis(): Int = 30_000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    @Test fun clearExactPrivateRunAfterHostCleanup() {
        need(args().getString("coupleLocalFinalize") == "true", "OPT_IN_REQUIRED")
        val run = canonical(args().getString("coupleRunId") ?: failCode("RUN_REQUIRED"))
        val attestationFile = context().filesDir.resolve("couple-qualification-host-$run.json")
        val attestation = JSONObject(attestationFile.readText())
        val ledger = Ledger(checkpoint(run), run)
        val finalReport = context().filesDir.resolve("couple-qualification-final-$run.json")
        val state = if (checkpoint(run).exists()) ledger.read() else JSONObject(finalReport.readText()).also(ledger::validate)
        need(attestation.getString("run_id") == run && attestation.getString("project_host") == HOST &&
            attestation.getString("session_a") == state.getString("session_a") && attestation.getString("session_b") == state.getString("session_b") &&
            attestation.optBoolean("records_absent") && attestation.optBoolean("logout_a") && attestation.optBoolean("logout_b"), "HOST_CLEANUP_NOT_CONFIRMED")
        if (input().exists()) {
            val bootstrap = JSONObject(input().readText())
            need(bootstrap.getString("run_id") == run && bootstrap.getString("project_host") == HOST &&
                Session.fromJson(bootstrap.getJSONObject("a")).let { it.userId == A && it.authSessionId() == state.getString("session_a") } &&
                Session.fromJson(bootstrap.getJSONObject("b")).let { it.userId == B && it.authSessionId() == state.getString("session_b") }, "PRIVATE_INPUT_SCOPE")
            need(input().delete(), "PRIVATE_INPUT_DELETE_PENDING")
        }
        need(!finalReport.exists() || JSONObject(finalReport.readText()).let { ledger.validate(it); !it.optBoolean("cleanup_complete") }, "FINAL_REPORT_PRESERVED")
        val complete = state.put("stage", "local_finalizing").put("cleanup_complete", false).put("cleanup_pending", true)
        // Keep the validated checkpoint/attestation until a complete result exists.
        write(finalReport, complete)
        AtomicFile(checkpoint(run)).delete()
        need(!checkpoint(run).exists() && attestationFile.delete(), "LOCAL_EVIDENCE_DELETE_PENDING")
        write(finalReport, complete.put("stage", "host_finalized").put("cleanup_complete", true).put("cleanup_pending", false))
        // AndroidSessionStore and any preferences are intentionally never cleared by this harness.
    }

    private suspend fun identity(api: SupabaseApi, owner: String, email: String) {
        // listMfaFactors reads the authenticated /auth/v1/user through the guarded transport.
        need(api.listMfaFactors(includeUnverified = true).length() == 0, "EXISTING_MFA_PRESERVED")
        need(api.session()?.let { it.userId == owner && it.email == email } == true, "SESSION_SCOPE")
        val profile = api.profile() ?: failCode("PROFILE_MISSING")
        need(profile.getString("user_id") == owner && SupabaseApi.parsePermissions(profile.optJSONObject("permissions"))["admin"] != true &&
            SupabaseApi.parsePermissions(profile.optJSONObject("permissions"))["couple_expenses"] == true, "QA_PERMISSIONS")
    }
    private suspend fun createExpense(api: SupabaseApi, guard: Guard, ledger: Ledger, label: String, amount: String, currency: String, date: LocalDate) {
        val body = transactionPayload(TransactionType.EXPENSE, BigDecimal(amount), currency, BigDecimal.ONE, null, "${ledger.marker}-$label", date)
            .put("user_id", guard.owner)
        guard.plan(label, "transactions", "POST", body, autoId = true)
        FinanceRepository(api).saveTransaction(null, TransactionType.EXPENSE, amount, currency, "1", null, "${ledger.marker}-$label", date.toString())
    }
    private suspend fun adoptConnection(api: SupabaseApi, ledger: Ledger, id: String) {
        val rows = api.select("couple_connections", "id=eq.$id&user_a=eq.$A&user_b=eq.$B")
        val value = row(rows, id)
        need(rows.length() == 1 && value.isNull("revoked_at"), "CONNECTION_SCOPE")
        ledger.connection(value)
    }
    private suspend fun revoke(api: SupabaseApi, guard: Guard, ledger: Ledger) {
        val id = ledger.read().getString("connection_id")
        guard.connection = id
        val existing = api.select("couple_connections", "id=eq.$id&user_a=eq.$A&user_b=eq.$B")
        need(existing.length() == 1, "CONNECTION_MISSING")
        if (!existing.getJSONObject(0).isNull("revoked_at")) { ledger.flag("revocation_confirmed"); return }
        val revokedAt = ledger.read().optString("planned_revoked_at").ifBlank { Instant.now().toString().also { ledger.value("planned_revoked_at", it) } }
        guard.plan("revocation", "couple_connections", "PATCH", JSONObject().put("revoked_at", revokedAt),
            mapOf("id" to "eq.$id", "user_a" to "eq.$A", "user_b" to "eq.$B", "revoked_at" to "is.null"))
        api.update("couple_connections", id, JSONObject().put("revoked_at", revokedAt), "user_a=eq.$A&user_b=eq.$B&revoked_at=is.null", A, revision(api))
        ledger.flag("revocation_confirmed")
    }
    private fun shareBody(connection: String, tx: String, actor: String, marker: String, partner: String?) = JSONObject()
        .put("connection_id", connection).put("original_tx_id", tx).put("created_by", actor).put("couple_category_name", marker)
        .put("split_mode", if (partner == null) "equal" else "custom").put("partner_amount", partner ?: JSONObject.NULL)
    private fun row(rows: JSONArray, id: String): JSONObject = (0 until rows.length()).map { rows.getJSONObject(it) }.singleOrNull { it.getString("id") == id } ?: failCode("ROW_ID_MISSING")
    private fun money(row: JSONObject, key: String, expected: String) = need(BigDecimal(row.get(key).toString()).compareTo(BigDecimal(expected)) == 0, "SPLIT_AMOUNT")
    private suspend fun revision(api: SupabaseApi) = api.sessionOwnerRevision(api.session() ?: failCode("SESSION_MISSING")) ?: failCode("SESSION_CHANGED")

    private class Ledger(private val file: File, val run: String) {
        val marker = "android-couple-$run"
        fun initialize(a: String, b: String) = write(file, JSONObject().put("version", 1).put("run_id", run).put("project_host", HOST)
            .put("user_a", A).put("user_b", B).put("session_a", canonical(a)).put("session_b", canonical(b))
            .put("marker", marker).put("started_at", Instant.now().toString()).put("operations", JSONObject()).put("failures", JSONArray()))
        @Synchronized fun read(): JSONObject = JSONObject(AtomicFile(file).openRead().use { String(it.readBytes()) }).also(::validate)
        fun validate(value: JSONObject) = need(value.getString("run_id") == run && value.getString("project_host") == HOST &&
            value.getString("user_a") == A && value.getString("user_b") == B && value.getString("marker") == marker, "CHECKPOINT_SCOPE")
        @Synchronized fun reserve(label: String, table: String, method: String, actor: String, body: JSONObject) {
            val value = read(); val ops = value.getJSONObject("operations")
            need(!ops.has(label), "SECOND_ATTEMPT_BLOCKED")
            ops.put(label, JSONObject().put("table", table).put("method", method).put("actor", actor)
                .put("id", body.optString("id")).put("expected", JSONObject(body.toString())).put("reserved_at", Instant.now().toString()))
            write(file, value)
        }
        @Synchronized fun record(label: String) = read().getJSONObject("operations").getJSONObject(label)
        @Synchronized fun value(key: String, value: Any) { write(file, read().put(key, value)) }
        fun flag(key: String) = value(key, true)
        @Synchronized fun failure(phase: String, code: String) { val v = read(); v.getJSONArray("failures").put(JSONObject().put("phase", phase).put("code", code)); write(file, v) }
        @Synchronized fun connection(row: JSONObject) { val v = read().put("connection_id", canonical(row.getString("id"))).put("connection_expected", row); write(file, v) }
    }
    private data class Plan(val label: String, val table: String, val method: String, val body: JSONObject, val query: Map<String, String>, val autoId: Boolean)
    private class Guard(val owner: String, private val token: String, private val ledger: Ledger) : Interceptor {
        var connection: String? = null
        var partnerCode: String? = null
        private var planned: Plan? = null
        @Synchronized fun discardUnsubmittedPlan() { planned = null }
        @Synchronized fun plan(label: String, table: String, method: String, body: JSONObject, query: Map<String, String> = emptyMap(), autoId: Boolean = false) {
            need(planned == null, "UNSUBMITTED_PLAN")
            planned = Plan(label, table, method, JSONObject(body.toString()), query, autoId)
        }
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request(); val url = request.url
            try {
                need(url.isHttps && url.host == HOST && request.header("Authorization") == "Bearer $token" &&
                    request.header("apikey") == BuildConfig.SUPABASE_ANON_KEY, "TRANSPORT_SCOPE")
                if (request.method == "GET") {
                    val table = url.encodedPath.removePrefix("/rest/v1/")
                    val allowed = url.encodedPath == "/auth/v1/user" || table in setOf("profiles", "transactions", "couple_connections", "couple_connection_requests", "shared_expenses", "shared_expenses_view", "couple_budgets", "couple_audit_log")
                    need(allowed, "READ_ENDPOINT")
                    if (table == "profiles") need(url.queryParameter("user_id") == "eq.$owner", "PROFILE_OWNER")
                    return chain.proceed(request).also { response ->
                        if (url.encodedPath == "/auth/v1/user" && response.isSuccessful) {
                            val json = JSONObject(response.peekBody(1_048_576).string())
                            need(json.getString("id") == owner && json.getString("email") == if (owner == A) EMAIL_A else EMAIL_B, "SERVER_IDENTITY")
                        }
                    }
                }
                // The point lookup is the sole read-only POST used by the invitation repository.
                val body = JSONObject(Buffer().also { request.body?.writeTo(it) }.readUtf8())
                if (url.encodedPath == "/rest/v1/rpc/find_user_by_couple_code") {
                    need(request.method == "POST" && url.queryParameterNames.isEmpty() && owner == A &&
                        body.keys().asSequence().toSet() == setOf("p_code") && partnerCode != null &&
                        body.getString("p_code") == partnerCode && normalizedCoupleCode(body.getString("p_code")) == body.getString("p_code"), "CODE_LOOKUP_SCOPE")
                    return chain.proceed(request)
                }
                val plan = synchronized(this) { planned.also { planned = null } } ?: failCode("UNPLANNED_WRITE")
                need(url.encodedPath == "/rest/v1/${plan.table}" && request.method == plan.method, "WRITE_ENDPOINT")
                need(url.queryParameterNames == plan.query.keys && plan.query.all { (k, v) -> url.queryParameterValues(k) == listOf(v) }, "WRITE_CAS")
                val expected = JSONObject(plan.body.toString())
                if (plan.autoId) expected.put("id", canonical(body.getString("id")))
                need(same(body, expected), "WRITE_PAYLOAD")
                ledger.reserve(plan.label, plan.table, plan.method, owner, body)
                return chain.proceed(request)
            } catch (error: Failure) { throw IOException(error.code) }
        }
    }
    private class Failure(val code: String) : IllegalStateException(code)
    companion object {
        private const val A = "d46cee52-3d3b-45ae-9bc3-c95931a82339"
        private const val B = "ed79c6ec-5265-4b8b-9f50-4fc1f296bc5b"
        private const val EMAIL_A = "android-qa-5ef2838c-a@example.invalid"
        private const val EMAIL_B = "android-qa-5ef2838c-b@example.invalid"
        private const val HOST = "crqnfbahytzenisospcx.supabase.co"
        private fun args() = InstrumentationRegistry.getArguments()
        private fun context() = InstrumentationRegistry.getInstrumentation().targetContext.also {
            need(it.packageName == "cc.savely.android" && it.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                BuildConfig.SUPABASE_URL.trimEnd('/') == "https://$HOST", "DEBUG_PROJECT_REQUIRED")
        }
        private fun input() = context().filesDir.resolve("couple-qualification-session.json")
        private fun checkpoint(run: String) = context().filesDir.resolve("couple-qualification-checkpoint-$run.json")
        private fun report(run: String) = context().filesDir.resolve("couple-qualification-result-$run.json")
        private fun config() = BackendConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, BuildConfig.WEB_URL)
        private fun canonical(value: String) = UUID.fromString(value).toString().also { need(it == value, "UUID_INVALID") }
        private fun need(ok: Boolean, code: String) { if (!ok) throw Failure(code) }
        private fun failCode(code: String): Nothing = throw Failure(code)
        private fun safeFailureCode(error: Throwable): String = when (error) {
            is Failure -> error.code
            is ApiException -> "API_${error.kind.name}_${error.status ?: 0}"
            else -> "NATIVE_" + error.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z0-9]{1,80}")) }.orEmpty().ifBlank { "FAILURE" }
        }
        private fun write(file: File, json: JSONObject) {
            val atomic = AtomicFile(file); val output = atomic.startWrite()
            try { output.write(json.toString().toByteArray()); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
        }
        private fun same(actual: JSONObject, expected: JSONObject): Boolean = actual.keys().asSequence().toSet() == expected.keys().asSequence().toSet() &&
            expected.keys().asSequence().all { key ->
                val a = actual.get(key); val b = expected.get(key)
                if (a === JSONObject.NULL || b === JSONObject.NULL) a === b else if (key in setOf("amount", "exchange_rate_eur", "partner_amount"))
                    runCatching { BigDecimal(a.toString()).compareTo(BigDecimal(b.toString())) == 0 }.getOrDefault(false) else a == b
            }
    }
}
