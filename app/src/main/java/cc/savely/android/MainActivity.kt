package cc.savely.android

import android.os.Bundle
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import cc.savely.android.ui.AuthCallbackInbox
import cc.savely.android.ui.deliverAuthCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cc.savely.android.ui.SavelyApp

class MainActivity : ComponentActivity() {
    private val authCallbacks by lazy { ViewModelProvider(this)[AuthCallbackInbox::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureAuthCallback(intent)
        enableEdgeToEdge()
        setContent { SavelyApp(authCallbacks = authCallbacks) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureAuthCallback(intent)
    }

    private fun captureAuthCallback(incoming: Intent?) {
        deliverAuthCallback(incoming, {
            setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        }, authCallbacks)
    }
}
