package com.mxff.ai

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** JavaScript interface exposed as window.MXFFNative. Mirrors src/lib/native.ts. */
class NativeBridge(private val act: MainActivity) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeOn = false
    private var wakeLang = "bn-BD"
    private var stopped = false

    init { tts = TextToSpeech(act) { ttsReady = it == TextToSpeech.SUCCESS }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = emit("tts_state", "speaking" to true)
            override fun onDone(id: String?) = emit("tts_state", "speaking" to false)
            @Deprecated("Deprecated in Java") override fun onError(id: String?) = emit("tts_state", "speaking" to false)
        })
    }

    private fun emit(type: String, vararg kv: Pair<String, Any>) {
        val o = JSONObject().put("type", type); kv.forEach { o.put(it.first, it.second) }; act.emit(o)
    }
    private fun ok(msg: String) = JSONObject().put("ok", true).put("message", msg).toString()
    private fun fail(msg: String) = JSONObject().put("ok", false).put("message", msg).toString()
    private fun has(p: String) = act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun openUri(u: String) = try {
        act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }

    @JavascriptInterface
    fun action(json: String): String {
        stopped = false
        val res = PhoneActions.run(act, JSONObject(json))
        emit("action_result", "ok" to res.getBoolean("ok"), "message" to res.getString("message"))
        return res.toString()
    }

    /** Web app pushes language / voice-reply / memory so the floating shadow (which runs outside the WebView) can use them. */
    @JavascriptInterface
    fun syncState(json: String) {
        try {
            val o = JSONObject(json)
            act.getSharedPreferences("mxff", android.content.Context.MODE_PRIVATE).edit()
                .putString("lang", o.optString("language", "bn"))
                .putBoolean("voiceReply", o.optBoolean("voiceReply", true))
                .putString("memory", (o.optJSONArray("memory") ?: JSONArray()).toString())
                .apply()
        } catch (e: Exception) { /* ignore malformed */ }
    }

    @JavascriptInterface
    fun getStatus(): String {
        val listeners = Settings.Secure.getString(act.contentResolver, "enabled_notification_listeners") ?: ""
        return JSONObject()
            .put("overlayPermission", Settings.canDrawOverlays(act))
            .put("micPermission", has(Manifest.permission.RECORD_AUDIO))
            .put("smsPermission", has(Manifest.permission.SEND_SMS))
            .put("callPermission", has(Manifest.permission.CALL_PHONE))
            .put("notificationAccess", listeners.contains(ComponentName(act, NotifListener::class.java).flattenToString()))
            .put("postNotifications", Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS))
            .put("overlayOn", OverlayService.running)
            .put("wakeOn", wakeOn)
            .put("androidVersion", Build.VERSION.SDK_INT).toString()
    }

    @JavascriptInterface
    fun setOverlay(on: Boolean): String {
        if (on && !Settings.canDrawOverlays(act)) { requestPermission("overlay"); return fail("Allow 'Display over other apps' first") }
        if (on && !has(Manifest.permission.RECORD_AUDIO)) { requestPermission("mic"); return fail("Allow the microphone first") }
        val i = Intent(act, OverlayService::class.java)
        if (on) act.startForegroundService(i) else act.stopService(i)
        return ok(if (on) "MXFF shadow on: say \"Hey MXFF\"" else "MXFF shadow off")
    }

    /** Wake word: keeps a recognition loop running while the app is open and fires when it hears "hey mxff" / "এমএক্সএফএফ". */
    @JavascriptInterface
    fun setWake(on: Boolean, lang: String): String {
        wakeOn = on; wakeLang = lang
        if (on) main.post { listenInternal(lang, wake = true) } else stopListening()
        return ok(if (on) "Wake word listening while app is open" else "Wake word off")
    }

    @JavascriptInterface fun startListening(lang: String) { stopped = false; main.post { listenInternal(lang, wake = false) } }

    private fun listenInternal(lang: String, wake: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(act)) { emit("stt_error", "message" to "Speech recognition not available on this phone"); return }
        recognizer?.destroy()
        val rec = SpeechRecognizer.createSpeechRecognizer(act); recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { if (!wake) emit("stt_state", "listening" to true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(p: Bundle?) {
                val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (!wake) emit("stt_partial", "text" to t)
            }
            override fun onResults(p: Bundle?) {
                val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (wake) {
                    val l = t.lowercase()
                    if (Regex("(hey|hi|ok)\\s*(mxff|m x f f|max|em ex)|এমএক্সএফএফ|হে এম").containsMatchIn(l)) emit("wake", "text" to t)
                    else if (wakeOn && !stopped) main.postDelayed({ listenInternal(wakeLang, true) }, 300)
                } else { emit("stt_state", "listening" to false); if (t.isNotBlank()) emit("stt_result", "text" to t) }
            }
            override fun onError(e: Int) {
                if (wake) { if (wakeOn && !stopped) main.postDelayed({ listenInternal(wakeLang, true) }, 1000); return }
                emit("stt_state", "listening" to false)
                if (e != SpeechRecognizer.ERROR_NO_MATCH && e != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) emit("stt_error", "message" to "Voice input error ($e)")
            }
        })
        rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
    }

    @JavascriptInterface fun stopListening() { main.post { recognizer?.stopListening(); recognizer?.destroy(); recognizer = null } }

    @JavascriptInterface
    fun speak(text: String, lang: String) {
        if (!ttsReady) { emit("tts_state", "speaking" to false); return }
        tts?.language = Locale.forLanguageTag(lang)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mxff-${System.nanoTime()}")
    }

    @JavascriptInterface fun stopSpeaking() { tts?.stop(); emit("tts_state", "speaking" to false) }

    @JavascriptInterface
    fun requestPermission(name: String) {
        main.post {
            when (name) {
                "mic" -> act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                "sms" -> act.requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 2)
                "call" -> act.requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 3)
                "post_notifications" -> if (Build.VERSION.SDK_INT >= 33) act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
                "overlay" -> act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}")))
                "notification_access" -> act.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
    }

    @JavascriptInterface fun getNotifications(): String = JSONArray(NotifListener.recent.toList()).toString()

    @JavascriptInterface
    fun emergencyStop() {
        stopped = true; wakeOn = false
        stopListening(); tts?.stop()
        act.stopService(Intent(act, OverlayService::class.java))
        emit("action_result", "ok" to true, "message" to "Emergency stop: all automation halted")
    }

    fun shutdown() { recognizer?.destroy(); tts?.shutdown() }
}
