package com.vincent.tellopilot.ai

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.vincent.tellopilot.drone.TelloDrone
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Actions élémentaires que d1 peut choisir. Chaque action = un petit pas, puis stationnaire. */
enum class AiAction(
    val key: String,
    val label: String,
    val description: String,
    val roll: Float = 0f, val pitch: Float = 0f, val throttle: Float = 0f, val yaw: Float = 0f,
    val ms: Long = 0,
) {
    FORWARD("forward", "Avancer", "fly forward: the goal is ahead and roughly centered, and the way is clear", pitch = 0.4f, ms = 900),
    TURN_LEFT("turn_left", "Tourner à gauche", "rotate left in place: the goal is on the left side of the image, or is not visible and must be searched for", yaw = -0.6f, ms = 600),
    TURN_RIGHT("turn_right", "Tourner à droite", "rotate right in place: the goal is on the right side of the image", yaw = 0.6f, ms = 600),
    UP("up", "Monter", "climb higher", throttle = 0.5f, ms = 700),
    DOWN("down", "Descendre", "descend lower", throttle = -0.45f, ms = 600),
    LEFT("left", "Glisser à gauche", "slide sideways to the left without turning", roll = -0.4f, ms = 700),
    RIGHT("right", "Glisser à droite", "slide sideways to the right without turning", roll = 0.4f, ms = 700),
    BACK("back", "Reculer", "fly backward: the drone is too close, or an obstacle is right in front", pitch = -0.35f, ms = 700),
    DONE("done", "Terminé", "the instruction is fulfilled: stop and hover");

    companion object {
        fun of(key: String) = values().firstOrNull { it.key == key }
    }
}

/**
 * Pilote IA : à chaque étape, le drone s'immobilise, d1 regarde l'image de la caméra
 * et choisit la prochaine action pour suivre la consigne ; le drone fait un petit pas.
 *
 * Sécurités : vitesse lente forcée, pas courts, 40 étapes max, altitude max, batterie,
 * liaison, et reprise en main immédiate dès que tu touches un joystick.
 */
