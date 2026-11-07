package com.vincent.tellopilot.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Enregistre le flux H.264 du Tello tel quel dans un MP4 (pas de ré-encodage :
 * qualité d'origine, aucune charge CPU). Commence à la première image clé.
 */
class Mp4Recorder(
    private val pfd: ParcelFileDescriptor,
    sps: ByteArray,
    pps: ByteArray,
    width: Int,
    height: Int,
) {
    private val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val track: Int
    private var started = false
    private var firstPts = -1L
    private var lastPts = -1L
    private val info = MediaCodec.BufferInfo()
    @Volatile var frames = 0; private set
    private var closed = false

    init {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
        fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        track = muxer.addTrack(fmt)
        muxer.start()
    }

    /** Appelé depuis le thread vidéo pour chaque image complète. */
    @Synchronized
    fun write(frame: ByteArray, key: Boolean, ptsUs: Long) {
        if (closed) return
        if (!started) {
            if (!key) return
            started = true
            firstPts = ptsUs
        }
        // On retire SPS/PPS/AUD/SEI (déjà dans l'en-tête du MP4) et on garde les tranches d'image.
        val out = ByteArrayOutputStream(frame.size)
        VideoPipeline.forEachNal(frame) { type, start, end ->
            if (type == 1 || type == 5) out.write(frame, start, end - start)
        }
        if (out.size() == 0) return
        var pts = ptsUs - firstPts
        if (pts <= lastPts) pts = lastPts + 1000
        lastPts = pts
        val bytes = out.toByteArray()
        info.set(0, bytes.size, pts, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        try {
            muxer.writeSampleData(track, ByteBuffer.wrap(bytes), info)
            frames++
        } catch (_: Exception) {
        }
    }

    /** @return true si la vidéo contient au moins une image. */
    @Synchronized
    fun close(): Boolean {
        if (closed) return frames > 0
        closed = true
        val ok = frames > 0
        runCatching { if (ok) muxer.stop() }
        runCatching { muxer.release() }
        runCatching { pfd.close() }
        return ok
    }
}
