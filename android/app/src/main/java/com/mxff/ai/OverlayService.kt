package com.mxff.ai

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * "Shadow mode": a small dim orb floating over every app.
 *  - Say "Hey MXFF" (or "Jarvis") and it listens, even while you are inside another app.
 *  - While it answers, a holographic head pops up once, speaks, then shrinks back to the quiet shadow.
 *  - Say nothing and it stays silent. Tap the orb to talk without the wake word, long-press it to put it to sleep.
 */
class OverlayService : Service() {
    private enum class Kind { WAKE, COMMAND, FOLLOWUP, CONFIRM }

    private lateinit var wm: WindowManager
    private lateinit var view: ShadowView
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences
    private lateinit var audio: AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var recognizer: SpeechRecognizer? = null
    private var session = 0
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var asleep = false
    private var appOpen = false
    private var busy = false
    private var hud = false
    private var pending: JSONObject? = null
    private val history = ArrayList<Pair<String, String>>()
    private var savedX = 20
    private var savedY = 300
    private var anim: ValueAnimator? = null
    private var mutedStreams = ArrayList<Int>()

    private val afterSpeakRunnable = Runnable { afterSpeak() }
    private val wakeRunnable = Runnable { if (!busy && !appOpen && !asleep) listen(Kind.WAKE) }

