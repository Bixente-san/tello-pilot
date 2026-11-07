package com.vincent.tellopilot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private fun View.dp(v: Float) = Hud.dp(context, v)

/** Fond "verre fumé" pour les panneaux et pastilles. */
fun glassBg(c: Context, radiusDp: Float, fill: Int = Hud.GLASS, stroke: Int = Hud.STROKE) =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = Hud.dp(c, radiusDp)
        setStroke(Hud.dpi(c, 1), stroke)
    }

/** Bouton rond au trait, façon HUD. */
@SuppressLint("ViewConstructor")
class GlassButton(c: Context, icon: Icon, private val accent: Int = Hud.CYAN) : View(c) {
    var icon: Icon = icon; set(v) { field = v; invalidate() }
    var active = false; set(v) { field = v; invalidate() }
    var tint: Int = Hud.WHITE; set(v) { field = v; invalidate() }
    var dimmed = false; set(v) { field = v; alpha = if (v) 0.45f else 1f }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Hud.GLASS }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - dp(2f)
        if (active) {
            glow.shader = RadialGradient(cx, cy, r * 1.15f, intArrayOf(Hud.alpha(accent, 0.45f), 0), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r * 1.15f, glow)
        }
        c.drawCircle(cx, cy, r, fill)
        ring.strokeWidth = if (active) dp(2f) else dp(1f)
        ring.color = if (active) accent else Hud.STROKE
        c.drawCircle(cx, cy, r, ring)
        // petits repères à 45° : touche "instrument"
        ring.strokeWidth = dp(1.5f); ring.color = Hud.alpha(accent, 0.55f)
        for (k in 0 until 4) {
            val a = Math.toRadians(45.0 + k * 90)
            c.drawLine(cx + (r - dp(4f)) * cos(a).toFloat(), cy + (r - dp(4f)) * sin(a).toFloat(),
                cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), ring)
        }
        Icons.draw(c, icon, cx, cy, r * 0.95f, if (active) accent else tint, dp(1.9f))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        return super.performClick()
    }
}

/** Petite vue qui ne dessine qu'une icône (tuiles des modes). */
@SuppressLint("ViewConstructor")
class IconView(c: Context, var icon: Icon, var color: Int = Hud.CYAN) : View(c) {
    override fun onDraw(c: Canvas) {
        Icons.draw(c, icon, width / 2f, height / 2f, min(width, height).toFloat(), color, dp(2f))
    }
}

/** Bandeau d'état en haut de l'écran. */
class StatusBar(c: Context) : View(c) {
    enum class State { NO_WIFI, CONNECTING, READY, FLYING, LANDING }

