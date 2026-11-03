package ro.netstabil.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ro.netstabil.app.data.ConnectionHeadline
import ro.netstabil.app.data.DiagnosticReport
import ro.netstabil.app.data.DnsProvider
import ro.netstabil.app.data.EventSeverity
import ro.netstabil.app.data.ProbeSnapshot
import ro.netstabil.app.data.SessionStats
import ro.netstabil.app.data.SettingsRepository
import ro.netstabil.app.network.CellularRadioAnchor
import ro.netstabil.app.network.HardwareModemKeepaliveOffload
import ro.netstabil.app.network.OsConnectivityDiagnosticsMonitor
import ro.netstabil.app.network.SignalMonitor
import ro.netstabil.app.privileged.PrivilegeManager
import ro.netstabil.app.privileged.PrivilegeTier
import ro.netstabil.app.service.DnsTurboEngine
import ro.netstabil.app.service.LocalDnsVpnService
import ro.netstabil.app.service.StabilizerForegroundService
import ro.netstabil.app.tile.NetStabilTileService
import ro.netstabil.app.widget.NetStabilWidgetProvider

class StabilizerController(
    private val appContext: Context,
    val settingsRepository: SettingsRepository,
    val privilegeManager: PrivilegeManager,
    val signalMonitor: SignalMonitor,
    val cellularRadioAnchor: CellularRadioAnchor,
    val hardwareKeepaliveOffload: HardwareModemKeepaliveOffload,
    val osDiagnosticsMonitor: OsConnectivityDiagnosticsMonitor,
    val diagnosticsEngine: NetworkDiagnosticsEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _isBusyReconnecting = MutableStateFlow(false)
    val isBusyReconnecting: StateFlow<Boolean> = _isBusyReconnecting.asStateFlow()

    private val _probeSnapshot = MutableStateFlow(ProbeSnapshot())
    val probeSnapshot: StateFlow<ProbeSnapshot> = _probeSnapshot.asStateFlow()

    private val _healthScore = MutableStateFlow(0)
    val healthScore: StateFlow<Int> = _healthScore.asStateFlow()

    private val _headline = MutableStateFlow(ConnectionHeadline.STABLE)
    val headline: StateFlow<ConnectionHeadline> = _headline.asStateFlow()

    private val _sessionStats = MutableStateFlow(SessionStats())
    val sessionStats: StateFlow<SessionStats> = _sessionStats.asStateFlow()

    private val _diagnosticReport = MutableStateFlow<DiagnosticReport?>(null)
    val diagnosticReport: StateFlow<DiagnosticReport?> = _diagnosticReport.asStateFlow()

    private val _isRunningDiagnostic = MutableStateFlow(false)
    val isRunningDiagnostic: StateFlow<Boolean> = _isRunningDiagnostic.asStateFlow()

    private val _isLocalDnsTunnelActive = MutableStateFlow(false)
    val isLocalDnsTunnelActive: StateFlow<Boolean> = _isLocalDnsTunnelActive.asStateFlow()

    private var foregroundUiPollingJob: Job? = null
    private val telemetryMutex = Mutex()

    @Volatile
    private var lastOsStallRecoveryElapsedMs: Long = 0L

    init {
        scope.launch {
            settingsRepository.settingsFlow.collect { s ->
                ro.netstabil.app.i18n.I18n.updateLanguage(s.appLanguage)
            }
        }
        scope.launch {
            privilegeManager.status.collect { priv ->
                if (priv.hasWriteSecureSettings || priv.activeTier != PrivilegeTier.NORMAL) {
                    val snap = settingsRepository.getSnapshot()
                    val cell = signalMonitor.cellular.value
                    val slot0 = (cell.simSlotIndex - 1).coerceAtLeast(0)
                    privilegeManager.syncDeepSystemOptimizations(
                        settings = snap,
                        subId = cell.subscriptionId,
                        simSlotZeroBased = slot0
                    )
                }
            }
        }
        scope.launch {
            combine(
                _isServiceRunning,
                cellularRadioAnchor.isCellularPinned,
                cellularRadioAnchor.isLowLatencySliceActive,
                hardwareKeepaliveOffload.isOffloadActive,
                hardwareKeepaliveOffload.activeIntervalSecFlow
            ) { running, pinned, slice, hwOffload, hwInterval ->
                if (running) {
                    SessionHardwareSnapshot(pinned, slice, hwOffload, hwInterval)
                } else {
                    SessionHardwareSnapshot(false, false, false, 0)
                }
            }.collect { hw ->
                _sessionStats.update { current ->
                    current.copy(
                        isCellularAnchorActive = hw.pinned,
                        isLowLatencySliceActive = hw.slice,
                        isHardwareKeepaliveOffloadActive = hw.offloadActive,
                        hardwareKeepaliveIntervalSec = hw.intervalSec
                    )
                }
            }
        }
    }

    private data class SessionHardwareSnapshot(
        val pinned: Boolean,
        val slice: Boolean,
        val offloadActive: Boolean,
        val intervalSec: Int
    )

    fun setServiceRunningState(running: Boolean) {
        _isServiceRunning.value = running
        _sessionStats.update { current ->
            if (running && current.serviceStartedAtMs == 0L) {
                SessionStats(
                    serviceStartedAtMs = System.currentTimeMillis(),
                    isCellularAnchorActive = cellularRadioAnchor.isCellularPinned.value,
                    isLowLatencySliceActive = cellularRadioAnchor.isLowLatencySliceActive.value,
                    isHardwareKeepaliveOffloadActive = hardwareKeepaliveOffload.isOffloadActive.value,
                    hardwareKeepaliveIntervalSec = hardwareKeepaliveOffload.activeIntervalSecFlow.value,
                    dnsCacheHits = current.dnsCacheHits
                )
            } else if (!running) {
                current.copy(
                    serviceStartedAtMs = 0L,
                    isCellularAnchorActive = false,
                    isLowLatencySliceActive = false,
                    isHardwareKeepaliveOffloadActive = false,
                    hardwareKeepaliveIntervalSec = 0
                )
            } else {
                current
            }
        }
        NetStabilTileService.requestSyncTile(appContext)
        NetStabilWidgetProvider.updateAllWidgets(appContext)
    }

    fun setLocalDnsTunnelState(active: Boolean) {
        _isLocalDnsTunnelActive.value = active
    }

    fun startForegroundUiSampling() {
        if (foregroundUiPollingJob?.isActive == true) return
        foregroundUiPollingJob = scope.launch {
            privilegeManager.refreshState()
            while (isActive) {
                if (!_isServiceRunning.value) {
                    refreshTelemetryAndProbeOnce()
                }
                delay(4000L)
            }
        }
    }

    fun stopForegroundUiSampling() {
        foregroundUiPollingJob?.cancel()
        foregroundUiPollingJob = null
    }

    suspend fun refreshTelemetryAndProbeOnce() = telemetryMutex.withLock {
        val settings = settingsRepository.getSnapshot()
        signalMonitor.pollOnce()
        val cell = signalMonitor.cellular.value
        val wifi = signalMonitor.wifi.value
        val probe = diagnosticsEngine.runWatchdogProbe(wifi, settings.dnsProvider)
        _probeSnapshot.value = probe
        _healthScore.value = diagnosticsEngine.computeHealthScore(cell, wifi, probe)
        _headline.value = diagnosticsEngine.determineHeadline(
            cell,
            wifi,
            probe,
            settings.wifiCutoffRssiDbm
        )
        _sessionStats.update { current ->
            current.copy(
                cellHandoversDetected = signalMonitor.handoverCount,
                isCellularAnchorActive = cellularRadioAnchor.isCellularPinned.value,
                isLowLatencySliceActive = cellularRadioAnchor.isLowLatencySliceActive.value,
                isHardwareKeepaliveOffloadActive = hardwareKeepaliveOffload.isOffloadActive.value,
                hardwareKeepaliveIntervalSec = hardwareKeepaliveOffload.activeIntervalSecFlow.value
            )
        }
        NetStabilWidgetProvider.updateAllWidgets(appContext)
    }

    fun incrementKeepAliveCounter() {
        _sessionStats.update { current ->
            current.copy(
                keepAlivePulsesSent = current.keepAlivePulsesSent + 1,
                cellHandoversDetected = signalMonitor.handoverCount,
                isCellularAnchorActive = cellularRadioAnchor.isCellularPinned.value,
                isLowLatencySliceActive = cellularRadioAnchor.isLowLatencySliceActive.value,
                isHardwareKeepaliveOffloadActive = hardwareKeepaliveOffload.isOffloadActive.value,
                hardwareKeepaliveIntervalSec = hardwareKeepaliveOffload.activeIntervalSecFlow.value
            )
        }
    }

    fun incrementRrcTurboCounter() {
        _sessionStats.update { current ->
            current.copy(
                keepAlivePulsesSent = current.keepAlivePulsesSent + 1,
                rrcTurboPulsesSent = current.rrcTurboPulsesSent + 1,
                cellHandoversDetected = signalMonitor.handoverCount,
                isCellularAnchorActive = cellularRadioAnchor.isCellularPinned.value,
                isLowLatencySliceActive = cellularRadioAnchor.isLowLatencySliceActive.value,
                isHardwareKeepaliveOffloadActive = hardwareKeepaliveOffload.isOffloadActive.value,
                hardwareKeepaliveIntervalSec = hardwareKeepaliveOffload.activeIntervalSecFlow.value
            )
        }
    }

    fun incrementDnsCacheHitCounter() {
        _sessionStats.update { current ->
            current.copy(dnsCacheHits = current.dnsCacheHits + 1)
        }
    }

    fun incrementAutoReconnectCounter() {
        _sessionStats.update { current ->
            current.copy(autoReconnectionsTriggered = current.autoReconnectionsTriggered + 1)
        }
    }

    fun increment2gRecoveryCounter() {
        _sessionStats.update { current ->
            current.copy(trap2gRecoveries = current.trap2gRecoveries + 1)
        }
    }

    fun incrementWeakWifiSwitchCounter() {
        _sessionStats.update { current ->
            current.copy(weakWifiSwitches = current.weakWifiSwitches + 1)
        }
    }

    /**
     * Apelat instant cand ConnectivityDiagnosticsManager (API 30+) raporteaza un Data Stall
     * (DNS consecutive timeouts sau TCP packet loss) direct din kernelul Android.
     */
    fun onKernelDataStallDetected(reasonRo: String) {
        _sessionStats.update { current ->
            current.copy(osDataStallsDetected = current.osDataStallsDetected + 1)
        }
        scope.launch {
            if (_isBusyReconnecting.value) return@launch
            val wifiNow = signalMonitor.wifi.value
            if (privilegeManager.isSystemAirplaneModeEnabled() && !wifiNow.isConnected) return@launch
            if (!wifiNow.isConnected && !privilegeManager.isMobileDataEnabledByUser()) return@launch
            val settings = settingsRepository.getSnapshot()
            val probe = diagnosticsEngine.runWatchdogProbe(
                signalMonitor.wifi.value,
                settings.dnsProvider
            )
            _probeSnapshot.value = probe

            if (probe.isCaptivePortal) {
                _headline.value = ConnectionHeadline.CAPTIVE_PORTAL
                settingsRepository.appendEvent(
                    EventSeverity.INFO,
                    ro.netstabil.app.i18n.trNow(
                        "$reasonRo -> Portal Captiv detectat pe Wi-Fi (așteaptă autentificare în browser).",
                        "$reasonRo -> Wi-Fi Captive Portal detected (awaiting browser login)."
                    )
                )
            } else if (!probe.isInternetReachable) {
                val nowElapsed = SystemClock.elapsedRealtime()
                if (settings.autoReconnectEnabled &&
                    (nowElapsed - lastOsStallRecoveryElapsedMs) >= 35_000L
                ) {
                    lastOsStallRecoveryElapsedMs = nowElapsed
                    executeActiveReconnect(
                        reasonRo = reasonRo,
                        allowAirplaneFallback = false
                    )
                } else {
                    reportNetworkBadToAndroid()
                    settingsRepository.appendEvent(
                        EventSeverity.ALERT,
                        ro.netstabil.app.i18n.trNow(
                            "$reasonRo -> s-a solicitat reevaluarea conexiunii în sistem.",
                            "$reasonRo -> requested OS network re-evaluation."
                        )
                    )
                }
            } else {
                settingsRepository.appendEvent(
                    EventSeverity.INFO,
                    ro.netstabil.app.i18n.trNow(
                        "$reasonRo -> verificat instant, ruta IP răspunde (${probe.ipLatencyMs ?: "--"} ms).",
                        "$reasonRo -> verified instantly, IP route responded (${probe.ipLatencyMs ?: "--"} ms)."
                    )
                )
            }
        }
    }

    fun startStabilizerFromUi() {
        scope.launch {
            settingsRepository.setStabilizerWanted(true)
            StabilizerForegroundService.start(appContext, fromForegroundUi = true)
        }
    }

    fun stopStabilizer() {
        scope.launch {
            settingsRepository.setStabilizerWanted(false)
            settingsRepository.appendEvent(
                EventSeverity.INFO,
                ro.netstabil.app.i18n.trNow(
                    "Stabilizarea a fost oprită de utilizator.",
                    "Signal stabilization stopped by user."
                )
            )
            StabilizerForegroundService.stop(appContext)
        }
    }

    suspend fun executeActiveReconnect(reasonRo: String, allowAirplaneFallback: Boolean): Boolean {
        if (privilegeManager.isSystemAirplaneModeEnabled() && !signalMonitor.wifi.value.isConnected) {
            _headline.value = ConnectionHeadline.AIRPLANE_MODE
            return false
        }
        if (!_isBusyReconnecting.compareAndSet(false, true)) return false
        _headline.value = ConnectionHeadline.FROZEN_RECONNECTING
        try {
            privilegeManager.refreshState()
            val isRo = ro.netstabil.app.i18n.I18n.currentIsRo
            val tier = privilegeManager.status.value.activeTier
            settingsRepository.appendEvent(
                EventSeverity.ACTION,
                if (isRo) {
                    "Reconectare inițiată ($reasonRo) în ${tier.short(true)}."
                } else {
                    "Reconnection started ($reasonRo) in ${tier.short(false)}."
                }
            )

            if (tier == PrivilegeTier.ROOT || tier == PrivilegeTier.SHIZUKU) {
                val settings = settingsRepository.getSnapshot()
                val wifiOn = signalMonitor.wifi.value.isConnected
                var recovered = false
                var recoveredLatencyMs: Int? = null

                if (wifiOn) {
                    val switchedOrReset = if (settings.smartWifiCutoffEnabled) {
                        privilegeManager.disableWeakWifiAndSwitchToMobile()
                    } else {
                        privilegeManager.reassociateWifiSession()
                    }
                    if (switchedOrReset) {
                        delay(2800L)
                        signalMonitor.pollOnce()
                        val check = diagnosticsEngine.runWatchdogProbe(
                            signalMonitor.wifi.value,
                            settings.dnsProvider
                        )
                        recovered = check.isInternetReachable
                        recoveredLatencyMs = check.ipLatencyMs
                    }
                } else {
                    val resetOk = privilegeManager.resetMobileDataSession()
                    if (resetOk) {
                        delay(2500L)
                        val check = diagnosticsEngine.runWatchdogProbe(
                            signalMonitor.wifi.value,
                            settings.dnsProvider
                        )
                        recovered = check.isInternetReachable
                        recoveredLatencyMs = check.ipLatencyMs
                    }

                    if (!recovered && allowAirplaneFallback && !privilegeManager.isInVoiceCall()) {
                        val radioOk = privilegeManager.cycleAirplaneRadio()
                        if (radioOk) {
                            delay(3800L)
                            signalMonitor.pollOnce()
                            val check = diagnosticsEngine.runWatchdogProbe(
                                signalMonitor.wifi.value,
                                settings.dnsProvider
                            )
                            recovered = check.isInternetReachable
                            recoveredLatencyMs = check.ipLatencyMs
                        }
                    }
                }

                refreshTelemetryAndProbeOnce()
                val finalMs = recoveredLatencyMs ?: _probeSnapshot.value.ipLatencyMs

                if (recovered) {
                    diagnosticsEngine.resetFailureCounter()
                    incrementAutoReconnectCounter()
                    settingsRepository.appendEvent(
                        EventSeverity.INFO,
                        if (isRo) {
                            "Conexiune restabilită cu succes (${finalMs ?: "--"} ms)."
                        } else {
                            "Connection successfully restored (${finalMs ?: "--"} ms)."
                        }
                    )
                } else {
                    reportNetworkBadToAndroid()
                    settingsRepository.appendEvent(
                        EventSeverity.ALERT,
                        if (isRo) {
                            "S-a executat resetarea radio, dar semnalul din zonă este încă indisponibil."
                        } else {
                            "Radio reset executed, but network coverage is still unavailable."
                        }
                    )
                }
                return recovered
            } else {
                reportNetworkBadToAndroid()
                delay(1500L)
                refreshTelemetryAndProbeOnce()
                val ok = _probeSnapshot.value.isInternetReachable
                if (ok) {
                    settingsRepository.appendEvent(
                        EventSeverity.INFO,
                        if (isRo) {
                            "Rețea revalidată prin sistemul Android (${_probeSnapshot.value.ipLatencyMs ?: "--"} ms)."
                        } else {
                            "Network revalidated via Android OS (${_probeSnapshot.value.ipLatencyMs ?: "--"} ms)."
                        }
                    )
                } else {
                    settingsRepository.appendEvent(
                        EventSeverity.ALERT,
                        if (isRo) {
                            "Conexiune blocată în Mod Normal - activați Shizuku pentru resetare automată Date Mobile."
                        } else {
                            "Connection stalled in Normal Mode - enable Shizuku for automatic Mobile Data reset."
                        }
                    )
                }
                return ok
            }
        } finally {
            _isBusyReconnecting.value = false
        }
    }

    /**
     * Secventa dubla din codul sursa Google AOSP (ConnectivityManager.java):
     * ConnectivityService ignora apelul daca starea raportata coincide deja cu lastValidated.
     * Trimiterea secventei (true) urmata de (false) forteaza garantat CMD_FORCE_REEVALUATION
     * in NetworkMonitor si declanseaza DataStallRecoveryManager din Android 14-16 chiar si in Mod Normal.
     */
    fun reportNetworkBadToAndroid() {
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val targets = LinkedHashSet<Network>(2)
            cm.activeNetwork?.let { targets.add(it) }
            cellularRadioAnchor.getPinnedNetwork()?.let { targets.add(it) }
            for (net in targets) {
                runCatching {
                    cm.reportNetworkConnectivity(net, true)
                    cm.reportNetworkConnectivity(net, false)
                }
            }
        }
    }

    fun runDiagnosticAsync() {
        if (!_isRunningDiagnostic.compareAndSet(false, true)) return
        scope.launch {
            try {
                signalMonitor.pollOnce()
                val settings = settingsRepository.getSnapshot()
                val report = diagnosticsEngine.runFourSegmentDiagnostic(
                    signalMonitor.cellular.value,
                    signalMonitor.wifi.value,
                    settings.dnsProvider
                )
                _diagnosticReport.value = report
                settingsRepository.appendEvent(
                    if (report.overallHealthy) EventSeverity.INFO else EventSeverity.ALERT,
                    ro.netstabil.app.i18n.trNow(
                        "Diagnostic rulat: ${report.summaryRo}",
                        "Diagnostic completed: ${report.summaryRo}"
                    )
                )
            } finally {
                _isRunningDiagnostic.value = false
            }
        }
    }

    suspend fun selectAndApplyDns(provider: DnsProvider): Boolean {
        DnsTurboEngine.clearCache()
        settingsRepository.setDnsProvider(provider)
        privilegeManager.refreshState()
        val isRo = ro.netstabil.app.i18n.I18n.currentIsRo

        if (provider == DnsProvider.CARRIER_DEFAULT) {
            LocalDnsVpnService.stop(appContext)
            privilegeManager.applyNativePrivateDns(DnsProvider.CARRIER_DEFAULT)
            settingsRepository.appendEvent(
                EventSeverity.INFO,
                if (isRo) "DNS revenit la serverul implicit al operatorului."
                else "DNS reverted to carrier default server."
            )
            return true
        }

        val nativeApplied = privilegeManager.applyNativePrivateDns(provider)
        if (nativeApplied) {
            LocalDnsVpnService.stop(appContext)
            settingsRepository.appendEvent(
                EventSeverity.ACTION,
                if (isRo) {
                    "Private DNS nativ activat: ${provider.title(true)} (${provider.dotHostname})."
                } else {
                    "Native Private DNS enabled: ${provider.title(false)} (${provider.dotHostname})."
                }
            )
            return true
        }
        return false
    }
}
