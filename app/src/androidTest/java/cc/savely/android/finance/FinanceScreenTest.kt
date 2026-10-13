package cc.savely.android.finance

import android.content.Context
import android.view.View
import android.widget.DatePicker
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Root
import androidx.test.espresso.NoMatchingRootException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.ui.SavelyTheme
import java.time.LocalDate
import java.util.UUID
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
import org.junit.Assert.assertTrue
import org.hamcrest.Matcher
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
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
    private val budgetFixture = AtomicBoolean(false)
    private val dashboardComparisonFixture = AtomicBoolean(false)
    private val calendarFixture = AtomicBoolean(false)
    private val budgetRoundingFixture = AtomicBoolean(false)
    private val insightsFixture = AtomicBoolean(false)
    private val anomalyInsightsFixture = AtomicBoolean(false)
    private val trendInsightsFixture = AtomicBoolean(false)
    private val milestoneInsightsFixture = AtomicBoolean(false)
    private val failSnapshotRead = AtomicBoolean(false)
    private val snapshotReadRequests = AtomicInteger()
    private val recurringInsightsFixture = AtomicBoolean(false)
    private val recurringPriceInsightsFixture = AtomicBoolean(false)
    private val recurringReadRequests = AtomicInteger()
    private val processingFixture = AtomicBoolean(false)
    private val failRecurringAdvance = AtomicBoolean(false)
    private val dueQueries = AtomicInteger()
    private val advanceRequests = AtomicInteger()
    private val positionClosureFixture = AtomicBoolean(false)
    private val failPositionRefresh = AtomicBoolean(false)
    private val positionRefreshRequests = AtomicInteger()
    private val transactionFilterFixture = AtomicBoolean(false)
    private val failTransactionInsert = AtomicBoolean(false)
    private val failTransactionReload = AtomicBoolean(false)
    private val transactionReloadFailures = AtomicInteger()

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

    @Test fun transactionsCombineCategorySearchTypeAndInclusiveDatesAndRejectInvalidPeriodsWithoutWrites() {
        transactionFilterFixture.set(true)
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "transactions", Modifier.fillMaxSize(), accountKey = USER_ID,
                today = LocalDate.of(2026, 6, 15)) }
        }
        waitForText("4 transazioni")
        scrollFinanceNode(hasText("4 transazioni"))
        scrollFinanceNode(hasText("Cerca descrizione o categoria")).performTextInput("ALIMENTARI")
        scrollFinanceNode(hasText("3 transazioni"))
        scrollFinanceNode(hasText("Uscite", substring = false)).performClick()
        scrollFinanceNode(hasText("2 transazioni"))
        scrollFinanceNode(hasText("Categoria: Tutte")).performClick()
        compose.onNodeWithText("Trasporti", substring = false).performClick()
        scrollFinanceNode(hasText("0 transazioni"))
        scrollFinanceNode(hasText("Nessuna transazione corrisponde ai filtri"))
        scrollFinanceNode(hasText("Categoria: Trasporti")).performClick()
        compose.onNodeWithText("Alimentari", substring = false).performClick()
        scrollFinanceNode(hasText("2 transazioni"))
        scrollFinanceNode(hasText("Pranzo sintetico"))
        scrollFinanceNode(hasText("−12,50", substring = true))
        scrollFinanceNode(hasText("Spesa USD sintetica"))
        scrollFinanceNode(hasText("−8,50", substring = true))

        scrollFinanceNode(hasText("Periodo: Questo mese")).performClick()
        compose.onNodeWithText("Personalizzato", substring = false).performClick()
        selectTransactionDate("Da", LocalDate.of(2026, 6, 15))
        selectTransactionDate("A", LocalDate.of(2026, 6, 1))
        scrollFinanceNode(hasText("Periodo non valido: controlla le date e l’anno."))
        compose.onNodeWithText("2 transazioni").assertDoesNotExist()
        assertEquals(0, posts.get() + patches.get() + deletes.get())
        selectTransactionDate("A", LocalDate.of(2026, 6, 15))
        scrollFinanceNode(hasText("1 transazioni"))
        scrollFinanceNode(hasText("Pranzo sintetico"))
        compose.onNodeWithText("Spesa USD sintetica").assertDoesNotExist()
        scrollFinanceNode(hasText("Entrate", substring = false)).performClick()
        scrollFinanceNode(hasText("0 transazioni"))
        scrollFinanceNode(hasText("Categoria: Alimentari")).performClick()
        compose.onNodeWithText("Tutte", substring = false).performClick()
        scrollFinanceNode(hasText("1 transazioni"))
        scrollFinanceNode(hasText("Entrata sintetica"))
        scrollFinanceNode(hasText("+100,00", substring = true))

        scrollFinanceNode(hasText("Periodo: Personalizzato")).performClick()
        compose.onNodeWithText("Anno", substring = false).performClick()
        scrollFinanceNode(hasText("Anno", substring = false) and hasSetTextAction()).performTextReplacement("invalid")
        scrollFinanceNode(hasText("Periodo non valido: controlla le date e l’anno."))
        scrollFinanceNode(hasText("Anno", substring = false) and hasSetTextAction()).performTextReplacement("2026")
        scrollFinanceNode(hasText("1 transazioni"))
        scrollFinanceNode(hasText("Periodo: Anno")).performClick()
        compose.onNodeWithText("Tutte", substring = false).performClick()
        scrollFinanceNode(hasText("Tutti i tipi", substring = false)).performClick()
        scrollFinanceNode(hasText("Cerca descrizione o categoria")).performTextClearance()
        scrollFinanceNode(hasText("5 transazioni"))
        scrollFinanceNode(hasText("Spesa precedente sintetica"))
        assertEquals("Filters are local: no synthetic record may be mutated", 0, posts.get() + patches.get() + deletes.get())
        assertEquals(0, rejectedRequests.get())
    }

    @Test fun transactionManualFxValidationMakesNoHttpAndCorrectedSavePreservesNativeUuidAndPrecision() {
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "transactions", Modifier.fillMaxSize(), accountKey = USER_ID) }
        }
        waitForText("2 transazioni")
        transactionList().performScrollToIndex(0)
        compose.onNodeWithText("Nuova transazione").performClick()
        val loadedRequestCount = server.requestCount
        compose.onNodeWithText("Importo").performScrollTo().performTextInput("0")
        compose.onNodeWithText("Salva").performClick()
        compose.onNodeWithText("Inserisci un importo maggiore di zero").performScrollTo().assertIsDisplayed()
        assertEquals(loadedRequestCount, server.requestCount)
        compose.onNodeWithText("Importo").performScrollTo().performTextReplacement("0,015")
        compose.onNodeWithText("Valuta: EUR").performScrollTo().performClick()
        compose.onNodeWithText("USD", substring = false).performClick()
        compose.onNodeWithContentDescription("Cambio automatico al salvataggio").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithText("Valore di 1 USD in EUR").performScrollTo().performTextInput("0")
        compose.onNodeWithText("Salva").performClick()
        compose.onNodeWithText("Cambio EUR fuori intervallo").performScrollTo().assertIsDisplayed()
        assertEquals(loadedRequestCount, server.requestCount)
        assertEquals(0, posts.get() + patches.get() + deletes.get())
        compose.onNodeWithText("Valore di 1 USD in EUR").performScrollTo().performTextReplacement("0,1234567")
        compose.onNodeWithText("Descrizione facoltativa").performScrollTo().performTextInput("Cambio manuale sintetico")
        compose.onNodeWithText("Salva").performClick()
        compose.waitUntil(15_000) {
            posts.get() == 1 && compose.onAllNodesWithText("Nuovo elemento").fetchSemanticsNodes().isEmpty()
        }
        transactionList().performScrollToIndex(0)
        waitForText("3 transazioni")
        val body = JSONObject(postedBody.get())
        UUID.fromString(body.getString("id"))
        assertEquals(USER_ID, body.getString("user_id"))
        assertEquals("0.02", body.getString("amount"))
        assertEquals("0.123457", body.getString("exchange_rate_eur"))
        assertEquals("USD", body.getString("currency"))
        assertEquals(LocalDate.now().toString(), body.getString("date"))
        assertTrue(body.isNull("category_id"))
        assertEquals("expense", body.getString("type"))
        assertEquals("Cambio manuale sintetico", body.getString("description"))
        scrollFinanceNode(hasText("Cambio manuale sintetico"))
        scrollFinanceNode(hasText("−0,02", substring = true))
        assertEquals(0, patches.get() + deletes.get() + rejectedRequests.get())
    }

    @Test fun privateInsertCommittedWith503ClosesFormBlocksWritesAndReloadsWithoutReplayingDoubleTap() {
        failTransactionInsert.set(true)
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "transactions", Modifier.fillMaxSize(), accountKey = USER_ID) }
        }
        waitForText("2 transazioni")
        transactionList().performScrollToIndex(0)
        compose.onNodeWithText("Nuova transazione").performClick()
        compose.onNodeWithText("Importo").performScrollTo().performTextInput("8,40")
        compose.onNodeWithText("Descrizione facoltativa").performScrollTo().performTextInput("Inserimento incerto sintetico")
        compose.onNodeWithText("Salva").performTouchInput { doubleClick() }
        waitForFinanceCondition("one delayed insert is busy") {
            posts.get() == 1 && compose.onAllNodesWithText("Salvataggio…").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Salvataggio…").assertIsNotEnabled()
        waitForFinanceCondition("ambiguous insert closes the original form") {
            compose.onAllNodesWithText("Nuovo elemento").fetchSemanticsNodes().isEmpty()
        }
        transactionList().performScrollToIndex(0)
        waitForText("Salvataggio non confermato. Aggiorna i dati e verifica la riga prima di creare un nuovo elemento.")
        compose.onNodeWithText("Nuova transazione").assertIsNotEnabled()
        waitForText("Le modifiche sono bloccate finché un aggiornamento dei dati non riesce.")
        val body = JSONObject(postedBody.get())
        UUID.fromString(body.getString("id"))
        assertEquals(USER_ID, body.getString("user_id"))
        assertEquals("8.40", body.getString("amount"))
        compose.onNodeWithText("Riprova").performClick()
        waitForFinanceCondition("failed read completes without releasing the write block") {
            transactionReloadFailures.get() == 1 &&
                compose.onAllNodes(hasContentDescription("Aggiorna i dati") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Nuova transazione").assertIsNotEnabled()
        compose.onNodeWithText("Le modifiche sono bloccate finché un aggiornamento dei dati non riesce.").assertExists()
        assertEquals("A failed read must not repeat an uncertain POST", 1, posts.get())
        failTransactionReload.set(false)
        compose.onNodeWithText("Riprova").performClick()
        waitForText("3 transazioni")
        compose.onNodeWithText("Nuova transazione").assertIsEnabled()
        compose.onNodeWithText("Le modifiche sono bloccate finché un aggiornamento dei dati non riesce.").assertDoesNotExist()
        scrollFinanceNode(hasText("Inserimento incerto sintetico"))
        scrollFinanceNode(hasText("−8,40", substring = true))
        assertEquals("Successful reconciliation reads the committed row instead of replaying the form", 1, posts.get())
        assertEquals(0, patches.get() + deletes.get() + rejectedRequests.get())
    }

    @Test fun recurringSuggestionPrefillsCancelPreservesAndSaveCreatesOnlyOnce() {
        recurringFixture.set(true)
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "recurring", Modifier.fillMaxSize(), accountKey = USER_ID) }
        }
        waitForText("Elabora ora")
        transactionList().performScrollToNode(hasText("Suggerimenti dalla cronologia"))
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

    @Test fun insightsServerMilestoneUsesRecordedValueFutureDateAndLiteralPositiveWithoutCurrencyOrWrites() {
        milestoneInsightsFixture.set(true)
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "insights", Modifier.fillMaxSize(), accountKey = USER_ID) }
        }
        waitForText("Tutti (1)")
        compose.onNodeWithText("Controlli disponibili (8 su 8)", substring = true).assertExists()
        compose.onNodeWithText("Attenzione (0)").assertExists()
        compose.onNodeWithText("Info (0)").assertExists()
        compose.onNodeWithText("Positivi (1)").performScrollTo()
        recurringTouch(hasText("Positivi (1)"))
        compose.onNodeWithText("Positivi (1)").assertIsSelected()
        val description = "Insight net-worth-milestone"
        transactionList().performScrollToNode(hasContentDescription(description))
        compose.onNodeWithContentDescription(description).assertIsDisplayed()
            .assertTextContains("Nuovo massimo del valore registrato dal server", substring = true)
            .assertTextContains("Positivo", substring = true)
            .assertTextContains("ha raggiunto 201,00, almeno 1 sopra il massimo precedente", substring = true)
            .assertTextContains("La serie server non distingue le valute", substring = true)
            .assertTextContains("non è confrontabile con i totali nativi per valuta", substring = true)
        compose.onNode(hasText("201,00", substring = true) and hasText("EUR", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("201,00", substring = true) and hasText("USD", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("201,00", substring = true) and hasText("€", substring = true)).assertDoesNotExist()
        transactionList().performScrollToIndex(0)
        compose.onNodeWithText("Info (0)").performScrollTo()
        recurringTouch(hasText("Info (0)"))
        compose.onNodeWithText("Info (0)").assertIsSelected()
        waitForText("Nessun segnale nel filtro selezionato")
        compose.onNodeWithContentDescription(description).assertDoesNotExist()
        org.junit.Assert.assertTrue("Insights must read server snapshots", snapshotReadRequests.get() > 0)
        assertEquals("Reading server history must never mutate records", 0, posts.get() + patches.get() + deletes.get())
        assertEquals(0, rejectedRequests.get())
    }

    @Test fun insightsSnapshotFailureShowsAnErrorRatherThanAnEmptyMilestoneListWithoutWrites() {
        milestoneInsightsFixture.set(true)
        failSnapshotRead.set(true)
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "insights", Modifier.fillMaxSize(), accountKey = USER_ID) }
        }
        waitForText("Non hai il permesso per questa operazione.")
        compose.onNodeWithText("Riprova").assertExists()
        compose.onNodeWithText("Nessun segnale negli otto controlli disponibili").assertDoesNotExist()
        compose.onNodeWithText("Tutti (0)").assertDoesNotExist()
        org.junit.Assert.assertTrue(snapshotReadRequests.get() > 0)
        assertEquals(0, posts.get() + patches.get() + deletes.get())
        assertEquals(0, rejectedRequests.get())
    }

    @Test fun insightsCategoryTrendUsesIndividualMedianFutureCurrencyAndLiteralInfoWithoutWrites() {
        trendInsightsFixture.set(true)
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "insights", Modifier.fillMaxSize(), accountKey = USER_ID) }
        }
        waitForText("Tutti (1)")
        compose.onNodeWithText("Controlli disponibili (8 su 8)", substring = true).assertExists()
        compose.onNodeWithText("Attenzione (0)").assertExists()
        compose.onNodeWithText("Positivi (0)").assertExists()
        compose.onNodeWithText("Info (1)").performScrollTo()
        recurringTouch(hasText("Info (1)"))
        compose.onNodeWithText("Info (1)").assertIsSelected()
        val description = "Insight category-trend-EUR-$FOOD_ID"
        transactionList().performScrollToNode(hasContentDescription(description))
        compose.onNodeWithContentDescription(description).assertIsDisplayed()
            .assertTextContains("Alimentari in trend crescente: EUR", substring = true)
            .assertTextContains("Info", substring = true)
            .assertTextContains("La mediana dei singoli importi in Alimentari è cresciuta del 25%", substring = true)
            .assertTextContains("50,00", substring = true)
            .assertTextContains("rispetto a 40,00", substring = true)
        compose.onNodeWithContentDescription("Insight category-trend-USD-$FOOD_ID").assertDoesNotExist()
        transactionList().performScrollToIndex(0)
        compose.onNodeWithText("Attenzione (0)").performScrollTo()
        recurringTouch(hasText("Attenzione (0)"))
        compose.onNodeWithText("Attenzione (0)").assertIsSelected()
        waitForText("Nessun segnale nel filtro selezionato")
        compose.onNodeWithContentDescription(description).assertDoesNotExist()
        assertEquals("Category trend reads the existing pipeline without mutations", 0, posts.get() + patches.get() + deletes.get())
        assertEquals(0, rejectedRequests.get())
    }

    @Test fun insightsSpendingAnomalyUsesIndividualMedianCurrencyAndBoundaryWithLiteralValuesWithoutWrites() {
        anomalyInsightsFixture.set(true)
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "insights", Modifier.fillMaxSize(), accountKey = USER_ID) }
        }
        waitForText("Tutti (1)")
        compose.onNodeWithText("Controlli disponibili (8 su 8)", substring = true).assertExists()
        compose.onNodeWithText("Attenzione (1)").assertExists()
        compose.onNodeWithText("Positivi (0)").assertExists()
        compose.onNodeWithText("Info (0)").assertExists()
        recurringTouch(hasText("Attenzione (1)"))
        compose.onNodeWithText("Attenzione (1)").assertIsSelected()
        val description = "Insight spending-anomaly-EUR-$FOOD_ID"
        transactionList().performScrollToNode(hasContentDescription(description))
        compose.onNodeWithContentDescription(description).assertIsDisplayed()
            .assertTextContains("Spesa anomala in Alimentari: EUR", substring = true)
            .assertTextContains("Attenzione", substring = true)
            .assertTextContains("Hai speso 70,00", substring = true)
            .assertTextContains("+40% rispetto alla mediana dei singoli importi (50,00", substring = true)
        compose.onNodeWithContentDescription("Insight spending-anomaly-USD-$FOOD_ID").assertDoesNotExist()
        transactionList().performScrollToIndex(0)
        compose.onNodeWithText("Positivi (0)").performScrollTo()
        recurringTouch(hasText("Positivi (0)"))
        compose.onNodeWithText("Positivi (0)").assertIsSelected()
        waitForText("Nessun segnale nel filtro selezionato")
        compose.onNodeWithContentDescription(description).assertDoesNotExist()
        assertEquals("Spending anomalies only read the existing data", 0, posts.get() + patches.get() + deletes.get())
        assertEquals(0, rejectedRequests.get())
    }

    @Test fun insightsShowSavingMonthAndSeverityFiltersWithLiteralValuesWithoutWrites() {
        insightsFixture.set(true)
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "insights", Modifier.fillMaxSize(), accountKey = USER_ID) }
        }
        waitForText("Tutti (3)")
        compose.onNodeWithText("Controlli disponibili (8 su 8)", substring = true).assertExists()
        compose.onNodeWithText("Attenzione (2)").assertExists()
        compose.onNodeWithText("Positivi (1)").assertExists()
        compose.onNodeWithText("Info (0)").assertExists()
        fun selectFilter(label: String) {
            transactionList().performScrollToIndex(0)
            transactionList().performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performScrollTo()
            recurringTouch(hasText(label))
            compose.onNodeWithText(label).assertIsSelected()
        }
        selectFilter("Positivi (1)")
        transactionList().performScrollToNode(hasContentDescription("Insight saving-month-EUR"))
        compose.onNodeWithContentDescription("Insight saving-month-EUR").assertIsDisplayed()
            .assertTextContains("Ottimo mese di risparmio: EUR", substring = true)
            .assertTextContains("Positivo", substring = true)
            .assertTextContains("Stai risparmiando 120,00", substring = true)
            .assertTextContains("+20%", substring = true)
            .assertTextContains("100,00", substring = true)
        compose.onNodeWithContentDescription("Insight budget-insights-food").assertDoesNotExist()
        selectFilter("Attenzione (2)")
        transactionList().performScrollToNode(hasContentDescription("Insight budget-insights-food"))
        compose.onNodeWithContentDescription("Insight budget-insights-food").assertIsDisplayed()
            .assertTextContains("Attenzione", substring = true)
        compose.onNodeWithContentDescription("Insight saving-month-EUR").assertDoesNotExist()
        selectFilter("Info (0)")
        waitForText("Nessun segnale nel filtro selezionato")
        selectFilter("Tutti (3)")
        transactionList().performScrollToNode(hasContentDescription("Insight saving-month-EUR"))
        compose.onNodeWithContentDescription("Insight saving-month-EUR").assertIsDisplayed()
        assertEquals("Insights derive signals without writing records", 0, posts.get() + patches.get() + deletes.get())
        org.junit.Assert.assertTrue("Insights must load tracked recurring records even when no candidate exists", recurringReadRequests.get() > 0)
        assertEquals(0, rejectedRequests.get())
    }

    @Test fun insightsInfoShowsRecurringLiteralCadenceAmountAndExcludesTrackedAndIgnoredWithoutWrites() {
        recurringInsightsFixture.set(true)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("finance_recurring_suggestions", Context.MODE_PRIVATE)
        check(preferences.edit().remove(USER_ID).commit())
        PreferenceRecurringDismissals(context).ignore(USER_ID, "EUR:abbonamento ignorato insights")
        try {
            compose.setContent {
                SavelyTheme("light") { FinanceScreen(api, "insights", Modifier.fillMaxSize(), accountKey = USER_ID) }
            }
            // These categorized recurring expenses also produce the newly supported spending anomaly.
            waitForText("Tutti (2)")
            compose.onNodeWithText("Controlli disponibili (8 su 8)", substring = true).assertExists()
            compose.onNodeWithText("Attenzione (1)").assertExists()
            compose.onNodeWithText("Positivi (0)").assertExists()
            compose.onNodeWithText("Info (1)").performScrollTo()
            recurringTouch(hasText("Info (1)"))
            compose.onNodeWithText("Info (1)").assertIsSelected()
            transactionList().performScrollToNode(hasContentDescription("Insight new-recurring-EUR:abbonamento disponibile insights"))
            compose.onNodeWithContentDescription("Insight new-recurring-EUR:abbonamento disponibile insights")
                .assertIsDisplayed().assertTextContains("Possibile spesa ricorrente: Abbonamento disponibile insights", substring = true)
                .assertTextContains("Info", substring = true)
                .assertTextContains("Rilevata una cadenza mensile di 12,50", substring = true)
                .assertTextContains("(4 occorrenze). Vuoi tenerla tracciata?", substring = true)
            compose.onNodeWithContentDescription("Insight new-recurring-EUR:abbonamento tracciato insights").assertDoesNotExist()
            compose.onNodeWithContentDescription("Insight new-recurring-EUR:abbonamento ignorato insights").assertDoesNotExist()
            transactionList().performScrollToIndex(0)
            compose.onNodeWithText("Attenzione (1)").performScrollTo()
            recurringTouch(hasText("Attenzione (1)"))
            transactionList().performScrollToNode(hasContentDescription("Insight spending-anomaly-EUR-$FOOD_ID"))
            compose.onNodeWithContentDescription("Insight spending-anomaly-EUR-$FOOD_ID").assertIsDisplayed()
                .assertTextContains("Spesa anomala in Alimentari: EUR", substring = true)
            compose.onNodeWithContentDescription("Insight new-recurring-EUR:abbonamento disponibile insights").assertDoesNotExist()
            org.junit.Assert.assertTrue("Insights must read tracked recurring records", recurringReadRequests.get() > 0)
            assertEquals("Reading and filtering suggestions must never mutate backend records", 0, posts.get() + patches.get() + deletes.get())
            assertEquals(0, rejectedRequests.get())
        } finally {
            check(preferences.edit().remove(USER_ID).commit())
        }
    }

    @Test fun insightsRecurringPriceShowsExactTenPercentAndSeverityFiltersWithoutWrites() {
        recurringPriceInsightsFixture.set(true)
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "insights", Modifier.fillMaxSize(), accountKey = USER_ID) }
        }
        waitForText("Tutti (2)")
        compose.onNodeWithText("Controlli disponibili (8 su 8)", substring = true).assertExists()
        compose.onNodeWithText("Attenzione (1)").assertExists()
        compose.onNodeWithText("Positivi (0)").assertExists()
        compose.onNodeWithText("Info (1)").assertExists()
        recurringTouch(hasText("Attenzione (1)"))
        compose.onNodeWithText("Attenzione (1)").assertIsSelected()
        transactionList().performScrollToNode(hasContentDescription("Insight recurring-price-EUR:netflix sintetico"))
        compose.onNodeWithContentDescription("Insight recurring-price-EUR:netflix sintetico").assertIsDisplayed()
            .assertTextContains("Spesa ricorrente aumentato: Netflix sintetico", substring = true)
            .assertTextContains("Attenzione", substring = true)
            .assertTextContains("L'ultimo importo (11,00", substring = true)
            .assertTextContains("è 10% rispetto al solito (10,00", substring = true)
        transactionList().performScrollToIndex(0)
        compose.onNodeWithText("Info (1)").performScrollTo()
        recurringTouch(hasText("Info (1)"))
        compose.onNodeWithText("Info (1)").assertIsSelected()
        transactionList().performScrollToNode(hasContentDescription("Insight new-recurring-EUR:netflix sintetico"))
        compose.onNodeWithContentDescription("Insight new-recurring-EUR:netflix sintetico").assertIsDisplayed()
            .assertTextContains("Rilevata una cadenza mensile di 10,00", substring = true)
            .assertTextContains("(3 occorrenze)", substring = true)
        compose.onNodeWithContentDescription("Insight recurring-price-EUR:netflix sintetico").assertDoesNotExist()
        org.junit.Assert.assertTrue("Price insights reuse tracked recurring reads", recurringReadRequests.get() > 0)
        assertEquals("Price insights must not create or alter backend data", 0, posts.get() + patches.get() + deletes.get())
        assertEquals(0, rejectedRequests.get())
    }

    @Test fun budgetMedianHalfCentDisplaysLiteralRoundedValuesWithoutRoundingSource() {
        budgetRoundingFixture.set(true)
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "budget", Modifier.fillMaxSize(), accountKey = USER_ID) }
        }
        val description = "Riepilogo budget EUR"
        waitForFinanceCondition("budget half-cent fixture loaded") {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
        transactionList().performScrollToNode(hasContentDescription(description))
        compose.waitForIdle()
        // Monthly totals 0.30 and 0.31 => exact median 0.305; budget 0.30 => exact difference 0.005.
        // Literal assertions intentionally do not derive expectations through formatMoney.
        compose.onNodeWithContentDescription(description).assertIsDisplayed()
            .assertTextContains("Spesa mensile prevista: 0,30", substring = true)
            .assertTextContains("Spesa mediana mensile reale: 0,31", substring = true)
            .assertTextContains("Differenza: +0,01", substring = true)
        assertEquals("Formatting must not write records", 0, posts.get() + patches.get() + deletes.get())
        assertEquals(0, rejectedRequests.get())
    }

    @Test fun budgetSummaryUsesGlobalMonthlyMedianAndKeepsCurrenciesSeparate() {
        budgetFixture.set(true)
        compose.setContent {
            SavelyTheme("light") { FinanceScreen(api, "budget", Modifier.fillMaxSize(), accountKey = USER_ID) }
        }
        waitForFinanceCondition("budget data loaded") {
            compose.onAllNodesWithContentDescription("Riepilogo budget EUR").fetchSemanticsNodes().isNotEmpty()
        }
        fun assertSummary(currency: String, planned: String, median: String, difference: String, positive: Boolean) {
            val description = "Riepilogo budget $currency"
            transactionList().performScrollToNode(hasContentDescription(description))
            compose.waitForIdle()
            compose.onNodeWithContentDescription(description).assertIsDisplayed()
                .assertTextContains("Spesa mensile prevista: ${formatMoney(planned.toBigDecimal(), currency)}", substring = true)
                .assertTextContains("Spesa mediana mensile reale: ${formatMoney(median.toBigDecimal(), currency)}", substring = true)
                .assertTextContains("Differenza: ${if (positive) "+" else ""}${formatMoney(difference.toBigDecimal(), currency)}", substring = true)
        }
        // EUR monthly totals 100, 100, 60 => median 100; sum of category medians would be 20.
        assertSummary("EUR", "80.00", "100.00", "20.00", true)
        assertSummary("GBP", "25.00", "0.00", "-25.00", false)
        assertSummary("USD", "0.00", "40.00", "40.00", true)
        assertEquals("Summary is read-only", 0, posts.get() + patches.get() + deletes.get())
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

    @Test fun dashboardCustomPeriodComparesLiteralAmountsValidatesInputAndKeepsCurrentMonthBudget() {
        dashboardComparisonFixture.set(true)
        compose.setContent { SavelyTheme("light") { FinanceScreen(api, "dashboard", Modifier.fillMaxSize(), accountKey = USER_ID) } }
        waitForText("Periodo: Mese corrente")
        compose.onNodeWithText("Periodo: Mese corrente").performClick()
        compose.onNodeWithText("Personalizzato").performClick()
        transactionList().performScrollToNode(hasText("Dal"))
        compose.onNodeWithText("Dal").performTextReplacement("2024-06-01")
        compose.onNodeWithText("Al").performTextReplacement("2024-06-15")
        transactionList().performScrollToNode(hasText("Applica intervallo"))
        compose.onNodeWithText("Applica intervallo").performClick()
        transactionList().performScrollToNode(hasText("Periodo precedente: 2024-05-17 – 2024-05-31"))
        compose.onNodeWithText("Periodo precedente: 2024-05-17 – 2024-05-31").assertIsDisplayed()
        transactionList().performScrollToNode(hasContentDescription("Statistiche EUR"))
        compose.onNodeWithContentDescription("Statistiche EUR")
            .assertTextContains("180,00", substring = true).assertTextContains("120,00", substring = true)
            .assertTextContains("30,00", substring = true).assertTextContains("90,00", substring = true)
            .assertTextContains("20% rispetto al periodo precedente", substring = true)
            .assertTextContains("75% del totale entrate", substring = true)
        transactionList().performScrollToNode(hasText("Spesa delle categorie con budget: ", substring = true))
        compose.onNodeWithText("Spesa delle categorie con budget: ", substring = true).assertTextContains("25,00", substring = true)
        compose.onNodeWithText("Disponibile: ", substring = true).assertTextContains("25,00", substring = true)
        transactionList().performScrollToNode(hasText("Dal"))
        compose.onNodeWithText("Dal").performTextReplacement("2024-02-30")
        transactionList().performScrollToNode(hasText("Applica intervallo"))
        compose.onNodeWithText("Applica intervallo").performClick()
        compose.onNodeWithText("Inserisci date valide nel formato YYYY-MM-DD").assertExists()
        compose.onNodeWithText("Periodo selezionato: 2024-06-01 – 2024-06-15").assertExists()
        assertEquals(0, posts.get() + patches.get() + deletes.get())
        assertEquals(0, rejectedRequests.get())
    }

    @Test fun dashboardCategoryDonutAndOperationLinksUseProductionScreenWithoutWrites() {
        dashboardComparisonFixture.set(true)
        val destination = AtomicReference("")
        compose.setContent { SavelyTheme("light") { FinanceScreen(api, "dashboard", Modifier.fillMaxSize(), accountKey = USER_ID,
            onNavigate = { destination.set(it) }) } }
        waitForText("Periodo: Mese corrente")
        transactionList().performScrollToNode(hasText("Spese per categoria"))
        compose.onNodeWithText("Spese per categoria").assertIsDisplayed()
        transactionList().performScrollToNode(hasText("Totale spese: ", substring = true))
        compose.onNodeWithText("Totale spese: ", substring = true).assertTextContains("25,00", substring = true)
        transactionList().performScrollToNode(hasText("Vedi tutti"))
        compose.waitForIdle()
        compose.onNodeWithText("Vedi tutti").performClick()
        assertEquals("budget", destination.get())
        transactionList().performScrollToNode(hasText("Vedi tutte"))
        compose.waitForIdle()
        compose.onNodeWithText("Vedi tutte").performClick()
        assertEquals("transactions", destination.get())
        assertEquals(0, posts.get() + patches.get() + deletes.get())
        assertEquals(0, rejectedRequests.get())
    }

    @Test fun recurringCalendarProjectsSavedDateNavigatesMonthAndExcludesPausedWithoutWrites() {
        calendarFixture.set(true)
        compose.setContent { SavelyTheme("light") { FinanceScreen(api, "recurring", Modifier.fillMaxSize(), accountKey = USER_ID) } }
        waitForText("Elabora ora")
        val first = LocalDate.now().withDayOfMonth(1)
        transactionList().performScrollToNode(hasContentDescription("$first: 1 scadenze"))
        compose.onNodeWithContentDescription("$first: 1 scadenze").performClick()
        transactionList().performScrollToNode(hasText("Scadenze del $first"))
        compose.onNodeWithText("Quota calendario · ", substring = true).assertTextContains("12,50", substring = true)
        compose.onNodeWithText("Quota in pausa · ", substring = true).assertDoesNotExist()
        transactionList().performScrollToNode(hasText("Mese successivo"))
        compose.onNodeWithText("Mese successivo").performClick()
        val next = first.plusMonths(1)
        transactionList().performScrollToNode(hasText("Scadenze del $next"))
        compose.onNodeWithText("Quota calendario · ", substring = true).assertTextContains("12,50", substring = true)
        compose.onNodeWithText("Quota in pausa · ", substring = true).assertDoesNotExist()
        assertEquals(0, posts.get() + patches.get() + deletes.get())
        assertEquals(0, rejectedRequests.get())
    }

    @Test fun collectiveClosureSavesBothPurchasesOnceAndFailedRefreshKeepsWritesBlockedUntilReconciliation() {
        positionClosureFixture.set(true)
        compose.setContent { SavelyTheme("light") { FinanceScreen(api, "portfolio", Modifier.fillMaxSize(), accountKey = USER_ID) } }
        waitForFinanceCondition("portfolio fixture loaded before scrolling to grouped purchases") {
            compose.onAllNodesWithText("Nuovo acquisto").fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) }
        }
        transactionList().performScrollToNode(hasText("Chiudi tutti gli acquisti"))
        compose.onNodeWithText("Chiudi tutti gli acquisti").assertIsEnabled().performClick()
        compose.onNodeWithText("Chiudi Titolo collettivo QA").assertIsDisplayed()
        compose.onNodeWithText("Verranno chiusi 2 acquisti, per 5 unità.", substring = true).assertExists()
        assertEquals("Opening the dialog cannot close a purchase", 0, patches.get())
        compose.onNodeWithText("Annulla").performClick()
        assertEquals("Cancel preserves both purchases", 0, patches.get())
        compose.onNodeWithText("Chiudi tutti gli acquisti").performClick()
        compose.onNodeWithText("Conferma chiusura collettiva").performClick()
        waitForFinanceCondition("both closures confirmed and automatic refresh requested") {
            patches.get() == 2 && positionRefreshRequests.get() == 2
        }
        transactionList().performScrollToIndex(0)
        waitForText("Riprova")
        assertEquals("Each purchase is patched exactly once", 2, patches.get())
        assertEquals("One initial load and one intentional reconciliation, excluding continuation pages", 2, positionRefreshRequests.get())
        transactionList().performScrollToIndex(0)
        compose.onNodeWithText("Nuovo acquisto").assertIsNotEnabled()
        compose.onNodeWithText("Le modifiche sono bloccate finché un aggiornamento dei dati non riesce.").assertExists()
        transactionList().performScrollToNode(hasText("Chiudi tutti gli acquisti"))
        compose.onNodeWithText("Chiudi tutti gli acquisti").assertIsNotEnabled()
        transactionList().performScrollToIndex(0)
        transactionList().performScrollToNode(hasText("Riprova"))
        compose.onNodeWithText("Riprova").performClick()
        waitForFinanceCondition("second failed read leaves the reconciliation gate") {
            positionRefreshRequests.get() == 3 && compose.onAllNodesWithText("Riprova").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Retry refresh is a read and must not repeat either closure", 2, patches.get())
        transactionList().performScrollToIndex(0)
        compose.onNodeWithText("Nuovo acquisto").assertIsNotEnabled()
        failPositionRefresh.set(false)
        transactionList().performScrollToNode(hasText("Riprova"))
        compose.onNodeWithText("Riprova").performClick()
        waitForFinanceCondition("successful refresh releases reconciliation") {
            positionRefreshRequests.get() == 4 && compose.onAllNodesWithText("Le modifiche sono bloccate finché un aggiornamento dei dati non riesce.").fetchSemanticsNodes().isEmpty()
        }
        transactionList().performScrollToIndex(0)
        compose.onNodeWithText("Nuovo acquisto").assertIsEnabled()
        transactionList().performScrollToNode(hasText("Nessuna posizione aperta"))
        compose.onNodeWithText("Nessuna posizione aperta").assertExists()
        transactionList().performScrollToNode(hasText("Chiuse"))
        compose.onNodeWithText("Chiuse").performClick()
        listOf("2", "3").forEach { quantity ->
            val purchase = hasText("Quantità $quantity", substring = true) and hasText("Vendita ${LocalDate.now()}", substring = true)
            transactionList().performScrollToNode(purchase)
            compose.onNode(purchase).assertExists()
        }
        assertEquals(2, patches.get())
        assertEquals(0, posts.get() + deletes.get())
        assertEquals("Headers, owner IDs, selected purchase IDs and closure CAS predicates must match", 0, rejectedRequests.get())
    }

    private fun transactionList() = compose.onNode(hasScrollToIndexAction())

    private fun scrollFinanceNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        transactionList().performScrollToNode(matcher)
        repeat(16) {
            compose.waitForIdle()
            val node = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
            if (node == null) {
                transactionList().performScrollToNode(matcher)
                return@repeat
            }
            val viewport = transactionList().fetchSemanticsNode().boundsInRoot
            val position = node.positionInRoot
            val bounds = Rect(position.x, position.y, position.x + node.size.width, position.y + node.size.height)
            if (bounds.top >= viewport.top && bounds.bottom <= viewport.bottom && bounds.left >= viewport.left && bounds.right <= viewport.right)
                return compose.onNode(matcher).assertIsDisplayed()
            // Filters share one tall lazy item. Move by a bounded delta rather than flinging past it.
            val delta = (bounds.center.y - viewport.center.y).coerceIn(-viewport.height / 2f, viewport.height / 2f)
            transactionList().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        }
        throw AssertionError("Financial fixture target never became fully visible: $matcher")
    }

    private fun selectTransactionDate(label: String, date: LocalDate) {
        scrollFinanceNode(hasText("$label: ", substring = true)).performClick()
        compose.waitForIdle()
        val focusedDialog = object : TypeSafeMatcher<Root>() {
            override fun describeTo(description: Description) {
                description.appendText("native dialog with actual window focus and completed layout")
            }
            override fun matchesSafely(root: Root): Boolean = isDialog().matches(root) &&
                root.decorView.hasWindowFocus() && !root.decorView.isLayoutRequested
        }
        fun awaitFocusedDialog(viewMatcher: Matcher<View>) {
            compose.waitUntil(15_000) {
                try {
                    onView(viewMatcher).inRoot(focusedDialog).check(matches(isDisplayed()))
                    true
                } catch (_: NoMatchingRootException) {
                    // The previous picker can still be dismissing before the next focused root is registered.
                    false
                }
            }
        }
        val picker = isAssignableFrom(DatePicker::class.java)
        awaitFocusedDialog(picker)
        onView(picker).inRoot(focusedDialog).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(DatePicker::class.java)
            override fun getDescription() = "Select the exact synthetic transaction boundary $date"
            override fun perform(controller: UiController, view: View) {
                (view as DatePicker).updateDate(date.year, date.monthValue - 1, date.dayOfMonth)
                controller.loopMainThreadUntilIdle()
            }
        })
        val confirmation = withId(android.R.id.button1)
        awaitFocusedDialog(confirmation)
        onView(confirmation).inRoot(focusedDialog).perform(click())
        compose.waitForIdle()
        scrollFinanceNode(hasText("$label: $date"))
    }

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
        val positionAssets = linkedMapOf(
            POSITION_FIRST_ID to positionAsset(POSITION_FIRST_ID, "2", "10.00"),
            POSITION_SECOND_ID to positionAsset(POSITION_SECOND_ID, "3", "11.00"),
        )
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return reject()
                if ((anomalyInsightsFixture.get() || trendInsightsFixture.get() || milestoneInsightsFixture.get() || dashboardComparisonFixture.get() || calendarFixture.get() || transactionFilterFixture.get()) && request.method != "GET") return reject()
                if (request.getHeader("Authorization") != "Bearer finance-device-access" ||
                    request.getHeader("apikey") != "public-test-key") return reject()
                if (request.method != "POST" && url.queryParameter("user_id") != "eq.$USER_ID") return reject()
                val offsetText = url.queryParameter("offset")
                val offset = offsetText?.toIntOrNull()
                if (request.method == "GET" && offsetText != null &&
                    (offset == null || offset < 0 || offset > 100_000 || url.queryParameter("limit") != "500")) return reject()
                val response = when (url.encodedPath) {
                    "/rest/v1/categories" -> {
                        val id = url.queryParameter("id")?.removePrefix("eq.")
                        val available = if (transactionFilterFixture.get()) categories + JSONObject().put("id", INCOME_CATEGORY_ID)
                            .put("user_id", USER_ID).put("name", "Alimentari entrate").put("type", "income") else categories
                        json(JSONArray(available.filter { id == null || it.getString("id") == id }).toString())
                    }
                    "/rest/v1/profiles" -> json("""[{"default_currency":"EUR"}]""")
                    "/rest/v1/budgets" -> if (dashboardComparisonFixture.get() && request.method == "GET") json(JSONArray()
                        .put(JSONObject().put("id", "dashboard-budget").put("category_id", FOOD_ID).put("amount", "50.00").put("currency", "EUR")).toString())
                        else if ((milestoneInsightsFixture.get() || trendInsightsFixture.get() || anomalyInsightsFixture.get() || recurringInsightsFixture.get() || recurringPriceInsightsFixture.get()) && request.method == "GET") json("[]")
                        else if (insightsFixture.get() && request.method == "GET") json(JSONArray()
                        .put(JSONObject().put("id", "insights-food").put("category_id", FOOD_ID).put("amount", "100.00").put("currency", "EUR"))
                        .toString()) else if (budgetRoundingFixture.get() && request.method == "GET") json(JSONArray()
                        .put(JSONObject().put("id", "budget-rounding").put("category_id", FOOD_ID).put("amount", "0.30").put("currency", "EUR"))
                        .toString()) else if (budgetFixture.get() && request.method == "GET") json(JSONArray()
                        .put(JSONObject().put("id", "budget-food").put("category_id", FOOD_ID).put("amount", "60.00").put("currency", "EUR"))
                        .put(JSONObject().put("id", "budget-transport").put("category_id", TRANSPORT_ID).put("amount", "20.00").put("currency", "EUR"))
                        .put(JSONObject().put("id", "budget-gbp").put("category_id", FOOD_ID).put("amount", "25.00").put("currency", "GBP"))
                        .toString()) else reject()
                    "/rest/v1/portfolio_assets" -> if (positionClosureFixture.get()) when (request.method) {
                        "GET" -> {
                            val id = url.queryParameter("id")?.removePrefix("eq.")
                            if (id == null && offset == 0) positionRefreshRequests.incrementAndGet()
                            if (failPositionRefresh.get()) MockResponse().setResponseCode(503).setBody("{}")
                            else json(JSONArray(positionAssets.values.filter { id == null || it.getString("id") == id }).toString())
                        }
                        "PATCH" -> {
                            val id = url.queryParameter("id")?.removePrefix("eq.") ?: return reject()
                            val asset = positionAssets[id] ?: return reject()
                            val expected = mapOf("type" to "eq.stock", "currency" to "eq.EUR", "symbol" to "eq.QASTOCK",
                                "quantity" to "eq.${asset.getString("quantity")}", "purchase_price" to "eq.${asset.getString("purchase_price")}",
                                "purchase_date" to "eq.${asset.getString("purchase_date")}", "sold_at" to "is.null", "sold_price" to "is.null")
                            if (expected.any { (field, value) -> url.queryParameter(field) != value } || !asset.isNull("sold_at")) return reject()
                            val body = JSONObject(request.body.readUtf8())
                            if (body.keys().asSequence().toSet() != setOf("sold_at", "sold_price") ||
                                body.getString("sold_at") != LocalDate.now().toString() || body.getString("sold_price") != "12.00") return reject()
                            asset.put("sold_at", body.getString("sold_at")).put("sold_price", body.getString("sold_price"))
                            patches.incrementAndGet()
                            // Both confirmed writes succeed; the following read fails until the test explicitly restores it.
                            if (id == POSITION_SECOND_ID) failPositionRefresh.set(true)
                            json(JSONArray().put(asset).toString())
                        }
                        else -> reject()
                    } else if (insightsFixture.get()) json("""[
                        {"id":"insights-loss","name":"Posizione sintetica","symbol":"TEST","type":"stock","quantity":"2","purchase_price":"100.00","current_price":"85.00","currency":"USD","purchase_date":"2026-01-01","sold_at":null,"sold_price":null,"notes":null}
                    ]""") else json("[]")
                    "/rest/v1/manual_price_updates" -> json("[]")
                    "/rest/v1/asset_price_history" -> if (request.method == "GET") json("[]") else reject()
                    "/rest/v1/net_worth_snapshots" -> {
                        if (request.method != "GET" || url.queryParameter("select") != "id,date,net_worth,components" ||
                            url.queryParameter("order") != "date.asc,id.asc" || url.queryParameter("limit") != "500" ||
                            offset == null || url.queryParameter("date") != null ||
                            url.queryParameter("deleted_at") != null) return reject()
                        if (offset == 0) snapshotReadRequests.incrementAndGet()
                        if (failSnapshotRead.get()) MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody("{}")
                        else if (milestoneInsightsFixture.get()) {
                            val today = LocalDate.now()
                            json(JSONArray()
                                .put(JSONObject().put("id", "server-old-peak").put("date", today.minusMonths(6).toString()).put("net_worth", "200.00").put("components", JSONObject.NULL))
                                .put(JSONObject().put("id", "server-recent-low").put("date", today.minusMonths(1).toString()).put("net_worth", "100.00").put("components", JSONObject.NULL))
                                .put(JSONObject().put("id", "server-future-max").put("date", today.plusYears(1).toString()).put("net_worth", "201.00")
                                    .put("components", JSONObject().put("currency", "USD").put("cashflow", "999999.00")))
                                .toString())
                        } else json("[]")
                    }
                    "/rest/v1/tgc_cards" -> json("""[
                        {"id":"priced","category":"magic","quantity":2,"purchase_price":"10.00","current_price":"12.50","currency":"EUR"},
                        {"id":"unpriced","category":"pokemon","quantity":1,"purchase_price":"100.00","current_price":null,"currency":null},
                        {"id":"foreign","category":"yugioh","quantity":1,"purchase_price":"50.00","current_price":"80.00","currency":"USD"}
                    ]""")
                    "/rest/v1/recurring_expenses" -> when (request.method) {
                        "GET" -> {
                            if (url.queryParameter("deleted_at") != "is.null" || url.queryParameter("limit") != "500") return reject()
                            if (offset == 0) recurringReadRequests.incrementAndGet()
                            if (calendarFixture.get() && recurring.isEmpty()) {
                                listOf(PROCESS_ID to true, CREATED_ID to false).forEach { (id, active) -> recurring[id] = JSONObject()
                                    .put("id", id).put("name", if (active) "Quota calendario" else "Quota in pausa").put("amount", "12.50").put("currency", "EUR")
                                    .put("category_id", JSONObject.NULL).put("frequency", "monthly").put("week_interval", 1)
                                    .put("next_due_date", LocalDate.now().withDayOfMonth(1).toString()).put("is_active", active) }
                            }
                            if (recurringInsightsFixture.get() && recurring.isEmpty()) recurring[PROCESS_ID] = JSONObject()
                                .put("id", PROCESS_ID).put("name", "Abbonamento tracciato insights").put("amount", "19.00").put("currency", "EUR")
                                .put("category_id", JSONObject.NULL).put("frequency", "monthly").put("week_interval", 1)
                                .put("next_due_date", LocalDate.now().plusDays(30).toString()).put("is_active", false)
                            if (processingFixture.get() && recurring.isEmpty()) recurring[PROCESS_ID] = JSONObject()
                                .put("id", PROCESS_ID).put("name", PROCESS_NAME).put("amount", "12.50").put("currency", "EUR")
                                .put("category_id", JSONObject.NULL).put("frequency", "monthly").put("week_interval", 1)
                                .put("next_due_date", LocalDate.now().withDayOfMonth(1).toString()).put("is_active", true)
                            val due = url.queryParameter("next_due_date")?.removePrefix("lte.")
                            val active = url.queryParameter("is_active") == "eq.true"
                            if (due != null && offset == 0) dueQueries.incrementAndGet()
                            val rows = recurring.values.filter { (!active || it.getBoolean("is_active")) &&
                                (due == null || it.getString("next_due_date") <= due) }
                            json(JSONArray(rows).toString()).apply { if (due != null && offset == 0) setBodyDelay(1200, TimeUnit.MILLISECONDS) }
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
                        "GET" -> if (failTransactionReload.get()) {
                            transactionReloadFailures.incrementAndGet()
                            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json").setBody("{}")
                        } else if (transactionFilterFixture.get()) json(JSONArray()
                            .put(transaction(LUNCH_ID, "12.50", "Pranzo sintetico", FOOD_ID).put("date", "2026-06-15"))
                            .put(transaction(TRAIN_ID, "30.00", "Biglietto treno", TRANSPORT_ID).put("date", "2026-06-14"))
                            .put(transaction(FILTER_DOLLAR_ID, "8.50", "Spesa USD sintetica", FOOD_ID).put("currency", "USD")
                                .put("exchange_rate_eur", "0.90").put("date", "2026-06-01"))
                            .put(transaction(FILTER_INCOME_ID, "100.00", "Entrata sintetica", INCOME_CATEGORY_ID).put("type", "income").put("date", "2026-06-15"))
                            .put(transaction(FILTER_PREVIOUS_ID, "50.00", "Spesa precedente sintetica", FOOD_ID).put("date", "2026-05-31"))
                            .toString()) else if (dashboardComparisonFixture.get()) json(JSONArray()
                            .put(transaction("dashboard-opening", "30.00", "Saldo iniziale", FOOD_ID).put("type", "income").put("date", "2024-04-01"))
                            .put(transaction("dashboard-previous-income", "100.00", "Entrata precedente", FOOD_ID).put("type", "income").put("date", "2024-05-17"))
                            .put(transaction("dashboard-previous-expense", "40.00", "Spesa precedente", FOOD_ID).put("date", "2024-05-31"))
                            .put(transaction("dashboard-current-income", "120.00", "Entrata selezionata", FOOD_ID).put("type", "income").put("date", "2024-06-01"))
                            .put(transaction("dashboard-current-expense", "30.00", "Spesa selezionata", FOOD_ID).put("date", "2024-06-15"))
                            .put(transaction("dashboard-month-budget", "25.00", "Spesa mese attuale", FOOD_ID).put("date", LocalDate.now().withDayOfMonth(1).toString())).toString())
                        else if (milestoneInsightsFixture.get()) json("[]") else if (trendInsightsFixture.get()) {
                            val today = LocalDate.now()
                            val month = today.withDayOfMonth(1)
                            val rows = JSONArray()
                            // Five older 40s plus two recent 50s and one future 50 => baseline40, recent50.
                            repeat(5) { index -> rows.put(transaction("trend-old-$index", "40.00", "Storico categoria $index", FOOD_ID)
                                .put("date", month.minusMonths(6).plusDays(index.toLong()).toString())) }
                            repeat(2) { index -> rows.put(transaction("trend-recent-$index", "50.00", "Recente categoria $index", FOOD_ID)
                                .put("date", month.minusMonths(1).plusDays(index.toLong()).toString())) }
                            rows.put(transaction("trend-future", "50.00", "Importo di un mese futuro", FOOD_ID)
                                .put("date", month.plusMonths(1).toString()))
                            // Same-month expense excluded from both medians, below the anomaly minimum.
                            rows.put(transaction("trend-current", "49.00", "Mese corrente escluso dal trend", FOOD_ID))
                            rows.put(transaction("trend-boundary", "9000.00", "Confine dodici mesi escluso", FOOD_ID)
                                .put("date", insightWindowStart(today, 12).toString()))
                            rows.put(transaction("trend-income", "9999.00", "Entrata storica esclusa", FOOD_ID)
                                .put("type", "income").put("date", month.minusMonths(1).toString()))
                            repeat(3) { index -> rows.put(transaction("trend-usd-old-$index", "100.00", "Storico USD $index", FOOD_ID)
                                .put("currency", "USD").put("date", month.minusMonths(6).plusDays(index.toLong()).toString())) }
                            repeat(2) { index -> rows.put(transaction("trend-usd-recent-$index", "100.00", "Recente USD $index", FOOD_ID)
                                .put("currency", "USD").put("date", month.minusMonths(1).plusDays(index.toLong()).toString())) }
                            rows.put(transaction("trend-null", "9000.00", "Categoria assente", FOOD_ID)
                                .put("category_id", JSONObject.NULL).put("date", month.minusMonths(1).toString()))
                            json(rows.toString())
                        } else if (anomalyInsightsFixture.get()) {
                            val today = LocalDate.now()
                            val month = today.withDayOfMonth(1)
                            json(JSONArray()
                                .put(transaction("anomaly-old-one", "40.00", "Primo importo storico", FOOD_ID).put("date", month.minusMonths(1).toString()))
                                .put(transaction("anomaly-old-two", "60.00", "Secondo importo storico", FOOD_ID).put("date", month.minusMonths(1).plusDays(1).toString()))
                                .put(transaction("anomaly-current-one", "35.00", "Prima uscita corrente", FOOD_ID))
                                .put(transaction("anomaly-current-future", "35.00", "Uscita a fine mese", FOOD_ID).put("date", month.plusMonths(1).minusDays(1).toString()))
                                .put(transaction("anomaly-usd-old", "100.00", "Importo USD precedente", FOOD_ID).put("currency", "USD").put("date", month.minusMonths(1).toString()))
                                .put(transaction("anomaly-usd-current", "100.00", "Importo USD corrente", FOOD_ID).put("currency", "USD"))
                                .put(transaction("anomaly-boundary", "9000.00", "Uscita esclusa al confine", FOOD_ID).put("date", today.minusDays(730).toString()))
                                .put(transaction("anomaly-income", "9999.00", "Entrata esclusa dallo storico", FOOD_ID).put("type", "income").put("date", month.minusMonths(1).toString()))
                                .put(transaction("anomaly-null-old", "10.00", "Storico senza categoria", FOOD_ID).put("category_id", JSONObject.NULL).put("date", month.minusMonths(1).toString()))
                                .put(transaction("anomaly-null-current", "500.00", "Corrente senza categoria", FOOD_ID).put("category_id", JSONObject.NULL))
                                .toString())
                        } else if (recurringPriceInsightsFixture.get()) {
                            val rows = JSONArray()
                            (2 downTo 0).forEach { index -> rows.put(transaction("price-insight-$index",
                                if (index == 2) "11.00" else "10.00", "Netflix sintetico", FOOD_ID)
                                .put("date", LocalDate.now().minusDays((2L - index) * 30).toString())) }
                            json(rows.toString())
                        } else if (recurringInsightsFixture.get()) {
                            val rows = JSONArray()
                            listOf("Abbonamento disponibile insights" to "12.50", "Abbonamento tracciato insights" to "19.00",
                                "Abbonamento ignorato insights" to "29.00").forEach { (name, amount) ->
                                repeat(4) { index -> rows.put(transaction("$name-$index", amount, name, FOOD_ID)
                                    .put("date", LocalDate.now().minusDays((3L - index) * 30).toString())) }
                            }
                            json(rows.toString())
                        } else if (insightsFixture.get()) {
                            val month = LocalDate.now().withDayOfMonth(1)
                            json(JSONArray()
                                .put(transaction("insights-eur-previous", "100.00", "Entrata EUR", FOOD_ID).put("type", "income").put("date", month.minusMonths(1).toString()))
                                .put(transaction("insights-usd-previous", "100.00", "Entrata USD", FOOD_ID).put("type", "income").put("currency", "USD").put("date", month.minusMonths(1).toString()))
                                .put(transaction("insights-eur-income", "320.00", "Entrata EUR", FOOD_ID).put("type", "income"))
                                .put(transaction("insights-eur-expense", "200.00", "Uscita EUR", FOOD_ID))
                                .put(transaction("insights-usd-income", "100.00", "Entrata USD", FOOD_ID).put("type", "income").put("currency", "USD"))
                                .toString())
                        } else if (budgetRoundingFixture.get()) {
                            val month = LocalDate.now().withDayOfMonth(1)
                            json(JSONArray()
                                .put(transaction("rounding-previous", "0.30", "Spesa precedente", FOOD_ID).put("date", month.minusMonths(1).toString()))
                                .put(transaction("rounding-current", "0.31", "Spesa corrente", FOOD_ID).put("date", month.toString()))
                                .toString())
                        } else if (budgetFixture.get()) {
                            val month = LocalDate.now().withDayOfMonth(1)
                            json(JSONArray()
                                .put(transaction("budget-a1", "90.00", "Spesa A", FOOD_ID).put("date", month.minusMonths(2).toString()))
                                .put(transaction("budget-b1", "10.00", "Spesa B", TRANSPORT_ID).put("date", month.minusMonths(2).toString()))
                                .put(transaction("budget-a2", "10.00", "Spesa A", FOOD_ID).put("date", month.minusMonths(1).toString()))
                                .put(transaction("budget-b2", "90.00", "Spesa B", TRANSPORT_ID).put("date", month.minusMonths(1).toString()))
                                .put(transaction("budget-a3", "10.00", "Spesa A", FOOD_ID).put("date", month.toString()))
                                .put(transaction("budget-b3", "10.00", "Spesa B", TRANSPORT_ID).put("date", month.toString()))
                                .put(transaction("budget-uncategorized", "40.00", "Spesa senza categoria", FOOD_ID).put("category_id", JSONObject.NULL))
                                .put(transaction("budget-usd", "40.00", "Spesa USD", FOOD_ID).put("currency", "USD"))
                                .put(transaction("budget-boundary", "9000.00", "Spesa al confine", FOOD_ID).put("date", LocalDate.now().minusDays(730).toString()))
                                .put(transaction("budget-income", "9000.00", "Entrata", FOOD_ID).put("type", "income"))
                                .toString())
                        } else if (url.queryParameter("description") != null) {
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
                            val storedId = if (failTransactionInsert.get()) body.getString("id") else CREATED_ID
                            transactions[storedId] = body.put("id", storedId).put("deleted_at", JSONObject.NULL)
                            if (failTransactionInsert.get()) {
                                failTransactionReload.set(true)
                                MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json").setBody("{}")
                                    .setBodyDelay(1200, TimeUnit.MILLISECONDS)
                            } else json(JSONArray().put(body).toString())
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
                // Repository reads until an empty page, including datasets shorter than its requested limit.
                // Keep every path's validation/error status above; only slice successful paginated GET bodies.
                if (request.method == "GET" && offset != null && response.status.startsWith("HTTP/1.1 200")) {
                    val all = JSONArray(response.getBody()!!.readUtf8())
                    val page = JSONArray()
                    for (index in offset until minOf(all.length(), offset + 500)) page.put(all.get(index))
                    response.setBody(page.toString())
                }
                return response
            }
        }
    }

    private fun transaction(id: String, amount: String, description: String, categoryId: String) = JSONObject()
        .put("id", id).put("user_id", USER_ID).put("type", "expense").put("amount", amount)
        .put("currency", "EUR").put("date", LocalDate.now().toString()).put("description", description)
        .put("category_id", categoryId).put("exchange_rate_eur", "1").put("deleted_at", JSONObject.NULL)
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun positionAsset(id: String, quantity: String, cost: String) = JSONObject()
        .put("id", id).put("user_id", USER_ID).put("name", "Titolo collettivo QA").put("symbol", "QASTOCK").put("type", "stock")
        .put("quantity", quantity).put("purchase_price", cost).put("current_price", "12.00").put("currency", "EUR")
        .put("purchase_date", LocalDate.now().minusDays(30).toString()).put("sold_at", JSONObject.NULL).put("sold_price", JSONObject.NULL).put("notes", JSONObject.NULL)
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
        const val POSITION_FIRST_ID = "10000000-0000-4000-8000-000000000008"
        const val POSITION_SECOND_ID = "10000000-0000-4000-8000-000000000009"
        const val INCOME_CATEGORY_ID = "10000000-0000-4000-8000-000000000010"
        const val FILTER_DOLLAR_ID = "10000000-0000-4000-8000-000000000011"
        const val FILTER_INCOME_ID = "10000000-0000-4000-8000-000000000012"
        const val FILTER_PREVIOUS_ID = "10000000-0000-4000-8000-000000000013"
    }
}
