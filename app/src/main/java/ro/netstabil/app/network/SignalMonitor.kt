package ro.netstabil.app.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthWcdma
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ro.netstabil.app.data.CellularTelemetry
import ro.netstabil.app.data.LteBandInfo
import ro.netstabil.app.data.NeighborCellSnapshot
import ro.netstabil.app.data.WifiTelemetry
import java.net.Inet4Address
import java.util.concurrent.Executors

class SignalMonitor(private val context: Context) {

    private val _cellular = MutableStateFlow(CellularTelemetry())
    val cellular: StateFlow<CellularTelemetry> = _cellular.asStateFlow()

    private val _wifi = MutableStateFlow(WifiTelemetry())
    val wifi: StateFlow<WifiTelemetry> = _wifi.asStateFlow()

    private val _signalHistoryDbm = MutableStateFlow<List<Int>>(emptyList())
    val signalHistoryDbm: StateFlow<List<Int>> = _signalHistoryDbm.asStateFlow()

    private var lastPrimaryCellId: Long? = null
    @Volatile
    var handoverCount: Int = 0
        private set

    @Volatile
    private var lastModemWakeRefreshElapsedMs: Long = 0L

    @Volatile
    private var isCarrierAggregationOverride: Boolean = false
    @Volatile
    private var is5gNsaOverride: Boolean = false
    private var displayInfoRegisteredSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var displayInfoCallbackObj: Any? = null
    private val callbackExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "netstabil-signal-cb").apply { isDaemon = true }
    }

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasPhonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    private fun ensureDisplayInfoCallback(tm: TelephonyManager, subId: Int) {
        if (!hasPhonePermission()) return
        if (displayInfoCallbackObj != null && displayInfoRegisteredSubId == subId) return
        runCatching {
            val prev = displayInfoCallbackObj as? TelephonyCallback
            if (prev != null) {
                val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val prevTm = if (displayInfoRegisteredSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    baseTm.createForSubscriptionId(displayInfoRegisteredSubId)
                } else {
                    baseTm
                }
                prevTm.unregisterTelephonyCallback(prev)
            }
            val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    val overrideType = telephonyDisplayInfo.overrideNetworkType
                    isCarrierAggregationOverride =
                        overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA ||
                        overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO
                    is5gNsaOverride =
                        overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                        overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
                }
            }
            tm.registerTelephonyCallback(callbackExecutor, cb)
            displayInfoCallbackObj = cb
            displayInfoRegisteredSubId = subId
        }
    }

    /**
     * Pe Xiaomi HyperOS si modemuri MediaTek, allCellInfo poate returna valori din cache vechi de
     * 15-45s cand ecranul este stins. Apelam requestCellInfoUpdate cu limitare de rata (10s) si
     * timeout sigur de 1.4s pentru a forta citirea proaspata direct din baseband-ul modemului.
     */
    @SuppressLint("MissingPermission")
    private suspend fun fetchFreshOrCachedCellInfo(tm: TelephonyManager): List<CellInfo> {
        val nowElapsed = SystemClock.elapsedRealtime()
        if ((nowElapsed - lastModemWakeRefreshElapsedMs) >= 10_000L) {
            lastModemWakeRefreshElapsedMs = nowElapsed
            val fresh = withTimeoutOrNull(1400L) {
                val deferred = CompletableDeferred<List<CellInfo>?>()
                val requested = runCatching {
                    tm.requestCellInfoUpdate(
                        callbackExecutor,
                        object : TelephonyManager.CellInfoCallback() {
                            override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                                deferred.complete(cellInfo)
                            }

                            override fun onError(errorCode: Int, detail: Throwable?) {
                                deferred.complete(null)
                            }
                        }
                    )
                    true
                }.getOrDefault(false)
                if (requested) deferred.await() else null
            }
            if (!fresh.isNullOrEmpty()) {
                return fresh
            }
        }
        return runCatching { tm.allCellInfo ?: emptyList() }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission")
    suspend fun pollOnce() = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNet = runCatching { cm.activeNetwork }.getOrNull()
        val caps = activeNet?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val linkProps = activeNet?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }

        val airplaneModeOn = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        }.getOrDefault(false)

        val isWifiTransport = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellTransport = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val dlKbps = caps?.linkDownstreamBandwidthKbps?.coerceAtLeast(0) ?: 0
        val ulKbps = caps?.linkUpstreamBandwidthKbps?.coerceAtLeast(0) ?: 0

        // 1. Colectare Telemetrie Celulara (compatibil API 29..36: orice producator)
        val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val activeDataSubId = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SubscriptionManager.getActiveDataSubscriptionId().let { id ->
                    if (id != SubscriptionManager.INVALID_SUBSCRIPTION_ID) id
                    else SubscriptionManager.getDefaultDataSubscriptionId()
                }
            } else {
                SubscriptionManager.getDefaultDataSubscriptionId()
            }
        }.getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)

        val tm = if (activeDataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            runCatching { baseTm.createForSubscriptionId(activeDataSubId) }.getOrDefault(baseTm)
        } else {
            baseTm
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !airplaneModeOn) {
            ensureDisplayInfoCallback(tm, activeDataSubId)
        }

        var simSlot = -1
        if (hasPhonePermission() && activeDataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            runCatching {
                val subMgr = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val subInfo = subMgr.getActiveSubscriptionInfo(activeDataSubId)
                if (subInfo != null) {
                    simSlot = subInfo.simSlotIndex + 1
                }
            }
        }

        val rawOp = runCatching {
            tm.networkOperatorName?.takeIf { it.isNotBlank() }
                ?: tm.simOperatorName?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: ro.netstabil.app.i18n.trNow("Fără Operator", "No Carrier")
        val mccMncRaw = runCatching { tm.networkOperator?.takeIf { it.length >= 5 } }.getOrNull() ?: "--"
        val formattedMccMnc = if (mccMncRaw.length >= 5) {
            "${mccMncRaw.substring(0, 3)}-${mccMncRaw.substring(3)}"
        } else {
            "--"
        }

        var netGen = if (airplaneModeOn) {
            ro.netstabil.app.i18n.trNow("Mod Avion", "Airplane")
        } else {
            resolveNetworkGeneration(tm)
        }
        var caDetected = !airplaneModeOn && isCarrierAggregationOverride
        if (!airplaneModeOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasPhonePermission()) {
            runCatching {
                val ss: ServiceState? = tm.serviceState
                val str = ss?.toString() ?: ""
                if (str.contains("isUsingCarrierAggregation=true", ignoreCase = true) ||
                    str.contains("mIsUsingCarrierAggregation = true", ignoreCase = true)
                ) {
                    caDetected = true
                }
            }
        }
        if (!airplaneModeOn && is5gNsaOverride && netGen.startsWith("4G")) {
            netGen = "5G NSA"
        } else if (caDetected && netGen == "4G LTE") {
            netGen = "4G+ LTE-A"
        }

        var rsrp: Int? = null
        var rsrq: Int? = null
        var sinr: Int? = null
        var cqiVal: Int? = null
        var asu: Int? = null
        var bars = 0
        var earfcn = -1
        var halBandNumber: Int? = null
        var secondaryBands = ""
        var bandwidthKhz = -1
        var cellId: Long? = null
        var eNodeB: Int? = null
        var sector: Int? = null
        var pci: Int? = null
        var tac: Int? = null
        var ta: Int? = null
        val neighbors = mutableListOf<NeighborCellSnapshot>()

        if (!airplaneModeOn && hasLocationPermission()) {
            val allCells: List<CellInfo> = fetchFreshOrCachedCellInfo(tm)

            val registeredCells = allCells.filter { it.isRegistered }
            val matchingDataSimCells = if (mccMncRaw.length >= 5) {
                registeredCells.filter { extractCellMccMnc(it) == mccMncRaw }
            } else {
                emptyList()
            }
            val candidateRegistered = matchingDataSimCells.ifEmpty { registeredCells }

            val registeredLteCells = candidateRegistered.filterIsInstance<CellInfoLte>()
            if (registeredLteCells.size >= 2) {
                caDetected = true
                if (netGen == "4G LTE") netGen = "4G+ LTE-A"
                val aggregatedBands = registeredLteCells.mapNotNull { lteCell ->
                    resolveLteCellBandInfo(lteCell)?.let { "B${it.bandNumber}" }
                }.distinct()
                if (aggregatedBands.size >= 2) {
                    secondaryBands = aggregatedBands.joinToString(" + ")
                }
            }

            // Pe telefoane Dual-SIM (Redmi Note 15 4G / Moto G31), alegem celula principala a SIM-ului de date activ
            val primaryCell: CellInfo? = candidateRegistered.minByOrNull { cell ->
                when (cell) {
                    is CellInfoNr -> 0
                    is CellInfoLte -> 1
                    is CellInfoWcdma -> 2
                    is CellInfoGsm -> 3
                    else -> 4
                }
            }

            if (primaryCell != null) {
                when (primaryCell) {
                    is CellInfoLte -> {
                        val id = primaryCell.cellIdentity
                        val ss = primaryCell.cellSignalStrength
                        rsrp = validMetric(ss.rsrp, -145, -40) ?: validMetric(ss.dbm, -145, -40)
                        rsrq = validMetric(ss.rsrq, -30, 0)
                        sinr = LteBandCalculator.normalizeMediaTekSinrDb(ss.rssnr)
                        cqiVal = validMetric(ss.cqi, 1, 15)
                        asu = validMetric(ss.asuLevel, 0, 97)
                        bars = ss.level.coerceIn(0, 4)

                        earfcn = validPositive(id.earfcn)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            halBandNumber = runCatching { id.bands.firstOrNull { it in 1..88 } }.getOrNull()
                        }
                        bandwidthKhz = validPositive(id.bandwidth)
                        val ci = id.ci
                        if (ci in 1 until Int.MAX_VALUE) {
                            cellId = ci.toLong()
                            eNodeB = ci shr 8
                            sector = ci and 0xFF
                        }
                        pci = validPositiveOrZero(id.pci, 503)
                        tac = validPositive(id.tac, 65535)
                        ta = validPositiveOrZero(ss.timingAdvance, 1282)
                        if (netGen == "--") netGen = if (caDetected) "4G+ LTE-A" else "4G LTE"
                    }
                    is CellInfoWcdma -> {
                        val id = primaryCell.cellIdentity
                        val ss = primaryCell.cellSignalStrength
                        rsrp = validMetric(ss.dbm, -125, -45)
                        asu = validMetric(ss.asuLevel, 0, 99)
                        bars = ss.level.coerceIn(0, 4)
                        earfcn = validPositive(id.uarfcn)
                        if (id.cid in 1 until Int.MAX_VALUE) cellId = id.cid.toLong()
                        pci = validPositiveOrZero(id.psc, 511)
                        tac = validPositive(id.lac, 65535)
                        if (netGen == "--") netGen = "3G HSPA"
                    }
                    is CellInfoGsm -> {
                        val id = primaryCell.cellIdentity
                        val ss = primaryCell.cellSignalStrength
                        rsrp = validMetric(ss.dbm, -120, -45)
                        asu = validMetric(ss.asuLevel, 0, 99)
                        bars = ss.level.coerceIn(0, 4)
                        earfcn = validPositive(id.arfcn)
                        if (id.cid in 1 until Int.MAX_VALUE) cellId = id.cid.toLong()
                        tac = validPositive(id.lac, 65535)
                        ta = validPositiveOrZero(ss.timingAdvance, 219)
                        if (netGen == "--") netGen = "2G EDGE"
                    }
                    is CellInfoNr -> {
                        val nrId = primaryCell.cellIdentity as? android.telephony.CellIdentityNr
                        val ss = primaryCell.cellSignalStrength as? android.telephony.CellSignalStrengthNr
                        rsrp = ss?.let { validMetric(it.ssRsrp, -145, -40) ?: validMetric(it.dbm, -145, -40) }
                            ?: validMetric(primaryCell.cellSignalStrength.dbm, -145, -40)
                        rsrq = ss?.let { validMetric(it.ssRsrq, -30, 0) }
                        sinr = ss?.let { validMetric(it.ssSinr, -23, 40) }
                        asu = primaryCell.cellSignalStrength.asuLevel.takeIf { it in 0..99 }
                        bars = primaryCell.cellSignalStrength.level.coerceIn(0, 4)
                        if (nrId != null) {
                            earfcn = validPositive(nrId.nrarfcn)
                            pci = validPositiveOrZero(nrId.pci, 1007)
                            tac = validPositive(nrId.tac, 16777215)
                            val nci = nrId.nci
                            if (nci in 1L until Long.MAX_VALUE) {
                                cellId = nci
                            }
                        }
                        if (netGen == "--") netGen = "5G NR"
                    }
                    else -> Unit
                }
            }

            for (cell in allCells) {
                if (cell !== primaryCell && !cell.isRegistered && neighbors.size < 6) {
                    when (cell) {
                        is CellInfoLte -> {
                            val nDbm = validMetric(cell.cellSignalStrength.rsrp, -145, -40)
                                ?: validMetric(cell.cellSignalStrength.dbm, -145, -40)
                            val nPci = validPositiveOrZero(cell.cellIdentity.pci, 503)
                            val nEarfcn = validPositive(cell.cellIdentity.earfcn)
                            val nBand = resolveLteCellBandInfo(cell)?.commonName
                                ?: if (nEarfcn > 0) "EARFCN $nEarfcn" else "LTE"
                            if (nDbm != null && nPci != null) {
                                neighbors.add(
                                    NeighborCellSnapshot(
                                        networkGeneration = "4G",
                                        pciOrCid = nPci,
                                        bandLabel = nBand,
                                        rsrpDbm = nDbm
                                    )
                                )
                            }
                        }
                        is CellInfoGsm -> {
                            val nDbm = validMetric(cell.cellSignalStrength.dbm, -120, -45)
                            val nCid = validPositive(cell.cellIdentity.cid)
                            if (nDbm != null && nCid > 0) {
                                neighbors.add(
                                    NeighborCellSnapshot(
                                        networkGeneration = "2G",
                                        pciOrCid = nCid,
                                        bandLabel = "GSM ARFCN ${cell.cellIdentity.arfcn.coerceAtLeast(0)}",
                                        rsrpDbm = nDbm
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Fallback prin SignalStrength (functioneaza chiar si fara permisiune de locatie pe API 29+)
        if (!airplaneModeOn && rsrp == null) {
            runCatching {
                val sig = tm.signalStrength
                if (sig != null) {
                    bars = sig.level.coerceIn(0, 4)
                    for (css in sig.cellSignalStrengths) {
                        when (css) {
                            is android.telephony.CellSignalStrengthNr -> {
                                rsrp = validMetric(css.ssRsrp, -145, -40) ?: validMetric(css.dbm, -145, -40)
                                rsrq = validMetric(css.ssRsrq, -30, 0)
                                sinr = validMetric(css.ssSinr, -23, 40)
                                asu = validMetric(css.asuLevel, 0, 99)
                                if (netGen == "--") netGen = if (is5gNsaOverride) "5G NSA" else "5G NR"
                                break
                            }
                            is CellSignalStrengthLte -> {
                                rsrp = validMetric(css.rsrp, -145, -40) ?: validMetric(css.dbm, -145, -40)
                                rsrq = validMetric(css.rsrq, -30, 0)
                                sinr = LteBandCalculator.normalizeMediaTekSinrDb(css.rssnr)
                                cqiVal = validMetric(css.cqi, 1, 15)
                                asu = validMetric(css.asuLevel, 0, 97)
                                if (netGen == "--") {
                                    netGen = when {
                                        is5gNsaOverride -> "5G NSA"
                                        caDetected -> "4G+ LTE-A"
                                        else -> "4G LTE"
                                    }
                                }
                                break
                            }
                            is CellSignalStrengthWcdma -> {
                                rsrp = validMetric(css.dbm, -125, -45)
                                if (netGen == "--") netGen = "3G HSPA"
                                break
                            }
                            is CellSignalStrengthGsm -> {
                                rsrp = validMetric(css.dbm, -120, -45)
                                if (netGen == "--") netGen = "2G EDGE"
                                break
                            }
                        }
                    }
                }
            }
        }

        if (cellId != null) {
            if (lastPrimaryCellId != null && lastPrimaryCellId != cellId) {
                handoverCount += 1
            }
            lastPrimaryCellId = cellId
        }

        val bandInfo = if (netGen.startsWith("4G")) {
            halBandNumber?.let { LteBandCalculator.fromBandNumber(it) }
                ?: LteBandCalculator.fromEarfcn(earfcn)
        } else {
            null
        }
        val estimatedDist = if (ta != null && netGen.startsWith("4G")) {
            (ta * 78.12).toInt()
        } else {
            null
        }

        _cellular.value = CellularTelemetry(
            operatorName = rawOp,
            mccMnc = formattedMccMnc,
            subscriptionId = if (activeDataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) activeDataSubId else -1,
            simSlotIndex = simSlot,
            networkGeneration = netGen,
            isCarrierAggregation = caDetected,
            bandInfo = bandInfo,
            secondaryBandsLabel = secondaryBands,
            earfcn = earfcn,
            bandwidthKhz = bandwidthKhz,
            rsrpDbm = rsrp,
            rsrqDb = rsrq,
            sinrDb = sinr,
            cqi = cqiVal,
            asuLevel = asu,
            signalBars = bars,
            eNodeBId = eNodeB,
            sectorId = sector,
            cellId = cellId,
            pci = pci,
            tac = tac,
            timingAdvance = ta,
            estimatedDistanceMeters = estimatedDist,
            downlinkKbps = if (isCellTransport) dlKbps else 0,
            uplinkKbps = if (isCellTransport) ulKbps else 0,
            isRoaming = runCatching { tm.isNetworkRoaming }.getOrDefault(false),
            isMobileDataActiveTransport = isCellTransport,
            neighbors = neighbors.sortedByDescending { it.rsrpDbm }
        )

        // 2. Colectare Telemetrie Wi-Fi
        val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo: WifiInfo? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (caps?.transportInfo as? WifiInfo) ?: @Suppress("DEPRECATION") wifiMgr.connectionInfo
            } else {
                @Suppress("DEPRECATION")
                wifiMgr.connectionInfo
            }
        }.getOrNull()

        val isRo = ro.netstabil.app.i18n.I18n.currentIsRo
        if (isWifiTransport) {
            // Pe Android 12-16 fara permisiunea de Locatie, OS-ul redacteaza networkId la -1 dar
            // pastreaza RSSI, frecventa, viteza link si IP/Gateway in LinkProperties.
            val defaultSsid = ro.netstabil.app.i18n.tr(isRo, "Wi-Fi Activ", "Active Wi-Fi")
            val rawSsid = wifiInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: defaultSsid
            val cleanSsid = if (rawSsid.equals("<unknown ssid>", ignoreCase = true) || rawSsid.isBlank()) {
                ro.netstabil.app.i18n.tr(isRo, "Wi-Fi Activ (SSID protejat)", "Active Wi-Fi (SSID hidden)")
            } else {
                rawSsid
            }
            val rssi = wifiInfo?.let { validMetric(it.rssi, -110, -15) }
            val freq = (wifiInfo?.frequency ?: 0).coerceAtLeast(0)
            val speed = (wifiInfo?.linkSpeed ?: 0).coerceAtLeast(0)
            val rawBssid = wifiInfo?.bssid ?: "--"
            val cleanBssid = if (rawBssid == "02:00:00:00:00:00" || rawBssid.isBlank()) "--" else rawBssid
            val localIpv4 = linkProps?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress ?: "--"
            val gwIpv4 = linkProps?.routes
                ?.mapNotNull { it.gateway }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.hostAddress != "0.0.0.0" }
                ?.hostAddress ?: extractDhcpGatewayFallback(wifiMgr)

            _wifi.value = WifiTelemetry(
                isConnected = true,
                ssid = cleanSsid,
                bssid = cleanBssid,
                rssiDbm = rssi,
                frequencyMhz = freq,
                channelLabel = LteBandCalculator.wifiChannelFromFrequency(freq, isRo),
                linkSpeedMbps = speed,
                localIp = localIpv4,
                gatewayIp = gwIpv4
            )
        } else {
            _wifi.value = WifiTelemetry(isConnected = false)
        }

        val activeDbm = if (isWifiTransport) _wifi.value.rssiDbm else rsrp
        if (activeDbm != null) {
            _signalHistoryDbm.value = (_signalHistoryDbm.value + activeDbm).takeLast(45)
        }
    }

    private fun resolveLteCellBandInfo(cell: CellInfoLte): LteBandInfo? {
        val id = cell.cellIdentity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val halBand = runCatching { id.bands.firstOrNull { it in 1..88 } }.getOrNull()
            if (halBand != null) {
                LteBandCalculator.fromBandNumber(halBand)?.let { return it }
            }
        }
        return LteBandCalculator.fromEarfcn(validPositive(id.earfcn))
    }

    @SuppressLint("MissingPermission")
    private fun resolveNetworkGeneration(tm: TelephonyManager): String {
        val type = runCatching {
            if (!hasPhonePermission()) {
                TelephonyManager.NETWORK_TYPE_UNKNOWN
            } else {
                val dataType = tm.dataNetworkType
                if (dataType != TelephonyManager.NETWORK_TYPE_UNKNOWN) dataType
                else tm.voiceNetworkType
            }
        }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)

        return when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G HSPA"
            TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_GSM,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_CDMA -> "2G GSM"
            else -> "--"
        }
    }

    private fun extractCellMccMnc(cell: CellInfo): String {
        return when (cell) {
            is CellInfoLte -> (cell.cellIdentity.mccString ?: "") + (cell.cellIdentity.mncString ?: "")
            is CellInfoWcdma -> (cell.cellIdentity.mccString ?: "") + (cell.cellIdentity.mncString ?: "")
            is CellInfoGsm -> (cell.cellIdentity.mccString ?: "") + (cell.cellIdentity.mncString ?: "")
            is CellInfoNr -> {
                val nrId = cell.cellIdentity as? android.telephony.CellIdentityNr
                (nrId?.mccString ?: "") + (nrId?.mncString ?: "")
            }
            else -> ""
        }
    }

    private fun extractDhcpGatewayFallback(wifiMgr: WifiManager): String {
        return runCatching {
            @Suppress("DEPRECATION")
            val gw = wifiMgr.dhcpInfo?.gateway ?: 0
            if (gw == 0) "--"
            else "${gw and 0xFF}.${(gw shr 8) and 0xFF}.${(gw shr 16) and 0xFF}.${(gw shr 24) and 0xFF}"
        }.getOrDefault("--")
    }

    private fun validMetric(value: Int, min: Int, max: Int): Int? {
        if (value == Int.MAX_VALUE || value == CellInfo.UNAVAILABLE) return null
        return if (value in min..max) value else null
    }

    private fun validPositive(value: Int, max: Int = Int.MAX_VALUE - 1): Int {
        if (value <= 0 || value == Int.MAX_VALUE || value > max) return -1
        return value
    }

    private fun validPositiveOrZero(value: Int, max: Int): Int? {
        if (value < 0 || value == Int.MAX_VALUE || value > max) return null
        return value
    }
}
