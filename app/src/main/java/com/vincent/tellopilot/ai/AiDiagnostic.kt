package com.vincent.tellopilot.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import org.json.JSONObject

/**
 * Test de vitesse du moteur IA : une question sans image, puis une avec image,
 * avec les temps mesurés et les lignes de timing du moteur (encodage de l'image, etc.).
 */
class AiDiagnostic(private val llama: LlamaServer, private val client: D1Client) {

    class Report(val text: String, val ok: Boolean)

    fun run(frame: Bitmap?): Report {
        val out = ArrayList<String>()
        val f = llama.cpuFeatures
        fun yes(b: Boolean) = if (b) "oui" else "non"
        out += "Moteur : ${llama.variant?.label ?: "?"} · ${llama.threads} cœurs" +
            " · dotprod ${yes("asimddp" in f)} · i8mm ${yes("i8mm" in f)}"
        return try {
            val r1 = client.ask(JSONObject().put("test", "ping"), null,
                mapOf("q" to D1Client.Question.Noul("Is this a short connectivity test message?")))
            out += "Question sans image : %.1f s (%d jetons)".format(r1.millis / 1000.0, r1.inputTokens)

            val img = frame ?: testImage()
            val mark = llama.logMark()
            val r2 = client.ask(JSONObject().put("test", "image"), bitmapToDataUri(img), mapOf("q" to D1Client.Question.Choice(
                "What does this image mostly show?",
                linkedMapOf<String, String?>("indoor" to "an indoor room", "outdoor" to "an outdoor scene", "pattern" to "an abstract pattern"),
            )))
            out += "Question avec image : %.1f s (%d jetons)%s".format(
                r2.millis / 1000.0, r2.inputTokens, if (frame == null) " · image de test" else "")
            llama.logSince(mark)
                .filter { "encoded in" in it || "decoded" in it }
                .takeLast(2)
                .forEach { out += "  " + it.substringAfter("]").trim().take(80) }
            out += "→ une étape du pilote IA prend environ %.0f s".format(r2.millis / 1000.0 + 2.0)
            Report(out.joinToString("\n"), true)
        } catch (e: Exception) {
            out += "Échec : ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
            llama.lastLog(3).lines().forEach { out += "  " + it.take(80) }
            Report(out.joinToString("\n"), false)
        }
    }

    private fun testImage(): Bitmap {
        val b = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 512f, 384f, Color.rgb(20, 60, 120), Color.rgb(230, 200, 120), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 512f, 384f, p)
        p.shader = null
        p.color = Color.rgb(200, 40, 40); c.drawCircle(160f, 200f, 70f, p)
        p.color = Color.rgb(40, 160, 80); c.drawRect(300f, 120f, 430f, 300f, p)
        return b
    }
}
