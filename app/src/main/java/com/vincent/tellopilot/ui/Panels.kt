package com.vincent.tellopilot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/** Réglages persistants. */
class Prefs(c: Context) {
    private val sp = c.getSharedPreferences("tello_pilot", Context.MODE_PRIVATE)
    var fast: Boolean
        get() = sp.getBoolean("fast", false)
        set(v) = sp.edit().putBoolean("fast", v).apply()
    /** 0 = mode 2 (gauche : altitude/rotation), 1 = mode 1 (gauche : avant/rotation) */
    var stickMode: Int
        get() = sp.getInt("stickMode", 0)
        set(v) = sp.edit().putInt("stickMode", v).apply()
    /** 0 douce, 1 normale, 2 directe */
    var sensitivity: Int
        get() = sp.getInt("sensitivity", 1)
        set(v) = sp.edit().putInt("sensitivity", v).apply()
    var bitrate: Int
        get() = sp.getInt("bitrate", 0)
        set(v) = sp.edit().putInt("bitrate", v).apply()
    var wide: Boolean
        get() = sp.getBoolean("wide", false)
        set(v) = sp.edit().putBoolean("wide", v).apply()
    /** index dans ALT_LIMITS */
    var altLimit: Int
        get() = sp.getInt("altLimit", 1)
        set(v) = sp.edit().putInt("altLimit", v).apply()
    /** 0 rapide (petite image), 1 équilibré, 2 précis (grande image) */
    var aiSpeed: Int
        get() = sp.getInt("aiSpeed", 1)
        set(v) = sp.edit().putInt("aiSpeed", v).apply()
    var horizon: Boolean
        get() = sp.getBoolean("horizon", true)
        set(v) = sp.edit().putBoolean("horizon", v).apply()

    companion object {
        val ALT_LIMITS = intArrayOf(5, 10, 20, 30)
    }
}

enum class FlightMode(val title: String, val subtitle: String, val icon: Icon) {
    AI("Pilote IA", "Donne une consigne : d1 décide en local", Icon.AI),
    THROW("Lance et vole", "Lance le drone : il se stabilise tout seul", Icon.THROW),
    PALM("Atterrissage main", "Se pose dans ta main", Icon.PALM),
    BOUNCE("Rebond", "Monte et descend au-dessus d'une surface", Icon.BOUNCE),
    FLIP8D("Flips 8D", "Glisse pour faire un flip dans 8 directions", Icon.FLIP),
    SPIN360("360°", "Tour complet sur place, filmé", Icon.ROTATE),
    UPAWAY("Grand angle", "Recule et monte en filmant", Icon.UPAWAY),
}

private fun title(c: Context, text: String) = TextView(c).apply {
    this.text = text
    setTextColor(Hud.CYAN)
    typeface = Hud.LABEL
    textSize = 15f
    letterSpacing = 0.18f
}

private fun header(c: Context, text: String, onClose: () -> Unit) = LinearLayout(c).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(title(c, text), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    addView(GlassButton(c, Icon.CLOSE).apply { setOnClickListener { onClose() } },
        LinearLayout.LayoutParams(Hud.dpi(c, 40), Hud.dpi(c, 40)))
}

fun buildModesPanel(c: Context, onPick: (FlightMode) -> Unit, onClose: () -> Unit): View {
    val card = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = glassBg(c, 22f)
        val p = Hud.dpi(c, 18)
        setPadding(p, Hud.dpi(c, 12), p, p)
        isClickable = true
    }
    card.addView(header(c, "MODES DE VOL", onClose))
    val grid = GridLayout(c).apply { columnCount = 4 }
    for (m in FlightMode.values()) {
        val tile = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = glassBg(c, 16f, 0x14FFFFFF, Hud.alpha(Hud.CYAN, 0.25f))
            val p = Hud.dpi(c, 14)
            setPadding(p, p, p, p)
            isClickable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onPick(m)
            }
            addView(IconView(c, m.icon), LinearLayout.LayoutParams(Hud.dpi(c, 30), Hud.dpi(c, 30)))
            addView(TextView(c).apply {
                text = m.title
                setTextColor(Hud.WHITE)
                typeface = Hud.TEXT_MEDIUM
                textSize = 14f
                setPadding(0, Hud.dpi(c, 10), 0, Hud.dpi(c, 2))
            })
            addView(TextView(c).apply {
                text = m.subtitle
                setTextColor(Hud.MUTED)
                typeface = Hud.TEXT
                textSize = 11f
                maxLines = 2
            })
        }
        grid.addView(tile, GridLayout.LayoutParams().apply {
            width = Hud.dpi(c, 150)
            height = Hud.dpi(c, 118)
            val m6 = Hud.dpi(c, 5)
            setMargins(m6, m6, m6, m6)
        })
    }
    card.addView(grid, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = Hud.dpi(c, 8) })
    return card
}

