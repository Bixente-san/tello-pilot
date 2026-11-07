package com.vincent.tellopilot

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var tello: TelloClient
    private var video: VideoDecoder? = null

    private lateinit var texture: TextureView
    private lateinit var logText: TextView
    private lateinit var stateText: TextView
    private lateinit var speedBtn: Button

    private val speeds = intArrayOf(30, 60, 100)
    private var speedIdx = 1
    private val speed get() = speeds[speedIdx]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        tello = TelloClient(
            context = applicationContext,
            scope = scope,
            onLog = { msg -> runOnUiThread { logText.text = msg } },
            onState = { st -> runOnUiThread { showState(st) } },
        )
        setContentView(buildUi())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(text: String, color: Int = Color.argb(150, 40, 40, 40), onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            isAllCaps = false
            textSize = 14f
            background = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onClick() }
        }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Vidéo plein écran
        texture = TextureView(this)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                video = VideoDecoder(Surface(st)) { msg -> runOnUiThread { logText.text = msg } }
                video?.start()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                video?.stop(); video = null; return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        root.addView(texture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))

        // Barre du haut : infos à gauche, boutons à droite
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        logText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f
            text = "Connecte le Wi-Fi TELLO-xxxx puis appuie sur Connecter"
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        stateText = TextView(this).apply {
            setTextColor(Color.rgb(140, 255, 160)); textSize = 13f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        info.addView(logText); info.addView(stateText)
        root.addView(info, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))

        val topBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        fun addTop(b: Button) {
            topBtns.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) })
        }
        addTop(button("Connecter") { tello.connect() })
        speedBtn = button("Vitesse $speed %") {
            speedIdx = (speedIdx + 1) % speeds.size
            speedBtn.text = "Vitesse $speed %"
        }
        addTop(speedBtn)
        addTop(button("📷 Photo") { takePhoto() })
        addTop(button("Flip") { askFlip() })
        addTop(button("STOP", Color.argb(220, 200, 30, 30)) {
            Toast.makeText(this, "Appui long pour couper les moteurs", Toast.LENGTH_SHORT).show()
        }.apply {
            setOnLongClickListener { tello.emergency(); true }
        })
        root.addView(topBtns, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))

        // Décoller / Atterrir au centre en bas
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }
        mid.addView(button("⬆ Décoller", Color.argb(200, 30, 130, 60)) { tello.takeoff() })
        mid.addView(button("⬇ Atterrir", Color.argb(200, 30, 80, 160)) { tello.land() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
        root.addView(mid, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        // Joysticks (mode 2) : gauche = monter/descendre + rotation, droite = avancer/reculer + gauche/droite
        val size = dp(170)
        val left = JoystickView(this, "Altitude / Rotation").apply {
            onMove = { x, y ->
                tello.yaw = (x * speed).toInt()
                tello.ud = (y * speed).toInt()
            }
        }
        val right = JoystickView(this, "Avancer / Côté").apply {
            onMove = { x, y ->
                tello.lr = (x * speed).toInt()
                tello.fb = (y * speed).toInt()
            }
        }
        root.addView(left, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(24), 0, 0, dp(28))
        })
        root.addView(right, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(24), dp(28))
        })
        return root
    }

    private fun showState(st: Map<String, String>) {
        val bat = st["bat"] ?: "?"
        val h = st["h"] ?: "?"
        val tof = st["tof"] ?: "?"
        val temp = st["temph"] ?: "?"
        val time = st["time"] ?: "?"
        stateText.text = "🔋 $bat %   ↕ $h cm   sol $tof cm   🌡 $temp °C   ⏱ $time s"
        val b = bat.toIntOrNull()
        stateText.setTextColor(
            when {
                b == null -> Color.WHITE
                b <= 15 -> Color.rgb(255, 90, 90)
                b <= 30 -> Color.rgb(255, 200, 80)
                else -> Color.rgb(140, 255, 160)
            }
        )
    }

    private fun askFlip() {
        val labels = arrayOf("Avant", "Arrière", "Gauche", "Droite")
        val dirs = arrayOf("f", "b", "l", "r")
        AlertDialog.Builder(this)
            .setTitle("Flip")
            .setItems(labels) { _, i -> tello.flip(dirs[i]) }
            .show()
    }

    private fun takePhoto() {
        val bmp: Bitmap = texture.bitmap ?: run {
            Toast.makeText(this, "Pas d'image vidéo", Toast.LENGTH_SHORT).show(); return
        }
        val name = "Tello_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date()) + ".jpg"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TelloPilot")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
                FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            }
            Toast.makeText(this, "Photo enregistrée dans Images/TelloPilot", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur photo : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        // Sécurité : si l'app passe en arrière-plan en vol, on pose le drone.
        if (tello.flying) {
            Thread { tello.landAndCloseBlocking() }.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        video?.stop()
        Thread { tello.landAndCloseBlocking() }.start()
        scope.cancel()
    }
}
