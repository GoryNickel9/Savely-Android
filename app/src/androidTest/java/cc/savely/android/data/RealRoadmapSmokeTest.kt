package cc.savely.android.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.savely.android.BuildConfig
import cc.savely.android.finance.FinanceRepository
import cc.savely.android.finance.assetFromJson
import cc.savely.android.modules.CollectionKind
import cc.savely.android.modules.CollectionOverviewRepository
import cc.savely.android.transfer.TransferRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Call
import okhttp3.Connection
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Opt-in only. Exercises native repositories and REST, not a complete MainActivity UI journey.
 * Every write has a durable reservation before transport, with no automatic second attempt.
 * The private bootstrap remains available until cleanup completes; it is never embedded in reports.
 */
@RunWith(AndroidJUnit4::class)
class RealRoadmapSmokeTest {
    @Test fun preflightEmptyLocalStateWithoutCredentials() {
        checkSafe(arguments().getString("roadmapPreflight") == "true", "PREFLIGHT_OPT_IN_REQUIRED")
        val context = application()
        emptyLocalState(context)
        checkSafe(!context.filesDir.resolve(INPUT).exists(), "PRIVATE_BOOTSTRAP_ALREADY_PRESENT")
        // Do not instantiate/load AndroidSessionStore: corrupt preexisting ciphertext must be preserved.
    }

    @Test fun qaNativeContractsAndIsolatedCrud() = runBlocking { execute(recovery = false) }

    @Test fun recoverExactRoadmapRun() = runBlocking { execute(recovery = true) }

    @Test fun checkpointGuardsWithoutCredentials() {
        checkSafe(arguments().getString("roadmapGuardFixtures") == "true", "FIXTURE_OPT_IN_REQUIRED")
        val context = application()
        val fixture = Fixture(context, UUID.randomUUID().toString(), UUID.randomUUID().toString(), LocalDate.of(2099, 1, 1))
        val journal = Journal(fixture)
        try {
            journal.initialize()
            val record = fixture.records.first()
            journal.reserve(record.id, "create")
            assertThrows(Failure::class.java) { journal.reserve(record.id, "create") }
            assertFalse(journal.absenceConclusive(record.id))
            assertThrows(Failure::class.java) { journal.reserve(record.id, "change") }
            val changed = JSONObject(record.payload.toString()).put("user_id", UUID.randomUUID().toString())
            assertFalse(matches(changed, record.payload))
            assertFalse(matches(JSONObject(record.payload.toString()).put("name", "foreign"), record.payload))
            journal.response(record.id, "create", "rejected")
            checkSafe(journal.absenceConclusive(record.id), "REJECTED_CREATE_NOT_RECONCILABLE")
            journal.reserve(record.id, "delete")
            assertThrows(Failure::class.java) { journal.reserve(record.id, "delete") }
            val changedCheckpoint = journal.read().put("session_id", UUID.randomUUID().toString())
            assertThrows(Failure::class.java) { journal.validate(changedCheckpoint) }
            assertThrows(Failure::class.java) { journal.mark("records_removed") }
            fixture.records.forEach { journal.confirmAbsence(it.id) }
            journal.mark("records_removed")
            journal.reserveLogout()
            assertThrows(Failure::class.java) { journal.reserveLogout() }
            assertFalse(localCleanupAuthorized(fixture, journal))
            journal.mark("logout_confirmed")
            checkSafe(localCleanupAuthorized(fixture, journal), "CONFIRMED_LOGOUT_LOCAL_RECOVERY_NOT_AVAILABLE")
            // No bootstrap, backend identity, or ciphertext is read by these proof-only assertions.
        } finally { AtomicFile(fixture.checkpoint).delete() }
    }