    var state = State.NO_WIFI; set(v) { field = v; invalidate() }
    var battery = -1; set(v) { if (field != v) { field = v; invalidate() } }
    var wifi = -1; set(v) { if (field != v) { field = v; invalidate() } }
    var heightM = 0f; set(v) { field = v; invalidate() }
    var speed = 0f; set(v) { field = v; invalidate() }
    var flightSeconds = 0; set(v) { if (field != v) { field = v; invalidate() } }
    var fast = false; set(v) { field = v; invalidate() }
    var onSpeedTap: (() -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chip = RectF()
    private val speedChip = RectF()

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, 0f, h, 0xCC000000.toInt(), 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), h, p)
        p.shader = null
        val cy = h * 0.46f
        val pad = dp(18f)

        // Marque
        p.typeface = Hud.MONO; p.textSize = dp(14f); p.color = Hud.CYAN; p.style = Paint.Style.FILL
        var x = pad
        c.drawText("TELLO", x, cy + dp(5f), p)
        x += p.measureText("TELLO") + dp(4f)
        val roomy = width >= dp(800f)
        if (roomy) {
            p.color = Hud.MUTED; p.typeface = Hud.LABEL; p.textSize = dp(10f)
            c.drawText("PILOT", x, cy + dp(5f), p)
            x += p.measureText("PILOT")
        }
        x += dp(14f)

        // Pastille d'état
        val (label, col) = when (state) {
            State.NO_WIFI -> "WI-FI TELLO REQUIS" to Hud.RED
            State.CONNECTING -> "CONNEXION…" to Hud.AMBER
            State.READY -> "PRÊT AU DÉCOLLAGE" to Hud.CYAN
            State.FLYING -> "EN VOL" to Hud.GREEN
            State.LANDING -> "ATTERRISSAGE" to Hud.AMBER
        }
        p.typeface = Hud.LABEL; p.textSize = dp(11f); p.letterSpacing = 0.08f
        val tw = p.measureText(label)
        chip.set(x, cy - dp(12f), x + tw + dp(30f), cy + dp(12f))
        p.color = Hud.alpha(col, 0.14f); c.drawRoundRect(chip, dp(12f), dp(12f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f); p.color = Hud.alpha(col, 0.6f)
        c.drawRoundRect(chip, dp(12f), dp(12f), p)
        p.style = Paint.Style.FILL; p.color = col
        val blink = state == State.CONNECTING && (SystemClock.uptimeMillis() / 500) % 2 == 0L
        if (!blink) c.drawCircle(chip.left + dp(12f), cy, dp(3.5f), p)
        c.drawText(label, chip.left + dp(21f), cy + dp(4f), p)
        p.letterSpacing = 0f
        if (state == State.CONNECTING) postInvalidateDelayed(500)

        // À droite : batterie, Wi-Fi, vitesse, télémétrie
        x = width - pad
        x = drawBattery(c, x, cy) - dp(18f)
        x = drawWifi(c, x, cy) - dp(18f)
        x = drawSpeedChip(c, x, cy) - dp(20f)
        val connected = state == State.READY || state == State.FLYING || state == State.LANDING
        if (roomy) x = drawValue(c, x, cy, "TEMPS", "%d:%02d".format(flightSeconds / 60, flightSeconds % 60), connected) - dp(18f)
        x = drawValue(c, x, cy, "VIT", "%.1f m/s".format(speed), connected) - dp(18f)
        drawValue(c, x, cy, "ALT", "%.1f m".format(heightM), connected)
    }

    private fun drawValue(c: Canvas, right: Float, cy: Float, label: String, value: String, on: Boolean): Float {
        p.style = Paint.Style.FILL
        p.typeface = Hud.MONO; p.textSize = dp(15f); p.color = if (on) Hud.WHITE else Hud.DIM
        val vw = p.measureText(if (on) value else "--")
        c.drawText(if (on) value else "--", right - vw, cy + dp(5.5f), p)
        p.typeface = Hud.LABEL; p.textSize = dp(9.5f); p.color = Hud.CYAN_SOFT; p.letterSpacing = 0.12f
        val lw = p.measureText(label)
        c.drawText(label, right - vw - lw - dp(6f), cy + dp(5f), p)
        p.letterSpacing = 0f
        return right - vw - lw - dp(6f)
    }

    private fun drawSpeedChip(c: Canvas, right: Float, cy: Float): Float {
        val label = if (fast) "RAPIDE" else "LENT"
        p.typeface = Hud.LABEL; p.textSize = dp(11f); p.letterSpacing = 0.1f
        val tw = p.measureText(label)
        speedChip.set(right - tw - dp(34f), cy - dp(13f), right, cy + dp(13f))
        val col = if (fast) Hud.AMBER else Hud.CYAN
        p.style = Paint.Style.FILL; p.color = Hud.alpha(col, 0.16f)
        c.drawRoundRect(speedChip, dp(13f), dp(13f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f); p.color = Hud.alpha(col, 0.7f)
        c.drawRoundRect(speedChip, dp(13f), dp(13f), p)
        // chevrons de vitesse
        p.strokeWidth = dp(1.8f); p.strokeCap = Paint.Cap.ROUND; p.color = col
        val n = if (fast) 2 else 1
        for (i in 0 until n) {
            val sx = speedChip.left + dp(10f) + i * dp(5f)
            c.drawLine(sx, cy - dp(4.5f), sx + dp(4f), cy, p)
            c.drawLine(sx + dp(4f), cy, sx, cy + dp(4.5f), p)
        }
        p.style = Paint.Style.FILL
        c.drawText(label, speedChip.right - tw - dp(10f), cy + dp(4f), p)
        p.letterSpacing = 0f
        return speedChip.left
    }

    private fun drawWifi(c: Canvas, right: Float, cy: Float): Float {
        val bars = when {
            wifi < 0 -> 0
            wifi >= 75 -> 4
            wifi >= 50 -> 3
            wifi >= 30 -> 2
            else -> 1
        }
        val col = when { wifi < 0 -> Hud.DIM; bars <= 1 -> Hud.RED; bars == 2 -> Hud.AMBER; else -> Hud.WHITE }
        val bw = dp(3.5f); val gap = dp(2.5f)
        p.style = Paint.Style.FILL
        for (i in 0 until 4) {
            val bh = dp(5f) + i * dp(3.2f)
            val l = right - (4 - i) * (bw + gap) + gap
            p.color = if (i < bars) col else Hud.DIM
            c.drawRoundRect(l, cy + dp(7f) - bh, l + bw, cy + dp(7f), dp(1f), dp(1f), p)
        }
        return right - 4 * (bw + gap)
    }

    private fun drawBattery(c: Canvas, right: Float, cy: Float): Float {
        val col = when { battery < 0 -> Hud.DIM; battery <= 15 -> Hud.RED; battery <= 30 -> Hud.AMBER; else -> Hud.GREEN }
        p.typeface = Hud.MONO; p.textSize = dp(14f); p.style = Paint.Style.FILL
        p.color = if (battery < 0) Hud.DIM else Hud.WHITE
        val txt = if (battery < 0) "--%" else "$battery%"
        val tw = p.measureText(txt)
        c.drawText(txt, right - tw, cy + dp(5f), p)
        val bx = right - tw - dp(8f) - dp(26f)
        val r = RectF(bx, cy - dp(6.5f), bx + dp(24f), cy + dp(6.5f))
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.3f); p.color = Hud.alpha(col, 0.9f)
        c.drawRoundRect(r, dp(3f), dp(3f), p)
        p.style = Paint.Style.FILL
        c.drawRoundRect(r.right + dp(1f), cy - dp(3f), r.right + dp(3f), cy + dp(3f), dp(1f), dp(1f), p)
        // 5 segments
        if (battery > 0) {
            val segs = ((battery + 19) / 20).coerceIn(1, 5)
            val sw = (r.width() - dp(4f) - dp(4f)) / 5f
            for (i in 0 until segs) {
                val l = r.left + dp(2f) + i * (sw + dp(1f))
                c.drawRect(l, r.top + dp(2.5f), l + sw, r.bottom - dp(2.5f), p)
            }
        }
        return bx
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit = RectF(speedChip).apply { inset(-dp(10f), -dp(10f)) }.contains(e.x, e.y)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return hit
        if (e.actionMasked == MotionEvent.ACTION_UP && hit) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onSpeedTap?.invoke()
        }
        return true
    }
}

