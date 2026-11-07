package com.mxff.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.SparseArray
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The "eyes and hands" of MXFF. Once the user switches it on in Android Settings → Accessibility, MXFF can
 *  - read what is on the screen of any app (as a compact numbered list),
 *  - tap, type, scroll and swipe in any app,
 *  - take a screenshot so the AI can also "see" videos and pictures.
 * It only works while a task (ScreenAgent) is running; it never acts on its own. All methods below that wait for a
 * gesture must be called from a background thread (ScreenAgent does that).
 */
class MxffAccessibilityService : AccessibilityService() {

    /** One numbered view of the screen. `nodes[id]` is the live node behind line `[id]`. */
    class Snapshot(val pkg: String, val text: String, val nodes: SparseArray<AccessibilityNodeInfo>, val labels: SparseArray<String>)

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* we read the screen on demand only */ }
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    // ------------------------------------------------------------------ reading the screen

    fun snapshot(): Snapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg == packageName) return null // never read MXFF's own window
        val nodes = SparseArray<AccessibilityNodeInfo>()
        val labels = SparseArray<String>()
        val sb = StringBuilder()
        var unlabeled = 0
        var next = 1
        val bounds = Rect()

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || next > MAX_NODES) return
            if (n.isVisibleToUser) {
                val text = n.text?.toString()?.trim().orEmpty()
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""
                val label = listOf(text, desc).filter { it.isNotEmpty() }.distinct().joinToString(" | ").take(160)
                val useful = label.isNotEmpty() || n.isEditable || hint.isNotEmpty() || (n.isClickable && unlabeled < 25)
                if (useful) {
                    if (label.isEmpty() && !n.isEditable && hint.isEmpty()) unlabeled++
                    n.getBoundsInScreen(bounds)
                    val flags = ArrayList<String>()
                    if (n.isEditable) flags.add("edit")
                    if (n.isPassword) flags.add("PASSWORD")
                    if (n.isClickable) flags.add("click")
                    if (n.isScrollable) flags.add("scroll")
                    if (n.isChecked) flags.add("checked")
                    if (n.isSelected) flags.add("selected")
                    val cls = n.className?.toString()?.substringAfterLast('.').orEmpty()
                    val shown = when {
                        n.isPassword -> "(hidden)"
                        label.isNotEmpty() -> "\"$label\""
                        hint.isNotEmpty() -> "(hint: $hint)"
                        else -> "(no label)"
                    }
                    sb.append('[').append(next).append("] ").append(cls).append(' ').append(shown)
                    if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append('}')
                    sb.append(" @").append(bounds.centerX()).append(',').append(bounds.centerY()).append('\n')
                    nodes.put(next, n)
                    labels.put(next, if (n.isPassword) "password field" else label.ifEmpty { hint.ifEmpty { cls } })
                    next++
                }
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        return Snapshot(pkg, sb.toString().take(MAX_CHARS), nodes, labels)
    }

    /** Screenshot as a small base64 JPEG (Android 11+). Null if unavailable or the app blocks screenshots. */
    fun screenshot(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        var out: String? = null
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hw = result.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                        if (wrapped != null) {
                            val soft = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                            val w = 540
                            val h = (soft.height * (w.toFloat() / soft.width)).toInt().coerceAtLeast(1)
                            val small = Bitmap.createScaledBitmap(soft, w, h, true)
                            val bos = ByteArrayOutputStream()
                            small.compress(Bitmap.CompressFormat.JPEG, 55, bos)
                            out = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                        }
                        hw.close()
                    } catch (e: Exception) { /* leave out = null */ }
                    latch.countDown()
                }
                override fun onFailure(errorCode: Int) { latch.countDown() }
            })
            latch.await(4, TimeUnit.SECONDS)
        } catch (e: Exception) { /* not available */ }
        return out
    }

    // ------------------------------------------------------------------ acting

    /** Click a node (or the nearest clickable parent); falls back to a real tap on its centre. */
    fun click(n: AccessibilityNodeInfo): Boolean {
        n.refresh()
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.isEnabled && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            depth++
        }
        val r = Rect(); n.getBoundsInScreen(r)
        return tap(r.centerX(), r.centerY())
    }

    fun setText(n: AccessibilityNodeInfo, text: String): Boolean {
        n.refresh()
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        return try { // some fields refuse SET_TEXT: paste instead
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("mxff", text))
            n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) { false }
    }

    /** Press the keyboard's Enter / Search key in the focused field (Android 11+). */
    fun imeEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** The currently focused text field, if any. */
    fun focusedInput(): AccessibilityNodeInfo? = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    fun scrollNode(n: AccessibilityNodeInfo, forward: Boolean): Boolean =
        n.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    fun tap(x: Int, y: Int): Boolean {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gesture(p, 60)
    }

    /** Finger swipe: "up" moves the finger upwards (shows the NEXT video / scrolls a feed down). */
    fun swipe(dir: String): Boolean {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat(); val h = m.heightPixels.toFloat()
        val p = Path()
        when (dir.lowercase()) {
            "up" -> { p.moveTo(w * 0.5f, h * 0.75f); p.lineTo(w * 0.5f, h * 0.25f) }
            "down" -> { p.moveTo(w * 0.5f, h * 0.25f); p.lineTo(w * 0.5f, h * 0.75f) }
            "left" -> { p.moveTo(w * 0.8f, h * 0.5f); p.lineTo(w * 0.2f, h * 0.5f) }
            "right" -> { p.moveTo(w * 0.2f, h * 0.5f); p.lineTo(w * 0.8f, h * 0.5f) }
            else -> return false
        }
        return gesture(p, 280)
    }

    private fun gesture(path: Path, ms: Long): Boolean {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val latch = CountDownLatch(1)
        var done = false
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        if (!ok) return false
        latch.await(3, TimeUnit.SECONDS)
        return done
    }

    companion object {
        private const val MAX_NODES = 220
        private const val MAX_CHARS = 7000
        @Volatile var instance: MxffAccessibilityService? = null
        val ready get() = instance != null
    }
}
