package com.vincent.tellopilot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/** Charte graphique du HUD : verre fumé, filets cyan, chiffres en monospace. */
object Hud {
    const val CYAN = 0xFF00E5FF.toInt()
    const val CYAN_SOFT = 0x6600E5FF
    const val CYAN_FAINT = 0x2600E5FF
    const val GLASS = 0xB30A0F16.toInt()
    const val GLASS_LIGHT = 0x660A0F16
    const val STROKE = 0x4D7FF3FF
    const val WHITE = 0xF2FFFFFF.toInt()
    const val MUTED = 0x99FFFFFF.toInt()
    const val DIM = 0x4DFFFFFF
    const val AMBER = 0xFFFFB020.toInt()
    const val RED = 0xFFFF3B5C.toInt()
    const val GREEN = 0xFF3DFFA2.toInt()

    val LABEL: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val TEXT: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val TEXT_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val MONO: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density
    fun dpi(c: Context, v: Int) = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun alpha(color: Int, a: Float) = Color.argb((Color.alpha(color) * a).toInt().coerceIn(0, 255),
        Color.red(color), Color.green(color), Color.blue(color))
}

enum class Icon { TAKEOFF, LAND, MODES, SETTINGS, PHOTO, VIDEO, CLOSE, FLIP, THROW, PALM, BOUNCE, ROTATE, UPAWAY, POWER, WIFI, CHECK, AI, STOP }

/** Icônes au trait, dessinées sur une grille 24×24. */
object Icons {
    private val path = Path()
    private val rect = RectF()

    fun draw(c: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, color: Int, stroke: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = stroke * 24f / size
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        c.save()
        c.translate(cx - size / 2, cy - size / 2)
        c.scale(size / 24f, size / 24f)
        path.reset()
        when (icon) {
            Icon.TAKEOFF -> {
                line(4f, 20f, 20f, 20f); line(12f, 16f, 12f, 4f)
                poly(7f, 9f, 12f, 4f, 17f, 9f)
            }
            Icon.LAND -> {
                line(4f, 20f, 20f, 20f); line(12f, 4f, 12f, 16f)
                poly(7f, 11f, 12f, 16f, 17f, 11f)
            }
            Icon.MODES -> {
                path.addRoundRect(RectF(4f, 4f, 10.5f, 10.5f), 1.5f, 1.5f, Path.Direction.CW)
                path.addRoundRect(RectF(13.5f, 4f, 20f, 10.5f), 1.5f, 1.5f, Path.Direction.CW)
                path.addRoundRect(RectF(4f, 13.5f, 10.5f, 20f), 1.5f, 1.5f, Path.Direction.CW)
                path.moveTo(16.75f, 13.5f); path.lineTo(20f, 16.75f); path.lineTo(16.75f, 20f)
                path.lineTo(13.5f, 16.75f); path.close()
            }
            Icon.SETTINGS -> {
                path.addCircle(12f, 12f, 3f, Path.Direction.CW)
                path.addCircle(12f, 12f, 6.5f, Path.Direction.CW)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    line(12f + 6.5f * Math.cos(a).toFloat(), 12f + 6.5f * Math.sin(a).toFloat(),
                        12f + 9.5f * Math.cos(a).toFloat(), 12f + 9.5f * Math.sin(a).toFloat())
                }
            }
            Icon.PHOTO -> {
                path.addRoundRect(RectF(3f, 7f, 21f, 19f), 2.5f, 2.5f, Path.Direction.CW)
                path.moveTo(8f, 7f); path.lineTo(9.5f, 4.5f); path.lineTo(14.5f, 4.5f); path.lineTo(16f, 7f)
                path.addCircle(12f, 13f, 3.5f, Path.Direction.CW)
            }
            Icon.VIDEO -> {
                path.addRoundRect(RectF(3f, 7f, 15f, 17f), 2f, 2f, Path.Direction.CW)
                path.moveTo(15f, 10.5f); path.lineTo(21f, 7.5f); path.lineTo(21f, 16.5f); path.lineTo(15f, 13.5f)
            }
            Icon.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Icon.CHECK -> poly(5f, 12.5f, 10f, 17.5f, 19f, 7f)
            Icon.FLIP -> {
                rect.set(5f, 5f, 19f, 19f)
                path.addArc(rect, -60f, 300f)
                poly(15.5f, 2.5f, 15.5f, 6.5f, 19.5f, 6.5f)
                line(9.5f, 12f, 14.5f, 12f)
            }
            Icon.THROW -> {
                path.moveTo(4f, 19f); path.quadTo(8f, 6f, 19f, 6f)
                poly(15f, 2.5f, 19f, 6f, 15f, 9.5f)
                path.addCircle(5f, 19f, 1.2f, Path.Direction.CW)
            }
            Icon.PALM -> {
                path.moveTo(4f, 15f); path.quadTo(12f, 23f, 20f, 15f)
                line(12f, 3f, 12f, 13f)
                poly(8.5f, 9.5f, 12f, 13f, 15.5f, 9.5f)
            }
            Icon.BOUNCE -> {
                path.moveTo(3f, 17f); path.quadTo(6f, 4f, 9f, 17f); path.quadTo(12f, 4f, 15f, 17f); path.quadTo(18f, 4f, 21f, 17f)
                line(2f, 21f, 22f, 21f)
            }
            Icon.ROTATE -> {
                rect.set(3f, 8f, 21f, 16f)
                path.addArc(rect, 200f, 300f)
                poly(18.5f, 9.5f, 20.5f, 12.5f, 17f, 13.5f)
                line(12f, 4f, 12f, 20f)
            }
            Icon.UPAWAY -> {
                line(5f, 19f, 19f, 5f)
                poly(11f, 5f, 19f, 5f, 19f, 13f)
                line(3f, 21f, 9f, 21f)
            }
            Icon.POWER -> {
                rect.set(5f, 5.5f, 19f, 19.5f)
                path.addArc(rect, -60f, 300f)
                line(12f, 3f, 12f, 11f)
            }
            Icon.AI -> {
                // réseau de neurones stylisé : 3 nœuds reliés à un cœur
                line(12f, 12.5f, 12f, 5.5f); line(12f, 12.5f, 6f, 17f); line(12f, 12.5f, 18f, 17f)
                line(12f, 5.5f, 6f, 17f); line(12f, 5.5f, 18f, 17f); line(6f, 17f, 18f, 17f)
                path.addCircle(12f, 4f, 2f, Path.Direction.CW)
                path.addCircle(4.8f, 18f, 2f, Path.Direction.CW)
                path.addCircle(19.2f, 18f, 2f, Path.Direction.CW)
                path.addCircle(12f, 12.5f, 2.6f, Path.Direction.CW)
            }
            Icon.STOP -> {
                // panneau stop (octogone) + point d'exclamation
                for (k in 0 until 8) {
                    val a = Math.toRadians(22.5 + k * 45.0)
                    val x = 12f + 9.5f * Math.cos(a).toFloat(); val y = 12f + 9.5f * Math.sin(a).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                line(12f, 7f, 12f, 13f)
                path.addCircle(12f, 16.6f, 0.5f, Path.Direction.CW)
            }
            Icon.WIFI -> {
                rect.set(2f, 6f, 22f, 26f); path.addArc(rect, 225f, 90f)
                rect.set(6f, 10f, 18f, 22f); path.addArc(rect, 225f, 90f)
                path.addCircle(12f, 17.5f, 1f, Path.Direction.CW)
            }
        }
        c.drawPath(path, p)
        c.restore()
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) { path.moveTo(x1, y1); path.lineTo(x2, y2) }
    private fun poly(vararg pts: Float) {
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
    }
}
