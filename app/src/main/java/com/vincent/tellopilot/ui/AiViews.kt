package com.vincent.tellopilot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.vincent.tellopilot.ai.AiAction
import com.vincent.tellopilot.ai.Navigator

/** Barre de progression fine, style HUD. */
class ThinBar(c: Context) : View(c) {
    var progress = 0f; set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var indeterminate = false; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), Hud.dpi(context, 4))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2
        p.shader = null; p.color = 0x26FFFFFF
        c.drawRoundRect(0f, 0f, w, h, r, r, p)
        p.shader = LinearGradient(0f, 0f, w, 0f, Hud.alpha(Hud.CYAN, 0.5f), Hud.CYAN, Shader.TileMode.CLAMP)
        if (indeterminate) {
            val t = (SystemClock.uptimeMillis() % 1400) / 1400f
            val seg = w * 0.3f
            val x = -seg + t * (w + seg)
            c.drawRoundRect(x.coerceAtLeast(0f), 0f, (x + seg).coerceAtMost(w), h, r, r, p)
            postInvalidateOnAnimation()
        } else {
            c.drawRoundRect(0f, 0f, w * progress, h, r, r, p)
        }
    }
}

/** Probabilités des actions proposées par d1 : la décision, rendue lisible. */
class DecisionBars(c: Context) : View(c) {
    private var rows: List<Pair<AiAction, Double>> = emptyList()
    private var chosen: AiAction? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val n = 4

    fun set(ranked: List<Pair<AiAction, Double>>, executed: AiAction?) {
        rows = ranked.take(n); chosen = executed
        invalidate()
    }

    fun clear() { rows = emptyList(); chosen = null; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), Hud.dpi(context, 22 * n))

    override fun onDraw(c: Canvas) {
        val rowH = height / n.toFloat()
        val labelW = Hud.dp(context, 112f)
        val pctW = Hud.dp(context, 40f)
        val barL = labelW
        val barR = width - pctW
        p.textSize = Hud.dp(context, 12f)
        for (i in 0 until n) {
            val cy = rowH * i + rowH / 2
            val row = rows.getOrNull(i)
            p.shader = null
            p.color = 0x1AFFFFFF
            val bh = Hud.dp(context, 6f)
            c.drawRoundRect(barL, cy - bh / 2, barR, cy + bh / 2, bh / 2, bh / 2, p)
            if (row == null) continue
            val (a, prob) = row
            val hot = a == chosen || (chosen == null && i == 0)
            val col = if (a == chosen) Hud.CYAN else if (hot) Hud.WHITE else Hud.MUTED
            p.typeface = if (a == chosen) Hud.LABEL else Hud.TEXT
            p.color = col
            c.drawText(a.label, 0f, cy + Hud.dp(context, 4f), p)
            p.color = if (a == chosen) Hud.CYAN else Hud.alpha(Hud.WHITE, if (hot) 0.7f else 0.35f)
            c.drawRoundRect(barL, cy - bh / 2, barL + (barR - barL) * prob.toFloat(), cy + bh / 2, bh / 2, bh / 2, p)
            p.typeface = Hud.MONO; p.color = col
            val t = "${(prob * 100).toInt()}%"
            c.drawText(t, width - p.measureText(t), cy + Hud.dp(context, 4f), p)
        }
    }
}

/** Panneau de mise en route du pilote IA. */
class AiPanel(c: Context, presets: List<String>, speed: Int, onSpeed: (Int) -> Unit, onClose: () -> Unit) : LinearLayout(c) {
    val status: TextView
    val bar = ThinBar(c)
    val primary: TextView
    val input: EditText
    val analyze: TextView
    val launch: TextView
    val diag: TextView
    val diagText: TextView
    private val form: LinearLayout

    init {
        orientation = VERTICAL
        background = glassBg(c, 22f)
        val pad = Hud.dpi(c, 20)
        setPadding(pad, Hud.dpi(c, 12), pad, pad)
        isClickable = true

        addView(LinearLayout(c).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(IconView(c, Icon.AI), LayoutParams(Hud.dpi(c, 22), Hud.dpi(c, 22)).apply { marginEnd = Hud.dpi(c, 10) })
            addView(TextView(c).apply {
                text = "PILOTE IA"
                setTextColor(Hud.CYAN); typeface = Hud.LABEL; textSize = 15f; letterSpacing = 0.18f
            })
            addView(TextView(c).apply {
                text = "  ·  d1-2B, 100 % sur le téléphone"
                setTextColor(Hud.MUTED); typeface = Hud.TEXT; textSize = 12f
            }, LayoutParams(0, WRAP_CONTENT, 1f))
            addView(GlassButton(c, Icon.CLOSE).apply { setOnClickListener { onClose() } },
                LayoutParams(Hud.dpi(c, 40), Hud.dpi(c, 40)))
        })

