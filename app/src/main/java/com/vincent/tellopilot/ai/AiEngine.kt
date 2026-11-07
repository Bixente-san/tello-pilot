package com.vincent.tellopilot.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Modèle de décision d1-2B (Liquid AI), exécuté 100 % sur le téléphone par llama.cpp.
 *
 *  - ModelStore  : téléchargement des fichiers GGUF (une seule fois, en Wi-Fi)
 *  - LlamaServer : lance le binaire llama-server embarqué dans l'APK, sur 127.0.0.1
 *  - D1Client    : pose des questions typées via /v1/systemone
 */
class ModelStore(private val context: Context) {

    data class Part(val file: String, val bytes: Long) {
        val url get() = "https://huggingface.co/LiquidAI/d1-2B-GGUF/resolve/main/$file"
    }

    val parts = listOf(
        Part("d1-2B-Q4_K_M.gguf", 1_100_000_000L),
        Part("mmproj-d1-2B-Q8_0.gguf", 203_000_000L),
    )

    val dir: File get() = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    fun file(p: Part) = File(dir, p.file)
    val model get() = file(parts[0])
    val mmproj get() = file(parts[1])

    private val prefs = context.getSharedPreferences("ai_models", Context.MODE_PRIVATE)
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** Fichier présent, complet, et aucun téléchargement en cours dessus. */
    private fun complete(p: Part): Boolean {
        val f = file(p)
        return f.exists() && f.length() > p.bytes * 0.9 && downloadId(p) == -1L
    }

    fun ready(): Boolean {
        progress() // range les téléchargements terminés
        return parts.all { complete(it) }
    }

    private fun downloadId(p: Part) = prefs.getLong(p.file, -1L)

    /** Lance (ou reprend) le téléchargement des fichiers manquants. Wi-Fi uniquement. */
    fun download() {
        for (p in parts) {
            if (complete(p)) continue
            val existing = downloadId(p)
            if (existing != -1L && status(existing) in setOf(DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED)) continue
            file(p).delete()
            val req = DownloadManager.Request(Uri.parse(p.url))
                .setTitle("Tello Pilot · ${p.file}")
                .setDescription("Modèle IA d1-2B")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, null, "models/${p.file}")
                .setAllowedOverMetered(false)
                .setAllowedOverRoaming(false)
            prefs.edit().putLong(p.file, dm.enqueue(req)).apply()
        }
    }

    private fun status(id: Long): Int {
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return -1
            return c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
    }

    data class Progress(val done: Long, val total: Long, val waitingForWifi: Boolean, val failed: Boolean, val active: Boolean)

    /** État global des téléchargements. Nettoie les téléchargements terminés. */
    fun progress(): Progress {
        var done = 0L; var total = 0L
        var waiting = false; var failed = false; var active = false
        for (p in parts) {
            total += p.bytes
            val id = downloadId(p)
            if (id == -1L) { if (complete(p)) done += p.bytes; continue }
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) { prefs.edit().remove(p.file).apply(); return@use }
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> { prefs.edit().remove(p.file).apply(); done += p.bytes }
                    DownloadManager.STATUS_FAILED -> { prefs.edit().remove(p.file).apply(); failed = true }
                    DownloadManager.STATUS_PAUSED -> {
                        waiting = true; active = true; done += got
                    }
                    else -> { active = true; done += got }
                }
            }
        }
        return Progress(done, total, waiting, failed, active)
    }

    fun delete() {
        for (p in parts) {
            val id = downloadId(p)
            if (id != -1L) dm.remove(id)
            prefs.edit().remove(p.file).apply()
            file(p).delete()
        }
    }
}

/** Le binaire llama-server embarqué, lancé comme processus local. */
class LlamaServer(private val context: Context, private val store: ModelStore) {
    val port = 18080
    @Volatile private var process: Process? = null
    private val log = ArrayDeque<String>()

    val binary: File get() = File(context.applicationInfo.nativeLibraryDir, "libllamaserver.so")

    val running get() = process?.isAlive == true

    fun lastLog(n: Int = 12): String = synchronized(log) { log.takeLast(n).joinToString("\n") }

