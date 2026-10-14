package cc.savely.android.modules

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.savely.android.BuildConfig
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.MemorySessionStore
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import cc.savely.android.ui.LanguagePreferences
import cc.savely.android.ui.LocalizationProvider
import cc.savely.android.ui.SavelyTheme
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.IOException
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Whole production FIRE form in English. Every attempted HTTP request fails before transport. */
@RunWith(AndroidJUnit4::class)
class FireQualificationTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(120)
    @get:Rule(order = 1) val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val owner = UUID.randomUUID().toString()
    private val foreign = UUID.randomUUID().toString()
    private val language = context.getSharedPreferences("fire-v09-language-$owner", Context.MODE_PRIVATE)
    private val requests = AtomicInteger()
    private val client = OkHttpClient.Builder().addInterceptor {
        requests.incrementAndGet()
        throw IOException("Synthetic FIRE fixture forbids transport")
    }.build()
    private val api = SupabaseApi(BackendConfig("https://fire-v09.invalid", "fixture-public"),
        MemorySessionStore(Session("synthetic-access", "synthetic-refresh", Long.MAX_VALUE, owner, "fixture@example.test")), client)
    private val visible = mutableStateOf(true)
    private lateinit var focus: FocusManager
    private var keyboard: SoftwareKeyboardController? = null
    private var mounted = false
    private val baseline = FireParameters(FireCalculations.Inputs(currentAge = 30, retirementAge = 60,
        currentSavings = 100000.0, annualContribution = 24000.0, annualIncome = 60000.0,
        expectedReturn = .07, inflationRate = .03, withdrawalRate = .04, annualExpenses = 48000.0), 12000.0)
    private val foreignBaseline = FireParameters(FireCalculations.Inputs(currentSavings = 77777.0), 9999.0)

    @Before fun prepareSyntheticPreferences() {
        assertTrue(language.edit().putString("language", "en").commit())
        assertTrue(preferences(owner).edit().putString(FireParameters.STORAGE_KEY, baseline.encode()).commit())
        assertTrue(preferences(foreign).edit().putString(FireParameters.STORAGE_KEY, foreignBaseline.encode()).commit())
    }

    @After fun disposeBeforeClearingOnlySyntheticPreferences() {
        if (mounted) {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
        }
        api.close()
        assertTrue(preferences(owner).edit().clear().commit())
        assertTrue(preferences(foreign).edit().clear().commit())
        assertTrue(language.edit().clear().commit())
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        assertEquals(0, requests.get())
    }

    @Test fun wholeEnglishStandardFormShowsLiteralMetricsAndSelectableNominalRealProjection() {
        mount()
        listOf("Current age" to "30", "Retirement age" to "60", "Current savings (€)" to "100000.0",
            "Annual contribution (€)" to "24000.0", "Annual net income (€)" to "60000.0",
            "Annual expenses (€)" to "48000.0", "Nominal return (%)" to "7", "Inflation (%)" to "3",
            "Annual withdrawal (%)" to "4").forEach { (label, value) -> assertField(label, value) }
        metric("FIRE target", euro("1200000"))
        metric("Years to FIRE", "24,4")
        metric("Age at FIRE", "54,4")
        metric("Savings rate", "40,0%")
        metric("Monthly contribution", euro("2000"))
        metric("Coast FIRE", euro("382635"))
        list().performScrollToNode(hasTestTag("fire-projection"))
        compose.onNodeWithTag("fire-projection-real").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("fire-projection-real").performClick().assertIsOn()
        compose.onNodeWithTag("fire-projection-year").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithText("Selected year: ${LocalDate.now().year + 1} · age 31").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${euro("131000")} · real value ${euro("127184")}\nContribution ${euro("24000")} · total contributed ${euro("124000")}")
            .performScrollTo().assertIsDisplayed()
        assertStored(baseline)
    }

    @Test fun wholeEnglishBaristaTabKeepsTheTenInputsAndShowsExactCommonBaselineResults() {
        val barista = FireParameters(FireCalculations.Inputs(currentAge = 35, retirementAge = 60,
            currentSavings = 50000.0, annualContribution = 12000.0, annualIncome = 60000.0,
            expectedReturn = .06, inflationRate = .02, withdrawalRate = .04, annualExpenses = 36000.0), 12000.0)
        assertTrue(preferences(owner).edit().putString(FireParameters.STORAGE_KEY, barista.encode()).commit())
        mount()
        tab("Barista")
        assertField("Current age", "35")
        assertField("Annual part-time income (€)", "12000.0")
        assertField("Nominal return (%)", "6")
        metric("Barista target", euro("600000"))
        metric("Full FIRE target", euro("900000"))
        metric("Years to Barista FIRE", "24,3")
        metric("Part-time income required", euro("12000"))
        metric("Capital saved with part-time work", euro("300000"))
        tab("Standard")
        assertField("Annual expenses (€)", "36000.0")
        tab("Barista")
        assertField("Annual part-time income (€)", "12000.0")
        assertStored(barista)
    }

    @Test fun invalidEnglishDraftKeepsLastValidSnapshotAndExplicitResetReplacesOnlyThisAccount() {
        mount()
        field("Annual expenses (€)").performTextReplacement("abc")
        clearFocus()
        list().performScrollToIndex(3)
        compose.onNodeWithText("Incomplete or invalid values: the last valid configuration remains saved.")
            .performScrollTo().assertIsDisplayed()
        list().performScrollToNode(hasText("Enter a valid number"))
        compose.onNodeWithText("FIRE target").assertDoesNotExist()
        compose.onNodeWithTag("fire-projection").assertDoesNotExist()
        assertStored(baseline)
        list().performScrollToIndex(3)
        compose.onNode(hasText("Restore example values") and hasClickAction()).performScrollTo().performClick()
        awaitStored(FireParameters())
        assertField("Annual expenses (€)", "48000.0")
        assertField("Nominal return (%)", "7")
        assertStored(FireParameters())
    }

    @Test fun zeroReturnWithNoSavingsOrContributionShowsEnglishUnreachableStateWithoutInfiniteGraphics() {
        val unreachable = FireParameters(baseline.inputs.copy(currentSavings = 0.0, annualContribution = 0.0,
            expectedReturn = 0.0, inflationRate = 0.0))
        assertTrue(preferences(owner).edit().putString(FireParameters.STORAGE_KEY, unreachable.encode()).commit())
        mount()
        assertField("Nominal return (%)", "0")
        metric("FIRE target", euro("1200000"))
        metric("Years to FIRE", "Target cannot be reached in this model")
        metric("Age at FIRE", "Target cannot be reached in this model")
        list().performScrollToNode(hasTestTag("fire-projection"))
        compose.onNodeWithTag("fire-progress-bar").performScrollTo().assertExists()
        compose.onNodeWithTag("fire-projection-scale").performScrollTo().assertIsDisplayed()
        assertStored(unreachable)
    }

    @Test fun englishImportPreviewAndCancelDoNotWriteAndExplicitApplySwitchesToBarista() {
        mount()
        val link = BuildConfig.WEB_URL.trimEnd('/') +
            "/fire/barista?age=31&retire=62&savings=125000&contrib=24000&income=60000&return=0.07&inflation=0.03&swr=0.04&expenses=30000&parttime=6000&years=35"
        fun openPreview() {
            clearFocus()
            list().performScrollToIndex(4)
            compose.onNode(hasText("Import FIRE link") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText("Shared FIRE link").performScrollTo().performTextReplacement(link)
            clearFocus()
            compose.onNode(hasText("Preview") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText("Configuration preview").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Annual part-time income (€): 6000").performScrollTo().assertIsDisplayed()
            assertStored(baseline)
        }
        openPreview()
        compose.onNode(hasText("Cancel") and hasClickAction()).assertIsDisplayed().performClick()
        assertStored(baseline)
        openPreview()
        compose.onNode(hasText("Apply configuration") and hasClickAction()).assertIsDisplayed().performClick()
        val imported = FireParameters(FireCalculations.Inputs(currentAge = 31, retirementAge = 62,
            currentSavings = 125000.0, annualContribution = 24000.0, annualIncome = 60000.0,
            expectedReturn = .07, inflationRate = .03, withdrawalRate = .04, annualExpenses = 30000.0), 6000.0)
        awaitStored(imported)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Import FIRE configuration").fetchSemanticsNodes().isEmpty() }
        metric("Barista target", euro("600000"))
        metric("Full FIRE target", euro("750000"))
        assertField("Annual part-time income (€)", "6000.0")
        assertStored(imported)
    }

    private fun mount() {
        compose.setContent {
            LocalizationProvider(LanguagePreferences(language)) {
                SavelyTheme("light") {
                    val manager = LocalFocusManager.current
                    val controller = LocalSoftwareKeyboardController.current
                    SideEffect { focus = manager; keyboard = controller }
                    if (visible.value) ModuleScreen(api, "fire", Modifier.fillMaxSize())
                }
            }
        }
        mounted = true
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Restore example values").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun clearFocus() = compose.runOnIdle { focus.clearFocus(force = true); keyboard?.hide() }
    private fun tab(label: String) {
        clearFocus()
        list().performScrollToIndex(1)
        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick().assertIsSelected()
    }
    private fun field(label: String): SemanticsNodeInteraction {
        clearFocus()
        val index = FIELDS.indexOf(label)
        require(index >= 0)
        list().performScrollToIndex(5 + index)
        return compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().assertIsDisplayed()
    }
    private fun assertField(label: String, value: String) = field(label).assert(
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    private fun metric(label: String, literal: String) {
        clearFocus()
        list().performScrollToNode(hasText(label))
        compose.onNode(hasText(literal) and hasAnySibling(hasText(label))).performScrollTo().assertIsDisplayed()
    }
    private fun preferences(user: String) = context.getSharedPreferences(FireParameters.preferencesName(user), Context.MODE_PRIVATE)
    private fun restored(user: String) = FireParameters.decode(checkNotNull(preferences(user).getString(FireParameters.STORAGE_KEY, null)))
    private fun awaitStored(expected: FireParameters) = compose.waitUntil(15_000) { restored(owner) == expected }
    private fun assertStored(expected: FireParameters) {
        assertEquals(expected, restored(owner))
        assertEquals(foreignBaseline, restored(foreign))
        assertEquals(0, requests.get())
    }
    private fun euro(literal: String) = NumberFormat.getCurrencyInstance(Locale.ITALY).format(BigDecimal(literal))

    companion object {
        private val FIELDS = listOf("Current age", "Retirement age", "Current savings (€)", "Annual contribution (€)",
            "Annual net income (€)", "Annual expenses (€)", "Nominal return (%)", "Inflation (%)",
            "Annual withdrawal (%)", "Annual part-time income (€)")
    }
}
