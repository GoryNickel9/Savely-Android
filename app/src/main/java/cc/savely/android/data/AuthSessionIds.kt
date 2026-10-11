package cc.savely.android.data

import org.json.JSONObject
import java.util.Base64
import java.util.UUID

private val AUTH_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

internal fun canonicalAuthSessionId(value: String?): String? = value?.takeIf(AUTH_UUID::matches)?.let { UUID.fromString(it).toString() }

/** Metadata only: this unverified decode never grants permissions or replaces backend token verification. */
fun Session.authSessionId(): String? = runCatching {
    val parts = accessToken.split('.')
    if (parts.size != 3 || parts[1].length > 32_768) return null
    val payload = ExactJson.parse(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)) as? JSONObject ?: return null
    canonicalAuthSessionId(payload.opt("session_id") as? String)
}.getOrNull()

/** get_active_sessions.id is a login_activity row ID, never a revocable Auth session ID. */
fun revocableSessionId(row: JSONObject): String? = canonicalAuthSessionId(row.opt("session_id") as? String)