    /** Démarre le serveur et attend qu'il ait chargé le modèle. Bloquant. */
    fun start(onStatus: (String) -> Unit): Boolean {
        if (running && healthy()) return true
        stop()
        if (!binary.exists()) { onStatus("Moteur IA absent de cette version de l'app"); return false }
        if (!store.ready()) { onStatus("Modèle non téléchargé"); return false }
        val threads = Runtime.getRuntime().availableProcessors().let { if (it >= 8) 4 else (it / 2).coerceAtLeast(2) }
        val cmd = listOf(
            binary.absolutePath,
            "-m", store.model.absolutePath,
            "--mmproj", store.mmproj.absolutePath,
            "--host", "127.0.0.1", "--port", "$port",
            "-c", "4096", "-b", "2048", "-ub", "2048",
            "-t", "$threads", "-np", "1",
            "--image-max-tokens", "256",
            "--no-webui",
        )
        onStatus("Démarrage du moteur IA…")
        val p = try {
            ProcessBuilder(cmd).redirectErrorStream(true).directory(context.filesDir).start()
        } catch (e: Exception) {
            onStatus("Impossible de lancer le moteur : ${e.message}"); return false
        }
        process = p
        Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(log) { log.addLast(line); while (log.size > 200) log.removeFirst() }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        onStatus("Chargement du modèle d1-2B…")
        val deadline = SystemClock.uptimeMillis() + 120_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (!p.isAlive) { onStatus("Le moteur s'est arrêté :\n${lastLog(4)}"); return false }
            if (healthy()) { onStatus("Moteur IA prêt"); return true }
            SystemClock.sleep(500)
        }
        onStatus("Le moteur ne répond pas")
        stop()
        return false
    }

    fun healthy(): Boolean = try {
        val c = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
        c.connectTimeout = 400; c.readTimeout = 800
        val ok = c.responseCode == 200
        c.disconnect()
        ok
    } catch (_: Exception) { false }

    fun stop() {
        process?.destroy()
        process = null
    }
}

/** Questions typées à d1 via l'API System One de llama-server. */
class D1Client(private val port: Int) {

    sealed class Question(val type: String) {
        abstract val instructions: String
        class Choice(override val instructions: String, val options: LinkedHashMap<String, String?>) : Question("choice")
        class Noul(override val instructions: String) : Question("noul")
    }

    class ChoiceAnswer(val best: String, val probabilities: Map<String, Double>, val confidence: Double)

    class Result(val choices: Map<String, ChoiceAnswer>, val nouls: Map<String, Double>, val inputTokens: Int, val millis: Long)

    fun ask(state: Any?, imageDataUri: String?, questions: Map<String, Question>): Result {
        val q = JSONObject()
        for ((id, question) in questions) {
            val o = JSONObject().put("type", question.type).put("instructions", question.instructions)
            if (question is Question.Choice) {
                val crit = JSONObject()
                for ((k, v) in question.options) crit.put(k, v ?: JSONObject.NULL)
                o.put("criteria", crit)
            }
            q.put(id, o)
        }
        val body = JSONObject()
            .put("state", state ?: JSONObject.NULL)
            .put("questions", q)
        if (imageDataUri != null) body.put("images", JSONArray().put(imageDataUri))

        val t0 = SystemClock.uptimeMillis()
        val c = URL("http://127.0.0.1:$port/v1/systemone").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 2000
        c.readTimeout = 60_000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException("d1 HTTP $code : ${text.take(200)}")

        val json = JSONObject(text)
        val answers = json.getJSONObject("answers")
        val choices = HashMap<String, ChoiceAnswer>()
        val nouls = HashMap<String, Double>()
        for (id in answers.keys()) {
            val a = answers.getJSONObject(id)
            when (a.optString("type")) {
                "choice" -> {
                    val pr = a.getJSONObject("probabilities")
                    val map = LinkedHashMap<String, Double>()
                    for (k in pr.keys()) map[k] = pr.getDouble(k)
                    choices[id] = ChoiceAnswer(a.getString("choice"), map, a.optDouble("confidence", 0.0))
                }
                "noul" -> nouls[id] = a.getDouble("noul")
            }
        }
        val tokens = json.optJSONObject("usage")?.optInt("input_tokens") ?: 0
        return Result(choices, nouls, tokens, SystemClock.uptimeMillis() - t0)
    }
}
