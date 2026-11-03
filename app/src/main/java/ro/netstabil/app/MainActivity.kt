package ro.netstabil.app

import android.Manifest
import android.app.Activity
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.netstabil.app.data.AppLanguage
import ro.netstabil.app.i18n.I18n
import ro.netstabil.app.i18n.LocalIsRo
import ro.netstabil.app.i18n.tr
import ro.netstabil.app.i18n.trNow
import ro.netstabil.app.privileged.PrivilegeTier
import ro.netstabil.app.service.LocalDnsVpnService
import ro.netstabil.app.system.DeviceOptimizationHelper
import ro.netstabil.app.ui.MainViewModel
import ro.netstabil.app.ui.screens.ControlDnsScreen
import ro.netstabil.app.ui.screens.HomeScreen
import ro.netstabil.app.ui.screens.LogSystemScreen
import ro.netstabil.app.ui.screens.SignalScreen
import ro.netstabil.app.ui.theme.AmberWarn
import ro.netstabil.app.ui.theme.EmeraldPrimary
import ro.netstabil.app.ui.theme.NetStabilTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val isRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
            val isBusyReconnecting by viewModel.isBusyReconnecting.collectAsStateWithLifecycle()
            val healthScore by viewModel.healthScore.collectAsStateWithLifecycle()
            val headline by viewModel.headline.collectAsStateWithLifecycle()
            val cellular by viewModel.cellular.collectAsStateWithLifecycle()
            val wifi by viewModel.wifi.collectAsStateWithLifecycle()
            val signalHistoryDbm by viewModel.signalHistoryDbm.collectAsStateWithLifecycle()
            val probe by viewModel.probe.collectAsStateWithLifecycle()
            val privilegeStatus by viewModel.privilegeStatus.collectAsStateWithLifecycle()
            val modemCapability by viewModel.modemCapability.collectAsStateWithLifecycle()
            val deepTuningStatus by viewModel.deepTuningStatus.collectAsStateWithLifecycle()
            val isModemCommandBusy by viewModel.isModemCommandBusy.collectAsStateWithLifecycle()
            val stats by viewModel.stats.collectAsStateWithLifecycle()
            val events by viewModel.events.collectAsStateWithLifecycle()
            val diagnosticReport by viewModel.diagnosticReport.collectAsStateWithLifecycle()
            val isRunningDiagnostic by viewModel.isRunningDiagnostic.collectAsStateWithLifecycle()
            val isLocalDnsTunnelActive by viewModel.isLocalDnsTunnelActive.collectAsStateWithLifecycle()
            val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
            val hasRadioPermissions by viewModel.hasRadioPermissions.collectAsStateWithLifecycle()
            val systemPrivateDnsLabel by viewModel.systemPrivateDnsLabel.collectAsStateWithLifecycle()

            val configuration = LocalConfiguration.current
            val systemLocale = configuration.locales[0] ?: java.util.Locale.getDefault()
            val isRo = when (settings.appLanguage) {
                AppLanguage.RO -> true
                AppLanguage.EN -> false
                AppLanguage.AUTO -> systemLocale.language.startsWith("ro", ignoreCase = true)
            }
            LaunchedEffect(settings.appLanguage, systemLocale) {
                I18n.updateLanguage(settings.appLanguage)
            }

            var currentTab by rememberSaveable { mutableIntStateOf(0) }
            var autoPromptedPermissions by rememberSaveable { mutableStateOf(false) }
            var manualPermissionTap by remember { mutableStateOf(false) }
            val snackbarHostState = remember { SnackbarHostState() }

            DisposableEffect(settings.darkTheme) {
                val barStyle = if (settings.darkTheme) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    )
                }
                enableEdgeToEdge(
                    statusBarStyle = barStyle,
                    navigationBarStyle = barStyle
                )
                onDispose {}
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                viewModel.refreshPermissionsFlag()
                val anyCoreGranted =
                    results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                if (manualPermissionTap && !anyCoreGranted) {
                    val canStillAsk =
                        shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
                            shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE)
                    if (!canStillAsk) {
                        DeviceOptimizationHelper.openAppDetailsSettings(this@MainActivity)
                        viewModel.emitMessage(
                            trNow(
                                "Activați Locația și Telefonul din Setările aplicației deschise.",
                                "Enable Location and Phone permissions in the opened App Settings."
                            )
                        )
                    }
                }
                manualPermissionTap = false
            }

            val vpnLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { res ->
                if (res.resultCode == Activity.RESULT_OK) {
                    LocalDnsVpnService.start(this@MainActivity)
                    viewModel.emitMessage(
                        trNow(
                            "Tunelul Turbo DNS (Paralel + Cache RAM + DoH/DoT) a fost activat.",
                            "Turbo DNS Tunnel (Parallel + RAM Cache + DoH/DoT) has been enabled."
                        )
                    )
                } else {
                    viewModel.emitMessage(
                        trNow(
                            "Acordul pentru tunelul DNS a fost refuzat.",
                            "DNS tunnel permission was declined."
                        )
                    )
                }
            }

            fun requestCorePermissions(isManualUserTap: Boolean) {
                manualPermissionTap = isManualUserTap
                val perms = buildList {
                    add(Manifest.permission.READ_PHONE_STATE)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
                permissionLauncher.launch(perms)
            }

            LaunchedEffect(Unit) {
                if (!hasRadioPermissions && !autoPromptedPermissions) {
                    autoPromptedPermissions = true
                    requestCorePermissions(isManualUserTap = false)
                }
            }

            LaunchedEffect(Unit) {
                viewModel.uiMessages.collect { msg ->
                    snackbarHostState.showSnackbar(msg)
                }
            }

            LaunchedEffect(Unit) {
                viewModel.requestLocalDnsVpnConsent.collect {
                    val prepareIntent = VpnService.prepare(this@MainActivity)
                    if (prepareIntent != null) {
                        vpnLauncher.launch(prepareIntent)
                    } else {
                        LocalDnsVpnService.start(this@MainActivity)
                        viewModel.emitMessage(
                            trNow(
                                "Tunelul Turbo DNS (Paralel + Cache RAM + DoH/DoT) a fost activat.",
                                "Turbo DNS Tunnel (Parallel + RAM Cache + DoH/DoT) has been enabled."
                            )
                        )
                    }
                }
            }

            CompositionLocalProvider(LocalIsRo provides isRo) {
                NetStabilTheme(darkTheme = settings.darkTheme) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                            TopAppBar(
                                title = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "NetStabil",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        val badgeColor = when (privilegeStatus.activeTier) {
                                            PrivilegeTier.ROOT, PrivilegeTier.SHIZUKU -> EmeraldPrimary
                                            PrivilegeTier.NORMAL -> AmberWarn
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = badgeColor.copy(alpha = 0.16f)
                                        ) {
                                            Text(
                                                text = privilegeStatus.activeTier.short(isRo),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = badgeColor,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                },
                                actions = {
                                    val langBadgeText = when (settings.appLanguage) {
                                        AppLanguage.AUTO -> if (isRo) "RO*" else "EN*"
                                        AppLanguage.RO -> "RO"
                                        AppLanguage.EN -> "EN"
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        border = BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                                        ),
                                        modifier = Modifier
                                            .padding(end = 2.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { viewModel.cycleLanguage() }
                                    ) {
                                        Text(
                                            text = langBadgeText,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                        )
                                    }
                                    IconButton(onClick = { viewModel.toggleTheme() }) {
                                        Icon(
                                            imageVector = if (settings.darkTheme) {
                                                Icons.Outlined.LightMode
                                            } else {
                                                Icons.Outlined.DarkMode
                                            },
                                            contentDescription = tr(isRo, "Schimbă tema", "Toggle theme")
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.background,
                                    titleContentColor = MaterialTheme.colorScheme.onBackground
                                )
                            )
                        },
                        bottomBar = {
                            val homeLabel = tr(isRo, "Acasă", "Home")
                            val signalLabel = tr(isRo, "Semnal", "Signal")
                            val controlLabel = tr(isRo, "Control", "Control")
                            val logLabel = tr(isRo, "Jurnal", "Log")
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface
                            ) {
                                NavigationBarItem(
                                    selected = currentTab == 0,
                                    onClick = { currentTab = 0 },
                                    icon = { Icon(Icons.Outlined.Speed, contentDescription = homeLabel) },
                                    label = { Text(homeLabel) }
                                )
                                NavigationBarItem(
                                    selected = currentTab == 1,
                                    onClick = { currentTab = 1 },
                                    icon = { Icon(Icons.Outlined.CellTower, contentDescription = signalLabel) },
                                    label = { Text(signalLabel) }
                                )
                                NavigationBarItem(
                                    selected = currentTab == 2,
                                    onClick = { currentTab = 2 },
                                    icon = { Icon(Icons.Outlined.Tune, contentDescription = controlLabel) },
                                    label = { Text(controlLabel) }
                                )
                                NavigationBarItem(
                                    selected = currentTab == 3,
                                    onClick = { currentTab = 3 },
                                    icon = { Icon(Icons.AutoMirrored.Outlined.ReceiptLong, contentDescription = logLabel) },
                                    label = { Text(logLabel) }
                                )
                            }
                        }
                    ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentTab) {
                            0 -> HomeScreen(
                                isRunning = isRunning,
                                isBusyReconnecting = isBusyReconnecting,
                                healthScore = healthScore,
                                headline = headline,
                                cellular = cellular,
                                wifi = wifi,
                                probe = probe,
                                settings = settings,
                                hasRadioPermissions = hasRadioPermissions,
                                onToggleStabilizer = {
                                    if (!hasRadioPermissions) {
                                        requestCorePermissions(isManualUserTap = false)
                                    }
                                    viewModel.toggleStabilizer()
                                },
                                onForceReconnect = { viewModel.forceReconnectNow() },
                                onSelectProfile = { viewModel.selectProfile(it) },
                                onRequestPermissions = { requestCorePermissions(isManualUserTap = true) }
                            )
                            1 -> SignalScreen(
                                cellular = cellular,
                                wifi = wifi,
                                probe = probe,
                                signalHistoryDbm = signalHistoryDbm,
                                privilegeStatus = privilegeStatus,
                                modemCapability = modemCapability,
                                isModemCommandBusy = isModemCommandBusy,
                                isMediaTekDevice = deviceInfo.isMediaTekSoC,
                                isXiaomiDevice = deviceInfo.isXiaomiHyperOsOrMiui,
                                onApplyDirectRatMode = { viewModel.applyDirectRatMode(it) },
                                onApplyDirectBandLock = { viewModel.applyDirectBandLock(it) },
                                onApplyDirectCellLock = { viewModel.applyDirectServingCellLock() },
                                onUnlockAllModem = { viewModel.unlockAllModemLocks() },
                                onOpenRadioInfo = { viewModel.openHiddenRadioInfo() },
                                onOpenMtkBandSelect = { viewModel.openMtkBandSelect() },
                                onOpenMtkEngineer = { viewModel.openMtkEngineerMode() },
                                onOpenApnSettings = { viewModel.openApnSettings() },
                                onCopyDialCode = { code, label ->
                                    viewModel.copyAndLaunchSecretCode(code, label)
                                }
                            )
                            2 -> ControlDnsScreen(
                                settings = settings,
                                privilegeStatus = privilegeStatus,
                                deepTuningStatus = deepTuningStatus,
                                isXiaomiDevice = deviceInfo.isXiaomiHyperOsOrMiui,
                                systemPrivateDnsLabel = systemPrivateDnsLabel,
                                isLocalDnsTunnelActive = isLocalDnsTunnelActive,
                                dnsCacheHits = stats.dnsCacheHits,
                                diagnosticReport = diagnosticReport,
                                isRunningDiagnostic = isRunningDiagnostic,
                                onRunDiagnostic = { viewModel.runFullDiagnostic() },
                                onSelectDns = { viewModel.selectDnsProvider(it) },
                                onKeepAliveChange = { viewModel.setKeepAlive(it) },
                                onPinCellularChange = { viewModel.setPinCellularRadio(it) },
                                onRrcTurboChange = { viewModel.setRrcTurbo(it) },
                                onAutoReconnectChange = { viewModel.setAutoReconnect(it) },
                                onAnti2gChange = { viewModel.setAnti2gTrap(it) },
                                onSmartWifiChange = { viewModel.setSmartWifi(it) },
                                onWifiCutoffChange = { viewModel.setWifiCutoffDbm(it) },
                                onCarrierConfigBoostChange = { viewModel.setCarrierConfigBoost(it) },
                                onFastKernelDataStallChange = { viewModel.setFastKernelDataStall(it) },
                                onConnectivityRfBoostChange = { viewModel.setConnectivityRfBoost(it) },
                                onHyperOsSleepShieldChange = { viewModel.setHyperOsSleepShield(it) },
                                onDualSimAutoSwitchChange = { viewModel.setDualSimAutoSwitch(it) },
                                onRestrictPowerKeeperChange = { viewModel.setRestrictXiaomiPowerKeeper(it) },
                                onReapplyDeepNow = { viewModel.reapplyAllDeepOptimizationsNow() },
                                onRestoreFactoryDefaults = { viewModel.restoreFactoryDeepSystemDefaults() },
                                onStartOnBootChange = { viewModel.setStartOnBoot(it) },
                                onOpenApnSettings = { viewModel.openApnSettings() }
                            )
                            3 -> LogSystemScreen(
                                deviceInfo = deviceInfo,
                                privilegeStatus = privilegeStatus,
                                deepTuningStatus = deepTuningStatus,
                                stats = stats,
                                events = events,
                                onAddQuickTile = {
                                    DeviceOptimizationHelper.requestAddQuickSettingsTile(this@MainActivity) { msg ->
                                        viewModel.emitMessage(msg)
                                    }
                                },
                                onPinHomeWidget = {
                                    val ok = DeviceOptimizationHelper.requestPinHomeWidget(this@MainActivity)
                                    if (ok) {
                                        viewModel.emitMessage(
                                            tr(
                                                isRo,
                                                "Confirmați adăugarea widget-ului pe ecran.",
                                                "Confirm adding the widget to your home screen."
                                            )
                                        )
                                    } else {
                                        viewModel.emitMessage(
                                            tr(
                                                isRo,
                                                "Țineți apăsat pe ecranul principal -> Widget-uri -> NetStabil.",
                                                "Long-press on your Home Screen -> Widgets -> NetStabil."
                                            )
                                        )
                                    }
                                },
                                onAuthorizeShizuku = { viewModel.authorizeShizuku() },
                                onOpenShizukuApp = {
                                    DeviceOptimizationHelper.openShizukuOrPlayStore(this@MainActivity)
                                },
                                onOpenWirelessDebug = {
                                    DeviceOptimizationHelper.openDeveloperWirelessDebuggingSettings(this@MainActivity)
                                },
                                onRequestBatteryExemption = {
                                    DeviceOptimizationHelper.requestIgnoreBatteryOptimizations(this@MainActivity)
                                },
                                onOpenXiaomiAutoStart = {
                                    DeviceOptimizationHelper.openXiaomiAutoStartSettings(this@MainActivity)
                                },
                                onRefreshPrivilege = { viewModel.refreshSystemStatus() },
                                onCopyLogs = { viewModel.copyLogsToClipboard() },
                                onClearLogs = { viewModel.clearLogs() }
                            )
                        }
                    }
                }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResumed()
    }

    override fun onPause() {
        viewModel.onAppPaused()
        super.onPause()
    }
}
