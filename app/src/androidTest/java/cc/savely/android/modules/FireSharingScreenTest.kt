package cc.savely.android.modules

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.util.Xml
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.savely.android.BuildConfig
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.ui.SavelyTheme
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Device preferences and native UI only: all API requests are rejected before transport. */
@RunWith(AndroidJUnit4::class)
class FireSharingScreenTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(90)
    @get:Rule(order = 1) val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val owner = UUID.randomUUID().toString()
    private val foreign = UUID.randomUUID().toString()
    private val baseline = FireParameters()
    private val foreignBaseline = FireParameters(FireCalculations.Inputs(currentSavings = 98765.43), 12345.0)
    private val requests = AtomicInteger()
    private lateinit var client: OkHttpClient
    private lateinit var store: MemorySessionStore
    private lateinit var api: SupabaseApi
    private lateinit var focus: FocusManager
    private var keyboard: SoftwareKeyboardController? = null
    private val visible = mutableStateOf(true)
    private var mounted = false
    private val fullLink get() = BuildConfig.WEB_URL.trimEnd('/') +
        "/fire/barista?age=31&retire=62&savings=125000&contrib=24000&income=60000&return=0.07&inflation=0.03&swr=0.04&expenses=30000&parttime=6000&portfolio=900000&years=35"

    @Before fun setup() {
        assertTrue(preferences(owner).edit().putString(FireParameters.STORAGE_KEY, baseline.encode()).commit())
        assertTrue(preferences(foreign).edit().putString(FireParameters.STORAGE_KEY, foreignBaseline.encode()).commit())
        client = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            throw IOException("FIRE sharing fixture forbids network requests")
        }.build()
        store = MemorySessionStore(session(owner))
        api = SupabaseApi(BackendConfig("https://fire-sharing.invalid", "public-test-key", BuildConfig.WEB_URL), store, client)
    }

    @After fun cleanup() {
        if (mounted) {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
        }
        assertTrue(preferences(owner).edit().clear().commit())
        assertTrue(preferences(foreign).edit().clear().commit())
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }

    @Test fun previewDoesNotWriteAndExplicitBaristaApplyPersistsAllTenValuesOnlyForCurrentAccount() {
        mount()
        openImport()
        replaceLink(fullLink)
        clickPreview()
        assertStored(baseline)
        for (text in listOf("Età attuale: 31", "Età pensionamento: 62", "Risparmi attuali (€): 125000",
            "Contributo annuo (€): 24000", "Reddito netto annuo (€): 60000", "Spese annue (€): 30000",
            "Rendimento nominale (%): 7", "Inflazione (%): 3", "Prelievo annuo (%): 4", "Reddito part-time annuo (€): 6000")) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Parametri non supportati: portfolio, years. Non verranno applicati.").performScrollTo().assertIsDisplayed()
        assertStored(baseline)
        touch(button("Applica configurazione"))
        val expected = FireParameters(FireCalculations.Inputs(currentAge = 31, retirementAge = 62,
            currentSavings = 125000.0, annualContribution = 24000.0, annualIncome = 60000.0,
            expectedReturn = 0.07, inflationRate = 0.03, withdrawalRate = 0.04, annualExpenses = 30000.0), 6000.0)
        await("Explicit import commits and closes") {
            runCatching { restored(owner) == expected && FireParameters.decode(diskSnapshot(owner)) == expected }.getOrDefault(false) &&
                compose.onAllNodesWithText("Importa configurazione FIRE").fetchSemanticsNodes().isEmpty()
        }
        list().performScrollToIndex(15)
        compose.onNodeWithText("Obiettivo Barista").assertIsDisplayed()
        compose.onNodeWithText(NumberFormat.getCurrencyInstance(Locale.ITALY).format(600000.0)).assertIsDisplayed()
        assertStored(expected)
    }

    @Test fun malformedInputAndChangingPastedUrlInvalidatePreviewWithoutPreferenceMutation() {
        mount()
        openImport()
        replaceLink(BuildConfig.WEB_URL.trimEnd('/') + "/fire/standard?age=30&age=31")
        clickPreview()
        compose.onNodeWithText("Il link contiene parametri FIRE duplicati").assertIsDisplayed()
        compose.onNode(button("Applica configurazione")).assertIsNotEnabled()
        assertStored(baseline)
        replaceLink(fullLink)
        clickPreview()
        compose.onNode(button("Applica configurazione")).assertIsEnabled()
        replaceLink(BuildConfig.WEB_URL.trimEnd('/') + "/fire/standard?expenses=-1")
        compose.onNode(button("Applica configurazione")).assertIsNotEnabled()
        compose.onNodeWithText("Anteprima della configurazione").assertDoesNotExist()
        clickPreview()
        compose.onNodeWithText("Importi negativi").assertIsDisplayed()
        compose.onNode(button("Applica configurazione")).assertIsNotEnabled()
        touch(button("Annulla"))
        assertStored(baseline)
    }

    @Test fun accountSwitchAfterPreviewRefusesApplyAndLeavesBothAccountsUnchanged() {
        mount()
        openImport()
        replaceLink(fullLink)
        clickPreview()
        compose.runOnIdle { store.save(session(foreign)) }
        touch(button("Applica configurazione"))
        await("Account mismatch is explicit") {
            compose.onAllNodesWithText("Account cambiato. Riapri FIRE per continuare.").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(button("Applica configurazione")).fetchSemanticsNodes().singleOrNull()
                    ?.config?.contains(SemanticsProperties.Disabled) == false
        }
        compose.onNodeWithText("Account cambiato. Riapri FIRE per continuare.").performScrollTo().assertIsDisplayed()
        assertStored(baseline)
        touch(button("Annulla"))
    }

    @Suppress("DEPRECATION")
    @Test fun realShareTouchEmitsPlainTextChooserWithFullWebConfigurationAndRejectsInvalidDraft() {
        mount()
        val captured = AtomicReference<Intent?>()
        val sends = AtomicInteger()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                captured.set(Intent(intent)); sends.incrementAndGet()
                // Intercept the chooser locally; do not start any recipient application.
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            sharingHeader("Condividi link FIRE")
            touch(button("Condividi link FIRE"))
            await("Share intent intercepted") { captured.get() != null }
            val chooser = checkNotNull(captured.get())
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val send = checkNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            val text = checkNotNull(send.getStringExtra(Intent.EXTRA_TEXT))
            val url = text.toHttpUrl()
            assertEquals("/fire/standard", url.encodedPath)
            assertEquals(10, url.queryParameterNames.size)
            assertEquals("0.07", url.queryParameter("return"))
            assertEquals("0.04", url.queryParameter("swr"))
            assertEquals("48000.0", url.queryParameter("expenses"))
            assertFalse(text.contains(owner)); assertFalse(text.contains("synthetic-access"))
            assertEquals(baseline, FireSharing.preview(BuildConfig.WEB_URL, text, foreignBaseline).parameters)
            clearFocus()
            list().performScrollToIndex(10)
            compose.onNodeWithText("Spese annue (€)").performTextReplacement("-1")
            sharingHeader("Condividi link FIRE")
            compose.onNode(button("Condividi link FIRE")).assertIsNotEnabled()
            assertEquals(1, sends.get())
            assertStored(baseline)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun mount() {
        compose.setContent {
            SavelyTheme("light") {
                val manager = LocalFocusManager.current
                val controller = LocalSoftwareKeyboardController.current
                SideEffect { focus = manager; keyboard = controller }
                if (visible.value) ModuleScreen(api, "fire", Modifier.fillMaxSize())
            }
        }
        mounted = true
        await("Account-local FIRE form ready") {
            compose.onAllNodesWithText("Ripristina valori di esempio").fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun openImport() { sharingHeader("Importa link FIRE"); touch(button("Importa link FIRE")) }
    private fun sharingHeader(label: String) {
        clearFocus(); list().performScrollToIndex(4)
        compose.onNode(button(label)).performScrollTo().assertIsDisplayed()
    }
    private fun replaceLink(value: String) {
        clearFocus()
        compose.onNodeWithText("Link FIRE condiviso").performScrollTo().performTextReplacement(value)
        clearFocus()
    }
    private fun clickPreview() {
        clearFocus()
        compose.onNode(button("Anteprima")).performScrollTo()
        touch(button("Anteprima"))
    }
    private fun clearFocus() { compose.runOnIdle { focus.clearFocus(force = true); keyboard?.hide() } }
    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun button(text: String) = hasText(text) and hasClickAction()
    private fun touch(matcher: SemanticsMatcher) {
        clearFocus()
        var previous: Rect? = null
        var stable = 0
        await("Stable touch $matcher") {
            compose.mainClock.advanceTimeByFrame()
            val node = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
            val bounds = node?.boundsInRoot
            val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().map { it.boundsInRoot }
            val scrolls = generateSequence(node?.parent) { it.parent }.filter {
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange) || it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
            }.map { it.boundsInRoot }
            val ready = node != null && !node.config.contains(SemanticsProperties.Disabled) && bounds != null &&
                bounds.width > 0 && bounds.height > 0 && roots.any { it.contains(bounds.center) } && scrolls.all { it.contains(bounds.center) }
            stable = if (ready && bounds == previous) stable + 1 else 0
            previous = bounds
            stable >= 2
        }
        compose.onNode(matcher).assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
    }
    private fun await(label: String, condition: () -> Boolean) {
        try { compose.waitUntil(15_000, condition) }
        catch (failure: ComposeTimeoutException) {
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            val dump = roots.fetchSemanticsNodes().indices.joinToString("\n") { index ->
                runCatching { roots[index].printToString() }.getOrElse { it.javaClass.simpleName }
            }
            throw AssertionError("FIRE sharing fixture: $label, clock=${compose.mainClock.currentTime}, requests=${requests.get()}\n" +
                dump, failure)
        }
    }
    private fun preferences(user: String) = context.getSharedPreferences(FireParameters.preferencesName(user), Context.MODE_PRIVATE)
    private fun restored(user: String) = FireParameters.decode(checkNotNull(preferences(user).getString(FireParameters.STORAGE_KEY, null)))
    private fun assertStored(expected: FireParameters) {
        assertEquals(expected, restored(owner)); assertEquals(expected, FireParameters.decode(diskSnapshot(owner)))
        assertEquals(foreignBaseline, restored(foreign)); assertEquals(foreignBaseline, FireParameters.decode(diskSnapshot(foreign)))
        assertEquals(0, requests.get())
    }
    private fun diskSnapshot(user: String): String {
        val file = File(context.applicationInfo.dataDir, "shared_prefs/${FireParameters.preferencesName(user)}.xml")
        file.inputStream().use { stream ->
            val parser = Xml.newPullParser().apply { setInput(stream, "UTF-8") }
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "string" &&
                    parser.getAttributeValue(null, "name") == FireParameters.STORAGE_KEY) return parser.nextText()
                parser.next()
            }
        }
        error("Synthetic FIRE preference snapshot missing")
    }
    private fun session(user: String) = Session("synthetic-access", "synthetic-refresh", Long.MAX_VALUE, user, "sharing@example.test")
}
