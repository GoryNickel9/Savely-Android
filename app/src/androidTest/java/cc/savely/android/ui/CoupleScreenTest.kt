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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private val transactionCurrency = AtomicReference("EUR")
    private val distinctShareOwners = AtomicBoolean(false)
    private val removedShares = ConcurrentHashMap.newKeySet<String>()
    private val shareDeletes = AtomicInteger()
    private val partnerRemovalRpcs = AtomicInteger()
    private val budgetPresent = AtomicBoolean(true)
    private val budgetAmount = AtomicReference("12")
    private val budgetId = AtomicReference(BUDGET)
    private val budgetPatches = AtomicInteger()
    private val budgetDeletes = AtomicInteger()
    private val budgetPosts = AtomicInteger()
    private val budgetPatchBody = AtomicReference("")
    private val budgetPostBody = AtomicReference("")
    private val connectionPatches = AtomicInteger()
    private val revokedAt = AtomicReference("")

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
        compose.onNodeWithText("Codice del partner").performTextInput("ABCD2345")
        compose.onNodeWithText("Invita partner").assertIsNotEnabled()
        assertEquals(2, mutations.get()) // One read-only lookup RPC and one request INSERT.
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

    @Test fun equalUsdSplitPreservesCurrencyAndShowsBothRoundedOddCentShares() {
        connected.set(true)
        transactionCurrency.set("USD")
        compose.setContent { SavelyTheme("light") { CoupleScreen(api) } }
        waitFor("Connessione attiva")
        compose.onNodeWithText("Scegli transazione").performScrollTo().performClick()
        compose.onNodeWithText("${LocalDate.now()} · 1.01 USD · Spesa nuova").performClick()
        val symbol = Currency.getInstance("USD").getSymbol(Locale.ITALY)
        compose.onNode(literalText("La tua quota: 0,51 $symbol · Quota partner: 0,51 $symbol")).performScrollTo().assertIsDisplayed()
        compose.onNode(literalText("Le quote uguali superano il totale di 0,01 $symbol per l’arrotondamento. Usa una quota personalizzata per mantenere il totale."))
            .performScrollTo().assertIsDisplayed()
        assertEquals(0, mutations.get())
        compose.onNodeWithText("Condividi spesa").performScrollTo().performClick()
        waitFor("3 condivisioni caricate, 3 mostrate.", substring = true)
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Scegli transazione") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val body = JSONObject(shareBody.get())
        assertEquals(setOf("id", "connection_id", "original_tx_id", "created_by", "couple_category_name", "split_mode", "partner_amount"), body.keys().asSequence().toSet())
        UUID.fromString(body.getString("id"))
        assertEquals(CONNECTION, body.getString("connection_id"))
        assertEquals(TX, body.getString("original_tx_id"))
        assertEquals(USER, body.getString("created_by"))
        assertEquals("equal", body.getString("split_mode"))
        assertTrue(body.isNull("partner_amount")); assertTrue(body.isNull("couple_category_name"))
        compose.onNodeWithText("Quota partner: 0.51 USD", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNode(literalText("Mediana mensile USD: 0,51 $symbol")).performScrollTo().assertIsDisplayed()
        assertEquals(1, sharePosts.get()); assertEquals(1, mutations.get())
        assertEquals(0, budgetPosts.get()); assertEquals(0, budgetPatches.get()); assertEquals(0, budgetDeletes.get())
    }

    @Test fun budgetEditRejectsZeroThenUpdatesAndDeleteRequiresConfirmationBeforeCreatingAgain() {
        connected.set(true)
        compose.setContent { SavelyTheme("light") { CoupleScreen(api) } }
        waitFor("Connessione attiva")
        compose.onNodeWithText("Modifica").performScrollTo().performClick()
        compose.onNodeWithText("Limite mensile EUR").performScrollTo().performTextClearance()
        compose.onNodeWithText("Limite mensile EUR").performTextInput("0")
        compose.onNodeWithText("Salva budget").performScrollTo().performClick()
        waitFor("Inserisci un importo positivo", substring = true)
        assertEquals(0, mutations.get())
        compose.onNodeWithText("Limite mensile EUR").performScrollTo().performTextClearance()
        compose.onNodeWithText("Limite mensile EUR").performTextInput("12,34")
        compose.onNodeWithText("Salva budget").performScrollTo().performClick()
        waitFor("12.34 EUR")
        waitForIdleRefresh()
        assertEquals("{\"amount\":12.34}", budgetPatchBody.get())
        compose.onNode(literalText("Budget totale: 12,34 €")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Elimina").performScrollTo().performClick()
        compose.onNodeWithText("Eliminare il budget?").assertIsDisplayed()
        compose.onNodeWithText("Annulla").performClick()
        assertEquals(0, budgetDeletes.get()); assertEquals(1, mutations.get())
        compose.onNodeWithText("Elimina").performScrollTo().performClick()
        compose.onNodeWithText("Conferma").performClick()
        compose.waitUntil(15_000) { budgetDeletes.get() == 1 && compose.onAllNodesWithText("12.34 EUR").fetchSemanticsNodes().isEmpty() }
        waitForIdleRefresh()
        compose.onNodeWithText("Modifica").assertDoesNotExist()
        compose.onNodeWithText("Scegli categoria comune").performScrollTo().performClick()
        compose.onNode(hasText("Casa") and hasAnyAncestor(isPopup()), useUnmergedTree = true).performClick()
        compose.onNodeWithText("Limite mensile EUR").performScrollTo().performTextInput("9,50")
        compose.onNodeWithText("Salva budget").performScrollTo().performClick()
        waitFor("9.50 EUR")
        waitForIdleRefresh()
        val body = JSONObject(budgetPostBody.get())
        assertEquals(setOf("id", "connection_id", "couple_category_name", "amount", "currency", "month", "year", "created_by"), body.keys().asSequence().toSet())
        UUID.fromString(body.getString("id"))
        assertEquals(CONNECTION, body.getString("connection_id")); assertEquals(USER, body.getString("created_by"))
        assertEquals("Casa", body.getString("couple_category_name")); assertEquals("9.50", body.getString("amount"))
        assertEquals("EUR", body.getString("currency")); assertEquals(1, body.getInt("month")); assertEquals(2000, body.getInt("year"))
        assertEquals(1, budgetPatches.get()); assertEquals(1, budgetDeletes.get()); assertEquals(1, budgetPosts.get())
        assertEquals(3, mutations.get()); assertEquals(0, sharePosts.get()); assertEquals(0, connectionPatches.get())
    }

    @Test fun creatorRemovalUsesOwnedDeleteAndPartnerRemovalUsesOnlyRpcPreservingOriginalTransaction() {
        connected.set(true)
        distinctShareOwners.set(true)
        compose.setContent { SavelyTheme("light") { CoupleScreen(api) } }
        waitFor("Connessione attiva")
        fun removalFor(description: String): SemanticsMatcher {
            val other = if (description == "Spesa del creatore") "Spesa del partner" else "Spesa del creatore"
            val rowTitle = hasText(description) or hasAnyDescendant(hasText(description))
            val otherRowTitle = hasText(other) or hasAnyDescendant(hasText(other))
            return hasText("Rimuovi") and hasAnyAncestor(rowTitle and !otherRowTitle)
        }
        compose.onNode(removalFor("Spesa del creatore"), useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Rimuovere la condivisione?").assertIsDisplayed()
        compose.onNodeWithText("La transazione originale rimane nel conto di chi l’ha creata.").assertExists()
        compose.onNodeWithText("Annulla").performClick()
        assertEquals(0, mutations.get())
        compose.onNode(removalFor("Spesa del creatore"), useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Conferma").performClick()
        waitFor("1 condivisioni caricate, 1 mostrate.", substring = true)
        waitForIdleRefresh()
        compose.onNodeWithText("Spesa del creatore").assertDoesNotExist()
        assertEquals(1, shareDeletes.get()); assertEquals(0, partnerRemovalRpcs.get())
        compose.onNode(removalFor("Spesa del partner"), useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Conferma").performClick()
        waitFor("0 condivisioni caricate, 0 mostrate.", substring = true)
        waitForIdleRefresh()
        compose.onNodeWithText("Spesa del partner").assertDoesNotExist()
        compose.onNodeWithText("Scegli transazione").performScrollTo().performClick()
        compose.onNodeWithText("2026-01-01 · 10.00 EUR · Spesa del creatore").assertIsEnabled().assertIsDisplayed()
        assertEquals(1, shareDeletes.get()); assertEquals(1, partnerRemovalRpcs.get()); assertEquals(2, mutations.get())
        assertEquals(0, sharePosts.get()); assertEquals(0, budgetPatches.get()); assertEquals(0, budgetDeletes.get()); assertEquals(0, connectionPatches.get())
    }

    @Test fun revocationRequiresConfirmationThenShowsMetadataWithoutReadingFormerPrivateDataAgain() {
        connected.set(true)
        compose.setContent { SavelyTheme("light") { CoupleScreen(api) } }
        waitFor("Connessione attiva")
        waitForIdleRefresh()
        val readsBeforeRevoke = privateReads.get()
        compose.onNodeWithText("Revoca connessione").performScrollTo().performClick()
        compose.onNodeWithText("Revocare la connessione?").assertIsDisplayed()
        compose.onNodeWithText("Annulla").performClick()
        assertEquals(0, mutations.get())
        compose.onNodeWithText("Revoca connessione").performScrollTo().performClick()
        compose.onNodeWithText("Conferma").performClick()
        waitFor("Connessione revocata il ", substring = true)
        waitForIdleRefresh()
        val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ITALIAN).withZone(ZoneId.systemDefault())
        compose.onNodeWithText("Connessione revocata il ${dateFormat.format(Instant.parse(revokedAt.get()))}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Creata il ${dateFormat.format(Instant.parse("2025-01-02T12:00:00Z"))}").assertExists()
        compose.onNodeWithText("Sola lettura").assertExists()
        compose.onNodeWithText("Connessione attiva").assertDoesNotExist()
        compose.onNodeWithText("Revoca connessione").assertDoesNotExist()
        compose.onNodeWithText("Condividi spesa").assertDoesNotExist()
        compose.onNodeWithText("Salva budget").assertDoesNotExist()
        compose.onNodeWithText("Spesa precedente").assertDoesNotExist()
        assertEquals(readsBeforeRevoke, privateReads.get())
        assertEquals(1, connectionPatches.get()); assertEquals(1, mutations.get())
        assertEquals(0, shareDeletes.get()); assertEquals(0, partnerRemovalRpcs.get()); assertEquals(0, budgetDeletes.get())
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

    private fun waitForIdleRefresh() = compose.waitUntil(15_000) {
        compose.onAllNodes(hasText("Aggiorna") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }

    private fun literalText(expected: String) = SemanticsMatcher("Literal normalized text: $expected") { node ->
        val key = androidx.compose.ui.semantics.SemanticsProperties.Text
        node.config.contains(key) && node.config[key].any { it.text.replace('\u00a0', ' ').replace('\u202f', ' ') == expected }
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
                "/rest/v1/couple_connections" -> if (request.method == "PATCH") {
                    val body = JSONObject(request.body.readUtf8())
                    val filters = url.queryParameterNames.associateWith { url.queryParameter(it) }
                    if (newOwner || body.keys().asSequence().toSet() != setOf("revoked_at") ||
                        filters != mapOf("id" to "eq.$CONNECTION", "or" to "(user_a.eq.$USER,user_b.eq.$USER)", "revoked_at" to "is.null")) return json("{}", 403)
                    Instant.parse(body.getString("revoked_at"))
                    revokedAt.set(body.getString("revoked_at")); connectionPatches.incrementAndGet()
                    connected.set(false); showArchive.set(true)
                    json("""[{"id":"$CONNECTION","user_a":"$USER","user_b":"$PARTNER","revoked_at":"${revokedAt.get()}"}]""")
                } else if (url.queryParameter("or") != "(user_a.eq.$reader,user_b.eq.$reader)") json("{}", 403)
                else if (url.queryParameter("revoked_at") == "not.is.null") {
                    archiveReads.incrementAndGet()
                    if (failArchive.get()) json("{}", 503)
                    else json(if (showArchive.get() && url.queryParameter("id") == null)
                        """[{"id":"$CONNECTION","user_a":"$USER","user_b":"$PARTNER","created_at":"2025-01-02T12:00:00Z","revoked_at":"${revokedAt.get().ifBlank { "2026-02-03T12:00:00Z" }}"}]""" else "[]")
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
                        val owner = if (newOwner) PARTNER else USER
                        val filters = url.queryParameterNames.associateWith { url.queryParameter(it) }
                        val duplicateCheck = mapOf("select" to "id", "sender_id" to "eq.$owner", "status" to "eq.pending", "limit" to "1")
                        val list = mapOf("select" to "*", "or" to "(sender_id.eq.$owner,receiver_id.eq.$owner)",
                            "status" to "eq.pending", "order" to "id.asc", "limit" to "500")
                        when (filters) {
                            duplicateCheck -> json(if (!newOwner && !invitationClosed.get() && invited.get()) """[{"id":"$REQUEST"}]""" else "[]")
                            list, list + ("id" to "gt.$REQUEST") -> {
                                if (invitationClosed.get() && !newOwner) requestReadsAfterPatch.incrementAndGet()
                                json(if (!newOwner && !invitationClosed.get() && (invited.get() || incomingInvitation.get()) && url.queryParameter("id") == null)
                                    """[{"id":"$REQUEST","sender_id":"${if (incomingInvitation.get()) PARTNER else USER}","receiver_id":"${if (incomingInvitation.get()) USER else PARTNER}","status":"pending"}]""" else "[]")
                            }
                            else -> json("{}", 403)
                        }
                    }
                    else -> json("{}", 405)
                }
                "/rest/v1/rpc/find_user_by_couple_code" -> {
                    lookups.incrementAndGet()
                    if (JSONObject(request.body.readUtf8()).getString("p_code") != "ABCD2345") json("null") else json("\"$PARTNER\"")
                }
                "/rest/v1/shared_expenses_view" -> {
                    privateReads.incrementAndGet()
                    if (request.method != "GET" || url.queryParameter("connection_id") != "eq.$CONNECTION") return json("{}", 403)
                    if (url.queryParameter("id") != null) json("[]") else {
                        val rows = JSONArray()
                        if (SHARE1 !in removedShares) rows.put(expense(SHARE1, OLD_TX, "2026-01-01", "10.00", "Casa").apply {
                            if (distinctShareOwners.get()) put("description", "Spesa del creatore")
                        })
                        if (SHARE2 !in removedShares) rows.put(expense(SHARE2, OLD_TX2, "2026-02-01", "20.00", "Casa").apply {
                            if (distinctShareOwners.get()) put("description", "Spesa del partner").put("created_by", PARTNER).put("currency", "USD")
                        })
                        if (shared.get()) rows.put(expense(SHARE3, TX, LocalDate.now().toString(), if (transactionCurrency.get() == "USD") "0.51" else "0.50", null)
                            .put("currency", transactionCurrency.get()))
                        json(rows.toString())
                    }
                }
                "/rest/v1/couple_budgets" -> when (request.method) {
                    "GET" -> {
                        privateReads.incrementAndGet()
                        if (url.queryParameter("connection_id") != "eq.$CONNECTION") json("{}", 403)
                        else json(if (budgetPresent.get() && url.queryParameter("id") == null)
                            """[{"id":"${budgetId.get()}","couple_category_name":"Casa","amount":"${budgetAmount.get()}","currency":"EUR"}]""" else "[]")
                    }
                    "PATCH", "DELETE" -> {
                        val filters = url.queryParameterNames.associateWith { url.queryParameter(it) }
                        if (newOwner || filters != mapOf("id" to "eq.$BUDGET", "connection_id" to "eq.$CONNECTION")) return json("{}", 403)
                        if (request.method == "PATCH") {
                            budgetPatchBody.set(request.body.readUtf8())
                            if (budgetPatchBody.get() != "{\"amount\":12.34}") return json("{}", 400)
                            budgetAmount.set("12.34"); budgetPatches.incrementAndGet()
                        } else { budgetPresent.set(false); budgetDeletes.incrementAndGet() }
                        json("""[{"id":"$BUDGET"}]""")
                    }
                    "POST" -> {
                        val body = JSONObject(request.body.readUtf8())
                        budgetPostBody.set(body.toString())
                        val id = UUID.fromString(body.getString("id")).toString()
                        if (newOwner || body.keys().asSequence().toSet() != setOf("id", "connection_id", "couple_category_name", "amount", "currency", "month", "year", "created_by") ||
                            body.optString("connection_id") != CONNECTION || body.optString("created_by") != USER || body.optString("couple_category_name") != "Casa" ||
                            body.optString("amount") != "9.50" || body.optString("currency") != "EUR" || body.optInt("month") != 1 || body.optInt("year") != 2000) return json("{}", 403)
                        budgetId.set(id); budgetAmount.set("9.50"); budgetPresent.set(true); budgetPosts.incrementAndGet()
                        json(JSONArray().put(body).toString())
                    }
                    else -> json("{}", 405)
                }
                "/rest/v1/transactions" -> {
                    if (request.method != "GET" || url.queryParameter("user_id") != "eq.$USER" || url.queryParameter("type") != "eq.expense" || url.queryParameter("deleted_at") != "is.null") return json("{}", 403)
                    if (url.queryParameter("id")?.startsWith("gt.") == true) json("[]")
                    else {
                        val rows = JSONArray()
                        if (distinctShareOwners.get()) rows.put(JSONObject().put("id", OLD_TX).put("amount", "10.00").put("currency", "EUR")
                            .put("date", "2026-01-01").put("description", "Spesa del creatore").put("user_id", USER).put("type", "expense"))
                        rows.put(JSONObject().put("id", TX).put("amount", java.math.BigDecimal("1.01")).put("currency", transactionCurrency.get())
                            .put("date", LocalDate.now().toString()).put("description", "Spesa nuova").put("user_id", USER).put("type", "expense"))
                        val selectedId = url.queryParameter("id")
                        if (selectedId == null) json(rows.toString())
                        else json(JSONArray().apply {
                            for (index in 0 until rows.length()) {
                                val row = rows.getJSONObject(index)
                                if (selectedId == "eq.${row.getString("id")}") put(row)
                            }
                        }.toString())
                    }
                }
                "/rest/v1/shared_expenses" -> if (request.method == "POST") {
                    shareBody.set(request.body.readUtf8())
                    val body = JSONObject(shareBody.get())
                    UUID.fromString(body.getString("id"))
                    if (newOwner || body.keys().asSequence().toSet() != setOf("id", "connection_id", "original_tx_id", "created_by", "couple_category_name", "split_mode", "partner_amount") ||
                        body.optString("connection_id") != CONNECTION || body.optString("original_tx_id") != TX || body.optString("created_by") != USER || !body.isNull("couple_category_name") ||
                        !(body.optString("split_mode") == "equal" && body.isNull("partner_amount") || body.optString("split_mode") == "custom" && body.optString("partner_amount") == "0.50")) return json("{}", 403)
                    sharePosts.incrementAndGet(); shared.set(true); json("""[{"id":"$SHARE3"}]""")
                } else if (request.method == "DELETE") {
                    if (newOwner || url.queryParameterNames.associateWith { url.queryParameter(it) } != mapOf("id" to "eq.$SHARE1", "created_by" to "eq.$USER")) return json("{}", 403)
                    shareDeletes.incrementAndGet(); removedShares.add(SHARE1); json("""[{"id":"$SHARE1"}]""")
                } else json(if (shared.get()) """[{"id":"$SHARE3"}]""" else "[]")
                "/rest/v1/rpc/delete_shared_expense_by_partner" -> {
                    if (newOwner || request.method != "POST" || url.query != null || request.body.readUtf8() != "{\"p_id\":\"$SHARE2\"}") return json("{}", 403)
                    partnerRemovalRpcs.incrementAndGet(); removedShares.add(SHARE2); json("null")
                }
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
