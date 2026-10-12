package cc.savely.android.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Native Couple flows against an isolated HTTPS fixture, with no real account or backend mutation. */
@RunWith(AndroidJUnit4::class)
class CoupleScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private lateinit var store: MemorySessionStore
    private val connected = AtomicBoolean(false)
    private val invited = AtomicBoolean(false)
    private val shared = AtomicBoolean(false)
    private val lookups = AtomicInteger()
    private val sharePosts = AtomicInteger()
    private val shareBody = AtomicReference("")
    private val showArchive = AtomicBoolean(false)
    private val failArchive = AtomicBoolean(false)
    private val archiveReads = AtomicInteger()
    private val privateReads = AtomicInteger()
    private val mutations = AtomicInteger()
    private val incomingInvitation = AtomicBoolean(false)
    private val invitationClosed = AtomicBoolean(false)
    private val invitationPatches = AtomicInteger()
    private val requestReadsAfterPatch = AtomicInteger()
    private val invitationPatchBody = AtomicReference("")
    private val invitationPatchQuery = AtomicReference<Map<String, String?>>(emptyMap())
    private val newOwnerMutations = AtomicInteger()
    private val holdInvitationPatch = AtomicBoolean(false)
    private val releaseInvitationPatch = CountDownLatch(1)

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Savely Couple fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS)
            // The gated PATCH must outlive its bounded 15-second response gate, not become a transport timeout.
            .readTimeout(20, TimeUnit.SECONDS).build()
        server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); dispatcher = fixture(); start() }
        store = MemorySessionStore(Session("couple-ui-token", "refresh", System.currentTimeMillis() / 1000 + 3600, USER, "couple-ui@example.test"))
        api = SupabaseApi(BackendConfig(server.url("/").toString(), "couple-public-key"), store, client)
    }
    @After fun teardown() {
        releaseInvitationPatch.countDown()
        server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }

    @Test fun validatesPartnerCodeBeforeLookupAndDisablesAnotherPendingInvitation() {
        compose.setContent { SavelyTheme("light") { CoupleScreen(api) } }
        waitFor("ABCD2345")
        compose.onNodeWithText("Codice del partner").performTextInput("OABC2345")
        compose.onNodeWithText("Invita partner").performClick()
        waitFor("Non usare O", substring = true)
        assertEquals(0, lookups.get())
        compose.onNodeWithText("Codice del partner").performTextClearance()
        compose.onNodeWithText("Codice del partner").performTextInput("abcd2345")
        compose.onNodeWithText("Invita partner").performClick()
        waitFor("Invito inviato")
        assertEquals(1, lookups.get())
        compose.onNodeWithText("Invita partner").assertIsNotEnabled()
    }

    @Test fun incomingInvitationRejectUsesReceiverAndPendingCasThenDisappearsAfterRefresh() {
        incomingInvitation.set(true)
        finishPendingInvitation("Invito ricevuto", "Rifiuta", "rejected", "receiver_id")
    }

    @Test fun outgoingInvitationCancelUsesSenderAndPendingCasThenDisappearsAfterRefresh() {
        invited.set(true)
        finishPendingInvitation("Invito inviato", "Annulla invito", "cancelled", "sender_id")
    }

    private fun finishPendingInvitation(title: String, action: String, status: String, ownerColumn: String) {
        compose.setContent { SavelyTheme("light") { CoupleScreen(api) } }
        waitFor(title)
        compose.onNodeWithText(action).performScrollTo().performClick()
        compose.waitUntil(15_000) {
            invitationPatches.get() == 1 && requestReadsAfterPatch.get() == 1 &&
                compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodes(hasText("Aggiorna") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        assertInvitationPatch(status, ownerColumn)
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNodeWithText(action).assertDoesNotExist()
        compose.onNodeWithText("Accetta").assertDoesNotExist()
        compose.onNodeWithText("Connessione attiva").assertDoesNotExist()
        assertEquals(1, mutations.get())
        assertEquals(0, privateReads.get())
        assertEquals(0, lookups.get())
        assertEquals(0, sharePosts.get())
        assertEquals(0, newOwnerMutations.get())
    }

    @Test fun lateRejectResponseFromOldOwnerClearsStaleInvitationsWithoutWritingAsNewOwner() {
        incomingInvitation.set(true)
        holdInvitationPatch.set(true)
        compose.setContent { SavelyTheme("light") { CoupleScreen(api) } }
        waitFor("Invito ricevuto")
        val originalRevision = requireNotNull(api.sessionOwnerRevision())
        compose.onNodeWithText("Rifiuta").performScrollTo().performClick()
        compose.waitUntil(15_000) { invitationPatches.get() == 1 }
        compose.onNodeWithText("Rifiuta").assertIsNotEnabled()
        compose.onNodeWithText("Accetta").assertIsNotEnabled()

        store.save(Session("couple-new-owner-token", "new-refresh", System.currentTimeMillis() / 1000 + 3600,
            PARTNER, "couple-new-owner@example.test"))
        // Observing the replacement through the API advances its revision; changing the store alone does not.
        assertEquals(PARTNER, runBlocking { api.session() }?.userId)
        assertNotEquals(originalRevision, api.sessionOwnerRevision())
        releaseInvitationPatch.countDown()

        waitFor("La sessione è cambiata. Aggiorna la schermata prima di continuare.")
        compose.onNodeWithText("ABCD2345").assertDoesNotExist()
        compose.onNodeWithText("Invito ricevuto").assertDoesNotExist()
        compose.onNodeWithText("Invito inviato").assertDoesNotExist()
        compose.onNodeWithText("Rifiuta").assertDoesNotExist()
        compose.onNodeWithText("Accetta").assertDoesNotExist()
        assertEquals(0, requestReadsAfterPatch.get())
        assertInvitationPatch("rejected", "receiver_id")

        compose.onNodeWithText("Aggiorna").performScrollTo().performClick()
        waitFor("WXYZ2345")
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("Aggiorna") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Invito ricevuto").assertDoesNotExist()
        compose.onNodeWithText("Invito inviato").assertDoesNotExist()
        assertEquals(PARTNER, store.load()?.userId)
        assertEquals(1, invitationPatches.get())
        assertEquals(1, mutations.get())
        assertEquals(0, newOwnerMutations.get())
        assertEquals(0, privateReads.get())
        assertEquals(0, lookups.get())
        assertEquals(0, sharePosts.get())
    }

    private fun assertInvitationPatch(status: String, ownerColumn: String) {
        assertEquals("{\"status\":\"$status\"}", invitationPatchBody.get())
        assertEquals(mapOf("id" to "eq.$REQUEST", ownerColumn to "eq.$USER", "status" to "eq.pending"),
            invitationPatchQuery.get())
    }

    @Test fun rendersBudgetMedianAndValidatesCustomSplitBeforeSharing() {
        connected.set(true)
        compose.setContent { SavelyTheme("light") { CoupleScreen(api) } }
        waitFor("Mediana mensile EUR:", substring = true)
        compose.onNodeWithText("Riepilogo budget EUR").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Budget totale: ${cc.savely.android.finance.formatMoney(java.math.BigDecimal("12"), "EUR")}").assertExists()
        compose.onNodeWithText("Spesa mediana mensile reale: ${cc.savely.android.finance.formatMoney(java.math.BigDecimal("15"), "EUR")}").assertExists()
        compose.onNodeWithText("Differenza: ${cc.savely.android.finance.formatMoney(java.math.BigDecimal("3"), "EUR")}").assertExists()
        compose.onNodeWithText("Mediana 15,00", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Scegli transazione").performScrollTo().performClick()
        compose.onNodeWithText("${LocalDate.now()} · 1.01 EUR · Spesa nuova").performClick()
        compose.onNodeWithText("Le quote uguali superano", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Quota personalizzata").performScrollTo().performClick()
        compose.onNodeWithText("Importo del partner nella valuta della transazione").performScrollTo().performTextInput("0")
        compose.onNodeWithText("Condividi spesa").performScrollTo().performClick()
        waitFor("Inserisci un importo positivo", substring = true)
        assertEquals(0, sharePosts.get())
        compose.onNodeWithText("Importo del partner nella valuta della transazione").performScrollTo().performTextClearance()
        compose.onNodeWithText("Importo del partner nella valuta della transazione").performTextInput("0,50")
        compose.onNodeWithText("Condividi spesa").performScrollTo().performClick()
        // The selection is cleared before the async reload ends; wait for refreshed data and an enabled button.
        compose.waitUntil(15_000) {
            sharePosts.get() == 1 &&
                compose.onAllNodesWithText("3 condivisioni caricate, 3 mostrate.", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("Scegli transazione") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        val body = JSONObject(shareBody.get())
        assertEquals("custom", body.getString("split_mode"))
        assertEquals("0.50", body.getString("partner_amount"))
        assertEquals(USER, body.getString("created_by"))
        assertFalse(body.has("category_id"))
        compose.onNodeWithText("Scegli transazione").assertIsEnabled().performScrollTo().performClick()
        waitFor("${LocalDate.now()} · 1.01 EUR · Spesa nuova")
        compose.onNodeWithText("${LocalDate.now()} · 1.01 EUR · Spesa nuova").assertIsNotEnabled()
    }

    @Test fun archiveShowsRevokedMetadataReadOnlyAndNeverLoadsFormerPartnerExpensesOrBudgets() {
        showArchive.set(true)
        compose.setContent { SavelyTheme("light") { CoupleScreen(api) } }
        waitFor("ABCD2345")
        compose.onNodeWithText("Connessioni archiviate").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connessione revocata il ", substring = true).performScrollTo().assertTextContains("2026", substring = true)
        compose.onNodeWithText("Creata il ", substring = true).assertTextContains("2025", substring = true)
        compose.onNodeWithText("Sola lettura").assertExists()
        compose.onNodeWithText("Per privacy, spese condivise", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Salva budget").assertDoesNotExist()
        compose.onNodeWithText("Condividi spesa").assertDoesNotExist()
        compose.onNodeWithText("Revoca connessione").assertDoesNotExist()
        compose.onNodeWithText("Rimuovi").assertDoesNotExist()
        assertEquals(2, archiveReads.get())
        assertEquals(0, privateReads.get())
        assertEquals(0, mutations.get())
    }

    @Test fun failedArchiveReadShowsErrorWithoutClaimingThereAreNoArchivedConnections() {
        failArchive.set(true)
        compose.setContent { SavelyTheme("light") { CoupleScreen(api) } }
        compose.waitUntil(15_000) { archiveReads.get() == 1 && compose.onAllNodes(hasText("Aggiorna") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Nessuna connessione archiviata").assertDoesNotExist()
        compose.onNodeWithText("Connessioni archiviate").assertDoesNotExist()
        compose.onNodeWithText("Servizio", substring = true).assertExists()
        assertEquals(0, privateReads.get())
        assertEquals(0, mutations.get())
    }

    private fun waitFor(text: String, substring: Boolean = false) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun fixture() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.method != "GET") mutations.incrementAndGet()
            val newOwner = request.getHeader("Authorization") == "Bearer couple-new-owner-token"
            if (newOwner && request.method != "GET") newOwnerMutations.incrementAndGet()
            if ((!newOwner && request.getHeader("Authorization") != "Bearer couple-ui-token") || request.getHeader("apikey") != "couple-public-key") return json("{}", 401)
            val url = request.requestUrl ?: return json("{}", 400)
            val reader = if (newOwner) PARTNER else USER
            return when (url.encodedPath) {
                "/rest/v1/profiles" -> if (url.queryParameter("user_id") != "eq.$reader") json("{}", 403)
                else json("""[{"couple_code":"${if (newOwner) "WXYZ2345" else "ABCD2345"}","permissions":{"couple_expenses":true}}]""")
                "/rest/v1/couple_connections" -> if (url.queryParameter("or") != "(user_a.eq.$reader,user_b.eq.$reader)") json("{}", 403)
                else if (url.queryParameter("revoked_at") == "not.is.null") {
                    archiveReads.incrementAndGet()
                    if (failArchive.get()) json("{}", 503)
                    else json(if (showArchive.get() && url.queryParameter("id") == null)
                        """[{"id":"$CONNECTION","user_a":"$USER","user_b":"$PARTNER","created_at":"2025-01-02T12:00:00Z","revoked_at":"2026-02-03T12:00:00Z"}]""" else "[]")
                } else json(if (connected.get()) """[{"id":"$CONNECTION","user_a":"$USER","user_b":"$PARTNER"}]""" else "[]")
                "/rest/v1/couple_connection_requests" -> when (request.method) {
                    "POST" -> { invited.set(true); json("""[{"id":"$REQUEST","sender_id":"$USER","receiver_id":"$PARTNER","status":"pending"}]""") }
                    "PATCH" -> {
                        invitationPatchBody.set(request.body.readUtf8())
                        invitationPatchQuery.set(url.queryParameterNames.associateWith { url.queryParameter(it) })
                        invitationPatches.incrementAndGet()
                        if (holdInvitationPatch.get() && !releaseInvitationPatch.await(15, TimeUnit.SECONDS)) return json("{}", 503)
                        val incoming = incomingInvitation.get()
                        val status = if (incoming) "rejected" else "cancelled"
                        val ownerColumn = if (incoming) "receiver_id" else "sender_id"
                        if (newOwner || invitationPatchBody.get() != "{\"status\":\"$status\"}" ||
                            invitationPatchQuery.get() != mapOf("id" to "eq.$REQUEST", ownerColumn to "eq.$USER", "status" to "eq.pending")) return json("{}", 400)
                        invitationClosed.set(true)
                        json("""[{"id":"$REQUEST","sender_id":"${if (incoming) PARTNER else USER}","receiver_id":"${if (incoming) USER else PARTNER}","status":"$status"}]""")
                    }
                    "GET" -> {
                        if (invitationClosed.get() && !newOwner) requestReadsAfterPatch.incrementAndGet()
                        val owner = if (newOwner) PARTNER else USER
                        if (url.queryParameter("or") != "(sender_id.eq.$owner,receiver_id.eq.$owner)" || url.queryParameter("status") != "eq.pending") json("{}", 403)
                        else json(if (!newOwner && !invitationClosed.get() && (invited.get() || incomingInvitation.get()) && url.queryParameter("id") == null)
                            """[{"id":"$REQUEST","sender_id":"${if (incomingInvitation.get()) PARTNER else USER}","receiver_id":"${if (incomingInvitation.get()) USER else PARTNER}","status":"pending"}]""" else "[]")
                    }
                    else -> json("{}", 405)
                }
                "/rest/v1/rpc/find_user_by_couple_code" -> {
                    lookups.incrementAndGet()
                    if (JSONObject(request.body.readUtf8()).getString("p_code") != "ABCD2345") json("null") else json("\"$PARTNER\"")
                }
                "/rest/v1/shared_expenses_view" -> {
                    privateReads.incrementAndGet()
                    if (url.queryParameter("id") != null) json("[]") else {
                        val rows = JSONArray().put(expense(SHARE1, OLD_TX, "2026-01-01", "10.00", "Casa"))
                            .put(expense(SHARE2, OLD_TX2, "2026-02-01", "20.00", "Casa"))
                        if (shared.get()) rows.put(expense(SHARE3, TX, LocalDate.now().toString(), "0.50", null))
                        json(rows.toString())
                    }
                }
                "/rest/v1/couple_budgets" -> {
                    privateReads.incrementAndGet()
                    json(if (url.queryParameter("id") == null) """[{"id":"$BUDGET","couple_category_name":"Casa","amount":12,"currency":"EUR"}]""" else "[]")
                }
                "/rest/v1/transactions" -> json(if (url.queryParameter("id")?.startsWith("gt.") == true) "[]" else """[{"id":"$TX","amount":1.01,"currency":"EUR","date":"${LocalDate.now()}","description":"Spesa nuova","user_id":"$USER","type":"expense"}]""")
                "/rest/v1/shared_expenses" -> if (request.method == "POST") {
                    shareBody.set(request.body.readUtf8()); sharePosts.incrementAndGet(); shared.set(true); json("""[{"id":"$SHARE3"}]""")
                } else json(if (shared.get()) """[{"id":"$SHARE3"}]""" else "[]")
                else -> json("{}", 404)
            }
        }
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun expense(id: String, tx: String, date: String, share: String, category: String?) = JSONObject()
        .put("id", id).put("original_tx_id", tx).put("created_by", USER).put("date", date).put("my_share_amount", share)
        .put("currency", "EUR").put("description", if (tx == TX) "Spesa nuova" else "Spesa precedente")
        .put("couple_category_name", category ?: JSONObject.NULL).put("tx_deleted_at", JSONObject.NULL)

    private companion object {
        const val USER = "10000000-0000-0000-0000-000000000001"
        const val PARTNER = "10000000-0000-0000-0000-000000000002"
        const val CONNECTION = "20000000-0000-0000-0000-000000000001"
        const val TX = "30000000-0000-0000-0000-000000000003"
        const val OLD_TX = "30000000-0000-0000-0000-000000000001"
        const val OLD_TX2 = "30000000-0000-0000-0000-000000000002"
        const val SHARE1 = "40000000-0000-0000-0000-000000000001"
        const val SHARE2 = "40000000-0000-0000-0000-000000000002"
        const val SHARE3 = "40000000-0000-0000-0000-000000000003"
        const val BUDGET = "50000000-0000-0000-0000-000000000001"
        const val REQUEST = "60000000-0000-0000-0000-000000000001"
    }
}
