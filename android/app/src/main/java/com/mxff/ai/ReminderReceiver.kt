package com.mxff.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("reminders", "Reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(System.currentTimeMillis().toInt(), Notification.Builder(ctx, "reminders")
            .setContentTitle("MXFF reminder").setContentText(i.getStringExtra("text") ?: "")
            .setSmallIcon(android.R.drawable.ic_popup_reminder).setAutoCancel(true).build())
    }
}
