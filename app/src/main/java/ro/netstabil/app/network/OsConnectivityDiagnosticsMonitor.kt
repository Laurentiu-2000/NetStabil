package ro.netstabil.app.network

import android.content.Context
import android.net.ConnectivityDiagnosticsManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors

/**
 * Asculta direct evenimentele kernelului / Netd din Android 11-16 (API 30+) prin
 * ConnectivityDiagnosticsManager (disponibil pentru aplicatii cu VPN activ sau locatie/privilegii):
 * - onDataStallSuspected: DNS consecutive timeouts sau rata mare de pierdere pachete TCP
 * - onConnectivityReportAvailable: invalidare portal captiv sau cadere validare retea
 *
 * Permite reactie sub-secunda la blocaje reale de date fara a astepta urmatorul ciclu Watchdog.
 */
class OsConnectivityDiagnosticsMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val callbackExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "netstabil-conn-diag").apply { isDaemon = true }
    }

    private var registeredCallbackObj: Any? = null

    @Volatile
    private var lastStallSignalElapsedMs: Long = 0L

    fun start(onDataStallDetected: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        synchronized(lock) {
            if (registeredCallbackObj != null) return
            registerApi30(onDataStallDetected)
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        synchronized(lock) {
            val cb = registeredCallbackObj ?: return
            registeredCallbackObj = null
            unregisterApi30(cb)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun registerApi30(onDataStallDetected: (String) -> Unit) {
        runCatching {
            val cdm = appContext.getSystemService(ConnectivityDiagnosticsManager::class.java) ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val callback = object : ConnectivityDiagnosticsManager.ConnectivityDiagnosticsCallback() {
                override fun onDataStallSuspected(
                    report: ConnectivityDiagnosticsManager.DataStallReport
                ) {
                    val now = SystemClock.elapsedRealtime()
                    if ((now - lastStallSignalElapsedMs) < 20_000L) return
                    lastStallSignalElapsedMs = now

                    val isRo = ro.netstabil.app.i18n.I18n.currentIsRo
                    val method = report.detectionMethod
                    val details = report.stallDetails
                    val reasonRo = when {
                        (method and ConnectivityDiagnosticsManager.DataStallReport.DETECTION_METHOD_DNS_EVENTS) != 0 -> {
                            val timeouts = details.getInt(
                                ConnectivityDiagnosticsManager.DataStallReport.KEY_DNS_CONSECUTIVE_TIMEOUTS,
                                0
                            )
                            if (timeouts > 0) {
                                if (isRo) "Timeouts DNS consecutive în sistem ($timeouts)"
                                else "Consecutive OS DNS timeouts ($timeouts)"
                            } else {
                                if (isRo) "Blocaj DNS raportat de sistemul Android"
                                else "DNS stall reported by Android OS"
                            }
                        }
                        (method and ConnectivityDiagnosticsManager.DataStallReport.DETECTION_METHOD_TCP_METRICS) != 0 -> {
                            val failRate = details.getInt(
                                ConnectivityDiagnosticsManager.DataStallReport.KEY_TCP_PACKET_FAIL_RATE,
                                0
                            )
                            if (failRate > 0) {
                                if (isRo) "Pierdere pachete TCP raportată de kernel ($failRate%)"
                                else "Kernel TCP packet loss reported ($failRate%)"
                            } else {
                                if (isRo) "Pierdere pachete TCP raportată de kernel"
                                else "Kernel TCP packet loss reported"
                            }
                        }
                        else -> if (isRo) {
                            "Data Stall raportat de ConnectivityDiagnosticsManager"
                        } else {
                            "Data stall reported by ConnectivityDiagnosticsManager"
                        }
                    }
                    onDataStallDetected(reasonRo)
                }

                override fun onConnectivityReportAvailable(
                    report: ConnectivityDiagnosticsManager.ConnectivityReport
                ) {
                    val validation = report.additionalInfo.getInt(
                        ConnectivityDiagnosticsManager.ConnectivityReport.KEY_NETWORK_VALIDATION_RESULT,
                        ConnectivityDiagnosticsManager.ConnectivityReport.NETWORK_VALIDATION_RESULT_VALID
                    )
                    if (validation == ConnectivityDiagnosticsManager.ConnectivityReport.NETWORK_VALIDATION_RESULT_INVALID) {
                        val now = SystemClock.elapsedRealtime()
                        if ((now - lastStallSignalElapsedMs) < 20_000L) return
                        lastStallSignalElapsedMs = now
                        onDataStallDetected(
                            ro.netstabil.app.i18n.trNow(
                                "Rețea marcată nevalidată de sistemul Android",
                                "Network marked unvalidated by Android OS"
                            )
                        )
                    }
                }
            }

            cdm.registerConnectivityDiagnosticsCallback(request, callbackExecutor, callback)
            registeredCallbackObj = callback
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun unregisterApi30(cb: Any) {
        runCatching {
            val cdm = appContext.getSystemService(ConnectivityDiagnosticsManager::class.java) ?: return
            cdm.unregisterConnectivityDiagnosticsCallback(
                cb as ConnectivityDiagnosticsManager.ConnectivityDiagnosticsCallback
            )
        }
    }
}
