package cc.savely.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Pure presentation/Intent adapter tests: never starts a configured backend client. */
class PasswordRecoveryScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun passwordPolicyConfirmationAndExplicitCancellationControlSubmission() {
        var submissions = 0
        var cancellations = 0
        var received = ""
        compose.setContent { PasswordRecoveryScreen(AppState(initialized = true, recovery = true), { password, confirm ->
            assertEquals(password, confirm); submissions++; received = password
        }, { cancellations++ }) }
        compose.onNodeWithText("Salva nuova password").assertIsNotEnabled()
        compose.onNodeWithText("Nuova password", substring = false).performTextInput("weak")
        compose.onNodeWithText("Conferma password", substring = false).performTextInput("weak")
        compose.onNodeWithText("Salva nuova password").assertIsNotEnabled()
        compose.onNodeWithText("Nuova password", substring = false).performTextReplacement(PASSWORD)
        compose.onNodeWithText("Le password non coincidono.").assertIsDisplayed()
        compose.onNodeWithText("Conferma password", substring = false).performTextReplacement(PASSWORD)
        compose.onNodeWithText("Salva nuova password").performClick()
        compose.runOnIdle { assertEquals(1, submissions); assertEquals(PASSWORD, received); assertEquals(0, cancellations) }
        compose.onNodeWithText("Annulla recupero ed esci").performClick()
        compose.runOnIdle { assertEquals(1, cancellations) }
    }

    @Test fun busyRecoveryPreventsSecondSubmitAndCancellation() {
        compose.setContent { PasswordRecoveryScreen(AppState(initialized = true, busy = true, recovery = true),
            { _, _ -> fail("Busy operation submitted") }, { fail("Busy operation cancelled") }) }
        compose.onNodeWithText("Nuova password", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("Conferma password", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("Attendi…").assertIsNotEnabled()
        compose.onNodeWithText("Annulla recupero ed esci").assertIsNotEnabled()
    }

    @Test fun coldAndWarmIntentDeliverySanitizesBeforePublishAndPreservesNewestEvent() {
        val inbox = AuthCallbackInbox()
        var current = Intent(Intent.ACTION_VIEW, Uri.parse(URL))
        deliverAuthCallback(current, { assertNull(inbox.events.value); current = Intent(Intent.ACTION_MAIN) }, inbox)
        assertNull(current.data); assertEquals(Intent.ACTION_MAIN, current.action)
        val cold = inbox.events.value!!
        // The same retained inbox receives the warm Activity callback.
        current = Intent(Intent.ACTION_VIEW, Uri.parse(URL))
        deliverAuthCallback(current, { current = Intent(Intent.ACTION_MAIN) }, inbox)
        val warm = inbox.events.value!!
        assertNotEquals(cold.id, warm.id)
        inbox.consume(cold.id); assertSame(warm, inbox.events.value)
        inbox.consume(warm.id); assertNull(inbox.events.value)
        deliverAuthCallback(Intent(Intent.ACTION_MAIN), { fail("Launcher should not be sanitized") }, inbox)
        assertNull(inbox.events.value)
    }
    private companion object {
        const val PASSWORD = "SyntheticPassword123!"
        const val URL = "savely://auth/callback?state=11111111-1111-4111-8111-111111111111&code=22222222-2222-4222-8222-222222222222"
    }
}
