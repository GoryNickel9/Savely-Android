package cc.savely.android.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File

/** Local UI only: no API client, backend, screenshot-equivalence or launcher claims. */
class SavelyNavigationAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeout = Timeout.seconds(90)
    private var storage: SharedPreferences? = null

    @After fun cleanup() { storage?.edit()?.clear()?.commit() }

    @Test fun fiveWebDestinationsUseRealTouchAndMoreKeepsTheCurrentRoute() {
        var route by mutableStateOf("dashboard")
        var theme by mutableStateOf("light")
        var moreOpened = 0
        compose.setContent {
            SavelyTheme(theme) {
                Column {
                    SavelyTopBar(destinations.firstOrNull { it.route == route }?.label ?: "Savely") {}
                    SavelyDrawerItem("Versione e aggiornamenti", selected = false) {}
                    SavelyBottomNavigation(route, { route = it }, { moreOpened++ })
                }
            }
        }
        compose.onNodeWithTag("nav-dashboard").assertIsSelected().assertHasClickAction()
        listOf("dashboard" to "Dashboard", "transactions" to "Transazioni", "budget" to "Budget",
            "settings" to "Impostazioni", "more" to "Altro").forEach { (target, label) ->
            compose.onNodeWithTag("nav-$target").assertTextEquals(label).assertIsDisplayed().assertHasClickAction()
        }
        compose.onNodeWithTag("nav-budget").performClick()
        compose.onNodeWithTag("nav-budget").assertIsSelected()
        compose.onNodeWithTag("nav-dashboard").assertIsNotSelected()
        screenshot("roadmap-ui-nav-light.png")
        compose.onNodeWithTag("nav-more").performClick()
        compose.runOnIdle { assertEquals("budget", route); assertEquals(1, moreOpened); route = "fire" }
        compose.onNodeWithTag("nav-more").assertIsSelected()
        compose.onNodeWithTag("nav-budget").assertIsNotSelected()
        clickNativeMoreButton()
        compose.waitUntil(10_000) { compose.runOnIdle { moreOpened == 2 } }
        compose.runOnIdle { assertEquals("fire", route); assertEquals(2, moreOpened) }
        compose.runOnIdle { route = "budget"; theme = "dark" }
        compose.onNodeWithTag("nav-budget").assertIsSelected()
        compose.onNodeWithTag("screen-title").assertTextEquals("Budget")
        screenshot("roadmap-ui-nav-dark.png")
    }

    @Test fun labelsReflowAtTwoHundredPercentOnSmallWidthAcrossLanguageAndTheme() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferencesStorage = context.getSharedPreferences("navigation-large-font-fixture", Context.MODE_PRIVATE)
        storage = preferencesStorage
        preferencesStorage.edit().clear().commit()
        val preferences = LanguagePreferences(preferencesStorage)
        preferences.write(AppLanguage.ITALIAN)
        var theme by mutableStateOf("light")
        var changeLanguage: (AppLanguage) -> Unit = {}
        var density = 1f
        var tapped = ""
        compose.setContent {
            LocalizationProvider(preferences) {
                val actual = LocalDensity.current
                val languageChange = LocalLanguageChange.current
                SideEffect { density = actual.density; changeLanguage = languageChange }
                CompositionLocalProvider(LocalDensity provides Density(actual.density, fontScale = 2f)) {
                    SavelyTheme(theme) {
                        Column(Modifier.width(320.dp)) {
                            SavelyTopBar("Versione e aggiornamenti") {}
                            SavelyDrawerItem("Versione e aggiornamenti", selected = false) { tapped = "release" }
                            SavelyBottomNavigation("fire", { tapped = it }, { tapped = "more" })
                        }
                    }
                }
            }
        }
        assertLabelsFit(density)
        compose.onNodeWithTag("screen-title").assertTextEquals("Versione e aggiornamenti")
        screenshot("roadmap-ui-nav-font200-320dp.png")
        compose.onNodeWithTag("nav-settings").performClick()
        compose.runOnIdle { assertEquals("settings", tapped); theme = "dark"; changeLanguage(AppLanguage.ENGLISH) }
        compose.onNodeWithTag("screen-title").assertTextEquals("Version and updates")
        compose.onNodeWithTag("nav-more").assertTextEquals("More")
        assertLabelsFit(density)
        compose.onNodeWithTag("nav-more").performClick()
        compose.runOnIdle { assertEquals("more", tapped) }
        assertEquals(AppLanguage.ENGLISH, preferences.read())
    }

    @Test fun semanticTextAndFilledPrimaryKeepReadableContrastInBothThemes() {
        var mode by mutableStateOf("light")
        var checks: () -> Unit = {}
        compose.setContent {
            SavelyTheme(mode) {
                val colors = MaterialTheme.colorScheme
                val type = MaterialTheme.typography
                val shapes = MaterialTheme.shapes
                SideEffect { checks = {
                    assertTrue(ColorUtils.calculateContrast(colors.onSurface.toArgb(), colors.surface.toArgb()) >= 4.5)
                    assertTrue(ColorUtils.calculateContrast(colors.onSurfaceVariant.toArgb(), colors.surfaceVariant.toArgb()) >= 4.5)
                    assertTrue(ColorUtils.calculateContrast(colors.onPrimary.toArgb(), colors.primary.toArgb()) >= 4.5)
                    assertTrue(ColorUtils.calculateContrast(colors.onPrimaryContainer.toArgb(), colors.primaryContainer.toArgb()) >= 4.5)
                    assertEquals(16f, type.bodyMedium.fontSize.value, 0f)
                    assertNotEquals(type.bodyMedium.fontFamily, type.headlineMedium.fontFamily)
                    assertEquals(androidx.compose.foundation.shape.RoundedCornerShape(12.dp), shapes.large)
                } }
            }
        }
        compose.runOnIdle { checks(); mode = "dark" }
        compose.runOnIdle { checks() }
    }

    private fun assertLabelsFit(density: Float) {
        listOf("dashboard", "transactions", "budget", "settings", "more").forEach { target ->
            val node = compose.onNodeWithTag("nav-$target").assertIsDisplayed().fetchSemanticsNode()
            assertTrue("$target touch width", node.boundsInRoot.width / density >= 48f)
            assertTrue("$target touch height", node.boundsInRoot.height / density >= 48f)
            assertTextHasNoOverflow("nav-label-$target")
        }
        assertTextHasNoOverflow("screen-title")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithText(if (storage?.getString("language", null) == "en") "Version and updates" else "Versione e aggiornamenti")
            .filterToOne(hasClickAction()).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        assertTrue("drawer text layout available", layouts.isNotEmpty())
        layouts.forEach { assertFalse("drawer label must reflow", it.hasVisualOverflow) }
    }

    /** Selected Role.Tab can expose Compose click semantics while hiding native ACTION_CLICK. */
    private fun clickNativeMoreButton() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expectedPackage = "cc.savely.android"
        assertEquals(expectedPackage, instrumentation.targetContext.packageName)
        var button: AccessibilityNodeInfo? = null
        try {
            compose.waitUntil(10_000) {
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@waitUntil false
                try {
                    if (root.packageName?.toString() != expectedPackage) return@waitUntil false
                    val matches = root.findAccessibilityNodeInfosByText("Altro")
                    try {
                        for (label in matches) {
                            if (label.text?.toString() != "Altro" && label.contentDescription?.toString() != "Altro") continue
                            var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(label)
                            try {
                                for (depth in 0..8) {
                                    val node = current ?: break
                                    if (node.packageName?.toString() != expectedPackage) break
                                    val bounds = Rect().also(node::getBoundsInScreen)
                                    if (node.className?.toString() == "android.widget.Button" && node.isClickable &&
                                        node.isEnabled && node.isVisibleToUser && !bounds.isEmpty &&
                                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                                        button = AccessibilityNodeInfo.obtain(node)
                                        break
                                    }
                                    if (depth == 8) break
                                    current = node.parent
                                    node.recycle()
                                }
                            } finally { current?.recycle() }
                            if (button != null) break
                        }
                        button != null
                    } finally { matches.forEach { it.recycle() } }
                } finally { root.recycle() }
            }
            val node = requireNotNull(button)
            assertEquals(expectedPackage, node.packageName?.toString())
            assertEquals("android.widget.Button", node.className?.toString())
            assertTrue(node.isClickable && node.isEnabled && node.isVisibleToUser)
            assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            assertTrue("Selected More must accept the native accessibility click", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } finally { button?.recycle() }
    }

    private fun assertTextHasNoOverflow(tag: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        assertTrue("$tag text layout available", layouts.isNotEmpty())
        layouts.forEach { assertFalse("$tag must reflow", it.hasVisualOverflow) }
    }

    private fun screenshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(context.cacheDir, name)
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            output.outputStream().use { assertTrue("PNG fixture capture", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        assertTrue("Screenshot fixture must contain bytes", output.length() > 0)
    }
}
