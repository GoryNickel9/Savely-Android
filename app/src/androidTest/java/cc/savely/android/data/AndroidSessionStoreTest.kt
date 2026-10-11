package cc.savely.android.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class AndroidSessionStoreTest {
    // applicationContext must preserve the isolated namespace used by the production store.
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("session_store_test_$name", mode)
    }
    private val store = AndroidSessionStore(context)

    @After fun clear() { store.clear() }

    @Test fun encryptedRoundTripAndLogout() {
        val session = Session("test-access-token", "test-refresh-token", 1900000000, "test-user", "test@example.com")
        store.save(session)
        assertEquals(session, store.load())
        val ciphertext = context.getSharedPreferences("savely_session", Context.MODE_PRIVATE).getString("session_v1", "")!!
        assertFalse(ciphertext.contains(session.accessToken))
        assertFalse(ciphertext.contains(session.refreshToken))
        store.clear()
        assertNull(store.load())
    }

    @Test fun corruptCiphertextCannotBecomeASession() {
        context.getSharedPreferences("savely_session", Context.MODE_PRIVATE).edit().putString("session_v1", "not-ciphertext").commit()
        assertTrue(runCatching { store.load() }.exceptionOrNull() is ApiException)
        assertNull(store.load())
    }
}
