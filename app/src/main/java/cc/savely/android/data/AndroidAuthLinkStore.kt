package cc.savely.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** PKCE verifiers and pending recovery metadata are encrypted separately from the active session. */
class AndroidAuthLinkStore(context: Context) : AuthLinkStore {
    private val preferences = context.applicationContext.getSharedPreferences("savely_auth_links", Context.MODE_PRIVATE)
    @Synchronized override fun pending(): PendingNativeAuth? = readValue("pending_v1", PendingNativeAuth::fromJson)
    @Synchronized override fun recovery(): NativeRecoveryContext? = readValue("recovery_v1", NativeRecoveryContext::fromJson)
    @Synchronized override fun savePending(value: PendingNativeAuth) = write("pending_v1", value.toJson().toString())
    @Synchronized override fun saveRecovery(value: NativeRecoveryContext) = write("recovery_v1", value.toJson().toString())
    @Synchronized override fun clearPending() = remove("pending_v1")
    @Synchronized override fun clearRecovery() = remove("recovery_v1")

    private fun <T> readValue(name: String, decode: (org.json.JSONObject) -> T): T? {
        try {
            val encoded = preferences.getString(name, null) ?: return null
            require(encoded.length <= 16_384)
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            return decode(org.json.JSONObject(String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)))
        } catch (_: Exception) {
            remove(name)
            throw storageError()
        }
    }
    private fun write(name: String, value: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            if (!preferences.edit().putString(name, encoded).commit()) throw storageError()
        } catch (_: Exception) { throw storageError() }
    }
    private fun remove(name: String) {
        if (!preferences.edit().remove(name).commit()) throw storageError()
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun storageError() = ApiException(ApiError.STORAGE, "Richiesta email locale non leggibile. Richiedi una nuova email dall’app.")
    private companion object { const val KEY_ALIAS = "savely.auth.links.v1"; const val IV_BYTES = 12 }
}
