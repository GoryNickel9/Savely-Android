package cc.savely.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real access-gate composable; callbacks are local and no API or credential is involved. */
@RunWith(AndroidJUnit4::class)
class MfaGateScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun busyVerificationDisablesCodeSubmitAndExitThenWrongCodeAllowsExplicitRetry() {
        val state = mutableStateOf(AppState(initialized = true, mfaFactor = "synthetic-factor"))
        val submitted = mutableListOf<String>()
        var exits = 0
        compose.setContent {
            SavelyTheme("light") {
                MfaChallengeScreen(state.value, { code ->
                    submitted.add(code)
                    state.value = state.value.copy(busy = true, error = null)
                }, { exits++ })
            }
        }
        val code = compose.onNodeWithText("Codice a 6 cifre", substring = false)
        val verify = compose.onNode(hasText("Verifica", substring = false) and hasClickAction())
        val exit = compose.onNode(hasText("Esci", substring = false) and hasClickAction())
        code.performTextInput("000000")
        verify.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        code.assertIsNotEnabled()
        verify.assertIsNotEnabled()
        exit.assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("000000"), submitted)
            assertEquals(0, exits)
            state.value = state.value.copy(busy = false, error = "Codice di verifica non corretto o scaduto.")
        }
        compose.onNodeWithText("Codice di verifica non corretto o scaduto.").assertExists()
        code.assertIsEnabled().performTextReplacement("123456")
        verify.assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(listOf("000000", "123456"), submitted)
            assertEquals(0, exits)
            state.value = state.value.copy(busy = false)
        }
        exit.assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun recreationDoesNotRestoreAnOtpOrSubmitOrSignOutTheMfaGate() {
        val restoration = StateRestorationTester(compose)
        var submitted = 0
        var exits = 0
        restoration.setContent {
            SavelyTheme("light") {
                MfaChallengeScreen(AppState(initialized = true, mfaFactor = "synthetic-factor"),
                    { submitted++ }, { exits++ })
            }
        }
        compose.onNodeWithText("Codice a 6 cifre", substring = false).performTextInput("123456")
        compose.onNode(hasText("Verifica", substring = false) and hasClickAction()).assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Verifica in due passaggi").assertExists()
        compose.onNodeWithText("Codice a 6 cifre", substring = false)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNode(hasText("Verifica", substring = false) and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("Esci", substring = false) and hasClickAction()).assertIsEnabled()
        compose.runOnIdle { assertEquals(0, submitted); assertEquals(0, exits) }
    }
}
