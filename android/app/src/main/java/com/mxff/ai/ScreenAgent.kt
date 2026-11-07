package com.mxff.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phone-operating agent. Loop: look at the screen -> ask the AI server what to do next -> do ONE step -> repeat,
 * until the AI says "done". Runs on a background thread (never the main thread).
 *
 * Safety rules enforced HERE in code (not only in the AI prompt):
 *  - Before pressing anything that sends a message, pays, buys, deletes or subscribes, the user is asked by voice.
 *  - Before installing an app from Play Store, the user is asked by voice.
 *  - Never types into password fields; never operates banking / mobile-money apps.
 *  - The AI can only press numbered elements it was shown (it cannot tap arbitrary coordinates).
 *  - Hard step and time limit; the orb tap / emergency stop cancels it immediately.
 */
class ScreenAgent(private val ctx: Context, private val host: Host, private val bn: Boolean) {

    interface Host {
        /** Speak and wait until finished. */
        fun say(text: String)
        /** Speak a question and return what the user answered (null = no answer). */
        fun ask(question: String): String?
        /** Speak a yes/no question. */
        fun confirm(question: String): Boolean
    }

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    private val history = ArrayList<String>()
    private val approvedInstalls = HashSet<String>()
    private var lastTyped = ""
    private var declined = false

    private fun t(b: String, e: String) = if (bn) b else e

    fun run(goal: String): String {
        val started = System.currentTimeMillis()
        var lastKey = ""
        var repeats = 0
        for (step in 1..MAX_STEPS) {
            if (cancelled) return t("থামিয়ে দিলাম।", "Stopped.")
            if (System.currentTimeMillis() - started > MAX_MILLIS) return t("অনেক সময় লেগে যাচ্ছে, তাই থামলাম।", "This is taking too long, so I stopped.")
            val svc = MxffAccessibilityService.instance
                ?: return t("MXFF-এর অ্যাক্সেসিবিলিটি সার্ভিস বন্ধ হয়ে গেছে। সেটিংস থেকে আবার চালু করুন।", "The MXFF accessibility service is off. Turn it on again in Settings.")
            val snap = svc.snapshot()
            val pkg = snap?.pkg.orEmpty()
            if (pkg.isNotEmpty() && FINANCE_RE.containsMatchIn(pkg))
                return t("এটা আর্থিক অ্যাপ। এখানে আমি নিজে কিছু করি না, আপনি নিজে করুন।", "That is a banking or money app. I don't operate those, please do it yourself.")

            val image = if (ctx.getSharedPreferences("mxff", Context.MODE_PRIVATE).getBoolean("screenVision", true)) svc.screenshot() else null
            val reply = askServer(goal, step, pkg, snap?.text.orEmpty(), image)
                ?: return t("থামিয়ে দিলাম।", "Stopped.")
            if (cancelled) return t("থামিয়ে দিলাম।", "Stopped.")
            if (!reply.optBoolean("ok")) return reply.optString("error", t("একটা সমস্যা হয়েছে।", "Something went wrong."))
            val a = reply.optJSONObject("step") ?: return t("বুঝতে পারিনি, আবার বলুন।", "I didn't understand, please say it again.")
            val kind = a.optString("a")

            when (kind) {
                "done" -> return a.optString("text").ifBlank { t("হয়ে গেছে।", "Done.") }
                "fail" -> return a.optString("text").ifBlank { t("এটা করতে পারলাম না।", "I couldn't do that.") }
            }

            val key = a.toString() + (snap?.text?.hashCode() ?: 0)
            repeats = if (key == lastKey) repeats + 1 else 0
            lastKey = key
            if (repeats >= 2) return t("একই জায়গায় আটকে গেছি, তাই থামলাম।", "I'm stuck on the same screen, so I stopped.")

            val obs = try { exec(svc, a, snap) } catch (e: Exception) { "error: ${e.message}" }
            history.add("$step. ${describe(a)} -> $obs")
            if (declined) return t("ঠিক আছে, করিনি।", "Okay, I didn't do it.")
            sleep(when (kind) {
                "open_app" -> 2300L
                "swipe" -> 1100L
                "enter" -> 1400L
                "wait" -> 0L
                "say", "ask", "wait_install" -> 300L
                else -> 900L
            })
        }
        return t("কাজটা শেষ করতে পারিনি, অনেক ধাপ হয়ে গেছে।", "I couldn't finish, it needed too many steps.")
    }

