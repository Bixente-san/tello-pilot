package com.vincent.tellopilot

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.inputmethod.InputMethodManager
import com.vincent.tellopilot.ai.D1Client
import com.vincent.tellopilot.ai.LlamaServer
import com.vincent.tellopilot.ai.ModelStore
import com.vincent.tellopilot.ai.Navigator
import com.vincent.tellopilot.drone.Attitude
import com.vincent.tellopilot.drone.FlightData
import com.vincent.tellopilot.drone.TelloDrone
import com.vincent.tellopilot.ui.AiHud
import com.vincent.tellopilot.ui.AiPanel
import com.vincent.tellopilot.ui.FlightMode
import com.vincent.tellopilot.ui.GlassButton
import com.vincent.tellopilot.ui.HorizonView
import com.vincent.tellopilot.ui.Hud
import com.vincent.tellopilot.ui.Icon
import com.vincent.tellopilot.ui.ModeSwitch
import com.vincent.tellopilot.ui.Prefs
import com.vincent.tellopilot.ui.ShutterButton
import com.vincent.tellopilot.ui.SlideConfirm
import com.vincent.tellopilot.ui.StatusBar
import com.vincent.tellopilot.ui.StickLayer
import com.vincent.tellopilot.ui.buildConnectOverlay
import com.vincent.tellopilot.ui.buildModesPanel
import com.vincent.tellopilot.ui.buildSettingsPanel
import com.vincent.tellopilot.ui.glassBg
import com.vincent.tellopilot.ui.mmss
import com.vincent.tellopilot.video.Mp4Recorder
import com.vincent.tellopilot.video.VideoPipeline
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

class MainActivity : Activity(), TelloDrone.Listener, VideoPipeline.Listener {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var drone: TelloDrone
    private lateinit var video: VideoPipeline

    // Vues
    private lateinit var root: FrameLayout
    private lateinit var hud: FrameLayout
    private lateinit var videoBox: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var horizon: HorizonView
    private lateinit var sticks: StickLayer
    private lateinit var status: StatusBar
    private lateinit var btnFly: GlassButton
    private lateinit var btnModes: GlassButton
    private lateinit var btnSettings: GlassButton
    private lateinit var btnStop: GlassButton
    private lateinit var shutter: ShutterButton
    private lateinit var modeSwitch: ModeSwitch
    private lateinit var banner: TextView
    private lateinit var recPill: TextView
    private lateinit var modeBar: LinearLayout
    private lateinit var modeBarText: TextView
    private lateinit var slideBox: LinearLayout
    private lateinit var slide: SlideConfirm
    private lateinit var bigText: TextView
    private lateinit var flash: View
    private lateinit var noVideo: TextView
    private var connectOverlay: View? = null
    private var connectHidden = false
    private var panel: View? = null

    // État
    private var link = TelloDrone.Link.NO_WIFI
    private var fd: FlightData? = null
    private var attitude: Attitude? = null
    private var wifi = -1
    private var flightStart = 0L
    private var lastFlightSec = 0
    private var recorder: Mp4Recorder? = null
    private var recUri: Uri? = null
    private var recFile: File? = null
    private var recStart = 0L
    private var photoPending = false
    private var bounceOn = false
    private var activeWarning: String? = null
    private var transientUntil = 0L

    // Vol automatique (360° / Grand angle)
    private var ez: FlightMode? = null
    private var ezStart = 0L
    private var ezYawAcc = 0f
    private var ezLastYaw: Float? = null
    private var ezStartedRec = false

    // Pilote IA : d1-2B exécuté localement par llama.cpp
    private enum class EngineState { OFF, STARTING, READY, FAILED }
    private lateinit var models: ModelStore
    private lateinit var llama: LlamaServer
    private lateinit var navigator: Navigator
    private lateinit var aiHud: AiHud
    private var aiPanel: AiPanel? = null
    private var engineState = EngineState.OFF
    private var engineMessage = ""
    private var aiDryRun = false
    private val presets = listOf("Va vers la porte", "Approche-toi de la personne", "Tourne jusqu'à voir une fenêtre",
        "Monte et regarde la table", "Va vers l'objet rouge")

