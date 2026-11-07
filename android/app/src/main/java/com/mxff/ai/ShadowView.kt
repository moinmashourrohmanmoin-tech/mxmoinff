package com.mxff.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Two looks, chosen by the size of the window it lives in:
 *  - small window  -> dim "shadow" orb (idle / listening / thinking / sleeping)
 *  - large window  -> JARVIS-style holographic head with rotating rings and a caption (while MXFF replies)
 */
class ShadowView(ctx: Context) : View(ctx) {
    companion object {
        const val IDLE = 0
        const val LISTENING = 1
        const val THINKING = 2
        const val SPEAKING = 3
        const val SLEEP = 4

        private const val CYAN = 0xFF22D3EE.toInt()
        private const val BRIGHT = 0xFF8CF6FF.toInt()
        private const val DEEP = 0xFF0E7490.toInt()

        /** Window size (px) of the hologram for a given screen width. */
        fun hudSize(ctx: Context, screenW: Int): Pair<Int, Int> {
            val d = ctx.resources.displayMetrics.density
            val w = min(screenW - 24 * d, 330 * d)
            return Pair(w.toInt(), (w * 1.28f).toInt())
        }
    }

    var mode = IDLE
        set(v) { field = v; invalidate() }
    var level = 0f
    var expand = 1f
        set(v) { field = v; invalidate() }
    var caption = ""
        set(v) { field = v; capKey = ""; invalidate() }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private fun col(base: Int, a: Float): Int = (base and 0x00FFFFFF) or (a.coerceIn(0f, 255f).toInt() shl 24)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var capLayout: StaticLayout? = null
    private var capKey = ""

    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            h.postDelayed(this, if (isHud()) 33L else if (mode == IDLE || mode == SLEEP) 120L else 50L)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); h.post(tick) }
    override fun onDetachedFromWindow() { h.removeCallbacks(tick); super.onDetachedFromWindow() }

    private fun isHud() = width > dp(150f)

    /** Seconds on a 120 s loop, so every rotation speed used below lands back on a whole turn. */
    private fun time(): Float = ((System.nanoTime() / 1_000_000L) % 120_000L) / 1000f

    override fun onDraw(c: Canvas) {
        val t = time()
        if (isHud()) drawHud(c, t) else drawOrb(c, t)
    }

    // ---------------------------------------------------------------- small shadow orb

    private fun glow(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, a: Float) {
        fill.shader = RadialGradient(cx, cy, r, intArrayOf(col(color, a), col(color, a * 0.35f), col(color, 0f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
    }

    private fun drawOrb(c: Canvas, t: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val breathe = 1f + 0.08f * sin(t * 2f * PI.toFloat() * 0.5f)
        when (mode) {
            SLEEP -> {
                glow(c, cx, cy, dp(14f), 0xFF8899A8.toInt(), 70f)
                fill.color = col(0xFF8899A8.toInt(), 110f)
                c.drawCircle(cx, cy, dp(5f), fill)
            }
            IDLE -> {
                // the quiet "shadow": a dim, slowly breathing blob
                glow(c, cx, cy, dp(24f) * breathe, CYAN, 95f)
                fill.color = col(CYAN, 120f)
                c.drawCircle(cx, cy, dp(8f) * breathe, fill)
            }
            LISTENING -> {
                val lv = level.coerceIn(0f, 1f)
                glow(c, cx, cy, dp(28f) + dp(6f) * lv, CYAN, 190f)
                fill.color = col(BRIGHT, 245f)
                c.drawCircle(cx, cy, dp(10f) + dp(5f) * lv, fill)
                line.strokeWidth = dp(1.4f)
                for (i in 0..1) {
                    val p = ((t * 0.8f) + i * 0.5f) % 1f
                    line.color = col(CYAN, 170f * (1f - p))
                    c.drawCircle(cx, cy, dp(11f) + dp(15f) * p, line)
                }
            }
            else -> { // THINKING / SPEAKING (collapsed)
                glow(c, cx, cy, dp(26f), CYAN, 170f)
                fill.color = col(BRIGHT, 235f)
                c.drawCircle(cx, cy, dp(10f), fill)
                line.strokeWidth = dp(2.2f)
                line.color = col(BRIGHT, 230f)
                val r = dp(17f)
                c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), t * 240f, 100f, false, line)
            }
        }
    }

    // ---------------------------------------------------------------- hologram

    private fun drawHud(c: Canvas, t: Float) {
        val e = expand.coerceIn(0f, 1f)
        val w = width.toFloat()
        val hgt = height.toFloat()
        val tau = (2.0 * PI).toFloat()

        // dark glass card
        val card = RectF(dp(6f), dp(6f), w - dp(6f), hgt - dp(6f))
        fill.color = col(0xFF03060F.toInt(), 232f * e)
        c.drawRoundRect(card, dp(26f), dp(26f), fill)
        line.strokeWidth = dp(1.2f)
        line.color = col(CYAN, 80f * e)
        c.drawRoundRect(card, dp(26f), dp(26f), line)

        c.save()
        val sc = 0.8f + 0.2f * e
        c.scale(sc, sc, w / 2f, hgt / 2f)

        val rr = min(w * 0.40f, dp(135f))
        val cx = w / 2f
        val cy = dp(36f) + rr
        val speaking = mode == SPEAKING

        // header
        text.reset(); text.isAntiAlias = true
        text.color = col(CYAN, 170f * e)
        text.textSize = 10f * d * resources.configuration.fontScale
        text.textAlign = Paint.Align.CENTER
        text.letterSpacing = 0.35f
        c.drawText("M X F F  ·  O N L I N E", cx, dp(26f), text)

        // outer ring + 3 rotating arcs
        line.strokeWidth = dp(1.2f); line.color = col(CYAN, 70f * e)
        c.drawCircle(cx, cy, rr, line)
        line.strokeWidth = dp(3f); line.color = col(BRIGHT, 215f * e)
        val outer = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
        for (i in 0..2) c.drawArc(outer, t * 30f + i * 120f, 55f, false, line)

        // tick ring
        val r2 = rr * 0.88f
        c.save(); c.rotate(-t * 15f, cx, cy)
        for (i in 0 until 72) {
            val a = i * 5f * PI.toFloat() / 180f
            val len = if (i % 6 == 0) dp(8f) else dp(3.5f)
            line.strokeWidth = dp(1.1f); line.color = col(CYAN, (if (i % 6 == 0) 160f else 85f) * e)
            c.drawLine(cx + cos(a) * r2, cy + sin(a) * r2, cx + cos(a) * (r2 - len), cy + sin(a) * (r2 - len), line)
        }
        c.restore()

        // inner arcs
        val r3 = rr * 0.72f
        val inner = RectF(cx - r3, cy - r3, cx + r3, cy + r3)
        line.strokeWidth = dp(1.6f); line.color = col(CYAN, 140f * e)
        c.drawArc(inner, t * 45f, 220f, false, line)
        line.color = col(BRIGHT, 120f * e)
        c.drawArc(inner, -t * 45f + 40f, 80f, false, line)

        // voice pulse while speaking
        if (speaking) {
            val p = (t * 0.8f) % 1f
            line.strokeWidth = dp(1.6f); line.color = col(BRIGHT, 110f * (1f - p) * e)
            c.drawCircle(cx, cy, rr * (0.30f + 0.40f * p), line)
        }

        // holographic head (dot grid clipped to head + shoulders)
        val hx = cx
        val hy = cy - rr * 0.06f
        val body = Path()
        body.addOval(RectF(hx - rr * 0.25f, hy - rr * 0.38f, hx + rr * 0.25f, hy + rr * 0.30f), Path.Direction.CW)
        val neck = Path()
        neck.moveTo(hx - rr * 0.09f, hy + rr * 0.26f)
        neck.lineTo(hx - rr * 0.10f, hy + rr * 0.42f)
        neck.cubicTo(hx - rr * 0.45f, hy + rr * 0.48f, hx - rr * 0.55f, hy + rr * 0.60f, hx - rr * 0.58f, hy + rr * 0.72f)
        neck.lineTo(hx + rr * 0.58f, hy + rr * 0.72f)
        neck.cubicTo(hx + rr * 0.55f, hy + rr * 0.60f, hx + rr * 0.45f, hy + rr * 0.48f, hx + rr * 0.10f, hy + rr * 0.42f)
        neck.lineTo(hx + rr * 0.09f, hy + rr * 0.26f)
        neck.close()
        body.op(neck, Path.Op.UNION)

        val top = hy - rr * 0.38f
        val bottom = hy + rr * 0.72f
        val step = maxOf(dp(4.5f), rr / 22f)
        val scanY = top + ((t * 0.25f) % 1f) * (bottom - top)
        c.save()
        c.clipPath(body)
        var y = top
        while (y < bottom) {
            var x = hx - rr * 0.58f
            while (x < hx + rr * 0.58f) {
                var a = 60f + 110f * (0.5f + 0.5f * sin(tau * t * 0.5f + x * 0.045f + y * 0.06f))
                if (abs(y - scanY) < dp(7f)) a = 255f
                fill.color = col(CYAN, a * e)
                c.drawCircle(x, y, dp(1.15f), fill)
                x += step
            }
            y += step
        }
        c.restore()

        // outline with a soft glow
        line.strokeWidth = dp(6f); line.color = col(CYAN, 45f * e)
        c.drawPath(body, line)
        line.strokeWidth = dp(1.2f); line.color = col(BRIGHT, 200f * e)
        c.drawPath(body, line)

        // eyes (blink every 4 s) and mouth (moves while speaking)
        val blink = if ((t % 4f) > 3.85f) 0.25f else 1f
        fill.color = col(BRIGHT, 255f * e)
        for (s in intArrayOf(-1, 1)) {
            val ex = hx + s * rr * 0.10f
            val ey = hy - rr * 0.12f
            c.drawRoundRect(RectF(ex - rr * 0.045f, ey - dp(1.6f) * blink, ex + rr * 0.045f, ey + dp(1.6f) * blink), dp(2f), dp(2f), fill)
        }
        val mh = if (speaking) dp(1.5f) + dp(5f) * abs(sin(tau * t * 1.5f)) * (0.5f + 0.5f * level.coerceIn(0f, 1f) + 0.3f) else dp(1.5f)
        val my = hy + rr * 0.13f
        c.drawRoundRect(RectF(hx - rr * 0.06f, my - mh / 2f, hx + rr * 0.06f, my + mh / 2f), dp(2f), dp(2f), fill)

        // glowing platform under the head
        val py = cy + rr * 0.80f
        for (i in 0..2) {
            val k = 1f - i * 0.28f
            line.strokeWidth = dp(1.4f); line.color = col(CYAN, (170f - i * 45f) * e)
            c.drawOval(RectF(cx - rr * 0.60f * k, py - rr * 0.10f * k, cx + rr * 0.60f * k, py + rr * 0.10f * k), line)
        }

        // status + caption
        val label = when (mode) { SPEAKING -> "S P E A K I N G"; THINKING -> "T H I N K I N G"; LISTENING -> "L I S T E N I N G"; else -> "R E A D Y" }
        text.reset(); text.isAntiAlias = true
        text.color = col(CYAN, 150f * e)
        text.textSize = 10f * d * resources.configuration.fontScale
        text.textAlign = Paint.Align.CENTER
        text.letterSpacing = 0.3f
        val labelY = cy + rr + dp(22f)
        c.drawText(label, cx, labelY, text)

        val cw = (w - dp(44f)).toInt()
        val key = "$caption|$cw"
        if (capKey != key && cw > 0) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
            tp.color = col(0xFFE6FBFF.toInt(), 245f)
            tp.textSize = 15f * d * resources.configuration.fontScale
            capLayout = StaticLayout.Builder.obtain(caption, 0, caption.length, tp, cw)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            capKey = key
        }
        capLayout?.let {
            c.save(); c.translate(dp(22f), labelY + dp(12f)); it.draw(c); c.restore()
        }
        c.restore()
    }
}
