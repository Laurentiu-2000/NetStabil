package ro.netstabil.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramSocket
import java.net.Socket

/**
 * Mentine o cerere hardware activa (requestNetwork cu TRANSPORT_CELLULAR + NET_CAPABILITY_INTERNET)
 * catre ConnectivityService din Android, plus un ascultator pasiv (registerNetworkCallback, NU requestNetwork)
 * pe Android 14-16 pentru profilul auto-certificat NET_CAPABILITY_PRIORITIZE_LATENCY.
 *
 * Folosirea registerNetworkCallback (pasiv) pentru PRIORITIZE_LATENCY previne blocarea DataNetworkController
 * pe telefoanele exclusiv 4G LTE (unde nu exista feliere 5G SA URSP si o cerere activa requestNetwork
 * poate bloca ridicarea APN-ului normal "internet" la reincarcarea CarrierConfig sau schimbarea benzii).
 */
class CellularRadioAnchor(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()

    private var primaryCallback: ConnectivityManager.NetworkCallback? = null
    private var lowLatencyCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var cellularNetwork: Network? = null

    @Volatile
    private var lowLatencyNetwork: Network? = null

    private val _isCellularPinned = MutableStateFlow(false)
    val isCellularPinned: StateFlow<Boolean> = _isCellularPinned.asStateFlow()

    private val _isLowLatencySliceActive = MutableStateFlow(false)
    val isLowLatencySliceActive: StateFlow<Boolean> = _isLowLatencySliceActive.asStateFlow()

    fun acquire() {
        synchronized(lock) {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (primaryCallback == null) {
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        cellularNetwork = network
                        _isCellularPinned.value = true
                    }

                    override fun onLost(network: Network) {
                        if (cellularNetwork == network) {
                            cellularNetwork = null
                            _isCellularPinned.value = lowLatencyNetwork != null
                        }
                    }

                    override fun onUnavailable() {
                        cellularNetwork = null
                        _isCellularPinned.value = lowLatencyNetwork != null
                    }
                }

                val registered = runCatching {
                    cm.requestNetwork(request, callback)
                    true
                }.getOrDefault(false)

                if (registered) {
                    primaryCallback = callback
                }
            }

            if (lowLatencyCallback == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val sliceRequest = runCatching {
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY)
                        .build()
                }.getOrNull()

                if (sliceRequest != null) {
                    val sliceCb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            lowLatencyNetwork = network
                            _isLowLatencySliceActive.value = true
                            _isCellularPinned.value = true
                        }

                        override fun onLost(network: Network) {
                            if (lowLatencyNetwork == network) {
                                lowLatencyNetwork = null
                                _isLowLatencySliceActive.value = false
                                _isCellularPinned.value = cellularNetwork != null
                            }
                        }

                        override fun onUnavailable() {
                            lowLatencyNetwork = null
                            _isLowLatencySliceActive.value = false
                        }
                    }

                    // CRITIC: registerNetworkCallback (pasiv) in loc de requestNetwork (activ)
                    // pentru a nu bloca APN-ul 4G "internet" pe modemurile fara 5G SA URSP.
                    val sliceRegistered = runCatching {
                        cm.registerNetworkCallback(sliceRequest, sliceCb)
                        true
                    }.getOrDefault(false)

                    if (sliceRegistered) {
                        lowLatencyCallback = sliceCb
                    }
                }
            }
        }
    }

    fun release() {
        synchronized(lock) {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            primaryCallback?.let { cb ->
                runCatching { cm.unregisterNetworkCallback(cb) }
            }
            lowLatencyCallback?.let { cb ->
                runCatching { cm.unregisterNetworkCallback(cb) }
            }
            primaryCallback = null
            lowLatencyCallback = null
            cellularNetwork = null
            lowLatencyNetwork = null
            _isCellularPinned.value = false
            _isLowLatencySliceActive.value = false
        }
    }

    fun getPinnedNetwork(): Network? = lowLatencyNetwork ?: cellularNetwork

    fun bindSocketIfMobileActive(socket: Socket, isWifiConnected: Boolean) {
        if (isWifiConnected) return
        val net = getPinnedNetwork() ?: return
        runCatching { net.bindSocket(socket) }
    }

    fun bindDatagramIfMobileActive(socket: DatagramSocket, isWifiConnected: Boolean) {
        if (isWifiConnected) return
        val net = getPinnedNetwork() ?: return
        runCatching { net.bindSocket(socket) }
    }
}