/** Réticule central et horizon artificiel. */
class HorizonView(c: Context) : View(c) {
    var pitch = 0f
    var roll = 0f
    var showHorizon = true; set(v) { field = v; invalidate() }
    var live = false; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    fun setAttitude(pitchDeg: Float, rollDeg: Float) {
        pitch = pitchDeg; roll = rollDeg
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val s = dp(1f)
        // Réticule
        p.strokeWidth = 1.6f * s; p.color = Hud.alpha(Hud.WHITE, 0.75f)
        val g = 14 * s; val l = 22 * s
        c.drawLine(cx - l - g, cy, cx - g, cy, p)
        c.drawLine(cx + g, cy, cx + g + l, cy, p)
        c.drawLine(cx, cy + g, cx, cy + g + 8 * s, p)
        p.color = Hud.CYAN
        c.drawCircle(cx, cy, 2 * s, p)
        // Crochets de cadrage
        p.color = Hud.alpha(Hud.WHITE, 0.35f); p.strokeWidth = 1.2f * s
        val bw = min(width, height) * 0.36f; val bh = bw * 0.62f; val k = 16 * s
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
            val x = cx + sx * bw; val y = cy + sy * bh
            c.drawLine(x, y, x - sx * k, y, p)
            c.drawLine(x, y, x, y - sy * k, p)
        }
        if (!showHorizon || !live) return
        // Horizon : tourne avec le roulis, se décale avec le tangage
        c.save()
        c.rotate(-roll, cx, cy)
        val pxPerDeg = 3.2f * s
        val hy = cy + pitch * pxPerDeg
        p.color = Hud.alpha(Hud.CYAN, 0.8f); p.strokeWidth = 1.6f * s
        val hw = min(width, height) * 0.42f
        c.drawLine(cx - hw, hy, cx - 40 * s, hy, p)
        c.drawLine(cx + 40 * s, hy, cx + hw, hy, p)
        p.color = Hud.alpha(Hud.CYAN, 0.45f); p.strokeWidth = 1.1f * s
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Hud.alpha(Hud.CYAN, 0.6f); textSize = 9 * s; typeface = Hud.MONO }
        for (deg in intArrayOf(-20, -10, 10, 20)) {
            val y = hy - deg * pxPerDeg
            val w = if (abs(deg) == 10) 34 * s else 24 * s
            c.drawLine(cx - 60 * s - w, y, cx - 60 * s, y, p)
            c.drawLine(cx + 60 * s, y, cx + 60 * s + w, y, p)
            c.drawText("${abs(deg)}", cx + 64 * s + w, y + 3 * s, tp)
        }
        c.restore()
    }
}

