package cc.savely.android.ui

import android.content.Intent

/** Shared by cold start and onNewIntent. Clear the Activity's URI before publishing the parsed event. */
internal fun deliverAuthCallback(incoming: Intent?, sanitizeIntent: () -> Unit, inbox: AuthCallbackInbox) {
    if (incoming?.action != Intent.ACTION_VIEW) return
    val raw = incoming.dataString ?: ""
    sanitizeIntent()
    inbox.submit(raw)
}
