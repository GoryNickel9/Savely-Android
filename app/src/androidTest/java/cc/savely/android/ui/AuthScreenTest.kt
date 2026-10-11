package cc.savely.android.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AuthScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loginValidationAndSubmittedCredentials() {
        var submitted = ""
        compose.setContent {
            SavelyTheme { AuthScreen(AppState(initialized = true),
                { email, password -> submitted = "$email/$password" }, { _, _, _ -> }, {}, {}) }
        }
        compose.onAllNodesWithText("Accedi").filterToOne(hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Email").performTextInput("test@example.com")
        compose.onNodeWithText("Password", substring = false).performTextInput("test123456")
        compose.onAllNodesWithText("Accedi").filterToOne(hasClickAction()).performClick()
        assertEquals("test@example.com/test123456", submitted)
    }

    @Test fun passwordRecoveryOnlyNeedsEmailAndDoesNotSignIn() {
        var requestedEmail = ""
        compose.setContent {
            SavelyTheme("light") { AuthScreen(AppState(initialized = true), { _, _ -> error("Unexpected login") },
                { _, _, _ -> }, { requestedEmail = it }, {}) }
        }
        compose.onNodeWithText("Password dimenticata?").performClick()
        compose.onNodeWithText("Email").performTextInput("test@example.com")
        compose.onNodeWithText("Invia email di recupero").performClick()
        assertEquals("test@example.com", requestedEmail)
    }

    @Test fun mfaRequiresSixDigits() {
        var submitted = ""
        compose.setContent { SavelyTheme { MfaChallengeScreen(AppState(), { submitted = it }, {}) } }
        compose.onNodeWithText("Verifica", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("Codice a 6 cifre").performTextInput("123456")
        compose.onNodeWithText("Verifica", substring = false).performClick()
        assertEquals("123456", submitted)
    }

    @Test fun restorationKeepsEmailButClearsPassword() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { SavelyTheme { AuthScreen(AppState(initialized = true), { _, _ -> }, { _, _, _ -> }, {}, {}) } }
        compose.onNodeWithText("Email").performTextInput("test@example.com")
        compose.onNodeWithText("Password", substring = false).performTextInput("temporary-password")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Email").assertTextContains("test@example.com")
        compose.onNodeWithText("Password", substring = false)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onAllNodesWithText("Accedi").filterToOne(hasClickAction()).assertIsNotEnabled()
    }

    @Test fun signupRequiresStrongMatchingConfirmationAndDoesNotRestoreEitherPassword() {
        var submitted = ""
        val restoration = StateRestorationTester(compose)
        restoration.setContent { SavelyTheme { AuthScreen(AppState(initialized = true), { _, _ -> },
            { _, password, _ -> submitted = password }, {}, {}) } }
        compose.onNodeWithText("Crea un account").performClick()
        compose.onNodeWithText("Email").performScrollTo().performTextInput("test@example.com")
        compose.onNodeWithText("Password", substring = false).performScrollTo().performTextInput("weak")
        compose.onNodeWithText("Conferma password", substring = false).performScrollTo().performTextInput("weak")
        compose.onNodeWithText("Crea account", substring = false).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Password", substring = false).performScrollTo().performTextReplacement("StrongPassword12!")
        compose.onNodeWithText("Crea account", substring = false).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Conferma password", substring = false).performScrollTo().performTextReplacement("StrongPassword12!")
        compose.onNodeWithText("Crea account", substring = false).performScrollTo().assertIsEnabled().performClick()
        assertEquals("StrongPassword12!", submitted)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Password", substring = false)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Conferma password", substring = false)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Crea account", substring = false).performScrollTo().assertIsNotEnabled()
    }
}