    // ================================================================ cycle de vie

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        prefs = Prefs(this)
        drone = TelloDrone(applicationContext, this)
        video = VideoPipeline(this)
        models = ModelStore(this)
        llama = LlamaServer(this, models)
        navigator = Navigator(drone, D1Client(llama.port), ::aiFrame,
            { Prefs.ALT_LIMITS[prefs.altLimit.coerceIn(0, Prefs.ALT_LIMITS.size - 1)].toFloat() }, navListener)
        applyPrefs()
        setContentView(buildUi())
        hideSystemBars()
        drone.start()
        video.start()
        ui.post(clock)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        sticks.reset()
    }

    override fun onStop() {
        super.onStop()
        if (navigator.running) navigator.abort("Pilote IA arrêté (app en arrière-plan)")
        stopEz(true)
        if (recorder != null) stopRecording()
        // Sécurité : si l'app passe en arrière-plan pendant le vol, on atterrit.
        if (drone.flying) drone.land()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        drone.stop()
        video.stop()
        llama.stop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            panel != null -> closePanel()
            slideBox.visibility == View.VISIBLE -> hideSlide()
            sticks.flipMode -> exitFlipMode()
            else -> super.onBackPressed()
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    private fun applyPrefs() {
        drone.fastMode = prefs.fast
        drone.videoBitrate = prefs.bitrate
        drone.wideVideo = prefs.wide
        drone.altLimitM = Prefs.ALT_LIMITS[prefs.altLimit.coerceIn(0, Prefs.ALT_LIMITS.size - 1)]
    }

    private fun dp(v: Int) = Hud.dpi(this, v)

    // ================================================================ interface

    private fun buildUi(): View {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Vidéo (format respecté, centrée)
        videoBox = FrameLayout(this)
        texture = TextureView(this)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = video.setSurface(Surface(st))
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { video.setSurface(null); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        videoBox.addView(texture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        videoBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        root.addView(videoBox, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        noVideo = TextView(this).apply {
            text = "EN ATTENTE DE LA VIDÉO"
            setTextColor(Hud.DIM); typeface = Hud.LABEL; textSize = 12f; letterSpacing = 0.25f
        }
        root.addView(noVideo, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER).apply { topMargin = dp(70) })

        horizon = HorizonView(this).apply { showHorizon = prefs.horizon }
        root.addView(horizon, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        sticks = StickLayer(this).apply {
            onSticks = this@MainActivity::onSticks
            onFlip = this@MainActivity::onFlipSwipe
        }
        updateStickLabels()
        root.addView(sticks, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Couche HUD (marges de sécurité pour l'encoche)
        hud = FrameLayout(this)
        root.addView(hud, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, ins ->
            var l = 0; var r = 0
            if (Build.VERSION.SDK_INT >= 28) ins.displayCutout?.let { l = it.safeInsetLeft; r = it.safeInsetRight }
            hud.setPadding(l, 0, r, 0)
            sticks.setPadding(l, 0, r, 0)
            ins
        }

        status = StatusBar(this).apply {
            fast = prefs.fast
            onSpeedTap = { toggleSpeed() }
        }
        hud.addView(status, FrameLayout.LayoutParams(MATCH_PARENT, dp(56), Gravity.TOP))

        // Colonne gauche : décollage, modes, réglages, arrêt d'urgence
        val leftCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        btnFly = GlassButton(this, Icon.TAKEOFF).apply { setOnClickListener { onFlyButton() } }
        btnModes = GlassButton(this, Icon.MODES).apply { setOnClickListener { openModes() } }
        btnSettings = GlassButton(this, Icon.SETTINGS).apply { setOnClickListener { openSettings() } }
        btnStop = GlassButton(this, Icon.POWER, Hud.RED).apply {
            tint = Hud.RED
            setOnClickListener { toast("Appui long sur ⏻ : coupe les moteurs (le drone tombe)", Hud.AMBER) }
            setOnLongClickListener { emergency(); true }
        }
        fun col(v: View, size: Int, gap: Int) = leftCol.addView(v, LinearLayout.LayoutParams(dp(size), dp(size)).apply { bottomMargin = dp(gap) })
        col(btnFly, 60, 14); col(btnModes, 50, 14); col(btnSettings, 50, 22); col(btnStop, 40, 0)
        hud.addView(leftCol, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(22); topMargin = dp(66)
        })

        // Colonne droite : photo/vidéo + déclencheur
        val rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        modeSwitch = ModeSwitch(this).apply {
            onToggle = { v ->
                shutter.video = v
                if (!v && recorder != null) stopRecording()
            }
        }
        shutter = ShutterButton(this).apply { setOnClickListener { onShutter() } }
        val btnAi = GlassButton(this, Icon.AI).apply { setOnClickListener { openAi() } }
        rightCol.addView(btnAi, LinearLayout.LayoutParams(dp(50), dp(50)).apply { bottomMargin = dp(16) })
        rightCol.addView(modeSwitch, LinearLayout.LayoutParams(dp(42), dp(84)).apply { bottomMargin = dp(16) })
        rightCol.addView(shutter, LinearLayout.LayoutParams(dp(70), dp(70)))
        hud.addView(rightCol, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(22); topMargin = dp(70)
        })

        // Haut-centre : alertes, enregistrement, mode actif
        val topCenter = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        banner = TextView(this).apply {
            setTextColor(Hud.WHITE); typeface = Hud.TEXT_MEDIUM; textSize = 13f
            val h = dp(16); val v = dp(8); setPadding(h, v, h, v)
            visibility = View.GONE
        }
        recPill = TextView(this).apply {
            setTextColor(Hud.WHITE); typeface = Hud.MONO; textSize = 13f
            val h = dp(14); val v = dp(6); setPadding(h, v, h, v)
            background = glassBg(this@MainActivity, 16f, Hud.alpha(Hud.RED, 0.25f), Hud.RED)
            visibility = View.GONE
        }
        modeBarText = TextView(this).apply {
            setTextColor(Hud.CYAN); typeface = Hud.LABEL; textSize = 13f; letterSpacing = 0.14f
            setPadding(dp(16), 0, dp(10), 0)
        }
        modeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassBg(this@MainActivity, 22f, Hud.GLASS, Hud.CYAN)
            setPadding(0, dp(3), dp(3), dp(3))
            addView(modeBarText)
            addView(GlassButton(this@MainActivity, Icon.CLOSE).apply { setOnClickListener { stopActiveMode() } },
                LinearLayout.LayoutParams(dp(36), dp(36)))
            visibility = View.GONE
        }
        val lp = { LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) } }
        topCenter.addView(banner, lp()); topCenter.addView(recPill, lp()); topCenter.addView(modeBar, lp())
        hud.addView(topCenter, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(52)
        })

        // Grand texte central (compte à rebours, consignes)
        bigText = TextView(this).apply {
            setTextColor(Hud.WHITE); typeface = Hud.LABEL; textSize = 22f; letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 0f, Color.BLACK)
            visibility = View.GONE
        }
        hud.addView(bigText, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        // Glisser pour décoller / atterrir
        slide = SlideConfirm(this).apply { onConfirm = { onSlideConfirmed() } }
        slideBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(slide, LinearLayout.LayoutParams(dp(330), dp(62)))
            addView(GlassButton(this@MainActivity, Icon.CLOSE).apply { setOnClickListener { hideSlide() } },
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) })
            visibility = View.GONE
        }
        hud.addView(slideBox, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(28)
        })

        aiHud = AiHud(this) {
            if (navigator.running) navigator.abort("Pilote IA arrêté") else hideAiHud()
        }.apply { visibility = View.GONE }
        hud.addView(aiHud, FrameLayout.LayoutParams(dp(380), WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(18)
        })

        flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f; isClickable = false }
        root.addView(flash, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        showConnectOverlay(true)
        return root
    }

    private fun fitVideo() {
        val cw = videoBox.width; val ch = videoBox.height
        if (cw == 0 || ch == 0) return
        val aspect = video.width.toFloat() / max(1, video.height)
        val (w, h) = if (cw.toFloat() / ch > aspect) (ch * aspect).toInt() to ch else cw to (cw / aspect).toInt()
        val lp = texture.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w; lp.height = h
            texture.layoutParams = lp
        }
    }

    private fun updateStickLabels() {
        if (prefs.stickMode == 0) {
            sticks.leftLabel = "ALTITUDE · ROTATION"; sticks.rightLabel = "DÉPLACEMENT"
        } else {
            sticks.leftLabel = "AVANT · ROTATION"; sticks.rightLabel = "ALTITUDE · CÔTÉ"
        }
    }

    private fun showConnectOverlay(show: Boolean) {
        if (show && connectOverlay == null && !connectHidden) {
            connectOverlay = buildConnectOverlay(this,
                onWifi = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                onHide = { connectHidden = true; showConnectOverlay(false) })
            hud.addView(connectOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            connectOverlay?.alpha = 0f
            connectOverlay?.animate()?.alpha(1f)?.setDuration(250)?.start()
        } else if (!show) {
            connectOverlay?.let { v -> v.animate().alpha(0f).setDuration(200).withEndAction { hud.removeView(v) }.start() }
            connectOverlay = null
        }
    }

    private fun openPanel(v: View, gravity: Int, w: Int, h: Int) {
        closePanel()
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x80000000.toInt())
            isClickable = true
            setOnClickListener { closePanel() }
            addView(v, FrameLayout.LayoutParams(w, h, gravity))
        }
        hud.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(180).start()
        if (gravity and Gravity.END == Gravity.END) {
            v.translationX = dp(60).toFloat()
            v.animate().translationX(0f).setDuration(220).start()
        } else {
            v.scaleX = 0.94f; v.scaleY = 0.94f
            v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }
        panel = scrim
        sticks.reset()
    }

    private fun closePanel() {
        val p = panel ?: return
        panel = null
        p.animate().alpha(0f).setDuration(150).withEndAction { hud.removeView(p) }.start()
    }

    private fun openModes() {
        openPanel(buildModesPanel(this, onPick = { m -> closePanel(); startMode(m) }, onClose = ::closePanel),
            Gravity.CENTER, WRAP_CONTENT, WRAP_CONTENT)
    }

    private fun openSettings() {
        openPanel(buildSettingsPanel(this, prefs, onChange = {
            applyPrefs()
            drone.applySettings()
            status.fast = prefs.fast
            horizon.showHorizon = prefs.horizon
            updateStickLabels()
        }, onClose = ::closePanel), Gravity.END or Gravity.TOP, dp(400), MATCH_PARENT)
    }

    // ================================================================ messages

    private fun toast(text: String, color: Int = Hud.CYAN, ms: Long = 2800) {
        transientUntil = SystemClock.uptimeMillis() + ms
        showBanner(text, color)
        ui.postDelayed({ if (SystemClock.uptimeMillis() >= transientUntil) refreshWarning(force = true) }, ms)
    }

    private fun showBanner(text: String, color: Int) {
        banner.text = text
        banner.background = glassBg(this, 18f, Hud.alpha(color, 0.18f), Hud.alpha(color, 0.9f))
        if (banner.visibility != View.VISIBLE) {
            banner.visibility = View.VISIBLE
            banner.alpha = 0f; banner.translationY = -dp(8).toFloat()
            banner.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
    }

    /** Alertes permanentes déduites de la télémétrie, par ordre de gravité. */
    private fun refreshWarning(force: Boolean = false) {
        val f = fd
        val (w, col) = when {
            link != TelloDrone.Link.CONNECTED && flightStart != 0L -> "Connexion perdue avec le drone" to Hud.RED
            f == null -> null to 0
            f.overheat -> "Surchauffe : atterris rapidement" to Hud.RED
            f.battery in 1..10 || f.batteryLower -> "Batterie critique : atterris maintenant" to Hud.RED
            f.windWarning -> "Vent fort : le drone peut dériver" to Hud.AMBER
            f.battery in 11..20 || f.batteryLow -> "Batterie faible" to Hud.AMBER
            wifi in 0..34 && f.flying -> "Signal Wi-Fi faible : rapproche-toi" to Hud.AMBER
            else -> null to 0
        }
        if (SystemClock.uptimeMillis() < transientUntil) { activeWarning = w; return }
        if (w == activeWarning && !force) return
        activeWarning = w
        if (w != null) showBanner(w, col)
        else banner.animate().alpha(0f).setDuration(200).withEndAction { banner.visibility = View.GONE }.start()
    }

    // ================================================================ pilotage

    private fun shape(v: Float): Float {
        val dead = 0.06f
        val a = abs(v)
        if (a < dead) return 0f
        val x = (a - dead) / (1 - dead)
        val e = when (prefs.sensitivity) { 0 -> 0.6f; 1 -> 0.3f; else -> 0f }
        return sign(v) * (e * x * x * x + (1 - e) * x)
    }

    private fun onSticks(lx: Float, ly: Float, rx: Float, ry: Float) {
        val sl = shape(lx); val sly = shape(ly); val sr = shape(rx); val sry = shape(ry)
        if (navigator.running) {
            if (maxOf(abs(sl), abs(sly), abs(sr), abs(sry)) > 0.35f) {
                navigator.abort("Tu as repris la main")
            } else return
        }
        if (ez != null) {
            if (maxOf(abs(sl), abs(sly), abs(sr), abs(sry)) > 0.35f) {
                stopEz(true)
                toast("Mode annulé : tu as repris la main", Hud.AMBER)
            } else return
        }
        drone.yaw = sl
        drone.roll = sr
        if (prefs.stickMode == 0) { drone.throttle = sly; drone.pitch = sry }
        else { drone.pitch = sly; drone.throttle = sry }
    }

    private fun toggleSpeed() {
        prefs.fast = !prefs.fast
        drone.fastMode = prefs.fast
        status.fast = prefs.fast
        toast(if (prefs.fast) "Mode rapide activé : vole avec précaution" else "Mode lent activé",
            if (prefs.fast) Hud.AMBER else Hud.CYAN)
    }

    private fun connected() = link == TelloDrone.Link.CONNECTED

    private fun onFlyButton() {
        if (!connected()) { toast("Connecte d'abord le Tello", Hud.AMBER); return }
        val flying = drone.flying
        slide.label = if (flying) "GLISSER POUR ATTERRIR" else "GLISSER POUR DÉCOLLER"
        slide.icon = if (flying) Icon.LAND else Icon.TAKEOFF
        slide.accent = if (flying) Hud.AMBER else Hud.CYAN
        slide.resetThumb()
        slideBox.visibility = View.VISIBLE
        slideBox.alpha = 0f; slideBox.translationY = dp(20).toFloat()
        slideBox.animate().alpha(1f).translationY(0f).setDuration(200).start()
    }

    private fun hideSlide() {
        slideBox.animate().alpha(0f).setDuration(150).withEndAction { slideBox.visibility = View.GONE }.start()
    }

    private fun onSlideConfirmed() {
        hideSlide()
        if (drone.flying) {
            stopActiveMode()
            drone.centerSticks()
            drone.land()
            toast("Atterrissage…")
        } else {
            drone.takeoff()
            toast("Décollage…")
        }
    }

    private fun emergency() {
        stopActiveMode()
        drone.emergency()
        toast("MOTEURS COUPÉS", Hud.RED, 4000)
    }

    // ================================================================ modes de vol

    private fun startMode(m: FlightMode) {
        if (m == FlightMode.AI) { openAi(); return }
        if (!connected()) { toast("Connecte d'abord le Tello", Hud.AMBER); return }
        val f = fd
        val flying = drone.flying
        when (m) {
            FlightMode.THROW -> {
                if (flying) { toast("Déjà en vol", Hud.AMBER); return }
                drone.throwAndGo()
                countdown(5, "LANCE DOUCEMENT LE DRONE VERS LE HAUT")
            }
            FlightMode.PALM -> {
                if (!flying) { toast("Décolle d'abord", Hud.AMBER); return }
                drone.palmLand()
                toast("Présente ta main à plat sous le drone", Hud.CYAN, 5000)
            }
            FlightMode.BOUNCE -> {
                if (!flying) { toast("Décolle d'abord", Hud.AMBER); return }
                bounceOn = true
                drone.bounce(true)
                showModeBar("REBOND ACTIF")
            }
            FlightMode.FLIP8D -> {
                if (!flying) { toast("Décolle d'abord", Hud.AMBER); return }
                if (f != null && f.battery < 50) { toast("Batterie sous 50 % : flips impossibles", Hud.AMBER); return }
                sticks.flipMode = true
                drone.centerSticks()
                showModeBar("FLIPS 8D")
            }
            FlightMode.SPIN360, FlightMode.UPAWAY -> {
                if (!flying) { toast("Décolle d'abord", Hud.AMBER); return }
                startEz(m)
            }
            FlightMode.AI -> openAi()
        }
    }

    private fun showModeBar(text: String) {
        modeBarText.text = text
        modeBar.visibility = View.VISIBLE
        modeBar.alpha = 0f
        modeBar.animate().alpha(1f).setDuration(180).start()
    }

    private fun hideModeBar() { modeBar.visibility = View.GONE }

    private fun stopActiveMode() {
        if (navigator.running) navigator.abort("Pilote IA arrêté")
        if (sticks.flipMode) exitFlipMode()
        if (bounceOn) { bounceOn = false; drone.bounce(false) }
        if (ez != null) stopEz(true)
        hideModeBar()
    }

    private fun exitFlipMode() {
        sticks.flipMode = false
        hideModeBar()
    }

    private fun onFlipSwipe(dir: Int) {
        val f = fd
        if (f != null && f.battery < 50) { toast("Batterie sous 50 % : flips impossibles", Hud.AMBER); return }
        drone.flip(dir)
        val names = arrayOf("AVANT", "GAUCHE", "ARRIÈRE", "DROITE", "AVANT-GAUCHE", "ARRIÈRE-GAUCHE", "ARRIÈRE-DROITE", "AVANT-DROITE")
        toast("Flip ${names[dir].lowercase(Locale.FRANCE)}", Hud.CYAN, 1500)
    }

    private fun countdown(from: Int, msg: String) {
        bigText.visibility = View.VISIBLE
        for (i in 0..from) {
            ui.postDelayed({
                if (i < from) bigText.text = "${from - i}\n$msg"
                else bigText.visibility = View.GONE
            }, i * 1000L)
        }
    }

    private fun startEz(m: FlightMode) {
        sticks.reset()
        drone.centerSticks()
        ez = m
        ezStart = SystemClock.uptimeMillis()
        ezYawAcc = 0f
        ezLastYaw = attitude?.yaw
        ezStartedRec = false
        if (recorder == null) ezStartedRec = startRecording()
        showModeBar(if (m == FlightMode.SPIN360) "360° EN COURS" else "GRAND ANGLE EN COURS")
        ui.post(ezTick)
    }

    private val ezTick = object : Runnable {
        override fun run() {
            val m = ez ?: return
            if (!drone.flying) { stopEz(true); return }
            val t = SystemClock.uptimeMillis() - ezStart
            when (m) {
                FlightMode.SPIN360 -> {
                    attitude?.yaw?.let { y ->
                        val last = ezLastYaw
                        if (last != null) {
                            var d = y - last
                            if (d > 180) d -= 360
                            if (d < -180) d += 360
                            ezYawAcc += abs(d)
                        }
                        ezLastYaw = y
                    }
                    drone.yaw = 0.5f
                    if (ezYawAcc >= 355f || t > 14_000) { stopEz(false); return }
                }
                FlightMode.UPAWAY -> {
                    if (t < 4000) { drone.pitch = -0.55f; drone.throttle = 0.5f }
                    else { stopEz(false); return }
                }
                else -> { stopEz(true); return }
            }
            ui.postDelayed(this, 50)
        }
    }

    private fun stopEz(cancelled: Boolean) {
        if (ez == null) return
        ez = null
        ui.removeCallbacks(ezTick)
        drone.centerSticks()
        hideModeBar()
        if (ezStartedRec) {
            ezStartedRec = false
            ui.postDelayed({ if (recorder != null) stopRecording() }, if (cancelled) 0L else 1200L)
        }
        if (!cancelled) toast("Séquence terminée et filmée")
    }

    // ================================================================ pilote IA

    private fun openAi() {
        val p = AiPanel(this, presets, onClose = ::closePanel)
        aiPanel = p
        p.primary.setOnClickListener { onAiPrimary() }
        p.analyze.setOnClickListener { launchAi(true) }
        p.launch.setOnClickListener { launchAi(false) }
        p.input.setOnEditorActionListener { v, _, _ -> hideKeyboard(v); true }
        // en haut de l'écran : le clavier ne cache pas la consigne
        openPanel(p, Gravity.TOP or Gravity.CENTER_HORIZONTAL, dp(560), WRAP_CONTENT)
        refreshAi()
        ui.removeCallbacks(aiRefresher)
        ui.postDelayed(aiRefresher, 1000)
    }

    private val aiRefresher = object : Runnable {
        override fun run() {
            val p = aiPanel ?: return
            if (!p.isAttachedToWindow || panel == null) { aiPanel = null; return }
            refreshAi()
            ui.postDelayed(this, 1000)
        }
    }

    private fun refreshAi() {
        val p = aiPanel ?: return
        fun show(text: String, bar: Boolean, primary: String?, form: Boolean) {
            p.status.text = text
            p.bar.visibility = if (bar) View.VISIBLE else View.GONE
            p.primary.visibility = if (primary != null) View.VISIBLE else View.GONE
            if (primary != null) p.primary.text = primary
            p.showForm(form)
        }
        if (!llama.binary.exists()) { show("Le moteur IA est absent de cette version de l'app.", false, null, false); return }
        val pr = models.progress()
        when {
            !models.ready() && pr.active -> {
                val pct = (100.0 * pr.done / pr.total).toInt()
                show(if (pr.waitingForWifi) "Téléchargement en pause : en attente d'un Wi-Fi avec Internet ($pct %)"
                     else "Téléchargement du modèle d1-2B… $pct %  (${pr.done / 1_000_000} / ${pr.total / 1_000_000} Mo)",
                    true, null, false)
                p.bar.indeterminate = false
                p.bar.progress = pr.done.toFloat() / pr.total
            }
            !models.ready() -> show(
                if (pr.failed) "Le téléchargement a échoué. Réessaie sur un Wi-Fi avec Internet."
                else "Le modèle d1-2B (1,3 Go) se télécharge une seule fois, sur un Wi-Fi avec Internet (pas celui du drone).",
                false, "TÉLÉCHARGER LE MODÈLE", false)
            engineState == EngineState.OFF -> { startEngine(); return }
            engineState == EngineState.STARTING -> {
                show(engineMessage, true, null, true)
                p.bar.indeterminate = true
            }
            engineState == EngineState.READY -> show("●  Moteur IA prêt · d1-2B tourne sur ton téléphone", false, null, true)
            else -> show("Le moteur IA n'a pas démarré : $engineMessage", false, "RÉESSAYER", false)
        }
    }

    private fun onAiPrimary() {
        if (!models.ready()) {
            models.download()
            toast("Téléchargement lancé (suivi dans les notifications)")
        } else {
            engineState = EngineState.OFF
            startEngine()
        }
        refreshAi()
    }

    private fun startEngine() {
        if (engineState == EngineState.STARTING) return
        engineState = EngineState.STARTING
        engineMessage = "Démarrage du moteur IA…"
        refreshAi()
        Thread {
            val ok = llama.start { msg -> ui.post { engineMessage = msg; refreshAi() } }
            ui.post {
                engineState = if (ok) EngineState.READY else EngineState.FAILED
                refreshAi()
            }
        }.start()
    }

    private fun launchAi(dryRun: Boolean) {
        val p = aiPanel ?: return
        val text = p.input.text.toString().trim()
        if (text.isEmpty()) { toast("Écris d'abord une consigne", Hud.AMBER); return }
        if (engineState != EngineState.READY || !llama.running) { toast("Le moteur IA n'est pas prêt", Hud.AMBER); return }
        if (!dryRun) {
            if (!connected()) { toast("Connecte d'abord le Tello", Hud.AMBER); return }
            if (!drone.flying) { toast("Décolle d'abord, ou utilise « Analyser l'image »", Hud.AMBER); return }
        }
        hideKeyboard(p.input)
        closePanel()
        stopActiveMode()
        sticks.reset()
        drone.centerSticks()
        aiDryRun = dryRun
        aiHud.begin(text, dryRun)
        aiHud.visibility = View.VISIBLE
        aiHud.alpha = 0f; aiHud.translationY = dp(20).toFloat()
        aiHud.animate().alpha(1f).translationY(0f).setDuration(200).start()
        navigator.start(text, dryRun)
    }

    private fun hideAiHud() {
        aiHud.animate().alpha(0f).setDuration(200).withEndAction { aiHud.visibility = View.GONE }.start()
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** Image envoyée à d1 : capture de la vidéo, 512 px de large. Appelée depuis le thread du pilote. */
    private fun aiFrame(): Bitmap? {
        val w = 512
        val h = (w.toFloat() * video.height / max(1, video.width)).toInt()
        var out: Bitmap? = null
        val latch = CountDownLatch(1)
        ui.post {
            out = if (texture.isAvailable && noVideo.visibility != View.VISIBLE) runCatching { texture.getBitmap(w, h) }.getOrNull() else null
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return out
    }

    private val navListener = object : Navigator.Listener {
        override fun onPhase(phase: Navigator.Phase, step: Int) {
            ui.post { aiHud.phase(phase, step, aiDryRun) }
        }

        override fun onDecision(d: Navigator.Decision) {
            ui.post { aiHud.decision(d) }
        }

        override fun onFinished(message: String, success: Boolean) {
            ui.post {
                drone.fastMode = prefs.fast
                aiHud.finished(message, success)
                toast(message, if (success) Hud.GREEN else Hud.AMBER)
                ui.postDelayed({ if (!navigator.running) hideAiHud() }, if (aiDryRun) 9000L else 4000L)
            }
        }
    }

    // ================================================================ photo / vidéo

    private fun onShutter() {
        if (shutter.video) {
            if (recorder != null) stopRecording() else if (!startRecording()) toast("Vidéo pas encore disponible", Hud.AMBER)
        } else takePhoto()
    }

    private fun takePhoto() {
        flash.alpha = 0.7f
        flash.animate().alpha(0f).setDuration(260).start()
        if (connected()) {
            photoPending = true
            shutter.busy = true
            drone.takePicture()
            // Si le drone n'envoie pas sa photo 5 Mpx, on garde l'image vidéo.
            ui.postDelayed({ if (photoPending) { photoPending = false; shutter.busy = false; grabFrame() } }, 6000)
        } else grabFrame()
    }

    private fun grabFrame() {
        val bmp = texture.bitmap ?: run { toast("Pas d'image à enregistrer", Hud.AMBER); return }
        Thread {
            val ok = runCatching {
                savePhoto { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, out) }
            }.isSuccess
            ui.post { toast(if (ok) "Capture enregistrée (Galerie › TelloPilot)" else "Erreur d'enregistrement", if (ok) Hud.CYAN else Hud.RED) }
        }.start()
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())

    private fun savePhoto(write: (java.io.OutputStream) -> Unit) {
        val name = "Tello_${stamp()}.jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TelloPilot")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use(write)
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
            FileOutputStream(File(dir, name)).use(write)
        }
    }

    private fun startRecording(): Boolean {
        val params = video.spsPps ?: return false
        val name = "Tello_${stamp()}.mp4"
        try {
            val pfd: ParcelFileDescriptor
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/TelloPilot")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                recUri = uri
                pfd = contentResolver.openFileDescriptor(uri, "rw") ?: return false
            } else {
                val f = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), name)
                recFile = f
                pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
            }
            val r = Mp4Recorder(pfd, params.first, params.second, video.width, video.height)
            recorder = r
            video.recorder = r
            drone.requestKeyframe()
            recStart = SystemClock.uptimeMillis()
            shutter.recording = true
            modeSwitch.video = true
            shutter.video = true
            recPill.visibility = View.VISIBLE
            recPill.text = "● REC 0:00"
            return true
        } catch (_: Exception) {
            recUri?.let { runCatching { contentResolver.delete(it, null, null) } }
            recUri = null
            return false
        }
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        video.recorder = null
        shutter.recording = false
        recPill.visibility = View.GONE
        val uri = recUri; recUri = null
        recFile = null
        Thread {
            val ok = r.close()
            if (uri != null) {
                if (ok) {
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                } else runCatching { contentResolver.delete(uri, null, null) }
            }
            ui.post { toast(if (ok) "Vidéo enregistrée (Galerie › TelloPilot)" else "Vidéo vide, non enregistrée", if (ok) Hud.CYAN else Hud.AMBER) }
        }.start()
    }

    // ================================================================ horloge UI (4 Hz)

    private val clock = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (flightStart != 0L) status.flightSeconds = ((now - flightStart) / 1000).toInt()
            if (recorder != null) recPill.text = "● REC ${mmss(((now - recStart) / 1000).toInt())}"
            ui.postDelayed(this, 250)
        }
    }

    // ================================================================ callbacks drone

    override fun onLink(link: TelloDrone.Link) { ui.post {
        val was = this.link
        this.link = link
        status.state = when (link) {
            TelloDrone.Link.NO_WIFI -> StatusBar.State.NO_WIFI
            TelloDrone.Link.CONNECTING -> StatusBar.State.CONNECTING
            TelloDrone.Link.CONNECTED -> if (drone.flying) StatusBar.State.FLYING else StatusBar.State.READY
        }
        horizon.live = link == TelloDrone.Link.CONNECTED
        if (link == TelloDrone.Link.CONNECTED) {
            connectHidden = false
            showConnectOverlay(false)
            if (was != TelloDrone.Link.CONNECTED) toast("Tello connecté", Hud.GREEN, 2000)
        } else {
            fd = null
            status.battery = -1; status.wifi = -1
            if (flightStart == 0L) showConnectOverlay(true)
            if (recorder != null) stopRecording()
            if (sticks.flipMode || bounceOn || ez != null || navigator.running) stopActiveMode()
        }
        refreshWarning(force = true)
    } }

    override fun onFlightData(fd: FlightData) { ui.post {
        val wasFlying = this.fd?.flying == true
        this.fd = fd
        status.battery = fd.battery
        status.heightM = fd.heightM
        status.speed = fd.horizontalSpeed
        status.state = if (fd.flying) StatusBar.State.FLYING else StatusBar.State.READY
        btnFly.icon = if (fd.flying) Icon.LAND else Icon.TAKEOFF
        if (fd.flying && !wasFlying) {
            flightStart = SystemClock.uptimeMillis()
            status.flightSeconds = 0
        }
        if (!fd.flying && wasFlying) {
            lastFlightSec = ((SystemClock.uptimeMillis() - flightStart) / 1000).toInt()
            flightStart = 0
            stopActiveMode()
            if (slideBox.visibility == View.VISIBLE) hideSlide()
        }
        refreshWarning()
    } }

    override fun onWifi(strength: Int) { ui.post { wifi = strength; status.wifi = strength } }

    override fun onAttitude(att: Attitude) { ui.post {
        attitude = att
        horizon.setAttitude(att.pitch, att.roll)
    } }

    override fun onPhoto(jpeg: ByteArray) {
        Thread {
            val ok = runCatching { savePhoto { it.write(jpeg) } }.isSuccess
            ui.post {
                photoPending = false
                shutter.busy = false
                toast(if (ok) "Photo enregistrée (Galerie › TelloPilot)" else "Erreur d'enregistrement de la photo",
                    if (ok) Hud.CYAN else Hud.RED)
            }
        }.start()
    }

    // ================================================================ callbacks vidéo

    override fun requestKeyframe() = drone.requestKeyframe()

    override fun onVideoSize(width: Int, height: Int) { ui.post { fitVideo() } }

    override fun onVideoAlive(alive: Boolean) { ui.post {
        noVideo.visibility = if (alive) View.GONE else View.VISIBLE
        noVideo.text = if (connected()) "SIGNAL VIDÉO INTERROMPU…" else "EN ATTENTE DE LA VIDÉO"
    } }
}
