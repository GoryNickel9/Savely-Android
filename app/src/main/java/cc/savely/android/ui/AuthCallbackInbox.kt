package cc.savely.android.ui

import androidx.lifecycle.ViewModel
import cc.savely.android.data.ApiException
import cc.savely.android.data.NativeAuthCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class AuthCallbackEvent internal constructor(val id: String, val callback: NativeAuthCallback?, val error: String?) {
    override fun toString(): String = "AuthCallbackEvent(valid=${callback != null})"
}

/** Activity-scoped memory only: configuration changes retain delivery, process death never persists codes. */
class AuthCallbackInbox : ViewModel() {
    private val mutable = MutableStateFlow<AuthCallbackEvent?>(null)
    val events = mutable.asStateFlow()
    fun submit(raw: String) {
        val id = UUID.randomUUID().toString()
        mutable.value = try { AuthCallbackEvent(id, NativeAuthCallback.parse(raw), null) }
        catch (error: ApiException) { AuthCallbackEvent(id, null, error.message) }
    }
    fun consume(eventId: String) { if (mutable.value?.id == eventId) mutable.value = null }
}
