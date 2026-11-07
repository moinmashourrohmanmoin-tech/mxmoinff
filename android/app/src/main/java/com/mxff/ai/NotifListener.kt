package com.mxff.ai

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

/** Keeps the last 20 notifications in memory only (never uploaded) so MXFF can read them aloud on request. */
class NotifListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val ex = sbn.notification.extras
        recent.addFirst(JSONObject().put("app", sbn.packageName)
            .put("title", ex.getCharSequence("android.title")?.toString() ?: "")
            .put("text", ex.getCharSequence("android.text")?.toString() ?: "")
            .put("at", sbn.postTime))
        while (recent.size > 20) recent.removeLast()
    }
    companion object { val recent = ConcurrentLinkedDeque<JSONObject>() }
}
