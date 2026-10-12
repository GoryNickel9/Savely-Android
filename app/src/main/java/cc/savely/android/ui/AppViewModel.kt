package cc.savely.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.savely.android.data.ApiException
import cc.savely.android.data.ApiError
import cc.savely.android.data.Session
import cc.savely.android.data.SupabaseApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Base64

data class AppState(
    val initialized: Boolean = false,
    val busy: Boolean = false,
    val session: Session? = null,
    val permissions: Map<String, Boolean> = emptyMap(),
    val mfaFactor: String? = null,
    val recovery: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val registrationsEnabled: Boolean? = null,
)

class AppViewModel(val api: SupabaseApi) : ViewModel() {
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            api.sessionInvalidation.filterNotNull().collect { event ->
                api.withCurrentSessionInvalidation(event) {
                    mutable.value = AppState(initialized = true, busy = mutable.value.busy, error = event.message)
                }
            }
        }
        restore()
    }

    fun restore() = runAction {
        val session = api.session()
        if (session == null) mutable.value = AppState(initialized = true, busy = mutable.value.busy)
        else acceptSession(session)
    }

    fun signIn(email: String, password: String) = runAction { acceptSession(api.signIn(email, password)) }

    fun signUp(email: String, password: String, fullName: String) = runAction {
        val message = api.signUpNative(email, password, fullName)
        val session = api.session()
        if (session != null) acceptSession(session)
        mutable.value = mutable.value.copy(message = message)
    }

    fun checkRegistrations() = runAction {
        mutable.value = mutable.value.copy(registrationsEnabled = api.registrationsEnabled())
    }

    fun resetPassword(email: String) = runAction {
        api.requestNativePasswordReset(email)
        mutable.value = mutable.value.copy(message = "Controlla la tua email e apri il link su questo dispositivo per recuperare la password.")
    }

    fun handleAuthCallback(event: AuthCallbackEvent) {
        viewModelScope.launch {
            state.first { !it.busy }
            runAction {
                val callback = event.callback ?: throw ApiException(ApiError.VALIDATION, requireNotNull(event.error))
                acceptSession(api.completeNativeCallback(callback).session)
            }
        }
    }

    fun completePasswordRecovery(password: String, confirmation: String) = runAction {
        acceptSession(api.completePasswordRecovery(password, confirmation))
        mutable.value = mutable.value.copy(message = "Password aggiornata.")
    }

    fun cancelPasswordRecovery() = runAction {
        if (api.cancelPasswordRecovery()) mutable.value = AppState(initialized = true, busy = mutable.value.busy)
    }

    fun verifyMfa(code: String) = runAction {
        val gate = mutable.value
        val factor = gate.mfaFactor ?: error("Nessun codice richiesto.")
        val session = gate.session
            ?: throw ApiException(ApiError.PERMISSION, "La sessione è cambiata. Aggiorna la schermata prima di continuare.")
        val owner = api.sessionOwnerRevision(session)
            ?: throw ApiException(ApiError.PERMISSION, "La sessione è cambiata. Aggiorna la schermata prima di continuare.")
        acceptSession(api.verifyTotp(factor, code, expectedOwner = session.userId, expectedRevision = owner))
    }

    // The central owner-bound invalidation collector handles logout, including HTTP failures.
    fun signOut() = runAction { api.signOut() }

    fun dismissMessage() { mutable.value = mutable.value.copy(message = null, error = null) }

    override fun onCleared() {
        api.close()
        super.onCleared()
    }

    private suspend fun acceptSession(session: Session) {
        val owner = api.sessionOwnerRevision(session) ?: return
        val recovery = api.isPasswordRecovery(session)
        // Verified factors require aal2 before any account data is displayed.
        val factors = api.listMfaFactors(expectedOwner = session.userId, expectedRevision = owner)
        if (!api.isSessionOwnerCurrent(owner)) return
        if (factors.length() > 0 && assuranceLevel(session) != "aal2") {
            api.withCurrentSessionOwner(owner) {
                mutable.value = AppState(initialized = true, busy = mutable.value.busy, session = session, recovery = recovery,
                    mfaFactor = factors.getJSONObject(0).getString("id"))
            }
            return
        }
        val permissions = if (recovery) emptyMap() else api.permissions(expectedOwner = session.userId, expectedRevision = owner)
        // A pane may invalidate the session while this restore is awaiting HTTP.
        api.withCurrentSessionOwner(owner) {
            mutable.value = AppState(initialized = true, busy = mutable.value.busy, session = session, permissions = permissions, recovery = recovery)
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        val actionOwner = api.sessionOwnerRevision()
        mutable.value = mutable.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (error is ApiException && error.kind in setOf(ApiError.AUTHENTICATION, ApiError.STORAGE)) {
                    // Definitive invalidations already clear their own observed login centrally.
                    // Keep the unsigned error fallback, without clearing a newer successful login.
                    api.withFailedSessionOwner(actionOwner) {
                        mutable.value = AppState(initialized = true, busy = mutable.value.busy, error = userMessage(error))
                    }
                    // Initial restore can first observe an existing login inside the action.
                    // Initialize its empty UI on failure without erasing an accepted replacement.
                    if (!mutable.value.initialized && mutable.value.session == null)
                        mutable.value = mutable.value.copy(initialized = true, error = userMessage(error))
                } else mutable.value = mutable.value.copy(initialized = true, error = userMessage(error))
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}

fun userMessage(error: Throwable): String = when (error) {
    is ApiException -> error.message ?: "Richiesta non riuscita. Riprova."
    is IllegalArgumentException -> error.message ?: "Controlla i dati inseriti."
    else -> "Operazione non riuscita. Controlla la connessione e riprova."
}

internal fun assuranceLevel(session: Session): String = runCatching {
    val payload = session.accessToken.split('.')[1]
    JSONObject(String(Base64.getUrlDecoder().decode(payload))).optString("aal", "aal1")
}.getOrDefault("aal1")
