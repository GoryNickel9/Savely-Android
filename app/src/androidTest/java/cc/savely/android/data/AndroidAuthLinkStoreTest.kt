package cc.savely.android.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AndroidAuthLinkStoreTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("auth_link_fixture_$name", mode)
    }
    private val store = AndroidAuthLinkStore(context)
    private val ordinary = AndroidSessionStore(context)
    @After fun cleanup() { store.clearPending(); store.clearRecovery(); ordinary.clear() }

    @Test fun verifierAndRecoveryTokensAreEncryptedAndRestoredInNewStoreInstance() {
        val pending = PendingNativeAuth.create(NativeAuthPurpose.RECOVERY, "qa@example.test")
        val session = session()
        store.savePending(pending)
        store.saveRecovery(NativeRecoveryContext(session, System.currentTimeMillis() / 1000))
        val prefs = context.getSharedPreferences("savely_auth_links", Context.MODE_PRIVATE)
        val ciphertext = prefs.getString("pending_v1", "")!! + prefs.getString("recovery_v1", "")!!
        assertFalse(ciphertext.contains(pending.verifier)); assertFalse(ciphertext.contains(session.accessToken))
        assertFalse(ciphertext.contains(session.refreshToken))
        val recreated = AndroidAuthLinkStore(context)
        assertEquals(pending.verifier, recreated.pending()!!.verifier)
        assertEquals(pending.state, recreated.pending()!!.state)
        assertEquals(session, recreated.recovery()!!.session)
        assertNull(ordinary.load())
    }

    @Test fun corruptRecoveryFailsClosedAndPreservesIndependentNormalSessionAndPendingLink() {
        val session = session(); ordinary.save(session)
        val pending = PendingNativeAuth.create(NativeAuthPurpose.SIGNUP, "qa@example.test")
        store.savePending(pending)
        context.getSharedPreferences("savely_auth_links", Context.MODE_PRIVATE).edit().putString("recovery_v1", "corrupt").commit()
        try { store.recovery(); fail("Expected safe storage failure") }
        catch (error: ApiException) { assertEquals(ApiError.STORAGE, error.kind); assertFalse(error.message!!.contains("corrupt")) }
        assertNull(store.recovery()); assertEquals(session, ordinary.load())
        assertEquals(pending.state, store.pending()!!.state)
    }
    private fun session(): Session {
        val jwt = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"session_id\":\"11111111-1111-4111-8111-111111111111\"}".toByteArray())
        return Session("header.$jwt.signature", "synthetic-refresh", 1900000000, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "qa@example.test")
    }
}
