package cc.savely.android.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferenceRecurringDismissalsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val firstUser = "20000000-0000-4000-8000-000000000001"
    private val secondUser = "20000000-0000-4000-8000-000000000002"

    @Before @After fun clearSyntheticFixture() {
        assertTrue(context.getSharedPreferences("finance_recurring_suggestions", Context.MODE_PRIVATE)
            .edit().remove(firstUser).remove(secondUser).commit())
    }

    @Test fun ignoredSuggestionSurvivesStoreRecreationAndStaysInItsAccount() {
        PreferenceRecurringDismissals(context).ignore(firstUser, "EUR:netflix")
        val recreated = PreferenceRecurringDismissals(context)
        assertEquals(setOf("EUR:netflix"), recreated.keys(firstUser))
        assertTrue(recreated.keys(secondUser).isEmpty())
        recreated.ignore(firstUser, "USD:gym")
        recreated.ignore(firstUser, "EUR:netflix")
        assertEquals(setOf("EUR:netflix", "USD:gym"), PreferenceRecurringDismissals(context).keys(firstUser))
    }
}