    @Test fun transportReservationsAndLostResponsesWithoutCredentials() {
        checkSafe(arguments().getString("roadmapGuardFixtures") == "true", "FIXTURE_OPT_IN_REQUIRED")
        val f = Fixture(application(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), LocalDate.of(2099, 1, 1))
        val journal = Journal(f)
        val token = "fixture.${Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject().put("session_id", f.session).toString().toByteArray())}.fixture"
        val store = MemorySessionStore(Session(token, "fixture", Instant.now().epochSecond + 3600, OWNER, EMAIL))
        val guard = Guard(f, store, journal)
        val client = client().build()
        try {
            journal.initialize()
            val record = f.records.first()
            fun request(body: JSONObject) = Request.Builder().url("$PROJECT/rest/v1/${record.table}")
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY).header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(JSON)).build()
            val original = request(record.payload)
            val lost = OfflineChain(original, client) {
                checkSafe(journal.entry(record.id).getBoolean("create_attempted"), "CHECKPOINT_NOT_RESERVED_BEFORE_TRANSPORT")
                throw IOException("fixture response lost")
            }
            guard.plan(record, "create", "POST", record.payload, emptyMap())
            assertThrows(IOException::class.java) { guard.intercept(lost) }
            assertEquals(1, lost.calls)
            assertFalse(journal.absenceConclusive(record.id))
            guard.plan(record, "create", "POST", record.payload, emptyMap())
            assertThrows(IOException::class.java) { guard.intercept(lost) }
            assertEquals(1, lost.calls)
            // A request cannot hide a different marker/owner or add an unplanned field.
            guard.plan(record, "create", "POST", record.payload, emptyMap())
            val altered = OfflineChain(request(JSONObject(record.payload.toString()).put("name", "different")), client) { error("must not proceed") }
            assertThrows(IOException::class.java) { guard.intercept(altered) }
            assertEquals(0, altered.calls)
            val foreign = OfflineChain(Request.Builder().url("$PROJECT/rest/v1/transactions?user_id=eq.${UUID.randomUUID()}")
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY).header("Authorization", "Bearer $token").build(), client) { error("must not proceed") }
            assertThrows(IOException::class.java) { guard.intercept(foreign) }
            assertEquals(0, foreign.calls)
            store.save(store.load()!!.copy(userId = UUID.randomUUID().toString()))
            assertThrows(IOException::class.java) { guard.intercept(lost) }
            assertEquals(1, lost.calls)
        } finally {
            AtomicFile(f.checkpoint).delete()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun recurringDeleteCasFiltersWithoutCredentials() {
        checkSafe(arguments().getString("roadmapGuardFixtures") == "true", "FIXTURE_OPT_IN_REQUIRED")
        val f = Fixture(application(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), LocalDate.of(2099, 1, 1))
        val journal = Journal(f)
        val token = "fixture.${Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject().put("session_id", f.session).toString().toByteArray())}.fixture"
        val store = MemorySessionStore(Session(token, "fixture", Instant.now().epochSecond + 3600, OWNER, EMAIL))
        val guard = Guard(f, store, journal)
        val client = client().build()
        try {
            journal.initialize()
            val record = f.records.single { it.label == "recurring" }
            journal.reserve(record.id, "create")
            journal.response(record.id, "create", "accepted")
            journal.confirmCreate(record.id)
            // PostgREST returns typed booleans and may normalize numeric strings/scales.
            val original = JSONObject(record.payload.toString()).put("amount", BigDecimal("0.01000000"))
                .put("week_interval", 1L).put("deleted_at", JSONObject.NULL)
            val fields = original.keys().asSequence().toSet() - setOf("id", "user_id")
            val originalQuery = withOwnerAndId(filters(original, fields), record.id)
            assertEquals(listOf("eq.false"), originalQuery["is_active"])
            assertEquals(true, filterMatches("eq.false", false))
            assertEquals(true, filterMatches("eq.true", true))
            assertFalse(filterMatches("eq.true", false))
            assertFalse(filterMatches("eq.FALSE", false))
            assertFalse(filterMatches("eq.0", false))
            assertEquals(true, filterMatches("eq.0.01000000", "0.01"))
            assertFalse(filterMatches("eq.0.02", "0.01"))
            assertEquals(true, filterMatches("is.null", JSONObject.NULL))
            assertFalse(filterMatches("eq.null", JSONObject.NULL))
            assertEquals(true, matches(original, record.payload))
            // Only filter serialization is normalized; typed payload comparisons stay strict.
            assertFalse(matches(JSONObject(record.payload.toString()).put("is_active", "false"), record.payload))
            guard.plan(record, "delete", "DELETE", null, originalQuery)
            guard.discardUnsubmittedPlans()
            assertThrows(Failure::class.java) {
                guard.plan(record, "delete", "DELETE", null, originalQuery + ("is_active" to listOf("eq.true")))
            }
            assertThrows(Failure::class.java) {
                guard.plan(record, "delete", "DELETE", null, originalQuery - "name")
            }
            assertThrows(Failure::class.java) {
                guard.plan(record, "delete", "DELETE", null, originalQuery + ("name" to listOf("eq.foreign")))
            }
            assertThrows(Failure::class.java) {
                guard.plan(record, "delete", "DELETE", null, originalQuery + ("user_id" to listOf("eq.${UUID.randomUUID()}")))
            }
            assertThrows(Failure::class.java) {
                guard.plan(record, "delete", "DELETE", record.payload, originalQuery)
            }
            val changed = JSONObject(original.toString()).put("amount", BigDecimal("0.02000000"))
            val changedQuery = withOwnerAndId(filters(changed, fields), record.id)
            assertThrows(Failure::class.java) { guard.plan(record, "delete", "DELETE", null, changedQuery) }
            journal.reserve(record.id, "change")
            journal.response(record.id, "change", "accepted")
            assertEquals(true, matches(changed, record.changed))
            guard.plan(record, "delete", "DELETE", null, changedQuery)
            val request = Request.Builder().url("$PROJECT/rest/v1/${record.table}?${queryString(changedQuery)}")
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY).header("Authorization", "Bearer $token").delete().build()
            val offline = OfflineChain(request, client) {
                checkSafe(journal.entry(record.id).getBoolean("delete_attempted"), "DELETE_CHECKPOINT_NOT_RESERVED_BEFORE_TRANSPORT")
            }
            guard.intercept(offline).close()
            assertEquals(1, offline.calls)
            assertEquals("accepted", journal.entry(record.id).getString("delete_response"))
            guard.plan(record, "delete", "DELETE", null, changedQuery)
            assertThrows(IOException::class.java) { guard.intercept(offline) }
            assertEquals(1, offline.calls)
            // This isolated journal never reads or changes the live run's checkpoint/bootstrap.
        } finally {
            AtomicFile(f.checkpoint).delete()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /** No sockets or HTTP calls: proceed is an explicit in-memory fixture callback. */
    private class OfflineChain(private val original: Request, client: OkHttpClient, val transport: () -> Unit) : Interceptor.Chain {
        private val offlineCall = client.newCall(original)
        var calls = 0
        override fun request(): Request = original
        override fun proceed(request: Request): Response {
            calls++
            transport()
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("[]".toResponseBody(JSON)).build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = offlineCall
        override fun connectTimeoutMillis(): Int = 45000
        override fun readTimeoutMillis(): Int = 45000
        override fun writeTimeoutMillis(): Int = 45000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private suspend fun execute(recovery: Boolean) {
        val fixture = fixture(if (recovery) "realRoadmapRecovery" else "realRoadmapSmoke")
        // Outside try/finally: refusal must not overwrite the very report being preserved.
        checkSafe(!fileExists(fixture.result(recovery)), "EXISTING_REPORT_PRESERVED")
        val journal = Journal(fixture)
        val store = MemorySessionStore()
        val guard = Guard(fixture, store, journal)
        val client = client().addInterceptor(guard).build()
        val api = SupabaseApi(BackendConfig(PROJECT, BuildConfig.SUPABASE_ANON_KEY, BuildConfig.WEB_URL), store, client)
        val contracts = JSONArray()
        var installed = false
        var exercisePassed = false
        var phase = "private_bootstrap"
        var failure: String? = null
        var outcome = Cleanup()
        try {
            if (!recovery) checkSafe(fixture.day == LocalDate.now(), "DEVICE_DATE_MISMATCH")
            emptyLocalState(fixture.context, allowThisCheckpoint = if (recovery) fixture.checkpoint.name else null)
            if (recovery) {
                checkSafe(journal.exists(), "RECOVERY_CHECKPOINT_REQUIRED")
                checkSafe(journal.read().getInt("first_pid") != Process.myPid(), "RECOVERY_REQUIRES_NEW_PROCESS")
            } else checkSafe(!journal.exists(), "EXISTING_CHECKPOINT_PRESERVED")
            if (recovery && localCleanupAuthorized(fixture, journal)) {
                phase = "local_cleanup_after_confirmed_logout"
                outcome = localCleanup(fixture, journal)
                exercisePassed = outcome.complete
                checkSafe(outcome.complete, "LOCAL_CLEANUP_PENDING")
            } else {
            val bootstrap = Session.fromJson(readJson(fixture.input, 16_384))
            owned(bootstrap, fixture)
            checkSafe(bootstrap.expiresAt > Instant.now().epochSecond + 1200, "BOOTSTRAP_VALIDITY_TOO_SHORT")
            phase = "server_identity"
            identity(fixture, bootstrap)
            store.save(bootstrap)
            installed = true
            if (!recovery) {
                phase = "uuid_preflight"
                fixture.records.forEach { checkSafe(select(api, it).length() == 0, "PREEXISTING_UUID_PRESERVED") }
                journal.initialize()
                val finance = FinanceRepository(api)
                suspend fun contract(name: String, body: suspend () -> Unit) {
                    phase = name
                    try {
                        body()
                        contracts.put(JSONObject().put("contract", name).put("status", "passed"))
                    } catch (error: Exception) {
                        contracts.put(JSONObject().put("contract", name).put("status", "failed").put("code", code(error)))
                    }
                }
                SECTIONS.forEach { section -> contract("finance_$section") { finance.load(section) } }
                contract("couple_archive") {
                    val enabled = api.permissions()["couple_expenses"] == true
                    if (enabled) {
                        val result = CoupleRepository(api).load()
                        checkSafe(result.userId == OWNER, "COUPLE_OWNER_MISMATCH")
                    } else {
                        try { CoupleRepository(api).load(); throw Failure("COUPLE_DISABLED_READ_NOT_REJECTED") }
                        catch (error: ApiException) { checkSafe(error.kind == ApiError.PERMISSION, "COUPLE_DISABLED_WRONG_ERROR") }
                    }
                }
                CollectionKind.entries.forEach { kind -> contract("collection_${kind.name.lowercase()}") {
                    checkSafe(CollectionOverviewRepository(api).load(kind).ownerId == OWNER, "COLLECTION_OWNER_MISMATCH")
                } }
                contract("transfer_import_context") { checkSafe(TransferRepository(api).importContext().userId == OWNER, "TRANSFER_OWNER_MISMATCH") }
                phase = "readonly_service_contracts"
                RoadmapServiceReadContracts.run(api, fixture.session) { contracts.put(it) }
                // All records are deterministic and checkpointed before the first POST. Dependencies precede children.
                for (record in fixture.records) {
                    phase = "create_${record.label}"
                    guard.plan(record, "create", "POST", record.payload, emptyMap())
                    api.insert(record.table, JSONObject(record.payload.toString()))
                    val row = requireRow(api, record, journal)
                    checkSafe(matches(row, record.payload), "CREATE_RECONCILIATION_MISMATCH")
                    journal.confirmCreate(record.id)
                    contracts.put(JSONObject().put("contract", phase).put("status", "passed"))
                }
                contract("transfer_export") {
                    val export = TransferRepository(api).export()
                    checkSafe(export.userId == OWNER && export.counts["transactions"] != null && export.csv.isNotBlank(), "TRANSFER_EXPORT_INCOMPLETE")
                    // CSV can contain other QA records. It remains only in RAM and is not included in the report.
                }
                for (record in fixture.records.filter { it.change.length() > 0 && it.table != "portfolio_assets" }) {
                    phase = "update_${record.label}"
                    val before = requireRow(api, record, journal)
                    val query = filters(before, record.payload.keys().asSequence().toSet() - setOf("id", "user_id"))
                    guard.plan(record, "change", "PATCH", record.change, withOwnerAndId(query, record.id))
                    api.update(record.table, record.id, JSONObject(record.change.toString()), queryString(query) + "&user_id=eq.$OWNER")
                    checkSafe(matches(requireRow(api, record, journal), record.changed), "UPDATE_RECONCILIATION_MISMATCH")
                    contracts.put(JSONObject().put("contract", phase).put("status", "passed"))
                }
                phase = "portfolio_group_close"
                val positions = fixture.records.filter { it.table == "portfolio_assets" }
                // The production repository issues its exact sequential CAS filters. Guard plans each body/filter.
                positions.forEach { record ->
                    val row = requireRow(api, record, journal)
                    val keys = setOf("type", "currency", "symbol", "quantity", "purchase_price", "purchase_date", "sold_at", "sold_price")
                    guard.plan(record, "change", "PATCH", record.change, withOwnerAndId(filters(row, keys), record.id))
                }
                finance.closeAssetGroup(positions.map { assetFromJson(requireRow(api, it, journal)) }, fixture.day.toString(), "0.02")
                positions.forEach { checkSafe(matches(requireRow(api, it, journal), it.changed), "GROUP_CLOSE_RECONCILIATION_MISMATCH") }
                contracts.put(JSONObject().put("contract", phase).put("status", "passed"))
                contract("portfolio_history_after_close") {
                    val data = finance.load("portfolio")
                    fixture.records.filter { it.table == "asset_price_history" }.forEach { record ->
                        checkSafe(data.priceHistory.any { it.id == record.id }, "HISTORY_READ_MISSING")
                    }
                    positions.forEach { record -> checkSafe(data.assets.any { it.id == record.id && it.closed }, "CLOSED_POSITION_READ_MISSING") }
                }
                exercisePassed = (0 until contracts.length()).all { contracts.getJSONObject(it).getString("status") == "passed" }
                if (!exercisePassed) failure = "READ_CONTRACT_FAILED"
            }
            }
        } catch (error: Throwable) {
            failure = code(error)
            contracts.put(JSONObject().put("contract", phase).put("status", "failed").put("code", failure))
        } finally {
            if (installed) outcome = cleanup(fixture, journal, store, guard, api)
            val complete = outcome.complete && !journal.exists()
            val report = JSONObject().put("version", 1).put("run_id", fixture.run).put("session_id", fixture.session)
                .put("user_id", OWNER).put("email", EMAIL).put("project_host", HOST)
                .put("date", fixture.day.toString())
                .put("scope", "native-repositories-and-REST/isolated-UUIDs").put("recovery", recovery)
                .put("stage", if ((exercisePassed || recovery) && failure == null && complete) "passed" else "failed")
                .put("phase", phase).put("contracts", contracts).put("crud_verified", exercisePassed && !recovery)
                .put("records_removed", outcome.removed).put("logout_server", outcome.loggedOut).put("cleanup_local", outcome.cleared)
                .put("input_removed", outcome.inputRemoved).put("cleanup_complete", complete).put("cleanup_pending", !complete).put("checkpoint_retained", journal.exists())
            failure?.let { report.put("failure_code", it) }
            outcome.failure?.let { report.put("cleanup_failure_code", it) }
            try {
                checkSafe(!fileExists(fixture.result(recovery)), "EXISTING_REPORT_PRESERVED")
                writeJson(fixture.result(recovery), report)
            } finally {
                api.close()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
        checkSafe((exercisePassed || recovery) && failure == null && outcome.complete && !journal.exists(), "ROADMAP_CONTRACT_OR_CLEANUP_FAILED")
    }

    private suspend fun cleanup(f: Fixture, j: Journal, store: SessionStore, guard: Guard, api: SupabaseApi): Cleanup =
        withContext(NonCancellable + Dispatchers.IO) {
            var state = Cleanup()
            try {
                owned(store.load(), f)
                identity(f, store.load()!!)
                guard.discardUnsubmittedPlans()
                if (j.exists()) {
                    // Explicit histories precede assets, and budget/transaction/recurring precede their category.
                    for (record in f.records.asReversed()) {
                        if (record.table == "portfolio_assets") linkedHistoryAbsent(api, f, record.id)
                        val rows = select(api, record)
                        checkSafe(rows.length() <= 1, "NON_UNIQUE_UUID_PRESERVED")
                        if (rows.length() == 1) {
                            val row = requireRow(api, record, j)
                            checkSafe(!j.entry(record.id).getBoolean("delete_attempted"), "PREVIOUS_DELETE_STILL_PRESENT_PENDING")
                            val fields = row.keys().asSequence().toSet() - setOf("id", "user_id", "updated_at")
                            val query = filters(row, fields)
                            guard.plan(record, "delete", "DELETE", null, withOwnerAndId(query, record.id))
                            try { api.delete(record.table, record.id, queryString(query) + "&user_id=eq.$OWNER") }
                            catch (_: Exception) { /* One reserved attempt only; reconcile the exact UUID below. */ }
                            checkSafe(select(api, record).length() == 0, "DELETE_ABSENCE_NOT_CONFIRMED")
                        } else checkSafe(j.absenceConclusive(record.id), "UNKNOWN_CREATE_ABSENCE_PENDING")
                        j.confirmAbsence(record.id)
                    }
                    f.records.forEach { checkSafe(select(api, it).length() == 0, "FINAL_ABSENCE_NOT_CONFIRMED") }
                    f.records.filter { it.table == "portfolio_assets" }.forEach { linkedHistoryAbsent(api, f, it.id) }
                    j.mark("records_removed")
                }
                state = state.copy(removed = true)
                // Retain the only recovery credential while any row or write remains uncertain.
                owned(store.load(), f)
                guard.logoutAllowed = true
                val logout = Request.Builder().url("$PROJECT/auth/v1/logout?scope=local")
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY).header("Authorization", "Bearer ${store.load()!!.accessToken}")
                    .post("{}".toRequestBody(JSON)).build()
                if (j.exists()) j.reserveLogout()
                val logoutClient = guard.clientForLogout()
                try { logoutClient.newCall(logout).execute().use { checkSafe(it.isSuccessful, "TEMPORARY_LOGOUT_PENDING") } }
                finally { logoutClient.dispatcher.executorService.shutdown(); logoutClient.connectionPool.evictAll() }
                state = state.copy(loggedOut = true)
                if (j.exists()) j.mark("logout_confirmed")
                owned(store.load(), f)
                store.clear()
                checkSafe(store.load() == null, "MEMORY_SESSION_CLEAR_PENDING")
                state = state.copy(cleared = true)
                if (j.exists()) j.mark("local_cleared")
                checkSafe(f.input.delete(), "PRIVATE_INPUT_REMOVAL_PENDING")
                state = state.copy(inputRemoved = true)
                if (j.exists()) { AtomicFile(f.checkpoint).delete(); checkSafe(!j.exists(), "CHECKPOINT_REMOVAL_PENDING") }
                state
            } catch (error: Throwable) { state.copy(failure = code(error)) }
        }

    private fun localCleanupAuthorized(f: Fixture, j: Journal): Boolean {
        val state = j.read()
        if (!state.getBoolean("records_removed")) return false
        if (state.getBoolean("logout_confirmed")) return true
        // If the durable checkpoint update failed after a confirmed logout, the sanitized report
        // still records that response. A new process only removes local files, never sends a write.
        if (!fileExists(f.result(false))) return false
        val report = readJson(f.result(false), 65_536, atomic = true)
        checkSafe(report.optString("run_id") == f.run && report.optString("session_id") == f.session && report.optString("user_id") == OWNER &&
            report.optString("email") == EMAIL && report.optString("project_host") == HOST && report.optString("date") == f.day.toString() &&
            report.opt("version") is Number && equivalent(report.opt("version"), 1) && report.opt("recovery") == false &&
            report.optString("scope") == "native-repositories-and-REST/isolated-UUIDs", "RECOVERY_REPORT_IDENTITY_INVALID")
        return report.opt("records_removed") == true && report.opt("logout_server") == true
    }

    private fun localCleanup(f: Fixture, j: Journal): Cleanup {
        var state = Cleanup(removed = true, loggedOut = true, cleared = true)
        try {
            emptyLocalState(f.context, allowThisCheckpoint = f.checkpoint.name)
            if (f.input.exists()) {
                owned(Session.fromJson(readJson(f.input, 16_384)), f)
                checkSafe(f.input.delete(), "PRIVATE_INPUT_REMOVAL_PENDING")
            }
            state = state.copy(inputRemoved = true)
            AtomicFile(f.checkpoint).delete()
            checkSafe(!j.exists(), "CHECKPOINT_REMOVAL_PENDING")
            return state
        } catch (error: Throwable) { return state.copy(failure = code(error)) }
    }

    private suspend fun linkedHistoryAbsent(api: SupabaseApi, f: Fixture, asset: String) {
        checkSafe(f.records.any { it.table == "portfolio_assets" && it.id == asset }, "FOREIGN_LINKED_HISTORY_SCOPE")
        checkSafe(api.select("asset_price_history", "select=id&user_id=eq.$OWNER&asset_id=eq.$asset&limit=1").length() == 0,
            "UNREGISTERED_OR_REMAINING_LINKED_HISTORY_PENDING")
    }

    private data class Plan(val record: Record, val operation: String, val method: String, val body: JSONObject?, val query: Map<String, List<String>>)

    /** This transport observes and validates; it never injects UUIDs, rewrites payloads, or retries. */
    private class Guard(val f: Fixture, val store: SessionStore, val journal: Journal) : Interceptor {
        private val plans = mutableMapOf<String, Plan>()
        private val connectionIds = mutableSetOf<String>()
        private var firstSearchedCardId: String? = null
        private var observedNonAdminProfile = false
        private var adminDenialAttempted = false
        @Volatile var logoutAllowed = false
        @Synchronized fun plan(record: Record, operation: String, method: String, body: JSONObject?, query: Map<String, List<String>>) {
            val registered = f.records.singleOrNull { it.id == record.id } ?: throw Failure("UNREGISTERED_PLAN_UUID")
            checkSafe(record.table == registered.table && sameObject(record.payload, registered.payload) && sameObject(record.change, registered.change), "PLAN_REGISTRY_MISMATCH")
            val expectedMethod = mapOf("create" to "POST", "change" to "PATCH", "delete" to "DELETE")[operation]
            checkSafe(method == expectedMethod, "PLAN_OPERATION_METHOD_MISMATCH")
            if (operation == "create") checkSafe(body != null && sameObject(body, registered.payload) && query.isEmpty(), "PLAN_INSERT_INVALID")
            else {
                checkSafe(query["id"] == listOf("eq.${record.id}") && query["user_id"] == listOf("eq.$OWNER"), "PLAN_OWNER_OR_UUID_FILTER_INVALID")
                if (operation == "change") checkSafe(body != null && registered.change.length() > 0 && sameObject(body, registered.change), "PLAN_CHANGE_INVALID")
                else {
                    checkSafe(body == null, "PLAN_DELETE_BODY_REJECTED")
                    val changed = journal.entry(record.id).getBoolean("change_attempted")
                    checkSafe(listOfNotNull(registered.payload, registered.changed.takeIf { changed }).any { expected ->
                        expected.keys().asSequence().filterNot { it in setOf("id", "user_id") }.all { key ->
                            val values = query[key]
                            values != null && values.size == 1 && filterMatches(values.single(), expected.get(key))
                        }
                    }, "PLAN_DELETE_PAYLOAD_FILTER_INVALID")
                }
            }
            checkSafe(!plans.containsKey(record.id), "DUPLICATE_PENDING_PLAN")
            plans[record.id] = Plan(record, operation, method, body, query)
        }
        fun clientForLogout(): OkHttpClient = client().addInterceptor(this).build()
        @Synchronized fun discardUnsubmittedPlans() { plans.clear() }
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var mutation: Plan? = null
            try {
                owned(store.load(), f)
                checkSafe(request.url.scheme == "https" && request.url.port == 443, "UNEXPECTED_ORIGIN")
                checkSafe(request.header("Authorization") == "Bearer ${store.load()!!.accessToken}", "UNEXPECTED_CREDENTIALS")
                if (request.url.host == "www.savely.cc") {
                    checkSafe(observedNonAdminProfile && !adminDenialAttempted && request.method == "POST" && request.url.encodedPath == "/api/admin" &&
                        request.url.queryParameterNames.isEmpty() && request.header("apikey") == null &&
                        sameObject(requestBody(request), JSONObject().put("action", "get-users")), "UNAUTHORIZED_ADMIN_DENIAL_PROBE")
                    adminDenialAttempted = true
                    return chain.proceed(request)
                }
                checkSafe(request.url.host == HOST && request.header("apikey") == BuildConfig.SUPABASE_ANON_KEY, "UNEXPECTED_ORIGIN_OR_APIKEY")
                if (request.url.encodedPath in setOf("/rest/v1/rpc/get_active_sessions", "/rest/v1/rpc/get_registrations_enabled")) {
                    // These two audited SQL functions only SELECT; every other RPC remains rejected.
                    checkSafe(request.method == "POST" && request.url.queryParameterNames.isEmpty() && requestBody(request).length() == 0, "READ_RPC_CONTRACT_MISMATCH")
                    return chain.proceed(request)
                }
                if (request.url.encodedPath == "/functions/v1/cardtrader-proxy") {
                    checkSafe(request.method == "GET", "CATALOG_MUTATION_REJECTED")
                    val action = request.url.queryParameter("action")
                    if (action == "search") {
                        checkSafe(request.url.queryParameterNames == setOf("action", "name") && request.url.queryParameterValues("action") == listOf("search") &&
                            request.url.queryParameterValues("name") == listOf("Black Lotus"), "CARD_SEARCH_SCOPE_REJECTED")
                        firstSearchedCardId = null
                    } else checkSafe(action == "prices" && firstSearchedCardId != null && request.url.queryParameterNames == setOf("action", "id", "condition") &&
                        request.url.queryParameterValues("action") == listOf("prices") && request.url.queryParameterValues("id") == listOf(firstSearchedCardId) &&
                        request.url.queryParameterValues("condition") == listOf("near_mint"), "CARD_PRICE_SCOPE_REJECTED")
                    val response = chain.proceed(request)
                    if (action == "search" && response.isSuccessful) try {
                        val rows = ExactJson.parse(response.peekBody(1_048_577).string()) as? JSONArray ?: throw Failure("CARD_SEARCH_RESPONSE_INVALID")
                        firstSearchedCardId = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                            .filter { it.optString("name").isNotBlank() }.mapNotNull { it.opt("id")?.toString() }
                            .firstOrNull { Regex("[0-9]{1,20}").matches(it) }
                    } catch (error: Throwable) { response.close(); throw error }
                    return response
                }
                if (request.method == "POST" && request.url.encodedPath == "/auth/v1/logout") {
                    checkSafe(logoutAllowed && request.url.queryParameterValues("scope") == listOf("local") && request.url.queryParameterNames == setOf("scope"), "LOGOUT_NOT_AUTHORIZED")
                    logoutAllowed = false
                    return chain.proceed(request)
                }
                checkSafe(request.url.encodedPath.startsWith("/rest/v1/"), "AUTH_RPC_OR_FUNCTION_WRITE_REJECTED")
                val table = request.url.encodedPath.removePrefix("/rest/v1/")
                checkSafe(table in READ_TABLES, "UNEXPECTED_TABLE")
                if (request.method == "GET") {
                    val scopes = request.url.queryParameterValues("user_id")
                    if (table == "couple_connections") {
                        checkSafe(request.url.queryParameterValues("or") == listOf("(user_a.eq.$OWNER,user_b.eq.$OWNER)"), "UNOWNED_COUPLE_READ")
                    } else if (table == "couple_connection_requests") {
                        checkSafe(request.url.queryParameterValues("or") == listOf("(sender_id.eq.$OWNER,receiver_id.eq.$OWNER)"), "UNOWNED_COUPLE_REQUEST_READ")
                    } else if (table in setOf("couple_budgets", "shared_expenses", "shared_expenses_view")) {
                        val scope = request.url.queryParameter("connection_id") ?: throw Failure("COUPLE_SCOPE_REQUIRED")
                        val ids = if (scope.startsWith("eq.")) listOf(scope.removePrefix("eq.")) else {
                            checkSafe(scope.startsWith("in.(") && scope.endsWith(")"), "COUPLE_SCOPE_INVALID")
                            scope.removePrefix("in.(").removeSuffix(")").split(',')
                        }
                        checkSafe(ids.isNotEmpty() && ids.all { it in connectionIds }, "FOREIGN_CONNECTION_READ_REJECTED")
                    } else checkSafe(scopes == listOf("eq.$OWNER"), "UNOWNED_READ_REJECTED")
                    val response = chain.proceed(request)
                    if (table == "profiles" && response.isSuccessful && request.url.queryParameter("select") == "*") try {
                        val rows = ExactJson.parse(response.peekBody(1_048_577).string()) as? JSONArray ?: throw Failure("PROFILE_RESPONSE_INVALID")
                        checkSafe(rows.length() == 1 && rows.getJSONObject(0).optString("user_id") == OWNER, "PROFILE_OWNER_NOT_CONFIRMED")
                        observedNonAdminProfile = SupabaseApi.parsePermissions(rows.getJSONObject(0).optJSONObject("permissions"))["admin"] != true
                    } catch (error: Throwable) { response.close(); throw error }
                    if (table == "couple_connections" && response.isSuccessful) try {
                        val rows = ExactJson.parse(response.peekBody(1_048_577).string()) as? JSONArray ?: throw Failure("COUPLE_RESPONSE_INVALID")
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i)
                            checkSafe(row.optString("user_a") == OWNER || row.optString("user_b") == OWNER, "FOREIGN_COUPLE_RESPONSE")
                            connectionIds.add(uuid(row.getString("id")))
                        }
                    } catch (error: Throwable) { response.close(); throw error }
                    return response
                }
                val body = if (request.method == "DELETE") null else requestBody(request)
                val id = if (request.method == "POST") body!!.getString("id") else request.url.queryParameter("id")?.removePrefix("eq.")
                    ?: throw Failure("MUTATION_ID_REQUIRED")
                val plan = synchronized(this) { plans.remove(id) } ?: throw Failure("UNPLANNED_WRITE_REJECTED")
                checkSafe(table == plan.record.table && request.method == plan.method, "MUTATION_TABLE_OR_METHOD_MISMATCH")
                val query = request.url.queryParameterNames.associateWith { request.url.queryParameterValues(it).filterNotNull() }
                checkSafe(query == plan.query, "MUTATION_FILTER_MISMATCH")
                checkSafe((body == null && plan.body == null) || (body != null && plan.body != null && sameObject(body, plan.body)), "MUTATION_PAYLOAD_MISMATCH")
                journal.reserve(id, plan.operation)
                mutation = plan
                val response = chain.proceed(request)
                val classification = if (response.isSuccessful) "accepted" else if (response.code in setOf(400, 401, 403, 404, 405, 409, 422)) "rejected" else "unknown"
                try { journal.response(id, plan.operation, classification) }
                catch (error: Throwable) { response.close(); throw error }
                return response
            } catch (error: Throwable) {
                // A reserved attempt remains unknown after transport loss. Nothing replays it.
                throw IOException(if (mutation == null) code(error) else "RESERVED_WRITE_RESPONSE_UNKNOWN")
            }
        }
    }

    private class Journal(val f: Fixture) {
        fun exists() = listOf("", ".bak", ".new").any { File(f.checkpoint.path + it).exists() }
        @Synchronized fun initialize() {
            checkSafe(!exists(), "EXISTING_CHECKPOINT_PRESERVED")
            val entries = JSONArray()
            f.records.forEach { record -> entries.put(JSONObject().put("id", record.id).put("table", record.table)
                .put("payload", record.payload).put("change", record.change).put("create_confirmed", false).put("absence_confirmed", false)
                .apply { listOf("create", "change", "delete").forEach { put("${it}_attempted", false); put("${it}_response", "none") } }) }
            writeJson(f.checkpoint, JSONObject().put("version", 1).put("run_id", f.run).put("session_id", f.session).put("day", f.day.toString())
                .put("owner", OWNER).put("project", HOST).put("first_pid", Process.myPid()).put("entries", entries)
                .put("records_removed", false).put("logout_attempted", false).put("logout_confirmed", false).put("local_cleared", false))
        }
        @Synchronized fun read(): JSONObject = readJson(f.checkpoint, 262_144, atomic = true).also(::validate)
        fun validate(json: JSONObject) {
            checkSafe(json.keys().asSequence().toSet() == setOf("version", "run_id", "session_id", "day", "owner", "project", "first_pid", "entries", "records_removed", "logout_attempted", "logout_confirmed", "local_cleared"), "CHECKPOINT_KEYS_INVALID")
            checkSafe(json.opt("version") is Number && equivalent(json.opt("version"), 1) && json.opt("first_pid") is Number &&
                json.getInt("version") == 1 && json.getString("run_id") == f.run && json.getString("session_id") == f.session &&
                json.getString("day") == f.day.toString() && json.getString("owner") == OWNER && json.getString("project") == HOST && json.getInt("first_pid") > 0,
                "CHECKPOINT_IDENTITY_MISMATCH")
            val entries = json.getJSONArray("entries")
            checkSafe(entries.length() == f.records.size, "CHECKPOINT_REGISTRY_SIZE_INVALID")
            for (i in 0 until entries.length()) {
                val row = entries.getJSONObject(i)
                val expected = f.records[i]
                checkSafe(row.keys().asSequence().toSet() == setOf("id", "table", "payload", "change", "create_confirmed", "absence_confirmed", "create_attempted", "create_response", "change_attempted", "change_response", "delete_attempted", "delete_response"), "REGISTRY_KEYS_INVALID")
                checkSafe(row.getString("id") == expected.id && row.getString("table") == expected.table && sameObject(row.getJSONObject("payload"), expected.payload) &&
                    sameObject(row.getJSONObject("change"), expected.change), "CHECKPOINT_PAYLOAD_MISMATCH")
                listOf("create_confirmed", "absence_confirmed", "create_attempted", "change_attempted", "delete_attempted").forEach { checkSafe(row.opt(it) is Boolean, "CHECKPOINT_FLAG_INVALID") }
                listOf("create", "change", "delete").forEach {
                    val response = row.getString("${it}_response")
                    checkSafe(response in setOf("none", "unknown", "accepted", "rejected") && (response != "none") == row.getBoolean("${it}_attempted"), "CHECKPOINT_RESPONSE_INVALID")
                }
                checkSafe(!row.getBoolean("create_confirmed") || row.getBoolean("create_attempted"), "CHECKPOINT_CREATE_INVALID")
                checkSafe(!row.getBoolean("change_attempted") || row.getBoolean("create_confirmed"), "CHECKPOINT_CHANGE_INVALID")
                checkSafe(!row.getBoolean("delete_attempted") || row.getBoolean("create_attempted"), "CHECKPOINT_DELETE_INVALID")
                checkSafe(!row.getBoolean("absence_confirmed") || !row.getBoolean("create_attempted") || row.getBoolean("create_confirmed") ||
                    row.getString("create_response") == "rejected" || row.getBoolean("delete_attempted"), "CHECKPOINT_ABSENCE_INVALID")
            }
            listOf("records_removed", "logout_attempted", "logout_confirmed", "local_cleared").forEach { checkSafe(json.opt(it) is Boolean, "CHECKPOINT_CLEANUP_FLAG_INVALID") }
            checkSafe(!json.getBoolean("records_removed") || (0 until entries.length()).all { entries.getJSONObject(it).getBoolean("absence_confirmed") }, "CHECKPOINT_REMOVAL_INVALID")
            checkSafe(!json.getBoolean("logout_attempted") || json.getBoolean("records_removed"), "CHECKPOINT_LOGOUT_INVALID")
            checkSafe(!json.getBoolean("logout_confirmed") || json.getBoolean("logout_attempted"), "CHECKPOINT_LOGOUT_CONFIRM_INVALID")
            checkSafe(!json.getBoolean("local_cleared") || json.getBoolean("logout_confirmed"), "CHECKPOINT_LOCAL_CLEAR_INVALID")
        }
        @Synchronized fun entry(id: String): JSONObject = find(read(), id)
        @Synchronized fun reserve(id: String, operation: String) {
            val json = read(); val row = find(json, id)
            checkSafe(operation in setOf("create", "change", "delete") && !row.getBoolean("${operation}_attempted") && !row.getBoolean("absence_confirmed"), "REPEATED_OR_ABSENT_WRITE_REJECTED")
            if (operation == "create") checkSafe(!row.getBoolean("delete_attempted"), "CREATE_AFTER_DELETE_REJECTED")
            if (operation == "change") checkSafe(row.getBoolean("create_confirmed") && !row.getBoolean("delete_attempted"), "CHANGE_BEFORE_CREATE_OR_AFTER_DELETE")
            if (operation == "delete") checkSafe(row.getBoolean("create_attempted"), "DELETE_WITHOUT_CREATE_REJECTED")
            row.put("${operation}_attempted", true).put("${operation}_response", "unknown")
            writeJson(f.checkpoint, json)
        }
        @Synchronized fun response(id: String, operation: String, outcome: String) {
            val json = read(); find(json, id).put("${operation}_response", outcome); writeJson(f.checkpoint, json)
        }
        @Synchronized fun confirmCreate(id: String) {
            val json = read(); val row = find(json, id)
            checkSafe(row.getBoolean("create_attempted"), "CREATE_CONFIRMATION_WITHOUT_ATTEMPT")
            row.put("create_confirmed", true); writeJson(f.checkpoint, json)
        }
        fun absenceConclusive(id: String): Boolean = entry(id).let { !it.getBoolean("create_attempted") || it.getBoolean("create_confirmed") || it.getString("create_response") == "rejected" || it.getBoolean("absence_confirmed") }
        @Synchronized fun confirmAbsence(id: String) {
            val json = read(); find(json, id).put("absence_confirmed", true); writeJson(f.checkpoint, json)
        }
        @Synchronized fun reserveLogout() {
            val json = read()
            checkSafe(json.getBoolean("records_removed") && !json.getBoolean("logout_attempted"), "LOGOUT_ALREADY_ATTEMPTED_OR_REMOVAL_PENDING")
            json.put("logout_attempted", true); writeJson(f.checkpoint, json)
        }
        @Synchronized fun mark(flag: String) {
            checkSafe(flag in setOf("records_removed", "logout_confirmed", "local_cleared"), "UNKNOWN_CLEANUP_FLAG")
            val json = read(); json.put(flag, true); validate(json); writeJson(f.checkpoint, json)
        }
        private fun find(json: JSONObject, id: String): JSONObject {
            val rows = json.getJSONArray("entries")
            return (0 until rows.length()).map { rows.getJSONObject(it) }.singleOrNull { it.getString("id") == id } ?: throw Failure("REGISTRY_UUID_UNKNOWN")
        }
    }

    private data class Record(val label: String, val table: String, val payload: JSONObject, val change: JSONObject = JSONObject()) {
        val id: String get() = payload.getString("id")
        val changed: JSONObject get() = JSONObject(payload.toString()).apply { change.keys().forEach { put(it, change.get(it)) } }
    }
    private data class Fixture(val context: Context, val run: String, val session: String, val day: LocalDate) {
        val input get() = context.filesDir.resolve(INPUT)
        val checkpoint get() = context.filesDir.resolve("roadmap-smoke-checkpoint-$run.json")
        fun result(recovery: Boolean) = context.filesDir.resolve(if (recovery) "roadmap-smoke-recovery-result-$run.json" else "roadmap-smoke-result-$run.json")
        private fun id(label: String) = UUID.nameUUIDFromBytes("$run:$label".toByteArray(Charsets.UTF_8)).toString()
        private val marker get() = "Android-Roadmap-QA-$run"
        val records: List<Record> get() {
            fun row(label: String) = JSONObject().put("id", id(label)).put("user_id", OWNER)
            val category = row("category").put("name", marker).put("type", "expense").put("icon", "wallet").put("color", "#123456")
            val tx = row("transaction").put("description", marker).put("type", "expense").put("amount", "0.01").put("currency", "EUR")
                .put("exchange_rate_eur", "1").put("date", day.toString()).put("category_id", id("category"))
            val budget = row("budget").put("category_id", id("category")).put("amount", "0.01").put("currency", "EUR").put("month", 1).put("year", 2000)
            val recurring = row("recurring").put("name", marker).put("amount", "0.01").put("currency", "EUR").put("category_id", id("category"))
                .put("frequency", "monthly").put("week_interval", 1).put("next_due_date", day.plusYears(10).toString()).put("is_active", false)
            fun asset(label: String) = row(label).put("name", marker).put("notes", marker).put("symbol", "QA" + run.replace("-", "").uppercase())
                .put("type", "other").put("quantity", "1").put("purchase_price", "0.01").put("current_price", "0.01").put("currency", "EUR")
                .put("purchase_date", day.toString()).put("sold_at", JSONObject.NULL).put("sold_price", JSONObject.NULL)
            val tcg = row("tcg").put("name", marker).put("notes", marker).put("category", "magic").put("condition", "near_mint")
                .put("quantity", 1).put("purchase_price", "0.01").put("current_price", "0.01").put("currency", "EUR").put("purchase_date", day.toString())
            val library = row("library").put("title", marker).put("notes", marker).put("category", "libri").put("quantity", 1).put("purchase_price", "0.01").put("reselling_value", "0.01")
            val cbd = row("fumo").put("marca", marker).put("descrizione", marker).put("costo", "0.01").put("grammi", "0.01").put("data_acquisto", day.toString())
            val poker = row("poker").put("name", marker).put("amount", "0.01")
            fun history(label: String, parent: String) = row(label).put("asset_id", id(parent)).put("price", "0.01").put("recorded_at", day.toString())
            val amount = JSONObject().put("amount", "0.02")
            val close = JSONObject().put("sold_at", day.toString()).put("sold_price", "0.02")
            return listOf(Record("category", "categories", category, JSONObject().put("color", "#654321")), Record("transaction", "transactions", tx, amount),
                Record("budget", "budgets", budget, amount), Record("recurring", "recurring_expenses", recurring, amount),
                Record("asset_one", "portfolio_assets", asset("asset_one"), close), Record("asset_two", "portfolio_assets", asset("asset_two"), close),
                Record("tcg", "tgc_cards", tcg, JSONObject().put("current_price", "0.02")), Record("library", "library_items", library, JSONObject().put("reselling_value", "0.02")),
                Record("fumo", "cbd", cbd, JSONObject().put("costo", "0.02")), Record("poker", "poker_manual_expenses", poker, amount),
                Record("history_one", "asset_price_history", history("history_one", "asset_one")), Record("history_two", "asset_price_history", history("history_two", "asset_two")))
        }
    }
    private data class Cleanup(val removed: Boolean = false, val loggedOut: Boolean = false, val cleared: Boolean = false, val inputRemoved: Boolean = false, val failure: String? = null) {
        val complete get() = removed && loggedOut && cleared && inputRemoved && failure == null
    }
    private class Failure(val code: String) : IllegalStateException(code)

    private companion object {
        const val OWNER = "9daffd41-39bf-4b1e-be68-abf12549c4a7"
        const val EMAIL = "e2e-savely@example.com"
        const val HOST = "crqnfbahytzenisospcx.supabase.co"
        const val PROJECT = "https://$HOST"
        const val INPUT = "roadmap-smoke-session.json"
        val JSON = "application/json; charset=utf-8".toMediaType()
        val SECTIONS = listOf("dashboard", "transactions", "categories", "budget", "recurring", "charts", "portfolio", "net-worth", "insights")
        val READ_TABLES = TransferRepository.USER_TABLES.toSet() + setOf("profiles", "net_worth_snapshots", "shared_expenses_view")
        fun arguments() = InstrumentationRegistry.getArguments()
        fun application(): Context = InstrumentationRegistry.getInstrumentation().targetContext.also {
            checkSafe(BuildConfig.DEBUG && it.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && it.packageName == "cc.savely.android" &&
                BuildConfig.SUPABASE_URL.trimEnd('/') == PROJECT, "DEBUG_APPLICATION_OR_PROJECT_MISMATCH")
        }
        fun emptyLocalState(context: Context, allowThisCheckpoint: String? = null) {
            checkSafe(!context.getSharedPreferences("savely_session", Context.MODE_PRIVATE).contains("session_v1"), "EXISTING_LOCAL_SESSION_PRESERVED")
            val files = context.filesDir.listFiles() ?: throw Failure("PRIVATE_DIRECTORY_UNREADABLE")
            checkSafe(files.none { file ->
                (file.name.startsWith("finance-form-checkpoint-") || file.name.startsWith("roadmap-smoke-checkpoint-")) &&
                    (allowThisCheckpoint == null || file.name !in listOf(allowThisCheckpoint, "$allowThisCheckpoint.bak", "$allowThisCheckpoint.new"))
            }, "EXISTING_CHECKPOINT_PRESERVED")
        }
        fun fixture(flag: String): Fixture {
            checkSafe(arguments().getString(flag) == "true", "EXPLICIT_OPT_IN_REQUIRED")
            val run = uuid(arguments().getString("roadmapRunId") ?: throw Failure("RUN_ID_REQUIRED"))
            val session = uuid(arguments().getString("roadmapSessionId") ?: throw Failure("SESSION_ID_REQUIRED"))
            val date = arguments().getString("roadmapDate") ?: throw Failure("DATE_REQUIRED")
            val day = LocalDate.parse(date)
            checkSafe(day.toString() == date, "DATE_NON_CANONICAL")
            return Fixture(application(), run, session, day)
        }
        fun checkSafe(value: Boolean, code: String) { if (!value) throw Failure(code) }
        fun fileExists(file: File) = listOf("", ".bak", ".new").any { File(file.path + it).exists() }
        fun uuid(value: String): String = canonicalAuthSessionId(value) ?: throw Failure("UUID_INVALID")
        fun owned(session: Session?, f: Fixture) = checkSafe(session != null && session.userId == OWNER && session.email == EMAIL && session.authSessionId() == f.session, "DIFFERENT_SESSION_PRESERVED")
        fun client() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(45, TimeUnit.SECONDS)
        suspend fun identity(f: Fixture, session: Session) = withContext(Dispatchers.IO) {
            owned(session, f)
            val client = client().build()
            try {
                val request = Request.Builder().url("$PROJECT/auth/v1/user").header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer ${session.accessToken}").build()
                client.newCall(request).execute().use { response ->
                    checkSafe(response.isSuccessful, "SERVER_IDENTITY_NOT_CONFIRMED")
                    val text = response.peekBody(65_537).bytes()
                    checkSafe(text.size in 1..65_536, "IDENTITY_RESPONSE_TOO_LARGE_OR_EMPTY")
                    val user = ExactJson.parse(text.toString(Charsets.UTF_8)) as? JSONObject ?: throw Failure("IDENTITY_RESPONSE_INVALID")
                    checkSafe(user.optString("id") == OWNER && user.optString("email") == EMAIL && user.has("email_confirmed_at") && !user.isNull("email_confirmed_at"), "SERVER_QA_IDENTITY_MISMATCH")
                    val factors = user.optJSONArray("factors")
                    // GoTrue can omit the empty factors array. Any present, nonempty array blocks this run.
                    checkSafe(!user.has("factors") || user.isNull("factors") || factors != null && factors.length() == 0, "EXISTING_MFA_FACTORS_PRESERVED")
                }
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
        suspend fun select(api: SupabaseApi, record: Record) = api.select(record.table, "select=*&id=eq.${record.id}&user_id=eq.$OWNER&limit=2")
        suspend fun requireRow(api: SupabaseApi, record: Record, journal: Journal): JSONObject {
            val rows = select(api, record)
            checkSafe(rows.length() == 1, "OWNED_ROW_NOT_UNIQUE_OR_MISSING")
            val row = rows.getJSONObject(0)
            val state = journal.entry(record.id)
            checkSafe(state.getBoolean("create_attempted") && (matches(row, record.payload) || (state.getBoolean("change_attempted") && matches(row, record.changed))), "CHANGED_ROW_PRESERVED")
            if (record.table in setOf("transactions", "categories", "recurring_expenses")) checkSafe(row.has("deleted_at") && row.isNull("deleted_at"), "SOFT_DELETED_ROW_PRESERVED")
            return row
        }
        fun equivalent(a: Any?, b: Any?): Boolean {
            if (a == null || b == null || a == JSONObject.NULL || b == JSONObject.NULL) return a == b
            if (a is Number || b is Number || (a is String && b is String && a.toBigDecimalOrNull() != null && b.toBigDecimalOrNull() != null)) {
                return runCatching { BigDecimal(a.toString()).compareTo(BigDecimal(b.toString())) == 0 }.getOrDefault(false)
            }
            return a == b
        }
        fun matches(row: JSONObject, payload: JSONObject) = payload.keys().asSequence().all { row.has(it) && equivalent(row.opt(it), payload.opt(it)) }
        fun filterMatches(filter: String, value: Any?): Boolean = when {
            value == null || value == JSONObject.NULL -> filter == "is.null"
            value is Boolean -> filter == "eq.$value"
            else -> filter.startsWith("eq.") && equivalent(filter.removePrefix("eq."), value)
        }
        fun sameObject(a: JSONObject, b: JSONObject) = a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && matches(a, b)
        fun filters(row: JSONObject, keys: Set<String>): Map<String, List<String>> = keys.sorted().associateWith { key ->
            checkSafe(row.has(key), "CAS_FIELD_MISSING")
            val value = row.get(key)
            checkSafe(value !is JSONObject && value !is JSONArray, "CAS_NESTED_FIELD_REJECTED")
            listOf(if (value == JSONObject.NULL) "is.null" else "eq.$value")
        }
        fun withOwnerAndId(query: Map<String, List<String>>, id: String) = query + mapOf("user_id" to listOf("eq.$OWNER"), "id" to listOf("eq.$id"))
        fun queryString(query: Map<String, List<String>>) = query.entries.joinToString("&") { (key, values) -> "$key=${URLEncoder.encode(values.single(), "UTF-8")}" }
        fun requestBody(request: Request): JSONObject {
            val body = request.body ?: throw Failure("BODY_REQUIRED")
            checkSafe(body.contentLength() in 1..16_384, "BODY_SIZE_REJECTED")
            val buffer = Buffer(); body.writeTo(buffer)
            return ExactJson.parse(buffer.readUtf8()) as? JSONObject ?: throw Failure("BODY_INVALID")
        }
        fun readJson(file: File, limit: Int, atomic: Boolean = false): JSONObject = (if (atomic) AtomicFile(file).openRead() else file.inputStream()).use { input ->
            val bytes = ByteArray(limit + 1)
            var count = 0
            while (count < bytes.size) { val next = input.read(bytes, count, bytes.size - count); if (next < 0) break; count += next }
            checkSafe(count in 1..limit, "PRIVATE_JSON_TOO_LARGE_OR_EMPTY")
            ExactJson.parse(String(bytes, 0, count, Charsets.UTF_8)) as? JSONObject ?: throw Failure("PRIVATE_JSON_INVALID")
        }
        fun writeJson(file: File, json: JSONObject) {
            val atomic = AtomicFile(file); val output = atomic.startWrite()
            try { output.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
            catch (error: Throwable) { atomic.failWrite(output); throw error }
        }
        fun code(error: Throwable): String = when (error) {
            is Failure -> error.code
            is ApiException -> "API_${error.kind.name}_${error.status ?: 0}"
            else -> "NATIVE_OPERATION_${error.javaClass.simpleName.filter(Char::isLetterOrDigit).take(48)}"
        }
    }
}