    // ------------------------------------------------------------------ one step

    private fun exec(svc: MxffAccessibilityService, a: JSONObject, snap: MxffAccessibilityService.Snapshot?): String {
        when (a.optString("a")) {
            "click" -> {
                val sn = snap ?: return "no readable screen"
                val id = a.optInt("id", -1)
                val n = sn.nodes.get(id) ?: return "no element [$id] on this screen"
                val label = sn.labels.get(id, "")
                gate(label)?.let { return it }
                return if (svc.click(n)) "clicked [$id] $label" else "click failed on [$id]"
            }
            "type" -> {
                val sn = snap ?: return "no readable screen"
                val id = a.optInt("id", -1)
                val n = sn.nodes.get(id) ?: return "no element [$id] on this screen"
                if (n.isPassword) return "refused: password field"
                val text = a.optString("text").take(2000)
                lastTyped = text
                return if (svc.setText(n, text)) "typed into [$id]" else "typing failed on [$id]"
            }
            "enter" -> {
                val f = svc.focusedInput()
                if (f != null && lastTyped.isNotBlank() && isMessageField(f)) {
                    if (!host.confirm(t("\"$lastTyped\" পাঠাব?", "Send \"$lastTyped\"?"))) { declined = true; return "user declined" }
                    lastTyped = ""
                }
                return if (svc.imeEnter()) "pressed enter" else "enter not available, press the on-screen button instead"
            }
            "swipe" -> return if (svc.swipe(a.optString("dir"))) "swiped ${a.optString("dir")}" else "swipe failed"
            "scroll" -> {
                val sn = snap ?: return "no readable screen"
                val n = sn.nodes.get(a.optInt("id", -1)) ?: return "no such element"
                return if (svc.scrollNode(n, a.optString("dir") != "backward")) "scrolled" else "cannot scroll there; use swipe"
            }
            "back" -> return if (svc.back()) "went back" else "back failed"
            "home" -> return if (svc.home()) "went home" else "home failed"
            "wait" -> { sleep(a.optLong("ms", 1500).coerceIn(500, 8000)); return "waited" }
            "open_app" -> {
                val name = a.optString("name")
                val r = PhoneActions.run(ctx, JSONObject().put("type", "open_app").put("name", name))
                return if (r.optBoolean("ok")) "opened $name" else "NOT INSTALLED: $name (ask the user with install_app)"
            }
            "install_app" -> return installApp(a.optString("name"))
            "wait_install" -> return waitInstall(a.optString("name"))
            "say" -> { host.say(a.optString("text")); return "spoken" }
            "ask" -> {
                val r = host.ask(a.optString("text"))
                return if (r.isNullOrBlank()) "user did not answer" else "user answered: $r"
            }
        }
        return "unknown action"
    }

    /** Presses that need the user's voice OK first. Returns null to go ahead, or an observation if the user said no. */
    private fun gate(label: String): String? {
        val parts = label.split(" | ")
        val risky = parts.any { RISKY_RE.containsMatchIn(it) || PRICE_RE.containsMatchIn(it.trim()) }
        val send = parts.any { SEND_RE.containsMatchIn(it) }
        if (!risky && !send) return null
        val q = when {
            risky -> t("\"$label\" চাপব? নিশ্চিত?", "Press \"$label\"? Are you sure?")
            lastTyped.isNotBlank() -> t("\"$lastTyped\" পাঠাব?", "Send \"$lastTyped\"?")
            else -> t("পাঠাব?", "Send it?")
        }
        if (host.confirm(q)) { if (send) lastTyped = ""; return null }
        declined = true
        return "user declined"
    }

