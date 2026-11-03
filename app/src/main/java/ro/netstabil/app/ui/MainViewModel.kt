package ro.netstabil.app.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ro.netstabil.app.NetStabilApp
import ro.netstabil.app.data.AppLanguage
import ro.netstabil.app.data.DnsProvider
import ro.netstabil.app.data.EventSeverity
import ro.netstabil.app.data.PreferredRatMode
import ro.netstabil.app.data.StabilizerProfile
import ro.netstabil.app.data.StabilizerSettings
import ro.netstabil.app.i18n.I18n
import ro.netstabil.app.i18n.trNow
import ro.netstabil.app.privileged.PrivilegeTier
import ro.netstabil.app.system.DeviceOptimizationHelper
import ro.netstabil.app.system.DeviceProfileInfo
import ro.netstabil.app.tile.NetStabilTileService
import ro.netstabil.app.widget.NetStabilWidgetProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NetStabilApp
    val controller = app.controller

    val settings: StateFlow<StabilizerSettings> = controller.settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StabilizerSettings())

    val isServiceRunning = controller.isServiceRunning
    val isBusyReconnecting = controller.isBusyReconnecting
    val healthScore = controller.healthScore
    val headline = controller.headline
    val cellular = controller.signalMonitor.cellular
    val wifi = controller.signalMonitor.wifi
    val signalHistoryDbm = controller.signalMonitor.signalHistoryDbm
    val probe = controller.probeSnapshot
    val privilegeStatus = controller.privilegeManager.status
    val modemCapability = controller.privilegeManager.modemCapability
    val deepTuningStatus = controller.privilegeManager.deepTuningStatus
    val stats = controller.sessionStats
    val events = controller.settingsRepository.events
    val diagnosticReport = controller.diagnosticReport
    val isRunningDiagnostic = controller.isRunningDiagnostic
    val isLocalDnsTunnelActive = controller.isLocalDnsTunnelActive

    private val _isModemCommandBusy = MutableStateFlow(false)
    val isModemCommandBusy: StateFlow<Boolean> = _isModemCommandBusy.asStateFlow()

    private val _deviceInfo = MutableStateFlow(DeviceOptimizationHelper.inspectDevice(app))
    val deviceInfo: StateFlow<DeviceProfileInfo> = _deviceInfo.asStateFlow()

    private val _hasRadioPermissions = MutableStateFlow(checkRadioPermissions())
    val hasRadioPermissions: StateFlow<Boolean> = _hasRadioPermissions.asStateFlow()

    private val _systemPrivateDnsLabel = MutableStateFlow(
        controller.privilegeManager.getCurrentSystemPrivateDns()
    )
    val systemPrivateDnsLabel: StateFlow<String> = _systemPrivateDnsLabel.asStateFlow()

    private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val uiMessages: SharedFlow<String> = _uiMessages.asSharedFlow()

    private val _requestLocalDnsVpnConsent = MutableSharedFlow<DnsProvider>(extraBufferCapacity = 1)
    val requestLocalDnsVpnConsent: SharedFlow<DnsProvider> = _requestLocalDnsVpnConsent.asSharedFlow()

    fun onAppResumed() {
        _deviceInfo.value = DeviceOptimizationHelper.inspectDevice(app)
        _hasRadioPermissions.value = checkRadioPermissions()
        _systemPrivateDnsLabel.value = controller.privilegeManager.getCurrentSystemPrivateDns()
        controller.startForegroundUiSampling()
        viewModelScope.launch {
            controller.privilegeManager.refreshState()
            controller.refreshTelemetryAndProbeOnce()
            val cell = cellular.value
            val slot0 = (cell.simSlotIndex - 1).coerceAtLeast(0)
            if (privilegeStatus.value.activeTier != PrivilegeTier.NORMAL) {
                controller.privilegeManager.probeModemHardware(cell.subscriptionId, slot0)
            }
            val snap = controller.settingsRepository.getSnapshot()
            controller.privilegeManager.syncDeepSystemOptimizations(
                settings = snap,
                subId = cell.subscriptionId,
                simSlotZeroBased = slot0
            )
        }
    }

    fun onAppPaused() {
        controller.stopForegroundUiSampling()
    }

    fun refreshPermissionsFlag() {
        _hasRadioPermissions.value = checkRadioPermissions()
        viewModelScope.launch {
            controller.refreshTelemetryAndProbeOnce()
        }
    }

    private fun checkRadioPermissions(): Boolean {
        return controller.signalMonitor.hasLocationPermission() &&
            controller.signalMonitor.hasPhonePermission()
    }

    fun toggleStabilizer() {
        if (isServiceRunning.value) {
            controller.stopStabilizer()
            emitMessage(trNow("Stabilizarea a fost oprită.", "Signal stabilization stopped."))
        } else {
            controller.startStabilizerFromUi()
            emitMessage(trNow("NetStabil a pornit în fundal.", "NetStabil started in the background."))
        }
    }

    fun forceReconnectNow() {
        viewModelScope.launch {
            val isRo = I18n.currentIsRo
            if (controller.privilegeManager.isSystemAirplaneModeEnabled() && !wifi.value.isConnected) {
                emitMessage(
                    if (isRo) "Modul Avion este activ - dezactivați întâi Modul Avion."
                    else "Airplane Mode is active - disable Airplane Mode first."
                )
                return@launch
            }
            val tier = privilegeStatus.value.activeTier
            val reason = if (isRo) "Manual din ecranul Acasă" else "Manual from Home screen"
            if (tier == PrivilegeTier.NORMAL) {
                val ok = controller.executeActiveReconnect(reason, false)
                if (ok) {
                    emitMessage(
                        if (isRo) "Conexiune revalidată și restabilită prin sistemul Android."
                        else "Connection revalidated and restored via Android OS."
                    )
                } else {
                    DeviceOptimizationHelper.openInternetConnectivityPanel(app)
                    emitMessage(
                        if (isRo) "Conexiunea încă nu răspunde - comutați Datele Mobile din panoul deschis."
                        else "Connection still unresponsive - toggle Mobile Data in the opened panel."
                    )
                }
            } else {
                val ok = controller.executeActiveReconnect(reason, true)
                emitMessage(
                    if (ok) {
                        if (isRo) "Conexiunea a fost resetată și restabilită."
                        else "Connection reset and restored."
                    } else {
                        if (isRo) "Resetarea radio s-a executat, se așteaptă răspunsul rețelei."
                        else "Radio reset executed, waiting for network response."
                    }
                )
            }
        }
    }

    fun selectProfile(profile: StabilizerProfile) {
        viewModelScope.launch {
            controller.settingsRepository.setProfile(profile)
            val isRo = I18n.currentIsRo
            emitMessage(
                if (isRo) "Profil selectat: ${profile.title(true)}"
                else "Selected profile: ${profile.title(false)}"
            )
        }
    }

    fun toggleTheme() {
        viewModelScope.launch {
            val next = !settings.value.darkTheme
            controller.settingsRepository.setDarkTheme(next)
        }
    }

    fun cycleLanguage() {
        viewModelScope.launch {
            val systemRo = I18n.isSystemLocaleRomanian()
            val next = if (systemRo) {
                // Pe un telefon in romana (RO*), primul click trece direct in EN fara pas mort!
                when (settings.value.appLanguage) {
                    AppLanguage.AUTO -> AppLanguage.EN
                    AppLanguage.EN -> AppLanguage.RO
                    AppLanguage.RO -> AppLanguage.AUTO
                }
            } else {
                // Pe un telefon international (EN*), primul click trece direct in RO fara pas mort!
                when (settings.value.appLanguage) {
                    AppLanguage.AUTO -> AppLanguage.RO
                    AppLanguage.RO -> AppLanguage.EN
                    AppLanguage.EN -> AppLanguage.AUTO
                }
            }
            controller.settingsRepository.setAppLanguage(next)
            val isRo = I18n.resolveIsRomanian(next)
            _systemPrivateDnsLabel.value = controller.privilegeManager.getCurrentSystemPrivateDns()
            controller.privilegeManager.refreshDeepTuningReadbackOnly()
            controller.signalMonitor.pollOnce()
            ro.netstabil.app.service.StabilizerForegroundService.refreshNotificationIfRunning(app)
            NetStabilWidgetProvider.updateAllWidgets(app)
            NetStabilTileService.requestSyncTile(app)
            val feedback = when (next) {
                AppLanguage.RO -> "Limbă setată: Română (RO)"
                AppLanguage.EN -> "Language set: English (EN)"
                AppLanguage.AUTO -> if (isRo) {
                    "Limbă: Automată după telefon (Română)"
                } else {
                    "Language: System Auto (English)"
                }
            }
            emitMessage(feedback)
        }
    }

    fun selectDnsProvider(provider: DnsProvider) {
        viewModelScope.launch {
            val nativeApplied = controller.selectAndApplyDns(provider)
            val isRo = I18n.currentIsRo
            _systemPrivateDnsLabel.value = controller.privilegeManager.getCurrentSystemPrivateDns()
            if (nativeApplied) {
                val msg = if (provider == DnsProvider.CARRIER_DEFAULT) {
                    if (isRo) "DNS revenit la cel implicit al operatorului."
                    else "DNS reverted to carrier default."
                } else {
                    if (isRo) "Private DNS nativ setat pe ${provider.title(true)} (fără VPN)."
                    else "Native Private DNS set to ${provider.title(false)} (no VPN)."
                }
                emitMessage(msg)
            } else {
                _requestLocalDnsVpnConsent.tryEmit(provider)
            }
        }
    }

    fun setKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            controller.settingsRepository.setKeepAliveEnabled(enabled)
            if (!enabled) {
                controller.hardwareKeepaliveOffload.release()
            } else if (isServiceRunning.value) {
                controller.hardwareKeepaliveOffload.sync(
                    enabled = true,
                    isWifiConnected = wifi.value.isConnected,
                    preferredCellularNetwork = controller.cellularRadioAnchor.getPinnedNetwork()
                )
            }
        }
    }

    fun setPinCellularRadio(enabled: Boolean) {
        viewModelScope.launch {
            controller.settingsRepository.setPinCellularRadioEnabled(enabled)
            if (isServiceRunning.value) {
                if (enabled) {
                    controller.cellularRadioAnchor.acquire()
                } else {
                    controller.cellularRadioAnchor.release()
                }
            }
        }
    }

    fun setRrcTurbo(enabled: Boolean) {
        viewModelScope.launch { controller.settingsRepository.setRrcTurboEnabled(enabled) }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { controller.settingsRepository.setAutoReconnectEnabled(enabled) }
    }

    fun setAnti2gTrap(enabled: Boolean) {
        viewModelScope.launch { controller.settingsRepository.setAnti2gTrapEnabled(enabled) }
    }

    fun setSmartWifi(enabled: Boolean) {
        viewModelScope.launch { controller.settingsRepository.setSmartWifiCutoffEnabled(enabled) }
    }

    fun setWifiCutoffDbm(dbm: Int) {
        viewModelScope.launch {
            controller.settingsRepository.setWifiCutoffDbm(dbm)
            syncDeepNow()
        }
    }

    fun setCarrierConfigBoost(enabled: Boolean) {
        viewModelScope.launch {
            controller.settingsRepository.setCarrierConfigBoostEnabled(enabled)
            syncDeepNow()
        }
    }

    fun setFastKernelDataStall(enabled: Boolean) {
        viewModelScope.launch {
            controller.settingsRepository.setFastKernelDataStallEnabled(enabled)
            syncDeepNow()
        }
    }

    fun setConnectivityRfBoost(enabled: Boolean) {
        viewModelScope.launch {
            controller.settingsRepository.setConnectivityRfBoostEnabled(enabled)
            syncDeepNow()
        }
    }

    fun setHyperOsSleepShield(enabled: Boolean) {
        viewModelScope.launch {
            controller.settingsRepository.setHyperOsSleepShieldEnabled(enabled)
            syncDeepNow()
        }
    }

    fun setDualSimAutoSwitch(enabled: Boolean) {
        viewModelScope.launch {
            controller.settingsRepository.setDualSimAutoSwitchEnabled(enabled)
            syncDeepNow()
        }
    }

    fun setRestrictXiaomiPowerKeeper(enabled: Boolean) {
        viewModelScope.launch {
            controller.settingsRepository.setRestrictXiaomiPowerKeeperBg(enabled)
            syncDeepNow()
        }
    }

    fun reapplyAllDeepOptimizationsNow() {
        viewModelScope.launch {
            val snap = controller.settingsRepository.getSnapshot()
            val cell = cellular.value
            val slot0 = (cell.simSlotIndex - 1).coerceAtLeast(0)
            val status = controller.privilegeManager.syncDeepSystemOptimizations(
                settings = snap,
                subId = cell.subscriptionId,
                simSlotZeroBased = slot0,
                forceReapply = true
            )
            val msg = if (I18n.currentIsRo) {
                "Optimizări avansate sincronizate: ${status.lastSummaryRo}."
            } else {
                "Advanced optimizations synced: ${status.lastSummaryRo}."
            }
            controller.settingsRepository.appendEvent(EventSeverity.ACTION, msg)
            emitMessage(msg)
        }
    }

    fun restoreFactoryDeepSystemDefaults() {
        viewModelScope.launch {
            controller.settingsRepository.resetDeepTweaksFlagsToFactory()
            val cell = cellular.value
            val slot0 = (cell.simSlotIndex - 1).coerceAtLeast(0)
            val res = controller.privilegeManager.restoreFactorySystemDefaults(
                subId = cell.subscriptionId,
                simSlotZeroBased = slot0
            )
            controller.settingsRepository.appendEvent(EventSeverity.ACTION, res.messageRo)
            emitMessage(res.messageRo)
        }
    }

    private suspend fun syncDeepNow() {
        val snap = controller.settingsRepository.getSnapshot()
        val cell = cellular.value
        val slot0 = (cell.simSlotIndex - 1).coerceAtLeast(0)
        controller.privilegeManager.syncDeepSystemOptimizations(
            settings = snap,
            subId = cell.subscriptionId,
            simSlotZeroBased = slot0
        )
    }

    fun setStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { controller.settingsRepository.setStartOnBoot(enabled) }
    }

    fun runFullDiagnostic() {
        controller.runDiagnosticAsync()
    }

    // --- Comenzi Directe Hardware Modem (Shizuku / Root) ---

    fun applyDirectRatMode(mode: PreferredRatMode) {
        if (!_isModemCommandBusy.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val cell = cellular.value
                val slot0 = (cell.simSlotIndex - 1).coerceAtLeast(0)
                val res = controller.privilegeManager.applyDirectRatMode(cell.subscriptionId, slot0, mode)
                controller.settingsRepository.appendEvent(
                    if (res.success) EventSeverity.ACTION else EventSeverity.ALERT,
                    res.messageRo
                )
                emitMessage(res.messageRo)
                controller.refreshTelemetryAndProbeOnce()
            } finally {
                _isModemCommandBusy.value = false
            }
        }
    }

    fun applyDirectBandLock(selectedBands: List<Int>) {
        if (!_isModemCommandBusy.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val cell = cellular.value
                val slot0 = (cell.simSlotIndex - 1).coerceAtLeast(0)
                val res = controller.privilegeManager.applyDirectLteBandLock(
                    cell.subscriptionId,
                    slot0,
                    selectedBands
                )
                controller.settingsRepository.appendEvent(
                    if (res.success) EventSeverity.ACTION else EventSeverity.ALERT,
                    res.messageRo
                )
                emitMessage(res.messageRo)
                controller.refreshTelemetryAndProbeOnce()
            } finally {
                _isModemCommandBusy.value = false
            }
        }
    }

    fun applyDirectServingCellLock() {
        if (!_isModemCommandBusy.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val cell = cellular.value
                val slot0 = (cell.simSlotIndex - 1).coerceAtLeast(0)
                val res = controller.privilegeManager.applyDirectCellLock(
                    subId = cell.subscriptionId,
                    simSlotZeroBased = slot0,
                    earfcn = cell.earfcn,
                    pci = cell.pci ?: -1
                )
                controller.settingsRepository.appendEvent(
                    if (res.success) EventSeverity.ACTION else EventSeverity.ALERT,
                    res.messageRo
                )
                emitMessage(res.messageRo)
                controller.refreshTelemetryAndProbeOnce()
            } finally {
                _isModemCommandBusy.value = false
            }
        }
    }

    fun unlockAllModemLocks() {
        if (!_isModemCommandBusy.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val cell = cellular.value
                val slot0 = (cell.simSlotIndex - 1).coerceAtLeast(0)
                val res = controller.privilegeManager.unlockAllModemHardware(cell.subscriptionId, slot0)
                controller.settingsRepository.appendEvent(
                    if (res.success) EventSeverity.ACTION else EventSeverity.ALERT,
                    res.messageRo
                )
                emitMessage(res.messageRo)
                controller.refreshTelemetryAndProbeOnce()
            } finally {
                _isModemCommandBusy.value = false
            }
        }
    }

    fun openHiddenRadioInfo() {
        viewModelScope.launch {
            val ok = DeviceOptimizationHelper.openHiddenRadioInfo(app, controller.privilegeManager)
            if (!ok) {
                emitMessage(
                    trNow(
                        "Sistemul a blocat meniul RadioInfo. Porniți Shizuku pentru deblocare.",
                        "OS blocked the RadioInfo menu. Start Shizuku to unlock it."
                    )
                )
            }
        }
    }

    fun openMtkEngineerMode() {
        viewModelScope.launch {
            val ok = DeviceOptimizationHelper.openMtkEngineerMode(app, controller.privilegeManager)
            if (!ok) {
                emitMessage(
                    trNow(
                        "Meniul Inginer MediaTek este restricționat de HyperOS fără Root/Shizuku.",
                        "MediaTek Engineer Mode is restricted by HyperOS without Root/Shizuku."
                    )
                )
            }
        }
    }

    fun openMtkBandSelect() {
        viewModelScope.launch {
            val ok = DeviceOptimizationHelper.openMtkBandSelect(app, controller.privilegeManager)
            if (!ok) {
                emitMessage(
                    trNow(
                        "Blocarea benzii necesită Shizuku pe HyperOS (vezi ghidul din Jurnal).",
                        "LTE band selection requires Shizuku on HyperOS (see guide in Log)."
                    )
                )
            }
        }
    }

    fun openApnSettings() {
        val ok = DeviceOptimizationHelper.openApnSettings(app)
        if (!ok) {
            emitMessage(
                trNow(
                    "Deschideți Setări -> Rețele mobile -> Nume puncte de acces (APN).",
                    "Open Settings -> Mobile networks -> Access Point Names (APN)."
                )
            )
        }
    }

    fun copyAndLaunchSecretCode(code: String, labelRo: String) {
        val ok = DeviceOptimizationHelper.copySecretCodeAndOpenDialer(app, code)
        if (ok) {
            emitMessage(
                trNow(
                    "Codul $code ($labelRo) a fost copiat - lipiți-l în tastatura telefonului.",
                    "Code $code ($labelRo) copied - paste it into the Phone dialer."
                )
            )
        } else {
            emitMessage(
                trNow(
                    "Tastați manual $code în aplicația Telefon.",
                    "Type $code manually in the Phone app."
                )
            )
        }
    }

    fun authorizeShizuku() {
        val requested = controller.privilegeManager.requestShizukuPermission()
        if (!requested) {
            emitMessage(
                trNow(
                    "Serviciul Shizuku nu rulează încă. Porniți-l întâi din aplicația Shizuku.",
                    "Shizuku service is not running yet. Start it inside the Shizuku app first."
                )
            )
        }
    }

    fun refreshSystemStatus() {
        viewModelScope.launch {
            controller.privilegeManager.refreshState(forceRootReprobe = true)
            _deviceInfo.value = DeviceOptimizationHelper.inspectDevice(app)
            _systemPrivateDnsLabel.value = controller.privilegeManager.getCurrentSystemPrivateDns()
            val cell = cellular.value
            val slot0 = (cell.simSlotIndex - 1).coerceAtLeast(0)
            if (privilegeStatus.value.activeTier != PrivilegeTier.NORMAL) {
                controller.privilegeManager.probeModemHardware(cell.subscriptionId, slot0)
            }
            val snap = controller.settingsRepository.getSnapshot()
            controller.privilegeManager.syncDeepSystemOptimizations(
                settings = snap,
                subId = cell.subscriptionId,
                simSlotZeroBased = slot0,
                forceReapply = true
            )
            emitMessage(
                trNow(
                    "Starea sistemului, a modemului și optimizările de nucleu au fost reverificate.",
                    "System status, modem bridge, and kernel optimizations have been re-verified."
                )
            )
        }
    }

    fun copyLogsToClipboard() {
        val list = events.value
        if (list.isEmpty()) return
        val isRo = I18n.currentIsRo
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val text = list.joinToString("\n") { ev ->
            "[${fmt.format(Date(ev.timestampMs))}] [${ev.severity.tag(isRo)}] ${ev.messageRo}"
        }
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("NetStabil Log", text))
        emitMessage(trNow("Jurnalul a fost copiat în clipboard.", "Event log copied to clipboard."))
    }

    fun clearLogs() {
        controller.settingsRepository.clearEvents()
        emitMessage(trNow("Jurnalul a fost șters.", "Event log cleared."))
    }

    fun emitMessage(msg: String) {
        _uiMessages.tryEmit(msg)
    }
}