/**
 * Deux joysticks flottants, comme l'app officielle : le stick apparaît là où le pouce
 * se pose (moitié gauche / moitié droite de l'écran) et revient au centre au relâchement.
 * En mode "Flips 8D", un glissement sur l'écran déclenche un flip dans sa direction.
 */
class StickLayer(c: Context) : View(c) {
    var onSticks: ((lx: Float, ly: Float, rx: Float, ry: Float) -> Unit)? = null
    var onFlip: ((dir: Int) -> Unit)? = null
    var leftLabel = "ALTITUDE · ROTATION"; set(v) { field = v; invalidate() }
    var rightLabel = "DÉPLACEMENT"; set(v) { field = v; invalidate() }
    var flipMode = false; set(v) { field = v; release(left); release(right); invalidate() }

    private inner class Stick {
        var pid = -1
        var cx = 0f; var cy = 0f
        var x = 0f; var y = 0f   // -1..1, y positif = haut
        var homeX = 0f; var homeY = 0f
        var edge = false
    }
    private val left = Stick()
    private val right = Stick()
    private val radius get() = dp(64f)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private var swipePid = -1
    private var sx = 0f; private var sy = 0f; private var ex = 0f; private var ey = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        left.homeX = dp(168f); left.homeY = h - dp(112f)
        right.homeX = w - dp(168f); right.homeY = h - dp(112f)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (flipMode) return flipTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val tx = e.getX(i); val ty = e.getY(i)
                val s = if (tx < width / 2f) left else right
                if (s.pid == -1) {
                    s.pid = e.getPointerId(i)
                    val r = radius
                    s.cx = tx.coerceIn(r + dp(8f), width - r - dp(8f))
                    s.cy = ty.coerceIn(r + dp(60f), height - r - dp(8f))
                    s.x = 0f; s.y = 0f; s.edge = false
                    update(s, tx, ty)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (s in arrayOf(left, right)) {
                    if (s.pid == -1) continue
                    val i = e.findPointerIndex(s.pid)
                    if (i >= 0) update(s, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (left.pid == id) release(left)
                if (right.pid == id) release(right)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { release(left); release(right) }
        }
        emit()
        invalidate()
        return true
    }

    private fun update(s: Stick, tx: Float, ty: Float) {
        var dx = (tx - s.cx) / radius
        var dy = -(ty - s.cy) / radius
        val d = hypot(dx, dy)
        if (d > 1f) { dx /= d; dy /= d }
        val atEdge = d >= 0.98f
        if (atEdge && !s.edge) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        s.edge = atEdge
        s.x = dx; s.y = dy
    }

    private fun release(s: Stick) { s.pid = -1; s.x = 0f; s.y = 0f; s.edge = false }

    fun reset() { release(left); release(right); emit(); invalidate() }

    private fun emit() = onSticks?.invoke(left.x, left.y, right.x, right.y)

    private fun flipTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { swipePid = e.getPointerId(0); sx = e.x; sy = e.y; ex = sx; ey = sy }
            MotionEvent.ACTION_MOVE -> { ex = e.x; ey = e.y }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - sx; val dy = sy - e.y
                if (swipePid != -1 && hypot(dx, dy) > dp(70f)) {
                    // secteurs de 45° : 0 = haut
                    val ang = (Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())) + 360 + 22.5) % 360
                    val dir = when ((ang / 45).toInt()) {
                        0 -> 0   // avant
                        1 -> 7   // avant-droite
                        2 -> 3   // droite
                        3 -> 6   // arrière-droite
                        4 -> 2   // arrière
                        5 -> 5   // arrière-gauche
                        6 -> 1   // gauche
                        else -> 4 // avant-gauche
                    }
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onFlip?.invoke(dir)
                }
                swipePid = -1
            }
            MotionEvent.ACTION_CANCEL -> swipePid = -1
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        if (flipMode) { drawFlipHint(c); return }
        drawStick(c, left, leftLabel)
        drawStick(c, right, rightLabel)
    }

    private fun drawStick(c: Canvas, s: Stick, label: String) {
        val active = s.pid != -1
        val cx = if (active) s.cx else s.homeX
        val cy = if (active) s.cy else s.homeY
        val r = radius
        val a = if (active) 1f else 0.45f
        // base
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r, intArrayOf(Hud.alpha(Hud.GLASS_LIGHT, a), Hud.alpha(Hud.GLASS, a)), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f); p.color = Hud.alpha(Hud.STROKE, a)
        c.drawCircle(cx, cy, r, p)
        c.drawCircle(cx, cy, r * 0.5f, p.apply { color = Hud.alpha(Hud.CYAN_FAINT, a) })
        // graduations
        p.color = Hud.alpha(Hud.CYAN, 0.7f * a); p.strokeWidth = dp(2f); p.strokeCap = Paint.Cap.ROUND
        for (k in 0 until 4) {
            val ang = Math.toRadians(k * 90.0)
            c.drawLine(cx + (r - dp(9f)) * cos(ang).toFloat(), cy + (r - dp(9f)) * sin(ang).toFloat(),
                cx + (r - dp(3f)) * cos(ang).toFloat(), cy + (r - dp(3f)) * sin(ang).toFloat(), p)
        }
        // arc d'intensité
        if (active && (s.x != 0f || s.y != 0f)) {
            val mag = min(1f, hypot(s.x, s.y))
            val dirDeg = Math.toDegrees(atan2(-s.y.toDouble(), s.x.toDouble())).toFloat()
            p.color = if (s.edge) Hud.AMBER else Hud.CYAN; p.strokeWidth = dp(3f)
            val sweep = 20f + 70f * mag
            c.drawArc(cx - r, cy - r, cx + r, cy + r, dirDeg - sweep / 2, sweep, false, p)
            p.strokeWidth = dp(1.2f); p.color = Hud.CYAN_SOFT
            c.drawLine(cx, cy, cx + s.x * r, cy - s.y * r, p)
        }
        // manette
        val kx = cx + s.x * r; val ky = cy - s.y * r
        val kr = dp(25f)
        p.style = Paint.Style.FILL
        p.color = Hud.alpha(Hud.GLASS, a)
        c.drawCircle(kx, ky, kr, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(2f); p.color = Hud.alpha(Hud.CYAN, a)
        c.drawCircle(kx, ky, kr, p)
        p.style = Paint.Style.FILL; p.color = Hud.alpha(Hud.CYAN, 0.9f * a)
        c.drawCircle(kx, ky, dp(4f), p)
        // libellé
        if (!active) {
            p.typeface = Hud.LABEL; p.textSize = dp(10f); p.letterSpacing = 0.14f; p.color = Hud.MUTED
            val tw = p.measureText(label)
            c.drawText(label, cx - tw / 2, cy + r + dp(18f), p)
            p.letterSpacing = 0f
        }
    }

    private fun drawFlipHint(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.5f); p.color = Hud.CYAN_SOFT
        val r = dp(70f)
        c.drawCircle(cx, cy, r, p)
        for (k in 0 until 8) {
            val a = Math.toRadians(k * 45.0 - 90)
            val x1 = cx + (r + dp(10f)) * cos(a).toFloat(); val y1 = cy + (r + dp(10f)) * sin(a).toFloat()
            val x2 = cx + (r + dp(26f)) * cos(a).toFloat(); val y2 = cy + (r + dp(26f)) * sin(a).toFloat()
            c.drawLine(x1, y1, x2, y2, p)
        }
        if (swipePid != -1) {
            p.color = Hud.CYAN; p.strokeWidth = dp(4f)
            c.drawLine(sx, sy, ex, ey, p)
            p.style = Paint.Style.FILL
            c.drawCircle(ex, ey, dp(8f), p)
        }
        p.style = Paint.Style.FILL; p.typeface = Hud.LABEL; p.textSize = dp(13f); p.letterSpacing = 0.12f; p.color = Hud.WHITE
        val t = "GLISSE DANS UNE DIRECTION POUR FAIRE UN FLIP"
        c.drawText(t, cx - p.measureText(t) / 2, cy + r + dp(52f), p)
        p.letterSpacing = 0f
    }
}

