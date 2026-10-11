package cc.savely.android.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.util.AtomicFile
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.savely.android.BuildConfig
import cc.savely.android.finance.FinanceScreen
import cc.savely.android.ui.SavelyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Exclude this class from ordinary instrumentation. Each method requires its own explicit opt-in.
 * Scope: production FinanceScreen/form with test-transport UUID, not MainActivity or the DB UUID default.
 */
@RunWith(AndroidJUnit4::class)
class RealFinanceFormSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun preflightEmptyLocalStateWithoutCredentials() {
        requireSafe(InstrumentationRegistry.getArguments().getString("financeFormPreflight") == "true", "PREFLIGHT_OPT_IN_REQUIRED")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        requireSafe(BuildConfig.DEBUG && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            context.packageName == "cc.savely.android" && BuildConfig.SUPABASE_URL.trimEnd('/') == PROJECT, "DEBUG_APPLICATION_OR_PROJECT_MISMATCH")
        requireSafe(!context.getSharedPreferences("savely_session", Context.MODE_PRIVATE).contains("session_v1"), "EXISTING_LOCAL_SESSION_PRESERVED")
        requireNoPendingCheckpoint(context)
        // No SessionStore.load(), network, server identity request, or credential is used in this phase.
    }

    @Test fun productionFormCreatesEditsAndDeletesOnlyOwnedQaMarker() = runBlocking {
        val fixture = realFixture("realFinanceFormSmoke")
        requireSafe(fixture.day == LocalDate.now() && fixture.day == LocalDate.now(ZoneId.of("Europe/Rome")), "DEVICE_DATE_MISMATCH")
        val store = AndroidSessionStore(fixture.context)
        val journal = Journal(fixture)
        val transport = GuardedTransport(fixture, store, journal)
        val client = client().addInterceptor(transport).build()
        val api = api(fixture, store, client)
        val visible = mutableStateOf(true)
        var installed = false
        var uiPassed = false
        var failure: String? = null
        var phase = "session_setup"
        try {
            // Reject existing ciphertext before load(): the production store clears corrupt ciphertext on error.
            requireSafe(!fixture.context.getSharedPreferences("savely_session", Context.MODE_PRIVATE).contains("session_v1"),
                "EXISTING_LOCAL_SESSION_PRESERVED")
            requireSafe(store.load() == null, "EXISTING_LOCAL_SESSION_PRESERVED")
            requireNoPendingCheckpoint(fixture.context)
            requireSafe(!journal.exists(), "EXISTING_CHECKPOINT_PRESERVED")
            val bootstrap = Session.fromJson(readJson(fixture.input, 16_384))
            requireOwned(bootstrap, fixture)
            requireSafe(bootstrap.expiresAt > Instant.now().epochSecond + 600, "BOOTSTRAP_VALIDITY_TOO_SHORT")
            val identityClient = client().build()
            try { assertServerIdentity(fixture, bootstrap, identityClient) }
            finally { identityClient.dispatcher.executorService.shutdown(); identityClient.connectionPool.evictAll() }
            installed = true
            withContext(Dispatchers.IO) { store.save(bootstrap) }
            requireSafe(store.load() == bootstrap, "ENCRYPTED_SESSION_REREAD_FAILED")
            requireSafe(fixture.input.delete(), "PRIVATE_INPUT_REMOVAL_FAILED")
            requireSafe(selectId(api, fixture).length() == 0, "PREEXISTING_UUID_PRESERVED")
            requireSafe(api.select("transactions", "select=id&user_id=eq.$QA_USER&description=eq.${fixture.marker}&limit=2").length() == 0,
                "PREEXISTING_MARKER_PRESERVED")
            journal.initialize()
            phase = "open_form"
            compose.setContent { if (visible.value) SavelyTheme("light") {
                FinanceScreen(api, "transactions", Modifier.fillMaxSize(), accountKey = QA_USER)
            } }
            waitText("Nuova transazione")
            touch(hasText("Nuova transazione"))
            waitText("Nuovo elemento")
            phase = "fill_form"
            compose.onNodeWithText("Importo").performScrollTo().performTextInput("0,01")
            compose.onNodeWithText("Descrizione facoltativa").performScrollTo().performTextInput(fixture.marker)
            compose.onNodeWithText("Tipo: Uscita").assertExists()
            // Fail rather than silently submit a profile's non-EUR default.
            compose.onNodeWithText("Valuta: EUR").performScrollTo().assertExists()
            compose.onNodeWithText("Categoria: Senza categoria").performScrollTo().assertExists()
            requireOwned(store.load(), fixture)
            journal.assertStage("prepared")
            phase = "submit_create"
            touch(hasText("Salva", substring = false))
            waitEditorClosed()
            phase = "verify_create"
            requireRow(selectId(api, fixture), fixture, "0.01")
            journal.confirm("created")
            phase = "filter_created"
            list().performScrollToIndex(0)
            compose.onNodeWithText("Cerca descrizione o categoria").performTextInput(fixture.marker)
            waitText("1 transazioni")
            list().performScrollToNode(hasContentDescription("Modifica ${fixture.marker}"))
            compose.onNode(hasText(fixture.marker, substring = false) and !hasSetTextAction()).assertIsDisplayed()
            phase = "display_created_amount"
            compose.onNodeWithText("−0,01", substring = true).assertIsDisplayed()
            phase = "open_edit"
            touch(hasContentDescription("Modifica ${fixture.marker}"))
            waitText("Modifica elemento")
            phase = "fill_edit"
            compose.onNodeWithText("Importo").performScrollTo().performTextReplacement("0,02")
            requireOwned(store.load(), fixture)
            phase = "submit_update"
            touch(hasText("Salva", substring = false))
            waitEditorClosed()
            phase = "verify_update"
            requireRow(selectId(api, fixture), fixture, "0.02")
            journal.confirm("updated")
            list().performScrollToNode(hasContentDescription("Modifica ${fixture.marker}"))
            phase = "display_updated_amount"
            compose.waitUntil(45_000) { compose.onAllNodesWithText("−0,02", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("−0,02", substring = true).assertIsDisplayed()
            phase = "open_delete"
            touch(hasContentDescription("Elimina ${fixture.marker}"))
            waitText("Eliminare ${fixture.marker}?")
            requireSafe(journal.read().opt("delete_attempted") == false, "CONFIRMATION_ALREADY_MUTATED")
            requireRow(selectId(api, fixture), fixture, "0.02")
            requireOwned(store.load(), fixture)
            phase = "submit_delete"
            touch(hasText("Elimina", substring = false))
            compose.waitUntil(45_000) { compose.onAllNodesWithText("Eliminare ${fixture.marker}?").fetchSemanticsNodes().isEmpty() }
            phase = "verify_delete"
            requireSafe(selectId(api, fixture).length() == 0, "UI_DELETE_NOT_CONFIRMED")
            journal.confirm("removed")
            list().performScrollToIndex(0)
            phase = "verify_empty_list"
            waitText("0 transazioni")
            uiPassed = true
        } catch (error: Throwable) {
            failure = "${safeCode(error)}_${phase}_${error.javaClass.simpleName.filter { it.isLetterOrDigit() }.take(64)}"
        } finally {
            runCatching { compose.runOnIdle { visible.value = false } }
            val outcome = if (installed) cleanup(fixture, store, journal, api, client) else Cleanup(false, false, false, "SESSION_NOT_INSTALLED")
            writeResult(fixture, journal, uiPassed && outcome.complete, uiPassed, outcome, failure)
            api.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        requireSafe(uiPassed && failure == null && !journal.exists(), failure ?: "FORM_OR_CLEANUP_INCOMPLETE")
    }

    /** A new instrumentation process only reconciles/cleans this exact run; it never opens or submits a form. */
    @Test fun recoverOwnedMarkerInNewProcess() = runBlocking {
        val fixture = realFixture("realFinanceFormRecovery")
        val journal = Journal(fixture)
        val checkpoint = journal.read()
        requireSafe(checkpoint.getInt("first_pid") != Process.myPid(), "RECOVERY_REQUIRES_NEW_PROCESS")
        val store = AndroidSessionStore(fixture.context)
        requireOwned(store.load(), fixture)
        val client = client().addInterceptor(GuardedTransport(fixture, store, journal)).build()
        val api = api(fixture, store, client)
        try {
            val outcome = cleanup(fixture, store, journal, api, client)
            writeResult(fixture, journal, outcome.complete, false, outcome, outcome.failure)
            requireSafe(outcome.complete && !journal.exists(), outcome.failure ?: "RECOVERY_PENDING")
        } finally {
            api.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /** Synthetic TLS only; the host must run this before obtaining any real credential. */
    @Test fun checkpointAndTransportFixturesUseSyntheticTlsOnly() {
        requireSafe(InstrumentationRegistry.getArguments().getString("financeFormFixtures") == "true", "FIXTURE_OPT_IN_REQUIRED")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val certificate = HeldCertificate.Builder().commonName("Finance form guard fixture").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val run = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val fixture = Fixture(context, run, sessionId, LocalDate.parse("2026-10-11"), server.url("/").toString(), "synthetic-public")
        val claims = Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject().put("session_id", sessionId).toString().toByteArray())
        val session = Session("header.$claims.signature", "synthetic-refresh", Long.MAX_VALUE, QA_USER, QA_EMAIL)
        val store = MemorySessionStore(session)
        val journal = Journal(fixture)
        val guard = GuardedTransport(fixture, store, journal)
        val client = client().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).addInterceptor(guard)
            .addNetworkInterceptor { chain ->
                if (chain.request().method in setOf("POST", "PATCH", "DELETE")) {
                    val checkpoint = journal.read()
                    val flag = when (chain.request().method) { "POST" -> "creation_attempted"; "PATCH" -> "update_attempted"; else -> "delete_attempted" }
                    requireSafe(checkpoint.opt(flag) == true, "MISSING_DURABLE_RESERVATION_BEFORE_WIRE")
                    requireSafe(checkpoint.optString("stage") in setOf("creating", "updating", "deleting"), "WRONG_STAGE_BEFORE_WIRE")
                }
                chain.proceed(chain.request())
            }.build()
        fun request(method: String, body: JSONObject? = null, id: String? = null): Request {
            val url = server.url("rest/v1/transactions").newBuilder()
            if (method != "POST") { url.addQueryParameter("user_id", "eq.$QA_USER"); id?.let { url.addQueryParameter("id", "eq.$it") } }
            return Request.Builder().url(url.build()).header("apikey", fixture.publicKey).header("Authorization", "Bearer ${session.accessToken}")
                .method(method, body?.toString()?.toRequestBody(JSON)).build()
        }
        try {
            journal.initialize()
            val base = journal.read()
            base.keys().asSequence().toSet().forEach { field ->
                val changed = JSONObject(base.toString()).put(field, "changed")
                assertThrows(SmokeFailure::class.java) { validateCheckpoint(changed, fixture) }
            }
            val owned = payload(fixture, "0.01").put("deleted_at", JSONObject.NULL)
            assertTrue(ownRow(owned, fixture, "0.01"))
            (listOf("id", "user_id") + frozenFields).forEach { field ->
                assertFalse(ownRow(JSONObject(owned.toString()).put(field, "different"), fixture, "0.01"))
            }
            listOf("user_id", "currency", "date", "type", "category_id", "exchange_rate_eur", "amount").forEach { field ->
                val changed = payload(fixture, "0.01", includeId = false).put(field, "changed")
                assertThrows(IOException::class.java) { guard.prepare(request("POST", changed)) }
            }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[]"))
            client.newCall(request("POST", payload(fixture, "0.01", includeId = false))).execute().close()
            val posted = server.takeRequest()
            assertEquals(fixture.runId, JSONObject(posted.body.readUtf8()).getString("id"))
            assertEquals(true, journal.read().getBoolean("creation_attempted"))
            assertEquals("accepted", journal.read().getString("create_response"))
            assertThrows(IOException::class.java) { guard.prepare(request("POST", payload(fixture, "0.01", includeId = false))) }
            assertEquals(1, server.requestCount)
            journal.confirm("created")
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[]"))
            client.newCall(request("PATCH", payload(fixture, "0.02", includeId = false).apply { remove("user_id") }, run)).execute().close()
            val updated = server.takeRequest().requestUrl!!
            assertEquals("eq.0.01", updated.queryParameter("amount"))
            assertEquals("eq.${fixture.marker}", updated.queryParameter("description"))
            assertEquals("is.null", updated.queryParameter("category_id"))
            journal.confirm("updated")
            val wrong = request("DELETE", id = UUID.randomUUID().toString())
            assertThrows(IOException::class.java) { guard.prepare(wrong) }
            assertEquals(2, server.requestCount)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[]"))
            client.newCall(request("DELETE", id = run)).execute().close()
            val deleted = server.takeRequest().requestUrl!!
            assertEquals("eq.0.02", deleted.queryParameter("amount"))
            assertEquals("eq.$run", deleted.queryParameter("id"))
            assertEquals("eq.$QA_USER", deleted.queryParameter("user_id"))
            assertThrows(IOException::class.java) { guard.prepare(request("DELETE", id = run)) }
            // Unknown POST + zero visible rows remains pending; absence alone never authorizes completion.
            val unknown = JSONObject(base.toString()).put("creation_attempted", true).put("create_response", "unknown").put("stage", "creating")
            assertFalse(absenceConclusive(unknown))
            assertTrue(absenceConclusive(JSONObject(unknown.toString()).put("create_response", "rejected")))
            assertTrue(absenceConclusive(JSONObject(unknown.toString()).put("creation_confirmed", true)))
            // Update can have committed before its response failed: both precise expected amounts may be reconciled.
            val ambiguousUpdate = JSONObject(unknown.toString()).put("creation_confirmed", true).put("update_attempted", true).put("stage", "updating")
            assertTrue(amountAllowed(ambiguousUpdate, "0.01"))
            assertTrue(amountAllowed(ambiguousUpdate, "0.02"))
            assertFalse(amountAllowed(unknown, "0.02"))
            assertFalse(amountAllowed(ambiguousUpdate, "0.03"))
            val invalidStage = JSONObject(base.toString()).put("stage", "created")
            assertThrows(SmokeFailure::class.java) { validateCheckpoint(invalidStage, fixture) }
            // A full request can reach the server and lose its response. No retry or empty-read success follows.
            val lostFixture = fixture.copy(runId = UUID.randomUUID().toString())
            val lostJournal = Journal(lostFixture)
            val lostGuard = GuardedTransport(lostFixture, store, lostJournal)
            val lostClient = client().callTimeout(2, TimeUnit.SECONDS)
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).addInterceptor(lostGuard).build()
            try {
                lostJournal.initialize()
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
                val lostRequest = Request.Builder().url(server.url("rest/v1/transactions"))
                    .header("apikey", lostFixture.publicKey).header("Authorization", "Bearer ${session.accessToken}")
                    .post(payload(lostFixture, "0.01", includeId = false).toString().toRequestBody(JSON)).build()
                assertThrows(IOException::class.java) { lostClient.newCall(lostRequest).execute().close() }
                assertEquals(lostFixture.runId, JSONObject(server.takeRequest().body.readUtf8()).getString("id"))
                assertEquals("unknown", lostJournal.read().getString("create_response"))
                assertFalse(absenceConclusive(lostJournal.read()))
                assertThrows(IOException::class.java) { lostGuard.prepare(lostRequest) }
                // Explicit cleanup is one additional CAS attempt, persisted and never repeated after ambiguity.
                lostJournal.reserveCleanup("0.01")
                assertThrows(SmokeFailure::class.java) { lostJournal.reserveCleanup("0.01") }
                assertFalse(absenceConclusive(lostJournal.read()))
                assertTrue(amountAllowed(lostJournal.read(), "0.01"))
            } finally {
                AtomicFile(lostFixture.checkpoint).delete()
                lostClient.dispatcher.executorService.shutdown()
                lostClient.connectionPool.evictAll()
            }
            store.save(session.copy(userId = UUID.randomUUID().toString()))
            assertThrows(IOException::class.java) { guard.prepare(request("GET", id = run)) }
            assertEquals(4, server.requestCount)
        } finally {
            AtomicFile(fixture.checkpoint).delete()
            fixture.result.delete()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }

    private suspend fun cleanup(fixture: Fixture, store: SessionStore, journal: Journal, api: SupabaseApi, client: OkHttpClient): Cleanup =
        withContext(NonCancellable + Dispatchers.IO) {
            var removed = false
            var loggedOut = false
            var cleared = false
            try {
                requireOwned(store.load(), fixture)
                assertServerIdentity(fixture, store.load()!!, client)
                if (journal.exists()) {
                    val checkpoint = journal.read()
                    val rows = selectId(api, fixture)
                    requireSafe(rows.length() <= 1, "NON_UNIQUE_UUID_PRESERVED")
                    if (rows.length() == 1) {
                        val row = rows.getJSONObject(0)
                        val amount = row.opt("amount")?.toString() ?: throw SmokeFailure("ROW_AMOUNT_MISSING")
                        requireSafe(amountAllowed(checkpoint, amount) && ownRow(row, fixture, amount), "CHANGED_ROW_PRESERVED")
                        // A distinct cleanup reservation follows a fresh read, with every field frozen in the DELETE.
                        journal.reserveCleanup(amount)
                        try { api.delete("transactions", fixture.runId, "user_id=eq.$QA_USER&${frozenQuery(fixture, amount)}") }
                        catch (_: Exception) { /* Never repeat DELETE; reconcile the exact UUID below. */ }
                        requireSafe(selectId(api, fixture).length() == 0, "CLEANUP_DELETE_PENDING")
                        journal.confirm("removed")
                    } else {
                        requireSafe(absenceConclusive(checkpoint), "UNKNOWN_CREATE_ABSENCE_PENDING")
                    }
                }
                removed = true
                // Do not revoke the only recovery credential while financial cleanup remains pending.
                val logoutSession = store.load()
                requireOwned(logoutSession, fixture)
                val request = Request.Builder().url("${fixture.endpoint.trimEnd('/')}/auth/v1/logout?scope=local")
                    .header("apikey", fixture.publicKey).header("Authorization", "Bearer ${logoutSession!!.accessToken}")
                    .post("{}".toRequestBody(JSON)).build()
                client.newCall(request).execute().use { requireSafe(it.isSuccessful, "TEMPORARY_LOGOUT_PENDING") }
                loggedOut = true
                requireOwned(store.load(), fixture)
                store.clear()
                requireSafe(store.load() == null, "LOCAL_SESSION_CLEAR_PENDING")
                cleared = true
                if (journal.exists()) { AtomicFile(fixture.checkpoint).delete(); requireSafe(!journal.exists(), "CHECKPOINT_REMOVAL_PENDING") }
                Cleanup(true, true, true, null)
            } catch (error: Throwable) {
                // Financial uncertainty keeps the credential; after logout, a retained file is not a usable session.
                Cleanup(removed, cleared, loggedOut, safeCode(error))
            }
        }

    private class GuardedTransport(val fixture: Fixture, val store: SessionStore, val journal: Journal) : Interceptor {
        @Volatile private var cleanupAmount: String? = null
        fun prepare(request: Request): Request {
            try {
                val saved = store.load()
                requireOwned(saved, fixture)
                val uri = URI(fixture.endpoint)
                requireSafe(request.url.scheme == uri.scheme && request.url.host == uri.host && request.url.port == (if (uri.port == -1) 443 else uri.port),
                    "UNEXPECTED_HTTP_ORIGIN")
                requireSafe(request.header("Authorization") == "Bearer ${saved!!.accessToken}" && request.header("apikey") == fixture.publicKey, "UNEXPECTED_HTTP_CREDENTIALS")
                val path = request.url.encodedPath
                if (request.method == "GET") {
                    if (path == "/auth/v1/user") return request
                    requireSafe(path in listOf("/rest/v1/categories", "/rest/v1/profiles", "/rest/v1/transactions") &&
                        request.url.queryParameterValues("user_id") == listOf("eq.$QA_USER"), "UNOWNED_READ_REJECTED")
                    request.url.queryParameter("id")?.let { requireSafe(it == "eq.${fixture.runId}", "FOREIGN_UUID_READ_REJECTED") }
                    request.url.queryParameter("description")?.let { requireSafe(it == "eq.${fixture.marker}", "FOREIGN_MARKER_READ_REJECTED") }
                    return request
                }
                if (path == "/auth/v1/logout" && request.method == "POST") {
                    requireSafe(request.url.queryParameterValues("scope") == listOf("local"), "GLOBAL_LOGOUT_REJECTED")
                    return request
                }
                requireSafe(path == "/rest/v1/transactions", "OTHER_TABLE_WRITE_REJECTED")
                if (request.method == "POST") {
                    requireSafe(request.url.queryParameterNames.isEmpty(), "INSERT_QUERY_REJECTED")
                    val body = requestBody(request)
                    requireSafe(body.keys().asSequence().toSet() == payload(fixture, "0.01", includeId = false).keys().asSequence().toSet() && ownPayload(body, fixture, "0.01", true), "INSERT_PAYLOAD_REJECTED")
                    journal.reserve("create")
                    return request.newBuilder().post(body.put("id", fixture.runId).toString().toRequestBody(JSON)).build()
                }
                requireSafe(request.url.queryParameterValues("id") == listOf("eq.${fixture.runId}") &&
                    request.url.queryParameterValues("user_id") == listOf("eq.$QA_USER"), "FOREIGN_MUTATION_REJECTED")
                val state = journal.read()
                val cleanup = request.method == "DELETE" && state.opt("cleanup_delete_attempted") == true && state.optString("stage") == "cleanup_pending"
                val expectedAmount = if (cleanup) state.getString("cleanup_amount") else if (request.method == "PATCH") "0.01" else "0.02"
                if (cleanup) {
                    requireSafe(cleanupAmount == null, "SECOND_CLEANUP_DELETE_REJECTED")
                    cleanupAmount = expectedAmount
                    requireSafe(request.url.queryParameterNames == (setOf("id", "user_id") + frozenFields), "CLEANUP_QUERY_REJECTED")
                    frozenFilters(fixture, expectedAmount).forEach { (key, value) -> requireSafe(request.url.queryParameterValues(key) == listOf(value), "CLEANUP_FIELD_REJECTED") }
                } else {
                    requireSafe(request.url.queryParameterNames == setOf("id", "user_id"), "NATIVE_MUTATION_QUERY_REJECTED")
                    if (request.method == "PATCH") {
                        val body = requestBody(request)
                        requireSafe(body.keys().asSequence().toSet() == payload(fixture, "0.02", includeId = false).apply { remove("user_id") }.keys().asSequence().toSet() &&
                            ownPayload(body, fixture, "0.02", false), "UPDATE_PAYLOAD_REJECTED")
                        journal.reserve("update")
                    } else { requireSafe(request.method == "DELETE", "UNSUPPORTED_MUTATION"); journal.reserve("delete") }
                }
                val url = request.url.newBuilder()
                if (!cleanup) frozenFilters(fixture, expectedAmount).forEach { (key, value) -> url.addQueryParameter(key, value) }
                return request.newBuilder().url(url.build()).build()
            } catch (error: Exception) { throw IOException(safeCode(error)) }
        }
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = prepare(chain.request())
            val response = chain.proceed(request)
            if (request.url.encodedPath == "/rest/v1/transactions" && request.method != "GET") {
                val operation = when (request.method) { "POST" -> "create"; "PATCH" -> "update"; else -> if (cleanupAmount != null) "cleanup" else "delete" }
                val outcome = if (response.isSuccessful) "accepted" else if (response.code in setOf(400, 401, 403, 404, 405, 409, 422)) "rejected" else "unknown"
                try { journal.response(operation, outcome) } catch (error: Exception) { response.close(); throw IOException(safeCode(error)) }
            }
            return response
        }
    }

    private class Journal(val fixture: Fixture) {
        fun exists() = fixture.checkpoint.exists() || File("${fixture.checkpoint.path}.bak").exists() || File("${fixture.checkpoint.path}.new").exists()
        @Synchronized fun initialize() { requireSafe(!exists(), "EXISTING_CHECKPOINT_PRESERVED"); writeJson(fixture.checkpoint, metadata(fixture)) }
        @Synchronized fun read(): JSONObject = AtomicFile(fixture.checkpoint).openRead().use { stream ->
            val bytes = stream.readBytesBounded(8_192)
            val json = ExactJson.parse(bytes.toString(Charsets.UTF_8)) as? JSONObject ?: throw SmokeFailure("CHECKPOINT_INVALID")
            validateCheckpoint(json, fixture)
            json
        }
        @Synchronized fun assertStage(stage: String) = requireSafe(read().getString("stage") == stage, "CHECKPOINT_STAGE_MISMATCH")
        @Synchronized fun reserve(operation: String) {
            val json = read()
            val attempted = if (operation == "create") "creation_attempted" else "${operation}_attempted"
            val previous = when (operation) { "create" -> "prepared"; "update" -> "created"; else -> "updated" }
            requireSafe(json.opt(attempted) == false && json.optString("stage") == previous && json.opt("cleanup_delete_attempted") == false,
                "SECOND_OR_OUT_OF_STAGE_SUBMIT_REJECTED")
            val next = when (operation) { "create" -> "creating"; "update" -> "updating"; else -> "deleting" }
            writeJson(fixture.checkpoint, json.put(attempted, true).put("stage", next).put("${operation}_response", "unknown"))
        }
        @Synchronized fun reserveCleanup(amount: String) {
            val json = read()
            requireSafe(json.opt("cleanup_delete_attempted") == false && amountAllowed(json, amount), "CLEANUP_ALREADY_ATTEMPTED_OR_AMOUNT_CHANGED")
            writeJson(fixture.checkpoint, json.put("cleanup_delete_attempted", true).put("cleanup_amount", canonicalMoney(amount))
                .put("stage", "cleanup_pending").put("cleanup_response", "unknown"))
        }
        @Synchronized fun response(operation: String, outcome: String) { writeJson(fixture.checkpoint, read().put("${operation}_response", outcome)) }
        @Synchronized fun confirm(stage: String) {
            val json = read()
            val required = when (stage) { "created" -> "creation_attempted"; "updated" -> "update_attempted"; else -> "creation_attempted" }
            requireSafe(json.opt(required) == true, "CONFIRM_WITHOUT_ATTEMPT")
            writeJson(fixture.checkpoint, json.put("stage", stage).put(when (stage) {
                "created" -> "creation_confirmed"; "updated" -> "update_confirmed"; else -> "removal_confirmed" }, true))
        }
    }

    private fun realFixture(flag: String): Fixture {
        val args = InstrumentationRegistry.getArguments()
        requireSafe(args.getString(flag) == "true", "EXPLICIT_OPT_IN_REQUIRED")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        requireSafe(BuildConfig.DEBUG && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0, "DEBUG_APPLICATION_REQUIRED")
        requireSafe(context.packageName == "cc.savely.android" && BuildConfig.SUPABASE_URL.trimEnd('/') == PROJECT, "APPLICATION_OR_PROJECT_MISMATCH")
        val run = canonicalAuthSessionId(args.getString("financeFormRunId")) ?: throw SmokeFailure("INVALID_RUN_UUID")
        val session = canonicalAuthSessionId(args.getString("financeFormSessionId")) ?: throw SmokeFailure("INVALID_SESSION_UUID")
        val text = args.getString("financeFormDate") ?: throw SmokeFailure("DATE_REQUIRED")
        val day = runCatching { LocalDate.parse(text) }.getOrNull() ?: throw SmokeFailure("DATE_INVALID")
        requireSafe(day.toString() == text, "DATE_NON_CANONICAL")
        return Fixture(context, run, session, day, PROJECT, BuildConfig.SUPABASE_ANON_KEY)
    }

    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun waitText(text: String) = compose.waitUntil(45_000) { compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() }
    private fun waitEditorClosed() = compose.waitUntil(45_000) {
        compose.onAllNodesWithText("Nuovo elemento").fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithText("Modifica elemento").fetchSemanticsNodes().isEmpty()
    }
    private fun touch(matcher: SemanticsMatcher) {
        val node = compose.onNode(matcher)
        if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()) list().performScrollToNode(matcher) else runCatching { node.performScrollTo() }
        compose.waitForIdle()
        var last: Rect? = null
        var samples = 0
        compose.waitUntil(15_000) {
            val target = node.fetchSemanticsNode()
            val bounds = target.boundsInRoot
            var top = target
            while (top.parent != null) top = top.parent!!
            val root = top.boundsInRoot
            val ready = !target.config.contains(SemanticsProperties.Disabled) && bounds.width > 0 && bounds.height > 0 &&
                bounds.left >= root.left && bounds.top >= root.top && bounds.right <= root.right && bounds.bottom <= root.bottom
            samples = if (ready && bounds == last) samples + 1 else 0
            last = bounds
            samples >= 3
        }
        node.assertIsEnabled().assertIsDisplayed().performTouchInput { click() }
    }

    private data class Fixture(val context: Context, val runId: String, val sessionId: String, val day: LocalDate, val endpoint: String, val publicKey: String) {
        val marker get() = "Android-Form-QA-$runId"
        val checkpoint get() = context.filesDir.resolve("finance-form-checkpoint-$runId.json")
        val input get() = context.filesDir.resolve("finance-form-session-$runId.json")
        val result get() = context.filesDir.resolve("finance-form-result-$runId.json")
    }
    private data class Cleanup(val removed: Boolean, val cleared: Boolean, val loggedOut: Boolean, val failure: String?) {
        val complete get() = removed && cleared && loggedOut && failure == null
    }
    private class SmokeFailure(val code: String) : IllegalStateException(code)

    private companion object {
        const val PROJECT = "https://crqnfbahytzenisospcx.supabase.co"
        const val QA_USER = "9daffd41-39bf-4b1e-be68-abf12549c4a7"
        const val QA_EMAIL = "e2e-savely@example.com"
        val JSON = "application/json".toMediaType()
        val frozenFields = setOf("description", "amount", "currency", "date", "type", "category_id", "exchange_rate_eur", "deleted_at")
        fun client() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS)
        fun api(f: Fixture, store: SessionStore, client: OkHttpClient) = SupabaseApi(BackendConfig(f.endpoint, f.publicKey, BuildConfig.WEB_URL), store, client)
        fun requireSafe(condition: Boolean, code: String) { if (!condition) throw SmokeFailure(code) }
        fun requireNoPendingCheckpoint(context: Context) {
            val names = context.filesDir.list() ?: throw SmokeFailure("PRIVATE_DIRECTORY_UNREADABLE")
            requireSafe(names.none { it.startsWith("finance-form-checkpoint-") }, "EXISTING_CHECKPOINT_PRESERVED")
        }
        fun requireOwned(session: Session?, f: Fixture) = requireSafe(session != null && session.userId == QA_USER && session.email == QA_EMAIL && session.authSessionId() == f.sessionId, "DIFFERENT_LOCAL_SESSION_PRESERVED")
        fun canonicalMoney(text: String): String = BigDecimal(text).setScale(2).toPlainString()
        fun decimalEquals(value: Any?, text: String) = runCatching { BigDecimal(value.toString()).compareTo(BigDecimal(text)) == 0 }.getOrDefault(false)
        fun payload(f: Fixture, amount: String, includeId: Boolean = true) = JSONObject().put("user_id", QA_USER).put("description", f.marker)
            .put("amount", amount).put("currency", "EUR").put("date", f.day.toString()).put("type", "expense")
            .put("category_id", JSONObject.NULL).put("exchange_rate_eur", "1").apply { if (includeId) put("id", f.runId) }
        fun ownPayload(row: JSONObject, f: Fixture, amount: String, owner: Boolean) =
            (!owner || row.optString("user_id") == QA_USER) && row.optString("description") == f.marker && decimalEquals(row.opt("amount"), amount) &&
                row.optString("currency") == "EUR" && row.optString("date") == f.day.toString() && row.optString("type") == "expense" &&
                row.has("category_id") && row.isNull("category_id") && decimalEquals(row.opt("exchange_rate_eur"), "1")
        fun ownRow(row: JSONObject, f: Fixture, amount: String) = row.optString("id") == f.runId && ownPayload(row, f, amount, true) &&
            row.has("deleted_at") && row.isNull("deleted_at")
        fun frozenFilters(f: Fixture, amount: String) = linkedMapOf("description" to "eq.${f.marker}", "amount" to "eq.${canonicalMoney(amount)}",
            "currency" to "eq.EUR", "date" to "eq.${f.day}", "type" to "eq.expense", "category_id" to "is.null", "exchange_rate_eur" to "eq.1", "deleted_at" to "is.null")
        fun frozenQuery(f: Fixture, amount: String) = frozenFilters(f, amount).entries.joinToString("&") { "${it.key}=${it.value}" }
        suspend fun selectId(api: SupabaseApi, f: Fixture): JSONArray = api.select("transactions", "select=id,user_id,description,amount,currency,date,type,category_id,exchange_rate_eur,deleted_at&id=eq.${f.runId}&user_id=eq.$QA_USER&limit=2")
        fun requireRow(rows: JSONArray, f: Fixture, amount: String) = requireSafe(rows.length() == 1 && ownRow(rows.getJSONObject(0), f, amount), "ROW_PAYLOAD_OR_UUID_MISMATCH")
        fun amountAllowed(j: JSONObject, amount: String) = j.opt("creation_attempted") == true &&
            (decimalEquals(amount, "0.01") || (j.opt("update_attempted") == true && decimalEquals(amount, "0.02")))
        fun absenceConclusive(j: JSONObject) = j.opt("creation_attempted") == false || j.opt("creation_confirmed") == true || j.opt("removal_confirmed") == true || j.optString("create_response") == "rejected"
        fun metadata(f: Fixture) = JSONObject().put("version", 1).put("run_id", f.runId).put("session_id", f.sessionId).put("user_id", QA_USER)
            .put("email", QA_EMAIL).put("project_host", URI(f.endpoint).host).put("transaction_id", f.runId).put("marker", f.marker)
            .put("amount", "0.01").put("currency", "EUR").put("date", f.day.toString()).put("type", "expense").put("category_id", JSONObject.NULL)
            .put("exchange_rate_eur", "1").put("stage", "prepared").put("first_pid", Process.myPid()).put("cleanup_amount", JSONObject.NULL)
            .apply { listOf("creation_attempted", "creation_confirmed", "update_attempted", "update_confirmed", "delete_attempted", "removal_confirmed", "cleanup_delete_attempted").forEach { put(it, false) }
                listOf("create_response", "update_response", "delete_response", "cleanup_response").forEach { put(it, "none") } }
        fun validateCheckpoint(j: JSONObject, f: Fixture) {
            val expected = metadata(f)
            requireSafe(j.keys().asSequence().toSet() == expected.keys().asSequence().toSet(), "CHECKPOINT_FIELDS_MISMATCH")
            requireSafe(j.opt("version") is Number && decimalEquals(j.opt("version"), "1"), "CHECKPOINT_VERSION_INVALID")
            listOf("run_id", "session_id", "user_id", "email", "project_host", "transaction_id", "marker", "amount", "currency", "date", "type", "category_id", "exchange_rate_eur").forEach {
                requireSafe(j.opt(it) == expected.opt(it), "CHECKPOINT_OWNER_OR_PAYLOAD_MISMATCH") }
            val flags = listOf("creation_attempted", "creation_confirmed", "update_attempted", "update_confirmed", "delete_attempted", "removal_confirmed", "cleanup_delete_attempted")
            flags.forEach { requireSafe(j.opt(it) is Boolean, "CHECKPOINT_FLAG_INVALID") }
            val pid = j.opt("first_pid")
            requireSafe((pid is Int || pid is Long) && j.getLong("first_pid") in 1..Int.MAX_VALUE.toLong(), "CHECKPOINT_PID_INVALID")
            listOf("create_response", "update_response", "delete_response", "cleanup_response").forEach {
                requireSafe(j.opt(it) in setOf("none", "unknown", "accepted", "rejected"), "CHECKPOINT_RESPONSE_INVALID") }
            requireSafe(j.optString("stage") in setOf("prepared", "creating", "created", "updating", "updated", "deleting", "removed", "cleanup_pending"), "CHECKPOINT_STAGE_INVALID")
            requireSafe(!j.getBoolean("creation_confirmed") || j.getBoolean("creation_attempted"), "CHECKPOINT_CREATE_INCONSISTENT")
            requireSafe(!j.getBoolean("update_attempted") || j.getBoolean("creation_confirmed"), "CHECKPOINT_UPDATE_INCONSISTENT")
            requireSafe(!j.getBoolean("update_confirmed") || j.getBoolean("update_attempted"), "CHECKPOINT_UPDATE_CONFIRM_INCONSISTENT")
            requireSafe(!j.getBoolean("delete_attempted") || j.getBoolean("update_confirmed"), "CHECKPOINT_DELETE_INCONSISTENT")
            requireSafe(if (j.getBoolean("cleanup_delete_attempted")) j.optString("cleanup_amount") in setOf("0.01", "0.02") else j.isNull("cleanup_amount"), "CHECKPOINT_CLEANUP_AMOUNT_INVALID")
            requireSafe(!j.getBoolean("cleanup_delete_attempted") || amountAllowed(j, j.optString("cleanup_amount")), "CHECKPOINT_CLEANUP_STAGE_INVALID")
            requireSafe(!j.getBoolean("removal_confirmed") || (j.getBoolean("creation_attempted") &&
                (j.getBoolean("delete_attempted") || j.getBoolean("cleanup_delete_attempted"))), "CHECKPOINT_REMOVAL_INCONSISTENT")
            mapOf("create" to "creation_attempted", "update" to "update_attempted", "delete" to "delete_attempted", "cleanup" to "cleanup_delete_attempted").forEach { (op, attempted) ->
                requireSafe((j.optString("${op}_response") != "none") == j.getBoolean(attempted), "CHECKPOINT_RESPONSE_STAGE_INVALID")
            }
            val consistentStage = when (j.getString("stage")) {
                "prepared" -> !j.getBoolean("creation_attempted")
                "creating" -> j.getBoolean("creation_attempted") && !j.getBoolean("creation_confirmed") && !j.getBoolean("cleanup_delete_attempted")
                "created" -> j.getBoolean("creation_confirmed") && !j.getBoolean("update_attempted") && !j.getBoolean("cleanup_delete_attempted")
                "updating" -> j.getBoolean("update_attempted") && !j.getBoolean("update_confirmed") && !j.getBoolean("cleanup_delete_attempted")
                "updated" -> j.getBoolean("update_confirmed") && !j.getBoolean("delete_attempted") && !j.getBoolean("cleanup_delete_attempted")
                "deleting" -> j.getBoolean("delete_attempted") && !j.getBoolean("removal_confirmed") && !j.getBoolean("cleanup_delete_attempted")
                "removed" -> j.getBoolean("removal_confirmed")
                "cleanup_pending" -> j.getBoolean("cleanup_delete_attempted") && !j.getBoolean("removal_confirmed")
                else -> false
            }
            requireSafe(consistentStage, "CHECKPOINT_STAGE_FLAGS_INCONSISTENT")
        }
        fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
            val buffer = ByteArray(max + 1)
            var count = 0
            while (count < buffer.size) { val n = read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
            requireSafe(count in 1..max, "PRIVATE_JSON_TOO_LARGE_OR_EMPTY")
            return buffer.copyOf(count)
        }
        fun readJson(file: File, max: Int): JSONObject = file.inputStream().use {
            ExactJson.parse(it.readBytesBounded(max).toString(Charsets.UTF_8)) as? JSONObject ?: throw SmokeFailure("PRIVATE_JSON_INVALID") }
        fun writeJson(file: File, value: JSONObject) {
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try { out.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
            catch (error: Throwable) { atomic.failWrite(out); throw error }
        }
        fun requestBody(r: Request): JSONObject {
            val body = r.body ?: throw SmokeFailure("REQUEST_BODY_REQUIRED")
            requireSafe(body.contentLength() in 1..4_096, "REQUEST_BODY_SIZE_REJECTED")
            val buffer = Buffer(); body.writeTo(buffer)
            return ExactJson.parse(buffer.readUtf8()) as? JSONObject ?: throw SmokeFailure("REQUEST_BODY_INVALID")
        }
        suspend fun assertServerIdentity(f: Fixture, session: Session, client: OkHttpClient) = withContext(Dispatchers.IO) {
            requireOwned(session, f)
            val request = Request.Builder().url("${f.endpoint.trimEnd('/')}/auth/v1/user").header("apikey", f.publicKey)
                .header("Authorization", "Bearer ${session.accessToken}").build()
            client.newCall(request).execute().use { response ->
                requireSafe(response.isSuccessful, "SERVER_IDENTITY_NOT_CONFIRMED")
                val source = response.body?.source() ?: throw SmokeFailure("EMPTY_IDENTITY_RESPONSE")
                requireSafe(!source.request(65_537), "IDENTITY_RESPONSE_TOO_LARGE")
                val user = ExactJson.parse(source.readUtf8()) as? JSONObject ?: throw SmokeFailure("IDENTITY_RESPONSE_INVALID")
                requireSafe(user.optString("id") == QA_USER && user.optString("email") == QA_EMAIL && !user.isNull("email_confirmed_at") && user.has("email_confirmed_at"), "SERVER_QA_IDENTITY_MISMATCH")
                val factors = user.optJSONArray("factors")
                requireSafe(!user.has("factors") || user.isNull("factors") || factors != null, "MFA_RESPONSE_INVALID")
                requireSafe(factors == null || (0 until factors.length()).none { factors.optJSONObject(it)?.optString("status") == "verified" }, "QA_MFA_REQUIRED")
            }
        }
        fun safeCode(error: Throwable) = when (error) { is SmokeFailure -> error.code; is ApiException -> "API_${error.kind.name}_${error.status ?: 0}"; else -> "NATIVE_FORM_OPERATION_FAILED" }
        fun writeResult(f: Fixture, journal: Journal, passed: Boolean, uiPassed: Boolean, outcome: Cleanup, failure: String?) {
            val result = JSONObject().put("run_id", f.runId).put("session_id", f.sessionId).put("user_id", QA_USER).put("email", QA_EMAIL)
                .put("project_host", URI(f.endpoint).host).put("transaction_id", f.runId).put("marker", f.marker).put("date", f.day.toString())
                .put("scope", "production-FinanceScreen-form/test-transport-UUID").put("stage", if (passed) "passed" else "failed")
                .put("form_crud_verified", uiPassed).put("transaction_removed", outcome.removed).put("cleanup_local", outcome.cleared)
                .put("logout_server", outcome.loggedOut).put("cleanup_pending", !outcome.complete).put("checkpoint_retained", journal.exists())
            (failure ?: outcome.failure)?.let { result.put("failure_code", it) }
            writeJson(f.result, result)
        }
    }
}