        status = TextView(c).apply {
            setTextColor(Hud.WHITE); typeface = Hud.TEXT_MEDIUM; textSize = 13f
            setPadding(0, Hud.dpi(c, 10), 0, Hud.dpi(c, 8))
        }
        addView(status)
        addView(bar, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        primary = pillButton(c, "") {}
        addView(primary, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = Hud.dpi(c, 12) })

        form = LinearLayout(c).apply { orientation = VERTICAL }
        input = EditText(c).apply {
            hint = "Ex. : va vers la porte"
            setHintTextColor(Hud.DIM)
            setTextColor(Hud.WHITE)
            typeface = Hud.TEXT
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            background = glassBg(c, 12f, 0x14FFFFFF, Hud.alpha(Hud.CYAN, 0.45f))
            val h = Hud.dpi(c, 14); val v = Hud.dpi(c, 10)
            setPadding(h, v, h, v)
        }
        form.addView(input, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Hud.dpi(c, 14) })

        val chips = LinearLayout(c).apply { orientation = HORIZONTAL }
        for (pr in presets) {
            chips.addView(TextView(c).apply {
                text = pr
                setTextColor(Hud.MUTED); typeface = Hud.TEXT; textSize = 12f
                val h = Hud.dpi(c, 12); val v = Hud.dpi(c, 6)
                setPadding(h, v, h, v)
                background = glassBg(c, 14f, 0x0FFFFFFF, 0x33FFFFFF)
                isClickable = true
                setOnClickListener { input.setText(pr); input.setSelection(pr.length) }
            }, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = Hud.dpi(c, 8) })
        }
        form.addView(HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Hud.dpi(c, 10) })

        val actions = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        analyze = pillButton(c, "ANALYSER L'IMAGE", Hud.WHITE) {}
        launch = pillButton(c, "LANCER LA CONSIGNE") {}
        actions.addView(analyze)
        actions.addView(launch, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = Hud.dpi(c, 10) })
        form.addView(actions, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = Hud.dpi(c, 14) })
        form.addView(TextView(c).apply {
            text = "VITESSE IA  ·  taille de l'image analysée"
            setTextColor(Hud.CYAN_SOFT); typeface = Hud.LABEL; textSize = 11f; letterSpacing = 0.14f
            setPadding(0, Hud.dpi(c, 14), 0, Hud.dpi(c, 6))
        })
        form.addView(Segmented(c, listOf("RAPIDE", "ÉQUILIBRÉE", "PRÉCISE"), speed, onSpeed), LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val diagRow = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        diag = TextView(c).apply {
            text = "TESTER LA VITESSE"
            setTextColor(Hud.CYAN_SOFT); typeface = Hud.LABEL; textSize = 11.5f; letterSpacing = 0.12f
            val h = Hud.dpi(c, 4); val v = Hud.dpi(c, 8)
            setPadding(0, v, h, v)
            isClickable = true
        }
        diagRow.addView(diag)
        form.addView(diagRow, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = Hud.dpi(c, 6) })
        diagText = TextView(c).apply {
            setTextColor(Hud.WHITE); typeface = Hud.MONO; textSize = 10.5f
            visibility = View.GONE
            background = glassBg(c, 10f, 0x10FFFFFF, 0x22FFFFFF)
            val p = Hud.dpi(c, 10)
            setPadding(p, p, p, p)
        }
        form.addView(diagText, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Hud.dpi(c, 4) })
        form.addView(TextView(c).apply {
            text = "Le drone avance par petits pas : il s'immobilise, d1 regarde l'image, choisit une action, " +
                "le drone l'exécute. Touche un joystick pour reprendre la main à tout moment."
            setTextColor(Hud.DIM); typeface = Hud.TEXT; textSize = 11f
            setPadding(0, Hud.dpi(c, 12), 0, 0)
        })
        addView(form)
    }

    fun showForm(show: Boolean) { form.visibility = if (show) View.VISIBLE else View.GONE }
}

