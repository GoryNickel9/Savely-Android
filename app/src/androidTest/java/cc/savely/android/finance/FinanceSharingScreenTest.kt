package cc.savely.android.finance

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.data.*
import cc.savely.android.ui.SavelyTheme
import java.time.LocalDate
import java.util.Currency
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Synthetic owner/partner data over Android TLS; rejected writes never escape this local server. */
@RunWith(AndroidJUnit4::class)
class FinanceSharingScreenTest {
    @get:Rule val compose = createComposeRule()
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val partner = "22222222-2222-4222-8222-222222222222"
    private val connection = "33333333-3333-4333-8333-333333333333"
    private val mine = "44444444-4444-4444-8444-444444444444"
    private val theirs = "55555555-5555-4555-8555-555555555555"
    private val sharedMine = "66666666-6666-4666-8666-666666666666"
    private val sharedTheirs = "77777777-7777-4777-8777-777777777777"
    private val privateTx = "88888888-8888-4888-8888-888888888888"
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private val posts = AtomicInteger()
    private val rpcs = AtomicInteger()
    private val deletes = AtomicInteger()
    private val rejected = AtomicInteger()
    private val partnerRemoved = AtomicBoolean(false)
    private val ownerRemoved = AtomicBoolean(false)
    private val failSharing = AtomicBoolean(false)
    private val inserted = AtomicReference<JSONObject?>(null)
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun active() = JSONObject().put("id", connection).put("user_a", owner).put("user_b", partner).put("revoked_at", JSONObject.NULL)
    private fun tx(id: String, description: String, amount: String) = JSONObject().put("id", id).put("user_id", owner).put("type", "expense")
        .put("amount", amount).put("currency", "EUR").put("exchange_rate_eur", "1").put("category_id", JSONObject.NULL)
        .put("description", description).put("date", LocalDate.now().toString()).put("deleted_at", JSONObject.NULL)
    private fun share(id: String, creator: String, tx: String, description: String, currency: String, mineAmount: String, partnerAmount: String, total: String) = JSONObject()
        .put("id", id).put("connection_id", connection).put("created_by", creator).put("original_tx_id", tx).put("couple_category_name", "Casa")
        .put("creator_share_amount", mineAmount).put("my_share_amount", partnerAmount).put("total_amount", total).put("currency", currency)
        .put("exchange_rate_eur", "1").put("description", description).put("date", LocalDate.now().toString()).put("tx_deleted_at", JSONObject.NULL)
    private fun ownShare() = share(sharedMine, owner, mine, "Cena condivisa", "EUR", "6.01", "4.00", "10.01")
    private fun partnerShare() = share(sharedTheirs, partner, theirs, "Spesa partner", "USD", "5.00", "2.01", "7.01")

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Finance sharing fixture").addSubjectAlternativeName("localhost").build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).retryOnConnectionFailure(false).build()
        server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    fun reject(): MockResponse { rejected.incrementAndGet(); return json("{}", 403) }
                    if (request.getHeader("Authorization") != "Bearer sharing-access" || request.getHeader("apikey") != "sharing-public") return reject()
                    when (url.encodedPath) {
                        "/rest/v1/categories" -> return if (request.method == "GET" && url.queryParameter("user_id") == "eq.$owner") json("[]") else reject()
                        "/rest/v1/profiles" -> return if (request.method == "GET" && url.queryParameter("user_id") == "eq.$owner") json("""[{"default_currency":"EUR","permissions":{"couple_expenses":true}}]""") else reject()
                        "/rest/v1/couple_connections" -> return if (request.method == "GET" && url.queryParameter("or") == "(user_a.eq.$owner,user_b.eq.$owner)" && url.queryParameter("revoked_at") == "is.null") json(JSONArray().put(active()).toString()) else reject()
                        "/rest/v1/transactions" -> {
                            if (request.method == "POST" && failSharing.get()) {
                                val body = JSONObject(request.body.readUtf8())
                                if (body.getString("user_id") != owner || body.getString("description") != "Nuova condivisa") return reject()
                                inserted.set(body); posts.incrementAndGet(); return json(JSONArray().put(body).toString())
                            }
                            if (request.method != "GET" || url.queryParameter("user_id") != "eq.$owner") return reject()
                            val id = url.queryParameter("id")?.removePrefix("eq.")
                            if (id != null) return if (id == inserted.get()?.getString("id")) json(JSONArray().put(inserted.get()).toString()) else reject()
                            if ((url.queryParameter("offset")?.toIntOrNull() ?: 0) > 0) return json("[]")
                            return json(JSONArray().put(tx(mine, "Cena condivisa", "10.01")).put(tx(privateTx, "Spesa privata", "12.50")).apply { inserted.get()?.let { put(it) } }.toString())
                        }
                        "/rest/v1/shared_expenses_view" -> {
                            if (request.method != "GET" || url.queryParameter("connection_id") != "eq.$connection") return reject()
                            if (url.queryParameter("id") == "eq.$sharedTheirs") return json(JSONArray().put(partnerShare()).toString())
                            if (url.queryParameter("id") == "eq.$sharedMine") return json(if (ownerRemoved.get()) "[]" else JSONArray().put(ownShare()).toString())
                            if ((url.queryParameter("offset")?.toIntOrNull() ?: 0) > 0) return json("[]")
                            return json(JSONArray().apply { if (!ownerRemoved.get()) put(ownShare()); if (!partnerRemoved.get()) put(partnerShare()) }.toString())
                        }
                        "/rest/v1/shared_expenses" -> {
                            if (request.method == "GET" && url.queryParameter("original_tx_id") == "eq.${inserted.get()?.getString("id")}" && url.queryParameter("connection_id") == "eq.$connection") return json("[]")
                            if (request.method == "POST" && failSharing.get()) {
                                val body = JSONObject(request.body.readUtf8())
                                if (body.getString("original_tx_id") != inserted.get()?.getString("id") || body.getString("created_by") != owner || body.getString("connection_id") != connection) return reject()
                                posts.incrementAndGet(); return json("{}", 403)
                            }
                            if (request.method == "DELETE") {
                                deletes.incrementAndGet()
                                if (url.queryParameterNames.associateWith { url.queryParameter(it) } != mapOf("id" to "eq.$sharedMine", "created_by" to "eq.$owner", "connection_id" to "eq.$connection")) return reject()
                                ownerRemoved.set(true)
                                return json("""[{"id":"$sharedMine"}]""")
                            }
                            return reject()
                        }
                        "/rest/v1/rpc/delete_shared_expense_by_partner" -> {
                            if (request.method != "POST") return reject()
                            val body = JSONObject(request.body.readUtf8())
                            if (body.length() != 1 || body.optString("p_id") != sharedTheirs) return reject()
                            rpcs.incrementAndGet(); partnerRemoved.set(true); return json("null")
                        }
                        else -> return reject()
                    }
                }
            }
            start()
        }
        val url = server.url("/").toString()
        api = SupabaseApi(BackendConfig(url, "sharing-public", url), MemorySessionStore(Session("sharing-access", "fixture", Long.MAX_VALUE, owner, "qa@example.test")), client)
    }
    @After fun teardown() { api.close(); server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun waitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun exactExpense(literalAmount: String, currency: String) = SemanticsMatcher("Exact expense −$literalAmount $currency") { node ->
        // Numeric expectation is literal and independent of formatMoney. Android supplies only the currency symbol.
        val expected = "−$literalAmount ${Currency.getInstance(currency).getSymbol(Locale.ITALY)}"
        node.config.contains(SemanticsProperties.Text) && node.config[SemanticsProperties.Text].any { text ->
            text.text.replace('\u00a0', ' ').replace('\u202f', ' ') == expected
        }
    }

    @Test fun sharesShowExactNativeCurrencyAmountsFilterAndRemovePartnerOnlyAfterConfirmation() {
        compose.setContent { SavelyTheme("light") { FinanceScreen(api, "transactions", Modifier.fillMaxSize(), accountKey = owner) } }
        waitText("Solo condivise"); list().performScrollToIndex(2); waitText("3 transazioni")
        val ownAmount = exactExpense("6,01", "EUR")
        list().performScrollToNode(ownAmount); compose.onNode(ownAmount).assertIsDisplayed()
        val ownQuota = hasText("Condivisa · Quota", substring = true) and hasText("6,01", substring = true) and hasText("10,01", substring = true)
        list().performScrollToNode(ownQuota); compose.onNode(ownQuota).assertIsDisplayed().assertTextContains("€", substring = true)
        val partnerAmount = exactExpense("2,01", "USD")
        list().performScrollToNode(partnerAmount); compose.onNode(partnerAmount).assertIsDisplayed()
        assertEquals(0, posts.get()); assertEquals(0, rpcs.get()); assertEquals(0, deletes.get())
        list().performScrollToNode(hasContentDescription("Solo condivise")); compose.onNodeWithContentDescription("Solo condivise").performClick()
        list().performScrollToIndex(2)
        waitText("2 transazioni")
        list().performScrollToNode(hasText("Cerca descrizione o categoria")); compose.onNodeWithText("Cerca descrizione o categoria").performTextInput("partner")
        list().performScrollToIndex(2)
        waitText("1 transazioni")
        list().performScrollToNode(hasContentDescription("Rimuovi condivisione Spesa partner"))
        compose.onNodeWithContentDescription("Modifica Spesa partner").assertDoesNotExist()
        compose.onNodeWithContentDescription("Elimina Spesa partner").assertDoesNotExist()
        compose.onNodeWithContentDescription("Rimuovi condivisione Spesa partner").performClick()
        compose.onNodeWithText("Rimuovere la condivisione?").assertIsDisplayed()
        compose.onNodeWithText("Annulla").performClick(); assertEquals(0, rpcs.get())
        compose.onNodeWithContentDescription("Rimuovi condivisione Spesa partner").performClick()
        compose.onNode(hasText("Rimuovi condivisione") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        compose.waitUntil(15_000) { rpcs.get() == 1 && compose.onAllNodesWithText("Rimuovere la condivisione?").fetchSemanticsNodes().isEmpty() }
        list().performScrollToIndex(2); waitText("0 transazioni")
        assertEquals(0, posts.get()); assertEquals(0, deletes.get()); assertEquals(0, rejected.get())
    }

    @Test fun creatorShareRemovalRequiresConfirmationAndKeepsFullOriginalAmountWithoutDeletingTransaction() {
        compose.setContent { SavelyTheme("light") { FinanceScreen(api, "transactions", Modifier.fillMaxSize(), accountKey = owner) } }
        waitText("Solo condivise")
        list().performScrollToIndex(2); waitText("3 transazioni")
        list().performScrollToNode(hasContentDescription("Rimuovi condivisione Cena condivisa"))
        compose.onNodeWithContentDescription("Rimuovi condivisione Cena condivisa").performClick()
        compose.onNodeWithText("Rimuovere la condivisione?").assertIsDisplayed()
        compose.onNodeWithText("Annulla").performClick()
        assertEquals(0, deletes.get()); assertEquals(0, rpcs.get()); assertEquals(0, posts.get())
        compose.onNodeWithContentDescription("Rimuovi condivisione Cena condivisa").performClick()
        compose.onNode(hasText("Rimuovi condivisione") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        compose.waitUntil(15_000) {
            deletes.get() == 1 && compose.onAllNodesWithText("Rimuovere la condivisione?").fetchSemanticsNodes().isEmpty()
        }
        list().performScrollToIndex(0)
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("Nuova transazione") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        list().performScrollToIndex(2); waitText("3 transazioni")
        val original = exactExpense("10,01", "EUR")
        compose.waitUntil(15_000) {
            val found = try {
                list().performScrollToNode(original)
                true
            } catch (failure: AssertionError) {
                // The unchanged count and enabled Add button can precede the asynchronous share reload.
                val expected = "No node found that matches ${original.description} in scrollable container"
                if (failure.message?.substringBefore('\n') != expected) throw failure
                false
            }
            found && compose.onAllNodes(original).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNode(original).assertIsDisplayed()
        compose.onNodeWithText("Cena condivisa").assertExists()
        compose.onNodeWithContentDescription("Modifica Cena condivisa").assertExists()
        compose.onNodeWithContentDescription("Elimina Cena condivisa").assertExists()
        compose.onNodeWithContentDescription("Rimuovi condivisione Cena condivisa").assertDoesNotExist()
        val partnerAmount = exactExpense("2,01", "USD")
        list().performScrollToNode(partnerAmount); compose.onNode(partnerAmount).assertIsDisplayed()
        assertEquals(1, deletes.get()); assertEquals(0, rpcs.get()); assertEquals(0, posts.get()); assertEquals(0, rejected.get())
    }
    @Test fun sharedCreateFailureClosesEditorAndBlocksWritesUntilRefreshWithoutDuplicatePersonalInsert() {
        failSharing.set(true)
        compose.setContent { SavelyTheme("light") { FinanceScreen(api, "transactions", Modifier.fillMaxSize(), accountKey = owner) } }
        waitText("Solo condivise"); list().performScrollToIndex(2); waitText("3 transazioni"); list().performScrollToIndex(0)
        compose.onNodeWithText("Nuova transazione").performClick()
        compose.onNodeWithText("Importo").performScrollTo().performTextInput("10,01")
        compose.onNodeWithText("Descrizione facoltativa").performScrollTo().performTextInput("Nuova condivisa")
        compose.onNodeWithContentDescription("Condividi con il partner").performScrollTo().performClick()
        compose.onNodeWithText("Salva").performClick()
        compose.waitUntil(15_000) { posts.get() == 2 && compose.onAllNodesWithText("Nuovo elemento").fetchSemanticsNodes().isEmpty() }
        list().performScrollToIndex(0)
        waitText("Spesa salvata, ma condivisione non confermata. Aggiorna i dati e verifica la transazione prima di riprovare.")
        compose.onNodeWithText("Nuova transazione").assertIsNotEnabled()
        assertEquals("10.01", inserted.get()!!.getString("amount")); assertEquals(owner, inserted.get()!!.getString("user_id"))
        assertEquals(2, posts.get()); assertEquals(0, deletes.get()); assertEquals(0, rpcs.get()); assertEquals(0, rejected.get())
    }
}
