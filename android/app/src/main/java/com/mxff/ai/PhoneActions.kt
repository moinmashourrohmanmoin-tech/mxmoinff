package com.mxff.ai

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import org.json.JSONObject

/** Every phone action MXFF can run. Used by the web bridge (NativeBridge) and the floating shadow (OverlayService). */
object PhoneActions {
    private fun res(ok: Boolean, msg: String) = JSONObject().put("ok", ok).put("message", msg)
    private fun ok(m: String) = res(true, m)
    private fun fail(m: String) = res(false, m)
    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun start(ctx: Context, i: Intent, label: String): JSONObject = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ok(label)
    } catch (e: Exception) { fail("Could not open: $label") }

    /** Actions that must be confirmed by the user before running. */
    val SENSITIVE = setOf("sms", "call", "whatsapp")

    fun run(ctx: Context, a: JSONObject): JSONObject {
        val q = Uri.encode(a.optString("query"))
        return when (a.optString("type")) {
            "open_app" -> openApp(ctx, a.optString("name"))
            "open_url" -> start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url"))), a.optString("url"))
            "google_search", "web_search" -> start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q")), "Google search")
            "youtube_search" -> start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q")), "YouTube search")
            "open_settings" -> start(ctx, Intent(Settings.ACTION_SETTINGS), "Settings")
            "open_camera" -> start(ctx, Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Camera")
            "open_maps" -> start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")), "Maps")
            "whatsapp" -> start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${a.optString("phone").filter { it.isDigit() }}?text=${Uri.encode(a.optString("text"))}")), "WhatsApp")
            "call" -> if (!a.optBoolean("confirmed")) fail("Call needs confirmation")
                else if (has(ctx, Manifest.permission.CALL_PHONE)) start(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:${a.optString("phone")}")), "Calling ${a.optString("phone")}")
                else start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${a.optString("phone")}")), "Dialer opened")
            "sms" -> sendSms(ctx, a)
            "reminder" -> reminder(ctx, a.optString("text"), a.optInt("minutesFromNow", 10))
            "torch" -> torch(ctx, a.optBoolean("on", true))
            "volume" -> volume(ctx, a.optString("direction"))
            "alarm" -> alarm(ctx, a)
            "timer" -> timer(ctx, a)
            "media" -> media(ctx, a.optString("command"))
            "agent" -> startAgent(a.optString("goal"))
            else -> fail("Unknown action")
        }
    }

    /** Well-known apps (English + Bengali names) -> package candidates (first installed one wins). */
    val KNOWN_APPS: Map<String, List<String>> = mapOf(
        "youtube" to listOf("com.google.android.youtube"), "ইউটিউব" to listOf("com.google.android.youtube"),
        "whatsapp" to listOf("com.whatsapp"), "হোয়াটসঅ্যাপ" to listOf("com.whatsapp"),
        "facebook" to listOf("com.facebook.katana"), "ফেসবুক" to listOf("com.facebook.katana"),
        "messenger" to listOf("com.facebook.orca"), "মেসেঞ্জার" to listOf("com.facebook.orca"),
        "instagram" to listOf("com.instagram.android"), "ইনস্টাগ্রাম" to listOf("com.instagram.android"),
        "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), "tik tok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        "টিকটক" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), "টিক টক" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        "telegram" to listOf("org.telegram.messenger"), "টেলিগ্রাম" to listOf("org.telegram.messenger"),
        "snapchat" to listOf("com.snapchat.android"), "x" to listOf("com.twitter.android"), "twitter" to listOf("com.twitter.android"),
        "imo" to listOf("com.imo.android.imoim"), "ইমো" to listOf("com.imo.android.imoim"),
        "spotify" to listOf("com.spotify.music"), "netflix" to listOf("com.netflix.mediaclient"),
        "chrome" to listOf("com.android.chrome"), "gmail" to listOf("com.google.android.gm"),
        "maps" to listOf("com.google.android.apps.maps"), "play store" to listOf("com.android.vending"), "প্লে স্টোর" to listOf("com.android.vending"),
    )

    /** Package of an installed app by name, or null if it is not installed. */
    fun findPackage(ctx: Context, name: String): String? {
        val pm = ctx.packageManager
        val n = name.trim().lowercase()
        if (n.isEmpty()) return null
        KNOWN_APPS[n]?.firstOrNull { pm.getLaunchIntentForPackage(it) != null }?.let { return it }
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .firstOrNull { it.loadLabel(pm).toString().lowercase().contains(n) }?.activityInfo?.packageName
    }

    private fun openApp(ctx: Context, name: String): JSONObject {
        val pkg = findPackage(ctx, name)
        val i = pkg?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
            ?: return res(false, "App not found: $name").put("code", "not_installed")
        return start(ctx, i, "Opened $name")
    }

    /** "Operate other apps for me" (TikTok, WhatsApp, Play Store ...): handled by the floating assistant + accessibility service. */
    private fun startAgent(goal: String): JSONObject = when {
        goal.isBlank() -> fail("No task given")
        !OverlayService.running -> fail("Turn on the Floating Assistant first (Settings)")
        !MxffAccessibilityService.ready -> fail("Turn on MXFF in Android Settings → Accessibility first")
        else -> { OverlayService.runAgent(goal, fromApp = true); ok("Working on it") }
    }

    private fun sendSms(ctx: Context, a: JSONObject): JSONObject {
        if (!a.optBoolean("confirmed")) return fail("SMS needs confirmation")
        val phone = a.optString("phone"); val text = a.optString("text")
        if (!has(ctx, Manifest.permission.SEND_SMS)) return start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).putExtra("sms_body", text), "SMS app opened")
        return try {
            val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            sms.sendMultipartTextMessage(phone, null, sms.divideMessage(text), null, null); ok("SMS sent to $phone")
        } catch (e: Exception) { fail("SMS failed: ${e.message}") }
    }

    private fun reminder(ctx: Context, text: String, minutes: Int): JSONObject {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, text.hashCode(), Intent(ctx, ReminderReceiver::class.java).putExtra("text", text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + minutes * 60_000L, pi)
        return ok("Reminder set for $minutes min")
    }

    private fun torch(ctx: Context, on: Boolean): JSONObject {
        return try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return fail("No flashlight on this phone")
            cm.setTorchMode(id, on)
            ok(if (on) "Flashlight on" else "Flashlight off")
        } catch (e: Exception) { fail("Flashlight failed") }
    }

    private fun volume(ctx: Context, dir: String): JSONObject {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        return try {
            when (dir.lowercase()) {
                "up" -> am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "down" -> am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "unmute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                "max" -> am.setStreamVolume(s, am.getStreamMaxVolume(s), AudioManager.FLAG_SHOW_UI)
                else -> return fail("Unknown volume command")
            }
            ok("Volume $dir")
        } catch (e: Exception) { fail("Volume change failed") }
    }

    private fun alarm(ctx: Context, a: JSONObject): JSONObject {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
            .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "MXFF"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return start(ctx, i, "Alarm set for ${a.optInt("hour")}:${"%02d".format(a.optInt("minute"))}")
    }

    private fun timer(ctx: Context, a: JSONObject): JSONObject {
        val sec = a.optInt("seconds", 60)
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return start(ctx, i, "Timer set for $sec s")
    }

    private fun media(ctx: Context, cmd: String): JSONObject {
        val key = when (cmd.lowercase()) {
            "play_pause", "play", "pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return fail("Unknown media command")
        }
        return try {
            val am = ctx.getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            ok("Media: $cmd")
        } catch (e: Exception) { fail("Media control failed") }
    }

    /** Short human-readable sentence of what an action will do (used for spoken confirmation). */
    fun describe(a: JSONObject, bn: Boolean): String {
        val phone = a.optString("phone"); val text = a.optString("text")
        return when (a.optString("type")) {
            "sms" -> if (bn) "$phone নম্বরে এসএমএস পাঠাব: $text" else "Send an SMS to $phone saying: $text"
            "call" -> if (bn) "$phone নম্বরে কল করব" else "Call $phone"
            "whatsapp" -> if (bn) "$phone নম্বরের হোয়াটসঅ্যাপ চ্যাট খুলব" else "Open the WhatsApp chat with $phone"
            else -> a.optString("type")
        }
    }
}