/** Bandeau compact pendant que le pilote IA vole. */
@SuppressLint("ViewConstructor")
class AiHud(c: Context, onStop: () -> Unit) : LinearLayout(c) {
    private val title: TextView
    private val phase: TextView
    private val instruction: TextView
    private val bars = DecisionBars(c)
    private val note: TextView
    private val detail: TextView
    private var thinkingSince = 0L
    private var thinkingLabel = ""

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = glassBg(c, 18f, Hud.GLASS, Hud.alpha(Hud.CYAN, 0.6f))
        val pad = Hud.dpi(c, 14)
        setPadding(pad, Hud.dpi(c, 10), Hud.dpi(c, 10), Hud.dpi(c, 10))
        isClickable = true

        val col = LinearLayout(c).apply { orientation = VERTICAL }
        val head = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title = TextView(c).apply { setTextColor(Hud.CYAN); typeface = Hud.LABEL; textSize = 12f; letterSpacing = 0.14f }
        phase = TextView(c).apply { setTextColor(Hud.WHITE); typeface = Hud.LABEL; textSize = 12f; letterSpacing = 0.1f }
        head.addView(title)
        head.addView(phase, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = Hud.dpi(c, 10) })
        col.addView(head)
        instruction = TextView(c).apply {
            setTextColor(Hud.MUTED); typeface = Hud.TEXT; textSize = 12f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, Hud.dpi(c, 2), 0, Hud.dpi(c, 6))
        }
        col.addView(instruction)
        col.addView(bars, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        note = TextView(c).apply {
            setTextColor(Hud.AMBER); typeface = Hud.TEXT; textSize = 11f
            visibility = View.GONE
        }
        col.addView(note)
        detail = TextView(c).apply {
            setTextColor(Hud.MUTED); typeface = Hud.MONO; textSize = 9.5f
            maxLines = 3
            visibility = View.GONE
        }
        col.addView(detail)
        addView(col, LayoutParams(0, WRAP_CONTENT, 1f))
        addView(GlassButton(c, Icon.CLOSE, Hud.RED).apply {
            tint = Hud.RED
            setOnClickListener { onStop() }
        }, LayoutParams(Hud.dpi(c, 46), Hud.dpi(c, 46)).apply { marginStart = Hud.dpi(c, 10) })
    }

    // déclaré après le bloc init : "phase" est alors initialisé
    private val ticker = object : Runnable {
        override fun run() {
            if (thinkingSince == 0L) return
            val s = (SystemClock.uptimeMillis() - thinkingSince) / 1000
            phase.text = "$thinkingLabel D1 RÉFLÉCHIT… $s s".trim()
            postDelayed(this, 500)
        }
    }

    fun begin(text: String, dryRun: Boolean) {
        title.text = if (dryRun) "ANALYSE IA" else "PILOTE IA"
        instruction.text = "« $text »"
        phase.text = ""
        note.visibility = View.GONE
        detail.visibility = View.GONE
        bars.clear()
        stopTicker()
    }

    private fun stopTicker() {
        thinkingSince = 0L
        removeCallbacks(ticker)
    }

    /** Extrait technique (journal du moteur) affiché sous la décision. */
    fun detail(text: String?) {
        detail.text = text ?: ""
        detail.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun phase(p: Navigator.Phase, step: Int, dryRun: Boolean) {
        val s = if (dryRun) "" else "ÉTAPE $step ·"
        phase.setTextColor(if (p == Navigator.Phase.THINKING) Hud.AMBER else Hud.GREEN)
        if (p == Navigator.Phase.THINKING) {
            thinkingLabel = s
            thinkingSince = SystemClock.uptimeMillis()
            removeCallbacks(ticker)
            ticker.run()
        } else {
            stopTicker()
            phase.text = "$s EXÉCUTION".trim()
        }
    }

    fun decision(d: Navigator.Decision) {
        stopTicker()
        bars.set(d.ranked, d.executed)
        val time = "décidé en %.1f s".format(d.millis / 1000.0)
        if (d.note != null) {
            note.text = "${d.note} · $time"
            note.setTextColor(Hud.AMBER)
        } else {
            note.text = "${d.executed?.label ?: "—"} · $time"
            note.setTextColor(Hud.CYAN)
        }
        note.visibility = View.VISIBLE
    }

    fun finished(msg: String, success: Boolean) {
        stopTicker()
        phase.text = msg.uppercase()
        phase.setTextColor(if (success) Hud.GREEN else Hud.AMBER)
    }
}