    private fun isMessageField(n: AccessibilityNodeInfo): Boolean {
        val hint = if (android.os.Build.VERSION.SDK_INT >= 26) n.hintText?.toString().orEmpty() else ""
        val s = "$hint ${n.contentDescription} ${n.viewIdResourceName}"
        return MESSAGE_FIELD_RE.containsMatchIn(s)
    }

    private fun installApp(name: String): String {
        if (name.isBlank()) return "no app name"
        PhoneActions.findPackage(ctx, name)?.let { return "$name is already installed; use open_app" }
        val key = name.trim().lowercase()
        if (key !in approvedInstalls) {
            val ok = host.confirm(t("$name অ্যাপটা ফোনে নেই। Play Store থেকে ইনস্টল করে খুলব?", "$name is not on this phone. Install it from Play Store and open it?"))
            if (!ok) { declined = true; return "user declined install" }
            approvedInstalls.add(key)
        }
        val pkg = PhoneActions.KNOWN_APPS[key]?.firstOrNull()
        val uri = if (pkg != null) "market://details?id=$pkg" else "market://search?q=${Uri.encode(name)}&c=apps"
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "opened Play Store for $name. Now press its Install button, then use wait_install"
        } catch (e: Exception) { "could not open Play Store" }
    }

    private fun waitInstall(name: String): String {
        val end = System.currentTimeMillis() + 150_000
        while (System.currentTimeMillis() < end) {
            if (cancelled) return "cancelled"
            if (PhoneActions.findPackage(ctx, name) != null) return "$name is installed now; use open_app"
            sleep(2000)
        }
        return "$name is still not installed after 2.5 minutes"
    }

    private fun describe(a: JSONObject): String = a.keys().asSequence().joinToString(" ") { k ->
        if (k == "text" && a.optString("a") == "type") "text=\"${a.optString(k).take(60)}\"" else "$k=${a.opt(k).toString().take(60)}"
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) { cancelled = true } }

    // ------------------------------------------------------------------ server

    private fun askServer(goal: String, step: Int, pkg: String, screen: String, image: String?): JSONObject? {
        if (cancelled) return null
        val lang = ctx.getSharedPreferences("mxff", Context.MODE_PRIVATE).getString("lang", "bn") ?: "bn"
        val text = Brain.agentUserText(goal, step, MAX_STEPS, pkg, screen, history.takeLast(14))
        return Brain.agentStep(ctx, text, image, lang)
    }

    companion object {
        private const val MAX_STEPS = 40
        private const val MAX_MILLIS = 6 * 60_000L

        private val SEND_RE = Regex("\\bsend\\b|পাঠা|সেন্ড", RegexOption.IGNORE_CASE)
        private val RISKY_RE = Regex(
            "\\b(buy|purchase|pay|payment|checkout|place order|order now|subscribe|delete|remove account|transfer|send money|donate)\\b|কিনুন|কিনতে|পেমেন্ট|পে করুন|সাবস্ক্রাইব|ডিলিট|মুছে ফেলুন|টাকা পাঠান|ট্রান্সফার|অর্ডার করুন",
            RegexOption.IGNORE_CASE)
        private val PRICE_RE = Regex("^[\\$৳₹€£]\\s?\\d")
        private val MESSAGE_FIELD_RE = Regex("message|msg|chat|reply|compose|মেসেজ|বার্তা", RegexOption.IGNORE_CASE)
        private val FINANCE_RE = Regex("bkash|nagad|konasl|dbbl|paypal|paytm|phonepe|googlepay|gpay|apps\\.nbu\\.paisa|wallet|bank|upay|tap\\.?pay", RegexOption.IGNORE_CASE)
    }
}
