package cc.savely.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A non-secret revision identifies the invalidated login, including across token rotations. */
@ConsistentCopyVisibility
data class SessionInvalidation internal constructor(val ownerRevision: Long, val message: String?)

/** Direct Supabase Auth/PostgREST client. Authorization remains enforced by backend RLS. */
class SupabaseApi(
    private val config: BackendConfig,
    private val sessionStore: SessionStore,
    client: OkHttpClient = OkHttpClient(),
    private val authLinks: AuthLinkStore = MemoryAuthLinkStore(),
) {
    // Never follow a redirect carrying tokens to another origin or retry a write automatically.
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(45, TimeUnit.SECONDS).build()
    private val authMutex = Mutex()
    private val auditScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var auditJob: Job? = null
    private val auditLock = Any()
    private var auditEpoch = 0L
    private var auditOwner: String? = null
    private val mutableAccessAuditWarning = MutableStateFlow<String?>(null)
    val accessAuditWarning = mutableAccessAuditWarning.asStateFlow()
    private val sessionStateLock = Any()
    private var ownerRevision = 0L
    private var ownerIdentity: Pair<String, String?>? = null
    private var ownerActive = false
    private val mutableSessionInvalidation = MutableStateFlow<SessionInvalidation?>(null)
    val sessionInvalidation = mutableSessionInvalidation.asStateFlow()
    private val base: HttpUrl
    private val web: HttpUrl
    private val webApi: HttpUrl

    init {
        config.validate()
        base = config.backendUrl()
        web = config.websiteUrl()
        webApi = config.websiteApiUrl()
    }

    suspend fun signIn(email: String, password: String): Session = authMutex.withLock {
        validateCredentials(email, password)
        val json = objectValue(request(endpoint(base, "auth/v1/token", "grant_type=password"), "POST",
            JSONObject().put("email", email.trim()).put("password", password), config.anonKey))
        parseSession(json).also { store(it, newLogin = true); startAccessAudit(it) }
    }

    /** Returns a fixed display status. Email confirmation currently uses the existing web callback. */
    suspend fun signUp(email: String, password: String, fullName: String = ""): String = authMutex.withLock {
        validateCredentials(email, password)
        PasswordPolicy.validate(password)
        if (publicRpc("get_registrations_enabled", JSONObject()) == false) {
            throw ApiException(ApiError.PERMISSION, "Le registrazioni sono temporaneamente disabilitate.")
        }
        val redirect = web.newBuilder().addPathSegments("auth/callback").build().toString()
        val url = endpoint(base, "auth/v1/signup").newBuilder().addQueryParameter("redirect_to", redirect).build()
        val body = JSONObject().put("email", email.trim()).put("password", password)
            .put("data", JSONObject().put("full_name", fullName.trim()))
        val result = objectValue(request(url, "POST", body, config.anonKey))
        if (result.has("access_token") && !result.isNull("access_token")) {
            val created = parseSession(result)
            store(created, newLogin = true)
            startAccessAudit(created)
            "Registrazione completata."
        } else {
            "Controlla la tua email per confermare la registrazione."
        }
    }

    /** The existing branded email endpoint redirects to /reset-password on the website. */
    suspend fun requestPasswordReset(email: String) {
        validateEmail(email)
        webPost("/api/reset-password", JSONObject().put("email", email.trim()), authenticated = false)
    }

    /** Native email requests use PKCE. The existing web methods remain available to Admin. */
    suspend fun signUpNative(email: String, password: String, fullName: String = ""): String = authMutex.withLock {
        validateCredentials(email, password)
        PasswordPolicy.validate(password)
        ensureUnsigned()
        if (publicRpc("get_registrations_enabled", JSONObject()) == false)
            throw ApiException(ApiError.PERMISSION, "Le registrazioni sono temporaneamente disabilitate.")
        val pending = PendingNativeAuth.create(NativeAuthPurpose.SIGNUP, email)
        withContext(Dispatchers.IO) { authLinks.savePending(pending) }
        val url = endpoint(base, "auth/v1/signup").newBuilder().addQueryParameter("redirect_to", pending.redirectUrl).build()
        val result = objectValue(request(url, "POST", pkceBody(pending).put("password", password)
            .put("data", JSONObject().put("full_name", fullName.trim())), config.anonKey))
        if (result.has("access_token") && !result.isNull("access_token")) {
            val created = parseSession(result)
            verifyNativeIdentity(created, pending.email)
            store(created, newLogin = true)
            startAccessAudit(created)
            "Registrazione completata."
        } else "Controlla la tua email e apri il link su questo dispositivo per confermare la registrazione."
    }

    suspend fun requestNativePasswordReset(email: String) = authMutex.withLock {
        validateEmail(email)
        ensureUnsigned()
        val pending = PendingNativeAuth.create(NativeAuthPurpose.RECOVERY, email)
        withContext(Dispatchers.IO) { authLinks.savePending(pending) }
        val url = endpoint(base, "auth/v1/recover").newBuilder().addQueryParameter("redirect_to", pending.redirectUrl).build()
        request(url, "POST", pkceBody(pending), config.anonKey)
        Unit
    }

    private fun pkceBody(pending: PendingNativeAuth) = JSONObject().put("email", pending.email)
        .put("code_challenge", pending.challenge).put("code_challenge_method", "s256")

    private suspend fun ensureUnsigned() {
        if (load() != null) throw ApiException(ApiError.VALIDATION, "Esci dall’account prima di richiedere un nuovo link email.")
    }

    /** Consume before exchange: an ambiguous response must never trigger another code redemption. */
    suspend fun completeNativeCallback(callback: NativeAuthCallback): NativeAuthResult = authMutex.withLock {
        val pending = withContext(Dispatchers.IO) { authLinks.pending() } ?: throw invalidNativeAuthLink()
        if (pending.state != callback.state) throw invalidNativeAuthLink()
        ensureUnsigned()
        withContext(Dispatchers.IO) { authLinks.clearPending() }
        if (callback.failed || !pending.isFresh(System.currentTimeMillis() / 1000)) throw invalidNativeAuthLink()
        val json = try {
            objectValue(request(endpoint(base, "auth/v1/token", "grant_type=pkce"), "POST",
                JSONObject().put("auth_code", callback.code).put("code_verifier", pending.verifier), config.anonKey))
        } catch (error: ApiException) {
            if (error.kind in setOf(ApiError.AUTHENTICATION, ApiError.PERMISSION) || error.status == 400) throw invalidNativeAuthLink()
            throw error
        }
        val created = parseSession(json)
        try { verifyNativeIdentity(created, pending.email) }
        catch (error: Exception) {
            // This token belongs to the just-redeemed link, never to an existing login.
            try { request(endpoint(base, "auth/v1/logout", "scope=local"), "POST", JSONObject(), created.accessToken) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ApiException) { /* No local credentials were installed. */ }
            throw error
        }
        if (pending.purpose == NativeAuthPurpose.RECOVERY) {
            withContext(Dispatchers.IO) { authLinks.saveRecovery(NativeRecoveryContext(created, System.currentTimeMillis() / 1000)) }
            observeSession(created, newLogin = true)
        } else {
            store(created, newLogin = true)
            startAccessAudit(created)
        }
        NativeAuthResult(created, pending.purpose)
    }

    private suspend fun verifyNativeIdentity(session: Session, expectedEmail: String) {
        if (!canonicalUuid(session.userId) || session.authSessionId() == null || session.expiresAt <= System.currentTimeMillis() / 1000)
            throw invalidNativeAuthLink()
        val user = objectValue(request(endpoint(base, "auth/v1/user"), token = session.accessToken))
        if (user.optString("id") != session.userId || !user.optString("email").equals(expectedEmail, ignoreCase = true) ||
            !session.email.equals(expectedEmail, ignoreCase = true) || user.isNull("email_confirmed_at") ||
            user.optString("email_confirmed_at").isBlank()) throw invalidNativeAuthLink()
    }

    suspend fun isPasswordRecovery(session: Session): Boolean = authMutex.withLock {
        withContext(Dispatchers.IO) { sessionStore.load() == null && authLinks.recovery()?.matches(session) == true }
    }

    suspend fun completePasswordRecovery(password: String, confirmation: String): Session = authMutex.withLock {
        PasswordPolicy.validate(password)
        if (password != confirmation) throw ApiException(ApiError.VALIDATION, "Le password non coincidono.")
        val context = withContext(Dispatchers.IO) { authLinks.recovery() } ?: throw invalidNativeAuthLink()
        val saved = load() ?: throw invalidNativeAuthLink()
        if (!context.matches(saved) || withContext(Dispatchers.IO) { sessionStore.load() } != null) throw invalidNativeAuthLink()
        val current = if (saved.expiresAt <= System.currentTimeMillis() / 1000 + REFRESH_MARGIN_SECONDS) refresh(saved) else saved
        val user = objectValue(request(endpoint(base, "auth/v1/user"), token = current.accessToken))
        val factors = user.optJSONArray("factors") ?: JSONArray()
        val needsMfa = (0 until factors.length()).any { factors.optJSONObject(it)?.optString("status") == "verified" }
        if (needsMfa && current.authAssuranceLevel() != "aal2")
            throw ApiException(ApiError.VALIDATION, "Verifica il codice della tua app di autenticazione prima di cambiare password.")
        request(endpoint(base, "auth/v1/user"), "PUT", JSONObject().put("password", password), current.accessToken)
        // Promotion follows a confirmed password update; an interrupted local write stays gated.
        withContext(Dispatchers.IO) { sessionStore.save(current); authLinks.clearRecovery() }
        observeSession(current)
        startAccessAudit(current)
        current
    }

    /** Cancels only the bound recovery session. A different normal login is preserved. */
    suspend fun cancelPasswordRecovery(): Boolean = authMutex.withLock {
        val context = withContext(Dispatchers.IO) { authLinks.recovery() } ?: return@withLock false
        val ordinary = withContext(Dispatchers.IO) { sessionStore.load() }
        if (ordinary != null) return@withLock false
        try {
            request(endpoint(base, "auth/v1/logout", "scope=local"), "POST", JSONObject(), context.session.accessToken)
        } catch (error: ApiException) {
            if (error.status !in listOf(401, 403)) throw error
        } finally {
            withContext(Dispatchers.IO) { authLinks.clearRecovery() }
            invalidateObservedSession(null)
        }
        true
    }

    /** Revoke the current session; local credentials are cleared even if the server is unreachable. */
    suspend fun signOut() = authMutex.withLock {
        cancelPendingAudit()
        withContext(Dispatchers.IO) {
            val saved = load()
            try {
                if (saved != null) {
                    try {
                        request(endpoint(base, "auth/v1/logout", "scope=local"), "POST", JSONObject(), saved.accessToken)
                    } catch (error: ApiException) {
                        if (error.status != 401 && error.status != 403) throw error
                    }
                }
            } finally {
                clearStoredSession()
            }
        }
    }

    suspend fun session(): Session? = authMutex.withLock {
        val saved = load() ?: return@withLock null
        if (saved.expiresAt > System.currentTimeMillis() / 1000 + REFRESH_MARGIN_SECONDS) saved
        else refresh(saved)
    }

    suspend fun select(table: String, query: String = ""): JSONArray {
        identifier(table)
        return arrayValue(authorized(endpoint(base, "rest/v1/$table", query)))
    }

    suspend fun insert(table: String, body: JSONObject): JSONObject {
        identifier(table)
        return singleRow(authorized(endpoint(base, "rest/v1/$table"), "POST", insertPayload(table, body), representation = true))
    }

    suspend fun update(table: String, id: String, body: JSONObject, query: String = ""): JSONObject {
        identifier(table)
        return singleRow(authorized(rowEndpoint(table, id, query), "PATCH", body, representation = true))
    }

    suspend fun delete(table: String, id: String, query: String = "") {
        identifier(table)
        val rows = arrayValue(authorized(rowEndpoint(table, id, query), "DELETE", representation = true))
        if (rows.length() != 1) throw ApiException(ApiError.PERMISSION, "Il record non è disponibile o non può essere eliminato.")
    }

    /** Soft-deleted rows can become invisible to SELECT; count verifies the mutation without selecting them. */
    suspend fun updateMinimal(table: String, id: String, body: JSONObject, query: String = "") {
        identifier(table)
        authorized(rowEndpoint(table, id, query), "PATCH", body, exactMutation = true)
    }

    /** Scalar, JSON object, JSON array or JSONObject.NULL, exactly as returned by the RPC. */
    suspend fun rpc(name: String, body: JSONObject = JSONObject()): Any {
        identifier(name)
        return authorized(endpoint(base, "rest/v1/rpc/$name"), "POST", body)
    }

    suspend fun profile(): JSONObject? {
        val current = requireSession()
        val url = endpoint(base, "rest/v1/profiles").newBuilder()
            .addQueryParameter("select", "*").addQueryParameter("user_id", "eq.${current.userId}").build()
        val rows = arrayValue(authorized(url))
        if (rows.length() > 1) throw ApiException(ApiError.RESPONSE, "Il profilo contiene dati incoerenti.")
        return rows.optJSONObject(0)
    }

    suspend fun permissions(): Map<String, Boolean> = parsePermissions(profile()?.optJSONObject("permissions"))

    suspend fun updateProfile(body: JSONObject): JSONObject {
        val current = requireSession()
        val url = endpoint(base, "rest/v1/profiles").newBuilder()
            .addQueryParameter("user_id", "eq.${current.userId}").build()
        return singleRow(authorized(url, "PATCH", body, representation = true))
    }

    suspend fun updateAccount(email: String? = null, password: String? = null): JSONObject = authMutex.withLock {
        password?.let(PasswordPolicy::validate)
        val saved = load() ?: throw unauthorized()
        val current = if (saved.expiresAt <= System.currentTimeMillis() / 1000 + REFRESH_MARGIN_SECONDS) refresh(saved) else saved
        val body = JSONObject()
        email?.let { validateEmail(it); body.put("email", it.trim()) }
        password?.let {
            body.put("password", it)
        }
        if (body.length() == 0) throw ApiException(ApiError.VALIDATION, "Inserisci i dati da modificare.")
        val result = objectValue(request(endpoint(base, "auth/v1/user"), "PUT", body, current.accessToken))
        store(current.copy(email = result.optString("email", current.email)))
        result
    }

    /** Returns true only when this revocation also clears the currently stored session. */
    suspend fun revokeSession(sessionId: String): Boolean {
        val id = canonicalAuthSessionId(sessionId) ?: throw ApiException(ApiError.VALIDATION, "ID della sessione non valido.")
        rpc("delete_session", JSONObject().put("p_session_id", id))
        return authMutex.withLock {
            val saved = load()
            if (saved?.authSessionId() == id) {
                cancelPendingAudit()
                clearStoredSession()
                true
            } else false
        }
    }

    fun dismissAccessAuditWarning() = synchronized(auditLock) { mutableAccessAuditWarning.value = null }

    /** Cancels device registration and invalidates any result from a preceding account. */
    fun cancelPendingAudit() = synchronized(auditLock) {
        auditEpoch++
        auditOwner = null
        auditJob?.cancel()
        auditJob = null
        mutableAccessAuditWarning.value = null
    }

    /** The owning ViewModel calls this when its API instance is no longer needed. */
    fun close() { cancelPendingAudit(); auditScope.cancel() }

    private fun startAccessAudit(session: Session) {
        cancelPendingAudit()
        val epoch = synchronized(auditLock) { auditOwner = session.userId; auditEpoch }
        val sessionId = session.authSessionId()
        if (sessionId == null) {
            publishAuditWarning(epoch, session)
            return
        }
        val job = auditScope.launch {
            try {
                // A session-specific agent keeps the server's DISTINCT(user_agent) from hiding another Android login.
                val body = JSONObject().put("user_id", session.userId).put("event_type", "sign_in")
                    .put("session_id", sessionId).put("user_agent", "Savely Android · session $sessionId")
                request(endpoint(base, "rest/v1/login_activity"), "POST", body, session.accessToken)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publishAuditWarning(epoch, session) }
        }
        synchronized(auditLock) {
            if (auditEpoch == epoch && auditOwner == session.userId) auditJob = job else job.cancel()
        }
    }

    private fun publishAuditWarning(epoch: Long, session: Session) = synchronized(auditLock) {
        if (auditEpoch == epoch && auditOwner == session.userId) mutableAccessAuditWarning.value = ACCESS_AUDIT_WARNING
    }

    /** Only the configured site's /api endpoints are reachable; no administrative key is used. */
    suspend fun webPost(path: String, body: JSONObject, authenticated: Boolean = true): JSONObject {
        if (!WEB_ENDPOINT.matches(path)) throw ApiException(ApiError.VALIDATION, "Endpoint non valido.")
        val url = endpoint(webApi, path.removePrefix("/"))
        return objectValue(if (authenticated) authorized(url, "POST", body, backend = false)
        else request(url, "POST", body, token = null, backend = false))
    }

    suspend fun invokeFunction(name: String, body: JSONObject = JSONObject()): Any {
        identifier(name, allowHyphen = true)
        return authorized(endpoint(base, "functions/v1/$name"), "POST", body)
    }

    suspend fun getFunction(name: String, query: String = ""): Any {
        identifier(name, allowHyphen = true)
        return authorized(endpoint(base, "functions/v1/$name", query))
    }

    // Access gates only need verified factors; settings also need unfinished enrollments.
    suspend fun listMfaFactors(includeUnverified: Boolean = false): JSONArray {
        val user = objectValue(authorized(endpoint(base, "auth/v1/user")))
        val factors = user.optJSONArray("factors") ?: JSONArray()
        return JSONArray().apply {
            for (index in 0 until factors.length()) {
                val factor = factors.optJSONObject(index) ?: continue
                val status = factor.optString("status")
                if (factor.optString("factor_type") == "totp" &&
                    (status == "verified" || includeUnverified && status == "unverified")) put(factor)
            }
        }
    }

    suspend fun enrollTotp(): JSONObject = objectValue(authorized(endpoint(base, "auth/v1/factors"), "POST",
        JSONObject().put("factor_type", "totp").put("friendly_name", "Savely Android")))

    suspend fun verifyTotp(factorId: String, code: String): Session = authMutex.withLock {
        validateId(factorId)
        val cleaned = code.replace(Regex("[\\s-]"), "")
        if (!Regex("[0-9]{6}").matches(cleaned)) throw ApiException(ApiError.VALIDATION, "Il codice deve essere di 6 cifre.")
        val saved = load() ?: throw unauthorized()
        val current = if (saved.expiresAt <= System.currentTimeMillis() / 1000 + REFRESH_MARGIN_SECONDS) refresh(saved) else saved
        val challenge = objectValue(request(endpoint(base, "auth/v1/factors/$factorId/challenge"), "POST", JSONObject(), current.accessToken))
        val challengeId = challenge.optString("id")
        if (challengeId.isBlank()) throw responseError()
        val verified = objectValue(request(endpoint(base, "auth/v1/factors/$factorId/verify"), "POST",
            JSONObject().put("challenge_id", challengeId).put("code", cleaned), current.accessToken))
        parseSession(verified).also { store(it) }
    }

    suspend fun unenrollTotp(factorId: String) {
        validateId(factorId)
        authorized(endpoint(base, "auth/v1/factors/$factorId"), "DELETE")
    }

    suspend fun unenrollPendingTotp(factorId: String) {
        validateId(factorId)
        val factors = listMfaFactors(includeUnverified = true)
        val pending = (0 until factors.length()).map { factors.getJSONObject(it) }
            .firstOrNull { it.optString("id") == factorId }
        if (pending?.optString("status") != "unverified") {
            throw ApiException(ApiError.VALIDATION, "La configurazione non è più incompleta. Aggiorna sicurezza prima di continuare.")
        }
        unenrollTotp(factorId)
    }

    private suspend fun publicRpc(name: String, body: JSONObject): Any = request(endpoint(base, "rest/v1/rpc/$name"), "POST", body, config.anonKey)

    private suspend fun authorized(
        url: HttpUrl, method: String = "GET", body: JSONObject? = null,
        representation: Boolean = false, backend: Boolean = true, exactMutation: Boolean = false,
    ): Any {
        val current = requireSession()
        try {
            return request(url, method, body, current.accessToken, backend, representation, exactMutation)
        } catch (error: ApiException) {
            if (error.status != 401) throw error
            val renewed = authMutex.withLock {
                val saved = load() ?: throw unauthorized()
                if (saved.accessToken != current.accessToken) saved else refresh(saved)
            }
            // A write is retried only after an explicit 401, never after a timeout or 5xx.
            return request(url, method, body, renewed.accessToken, backend, representation, exactMutation)
        }
    }

    private suspend fun requireSession(): Session = session() ?: throw unauthorized()

    private suspend fun refresh(saved: Session): Session {
        try {
            val json = objectValue(request(endpoint(base, "auth/v1/token", "grant_type=refresh_token"), "POST",
                JSONObject().put("refresh_token", saved.refreshToken), config.anonKey))
            return parseSession(json).also { store(it) }
        } catch (error: ApiException) {
            if (error.status in listOf(400, 401, 403) && error.code in INVALID_REFRESH_CODES) {
                cancelPendingAudit()
                clearStoredSession(unauthorized().message)
                throw unauthorized()
            }
            throw error
        }
    }

    /** These checks do not refresh or issue HTTP requests. A newer login invalidates old events. */
    fun sessionOwnerRevision(): Long? = synchronized(sessionStateLock) { ownerRevision.takeIf { ownerActive } }
    internal fun sessionOwnerRevision(session: Session): Long? = synchronized(sessionStateLock) {
        ownerRevision.takeIf { ownerActive && ownerIdentity == (session.userId to session.authSessionId()) }
    }
    fun isSessionOwnerCurrent(revision: Long): Boolean = synchronized(sessionStateLock) { ownerActive && ownerRevision == revision }
    fun isSessionInvalidationCurrent(event: SessionInvalidation): Boolean = synchronized(sessionStateLock) {
        !ownerActive && ownerRevision == event.ownerRevision && mutableSessionInvalidation.value === event
    }

    // Keep the check and UI publication atomic with a login/clear on the I/O dispatcher.
    internal fun withCurrentSessionOwner(revision: Long, update: () -> Unit): Unit = synchronized(sessionStateLock) {
        if (isSessionOwnerCurrent(revision)) update()
    }
    internal fun withCurrentSessionInvalidation(event: SessionInvalidation, update: () -> Unit): Unit = synchronized(sessionStateLock) {
        if (isSessionInvalidationCurrent(event)) update()
    }

    private fun observeSession(session: Session, newLogin: Boolean = false) = synchronized(sessionStateLock) {
        val identity = session.userId to session.authSessionId()
        if (newLogin || !ownerActive || ownerIdentity != identity) ownerRevision++
        ownerIdentity = identity
        ownerActive = true
        mutableSessionInvalidation.value = null
    }

    private fun invalidateObservedSession(message: String?): Unit = synchronized(sessionStateLock) {
        if (ownerActive) {
            ownerActive = false
            mutableSessionInvalidation.value = SessionInvalidation(ownerRevision, message)
        }
    }

    private suspend fun load(): Session? = withContext(Dispatchers.IO) {
        try {
            (sessionStore.load() ?: authLinks.recovery()?.session).also { saved ->
                if (saved == null) invalidateObservedSession(unauthorized().message) else observeSession(saved)
            }
        } catch (error: ApiException) {
            if (error.kind == ApiError.STORAGE) invalidateObservedSession(error.message)
            throw error
        }
    }

    private suspend fun store(session: Session, newLogin: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            val recovery = if (newLogin || sessionStore.load() != null) null else authLinks.recovery()
            if (recovery != null) {
                if (!recovery.matches(session)) throw responseError()
                authLinks.saveRecovery(NativeRecoveryContext(session, recovery.createdAt))
            }
            else {
                sessionStore.save(session)
                if (newLogin) { authLinks.clearPending(); authLinks.clearRecovery() }
            }
            observeSession(session, newLogin)
        }
        catch (error: ApiException) {
            if (error.kind == ApiError.STORAGE) invalidateObservedSession(error.message)
            throw error
        }
    }

    private suspend fun clearStoredSession(message: String? = null) = withContext(Dispatchers.IO) {
        try { sessionStore.clear(); authLinks.clearRecovery(); invalidateObservedSession(message) }
        catch (error: ApiException) {
            if (error.kind == ApiError.STORAGE) invalidateObservedSession(error.message)
            throw error
        }
    }

    private suspend fun request(
        url: HttpUrl, method: String = "GET", body: JSONObject? = null, token: String?,
        backend: Boolean = true, representation: Boolean = false, exactMutation: Boolean = false,
    ): Any {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (backend && url.encodedPath.startsWith("/auth/v1/")) builder.header("X-Supabase-Api-Version", "2024-01-01")
        if (backend) builder.header("apikey", config.anonKey)
        token?.let { builder.header("Authorization", "Bearer $it") }
        if (!backend) builder.header("Origin", web.toString().removeSuffix("/"))
        if (representation) builder.header("Prefer", "return=representation")
        if (exactMutation) builder.header("Prefer", "return=minimal,count=exact")
        val requestBody = if (method in setOf("GET", "HEAD", "DELETE") && body == null) null
        else (body ?: JSONObject()).toString().toRequestBody(JSON_MEDIA)
        builder.method(method, requestBody)
        return execute(builder.build(), exactMutation)
    }

    private suspend fun execute(request: Request, exactMutation: Boolean = false): Any = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(ApiException(ApiError.NETWORK,
                    "Connessione non disponibile. Controlla la rete e riprova."))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val buffer = Buffer()
                        response.body?.source()?.let { source ->
                            while (source.read(buffer, 8192) != -1L) {
                                if (buffer.size > MAX_RESPONSE_BYTES) throw responseError()
                            }
                        }
                        val text = buffer.readUtf8()
                        if (!response.isSuccessful) throw httpError(response.code, text)
                        if (exactMutation) {
                            val count = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                                ?: throw responseError()
                            if (count != 1L) throw ApiException(ApiError.PERMISSION, "Il record non è disponibile o non può essere modificato.")
                        }
                        val value = if (text.isBlank()) JSONObject.NULL else ExactJson.parse(text)
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) {
                        val safe = when (error) {
                            is ApiException -> error
                            is IOException -> ApiException(ApiError.NETWORK, "Connessione interrotta. Riprova.")
                            else -> responseError()
                        }
                        if (continuation.isActive) continuation.resumeWithException(safe)
                    }
                }
            }
        })
    }

    private fun endpoint(origin: HttpUrl, path: String, query: String = ""): HttpUrl {
        if (query.any { it == '#' || it == '\n' || it == '\r' }) throw ApiException(ApiError.VALIDATION, "Query non valida.")
        return origin.newBuilder().addPathSegments(path).apply {
            if (query.isNotEmpty()) encodedQuery(query.removePrefix("?"))
        }.build()
    }

    private fun rowEndpoint(table: String, id: String, query: String): HttpUrl {
        validateId(id)
        val url = endpoint(base, "rest/v1/$table", query)
        if (url.queryParameterNames.contains("id")) throw ApiException(ApiError.VALIDATION, "Filtro del record duplicato.")
        return url.newBuilder().addQueryParameter("id", "eq.$id").build()
    }

    private fun identifier(value: String, allowHyphen: Boolean = false) {
        val valid = if (allowHyphen) FUNCTION_IDENTIFIER else IDENTIFIER
        if (!valid.matches(value)) throw ApiException(ApiError.VALIDATION, "Identificatore non valido.")
    }

    private fun validateId(id: String) {
        if (!RECORD_ID.matches(id)) throw ApiException(ApiError.VALIDATION, "ID del record non valido.")
    }

    private fun validateEmail(email: String) {
        if (!EMAIL.matches(email.trim()) || email.length > 254) throw ApiException(ApiError.VALIDATION, "Email non valida.")
    }

    private fun validateCredentials(email: String, password: String) {
        validateEmail(email)
        if (password.isBlank()) throw ApiException(ApiError.VALIDATION, "Inserisci la password.")
    }

    private fun parseSession(json: JSONObject): Session = try { Session.fromJson(json) }
    catch (_: Exception) { throw responseError() }
    private fun objectValue(value: Any): JSONObject = value as? JSONObject ?: throw responseError()
    private fun arrayValue(value: Any): JSONArray = value as? JSONArray ?: throw responseError()
    private fun singleRow(value: Any): JSONObject {
        val rows = arrayValue(value)
        if (rows.length() != 1) throw ApiException(ApiError.PERMISSION, "Il record non è disponibile o non può essere modificato.")
        return rows.optJSONObject(0) ?: throw responseError()
    }

    private fun httpError(status: Int, text: String): ApiException {
        val rawCode = runCatching {
            val json = JSONObject(text)
            (json.opt("code") as? String) ?: json.optString("error_code", "")
        }.getOrDefault("")
        val code = rawCode.takeIf { Regex("[A-Za-z0-9_]{1,80}").matches(it) }
        val kind = when (status) {
            401 -> ApiError.AUTHENTICATION
            403 -> ApiError.PERMISSION
            429 -> ApiError.RATE_LIMIT
            in 500..599 -> ApiError.SERVER
            else -> ApiError.VALIDATION
        }
        val message = when {
            code == "invalid_credentials" -> "Email o password non corrette."
            code == "email_not_confirmed" -> "Conferma il tuo indirizzo email prima di accedere."
            code == "mfa_verification_failed" -> "Codice di verifica non corretto o scaduto."
            status == 401 -> "Sessione scaduta. Accedi di nuovo."
            status == 403 -> "Non hai il permesso per questa operazione."
            status == 429 -> "Troppe richieste. Riprova più tardi."
            status >= 500 -> "Servizio temporaneamente non disponibile. Riprova."
            else -> "Richiesta non valida o non consentita ($status)."
        }
        return ApiException(kind, message, status, code)
    }

    private fun unauthorized() = ApiException(ApiError.AUTHENTICATION, "Accedi per continuare.", 401)
    private fun responseError() = ApiException(ApiError.RESPONSE, "Il servizio ha restituito dati non validi.")

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val IDENTIFIER = Regex("[a-z][a-z0-9_]{0,62}")
        private val FUNCTION_IDENTIFIER = Regex("[a-z][a-z0-9_-]{0,62}")
        private val RECORD_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val WEB_ENDPOINT = Regex("/api/[a-z][a-z0-9-]{0,62}")
        private val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
        private const val REFRESH_MARGIN_SECONDS = 60
        private const val MAX_RESPONSE_BYTES = 32L * 1024 * 1024
        private const val ACCESS_AUDIT_WARNING = "Accesso riuscito, ma questo dispositivo non è stato registrato nell’elenco degli accessi. Riprova al prossimo accesso."
        private val INVALID_REFRESH_CODES = setOf("refresh_token_not_found", "refresh_token_already_used", "session_not_found", "session_expired", "bad_jwt", "user_banned", "user_not_found")
        val PERMISSION_NAMES = listOf("admin", "poker", "fumo", "fire", "tcg", "libreria", "couple_expenses")

        fun parsePermissions(json: JSONObject?): Map<String, Boolean> = PERMISSION_NAMES.associateWith { key -> json?.opt(key) == true }
    }
}
