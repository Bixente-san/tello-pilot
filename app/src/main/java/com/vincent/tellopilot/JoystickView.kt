package com.vincent.tellopilot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Joystick virtuel qui revient au centre quand on le lâche. Sortie x,y dans -1..1 (y positif = haut). */
class JoystickView(context: Context, private val label: String) : View(context) {

    var onMove: ((x: Float, y: Float) -> Unit)? = null

    private var knobX = 0f
    private var knobY = 0f

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255); style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255); style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255); textSize = 30f; textAlign = Paint.Align.CENTER
    }

    private val radius get() = min(width, height) / 2f * 0.85f

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radius, basePaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx + knobX * radius, cy - knobY * radius, radius * 0.35f, knobPaint)
        canvas.drawText(label, cx, cy + radius + 30f, textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var dx = (e.x - width / 2f) / radius
                var dy = -(e.y - height / 2f) / radius
                val d = hypot(dx, dy)
                if (d > 1f) { dx /= d; dy /= d }
                knobX = dx; knobY = dy
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = 0f; knobY = 0f
            }
        }
        onMove?.invoke(knobX, knobY)
        invalidate()
        return true
    }
}