fun buildSettingsPanel(c: Context, prefs: Prefs, onChange: () -> Unit, onClose: () -> Unit): View {
    val list = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val p = Hud.dpi(c, 22)
        setPadding(p, Hud.dpi(c, 14), p, p)
    }
    list.addView(header(c, "PARAMÈTRES", onClose))

    fun section(name: String) = list.addView(TextView(c).apply {
        text = name
        setTextColor(Hud.CYAN_SOFT)
        typeface = Hud.LABEL
        textSize = 11f
        letterSpacing = 0.2f
        setPadding(0, Hud.dpi(c, 20), 0, Hud.dpi(c, 4))
    })

    fun row(label: String, hint: String?, options: List<String>, sel: Int, pick: (Int) -> Unit) {
        list.addView(TextView(c).apply {
            text = label
            setTextColor(Hud.WHITE)
            typeface = Hud.TEXT_MEDIUM
            textSize = 14f
            setPadding(0, Hud.dpi(c, 10), 0, Hud.dpi(c, if (hint == null) 8 else 2))
        })
        if (hint != null) list.addView(TextView(c).apply {
            text = hint
            setTextColor(Hud.MUTED)
            typeface = Hud.TEXT
            textSize = 11.5f
            setPadding(0, 0, 0, Hud.dpi(c, 8))
        })
        list.addView(Segmented(c, options, sel) { pick(it); onChange() }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    section("PILOTAGE")
    row("Vitesse de vol", "Rapide = comme le mode rapide de l'app officielle", listOf("LENTE", "RAPIDE"),
        if (prefs.fast) 1 else 0) { prefs.fast = it == 1 }
    row("Mode des joysticks", "Mode 2 : gauche = altitude/rotation, droite = déplacement", listOf("MODE 2", "MODE 1"),
        prefs.stickMode) { prefs.stickMode = it }
    row("Sensibilité", "Douce = plus de précision autour du centre", listOf("DOUCE", "NORMALE", "DIRECTE"),
        prefs.sensitivity) { prefs.sensitivity = it }

    section("CAMÉRA")
    row("Débit vidéo", "Auto s'adapte au Wi-Fi. Baisse-le si l'image saccade.",
        listOf("AUTO", "1", "1,5", "2", "3", "4"), prefs.bitrate) { prefs.bitrate = it }
    row("Format", null, listOf("4:3", "16:9"), if (prefs.wide) 1 else 0) { prefs.wide = it == 1 }
    row("Horizon artificiel", null, listOf("AFFICHÉ", "MASQUÉ"), if (prefs.horizon) 0 else 1) { prefs.horizon = it == 0 }

    section("SÉCURITÉ")
    row("Altitude maximale", null, Prefs.ALT_LIMITS.map { "$it m" }, prefs.altLimit) { prefs.altLimit = it }

    list.addView(TextView(c).apply {
        text = "Tello Pilot 3.2 · protocole natif du Tello\nMaintiens STOP 1 s : coupe les moteurs en urgence."
        setTextColor(Hud.DIM)
        typeface = Hud.TEXT
        textSize = 11f
        setPadding(0, Hud.dpi(c, 24), 0, 0)
    })

    return ScrollView(c).apply {
        background = GradientDrawable().apply {
            setColor(0xEB070B10.toInt())
            val r = Hud.dp(c, 22f)
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            setStroke(Hud.dpi(c, 1), Hud.STROKE)
        }
        isVerticalScrollBarEnabled = false
        isClickable = true
        addView(list)
    }
}

/** Déclencheur photo / vidéo. */
class ShutterButton(c: Context) : View(c) {
    var video = false; set(v) { field = v; invalidate() }
    var recording = false; set(v) { field = v; invalidate() }
    var busy = false; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init { isClickable = true }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - Hud.dp(context, 3f)
        p.style = Paint.Style.FILL; p.color = Hud.GLASS
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = Hud.dp(context, 3f); p.color = Hud.WHITE
        c.drawCircle(cx, cy, r, p)
        if (recording) {
            val t = (SystemClock.uptimeMillis() % 2000) / 2000f * 360f
            p.color = Hud.RED
            c.drawArc(cx - r, cy - r, cx + r, cy + r, t, 90f, false, p)
            postInvalidateOnAnimation()
        }
        p.style = Paint.Style.FILL
        when {
            recording -> {
                p.color = Hud.RED
                val s = r * 0.38f
                c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, s * 0.3f, s * 0.3f, p)
            }
            video -> { p.color = Hud.RED; c.drawCircle(cx, cy, r * 0.66f, p) }
            else -> { p.color = if (busy) Hud.DIM else Hud.WHITE; c.drawCircle(cx, cy, r * 0.72f, p) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        return super.performClick()
    }
}

/** Sélecteur vertical Photo / Vidéo. */
class ModeSwitch(c: Context) : View(c) {
    var video = false; set(v) { field = v; invalidate() }
    var onToggle: ((Boolean) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init { isClickable = true }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = w / 2
        p.style = Paint.Style.FILL; p.color = Hud.GLASS
        c.drawRoundRect(0f, 0f, w, h, r, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = Hud.dp(context, 1f); p.color = Hud.STROKE
        c.drawRoundRect(0f, 0f, w, h, r, r, p)
        val half = h / 2
        val selTop = if (video) half else 0f
        p.style = Paint.Style.FILL; p.color = Hud.alpha(Hud.CYAN, 0.18f)
        val inset = Hud.dp(context, 3f)
        c.drawRoundRect(inset, selTop + inset, w - inset, selTop + half - inset, r - inset, r - inset, p)
        Icons.draw(c, Icon.PHOTO, w / 2, half / 2, w * 0.5f, if (!video) Hud.CYAN else Hud.MUTED, Hud.dp(context, 1.8f))
        Icons.draw(c, Icon.VIDEO, w / 2, half + half / 2, w * 0.5f, if (video) Hud.CYAN else Hud.MUTED, Hud.dp(context, 1.8f))
    }

    override fun performClick(): Boolean {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        video = !video
        onToggle?.invoke(video)
        return super.performClick()
    }
}

/** Écran de connexion (affiché tant que le drone n'est pas relié). */
fun buildConnectOverlay(c: Context, onWifi: () -> Unit, onHide: () -> Unit): View {
    val card = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = glassBg(c, 24f)
        val p = Hud.dpi(c, 26)
        setPadding(p, p, p, p)
        isClickable = true
    }
    card.addView(RadarView(c), LinearLayout.LayoutParams(Hud.dpi(c, 120), Hud.dpi(c, 120)).apply { marginEnd = Hud.dpi(c, 26) })
    val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    col.addView(title(c, "CONNEXION AU TELLO"))
    val steps = listOf(
        "1   Allume le Tello (bouton sur le côté)",
        "2   Connecte le téléphone au Wi-Fi TELLO-XXXXXX",
        "3   Si Android signale « pas d'Internet », reste connecté",
    )
    for (s in steps) col.addView(TextView(c).apply {
        text = s
        setTextColor(Hud.WHITE)
        typeface = Hud.TEXT
        textSize = 13.5f
        setPadding(0, Hud.dpi(c, 8), 0, 0)
    })
    val btns = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, Hud.dpi(c, 18), 0, 0)
    }
    btns.addView(pillButton(c, "OUVRIR LE WI-FI") { onWifi() })
    btns.addView(TextView(c).apply {
        text = "MASQUER"
        setTextColor(Hud.MUTED)
        typeface = Hud.LABEL
        textSize = 12f
        letterSpacing = 0.12f
        val h = Hud.dpi(c, 18); val v = Hud.dpi(c, 10)
        setPadding(h, v, h, v)
        isClickable = true
        setOnClickListener { onHide() }
    })
    col.addView(btns)
    card.addView(col)
    return FrameLayout(c).apply {
        setBackgroundColor(0x99000000.toInt())
        isClickable = true
        addView(card, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
    }
}
