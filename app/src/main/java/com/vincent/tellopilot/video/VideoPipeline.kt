package com.vincent.tellopilot.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import com.vincent.tellopilot.drone.Protocol
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * Vidéo du Tello (protocole natif) :
 *  - chaque paquet UDP = [n° d'image][index | 0x80 si dernier] + morceau de H.264 ;
 *  - on reconstitue des images entières ; une image incomplète est jetée et on
 *    demande une image clé au drone au lieu d'envoyer des données corrompues au décodeur ;
 *  - un chien de garde réinitialise le décodeur s'il ne produit plus d'image (écran figé).
 */
class VideoPipeline(private val listener: Listener) {

    interface Listener {
        fun requestKeyframe()
        fun onVideoSize(width: Int, height: Int)
        fun onVideoAlive(alive: Boolean)
    }

    @Volatile private var running = false
    private var rxThread: Thread? = null
    private var outThread: Thread? = null
    private var socket: DatagramSocket? = null

    private val codecLock = Any()
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var surface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    @Volatile private var configuredSps: ByteArray? = null

    @Volatile var width = 960; private set
    @Volatile var height = 720; private set

    private var needKeyframe = true
    @Volatile private var lastOutput = 0L
    private var lastQueued = 0L
    private var lastPacket = 0L
    @Volatile private var alive = false
    private val t0 = System.nanoTime()

    @Volatile var recorder: Mp4Recorder? = null

    // Reconstitution des images
    private val frame = ByteArrayOutputStream(128 * 1024)
    private var frameNo = -1
    private var nextIdx = 0
    private var corrupt = false

    fun start() {
        if (running) return
        running = true
        rxThread = Thread(::rxLoop, "video-rx").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        outThread = Thread(::outLoop, "video-out").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        socket?.close()
        releaseCodec()
    }

    fun setSurface(s: Surface?) {
        surface = s
        if (s == null) releaseCodec() else needKeyframe = true
    }

    val spsPps: Pair<ByteArray, ByteArray>? get() {
        val s = sps; val p = pps
        return if (s != null && p != null) s to p else null
    }

    // ---------------------------------------------------------------- réseau

    private fun rxLoop() {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val sock = socket ?: DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 300
                    receiveBufferSize = 1024 * 1024
                    bind(InetSocketAddress(Protocol.VIDEO_PORT))
                }.also { socket = it }
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                    lastPacket = SystemClock.uptimeMillis()
                    onPacket(pkt.data, pkt.length)
                } catch (_: SocketTimeoutException) {
                }
                watchdog()
            } catch (_: Exception) {
                socket?.close(); socket = null
                SystemClock.sleep(200)
            }
        }
    }

    private fun onPacket(d: ByteArray, len: Int) {
        if (len < 3) return
        val no = d[0].toInt() and 0xff
        val idx = d[1].toInt() and 0x7f
        val last = (d[1].toInt() and 0x80) != 0

        if (idx == 0) {
            if (frameNo != -1) lost() // l'image précédente n'a jamais été terminée
            frame.reset()
            frameNo = no
            nextIdx = 0
            corrupt = false
        } else if (no != frameNo || idx != nextIdx) {
            if (!corrupt && frameNo != -1) lost()
            corrupt = true
        }
        if (!corrupt && frameNo == no) {
            frame.write(d, 2, len - 2)
            nextIdx = idx + 1
        }
        if (last) {
            if (!corrupt && frameNo == no) onFrame(frame.toByteArray())
            frameNo = -1
            corrupt = false
        }
    }

    private fun lost() {
        needKeyframe = true
        listener.requestKeyframe()
    }

    // ---------------------------------------------------------------- H.264

    private fun onFrame(f: ByteArray) {
        var hasIdr = false
        var hasSps = false
        forEachNal(f) { type, start, end ->
            when (type) {
                7 -> { sps = f.copyOfRange(start, end); hasSps = true }
                8 -> pps = f.copyOfRange(start, end)
                5 -> hasIdr = true
            }
        }
        val s = sps
        val p = pps
        if (surface != null && s != null && p != null && (codec == null || !configuredSps.contentEquals(s))) {
            createCodec(s, p)
        }

        val pts = (System.nanoTime() - t0) / 1000
        recorder?.write(f, hasIdr, pts)

        val c = codec ?: return
        if (needKeyframe) {
            if (!hasIdr) { listener.requestKeyframe(); return }
            needKeyframe = false
        }
        try {
            val i = c.dequeueInputBuffer(40_000)
            if (i < 0) { lost(); return }
            val ib = c.getInputBuffer(i) ?: return
            ib.clear()
            if (f.size > ib.capacity()) { c.queueInputBuffer(i, 0, 0, pts, 0); lost(); return }
            ib.put(f)
            c.queueInputBuffer(i, 0, f.size, pts, if (hasSps || hasIdr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            lastQueued = SystemClock.uptimeMillis()
            if (lastOutput == 0L) lastOutput = lastQueued
        } catch (_: Exception) {
            releaseCodec()
        }
    }

    private fun createCodec(s: ByteArray, p: ByteArray) {
        releaseCodec()
        val surf = surface ?: return
        fun build(lowLatency: Boolean): MediaCodec {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(s))
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(p))
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
            if (lowLatency && Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                try {
                    configure(fmt, surf, null, 0)
                    start()
                } catch (e: Exception) {
                    release(); throw e
                }
            }
        }
        val c = try { build(true) } catch (_: Exception) {
            try { build(false) } catch (_: Exception) { null }
        } ?: return
        synchronized(codecLock) { codec = c }
        configuredSps = s
        needKeyframe = true
        lastOutput = 0L
    }

    private fun releaseCodec() {
        val c = synchronized(codecLock) { codec.also { codec = null } } ?: return
        runCatching { c.stop() }
        runCatching { c.release() }
        configuredSps = null
        lastOutput = 0L
    }

    private fun outLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val c = codec
            if (c == null) { SystemClock.sleep(20); continue }
            try {
                val o = synchronized(codecLock) {
                    if (codec !== c) MediaCodec.INFO_TRY_AGAIN_LATER
                    else c.dequeueOutputBuffer(info, 0)
                }
                when {
                    o >= 0 -> {
                        synchronized(codecLock) { if (codec === c) c.releaseOutputBuffer(o, true) }
                        lastOutput = SystemClock.uptimeMillis()
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        val w = f.getInteger(MediaFormat.KEY_WIDTH)
                        val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                        val cw = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else w
                        val ch = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else h
                        width = cw; height = ch
                        listener.onVideoSize(cw, ch)
                    }
                    else -> SystemClock.sleep(4)
                }
            } catch (_: Exception) {
                SystemClock.sleep(20)
            }
        }
    }

    private fun watchdog() {
        val now = SystemClock.uptimeMillis()
        val nowAlive = now - lastPacket < 1500 && (codec == null || lastOutput == 0L || now - lastOutput < 1500)
        if (nowAlive != alive) { alive = nowAlive; listener.onVideoAlive(nowAlive) }
        // Des données arrivent mais plus rien ne sort du décodeur : on le relance.
        val lo = lastOutput
        if (codec != null && lo != 0L && now - lastQueued < 500 && now - lo > 2000) {
            val s = sps; val p = pps
            releaseCodec()
            if (s != null && p != null) createCodec(s, p)
            listener.requestKeyframe()
        }
    }

    companion object {
        /** Parcourt les NAL (séparées par 00 00 01 / 00 00 00 01). start = début du start code. */
        inline fun forEachNal(f: ByteArray, block: (type: Int, start: Int, end: Int) -> Unit) {
            val starts = ArrayList<Int>()
            val heads = ArrayList<Int>()
            var i = 0
            while (i + 3 < f.size) {
                if (f[i].toInt() == 0 && f[i + 1].toInt() == 0) {
                    if (f[i + 2].toInt() == 1) { starts.add(i); heads.add(i + 3); i += 3; continue }
                    if (f[i + 2].toInt() == 0 && f[i + 3].toInt() == 1) { starts.add(i); heads.add(i + 4); i += 4; continue }
                }
                i++
            }
            for (k in starts.indices) {
                val h = heads[k]
                if (h >= f.size) continue
                val end = if (k + 1 < starts.size) starts[k + 1] else f.size
                block(f[h].toInt() and 0x1f, starts[k], end)
            }
        }
    }
}