class Navigator(
    private val drone: TelloDrone,
    private val client: D1Client,
    private val grab: () -> Bitmap?,
    private val altLimitM: () -> Float,
    private val listener: Listener,
) {
    enum class Phase { THINKING, MOVING }

    class Decision(
        val step: Int,
        val ranked: List<Pair<AiAction, Double>>,
        val executed: AiAction?,
        val note: String?,
        val millis: Long,
    )

    interface Listener {
        fun onPhase(phase: Phase, step: Int)
        fun onDecision(d: Decision)
        fun onFinished(message: String, success: Boolean)
    }

    /** Une session par consigne : une ancienne session encore bloquée sur le modèle ne peut plus agir. */
    private class Session {
        val aborted = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
    }

    @Volatile private var session: Session? = null
    val running get() = session?.finished?.get() == false

    var instruction = ""; private set

    /** dryRun = analyse une seule image sans bouger (utilisable au sol). */
    fun start(instruction: String, dryRun: Boolean) {
        if (running) return
        this.instruction = instruction.trim()
        val s = Session()
        session = s
        Thread({ loop(s, dryRun) }, "ai-navigator").apply { isDaemon = true; start() }
    }

    /** Arrêt immédiat : sticks au neutre, le drone reste en stationnaire. */
    fun abort(message: String) {
        val s = session ?: return
        if (s.finished.get()) return
        s.aborted.set(true)
        drone.centerSticks()
        finish(s, message, false)
    }

    private fun finish(s: Session, message: String, success: Boolean) {
        if (s.finished.compareAndSet(false, true)) listener.onFinished(message, success)
    }

    private fun loop(s: Session, dryRun: Boolean) {
        val aborted = s.aborted
        fun finish(message: String, success: Boolean) = finish(s, message, success)
        val savedFast = drone.fastMode
        if (!dryRun) drone.fastMode = false
        val history = ArrayDeque<String>()
        var step = 0
        var unsure = 0
        try {
            while (!aborted.get()) {
                if (!dryRun) {
                    safetyStop()?.let { finish(it, false); return }
                    if (step >= MAX_STEPS) { finish("Limite de $MAX_STEPS étapes atteinte", false); return }
                    drone.centerSticks()
                    sleep(450) // laisse le drone se stabiliser avant de regarder
                }
                if (aborted.get()) return
                listener.onPhase(Phase.THINKING, step + 1)

                val bmp = grab() ?: run { finish("Pas d'image vidéo", false); return }
                val image = toDataUri(bmp)
                val res = client.ask(state(step, history, dryRun), image, mapOf("action" to question()))
                if (aborted.get()) return

                val ans = res.choices["action"] ?: run { finish("Réponse inattendue du modèle", false); return }
                val ranked = ans.probabilities.mapNotNull { (k, v) -> AiAction.of(k)?.let { it to v } }
                    .sortedByDescending { it.second }
                val (best, p) = ranked.first()

                if (dryRun) {
                    listener.onDecision(Decision(step + 1, ranked, null, "Analyse seule, le drone ne bouge pas", res.millis))
                    finish("Analyse terminée", true)
                    return
                }

                if (best == AiAction.DONE && p >= DONE_THRESHOLD) {
                    listener.onDecision(Decision(step + 1, ranked, AiAction.DONE, null, res.millis))
                    finish("Consigne accomplie", true)
                    return
                }
                if (p < MIN_PROB || best == AiAction.DONE) {
                    unsure++
                    listener.onDecision(Decision(step + 1, ranked, null, "Hésitation (${(p * 100).toInt()} %) : nouvelle image", res.millis))
                    if (unsure >= MAX_UNSURE) { finish("d1 hésite trop : reformule la consigne", false); return }
                    continue
                }
                unsure = 0

                val h = drone.lastFlight?.heightM ?: 0f
                val blocked = when {
                    best == AiAction.UP && h >= altLimitM() - 0.3f -> "Altitude max atteinte"
                    best == AiAction.DOWN && h <= 0.5f -> "Trop près du sol"
                    else -> null
                }
                if (blocked != null) {
                    listener.onDecision(Decision(step + 1, ranked, null, blocked, res.millis))
                    history.addLast("${best.key} (refused: $blocked)")
                } else {
                    listener.onDecision(Decision(step + 1, ranked, best, null, res.millis))
                    listener.onPhase(Phase.MOVING, step + 1)
                    execute(best, aborted)
                    history.addLast(best.key)
                }
                while (history.size > 5) history.removeFirst()
                step++
            }
        } catch (e: Exception) {
            if (!aborted.get()) finish("Erreur du moteur IA : ${e.message ?: e.javaClass.simpleName}", false)
        } finally {
            if (session === s || !aborted.get()) {
                drone.centerSticks()
                drone.fastMode = savedFast
            }
            finish("Arrêt", false)
        }
    }

    private fun safetyStop(): String? {
        if (drone.link != TelloDrone.Link.CONNECTED) return "Liaison perdue : arrêt du pilote IA"
        val f = drone.lastFlight ?: return "Pas de télémétrie"
        if (!f.flying) return "Le drone n'est plus en vol"
        if (f.battery in 1..15) return "Batterie faible : arrêt du pilote IA"
        return null
    }

    private fun execute(a: AiAction, aborted: AtomicBoolean) {
        val end = SystemClock.uptimeMillis() + a.ms
        while (SystemClock.uptimeMillis() < end && !aborted.get()) {
            drone.roll = a.roll; drone.pitch = a.pitch; drone.throttle = a.throttle; drone.yaw = a.yaw
            sleep(40)
        }
        drone.centerSticks()
        sleep(250) // freinage
    }

    private fun state(step: Int, history: ArrayDeque<String>, dryRun: Boolean): JSONObject {
        val f = drone.lastFlight
        return JSONObject()
            .put("instruction", instruction)
            .put("camera", "The image is the live view of the drone's forward-facing camera.")
            .put("drone", JSONObject()
                .put("flying", f?.flying ?: !dryRun)
                .put("altitude_m", ((f?.heightM ?: 0f) * 10).toInt() / 10.0)
                .put("step", step + 1))
            .put("previous_actions", JSONArray(history.toList()))
    }

    private fun question() = D1Client.Question.Choice(
        "You pilot a small indoor drone using its front camera image. " +
            "Choose the single best next action to carry out the user's instruction. " +
            "Choose 'done' only when the instruction is clearly fulfilled.",
        LinkedHashMap<String, String?>().apply { for (a in AiAction.values()) put(a.key, a.description) },
    )

    private fun toDataUri(b: Bitmap): String {
        val out = ByteArrayOutputStream(64 * 1024)
        b.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun sleep(ms: Long) = SystemClock.sleep(ms)

    companion object {
        const val MAX_STEPS = 40
        const val MIN_PROB = 0.30
        const val DONE_THRESHOLD = 0.50
        const val MAX_UNSURE = 3
    }
}
