package ro.netstabil.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.IpSecManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.SocketKeepalive
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Keepalive Hardware Offload direct in procesorul baseband al modemului 4G/5G
 * (Android CDD 7.4.2.6 [C-1-2]: "MUST support at least one keepalive slot over cellular").
 *
 * Foloseste API-ul oficial Android ConnectivityManager.createSocketKeepalive impreuna cu
 * IpSecManager.openUdpEncapsulationSocket (nu necesita Root si nici macar Shizuku - functioneaza
 * inclusiv in MOD NORMAL!).
 *
 * Cand modemul accepta comanda (IRadio.startKeepalive -> Callback.onStarted), cipul radio trimite
 * autonom din hardware un pachet NAT-T de 29 octeti (payload 0xFF) la fiecare 10-30 secunde cu
 * 0% utilizare CPU principal, mentinand vie tabela CGNAT a operatorului (Vodafone / Orange) si
 * sesiunea radio chiar si atunci cand procesorul aplicatiei intra in somn adanc.
 */
class HardwareModemKeepaliveOffload(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val callbackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "netstabil-hw-keepalive").apply { isDaemon = true }
    }

    private var sessionGeneration: Long = 0L
    private var isStarting: Boolean = false
    private var startAttemptElapsedMs: Long = 0L
    private var activeNetworkHandle: Long = -1L
    private var activeSourceIp: String = ""
    private var activeIntervalSec: Int = 0
    private var unsupportedNetworkHandle: Long = -1L
    private var cooldownNetworkHandle: Long = -1L
    private var retryNotBeforeElapsedMs: Long = 0L

    private var encapSocket: IpSecManager.UdpEncapsulationSocket? = null
    private var socketKeepalive: SocketKeepalive? = null

    private val _isOffloadActive = MutableStateFlow(false)
    val isOffloadActive: StateFlow<Boolean> = _isOffloadActive.asStateFlow()

    private val _activeIntervalSecFlow = MutableStateFlow(0)
    val activeIntervalSecFlow: StateFlow<Int> = _activeIntervalSecFlow.asStateFlow()

    /**
     * Sincronizeaza slotul hardware de keepalive pe reteaua celulara activa.
     * Daca Wi-Fi este activ sau keepAliveEnabled este oprit, elibereaza curat slotul hardware.
     */
    fun sync(enabled: Boolean, isWifiConnected: Boolean, preferredCellularNetwork: Network?) {
        if (!enabled || isWifiConnected) {
            release()
            return
        }

        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val ipSec = appContext.getSystemService(Context.IPSEC_SERVICE) as? IpSecManager
            ?: return

        val targetNet = resolveCellularNetwork(cm, preferredCellularNetwork)
        if (targetNet == null) {
            release()
            return
        }

        val linkProps = runCatching { cm.getLinkProperties(targetNet) }.getOrNull()
        val srcIpv4 = extractNativeIpv4Address(linkProps)
        if (srcIpv4 == null) {
            // Pe retele exclusiv IPv6 fara adresa IPv4 nativa pe rmnet/ccmni, NattKeepalivePacketData
            // ar returna ERROR_INVALID_IP_ADDRESS; eliberam curat fara erori.
            release()
            return
        }

        val netHandle = targetNet.networkHandle
        val srcHost = srcIpv4.hostAddress ?: return

        synchronized(lock) {
            val nowElapsed = SystemClock.elapsedRealtime()
            if (unsupportedNetworkHandle == netHandle) {
                return
            }
            if (cooldownNetworkHandle == netHandle && nowElapsed < retryNotBeforeElapsedMs) {
                return
            }

            if (socketKeepalive != null &&
                activeNetworkHandle == netHandle &&
                activeSourceIp == srcHost
            ) {
                if (_isOffloadActive.value) {
                    return
                }
                if (isStarting && (nowElapsed - startAttemptElapsedMs) < START_TIMEOUT_MS) {
                    return
                }
            }

            closeInternalLocked()
            val myGen = ++sessionGeneration
            var currentRequestedSec = PREFERRED_INTERVAL_SEC

            runCatching {
                val udpEncap = ipSec.openUdpEncapsulationSocket()
                val dstIpv4 = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))

                val callback = object : SocketKeepalive.Callback() {
                    override fun onStarted() {
                        synchronized(lock) {
                            if (sessionGeneration != myGen) return
                            isStarting = false
                            _activeIntervalSecFlow.value = currentRequestedSec
                            _isOffloadActive.value = true
                        }
                    }

                    override fun onStopped() {
                        synchronized(lock) {
                            if (sessionGeneration != myGen) return
                            isStarting = false
                            _isOffloadActive.value = false
                            _activeIntervalSecFlow.value = 0
                        }
                    }

                    override fun onDataReceived() {
                        synchronized(lock) {
                            if (sessionGeneration != myGen) return
                            cooldownNetworkHandle = netHandle
                            retryNotBeforeElapsedMs =
                                SystemClock.elapsedRealtime() + ERROR_RETRY_COOLDOWN_MS
                            closeInternalLocked()
                        }
                    }

                    override fun onError(error: Int) {
                        synchronized(lock) {
                            if (sessionGeneration != myGen) return
                            val nextSec = if (error == SocketKeepalive.ERROR_INVALID_INTERVAL) {
                                nextFallbackIntervalSec(currentRequestedSec)
                            } else {
                                null
                            }

                            if (nextSec != null && socketKeepalive != null) {
                                currentRequestedSec = nextSec
                                activeIntervalSec = nextSec
                                isStarting = true
                                startAttemptElapsedMs = SystemClock.elapsedRealtime()
                                val started = runCatching {
                                    socketKeepalive?.start(nextSec)
                                    true
                                }.getOrDefault(false)
                                if (!started) {
                                    cooldownNetworkHandle = netHandle
                                    retryNotBeforeElapsedMs =
                                        SystemClock.elapsedRealtime() + ERROR_RETRY_COOLDOWN_MS
                                    closeInternalLocked()
                                }
                            } else {
                                if (error == SocketKeepalive.ERROR_UNSUPPORTED) {
                                    unsupportedNetworkHandle = netHandle
                                } else {
                                    cooldownNetworkHandle = netHandle
                                    retryNotBeforeElapsedMs =
                                        SystemClock.elapsedRealtime() + ERROR_RETRY_COOLDOWN_MS
                                }
                                closeInternalLocked()
                            }
                        }
                    }
                }

                val ka = cm.createSocketKeepalive(
                    targetNet,
                    udpEncap,
                    srcIpv4,
                    dstIpv4,
                    callbackExecutor,
                    callback
                )
                encapSocket = udpEncap
                socketKeepalive = ka
                activeNetworkHandle = netHandle
                activeSourceIp = srcHost
                activeIntervalSec = PREFERRED_INTERVAL_SEC
                isStarting = true
                startAttemptElapsedMs = nowElapsed
                ka.start(PREFERRED_INTERVAL_SEC)
            }.onFailure {
                cooldownNetworkHandle = netHandle
                retryNotBeforeElapsedMs = SystemClock.elapsedRealtime() + ERROR_RETRY_COOLDOWN_MS
                closeInternalLocked()
            }
        }
    }

    fun release() {
        synchronized(lock) {
            cooldownNetworkHandle = -1L
            retryNotBeforeElapsedMs = 0L
            closeInternalLocked()
        }
    }

    private fun closeInternalLocked() {
        sessionGeneration += 1L
        isStarting = false
        startAttemptElapsedMs = 0L
        _isOffloadActive.value = false
        _activeIntervalSecFlow.value = 0
        activeNetworkHandle = -1L
        activeSourceIp = ""
        activeIntervalSec = 0
        socketKeepalive?.let { ka ->
            runCatching { ka.stop() }
            runCatching { ka.close() }
        }
        socketKeepalive = null
        encapSocket?.let { sock ->
            runCatching { sock.close() }
        }
        encapSocket = null
    }

    private fun resolveCellularNetwork(
        cm: ConnectivityManager,
        preferred: Network?
    ): Network? {
        if (preferred != null) {
            val caps = runCatching { cm.getNetworkCapabilities(preferred) }.getOrNull()
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                return preferred
            }
        }
        val active = cm.activeNetwork ?: return null
        val caps = runCatching { cm.getNetworkCapabilities(active) }.getOrNull()
        return if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
            active
        } else {
            null
        }
    }

    companion object {
        const val PREFERRED_INTERVAL_SEC = 10 // SocketKeepalive.MIN_INTERVAL_SEC = 10s
        const val FALLBACK_INTERVAL_SEC = 15
        const val CONSERVATIVE_INTERVAL_SEC = 30
        private const val START_TIMEOUT_MS = 10_000L
        private const val ERROR_RETRY_COOLDOWN_MS = 60_000L

        /**
         * Trepte de fallback (10s -> 15s -> 30s) pentru compatibilitate intre modemurile noi
         * Android 14-16 (Helio G100 Ultra, 10s) si RIL-urile mai vechi Android 10-12 (Helio G85).
         */
        fun nextFallbackIntervalSec(currentSec: Int): Int? = when (currentSec) {
            PREFERRED_INTERVAL_SEC -> FALLBACK_INTERVAL_SEC
            FALLBACK_INTERVAL_SEC -> CONSERVATIVE_INTERVAL_SEC
            else -> null
        }

        /**
         * Extrage prima adresa IPv4 nativa de pe interfata celulara principala (excluzand loopback
         * si adresele sintetice CLAT 192.0.0.0/29 din RFC 7335 care nu exista in baseband-ul hardware).
         */
        fun isUsableBasebandIpv4(bytes: ByteArray): Boolean {
            if (bytes.size != 4) return false
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val b3 = bytes[3].toInt() and 0xFF
            if (b0 == 0 || b0 == 127) return false
            // Exclude RFC 7335 IPv4 Service Continuity Prefix 192.0.0.0/29 (464XLAT clatd)
            if (b0 == 192 && b1 == 0 && b2 == 0 && (b3 in 0..7)) return false
            return true
        }

        private fun extractNativeIpv4Address(linkProps: LinkProperties?): Inet4Address? {
            if (linkProps == null) return null
            for (la in linkProps.linkAddresses) {
                val addr = la.address
                if (addr is Inet4Address && isUsableBasebandIpv4(addr.address)) {
                    return addr
                }
            }
            return null
        }
    }
}