    private val bn get() = prefs.getString("lang", "bn") != "en"
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun micOk() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        prefs = getSharedPreferences("mxff", MODE_PRIVATE)
        audio = getSystemService(AudioManager::class.java)
        wm = getSystemService(WindowManager::class.java)
        appOpen = MainActivity.visible
        startAsForeground()

        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { main.post { view.level = 0.6f } }
            override fun onDone(id: String?) { main.post { afterSpeak() } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { afterSpeak() } }
        })

        try { buildView() } catch (e: Exception) { stopSelf(); return }
        if (appOpen) view.visibility = View.GONE else startWake()
    }

    override fun onDestroy() {
        running = false
        instance = null
        anim?.cancel()
        unmuteNow()
        main.removeCallbacksAndMessages(null)
        stopRecognizer()
        tts?.stop(); tts?.shutdown()
        io.shutdownNow()
        if (::view.isInitialized) try { wm.removeView(view) } catch (e: Exception) { /* not attached */ }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ foreground notification

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("overlay", "MXFF shadow", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "overlay")
            .setContentTitle("MXFF AI is ready")
            .setContentText("Say \"Hey MXFF\" · tap to open the app")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (micOk()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(1, n, type)
        } else if (Build.VERSION.SDK_INT >= 30 && micOk()) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(1, n)
    }

    // ------------------------------------------------------------------ window + touch

    private fun buildView() {
        view = ShadowView(this)
        savedX = prefs.getInt("orbX", 20)
        savedY = prefs.getInt("orbY", 300)
        lp = WindowManager.LayoutParams(dp(64), dp(64), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = savedX; y = savedY }

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false; var downAt = 0L
        view.setOnTouchListener { _, e ->
            if (hud) {
                if (e.action == MotionEvent.ACTION_UP) dismissHud()
                return@setOnTouchListener true
            }
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false; downAt = SystemClock.uptimeMillis() }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (dx * dx + dy * dy > 100) moved = true
                    if (moved) { lp.x = sx + dx.toInt(); lp.y = sy + dy.toInt(); wm.updateViewLayout(view, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        savedX = lp.x; savedY = lp.y
                        prefs.edit().putInt("orbX", savedX).putInt("orbY", savedY).apply()
                    } else if (SystemClock.uptimeMillis() - downAt > 700) toggleSleep()
                    else onTap()
                }
            }
            true
        }
        wm.addView(view, lp)
    }

    private fun animateExpand(from: Float, to: Float, end: (() -> Unit)?) {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(from, to)
        a.duration = 320
        a.addUpdateListener { view.expand = it.animatedValue as Float }
        var cancelled = false
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(x: Animator) { cancelled = true }
            override fun onAnimationEnd(x: Animator) { if (!cancelled) end?.invoke() }
        })
        anim = a
        a.start()
    }

    private fun showHud(text: String) {
        view.caption = text
        view.mode = ShadowView.SPEAKING
        if (hud) { if (view.expand < 1f) animateExpand(view.expand, 1f, null); return }
        hud = true
        val (w, h) = ShadowView.hudSize(this, resources.displayMetrics.widthPixels)
        lp.width = w; lp.height = h
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.x = 0; lp.y = (resources.displayMetrics.heightPixels * 0.08f).toInt()
        view.expand = 0f
        wm.updateViewLayout(view, lp)
        animateExpand(0f, 1f, null)
    }

    private fun hideHud() {
        if (!hud) return
        animateExpand(view.expand, 0f) {
            hud = false
            lp.width = dp(64); lp.height = dp(64)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = savedX; lp.y = savedY
            wm.updateViewLayout(view, lp)
        }
    }

    /** Called by MainActivity: the shadow hides and the mic is released while the app itself is on screen. */
    private fun applyAppOpen(open: Boolean) {
        appOpen = open
        if (open) {
            main.removeCallbacks(afterSpeakRunnable); main.removeCallbacks(wakeRunnable)
            stopRecognizer(); tts?.stop(); pending = null; busy = false
            anim?.cancel(); hud = false
            lp.width = dp(64); lp.height = dp(64); lp.gravity = Gravity.TOP or Gravity.START; lp.x = savedX; lp.y = savedY
            view.visibility = View.GONE
            try { wm.updateViewLayout(view, lp) } catch (e: Exception) { /* ignore */ }
        } else {
            view.visibility = View.VISIBLE
            if (!asleep) startWake() else view.mode = ShadowView.SLEEP
        }
    }

    // ------------------------------------------------------------------ user gestures

    private fun onTap() {
        when {
            asleep -> { asleep = false; startWake() }
            busy -> dismissHud()
            else -> { stopRecognizer(); main.removeCallbacks(wakeRunnable); listen(Kind.COMMAND) }
        }
    }

    private fun toggleSleep() {
        asleep = !asleep
        if (asleep) {
            main.removeCallbacks(wakeRunnable); main.removeCallbacks(afterSpeakRunnable)
            stopRecognizer(); tts?.stop(); pending = null; busy = false
            hideHud(); view.mode = ShadowView.SLEEP
        } else startWake()
    }

    private fun dismissHud() {
        main.removeCallbacks(afterSpeakRunnable)
        tts?.stop(); pending = null; busy = false
        hideHud(); startWake()
    }

    // ------------------------------------------------------------------ listening

    private fun startWake() {
        if (appOpen || asleep) return
        busy = false
        view.mode = ShadowView.IDLE
        main.removeCallbacks(wakeRunnable)
        listen(Kind.WAKE)
    }

    private fun restartWake(delay: Long) {
        main.removeCallbacks(wakeRunnable)
        main.postDelayed(wakeRunnable, delay)
    }

    private fun stopRecognizer() {
        session++
        try { recognizer?.destroy() } catch (e: Exception) { /* ignore */ }
        recognizer = null
    }

    private fun recLang() = if (prefs.getString("lang", "bn") == "en") "en-US" else "bn-BD"

    private fun listen(kind: Kind) {
        if (appOpen || asleep || !micOk()) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showHud(if (bn) "এই ফোনে ভয়েস রিকগনিশন নেই।" else "Speech recognition is not available on this phone.")
            main.postDelayed({ hideHud() }, 3000)
            return
        }
        stopRecognizer()
        val my = ++session
        view.mode = if (kind == Kind.WAKE) ShadowView.IDLE else ShadowView.LISTENING
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onRmsChanged(v: Float) {
                if (my != session) return
                val l = ((v + 2f) / 12f).coerceIn(0f, 1f)
                view.level = view.level * 0.6f + l * 0.4f
            }
            override fun onResults(p: Bundle?) {
                if (my != session) return
                val text = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                onHeard(kind, text)
            }
            override fun onError(e: Int) {
                if (my != session) return
                onListenError(kind, e)
            }
        })
        muteBeepBriefly()
        rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, recLang())
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L))
    }

    private fun onHeard(kind: Kind, text: String) {
        when (kind) {
            Kind.WAKE -> {
                val rest = afterWake(text)
                when {
                    rest == null -> restartWake(250)
                    rest.isBlank() -> listen(Kind.COMMAND)
                    else -> handle(rest)
                }
            }
            Kind.COMMAND, Kind.FOLLOWUP -> if (text.isBlank()) startWake() else handle(text)
            Kind.CONFIRM -> answerConfirm(text)
        }
    }

    private fun onListenError(kind: Kind, err: Int) {
        if (err == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return
        when (kind) {
            Kind.WAKE -> restartWake(when (err) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 250L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_CLIENT -> 1500L
                else -> 800L
            })
            Kind.CONFIRM -> { pending = null; startWake() }
            else -> startWake()
        }
    }

    // "Hey MXFF" / "Jarvis" in Latin or Bengali script (speech engines spell it many ways)
    private val wakeRe = Regex(
        "(?:(?:hey|hay|hi|ok|okay|হে|হেই|হ্যালো|ওকে)\\s*(?:m\\s*x\\s*f\\s*f|max|mex|এম\\s*এক্স\\s*এফ\\s*এফ|ম্যাক্স|মেক্স)" +
            "|m\\s*x\\s*f\\s*f|এম\\s*এক্স\\s*এফ\\s*এফ|jarvis|জার্ভিস|জারভিস)",
        RegexOption.IGNORE_CASE)

    private fun afterWake(text: String): String? {
        val m = wakeRe.find(text) ?: return null
        return text.substring(m.range.last + 1).trim(' ', ',', '.', '।', '،', '!')
    }

    // ------------------------------------------------------------------ thinking + acting

    private fun handle(text: String) {
        if (Regex("^(stop|cancel|never ?mind|থামো|থামুন|চুপ|বাদ দাও|বাদ দিন)", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())) {
            startWake(); return
        }
        busy = true
        view.mode = ShadowView.THINKING
        history.add("user" to text)
        while (history.size > 12) history.removeAt(0)
        val msgs = history.toList()
        io.execute {
            val r = askServer(msgs)
            main.post { onReply(r.first, r.second) }
        }
    }

    private fun askServer(msgs: List<Pair<String, String>>): Pair<Boolean, String> {
        val offline = if (bn) "MXFF সার্ভারে পৌঁছাতে পারছি না। ইন্টারনেট দেখুন, আর ওয়েব অ্যাপ নতুন করে পাবলিশ করা আছে কিনা দেখুন।"
        else "I can't reach the MXFF server. Check your internet and that the web app has been republished."
        return try {
            val con = URL(BuildConfig.WEB_URL.trimEnd('/') + "/api/mxff-ask").openConnection() as HttpURLConnection
            con.requestMethod = "POST"
            con.connectTimeout = 15000
            con.readTimeout = 60000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            if (BuildConfig.APP_TOKEN.isNotEmpty()) con.setRequestProperty("x-mxff-token", BuildConfig.APP_TOKEN)
            val mem = try { JSONArray(prefs.getString("memory", "[]")) } catch (e: Exception) { JSONArray() }
            val body = JSONObject()
                .put("messages", JSONArray().apply { msgs.forEach { put(JSONObject().put("role", it.first).put("content", it.second)) } })
                .put("language", prefs.getString("lang", "bn"))
                .put("memory", mem)
            con.outputStream.use { it.write(body.toString().toByteArray()) }
            val stream = if (con.responseCode in 200..299) con.inputStream else con.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val j = JSONObject(raw)
            if (j.optBoolean("ok")) Pair(true, j.getString("text")) else Pair(false, j.optString("error", offline))
        } catch (e: Exception) { Pair(false, offline) }
    }

    private fun onReply(ok: Boolean, text: String) {
        if (!busy) return // user dismissed meanwhile
        if (!ok) { say(text); return }
        val m = Regex("\\n?ACTION:\\s*(\\{.*\\})\\s*$", RegexOption.DOT_MATCHES_ALL).find(text)
        val clean = (if (m != null) text.substring(0, m.range.first) else text).trim()
        val action = m?.let { try { JSONObject(it.groupValues[1]) } catch (e: Exception) { null } }
        history.add("assistant" to clean)

        if (action != null && action.optString("type") in PhoneActions.SENSITIVE) {
            pending = action
            say(PhoneActions.describe(action, bn) + if (bn) "। নিশ্চিত? হ্যাঁ বা না বলুন।" else ". Sure? Say yes or no.")
            return
        }
        var spoken = clean
        if (action != null) {
            val r = PhoneActions.run(applicationContext, action)
            if (!r.optBoolean("ok")) spoken = r.optString("message")
        }
        if (spoken.isBlank()) startWake() else say(spoken)
    }

    private fun answerConfirm(text: String) {
        val yes = Regex("\\b(?:yes|yeah|yep|sure|okay|ok|confirm|go ahead)\\b|হ্যাঁ|হ্যা|হাঁ|জি|ঠিক আছে|করো|পাঠাও", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val no = Regex("\\b(?:no|nope|cancel|don't|stop)\\b|না|থাক|বাদ", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val a = pending
        pending = null
        busy = true
        if (a != null && yes && !no) {
            a.put("confirmed", true)
            val r = PhoneActions.run(applicationContext, a)
            say(if (r.optBoolean("ok")) (if (bn) "হয়ে গেছে।" else "Done.") else r.optString("message"))
        } else say(if (bn) "ঠিক আছে, বাতিল করলাম।" else "Okay, cancelled.")
    }

    // ------------------------------------------------------------------ speaking

    private fun say(text: String) {
        busy = true
        stopRecognizer()
        main.removeCallbacks(wakeRunnable)
        unmuteNow()
        showHud(text)
        val spoken = text.replace(Regex("[*#`_>~]"), "").replace(Regex("https?://\\S+"), "").trim()
        val locale = when (prefs.getString("lang", "bn")) {
            "en" -> Locale.US
            "bn" -> Locale.forLanguageTag("bn-BD")
            else -> if (Regex("[\\u0980-\\u09FF]").containsMatchIn(spoken)) Locale.forLanguageTag("bn-BD") else Locale.US
        }
        var started = false
        if (ttsReady && prefs.getBoolean("voiceReply", true) && spoken.isNotBlank()) {
            val r = tts?.setLanguage(locale)
            if (r != null && r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "mxff-${System.nanoTime()}")
                started = true
            }
        }
        // No voice available (or voice reply is off): keep the caption on screen long enough to read.
        if (!started) main.postDelayed(afterSpeakRunnable, (1800L + text.length * 55L).coerceAtMost(9000L))
    }

    private fun afterSpeak() {
        if (!busy || appOpen) return
        main.removeCallbacks(afterSpeakRunnable)
        hideHud()
        busy = false
        // Short follow-up window without the wake word; if you say nothing it drops back to the quiet shadow.
        if (pending != null) listen(Kind.CONFIRM) else listen(Kind.FOLLOWUP)
    }

    // ------------------------------------------------------------------ recogniser start beep

    /** Many phones beep each time the recogniser starts; mute the short-lived system streams for a moment. */
    private fun muteBeepBriefly() {
        unmuteNow()
        for (s in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try {
                if (audio.isStreamMute(s)) continue
                audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                mutedStreams.add(s)
            } catch (e: Exception) { /* Do-Not-Disturb rules may forbid it; skip */ }
        }
        if (mutedStreams.isNotEmpty()) main.postDelayed({ unmuteNow() }, 800)
    }

    private fun unmuteNow() {
        for (s in mutedStreams) try { audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { /* ignore */ }
        mutedStreams.clear()
    }

    companion object {
        @Volatile var running = false
        @Volatile private var instance: OverlayService? = null

        /** MainActivity tells the shadow when the app itself is on screen. */
        fun setAppOpen(open: Boolean) { instance?.let { s -> s.main.post { s.applyAppOpen(open) } } }
    }
}