/** "Glisser pour décoller / atterrir", comme l'app officielle. */
class SlideConfirm(c: Context) : View(c) {
    var label = "GLISSER POUR DÉCOLLER"; set(v) { field = v; invalidate() }
    var icon = Icon.TAKEOFF; set(v) { field = v; invalidate() }
    var accent = Hud.CYAN; set(v) { field = v; invalidate() }
    var onConfirm: (() -> Unit)? = null

    private var progress = 0f
    private var dragging = false
    private var grab = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun resetThumb() { progress = 0f; dragging = false; invalidate() }

    private val thumbR get() = height / 2f - dp(5f)
    private val travel get() = width - 2 * (thumbR + dp(5f))

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        val rr = h / 2
        p.style = Paint.Style.FILL; p.color = Hud.GLASS
        c.drawRoundRect(0f, 0f, w, h, rr, rr, p)
        // remplissage de progression
        val tx = dp(5f) + thumbR + progress * travel
        p.shader = LinearGradient(0f, 0f, tx, 0f, Hud.alpha(accent, 0.05f), Hud.alpha(accent, 0.35f), Shader.TileMode.CLAMP)
        c.drawRoundRect(0f, 0f, tx + thumbR, h, rr, rr, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.2f); p.color = Hud.alpha(accent, 0.6f)
        c.drawRoundRect(dp(0.6f), dp(0.6f), w - dp(0.6f), h - dp(0.6f), rr, rr, p)
        // texte + chevrons animés
        p.style = Paint.Style.FILL; p.typeface = Hud.LABEL; p.textSize = dp(13f); p.letterSpacing = 0.14f
        p.color = Hud.alpha(Hud.WHITE, 0.85f * (1 - progress))
        val tw = p.measureText(label)
        c.drawText(label, (w - tw) / 2 + thumbR / 2, h / 2 + dp(4.5f), p)
        p.letterSpacing = 0f
        val phase = (SystemClock.uptimeMillis() % 1200) / 1200f
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(2f); p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 3) {
            val a = ((1 - abs(phase * 3 - i - 0.5f)).coerceIn(0f, 1f)) * (1 - progress)
            p.color = Hud.alpha(accent, a)
            val x = w - dp(48f) + i * dp(9f)
            c.drawLine(x, h / 2 - dp(6f), x + dp(5f), h / 2, p)
            c.drawLine(x + dp(5f), h / 2, x, h / 2 + dp(6f), p)
        }
        // pouce
        p.style = Paint.Style.FILL; p.color = Hud.alpha(accent, 0.22f)
        c.drawCircle(tx, h / 2, thumbR, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(2f); p.color = accent
        c.drawCircle(tx, h / 2, thumbR, p)
        Icons.draw(c, icon, tx, h / 2, thumbR * 1.05f, Hud.WHITE, dp(2f))
        if (!dragging && progress == 0f) postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val tx = dp(5f) + thumbR + progress * travel
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (abs(e.x - tx) > thumbR * 1.6f) return false
                dragging = true; grab = e.x - tx
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                progress = ((e.x - grab - dp(5f) - thumbR) / travel).coerceIn(0f, 1f)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (progress > 0.9f) {
                    progress = 1f
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onConfirm?.invoke()
                } else {
                    progress = 0f
                }
                invalidate()
            }
        }
        return true
    }
}

