package cc.savely.android.modules

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.savely.android.ui.SavelyTheme
import org.junit.Rule
import org.junit.Test

class FireProjectionScreenTest {
    @get:Rule val compose = createComposeRule()
    private val points = listOf(FireCalculations.Projection(30, 2026, 1000.0, 1000.0, 1000.0, 1000.0),
        FireCalculations.Projection(31, 2027, 1200.0, 100.0, 1100.0, 1165.0))

    @Test fun surplusProgressKeepsTheActualPercentageButCapsTheAccessibleProgressBar() {
        compose.setContent { SavelyTheme("light") { FireProjectionChart(points, 1500.0, 1000.0) } }
        compose.onNodeWithText("150,0% dell’obiettivo", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("fire-progress-bar").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f, 0f..1f)))
        compose.onNodeWithTag("fire-projection-year").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithText("Anno selezionato: 2027 · 31 anni").assertIsDisplayed()
        compose.onNodeWithText("totale versato", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("fire-projection-real").performClick().assertIsOff()
    }

    @Test fun invalidProjectionReportsAnErrorWithoutRenderingInfiniteGeometry() {
        compose.setContent { SavelyTheme { FireProjectionChart(points.map { it.copy(portfolio = Double.POSITIVE_INFINITY) }, 1000.0, 2000.0) } }
        compose.onNodeWithText("Proiezione non rappresentabile: controlla importi e tassi.").assertIsDisplayed()
        compose.onNodeWithTag("fire-progress-bar").assertDoesNotExist()
        compose.onNodeWithTag("fire-projection-year").assertDoesNotExist()
    }
}
