package ro.netstabil.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.netstabil.app.MainActivity
import ro.netstabil.app.NetStabilApp
import ro.netstabil.app.R
import ro.netstabil.app.data.CellularTelemetry
import ro.netstabil.app.data.DnsProvider
import ro.netstabil.app.data.EventSeverity
import ro.netstabil.app.data.StabilizerProfile
import ro.netstabil.app.data.StabilizerSettings
import ro.netstabil.app.privileged.PrivilegeTier

class StabilizerForegroundService : LifecycleService() {

    private var loopJob: Job? = null
    private var rrcTurboJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastWakeLockRenewElapsedMs: Long = 0L

    private var lastAutoRecoveryTimestampMs: Long = 0L
    private var consecutive2gReadings: Int = 0
    private var consecutiveWeakWifiReadings: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val app = application as NetStabilApp
        val controller = app.controller

        when (intent?.action) {
            ACTION_STOP -> {
                lifecycleScope.launch {
                    controller.settingsRepository.setStabilizerWanted(false)
                    controller.settingsRepository.appendEvent(
                        EventSeverity.INFO,
                        ro.netstabil.app.i18n.trNow(
                            "Stabilizarea a fost oprită din notificare.",
                            "Signal stabilization stopped from notification."
                        )
                    )
                }
                stopSelfClean()
                return START_NOT_STICKY
            }
            ACTION_FORCE_RECONNECT -> {
                if (controller.isServiceRunning.value) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        controller.executeActiveReconnect(
                            reasonRo = ro.netstabil.app.i18n.trNow(
                                "Comandă rapidă din notificare",
                                "Quick notification action"
                            ),
                            allowAirplaneFallback = true
                        )
                        updateOngoingNotification()
                    }
                    return START_STICKY
                }
            }
            ACTION_REFRESH_NOTIFICATION -> {
                if (controller.isServiceRunning.value) {
                    createNotificationChannels()
                    updateOngoingNotification()
                    return START_STICKY
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val startedFromUi = intent?.getBooleanExtra(EXTRA_FROM_UI, false) ?: false
        val initialNotif = buildOngoingNotification(
            title = ro.netstabil.app.i18n.trNow("NetStabil activ", "NetStabil active"),
            body = ro.netstabil.app.i18n.trNow(
                "Inițializare monitorizare semnal și keepalive...",
                "Initializing signal monitor and keepalive..."
            )
        )

        val foregroundStartedOk = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val fgsType = computeSafeForegroundServiceType(startedFromUi)
                try {
                    startForeground(NOTIF_ID_ONGOING, initialNotif, fgsType)
                } catch (_: SecurityException) {
                    startForeground(
                        NOTIF_ID_ONGOING,
                        initialNotif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            } else {
                startForeground(NOTIF_ID_ONGOING, initialNotif)
            }
            true
        } catch (_: Exception) {
            false
        }

        if (!foregroundStartedOk) {
            controller.settingsRepository.appendEvent(
                EventSeverity.ALERT,
                ro.netstabil.app.i18n.trNow(
                    "Android ${Build.VERSION.RELEASE} a cerut confirmare după restart (activați Scoate Restricția Baterie pentru auto-start direct).",
                    "Android ${Build.VERSION.RELEASE} requested confirmation after reboot (enable Unrestricted Battery for direct auto-start)."
                )
            )
            postBootResumeNotification(this)
            stopSelf()
            return START_NOT_STICKY
        }

        lifecycleScope.launch {
            controller.settingsRepository.setStabilizerWanted(true)
        }
        controller.setServiceRunningState(true)
        controller.osDiagnosticsMonitor.start { stallReason ->
            controller.onKernelDataStallDetected(stallReason)
        }
        if (loopJob?.isActive != true) {
            startStabilizerLoop()
        }
        if (rrcTurboJob?.isActive != true) {
            startRrcTurboTicker()
        }
        return START_STICKY
    }

    private fun computeSafeForegroundServiceType(startedFromUi: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return 0
        }
        val hasFineOrCoarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val hasBackgroundLoc = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return if (hasFineOrCoarse && (startedFromUi || hasBackgroundLoc)) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
    }

    private fun startStabilizerLoop() {
        val app = application as NetStabilApp
        val controller = app.controller
        val settingsRepo = controller.settingsRepository
        val privMgr = controller.privilegeManager
        val sigMon = controller.signalMonitor
        val diag = controller.diagnosticsEngine

        loopJob = lifecycleScope.launch(Dispatchers.IO) {
            privMgr.refreshState()
            val startSettings = settingsRepo.getSnapshot()
            syncPowerLocks(startSettings.profile.holdPartialWakeLock)

            if (privMgr.status.value.isRootAvailable) {
                privMgr.applyRootKernelTcpTuning()
            }
            if (privMgr.status.value.activeTier != PrivilegeTier.NORMAL) {
                privMgr.disableWifiScanThrottling()
            }

            // Sincronizam mai intai CarrierConfig si nucleul sistemului INAINTE de ancorarea radio,
            // ca modemul sa nu primeasca simultan reincarcare profil SIM si cerere requestNetwork.
            sigMon.pollOnce()
            val initCell = sigMon.cellular.value
            privMgr.syncDeepSystemOptimizations(
                settings = startSettings,
                subId = initCell.subscriptionId,
                simSlotZeroBased = (initCell.simSlotIndex - 1).coerceAtLeast(0)
            )
            syncCellularAnchor(startSettings.pinCellularRadioEnabled)

            val startIsRo = ro.netstabil.app.i18n.I18n.currentIsRo
            settingsRepo.appendEvent(
                EventSeverity.INFO,
                if (startIsRo) {
                    "Motor pornit în profil ${startSettings.profile.title(true)} (${privMgr.status.value.activeTier.short(true)})."
                } else {
                    "Engine started in ${startSettings.profile.title(false)} profile (${privMgr.status.value.activeTier.short(false)})."
                }
            )

            var lastKeepAliveSentMs = 0L

            while (isActive) {
                var sleepMs = 10_000L
                try {
                    val settings = settingsRepo.getSnapshot()
                    val isRo = ro.netstabil.app.i18n.I18n.currentIsRo
                    sleepMs = (settings.profile.watchdogIntervalSec * 1000L).coerceIn(5000L, 30000L)
                    val airplaneOn = privMgr.isSystemAirplaneModeEnabled()
                    syncPowerLocks(settings.profile.holdPartialWakeLock && !airplaneOn)
                    syncCellularAnchor(settings.pinCellularRadioEnabled && !airplaneOn)
                    privMgr.refreshState()

                    // Auto-promovare DNS: daca utilizatorul activeaza Shizuku/Root ulterior,
                    // trece automat de pe tunelul VPN local pe Private DNS nativ Android (fara VPN)
                    if (controller.isLocalDnsTunnelActive.value &&
                        settings.dnsProvider != DnsProvider.CARRIER_DEFAULT &&
                        privMgr.status.value.activeTier != PrivilegeTier.NORMAL
                    ) {
                        if (privMgr.applyNativePrivateDns(settings.dnsProvider)) {
                            LocalDnsVpnService.stop(this@StabilizerForegroundService)
                            settingsRepo.appendEvent(
                                EventSeverity.INFO,
                                if (isRo) {
                                    "DNS promovat automat la Private DNS nativ (${settings.dnsProvider.title(true)})."
                                } else {
                                    "DNS auto-promoted to native Private DNS (${settings.dnsProvider.title(false)})."
                                }
                            )
                        }
                    }

                    // 1. Colectare semnal proaspat din modem + verificare dubla Watchdog
                    controller.refreshTelemetryAndProbeOnce()
                    val cell = sigMon.cellular.value
                    val wifi = sigMon.wifi.value
                    val probe = controller.probeSnapshot.value
                    val now = System.currentTimeMillis()

                    // 1B. Sincronizare cu deduplicare pentru cele 5 mecanisme avansate (CarrierConfig / DSRM / RF / HyperOS / Dual-SIM)
                    privMgr.syncDeepSystemOptimizations(
                        settings = settings,
                        subId = cell.subscriptionId,
                        simSlotZeroBased = (cell.simSlotIndex - 1).coerceAtLeast(0)
                    )

                    // Daca utilizatorul a activat voluntar Modul Avion (fara Wi-Fi) sau a oprit manual Datele Mobile (fara Wi-Fi),
                    // respectam strict decizia utilizatorului si nu rulam reconectari automate sau pulsuri.
                    val userDisabledAllRadios =
                        (airplaneOn && !wifi.isConnected) ||
                            (!wifi.isConnected && !privMgr.isMobileDataEnabledByUser())

                    if (userDisabledAllRadios) {
                        controller.hardwareKeepaliveOffload.release()
                        diag.resetFailureCounter()
                        consecutive2gReadings = 0
                        consecutiveWeakWifiReadings = 0
                        updateOngoingNotification()
                    } else {
                        // 1C. Keepalive Hardware Offload direct in cipul baseband al modemului (0% CPU, Android CDD 7.4.2.6)
                        controller.hardwareKeepaliveOffload.sync(
                            enabled = settings.keepAliveEnabled,
                            isWifiConnected = wifi.isConnected,
                            preferredCellularNetwork = controller.cellularRadioAnchor.getPinnedNetwork()
                        )

                        // 2. Keepalive Clasic (cand Mod Turbo RRC 3.2s nu preia deja traficul pe 4G)
                        val rrcTurboHandling4g = shouldRunRrcTurbo(settings, cell, wifi.isConnected)
                        val keepAliveEveryMs = settings.profile.keepAliveIntervalSec * 1000L
                        if (settings.keepAliveEnabled &&
                            !rrcTurboHandling4g &&
                            (now - lastKeepAliveSentMs) >= keepAliveEveryMs
                        ) {
                            val sent = diag.sendKeepAliveMicroPulse(wifi.isConnected)
                            lastKeepAliveSentMs = now
                            if (sent) {
                                controller.incrementKeepAliveCounter()
                            }
                        }

                        // 3. Smart Wi-Fi Cut-off (nu intrerupe Wi-Fi pe Portal Captiv cu semnal bun)
                        if (settings.smartWifiCutoffEnabled && wifi.isConnected) {
                            val rssi = wifi.rssiDbm ?: -65
                            val wifiUnusable =
                                rssi <= settings.wifiCutoffRssiDbm ||
                                    (!probe.isInternetReachable && !probe.isCaptivePortal)
                            if (wifiUnusable) {
                                consecutiveWeakWifiReadings += 1
                            } else {
                                consecutiveWeakWifiReadings = 0
                            }

                            if (consecutiveWeakWifiReadings >= 2 && canTriggerRecovery(now, 35_000L)) {
                                consecutiveWeakWifiReadings = 0
                                lastAutoRecoveryTimestampMs = now
                                val tier = privMgr.status.value.activeTier
                                if (tier == PrivilegeTier.ROOT || tier == PrivilegeTier.SHIZUKU) {
                                    val switched = privMgr.disableWeakWifiAndSwitchToMobile()
                                    if (switched) {
                                        controller.incrementWeakWifiSwitchCounter()
                                        settingsRepo.appendEvent(
                                            EventSeverity.ACTION,
                                            if (isRo) {
                                                "Wi-Fi slab ($rssi dBm) dezactivat automat -> comutat pe ${cell.operatorName}."
                                            } else {
                                                "Weak Wi-Fi ($rssi dBm) auto-disabled -> switched to ${cell.operatorName}."
                                            }
                                        )
                                    }
                                } else {
                                    controller.reportNetworkBadToAndroid()
                                    postAlertNotification(
                                        if (isRo) "Wi-Fi degradat ($rssi dBm)" else "Degraded Wi-Fi ($rssi dBm)",
                                        if (isRo) {
                                            "Apăsați pentru a comuta rapid pe Date Mobile (${cell.operatorName})."
                                        } else {
                                            "Tap to quickly switch to Mobile Data (${cell.operatorName})."
                                        }
                                    )
                                }
                            }
                        } else {
                            consecutiveWeakWifiReadings = 0
                        }

                        // 4. Protectie Anti-Blocaj in 2G EDGE/GPRS
                        if (settings.anti2gTrapEnabled && !wifi.isConnected && cell.networkGeneration.startsWith("2G")) {
                            if (!privMgr.isInVoiceCall()) {
                                consecutive2gReadings += 1
                            }
                            if (consecutive2gReadings >= 2 && canTriggerRecovery(now, 60_000L)) {
                                consecutive2gReadings = 0
                                lastAutoRecoveryTimestampMs = now
                                val tier = privMgr.status.value.activeTier
                                if (tier == PrivilegeTier.ROOT || tier == PrivilegeTier.SHIZUKU) {
                                    settingsRepo.appendEvent(
                                        EventSeverity.ACTION,
                                        if (isRo) {
                                            "Blocaj pe ${cell.networkGeneration} detectat -> reatașare automată la 4G LTE."
                                        } else {
                                            "2G trap (${cell.networkGeneration}) detected -> auto-reattaching to 4G LTE."
                                        }
                                    )
                                    val ok = privMgr.cycleAirplaneRadio()
                                    if (ok) {
                                        controller.increment2gRecoveryCounter()
                                    }
                                } else {
                                    postAlertNotification(
                                        if (isRo) "Telefon blocat în 2G (${cell.operatorName})" else "Trapped on 2G (${cell.operatorName})",
                                        if (isRo) {
                                            "Activați Shizuku sau apăsați aici pentru reconectare la 4G LTE."
                                        } else {
                                            "Enable Shizuku or tap here to reconnect to 4G LTE."
                                        }
                                    )
                                }
                            }
                        } else {
                            consecutive2gReadings = 0
                        }

                        // 5. Auto-Reconnect la Conexiune Inghetata ("Zombie Data", exclus Portal Captiv)
                        if (settings.autoReconnectEnabled &&
                            !probe.isCaptivePortal &&
                            probe.consecutiveFailures >= settings.profile.failureThreshold &&
                            canTriggerRecovery(now, 40_000L)
                        ) {
                            lastAutoRecoveryTimestampMs = now
                            val allowAirplane = probe.consecutiveFailures >= (settings.profile.failureThreshold + 2)
                            val ok = controller.executeActiveReconnect(
                                reasonRo = if (isRo) {
                                    "${probe.consecutiveFailures} verificări eșuate consecutiv"
                                } else {
                                    "${probe.consecutiveFailures} consecutive failed probes"
                                },
                                allowAirplaneFallback = allowAirplane
                            )
                            if (!ok && privMgr.status.value.activeTier == PrivilegeTier.NORMAL) {
                                postAlertNotification(
                                    if (isRo) "Conexiune de date înghețată" else "Frozen data connection",
                                    if (isRo) {
                                        "Internetul nu răspunde. Apăsați pentru panoul rapid de reconectare."
                                    } else {
                                        "Internet is unresponsive. Tap to open the quick connectivity panel."
                                    }
                                )
                            }
                        }

                        updateOngoingNotification()
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                }
                delay(sleepMs)
            }
        }
    }

    private fun startRrcTurboTicker() {
        val app = application as NetStabilApp
        val controller = app.controller
        val settingsRepo = controller.settingsRepository
        val privMgr = controller.privilegeManager
        val sigMon = controller.signalMonitor
        val diag = controller.diagnosticsEngine

        rrcTurboJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(1600L)
            while (isActive) {
                try {
                    val settings = settingsRepo.getSnapshot()
                    val wifiOn = sigMon.wifi.value.isConnected
                    val cell = sigMon.cellular.value
                    if (!controller.isBusyReconnecting.value &&
                        !privMgr.isSystemAirplaneModeEnabled() &&
                        privMgr.isMobileDataEnabledByUser() &&
                        shouldRunRrcTurbo(settings, cell, wifiOn)
                    ) {
                        val rttMs = diag.sendRrcTurboLowDelayPulse(isWifiConnected = false)
                        if (rttMs != null) {
                            controller.incrementRrcTurboCounter()
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                }
                delay(3200L)
            }
        }
    }

    private fun syncCellularAnchor(pinEnabled: Boolean) {
        val anchor = (application as NetStabilApp).controller.cellularRadioAnchor
        if (pinEnabled) {
            anchor.acquire()
        } else {
            anchor.release()
        }
    }

    private fun canTriggerRecovery(nowMs: Long, minCooldownMs: Long): Boolean {
        return (nowMs - lastAutoRecoveryTimestampMs) >= minCooldownMs
    }

    private fun syncPowerLocks(holdLocks: Boolean) {
        if (holdLocks) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "NetStabil::StabilizerWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            val nowElapsed = SystemClock.elapsedRealtime()
            if (wakeLock?.isHeld != true || (nowElapsed - lastWakeLockRenewElapsedMs) >= 30 * 60 * 1000L) {
                runCatching {
                    wakeLock?.acquire(6 * 60 * 60 * 1000L)
                    lastWakeLockRenewElapsedMs = nowElapsed
                }
            }

            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "NetStabil::LowLatencyWifiLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                runCatching { wifiLock?.acquire() }
            }
        } else {
            releasePowerLocks()
        }
    }

    private fun releasePowerLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun updateOngoingNotification() {
        val app = application as NetStabilApp
        val controller = app.controller
        val isRo = ro.netstabil.app.i18n.I18n.currentIsRo
        val cell = controller.signalMonitor.cellular.value
        val wifi = controller.signalMonitor.wifi.value
        val probe = controller.probeSnapshot.value
        val score = controller.healthScore.value

        val title = "NetStabil (${score}/100) - ${controller.headline.value.text(isRo)}"
        val netDesc = if (wifi.isConnected) {
            "Wi-Fi ${wifi.ssid} | ${wifi.rssiDbm ?: "--"} dBm"
        } else {
            val band = cell.bandInfo?.let { "B${it.bandNumber}" } ?: cell.networkGeneration
            "${cell.operatorName} $band | ${cell.rsrpDbm ?: "--"} dBm"
        }
        val pingDesc = probe.ipLatencyMs?.let { "$it ms" } ?: if (isRo) "fără răspuns" else "no reply"
        val body = "$netDesc | Ping $pingDesc"

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_ONGOING, buildOngoingNotification(title, body))
    }

    private fun buildOngoingNotification(title: String, body: String): Notification {
        val isRo = ro.netstabil.app.i18n.I18n.currentIsRo
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, StabilizerForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val reconnectIntent = PendingIntent.getService(
            this,
            12,
            Intent(this, StabilizerForegroundService::class.java).apply { action = ACTION_FORCE_RECONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, if (isRo) "Reconectează" else "Reconnect", reconnectIntent)
            .addAction(0, if (isRo) "Oprește" else "Stop", stopIntent)
            .build()
    }

    private fun postAlertNotification(title: String, body: String) {
        val panelIntent = PendingIntent.getActivity(
            this,
            20,
            Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(panelIntent)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_ALERT, notif)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ongoing = NotificationChannel(
            CHANNEL_ONGOING,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.alert_channel_desc)
        }
        nm.createNotificationChannel(ongoing)
        nm.createNotificationChannel(alerts)
    }

    private fun stopSelfClean() {
        loopJob?.cancel()
        rrcTurboJob?.cancel()
        val controller = (application as NetStabilApp).controller
        controller.osDiagnosticsMonitor.stop()
        controller.hardwareKeepaliveOffload.release()
        controller.cellularRadioAnchor.release()
        releasePowerLocks()
        controller.setServiceRunningState(false)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        loopJob?.cancel()
        rrcTurboJob?.cancel()
        val controller = (application as NetStabilApp).controller
        controller.osDiagnosticsMonitor.stop()
        controller.hardwareKeepaliveOffload.release()
        controller.cellularRadioAnchor.release()
        releasePowerLocks()
        controller.setServiceRunningState(false)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ONGOING = "netstabil_ongoing"
        private const val CHANNEL_ALERTS = "netstabil_alerts"
        private const val NOTIF_ID_ONGOING = 1001
        private const val NOTIF_ID_ALERT = 1002
        private const val NOTIF_ID_BOOT_RESUME = 1003

        const val ACTION_START = "ro.netstabil.app.action.START"
        const val ACTION_STOP = "ro.netstabil.app.action.STOP"
        const val ACTION_FORCE_RECONNECT = "ro.netstabil.app.action.FORCE_RECONNECT"
        const val ACTION_REFRESH_NOTIFICATION = "ro.netstabil.app.action.REFRESH_NOTIFICATION"
        const val EXTRA_FROM_UI = "extra_from_ui"

        fun shouldRunRrcTurbo(
            settings: StabilizerSettings,
            cell: CellularTelemetry,
            isWifiConnected: Boolean
        ): Boolean {
            if (!settings.keepAliveEnabled || !settings.rrcTurboEnabled || isWifiConnected) {
                return false
            }
            return when (settings.profile) {
                StabilizerProfile.MAX_POWER -> true
                StabilizerProfile.BALANCED -> {
                    (cell.rsrpDbm ?: -95) <= -98 || (cell.sinrDb ?: 10) <= 3
                }
                StabilizerProfile.ECONOMIC -> false
            }
        }

        fun start(context: Context, fromForegroundUi: Boolean = false): Boolean {
            val intent = Intent(context, StabilizerForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FROM_UI, fromForegroundUi)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                postBootResumeNotification(context)
            }.getOrDefault(false)
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, StabilizerForegroundService::class.java))
            }
        }

        fun refreshNotificationIfRunning(context: Context) {
            val app = context.applicationContext as? NetStabilApp ?: return
            if (!app.controller.isServiceRunning.value) return
            runCatching {
                context.startService(
                    Intent(context, StabilizerForegroundService::class.java).apply {
                        action = ACTION_REFRESH_NOTIFICATION
                    }
                )
            }
        }

        fun postBootResumeNotification(context: Context) {
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val alerts = NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.alert_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(alerts)
                val openIntent = PendingIntent.getActivity(
                    context,
                    30,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val isRo = ro.netstabil.app.i18n.I18n.currentIsRo
                val notif = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_stat_signal)
                    .setContentTitle(
                        if (isRo) "NetStabil pregătit după restart"
                        else "NetStabil ready after reboot"
                    )
                    .setContentText(
                        if (isRo) "Apăsați pentru a relua stabilizarea activă a semnalului."
                        else "Tap to resume active signal stabilization."
                    )
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(openIntent)
                    .build()
                nm.notify(NOTIF_ID_BOOT_RESUME, notif)
            }
        }
    }
}
