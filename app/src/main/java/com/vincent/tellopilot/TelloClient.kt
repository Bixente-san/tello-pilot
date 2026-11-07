package com.vincent.tellopilot

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.resume

/**
 * Pilote le Tello via le SDK texte officiel (UDP) :
 *  - commandes  -> 192.168.10.1:8889 (réponses "ok"/"error" sur le même socket)
 *  - état       <- port local 8890 ("bat:87;h:30;...")
 *  - vidéo H264 <- port local 11111 (géré par VideoDecoder)
 */
class TelloClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit,
    private val onState: (Map<String, String>) -> Unit,
) {
    private val telloAddr = InetSocketAddress(InetAddress.getByName("192.168.10.1"), 8889)
    private var cmdSocket: DatagramSocket? = null
    private var stateSocket: DatagramSocket? = null
    private val cmdMutex = Mutex()
    private var rcJob: Job? = null
    private var stateJob: Job? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile var connected = false; private set
    @Volatile var flying = false; private set

    // Valeurs des joysticks, -100..100
    @Volatile var lr = 0
    @Volatile var fb = 0
    @Volatile var ud = 0
    @Volatile var yaw = 0

    /**
     * Le Wi-Fi du Tello n'a pas d'accès Internet : Android (et surtout HyperOS)
     * a tendance à envoyer le trafic en 4G. On force tout le process sur le Wi-Fi.
     */
    private suspend fun bindToWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network: Network? = withTimeoutOrNull(8000) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }
                }
                wifiCallback = cb
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.requestNetwork(req, cb)
            }
        }
        if (network == null) return false
        return cm.bindProcessToNetwork(network)
    }

    fun connect() {
        if (connected) return
        scope.launch(Dispatchers.IO) {
            onLog("Connexion au Wi-Fi du Tello…")
            if (!bindToWifi()) {
                onLog("Wi-Fi introuvable : connecte le téléphone au réseau TELLO-xxxx")
                close()
                return@launch
            }
            try {
                cmdSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 3000
                    bind(InetSocketAddress(8889))
                }
                stateSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 3000
                    bind(InetSocketAddress(8890))
                }
            } catch (e: Exception) {
                onLog("Erreur socket : ${e.message}")
                return@launch
            }

            var ok = false
            repeat(5) {
                if (!ok) ok = sendAndWait("command") == "ok"
            }
            if (!ok) {
                onLog("Le Tello ne répond pas. Il est allumé et le Wi-Fi connecté ?")
                close()
                return@launch
            }
            connected = true
            onLog("Connecté ✓")
            startStateLoop()
            sendAndWait("streamon")
            startRcLoop()
        }
    }

    private suspend fun sendAndWait(cmd: String, timeoutMs: Int = 3000): String? = cmdMutex.withLock {
        val sock = cmdSocket ?: return null
        return try {
            val data = cmd.toByteArray()
            sock.soTimeout = timeoutMs
            sock.send(DatagramPacket(data, data.size, telloAddr))
            val buf = ByteArray(1024)
            val pkt = DatagramPacket(buf, buf.size)
            sock.receive(pkt)
            String(pkt.data, 0, pkt.length).trim()
        } catch (e: SocketTimeoutException) {
            null
        } catch (e: Exception) {
            onLog("Erreur envoi '$cmd' : ${e.message}")
            null
        }
    }

    /** Commande sans attente de réponse (rc, emergency). */
    private fun sendRaw(cmd: String) {
        val sock = cmdSocket ?: return
        try {
            val data = cmd.toByteArray()
            sock.send(DatagramPacket(data, data.size, telloAddr))
        } catch (_: Exception) {
        }
    }

    private fun startRcLoop() {
        rcJob?.cancel()
        rcJob = scope.launch(Dispatchers.IO) {
            while (isActive && connected) {
                // Envoyé toutes les 50 ms : sert aussi de keep-alive
                // (sans commande pendant 15 s, le Tello atterrit tout seul).
                if (!cmdMutex.isLocked) sendRaw("rc $lr $fb $ud $yaw")
                delay(50)
            }
        }
    }

    private fun startStateLoop() {
        stateJob?.cancel()
        stateJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(1024)
            while (isActive && connected) {
                val sock = stateSocket ?: break
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val txt = String(pkt.data, 0, pkt.length).trim()
                    val map = txt.split(';')
                        .mapNotNull { kv ->
                            val i = kv.indexOf(':')
                            if (i > 0) kv.substring(0, i) to kv.substring(i + 1) else null
                        }.toMap()
                    onState(map)
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    fun takeoff() = scope.launch(Dispatchers.IO) {
        if (!connected) return@launch onLog("Pas connecté")
        onLog("Décollage…")
        val r = sendAndWait("takeoff", 20000)
        if (r == "ok") { flying = true; onLog("En vol ✓") } else onLog("Décollage : ${r ?: "pas de réponse"}")
    }

    fun land() = scope.launch(Dispatchers.IO) {
        if (!connected) return@launch
        lr = 0; fb = 0; ud = 0; yaw = 0
        onLog("Atterrissage…")
        val r = sendAndWait("land", 20000)
        flying = false
        onLog(if (r == "ok") "Posé ✓" else "Atterrissage : ${r ?: "pas de réponse"}")
    }

    /** dir = f, b, l ou r */
    fun flip(dir: String) = scope.launch(Dispatchers.IO) {
        if (!flying) return@launch onLog("Décolle d'abord")
        val r = sendAndWait("flip $dir", 10000)
        if (r != "ok") onLog("Flip : ${r ?: "pas de réponse"} (batterie > 50 % requise)")
    }

    /** Coupe les moteurs immédiatement. Le drone tombe. */
    fun emergency() {
        scope.launch(Dispatchers.IO) {
            repeat(3) { sendRaw("emergency") }
            flying = false
            onLog("ARRÊT D'URGENCE")
        }
    }

    fun close() {
        connected = false
        rcJob?.cancel()
        stateJob?.cancel()
        try { cmdSocket?.close() } catch (_: Exception) {}
        try { stateSocket?.close() } catch (_: Exception) {}
        cmdSocket = null
        stateSocket = null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        wifiCallback = null
        cm.bindProcessToNetwork(null)
    }

    /** Appelé quand l'app passe en arrière-plan : on pose le drone par sécurité. */
    fun landAndCloseBlocking() {
        if (connected && flying) {
            lr = 0; fb = 0; ud = 0; yaw = 0
            sendRaw("land")
            flying = false
        }
        close()
    }
}
