package cc.savely.android.data

import org.json.JSONObject

/** Times use Unix seconds. Tokens must never be displayed or logged. */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val email: String,
) {
    override fun toString(): String = "Session(userId=$userId, expiresAt=$expiresAt)"

    internal fun toJson(): JSONObject = JSONObject()
        .put("access_token", accessToken)
        .put("refresh_token", refreshToken)
        .put("expires_at", expiresAt)
        .put("user", JSONObject().put("id", userId).put("email", email))

    companion object {
        internal fun fromJson(json: JSONObject, now: Long = System.currentTimeMillis() / 1000): Session {
            val user = json.getJSONObject("user")
            val access = json.getString("access_token")
            val refresh = json.getString("refresh_token")
            val id = user.getString("id")
            val expiry = if (json.has("expires_at")) json.getLong("expires_at")
            else now + json.getLong("expires_in")
            require(access.isNotBlank() && refresh.isNotBlank() && id.isNotBlank() && expiry > 0)
            return Session(access, refresh, expiry, id, user.optString("email", ""))
        }
    }
}

interface SessionStore {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
}

/** A session store for tests and hosts that do not need persistence. */
class MemorySessionStore(initial: Session? = null) : SessionStore {
    @Volatile private var value: Session? = initial
    override fun load(): Session? = value
    override fun save(session: Session) { value = session }
    override fun clear() { value = null }
}