/** Contrôle segmenté pour les réglages. */
@SuppressLint("ViewConstructor")
class Segmented(c: Context, private val options: List<String>, selected: Int, private val onPick: (Int) -> Unit) : View(c) {
    var selected = selected; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), Hud.dpi(context, 36))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = dp(9f)
        p.style = Paint.Style.FILL; p.color = 0x33FFFFFF
        c.drawRoundRect(0f, 0f, w, h, r, r, p)
        val sw = w / options.size
        val l = selected * sw
        p.color = Hud.alpha(Hud.CYAN, 0.18f)
        c.drawRoundRect(l + dp(2f), dp(2f), l + sw - dp(2f), h - dp(2f), r - dp(2f), r - dp(2f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.2f); p.color = Hud.CYAN
        c.drawRoundRect(l + dp(2f), dp(2f), l + sw - dp(2f), h - dp(2f), r - dp(2f), r - dp(2f), p)
        p.style = Paint.Style.FILL; p.typeface = Hud.LABEL; p.textSize = dp(12f); p.letterSpacing = 0.06f
        options.forEachIndexed { i, o ->
            p.color = if (i == selected) Hud.CYAN else Hud.MUTED
            val tw = p.measureText(o)
            c.drawText(o, i * sw + (sw - tw) / 2, h / 2 + dp(4.3f), p)
        }
        p.letterSpacing = 0f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = (e.x / (width / options.size.toFloat())).toInt().coerceIn(0, options.size - 1)
            if (i != selected) {
                selected = i
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onPick(i)
            }
        }
        return true
    }
}

