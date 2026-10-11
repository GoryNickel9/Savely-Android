package cc.savely.android.modules

import android.content.Context
import android.util.Xml
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.ui.SavelyTheme
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Real Android disk storage and native UI, with every network request rejected before transport.
 * Recreation disposes the composition and builds a fresh API/session store; it does not kill the process.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class FirePersistenceTest {
    private val effectDispatcher = StandardTestDispatcher()
    // Queue I/O resumptions for the test clock's UI-thread traversal, instead of running them unconfined.
    @get:Rule val compose = createComposeRule(effectContext = effectDispatcher)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val accounts = mutableListOf<String>()
    private val requests = AtomicInteger()
    private val client = OkHttpClient.Builder().addInterceptor {
        requests.incrementAndGet()
        throw IOException("FIRE persistence tests forbid network requests")
    }.build()
    private val selectedApi = mutableStateOf<SupabaseApi?>(null)
    private val generation = mutableStateOf(0)
    private var contentMounted = false
    private lateinit var focusManager: FocusManager
    private var keyboardController: SoftwareKeyboardController? = null
    private var resetTouchDiagnostic = "No reset touch dispatched"

    @After fun cleanupSyntheticAccounts() {
        if (contentMounted) {
            compose.runOnIdle { selectedApi.value = null }
            compose.waitForIdle()
        }
        accounts.forEach { user ->
            assertTrue(preferences(user).edit().clear().commit())
        }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        assertEquals("Local FIRE restore/edit/reset must not contact any API", 0, requests.get())
    }

    @Test fun allTenParametersCommitToDiskAndRestoreInFreshScreenAndSessionStore() {
        val user = account()
        mount(user)
        waitForSaved(user, FireParameters())
        listOf(
            "Età attuale" to "38", "Età pensionamento" to "61",
            "Risparmi attuali (€)" to "153421.75", "Contributo annuo (€)" to "18600",
            "Reddito netto annuo (€)" to "63000", "Spese annue (€)" to "44400",
            "Rendimento nominale (%)" to "6.25", "Inflazione (%)" to "2.5",
            "Prelievo annuo (%)" to "3.75",
        ).forEach { (label, value) -> replace(label, value) }
        barista()
        replace("Reddito part-time annuo (€)", "17400")
        val expected = FireParameters(FireCalculations.Inputs(38, 61, 153421.75, 18600.0,
            63000.0, 0.0625, 0.025, 0.0375, 44400.0), 17400.0)
        waitForSaved(user, expected)
        assertEquals(expected, FireParameters.decode(diskSnapshot(user)))

        recreate(user)
        assertField("Età attuale", "38")
        assertField("Età pensionamento", "61")
        assertField("Risparmi attuali (€)", "153421.75")
        assertField("Contributo annuo (€)", "18600.0")
        assertField("Reddito netto annuo (€)", "63000.0")
        assertField("Spese annue (€)", "44400.0")
        assertField("Rendimento nominale (%)", "6.25")
        assertField("Inflazione (%)", "2.5")
        assertField("Prelievo annuo (%)", "3.75")
        barista()
        assertField("Reddito part-time annuo (€)", "17400.0")
        assertEquals(expected, restored(user))
    }

    @Test fun switchingAccountsAndResetNeverOverwriteAnotherAccountsParameters() {
        val first = account()
        val second = account()
        val firstSnapshot = FireParameters(inputs = FireCalculations.Inputs(currentSavings = 123456.0))
        val secondSnapshot = FireParameters(inputs = FireCalculations.Inputs(currentSavings = 654321.0))
        mount(first)
        replace("Risparmi attuali (€)", "123456")
        waitForSaved(first, firstSnapshot)
        recreate(second)
        assertField("Risparmi attuali (€)", "100000.0")
        replace("Risparmi attuali (€)", "654321")
        waitForSaved(second, secondSnapshot)
        assertEquals(firstSnapshot, restored(first))
        recreate(first)
        assertField("Risparmi attuali (€)", "123456.0")
        reset()
        waitForSaved(first, FireParameters())
        recreate(first)
        assertField("Risparmi attuali (€)", "100000.0")
        assertEquals(secondSnapshot, restored(second))
        recreate(second)
        assertField("Risparmi attuali (€)", "654321.0")
    }

    @Test fun corruptOrUnsupportedSnapshotsStayUntouchedUntilExplicitReset() {
        listOf("{broken", "{\"version\":99,\"params\":{}}").forEachIndexed { index, raw ->
            val user = account()
            assertTrue(preferences(user).edit().putString(FireParameters.STORAGE_KEY, raw).commit())
            if (index == 0) mount(user) else recreate(user)
            warning().assertIsDisplayed()
            assertField("Età attuale", "30")
            replace("Risparmi attuali (€)", "222222")
            compose.waitForIdle()
            assertEquals(raw, preferences(user).getString(FireParameters.STORAGE_KEY, null))
            recreate(user)
            warning().assertIsDisplayed()
            assertField("Risparmi attuali (€)", "100000.0")
            assertEquals(raw, diskSnapshot(user))
            reset()
            waitForSaved(user, FireParameters())
            compose.onNodeWithText(WARNING).assertDoesNotExist()
            recreate(user)
            assertField("Risparmi attuali (€)", "100000.0")
            assertEquals(FireParameters(), restored(user))
        }
    }

    @Test fun incompleteOrInvalidDraftKeepsLastValidSnapshotAcrossRecreation() {
        val user = account()
        val valid = FireParameters(inputs = FireCalculations.Inputs(currentSavings = 77777.0))
        mount(user)
        replace("Risparmi attuali (€)", "77777")
        waitForSaved(user, valid)
        replace("Risparmi attuali (€)", "-1")
        header(STATUS_INDEX, "Valori incompleti o non validi: resta salvata l'ultima configurazione valida.")
            .assertIsDisplayed()
        assertEquals(valid, restored(user))
        field("Risparmi attuali (€)").performTextClearance()
        compose.waitForIdle()
        assertEquals(valid, FireParameters.decode(diskSnapshot(user)))
        recreate(user)
        assertField("Risparmi attuali (€)", "77777.0")
    }

    @Test fun standardResultsFollowEveryValidDraftAndRestoreWithoutCalculate() {
        val user = account()
        mount(user)
        replace("Prelievo annuo (%)", "4")
        replace("Spese annue (€)", "30000")
        assertMetric(STANDARD_RESULT_INDEX, "Obiettivo FIRE", "750000")
        replace("Spese annue (€)", "24000")
        assertMetric(STANDARD_RESULT_INDEX, "Obiettivo FIRE", "600000")
        replace("Risparmi attuali (€)", "120000")
        header(STANDARD_RESULT_INDEX + 7, "${LocalDate.now().year} · 30 anni")
        compose.onNodeWithText("${euro("120000")} · valore reale ${euro("120000")}").assertIsDisplayed()
        val expected = FireParameters(inputs = FireCalculations.Inputs(
            currentSavings = 120000.0, annualExpenses = 24000.0))
        waitForSaved(user, expected)
        recreate(user)
        assertMetric(STANDARD_RESULT_INDEX, "Obiettivo FIRE", "600000")

        replace("Spese annue (€)", "abc")
        assertNoCurrentResults(STANDARD_RESULT_INDEX, "Inserisci un numero valido")
        assertEquals(expected, restored(user))
        assertEquals(expected, FireParameters.decode(diskSnapshot(user)))
        recreate(user)
        assertMetric(STANDARD_RESULT_INDEX, "Obiettivo FIRE", "600000")
        reset()
        waitForSaved(user, FireParameters())
        assertMetric(STANDARD_RESULT_INDEX, "Obiettivo FIRE", "1200000")
        compose.onNodeWithText("Calcola").assertDoesNotExist()
    }

    @Test fun baristaResultsFollowExpensesAndPartTimeAndSuppressInvalidDraft() {
        val user = account()
        mount(user)
        barista()
        replace("Prelievo annuo (%)", "4")
        replace("Spese annue (€)", "30000")
        replace("Reddito part-time annuo (€)", "12000")
        assertMetric(BARISTA_RESULT_INDEX, "Obiettivo Barista", "450000")
        assertMetric(BARISTA_RESULT_INDEX + 1, "Obiettivo FIRE completo", "750000")
        replace("Spese annue (€)", "24000")
        assertMetric(BARISTA_RESULT_INDEX, "Obiettivo Barista", "300000")
        assertMetric(BARISTA_RESULT_INDEX + 1, "Obiettivo FIRE completo", "600000")
        val expected = FireParameters(inputs = FireCalculations.Inputs(annualExpenses = 24000.0),
            partTimeIncome = 12000.0)
        waitForSaved(user, expected)

        replace("Reddito part-time annuo (€)", "-1")
        assertNoCurrentResults(BARISTA_RESULT_INDEX, "Reddito part-time non valido")
        assertEquals(expected, restored(user))
        assertEquals(expected, FireParameters.decode(diskSnapshot(user)))
        recreate(user)
        assertMetric(STANDARD_RESULT_INDEX, "Obiettivo FIRE", "600000")
        barista()
        assertMetric(BARISTA_RESULT_INDEX, "Obiettivo Barista", "300000")
        assertMetric(BARISTA_RESULT_INDEX + 1, "Obiettivo FIRE completo", "600000")
        reset()
        waitForSaved(user, FireParameters())
        assertMetric(BARISTA_RESULT_INDEX, "Obiettivo Barista", "700000")
        assertMetric(BARISTA_RESULT_INDEX + 1, "Obiettivo FIRE completo", "1200000")
    }

    private fun assertMetric(index: Int, label: String, literalAmount: String) {
        header(index, label)
        compose.onNode(hasText(euro(literalAmount)) and hasAnySibling(hasText(label))).assertIsDisplayed()
    }
    private fun assertNoCurrentResults(index: Int, validationMessage: String) {
        // At the old first-result position the invalid form ends with its validation error.
        header(index, validationMessage).assertIsDisplayed()
        listOf("Obiettivo FIRE", "Obiettivo Barista", "Obiettivo FIRE completo", "Proiezione annuale")
            .forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }
    // Formatting literal expectations does not reuse the calculator or derive the expected targets.
    private fun euro(literalAmount: String) = NumberFormat.getCurrencyInstance(Locale.ITALY)
        .format(BigDecimal(literalAmount))

    private fun account() = "fire-device-test-${UUID.randomUUID()}".also { accounts.add(it) }
    private fun preferences(user: String) = context.createPackageContext(context.packageName, 0)
        .getSharedPreferences(FireParameters.preferencesName(user), Context.MODE_PRIVATE)
    private fun restored(user: String) = FireParameters.decode(
        checkNotNull(preferences(user).getString(FireParameters.STORAGE_KEY, null)))
    private fun api(user: String) = SupabaseApi(BackendConfig("https://fire-test.invalid", "public-test-key"),
        MemorySessionStore(Session("synthetic-access", "synthetic-refresh", Long.MAX_VALUE, user,
            "fire-device@example.test")), client)

    private fun mount(user: String) {
        compose.runOnUiThread { selectedApi.value = api(user) }
        compose.setContent {
            SavelyTheme("light") {
                val manager = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                SideEffect { focusManager = manager; keyboardController = keyboard }
                key(generation.value) {
                    selectedApi.value?.let { ModuleScreen(it, "fire", Modifier.fillMaxSize()) }
                }
            }
        }
        contentMounted = true
        waitForForm()
    }

    private fun recreate(user: String) {
        compose.runOnIdle {
            selectedApi.value = api(user)
            generation.value++
        }
        waitForForm()
    }

    private fun waitForForm() {
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Ripristina valori di esempio").fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun waitForSaved(user: String, expected: FireParameters) {
        clearInputFocus()
        list().performScrollToIndex(STATUS_INDEX)
        try {
            compose.waitUntil(10000) {
                drainEffects()
                val committed = runCatching {
                    restored(user) == expected && FireParameters.decode(diskSnapshot(user)) == expected
                }
                    .getOrDefault(false)
                // Disk commit finishes before the I/O coroutine resumes and publishes the UI success state.
                committed && compose.onAllNodesWithText("Parametri salvati sul dispositivo per questo account.")
                    .fetchSemanticsNodes().isNotEmpty() &&
                    compose.onNodeWithText("Parametri salvati sul dispositivo per questo account.").isDisplayed()
            }
        } catch (failure: ComposeTimeoutException) {
            // These diagnostics contain only the ten non-secret FIRE parameters and this synthetic screen.
            val stored = runCatching { restored(user).toString() }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            val disk = runCatching { FireParameters.decode(diskSnapshot(user)).toString() }
                .getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            val semantics = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }
                .getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            throw AssertionError("FIRE save timeout\n$resetTouchDiagnostic\nexpected=$expected\nstoredDecoded=$stored\ndiskDecoded=$disk\n$semantics", failure)
        }
        compose.onNodeWithText("Parametri salvati sul dispositivo per questo account.").assertIsDisplayed()
    }
    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun drainEffects() {
        // Launches queued by rememberCoroutineScope must start even before they publish snapshot changes.
        compose.runOnUiThread { effectDispatcher.scheduler.runCurrent() }
    }
    private fun clearInputFocus() {
        compose.runOnIdle {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }
    private fun displayed(label: String): SemanticsNodeInteraction {
        compose.waitUntil(10000) {
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() &&
                compose.onNodeWithText(label).isDisplayed()
        }
        return compose.onNodeWithText(label).assertIsDisplayed()
    }
    private fun header(index: Int, label: String): SemanticsNodeInteraction {
        clearInputFocus()
        list().performScrollToIndex(index)
        drainEffects()
        var previousBounds: Rect? = null
        var previousViewport: Rect? = null
        var stableFrames = 0
        try {
            compose.waitUntil(10000) {
                drainEffects()
                compose.mainClock.advanceTimeByFrame()
                if (compose.onAllNodesWithText(label).fetchSemanticsNodes().isEmpty()) {
                    stableFrames = 0
                    false
                } else {
                    val bounds = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
                    val viewport = list().fetchSemanticsNode().boundsInRoot
                    val inside = bounds.width > 0 && bounds.height > 0 && viewport.contains(bounds.center)
                    stableFrames = if (inside && bounds == previousBounds && viewport == previousViewport) stableFrames + 1 else 0
                    previousBounds = bounds
                    previousViewport = viewport
                    stableFrames >= 2
                }
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("FIRE header did not settle: label=$label, clock=${compose.mainClock.currentTime}, bounds=$previousBounds, viewport=$previousViewport", failure)
        }
        return compose.onNodeWithText(label).assertIsDisplayed()
    }
    private fun field(label: String): SemanticsNodeInteraction {
        val index = FIELD_LABELS.indexOf(label)
        require(index >= 0) { "Unknown FIRE field: $label" }
        clearInputFocus()
        // The form has five fixed header items. Index scrolling avoids Compose's unbounded ScrollBy search.
        list().performScrollToIndex(FIRST_FIELD_INDEX + index)
        return displayed(label)
    }
    private fun replace(label: String, value: String) {
        field(label).performTextReplacement(value)
    }
    private fun assertField(label: String, value: String) {
        field(label).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(value)))
    }
    private fun barista() {
        header(TABS_INDEX, "Barista").performClick()
    }
    private fun reset() {
        val button = header(STATUS_INDEX, "Ripristina valori di esempio").assertIsEnabled()
        val bounds = button.fetchSemanticsNode().boundsInRoot
        val viewport = list().fetchSemanticsNode().boundsInRoot
        resetTouchDiagnostic = "Reset touch: clock=${compose.mainClock.currentTime}, bounds=$bounds, center=${bounds.center}, viewport=$viewport"
        assertTrue(resetTouchDiagnostic, viewport.contains(bounds.center))
        try { button.performClick() }
        catch (failure: Exception) { throw AssertionError(resetTouchDiagnostic, failure) }
        drainEffects()
    }
    private fun warning() = header(STATUS_INDEX, WARNING)

    /** Read committed XML, rather than trusting the in-process SharedPreferences cache alone. */
    private fun diskSnapshot(user: String): String {
        val file = File(context.applicationInfo.dataDir,
            "shared_prefs/${FireParameters.preferencesName(user)}.xml")
        file.inputStream().use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "string" &&
                    parser.getAttributeValue(null, "name") == FireParameters.STORAGE_KEY) return parser.nextText()
            }
        }
        error("Committed FIRE snapshot missing")
    }

    companion object {
        private const val TABS_INDEX = 1
        private const val STATUS_INDEX = 3
        private const val FIRST_FIELD_INDEX = 5
        private const val STANDARD_RESULT_INDEX = FIRST_FIELD_INDEX + 9
        private const val BARISTA_RESULT_INDEX = FIRST_FIELD_INDEX + 10
        private val FIELD_LABELS = listOf("Età attuale", "Età pensionamento", "Risparmi attuali (€)",
            "Contributo annuo (€)", "Reddito netto annuo (€)", "Spese annue (€)", "Rendimento nominale (%)",
            "Inflazione (%)", "Prelievo annuo (%)", "Reddito part-time annuo (€)")
        private const val WARNING = "Parametri locali non leggibili o versione non supportata. Sono mostrati valori di esempio; usa Ripristina per sostituire il salvataggio."
    }
}
