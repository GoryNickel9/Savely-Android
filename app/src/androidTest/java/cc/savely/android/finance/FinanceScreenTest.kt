package cc.savely.android.finance

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.ui.SavelyTheme
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Compose, Android TLS and financial CRUD against synthetic in-memory records only. */
@RunWith(AndroidJUnit4::class)
class FinanceScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: SupabaseApi
    private val posts = AtomicInteger()
    private val patches = AtomicInteger()
    private val deletes = AtomicInteger()
    private val rejectedRequests = AtomicInteger()
    private val postedBody = AtomicReference("")
    private val patchedBody = AtomicReference("")
    private val recurringFixture = AtomicBoolean(false)
    private val processingFixture = AtomicBoolean(false)
    private val failRecurringAdvance = AtomicBoolean(false)
    private val dueQueries = AtomicInteger()
    private val advanceRequests = AtomicInteger()

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("Savely finance UI fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            dispatcher = fixtureDispatcher()
            start()
        }
        val url = server.url("/").toString()
        val store = MemorySessionStore(Session("finance-device-access", "finance-device-refresh",
            System.currentTimeMillis() / 1000 + 3600, USER_ID, "finance-ui@example.test"))
        api = SupabaseApi(BackendConfig(url, "public-test-key", url), store, client)
    }

    @After fun teardown() {
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun transactionsLoadSearchCategoryValidateCreateEditAndConfirmDelete() {
        compose.setContent {
            SavelyTheme("light") {
                FinanceScreen(api, "transactions", Modifier.fillMaxSize(), accountKey = USER_ID)
            }
        }
        waitForText("2 transazioni")
        transactionList().performScrollToNode(hasText("Pranzo sintetico"))
        compose.onNodeWithText("Pranzo sintetico").assertIsDisplayed()
        compose.onNodeWithText("12,50", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Alimentari", substring = true).assertIsDisplayed()

        transactionList().performScrollToIndex(0)
        compose.onNodeWithText("Cerca descrizione o categoria").performTextInput("alimentari")
        waitForText("1 transazioni")
        transactionList().performScrollToNode(hasText("Pranzo sintetico"))
        compose.onNodeWithText("Pranzo sintetico").assertIsDisplayed()
        compose.onNodeWithText("Biglietto treno").assertDoesNotExist()

        transactionList().performScrollToIndex(0)
        compose.onNodeWithText("Cerca descrizione o categoria").performTextClearance()
        waitForText("2 transazioni")
        compose.onNodeWithText("Nuova transazione").performClick()
        compose.onNodeWithText("Nuovo elemento").assertIsDisplayed()
        compose.onNodeWithText("Salva").performClick()
        compose.onNodeWithText("Inserisci un importo numerico").performScrollTo().assertIsDisplayed()
        assertEquals("Invalid input must not issue a POST", 0, posts.get())
        compose.onNodeWithText("Importo").performScrollTo().performTextInput("8,40")
        compose.onNodeWithText("Descrizione facoltativa").performScrollTo().performTextInput("Cena sintetica")
        compose.onNodeWithText("Salva").performClick()
        waitForText("3 transazioni")
        assertEquals(1, posts.get())
        val created = JSONObject(postedBody.get())
        assertEquals(USER_ID, created.getString("user_id"))
        assertEquals("8.40", created.getString("amount"))
        assertEquals(LocalDate.now().toString(), created.getString("date"))

        transactionList().performScrollToNode(hasContentDescription("Modifica Pranzo sintetico"))
        compose.onNodeWithContentDescription("Modifica Pranzo sintetico").performClick()
        compose.onNodeWithText("Importo").performTextReplacement("13,75")
        compose.onNodeWithText("Salva").performClick()
        compose.waitUntil(15_000) {
            patches.get() == 1 && compose.onAllNodesWithText("Modifica elemento").fetchSemanticsNodes().isEmpty()
        }
        transactionList().performScrollToNode(hasText("Pranzo sintetico"))
        compose.waitUntil(15_000) { compose.onAllNodesWithText("13,75", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("13,75", substring = true).assertIsDisplayed()
        assertEquals("13.75", JSONObject(patchedBody.get()).getString("amount"))

        transactionList().performScrollToNode(hasContentDescription("Elimina Cena sintetica"))
        compose.onNodeWithContentDescription("Elimina Cena sintetica").performClick()
        compose.onNodeWithText("Eliminare Cena sintetica?").assertIsDisplayed()
        assertEquals("Opening confirmation must not delete", 0, deletes.get())
        compose.onNodeWithText("Annulla").performClick()
        assertEquals("Cancel must preserve the record", 0, deletes.get())
        compose.onNodeWithContentDescription("Elimina Cena sintetica").performClick()
        compose.onNodeWithText("Elimina", substring = false).performClick()
        compose.waitUntil(15_000) {
            deletes.get() == 1 && compose.onAllNodesWithText("Eliminare Cena sintetica?").fetchSemanticsNodes().isEmpty()
        }
        transactionList().performScrollToIndex(0)
        waitForText("2 transazioni")
        assertEquals(1, deletes.get())
        assertEquals("Every API call must use this synthetic owner's credentials and filters", 0, rejectedRequests.get())
    }

    @Test fun recurringSuggestionPrefillsCancelPreservesAndSaveCreatesOnlyOnce() {
        recurringFixture.set(true)
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "recurring", Modifier.fillMaxSize(), accountKey = USER_ID) }
        }
        waitForText("Suggerimenti dalla cronologia")
        transactionList().performScrollToNode(hasText("Crea ricorrenza"))
        compose.onNodeWithText("Crea ricorrenza").performClick()
        compose.onNodeWithText("Nome").assertTextContains("Abbonamento sintetico")
        compose.onNodeWithText("Importo").assertTextContains("12.50")
        compose.onNodeWithText("Frequenza: Mensile").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Prossima scadenza: ${LocalDate.now().plusDays(30)}").performScrollTo().assertIsDisplayed()
        assertEquals("Opening a suggestion must not write", 0, posts.get())
        compose.onNodeWithText("Annulla").performClick()
        compose.onNodeWithText("Crea ricorrenza").assertExists().performClick()
        assertEquals("Cancel must retain the suggestion without writing", 0, posts.get())
        compose.onNodeWithText("Salva").performClick()
        compose.waitUntil(15_000) {
            posts.get() == 1 && compose.onAllNodesWithText("Nuovo elemento").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Suggerimenti dalla cronologia").fetchSemanticsNodes().isEmpty()
        }
        transactionList().performScrollToIndex(0)
        transactionList().performScrollToNode(hasContentDescription("Modifica Abbonamento sintetico"))
        compose.onNodeWithContentDescription("Modifica Abbonamento sintetico").assertIsDisplayed()
        val body = JSONObject(postedBody.get())
        assertEquals(USER_ID, body.getString("user_id"))
        assertEquals("12.50", body.getString("amount"))
        assertEquals("monthly", body.getString("frequency"))
        assertEquals(LocalDate.now().plusDays(30).toString(), body.getString("next_due_date"))
        assertEquals("EUR", body.getString("currency"))
        assertEquals(0, rejectedRequests.get())
    }

    @Test fun portfolioIncludesPricedEurTcgInIlliquidValueAndExplainsExclusions() {
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "portfolio", Modifier.fillMaxSize(), accountKey = USER_ID) }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Portafoglio ", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Portafoglio ", substring = true).assertTextContains("25,00", substring = true)
        compose.onNodeWithText("Valore illiquido", substring = true).assertTextContains("25,00", substring = true)
        compose.onNodeWithText("Valore liquido", substring = true).assertTextContains("0,00", substring = true)
        transactionList().performScrollToNode(hasText("Magic: The Gathering · 2 carte"))
        compose.onNodeWithText("Magic: The Gathering · 2 carte").assertIsDisplayed()
        transactionList().performScrollToNode(hasText("voce TCG esclusa", substring = true))
        compose.onNodeWithText("1 voce TCG esclusa", substring = true).assertIsDisplayed()
        transactionList().performScrollToNode(hasText("voce TCG in valuta diversa", substring = true))
        compose.onNodeWithText("1 voce TCG in valuta diversa", substring = true).assertIsDisplayed()
        assertEquals("Reading valuation must not create or modify records", 0, posts.get() + patches.get() + deletes.get())
        assertEquals(0, rejectedRequests.get())
    }

    @Test fun recurringTogglePauseAndProcessPreventDuplicateSubmit() {
        processingFixture.set(true)
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "recurring", Modifier.fillMaxSize(), accountKey = USER_ID) }
        }
        waitForText("Elabora ora")
        recurringTouch(hasContentDescription("Attiva o metti in pausa $PROCESS_NAME"), expectedOn = true)
        waitForText("$PROCESS_NAME: in pausa")
        compose.onNodeWithContentDescription("Attiva o metti in pausa $PROCESS_NAME").assertIsOff()
        transactionList().performScrollToIndex(0)
        recurringTouch(hasText("Elabora ora"))
        waitForFinanceCondition("paused processing due query") { dueQueries.get() == 1 }
        transactionList().performScrollToIndex(0)
        waitForText(RecurringProcessingResult().message)
        assertEquals("Paused recurring must not create a transaction", 0, posts.get())
        recurringTouch(hasContentDescription("Attiva o metti in pausa $PROCESS_NAME"))
        waitForText("$PROCESS_NAME: attiva")
        transactionList().performScrollToIndex(0)
        recurringTouch(hasText("Elabora ora"))
        waitForText("Operazione in corso…")
        compose.onNodeWithText("Operazione in corso…").assertIsNotEnabled().performTouchInput { click(); click() }
        waitForFinanceCondition("one processing insert and advance after pause/resume") { posts.get() == 1 && patches.get() == 3 }
        transactionList().performScrollToIndex(0)
        waitForText(RecurringProcessingResult(1, 0, 1).message)
        assertEquals(1, posts.get())
        assertEquals("Pause, resume and advance must each issue one PATCH", 3, patches.get())
        assertEquals("Two intentional processing actions, no extra action from the disabled button", 2, dueQueries.get())
        val body = JSONObject(postedBody.get())
        assertEquals(USER_ID, body.getString("user_id"))
        assertEquals(LocalDate.now().withDayOfMonth(1).toString(), body.getString("date"))
        assertEquals("12.50", body.getString("amount"))
        assertEquals(LocalDate.now().withDayOfMonth(1).plusMonths(1).toString(), JSONObject(patchedBody.get()).getString("next_due_date"))
        assertEquals(0, rejectedRequests.get())
    }

    @Test fun recurringInsertCommitThenAdvanceFailureShowsPartialErrorWithoutAutomaticRetry() {
        processingFixture.set(true)
        failRecurringAdvance.set(true)
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "recurring", Modifier.fillMaxSize(), accountKey = USER_ID) }
        }
        waitForText("Elabora ora")
        recurringTouch(hasText("Elabora ora"))
        waitForFinanceCondition("first insert and failed advance") { posts.get() == 1 && advanceRequests.get() == 1 }
        transactionList().performScrollToIndex(0)
        waitForFinanceCondition("partial failure in composed header") {
            compose.onAllNodesWithText("Elaborazione interrotta", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        transactionList().performScrollToNode(hasText("Elaborazione interrotta", substring = true))
        compose.onNodeWithText("Elaborazione interrotta", substring = true)
            .assertTextContains("1 transazioni create", substring = true)
            .assertTextContains("0 scadenze aggiornate", substring = true)
            .assertTextContains("verifica i movimenti", substring = true)
        assertEquals(1, posts.get())
        assertEquals(0, patches.get())
        assertEquals(1, dueQueries.get())
        failRecurringAdvance.set(false)
        recurringTouch(hasText("Riprova"))
        waitForFinanceCondition("refresh removes partial error") { compose.onAllNodesWithText("Elaborazione interrotta", substring = true).fetchSemanticsNodes().isEmpty() }
        assertEquals("Retry card refreshes only, without repeating writes", 1, posts.get())
        assertEquals(1, dueQueries.get())
        transactionList().performScrollToIndex(0)
        recurringTouch(hasText("Elabora ora"))
        waitForFinanceCondition("explicit rerun advances existing occurrence") { patches.get() == 1 }
        transactionList().performScrollToIndex(0)
        waitForText(RecurringProcessingResult(0, 1, 1).message)
        assertEquals("Explicit rerun must find the committed transaction", 1, posts.get())
        assertEquals(1, patches.get())
        assertEquals(0, rejectedRequests.get())
    }

    private fun transactionList() = compose.onNode(hasScrollToIndexAction())
    private fun waitForText(text: String) = waitForFinanceCondition("text: $text") {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /** Wait for the asynchronous lazy scroll and refresh to finish before a real pointer gesture. */
    private fun recurringTouch(matcher: SemanticsMatcher, expectedOn: Boolean? = null) {
        transactionList().performScrollToNode(matcher)
        compose.waitForIdle()
        var lastBounds: Rect? = null
        var stableSamples = 0
        waitForFinanceCondition("enabled stable touch target: $matcher") {
            val node = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
            val bounds = node?.boundsInRoot
            val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val ready = node != null && !node.config.contains(SemanticsProperties.Disabled) && bounds != null &&
                bounds.width > 0 && bounds.height > 0 && bounds.left >= rootBounds.left &&
                bounds.top >= rootBounds.top && bounds.right <= rootBounds.right && bounds.bottom <= rootBounds.bottom
            stableSamples = if (ready && bounds == lastBounds) stableSamples + 1 else 0
            lastBounds = bounds
            stableSamples >= 3
        }
        compose.waitForIdle()
        val target = compose.onNode(matcher).assertIsEnabled().assertIsDisplayed()
        if (expectedOn == true) target.assertIsOn()
        if (expectedOn == false) target.assertIsOff()
        target.performTouchInput { click() }
    }

    private fun waitForFinanceCondition(label: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(15_000, condition)
        } catch (failure: ComposeTimeoutException) {
            val counters = "POST=${posts.get()} PATCH=${patches.get()} advanceRequests=${advanceRequests.get()} " +
                "due=${dueQueries.get()} rejected=${rejectedRequests.get()} requests=${server.requestCount}"
            val semantics = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }
                .getOrElse { "Semantics unavailable: ${it.message}" }
            throw AssertionError("Finance fixture timeout: $label\n$counters\n$semantics", failure)
        }
    }

    private fun fixtureDispatcher(): Dispatcher {
        val categories = listOf(
            JSONObject().put("id", FOOD_ID).put("user_id", USER_ID).put("name", "Alimentari").put("type", "expense"),
            JSONObject().put("id", TRANSPORT_ID).put("user_id", USER_ID).put("name", "Trasporti").put("type", "expense"),
        )
        val transactions = linkedMapOf(
            LUNCH_ID to transaction(LUNCH_ID, "12.50", "Pranzo sintetico", FOOD_ID),
            TRAIN_ID to transaction(TRAIN_ID, "30.00", "Biglietto treno", TRANSPORT_ID),
        )
        val recurring = linkedMapOf<String, JSONObject>()
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return reject()
                if (request.getHeader("Authorization") != "Bearer finance-device-access" ||
                    request.getHeader("apikey") != "public-test-key") return reject()
                if (request.method != "POST" && url.queryParameter("user_id") != "eq.$USER_ID") return reject()
                return when (url.encodedPath) {
                    "/rest/v1/categories" -> {
                        val id = url.queryParameter("id")?.removePrefix("eq.")
                        json(JSONArray(categories.filter { id == null || it.getString("id") == id }).toString())
                    }
                    "/rest/v1/profiles" -> json("""[{"default_currency":"EUR"}]""")
                    "/rest/v1/portfolio_assets", "/rest/v1/manual_price_updates" -> json("[]")
                    "/rest/v1/tgc_cards" -> json("""[
                        {"id":"priced","category":"magic","quantity":2,"purchase_price":"10.00","current_price":"12.50","currency":"EUR"},
                        {"id":"unpriced","category":"pokemon","quantity":1,"purchase_price":"100.00","current_price":null,"currency":null},
                        {"id":"foreign","category":"yugioh","quantity":1,"purchase_price":"50.00","current_price":"80.00","currency":"USD"}
                    ]""")
                    "/rest/v1/recurring_expenses" -> when (request.method) {
                        "GET" -> {
                            if (processingFixture.get() && recurring.isEmpty()) recurring[PROCESS_ID] = JSONObject()
                                .put("id", PROCESS_ID).put("name", PROCESS_NAME).put("amount", "12.50").put("currency", "EUR")
                                .put("category_id", JSONObject.NULL).put("frequency", "monthly").put("week_interval", 1)
                                .put("next_due_date", LocalDate.now().withDayOfMonth(1).toString()).put("is_active", true)
                            val due = url.queryParameter("next_due_date")?.removePrefix("lte.")
                            val active = url.queryParameter("is_active") == "eq.true"
                            if (due != null) dueQueries.incrementAndGet()
                            val rows = recurring.values.filter { (!active || it.getBoolean("is_active")) &&
                                (due == null || it.getString("next_due_date") <= due) }
                            json(JSONArray(rows).toString()).apply { if (due != null) setBodyDelay(1200, TimeUnit.MILLISECONDS) }
                        }
                        "POST" -> {
                            val raw = request.body.readUtf8()
                            val body = JSONObject(raw)
                            if (body.getString("user_id") != USER_ID) return reject()
                            postedBody.set(raw)
                            posts.incrementAndGet()
                            recurring[CREATED_ID] = body.put("id", CREATED_ID)
                            json(JSONArray().put(body).toString())
                        }
                        "PATCH" -> {
                            val id = url.queryParameter("id")?.removePrefix("eq.") ?: return reject()
                            val record = recurring[id] ?: return reject()
                            val raw = request.body.readUtf8()
                            val body = JSONObject(raw)
                            if (body.has("next_due_date")) advanceRequests.incrementAndGet()
                            if (body.has("next_due_date") && failRecurringAdvance.get()) return MockResponse().setResponseCode(403).setBody("{}")
                            if (url.queryParameter("next_due_date")?.removePrefix("eq.")?.let { it != record.getString("next_due_date") } == true)
                                return json("[]")
                            body.keys().forEach { record.put(it, body.get(it)) }
                            patchedBody.set(raw)
                            patches.incrementAndGet()
                            json(JSONArray().put(record).toString())
                        }
                        else -> reject()
                    }
                    "/rest/v1/transactions" -> when (request.method) {
                        "GET" -> if (url.queryParameter("description") != null) {
                            val name = url.queryParameter("description")!!.removePrefix("eq.")
                            val dates = url.queryParameterValues("date")
                            val start = dates.first { it!!.startsWith("gte.") }!!.removePrefix("gte.")
                            val end = dates.first { it!!.startsWith("lt.") }!!.removePrefix("lt.")
                            json(JSONArray(transactions.values.filter { it.getString("description") == name &&
                                it.getString("date") >= start && it.getString("date") < end }).toString())
                        } else if (recurringFixture.get()) json(JSONArray((0 until 3).map { index ->
                            transaction("recurring-fixture-$index", "12.50", "Abbonamento sintetico", FOOD_ID)
                                .put("date", LocalDate.now().minusDays((2L - index) * 30).toString())
                        }).toString()) else json(JSONArray(transactions.values.toList()).toString())
                        "POST" -> {
                            val raw = request.body.readUtf8()
                            val body = JSONObject(raw)
                            if (body.getString("user_id") != USER_ID) return reject()
                            postedBody.set(raw)
                            posts.incrementAndGet()
                            transactions[CREATED_ID] = body.put("id", CREATED_ID).put("deleted_at", JSONObject.NULL)
                            json(JSONArray().put(body).toString())
                        }
                        "PATCH" -> {
                            if (url.queryParameter("id") != "eq.$LUNCH_ID") return reject()
                            val record = transactions[LUNCH_ID] ?: return reject()
                            val raw = request.body.readUtf8()
                            val body = JSONObject(raw)
                            body.keys().forEach { record.put(it, body.get(it)) }
                            patchedBody.set(raw)
                            patches.incrementAndGet()
                            json(JSONArray().put(record).toString())
                        }
                        "DELETE" -> {
                            if (url.queryParameter("id") != "eq.$CREATED_ID") return reject()
                            val record = transactions.remove(CREATED_ID) ?: return reject()
                            deletes.incrementAndGet()
                            json(JSONArray().put(record).toString())
                        }
                        else -> reject()
                    }
                    else -> reject()
                }
            }
        }
    }

    private fun transaction(id: String, amount: String, description: String, categoryId: String) = JSONObject()
        .put("id", id).put("user_id", USER_ID).put("type", "expense").put("amount", amount)
        .put("currency", "EUR").put("date", LocalDate.now().toString()).put("description", description)
        .put("category_id", categoryId).put("exchange_rate_eur", "1").put("deleted_at", JSONObject.NULL)
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun reject(): MockResponse { rejectedRequests.incrementAndGet(); return MockResponse().setResponseCode(403).setBody("{}") }

    private companion object {
        const val USER_ID = "10000000-0000-4000-8000-000000000001"
        const val FOOD_ID = "10000000-0000-4000-8000-000000000002"
        const val TRANSPORT_ID = "10000000-0000-4000-8000-000000000003"
        const val LUNCH_ID = "10000000-0000-4000-8000-000000000004"
        const val TRAIN_ID = "10000000-0000-4000-8000-000000000005"
        const val CREATED_ID = "10000000-0000-4000-8000-000000000006"
        const val PROCESS_ID = "10000000-0000-4000-8000-000000000007"
        const val PROCESS_NAME = "Ricorrenza sintetica da elaborare"
    }
}
