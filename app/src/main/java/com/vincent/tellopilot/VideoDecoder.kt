package com.vincent.tellopilot

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * Reçoit le flux H.264 brut du Tello (UDP 11111), le découpe en NAL units
 * et le décode avec le décodeur matériel du téléphone vers une Surface.
 */
class VideoDecoder(
    private val surface: Surface,
    private val onLog: (String) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "tello-video").apply { start() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        thread?.join(1000)
        thread = null
    }

    private fun loop() {
        var codec: MediaCodec? = null
        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 1000
                receiveBufferSize = 512 * 1024
                bind(InetSocketAddress(11111))
            }
            socket = sock

            val stream = ByteArrayOutputStream(256 * 1024)
            val buf = ByteArray(2048)
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            val info = MediaCodec.BufferInfo()
            val t0 = System.nanoTime()

            while (running) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                stream.write(pkt.data, 0, pkt.length)

                // Extrait toutes les NAL complètes (entre deux start codes 00 00 00 01)
                val data = stream.toByteArray()
                val starts = findStartCodes(data)
                if (starts.size < 2) {
                    if (data.size > 2 * 1024 * 1024) stream.reset() // flux corrompu
                    continue
                }
                for (i in 0 until starts.size - 1) {
                    val nal = data.copyOfRange(starts[i], starts[i + 1])
                    val type = nal[4].toInt() and 0x1F

                    if (codec == null) {
                        if (type == 7) sps = nal
                        if (type == 8) pps = nal
                        val s = sps; val p = pps
                        if (s != null && p != null) {
                            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 960, 720)
                            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(s))
                            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(p))
                            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                                configure(fmt, surface, null, 0)
                                start()
                            }
                            onLog("Vidéo ✓")
                        }
                        continue
                    }

                    val c = codec!!
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = c.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        if (nal.size <= inBuf.capacity()) {
                            inBuf.put(nal)
                            val pts = (System.nanoTime() - t0) / 1000
                            c.queueInputBuffer(inIdx, 0, nal.size, pts, 0)
                        } else {
                            c.queueInputBuffer(inIdx, 0, 0, 0, 0)
                        }
                    }
                    // Affiche toutes les images prêtes
                    while (true) {
                        val outIdx = c.dequeueOutputBuffer(info, 0)
                        if (outIdx >= 0) c.releaseOutputBuffer(outIdx, true)
                        else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    }
                }
                // Garde la NAL incomplète pour le prochain paquet
                val rest = data.copyOfRange(starts.last(), data.size)
                stream.reset()
                stream.write(rest)
            }
        } catch (e: Exception) {
            if (running) onLog("Erreur vidéo : ${e.message}")
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun findStartCodes(d: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i + 3 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) {
                out.add(i)
                i += 4
            } else {
                i++
            }
        }
        return out
    }
}
