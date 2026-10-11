package cc.savely.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM ciphertext in private preferences; the encryption key stays in Android Keystore. */
class AndroidSessionStore(context: Context) : SessionStore {
    private val preferences = context.applicationContext.getSharedPreferences("savely_session", Context.MODE_PRIVATE)

    @Synchronized override fun load(): Session? {
        val encoded = preferences.getString("session_v1", null) ?: return null
        try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
            cipher.updateAAD(AAD)
            val json = String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
            return Session.fromJson(JSONObject(json))
        } catch (_: Exception) {
            // A restore onto another device or an invalidated key cannot recover the session.
            clear()
            throw ApiException(ApiError.STORAGE, "Sessione locale non leggibile. Accedi di nuovo.")
        }
    }

    @Synchronized override fun save(session: Session) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD)
            val encrypted = cipher.iv + cipher.doFinal(session.toJson().toString().toByteArray(Charsets.UTF_8))
            if (!preferences.edit().putString("session_v1", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
                throw ApiException(ApiError.STORAGE, "Impossibile salvare la sessione locale.")
            }
        } catch (error: ApiException) {
            throw error
        } catch (_: Exception) {
            throw ApiException(ApiError.STORAGE, "Impossibile salvare la sessione locale.")
        }
    }

    @Synchronized override fun clear() {
        if (!preferences.edit().remove("session_v1").commit()) {
            throw ApiException(ApiError.STORAGE, "Impossibile cancellare la sessione locale.")
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "savely.session.v1"
        const val IV_BYTES = 12
        val AAD = "savely.session.v1".toByteArray(Charsets.UTF_8)
    }
}