/** Radar animé de l'écran de connexion. */
class RadarView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f - dp(2f)
        val t = (SystemClock.uptimeMillis() % 2000) / 2000f
        p.strokeWidth = dp(1f)
        for (k in 1..3) { p.color = Hud.alpha(Hud.CYAN, 0.18f); c.drawCircle(cx, cy, r * k / 3f, p) }
        for (k in 0 until 2) {
            val f = (t + k * 0.5f) % 1f
            p.color = Hud.alpha(Hud.CYAN, 0.8f * (1 - f)); p.strokeWidth = dp(2f)
            c.drawCircle(cx, cy, r * f, p)
        }
        val a = t * 360f
        p.strokeWidth = dp(2f); p.color = Hud.CYAN
        c.drawArc(cx - r, cy - r, cx + r, cy + r, a, 40f, false, p)
        Icons.draw(c, Icon.WIFI, cx, cy, r * 0.7f, Hud.WHITE, dp(2f))
        postInvalidateOnAnimation()
    }
}

/** Libellé de bouton pilule. */
fun pillButton(c: Context, text: String, accent: Int = Hud.CYAN, onClick: () -> Unit) = TextView(c).apply {
    this.text = text
    setTextColor(accent)
    typeface = Hud.LABEL
    textSize = 13f
    letterSpacing = 0.12f
    gravity = Gravity.CENTER
    val h = Hud.dpi(c, 18); val v = Hud.dpi(c, 10)
    setPadding(h, v, h, v)
    background = glassBg(c, 22f, Hud.alpha(accent, 0.12f), Hud.alpha(accent, 0.8f))
    isClickable = true
    setOnClickListener {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onClick()
    }
}

/** Format court pour une durée en secondes. */
fun mmss(s: Int) = "%d:%02d".format(s / 60, s % 60)
