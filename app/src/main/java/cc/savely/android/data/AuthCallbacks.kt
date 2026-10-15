package cc.savely.android.data

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

enum class NativeAuthPurpose { SIGNUP, RECOVERY }

/** Opaque PKCE input. No raw URL, error description, access token or refresh token is retained. */
class NativeAuthCallback private constructor(val state: String, val code: String?, val failed: Boolean) {
    override fun toString(): String = "NativeAuthCallback(failed=$failed)"

    companion object {
        const val REDIRECT_BASE = "savely://auth/callback"
        fun parse(raw: String): NativeAuthCallback {
            try {
                require(raw.length in 1..4096 && raw.none { it <= ' ' || it == '\\' })
                val uri = URI(raw)
                require(uri.scheme == "savely" && uri.rawAuthority == "auth" && uri.rawPath == "/callback" && uri.rawFragment == null)
                val values = linkedMapOf<String, String>()
                val query = requireNotNull(uri.rawQuery)
                query.split('&').forEach { part ->
                    val pair = part.split('=', limit = 2)
                    require(pair.size == 2 && pair[0] in setOf("state", "code", "error", "error_code", "error_description"))
                    require(!values.containsKey(pair[0]))
                    values[pair[0]] = URLDecoder.decode(pair[1], Charsets.UTF_8.name())
                }
                val state = requireNotNull(values["state"])
                require(canonicalUuid(state))
                val code = values["code"]
                val failed = values.keys.any { it.startsWith("error") }
                require((code != null) != failed)
                if (code != null) require(Regex("[A-Za-z0-9_-]{20,1024}").matches(code))
                else require(!values["error"].isNullOrBlank() || !values["error_code"].isNullOrBlank())
                return NativeAuthCallback(state, code, failed)
            } catch (_: Exception) {
                throw invalidNativeAuthLink()
            }
        }
    }
}

internal fun canonicalUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
internal fun invalidNativeAuthLink() = ApiException(ApiError.VALIDATION, "Link non valido, scaduto o già utilizzato. Richiedi una nuova email dall’app.")

internal fun Session.authAssuranceLevel(): String = runCatching {
    JSONObject(String(Base64.getUrlDecoder().decode(accessToken.split('.')[1]), Charsets.UTF_8)).optString("aal", "aal1")
}.getOrDefault("aal1")

class PendingNativeAuth(
    val state: String, val purpose: NativeAuthPurpose, val email: String, val verifier: String, val createdAt: Long,
) {
    init {
        require(canonicalUuid(state) && email.length in 3..254 && Regex("[A-Za-z0-9_-]{43}").matches(verifier) && createdAt > 0)
    }
    val redirectUrl: String get() = "${NativeAuthCallback.REDIRECT_BASE}?state=$state"
    val challenge: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun isFresh(now: Long): Boolean = now >= createdAt && now - createdAt <= MAX_AGE_SECONDS
    override fun toString(): String = "PendingNativeAuth(purpose=$purpose)"
    internal fun toJson() = JSONObject().put("state", state).put("purpose", purpose.name).put("email", email)
        .put("verifier", verifier).put("created_at", createdAt)
    companion object {
        const val MAX_AGE_SECONDS = 3600L
        fun create(purpose: NativeAuthPurpose, email: String, now: Long = System.currentTimeMillis() / 1000): PendingNativeAuth {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return PendingNativeAuth(UUID.randomUUID().toString(), purpose, email.trim(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), now)
        }
        internal fun fromJson(value: JSONObject) = PendingNativeAuth(value.getString("state"), NativeAuthPurpose.valueOf(value.getString("purpose")),
            value.getString("email"), value.getString("verifier"), value.getLong("created_at"))
    }
}

/** Recovery is bound to the server session UUID, surviving token rotation without granting account navigation. */
class NativeRecoveryContext(val session: Session, val createdAt: Long) {
    val userId: String get() = session.userId
    val sessionId: String get() = requireNotNull(session.authSessionId())
    init { require(canonicalUuid(session.userId) && session.authSessionId() != null && createdAt > 0) }
    fun matches(session: Session): Boolean = session.userId == userId && session.authSessionId() == sessionId
    override fun toString(): String = "NativeRecoveryContext()"
    internal fun toJson() = JSONObject().put("session", session.toJson()).put("created_at", createdAt)
    companion object {
        internal fun fromJson(value: JSONObject) = NativeRecoveryContext(Session.fromJson(value.getJSONObject("session")), value.getLong("created_at"))
    }
}

interface AuthLinkStore {
    fun pending(): PendingNativeAuth?
    fun savePending(value: PendingNativeAuth)
    fun clearPending()
    fun recovery(): NativeRecoveryContext?
    fun saveRecovery(value: NativeRecoveryContext)
    fun clearRecovery()
}

class MemoryAuthLinkStore : AuthLinkStore {
    @Volatile private var pendingValue: PendingNativeAuth? = null
    @Volatile private var recoveryValue: NativeRecoveryContext? = null
    override fun pending() = pendingValue
    override fun savePending(value: PendingNativeAuth) { pendingValue = value }
    override fun clearPending() { pendingValue = null }
    override fun recovery() = recoveryValue
    override fun saveRecovery(value: NativeRecoveryContext) { recoveryValue = value }
    override fun clearRecovery() { recoveryValue = null }
}

class NativeAuthResult(val session: Session, val purpose: NativeAuthPurpose) {
    override fun toString(): String = "NativeAuthResult(purpose=$purpose)"
}
