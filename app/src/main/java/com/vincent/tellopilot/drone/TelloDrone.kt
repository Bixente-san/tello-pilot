package com.vincent.tellopilot.drone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Liaison avec le Tello via son protocole binaire (celui de l'app officielle).
 *
 * - Les sticks partent à 50 Hz depuis un thread dédié, sans aucun verrou :
 *   une commande lente (décollage, flip…) ne peut plus bloquer le pilotage.
 * - Les commandes importantes sont renvoyées tant que le drone n'a pas accusé réception.
 * - Toute la connexion passe par le Wi-Fi du Tello, même s'il n'a pas Internet.
 */
class TelloDrone(private val context: Context, private val listener: Listener) {

    enum class Link { NO_WIFI, CONNECTING, CONNECTED }

    interface Listener {
        fun onLink(link: Link)
        fun onFlightData(fd: FlightData)
        fun onWifi(strength: Int)
        fun onAttitude(att: Attitude)
        fun onPhoto(jpeg: ByteArray)
    }

    // Sticks, -1..1. Écrits par l'UI, lus par le thread des sticks.
    @Volatile var roll = 0f
    @Volatile var pitch = 0f
    @Volatile var throttle = 0f
    @Volatile var yaw = 0f
    @Volatile var fastMode = false

    // Réglages appliqués à chaque (re)connexion
    @Volatile var videoBitrate = 0       // 0 = auto, 1..5 = 1 / 1,5 / 2 / 3 / 4 Mb/s
    @Volatile var wideVideo = false      // false = 4:3 (960x720), true = 16:9
    @Volatile var altLimitM = 10

    @Volatile var link = Link.NO_WIFI; private set
    @Volatile var lastFlight: FlightData? = null; private set
    val flying get() = lastFlight?.flying == true

    private val telloAddr = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 10, 1)), 8889)
    @Volatile private var socket: DatagramSocket? = null
    private val seq = AtomicInteger(0x01e4)
    @Volatile private var lastRx = 0L
    private var lastConnReq = 0L
    private var lastVideoStart = 0L
    private var lastAttitudeCb = 0L
    @Volatile private var running = false
    private var rxThread: Thread? = null
    private var ticker: ScheduledExecutorService? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private class Pending(val bytes: ByteArray, var left: Int, var nextAt: Long)
    private val pending = ConcurrentHashMap<Int, Pending>()

    private class Download(val num: Int, val size: Int) {
        val buf = ByteArray(size)
        val chunks = IntArray(size / 1024 / 8 + 2)
        var received = 0
    }
    private val downloads = HashMap<Int, Download>()

    // ---------------------------------------------------------------- cycle de vie

    fun start() {
        if (running) return
        running = true
        watchWifi()
        rxThread = Thread(::rxLoop, "tello-rx").apply { isDaemon = true; start() }
        ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tello-tick").apply { isDaemon = true } }
            .also { it.scheduleAtFixedRate(::tick, 0, 20, TimeUnit.MILLISECONDS) }
    }

    fun stop() {
        running = false
        ticker?.shutdownNow(); ticker = null
        socket?.close(); socket = null
        rxThread = null
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        netCallback = null
        runCatching { cm.bindProcessToNetwork(null) }
        setLink(Link.NO_WIFI)
    }

    /**
     * Le Wi-Fi du Tello n'a pas Internet : sans ça, Android (HyperOS en particulier)
     * envoie les paquets en 4G. On attache tout le process au Wi-Fi dès qu'il apparaît.
     */
    private fun watchWifi() {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                openSocket()
                setLink(Link.CONNECTING)
            }
            override fun onLost(network: Network) {
                cm.bindProcessToNetwork(null)
                setLink(Link.NO_WIFI)
            }
        }
        netCallback = cb
        cm.requestNetwork(req, cb)
    }

    private fun openSocket() {
        socket?.close()
        socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 500
                receiveBufferSize = 256 * 1024
                bind(InetSocketAddress(0))
            }
        } catch (_: Exception) { null }
        lastConnReq = 0
    }

    private fun setLink(l: Link) {
        if (link == l) return
        link = l
        if (l != Link.CONNECTED) lastFlight = null
        listener.onLink(l)
    }

    // ---------------------------------------------------------------- émission

    private fun send(bytes: ByteArray) {
        val s = socket ?: return
        try { s.send(DatagramPacket(bytes, bytes.size, telloAddr)) } catch (_: Exception) {}
    }

    private fun cmd(cmd: Int, type: Int, payload: ByteArray = ByteArray(0), retries: Int = 0) {
        val p = Protocol.packet(cmd, type, payload, nextSeq())
        send(p)
        if (retries > 0) pending[cmd] = Pending(p, retries, SystemClock.uptimeMillis() + 250)
    }

    private fun nextSeq(): Int {
        val v = seq.incrementAndGet() and 0xffff
        return if (v == 0) seq.incrementAndGet() and 0xffff else v
    }

    private fun tick() {
        try {
            val now = SystemClock.uptimeMillis()
            if (socket == null) return
            when (link) {
                Link.NO_WIFI -> {}
                Link.CONNECTING -> if (now - lastConnReq > 500) {
                    lastConnReq = now
                    send(Protocol.connReq())
                }
                Link.CONNECTED -> {
                    if (now - lastRx > 3000) { setLink(Link.CONNECTING); return }
                    val c = Calendar.getInstance()
                    send(Protocol.stick(roll, pitch, throttle, yaw, fastMode,
                        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), c.get(Calendar.MILLISECOND)))
                    // Demande régulière d'image clé : la vidéo se rétablit seule après une perte.
                    if (now - lastVideoStart > 1000) requestKeyframe()
                    for ((k, p) in pending) {
                        if (now >= p.nextAt) {
                            if (p.left-- <= 0) pending.remove(k)
                            else { send(p.bytes); p.nextAt = now + 250 }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- réception

    private fun rxLoop() {
        val buf = ByteArray(4096)
        while (running) {
            val s = socket
            if (s == null) { SystemClock.sleep(100); continue }
            try {
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                lastRx = SystemClock.uptimeMillis()
                handle(pkt.data, pkt.length)
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                SystemClock.sleep(50)
            }
        }
    }

    private fun handle(d: ByteArray, len: Int) {
        if (len >= 9 && d[0] == 'c'.code.toByte() && String(d, 0, 9, Charsets.US_ASCII) == "conn_ack:") {
            if (link != Link.CONNECTED) onConnected()
            return
        }
        if (len < 11 || (d[0].toInt() and 0xff) != 0xCC) return
        if (link != Link.CONNECTED) setLink(Link.CONNECTED)
        val id = Protocol.u16(d, 5)
        pending.remove(id)
        when (id) {
            Protocol.FLIGHT_MSG -> if (len >= 9 + FlightData.MIN_SIZE) {
                val fd = FlightData(d, 9)
                lastFlight = fd
                listener.onFlightData(fd)
            }
            Protocol.WIFI_MSG -> listener.onWifi(d[9].toInt() and 0xff)
            Protocol.LOG_HEADER_MSG -> send(
                Protocol.packet(Protocol.LOG_HEADER_MSG, 0x50, byteArrayOf(0, d[9], d[10]), nextSeq())
            )
            Protocol.LOG_DATA_MSG -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastAttitudeCb > 40) {
                    LogParser.parseAttitude(d, 10, len)?.let { lastAttitudeCb = now; listener.onAttitude(it) }
                }
            }
            Protocol.FILE_SIZE -> if (len >= 18) {
                val size = Protocol.u32(d, 10).toInt()
                val num = Protocol.u16(d, 14)
                if (size in 1..20_000_000) downloads[num] = Download(num, size)
                send(d.copyOf(len)) // accusé de réception = renvoi du paquet
            }
            Protocol.FILE_DATA -> if (len >= 23) onFileData(d, len)
        }
    }

    private fun onConnected() {
        lastRx = SystemClock.uptimeMillis()
        setLink(Link.CONNECTED)
        val c = Calendar.getInstance()
        cmd(Protocol.TIME_CMD, 0x50, Protocol.timePayload(
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), c.get(Calendar.MILLISECOND)))
        applySettings()
        requestKeyframe()
    }

    fun applySettings() {
        if (link != Link.CONNECTED) return
        cmd(Protocol.VIDEO_ENCODER_RATE_CMD, 0x68, byteArrayOf(videoBitrate.toByte()))
        cmd(Protocol.VIDEO_MODE_CMD, 0x68, byteArrayOf(if (wideVideo) 1 else 0))
        cmd(Protocol.SET_ALT_LIMIT_CMD, 0x68, byteArrayOf(altLimitM.toByte(), 0))
    }

    private fun onFileData(d: ByteArray, len: Int) {
        val num = Protocol.u16(d, 9)
        val chunk = Protocol.u32(d, 11).toInt()
        val frag = Protocol.u32(d, 15).toInt()
        val size = Protocol.u16(d, 19)
        val dl = downloads[num] ?: return
        if (chunk !in dl.chunks.indices || 21 + size > len) return
        val bit = 1 shl (frag % 8)
        if (dl.chunks[chunk] and bit != 0) return
        val off = frag * 1024
        if (off + size > dl.size) return
        System.arraycopy(d, 21, dl.buf, off, size)
        dl.received += size
        dl.chunks[chunk] = dl.chunks[chunk] or bit
        if (dl.chunks[chunk] == 0xFF) {
            cmd(Protocol.FILE_DATA, 0x50, byteArrayOf(0) + le16(num) + le32(chunk))
        }
        if (dl.received >= dl.size) {
            cmd(Protocol.FILE_DATA, 0x50, byteArrayOf(1) + le16(num) + le32(chunk))
            cmd(Protocol.FILE_COMPLETE, 0x48, le16(num) + le32(dl.size))
            downloads.remove(num)
            listener.onPhoto(dl.buf)
        }
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v ushr 8) and 0xff).toByte())
    private fun le32(v: Int) = le16(v) + le16(v ushr 16)

    // ---------------------------------------------------------------- commandes

    fun requestKeyframe() {
        val now = SystemClock.uptimeMillis()
        if (now - lastVideoStart < 300) return
        lastVideoStart = now
        send(Protocol.packet(Protocol.VIDEO_START_CMD, 0x60, ByteArray(0), 0))
    }

    fun takeoff() = cmd(Protocol.TAKEOFF_CMD, 0x68, ByteArray(0), retries = 4)
    fun land() = cmd(Protocol.LAND_CMD, 0x68, byteArrayOf(0), retries = 4)
    fun palmLand() = cmd(Protocol.PALM_LAND_CMD, 0x68, byteArrayOf(0), retries = 4)
    /** Lance et vole : les hélices tournent au ralenti ~5 s, on lance le drone, il se stabilise. */
    fun throwAndGo() = cmd(Protocol.THROW_AND_GO_CMD, 0x48, byteArrayOf(0), retries = 2)
    /** 0 avant, 1 gauche, 2 arrière, 3 droite, 4 avant-gauche, 5 arrière-gauche, 6 arrière-droite, 7 avant-droite */
    fun flip(dir: Int) = cmd(Protocol.FLIP_CMD, 0x70, byteArrayOf(dir.toByte()))
    fun bounce(on: Boolean) = cmd(Protocol.BOUNCE_CMD, 0x68, byteArrayOf(if (on) 0x30 else 0x31), retries = 2)
    fun takePicture() = cmd(Protocol.TAKE_PICTURE_CMD, 0x68)

    /** Coupe les moteurs immédiatement : le drone tombe. */
    fun emergency() {
        repeat(3) { send(Protocol.emergency()) }
    }

    /**
     * 2e voie si le drone n'a pas obéi : on passe le drone en mode SDK texte ("command")
     * puis on renvoie "emergency". Ne sert qu'en dernier recours (le drone est en train de tomber).
     */
    fun emergencySdk() {
        Thread {
            send("command".toByteArray(Charsets.US_ASCII))
            SystemClock.sleep(150)
            repeat(3) { send(Protocol.emergency()); SystemClock.sleep(50) }
        }.start()
    }

    fun centerSticks() { roll = 0f; pitch = 0f; throttle = 0f; yaw = 0f }
}
