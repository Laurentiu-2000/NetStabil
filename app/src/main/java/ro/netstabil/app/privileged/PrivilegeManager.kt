package ro.netstabil.app.privileged

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import rikka.shizuku.Shizuku
import ro.netstabil.app.BuildConfig
import ro.netstabil.app.data.DeepTuningStatus
import ro.netstabil.app.data.DnsProvider
import ro.netstabil.app.data.ModemControlCapability
import ro.netstabil.app.data.PreferredRatMode
import ro.netstabil.app.data.StabilizerSettings
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

enum class PrivilegeTier(
    val labelRo: String,
    val shortRo: String,
    val labelEn: String,
    val shortEn: String
) {
    ROOT(
        "Mod Root (Acces Complet Kernel + Radio)",
        "MOD ROOT",
        "Root Mode (Full Kernel + Radio Access)",
        "ROOT MODE"
    ),
    SHIZUKU(
        "Mod Shizuku (Acces Sistem Fără Root)",
        "MOD SHIZUKU",
        "Shizuku Mode (Rootless System Access)",
        "SHIZUKU MODE"
    ),
    NORMAL(
        "Mod Normal (Standard Android)",
        "MOD NORMAL",
        "Normal Mode (Standard Android)",
        "NORMAL MODE"
    );

    fun short(isRo: Boolean): String = if (isRo) shortRo else shortEn
    fun label(isRo: Boolean): String = if (isRo) labelRo else labelEn
}

data class PrivilegeStatus(
    val activeTier: PrivilegeTier = PrivilegeTier.NORMAL,
    val isRootAvailable: Boolean = false,
    val isShizukuInstalled: Boolean = false,
    val isShizukuRunning: Boolean = false,
    val isShizukuGranted: Boolean = false,
    val isShizukuServiceBound: Boolean = false,
    val hasWriteSecureSettings: Boolean = false,
    val shellUid: Int = -1
)

data class ShellResult(
    val success: Boolean,
    val exitCode: Int,
    val output: String
)

data class ModemActionResult(
    val success: Boolean,
    val messageRo: String
)

class PrivilegeManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bindMutex = Mutex()
    private val deepTuningMutex = Mutex()

    private val _status = MutableStateFlow(PrivilegeStatus())
    val status: StateFlow<PrivilegeStatus> = _status.asStateFlow()

    private val _modemCapability = MutableStateFlow(ModemControlCapability())
    val modemCapability: StateFlow<ModemControlCapability> = _modemCapability.asStateFlow()

    private val _deepTuningStatus = MutableStateFlow(DeepTuningStatus())
    val deepTuningStatus: StateFlow<DeepTuningStatus> = _deepTuningStatus.asStateFlow()

    @Volatile
    private var shizukuService: IPrivilegedService? = null
    @Volatile
    private var pendingBindDeferred: CompletableDeferred<IPrivilegedService?>? = null

    @Volatile
    private var lastAppliedDeepSignature: String? = null
    @Volatile
    private var carrierConfigAppliedInSession: Boolean = false
    @Volatile
    private var dualSimPolicyAppliedInSession: Boolean = false
    @Volatile
    private var hyperOsShieldAppliedInSession: Boolean = false
    @Volatile
    private var powerKeeperRestrictedInSession: Boolean = false
    @Volatile
    private var rootProbeCompleted: Boolean = false
    @Volatile
    private var cachedRootAvailable: Boolean = false
    @Volatile
    var isInternalRadioResetInProgress: Boolean = false
        private set

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, PrivilegedUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("privileged")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                val svc = IPrivilegedService.Stub.asInterface(binder)
                shizukuService = svc
                pendingBindDeferred?.complete(svc)
                scope.launch {
                    val uid = runCatching { svc.uid }.getOrDefault(2000)
                    grantPermanentSystemPermissionsInternal()
                    lastAppliedDeepSignature = null
                    refreshState(overrideBound = true, overrideUid = uid)
                    probeModemHardware(-1, 0)
                }
            } else {
                pendingBindDeferred?.complete(null)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuService = null
            lastAppliedDeepSignature = null
            scope.launch { refreshState(overrideBound = false) }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        scope.launch {
            refreshState()
            if (checkShizukuGrantedSafe()) {
                ensureShizukuBound()
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuService = null
        lastAppliedDeepSignature = null
        scope.launch {
            refreshState(overrideBound = false)
            _modemCapability.value = ModemControlCapability()
        }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            scope.launch {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                lastAppliedDeepSignature = null
                refreshState()
                if (granted) {
                    ensureShizukuBound()
                }
            }
        }

    fun initialize() {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }
        scope.launch {
            refreshState()
            refreshDeepTuningReadbackOnly()
            if (_status.value.isRootAvailable) {
                grantPermanentSystemPermissionsInternal()
                refreshState()
                probeModemHardware(-1, 0)
            } else if (_status.value.isShizukuRunning && _status.value.isShizukuGranted) {
                ensureShizukuBound()
            }
        }
    }

    fun shutdown() {
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            if (shizukuService != null) {
                Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            }
        }
    }

    fun requestShizukuPermission(requestCode: Int = 1001): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                scope.launch { ensureShizukuBound() }
                true
            } else {
                Shizuku.requestPermission(requestCode)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun refreshState(
        overrideBound: Boolean? = null,
        overrideUid: Int? = null,
        forceRootReprobe: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val rootAvail = checkRootAvailable(forceRootReprobe)
        val shizukuInstalled = isPackageInstalled("moe.shizuku.privileged.api")
        val shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuGranted = if (shizukuRunning) checkShizukuGrantedSafe() else false
        val isBound = overrideBound ?: (shizukuService?.asBinder()?.pingBinder() == true)
        val hasWriteSecure = context.checkSelfPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

        val tier = when {
            rootAvail -> PrivilegeTier.ROOT
            shizukuRunning && shizukuGranted -> PrivilegeTier.SHIZUKU
            else -> PrivilegeTier.NORMAL
        }

        val currentUid = overrideUid ?: when {
            rootAvail -> 0
            isBound -> runCatching { shizukuService?.uid ?: 2000 }.getOrDefault(2000)
            else -> -1
        }

        _status.value = PrivilegeStatus(
            activeTier = tier,
            isRootAvailable = rootAvail,
            isShizukuInstalled = shizukuInstalled,
            isShizukuRunning = shizukuRunning,
            isShizukuGranted = shizukuGranted,
            isShizukuServiceBound = isBound,
            hasWriteSecureSettings = hasWriteSecure,
            shellUid = currentUid
        )
    }

    private fun checkShizukuGrantedSafe(): Boolean {
        return try {
            !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    pkg,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun hasSuBinary(): Boolean {
        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/vendor/bin/su"
        )
        return paths.any { runCatching { File(it).exists() }.getOrDefault(false) }
    }

    private fun checkRootAvailable(forceReprobe: Boolean = false): Boolean {
        if (rootProbeCompleted && !forceReprobe) {
            return cachedRootAvailable
        }
        if (!hasSuBinary()) {
            cachedRootAvailable = false
            rootProbeCompleted = true
            return false
        }
        val res = execRootInternal("id", 3)
        val granted = res.success && res.output.contains("uid=0")
        cachedRootAvailable = granted
        rootProbeCompleted = true
        return granted
    }

    private fun isXiaomiBrandDevice(): Boolean {
        val m = Build.MANUFACTURER ?: ""
        val b = Build.BRAND ?: ""
        return m.contains("xiaomi", true) ||
            b.contains("xiaomi", true) ||
            b.contains("redmi", true) ||
            b.contains("poco", true)
    }

    fun isSystemAirplaneModeEnabled(): Boolean {
        if (isInternalRadioResetInProgress) return false
        return runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        }.getOrDefault(false)
    }

    fun isMobileDataEnabledByUser(): Boolean {
        if (isInternalRadioResetInProgress) return true

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeCellularUp = runCatching {
            val active = cm?.activeNetwork ?: return@runCatching false
            cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }.getOrDefault(false)
        if (activeCellularUp) return true

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

        if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val scopedTm = if (baseTm != null && activeDataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                runCatching { baseTm.createForSubscriptionId(activeDataSubId) }.getOrDefault(baseTm)
            } else {
                baseTm
            }
            val enabled = runCatching { scopedTm?.isDataEnabled }.getOrNull()
            if (enabled != null) return enabled
        }

        val settingKey = if (activeDataSubId > 0) "mobile_data$activeDataSubId" else "mobile_data"
        val subToggle = runCatching {
            val cr = context.contentResolver
            if (Settings.Global.getString(cr, settingKey) != null) {
                Settings.Global.getInt(cr, settingKey, 1)
            } else {
                Settings.Global.getInt(cr, "mobile_data", 1)
            }
        }.getOrDefault(1)
        return subToggle != 0
    }

    private fun execRootInternal(command: String, timeoutSec: Int = 8): ShellResult {
        return try {
            val pb = ProcessBuilder("su", "-c", command)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = StringBuilder()
            val readerThread = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            if (out.length < 32768) out.append(line).append('\n')
                        }
                    }
                }
            }
            readerThread.isDaemon = true
            readerThread.start()
            val done = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!done) {
                proc.destroyForcibly()
                return ShellResult(false, 124, "Timeout su")
            }
            readerThread.join(400)
            val code = proc.exitValue()
            ShellResult(code == 0, code, out.toString().trim())
        } catch (e: Exception) {
            ShellResult(false, 127, e.message ?: "su indisponibil")
        }
    }

    private suspend fun ensureShizukuBound(): IPrivilegedService? = bindMutex.withLock {
        val existing = shizukuService
        if (existing != null && existing.asBinder().pingBinder()) {
            return existing
        }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return null
        if (!checkShizukuGrantedSafe()) return null

        val deferred = CompletableDeferred<IPrivilegedService?>()
        pendingBindDeferred = deferred
        val boundCallOk = withContext(Dispatchers.Main) {
            runCatching {
                Shizuku.bindUserService(userServiceArgs, userServiceConnection)
                true
            }.getOrDefault(false)
        }
        if (!boundCallOk) {
            pendingBindDeferred = null
            return null
        }
        val svc = withTimeoutOrNull(8000L) { deferred.await() }
        pendingBindDeferred = null
        return svc
    }

    suspend fun runPrivilegedCommand(command: String, timeoutSec: Int = 10): ShellResult =
        withContext(Dispatchers.IO) {
            val current = _status.value
            if (current.isRootAvailable) {
                return@withContext execRootInternal(command, timeoutSec)
            }
            val svc = ensureShizukuBound()
            if (svc != null) {
                return@withContext try {
                    val raw = svc.execCommand(command, timeoutSec)
                    parseAidlResponse(raw)
                } catch (e: Exception) {
                    ShellResult(false, 126, e.message ?: "Eroare comunicare Shizuku")
                }
            }
            ShellResult(false, 125, "Niciun mod privilegiat activ (Root sau Shizuku)")
        }

    // --- Control Direct Hardware Modem (Shizuku Binder / Root app_process) ---

    private fun invokeRootModemCli(vararg cliArgs: String): String? {
        val apkPath = context.applicationInfo.sourceDir
        val escapedArgs = cliArgs.joinToString(" ") { "'${it.replace("'", "")}'" }
        val cmd = "CLASSPATH='$apkPath' /system/bin/app_process /system/bin ro.netstabil.app.privileged.ModemRootCli $escapedArgs"
        val res = execRootInternal(cmd, 12)
        if (!res.success && res.output.isBlank()) return null
        return res.output.lineSequence().lastOrNull { it.trim().startsWith("{") }
    }

    suspend fun probeModemHardware(subId: Int, simSlotZeroBased: Int): ModemControlCapability =
        withContext(Dispatchers.IO) {
            val rawJson = if (_status.value.isRootAvailable) {
                invokeRootModemCli("probe", subId.toString(), simSlotZeroBased.toString())
            } else {
                ensureShizukuBound()?.let { svc ->
                    runCatching { svc.probeModemCapabilities(subId, simSlotZeroBased) }.getOrNull()
                }
            }

            if (rawJson.isNullOrBlank()) {
                return@withContext _modemCapability.value
            }

            val parsed = runCatching { JSONObject(rawJson) }.getOrNull() ?: return@withContext _modemCapability.value
            val isRo = ro.netstabil.app.i18n.I18n.currentIsRo
            val backendDefault = if (isRo) "Necunoscut" else "Unknown"
            val updated = ModemControlCapability(
                probed = true,
                hasDirectRatControl = parsed.optBoolean("hasDirectRat", false),
                hasMtkAtPassthrough = parsed.optBoolean("hasMtkAt", false),
                hasHalChannelLock = parsed.optBoolean("hasHalLock", false),
                activeBackendRo = if (isRo) {
                    parsed.optString("backend", backendDefault)
                } else {
                    parsed.optString("backendEn", parsed.optString("backend", backendDefault))
                },
                activeBandsSummary = parsed.optString("bandsSummary", ""),
                isCellLocked = parsed.optBoolean("cellLocked", false),
                lastActionMessageRo = if (isRo) {
                    parsed.optString("msg", "")
                } else {
                    parsed.optString("msgEn", parsed.optString("msg", ""))
                }
            )
            _modemCapability.value = updated
            updated
        }

    suspend fun applyDirectRatMode(
        subId: Int,
        simSlotZeroBased: Int,
        mode: PreferredRatMode
    ): ModemActionResult = withContext(Dispatchers.IO) {
        val rawJson = if (_status.value.isRootAvailable) {
            invokeRootModemCli("rat", subId.toString(), simSlotZeroBased.toString(), mode.code)
        } else {
            ensureShizukuBound()?.let { svc ->
                runCatching { svc.setPreferredRatMode(subId, simSlotZeroBased, mode.code) }.getOrNull()
            }
        }
        parseAndRecordModemAction(
            rawJson,
            ro.netstabil.app.i18n.trNow(
                "Activați mai întâi Shizuku pentru control direct 4G.",
                "Enable Shizuku first for direct 4G control."
            )
        )
    }

    suspend fun applyDirectLteBandLock(
        subId: Int,
        simSlotZeroBased: Int,
        bands: List<Int>
    ): ModemActionResult = withContext(Dispatchers.IO) {
        val clean = bands.distinct().sorted()
        val rawJson = if (_status.value.isRootAvailable) {
            invokeRootModemCli(
                "bands",
                subId.toString(),
                simSlotZeroBased.toString(),
                clean.joinToString(",")
            )
        } else {
            ensureShizukuBound()?.let { svc ->
                runCatching { svc.lockLteBands(subId, simSlotZeroBased, clean.toIntArray()) }.getOrNull()
            }
        }
        parseAndRecordModemAction(
            rawJson,
            ro.netstabil.app.i18n.trNow(
                "Activați mai întâi Shizuku pentru blocarea benzilor 4G.",
                "Enable Shizuku first to lock 4G LTE bands."
            )
        )
    }

    suspend fun applyDirectCellLock(
        subId: Int,
        simSlotZeroBased: Int,
        earfcn: Int,
        pci: Int
    ): ModemActionResult = withContext(Dispatchers.IO) {
        val rawJson = if (_status.value.isRootAvailable) {
            invokeRootModemCli(
                "cell",
                subId.toString(),
                simSlotZeroBased.toString(),
                earfcn.toString(),
                pci.toString()
            )
        } else {
            ensureShizukuBound()?.let { svc ->
                runCatching { svc.lockLteCell(subId, simSlotZeroBased, earfcn, pci) }.getOrNull()
            }
        }
        parseAndRecordModemAction(
            rawJson,
            ro.netstabil.app.i18n.trNow(
                "Activați mai întâi Shizuku pentru blocarea stâlpului 4G.",
                "Enable Shizuku first to lock the 4G cell tower."
            )
        )
    }

    suspend fun unlockAllModemHardware(
        subId: Int,
        simSlotZeroBased: Int
    ): ModemActionResult = withContext(Dispatchers.IO) {
        val rawJson = if (_status.value.isRootAvailable) {
            invokeRootModemCli("unlock", subId.toString(), simSlotZeroBased.toString())
        } else {
            ensureShizukuBound()?.let { svc ->
                runCatching { svc.unlockAllModemLocks(subId, simSlotZeroBased) }.getOrNull()
            }
        }
        parseAndRecordModemAction(
            rawJson,
            ro.netstabil.app.i18n.trNow(
                "Activați Shizuku pentru a reseta blocările modemului.",
                "Enable Shizuku to reset modem locks."
            )
        )
    }

    // --- Cele 5 Mecanisme Avansate de Sistem Fara Root (Android 16 + HyperOS + CarrierConfig) ---

    @Volatile
    private var lastDetectedSimCount: Int = 1

    private fun readActiveSimCountSafe(): Int {
        val count = runCatching {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? android.telephony.SubscriptionManager
            @Suppress("MissingPermission")
            sm?.activeSubscriptionInfoCount ?: 0
        }.getOrDefault(0)
        return if (count > 0) count else lastDetectedSimCount
    }

    private suspend fun invokeCarrierConfigBoostAidl(
        subId: Int,
        simSlotZeroBased: Int,
        enable: Boolean
    ): Boolean {
        val rawJson = if (_status.value.isRootAvailable) {
            invokeRootModemCli("carrier", subId.toString(), simSlotZeroBased.toString(), enable.toString())
        } else {
            ensureShizukuBound()?.let { svc ->
                runCatching { svc.applyCarrierConfigBoost(subId, simSlotZeroBased, enable) }.getOrNull()
            }
        } ?: return false
        return runCatching {
            val obj = JSONObject(rawJson)
            val subCount = obj.optInt("subCount", 0)
            if (subCount > 0) lastDetectedSimCount = subCount
            obj.optBoolean("ok", false)
        }.getOrDefault(false)
    }

    private suspend fun invokeDualSimAutoSwitchAidl(enable: Boolean): Boolean {
        val rawJson = if (_status.value.isRootAvailable) {
            invokeRootModemCli("dualsim", "-1", "0", enable.toString())
        } else {
            ensureShizukuBound()?.let { svc ->
                runCatching { svc.setDualSimAutoSwitch(enable) }.getOrNull()
            }
        } ?: return false
        return runCatching {
            val obj = JSONObject(rawJson)
            val subCount = obj.optInt("subCount", 0)
            if (subCount > 0) lastDetectedSimCount = subCount
            obj.optBoolean("ok", false)
        }.getOrDefault(false)
    }

    private suspend fun putGlobalSettingSmart(key: String, value: String): Boolean {
        val safeValue = value.replace("'", "")
        val hasDirectWrite = context.checkSelfPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasDirectWrite) {
            val wrote = runCatching {
                Settings.Global.putString(context.contentResolver, key, safeValue)
            }.getOrDefault(false)
            if (wrote) return true
        }
        return runPrivilegedCommand("settings put global $key '$safeValue' 2>/dev/null", 5).success
    }

    private suspend fun deleteGlobalSettingSmart(key: String) {
        if (_status.value.activeTier != PrivilegeTier.NORMAL) {
            runPrivilegedCommand("settings delete global $key 2>/dev/null", 5)
        }
        val hasDirectWrite = context.checkSelfPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasDirectWrite) {
            runCatching {
                context.contentResolver.delete(Settings.Global.getUriFor(key), null, null)
            }
            runCatching {
                Settings.Global.putString(context.contentResolver, key, null)
            }
        }
    }

    fun refreshDeepTuningReadbackOnly() {
        val verifiedGlobal = isGlobalKernelTuningVerifiedInOs()
        val simCount = readActiveSimCountSafe()
        _deepTuningStatus.update { current ->
            current.copy(
                globalKernelTweaksVerified = verifiedGlobal,
                carrierConfigOverridden = carrierConfigAppliedInSession,
                hyperOsShieldActive = hyperOsShieldAppliedInSession || isHyperOsFreezerDisabledInOs(),
                dualSimPolicyActive = dualSimPolicyAppliedInSession,
                powerKeeperRestricted = powerKeeperRestrictedInSession,
                detectedSimCount = simCount,
                lastSummaryRo = buildDeepStatusSummaryRo(
                    verifiedGlobal = verifiedGlobal,
                    carrierOk = carrierConfigAppliedInSession,
                    hyperOk = hyperOsShieldAppliedInSession || isHyperOsFreezerDisabledInOs(),
                    dualSimOk = dualSimPolicyAppliedInSession
                )
            )
        }
    }

    private fun isGlobalKernelTuningVerifiedInOs(): Boolean {
        return runCatching {
            val cr = context.contentResolver
            val dsrm = Settings.Global.getString(cr, KEY_DSRM_DURATION)
            val wifiScore = Settings.Global.getString(cr, "wifi_score_params")
            !dsrm.isNullOrBlank() || !wifiScore.isNullOrBlank()
        }.getOrDefault(false)
    }

    private fun isHyperOsFreezerDisabledInOs(): Boolean {
        return runCatching {
            Settings.Global.getString(context.contentResolver, "cached_apps_freezer") == "disabled"
        }.getOrDefault(false)
    }

    /**
     * Sincronizeaza cele 5 mecanisme avansate (cu deduplicare prin semnatura ca sa nu repete inutil
     * apelurile Binder / Settings in bucla de fundal).
     */
    suspend fun syncDeepSystemOptimizations(
        settings: StabilizerSettings,
        subId: Int,
        simSlotZeroBased: Int,
        forceReapply: Boolean = false
    ): DeepTuningStatus = deepTuningMutex.withLock {
        val st = _status.value
        val effectiveSubId = if (subId > 0) {
            subId
        } else {
            runCatching { android.telephony.SubscriptionManager.getDefaultDataSubscriptionId() }
                .getOrDefault(subId)
        }
        val signature = listOf(
            st.activeTier.name,
            st.hasWriteSecureSettings,
            effectiveSubId,
            settings.carrierConfigBoostEnabled,
            settings.fastKernelDataStallEnabled,
            settings.connectivityRfBoostEnabled,
            settings.wifiCutoffRssiDbm,
            settings.hyperOsSleepShieldEnabled,
            settings.dualSimAutoSwitchEnabled,
            settings.restrictXiaomiPowerKeeperBg
        ).joinToString("|")

        if (!forceReapply && signature == lastAppliedDeepSignature) {
            return@withLock _deepTuningStatus.value
        }

        val canWriteSettings = st.hasWriteSecureSettings || st.activeTier != PrivilegeTier.NORMAL
        val isPrivileged = st.activeTier != PrivilegeTier.NORMAL

        // 1. CarrierConfig IMS / VoLTE / VoWiFi / 4G+ / Anti-2G (Shizuku / Root)
        if (isPrivileged) {
            if (settings.carrierConfigBoostEnabled) {
                carrierConfigAppliedInSession =
                    invokeCarrierConfigBoostAidl(effectiveSubId, simSlotZeroBased, true)
            } else if (carrierConfigAppliedInSession) {
                invokeCarrierConfigBoostAidl(effectiveSubId, simSlotZeroBased, false)
                carrierConfigAppliedInSession = false
            }
        }

        // 2. DataStallRecoveryManager rapid + DnsResolver rapid (Settings.Global permanent)
        if (canWriteSettings) {
            if (settings.fastKernelDataStallEnabled) {
                putGlobalSettingSmart(KEY_DSRM_DURATION, FAST_DSRM_DURATIONS)
                putGlobalSettingSmart(KEY_DSRM_ACTIONS, FAST_DSRM_ACTIONS)
                putGlobalSettingSmart("dns_resolver_sample_validity_seconds", "600")
                putGlobalSettingSmart("dns_resolver_min_samples", "4")
                putGlobalSettingSmart("dns_resolver_success_threshold_percent", "45")
            } else {
                deleteGlobalSettingSmart(KEY_DSRM_DURATION)
                deleteGlobalSettingSmart(KEY_DSRM_ACTIONS)
                deleteGlobalSettingSmart("dns_resolver_sample_validity_seconds")
                deleteGlobalSettingSmart("dns_resolver_min_samples")
                deleteGlobalSettingSmart("dns_resolver_success_threshold_percent")
            }
        }

        // 3. ConnectivityService + Multipath QUIC/HTTP3 + Mainline cmd wifi + RF 2.4 GHz
        if (canWriteSettings) {
            if (settings.connectivityRfBoostEnabled) {
                putGlobalSettingSmart("mobile_data_always_on", "1")
                putGlobalSettingSmart("network_avoid_bad_wifi", "1")
                putGlobalSettingSmart("network_metered_multipath_preference", MULTIPATH_ALL_ENABLED)
                putGlobalSettingSmart("network_default_daily_multipath_quota_bytes", MULTIPATH_DAILY_QUOTA_BYTES)
                putGlobalSettingSmart("wifi_wakeup_enabled", "0")
                putGlobalSettingSmart("wifi_scan_always_enabled", "0")
                putGlobalSettingSmart("ble_scan_always_enabled", "0")
                putGlobalSettingSmart(
                    "wifi_score_params",
                    buildWifiScoreParams(settings.wifiCutoffRssiDbm)
                )
                if (isPrivileged) {
                    runPrivilegedCommand(
                        "cmd wifi set-scan-always-available disabled 2>/dev/null; " +
                            "cmd wifi set-ipreach-disconnect enabled 2>/dev/null; " +
                            "cmd wifi set-one-shot-screen-on-delay-ms 0 2>/dev/null",
                        6
                    )
                }
            } else {
                deleteGlobalSettingSmart("wifi_score_params")
                deleteGlobalSettingSmart("network_metered_multipath_preference")
                deleteGlobalSettingSmart("network_default_daily_multipath_quota_bytes")
                if (isPrivileged) {
                    runPrivilegedCommand("cmd wifi set-one-shot-screen-on-delay-ms 200 2>/dev/null", 5)
                }
            }
        }

        // 4. Scut Retea Ecran Stins Xiaomi HyperOS / Android Freezer + PowerKeeper optional
        if (canWriteSettings) {
            if (settings.hyperOsSleepShieldEnabled) {
                val freezerOk = putGlobalSettingSmart("cached_apps_freezer", "disabled")
                if (isPrivileged) {
                    val pkg = BuildConfig.APPLICATION_ID
                    val shieldCmd = buildString {
                        if (isXiaomiBrandDevice()) {
                            val existingPrio = runCatching {
                                Settings.System.getString(context.contentResolver, "cloud_network_priority_whitelist")
                            }.getOrNull()
                            val existingLowLat = runCatching {
                                Settings.System.getString(context.contentResolver, "cloud_lowlatency_whitelist")
                            }.getOrNull()
                            val existingMem = runCatching {
                                Settings.System.getString(context.contentResolver, "cloud_memory_freeze_whitelist")
                            }.getOrNull()

                            val mergedPrio = mergePackageIntoCsvWhitelist(existingPrio, pkg)
                            val mergedLowLat = mergePackageIntoCsvWhitelist(existingLowLat, pkg)
                            val mergedMem = mergePackageIntoCsvWhitelist(existingMem, pkg)

                            append("settings put system cloud_sleepmode_networkpolicy_enabled off 2>/dev/null; ")
                            append("settings put system cloud_greezer_enable false 2>/dev/null; ")
                            append("settings put system cloud_network_priority_whitelist '$mergedPrio' 2>/dev/null; ")
                            append("settings put system cloud_lowlatency_whitelist '$mergedLowLat' 2>/dev/null; ")
                            append("settings put system cloud_memory_freeze_whitelist '$mergedMem' 2>/dev/null; ")
                        }
                        append("cmd deviceidle except-idle-whitelist +$pkg 2>/dev/null; ")
                        append("am set-standby-bucket $pkg active 2>/dev/null; ")
                        append("cmd appops set $pkg WAKE_LOCK allow 2>/dev/null")
                    }
                    val shellOk = runPrivilegedCommand(shieldCmd, 8).success
                    hyperOsShieldAppliedInSession = freezerOk || shellOk
                } else {
                    hyperOsShieldAppliedInSession = freezerOk
                }
            } else if (hyperOsShieldAppliedInSession) {
                deleteGlobalSettingSmart("cached_apps_freezer")
                hyperOsShieldAppliedInSession = false
            }
        }

        if (isPrivileged && isPackageInstalled("com.miui.powerkeeper")) {
            if (settings.restrictXiaomiPowerKeeperBg) {
                val pkRes = runPrivilegedCommand(
                    "cmd appops set com.miui.powerkeeper RUN_IN_BACKGROUND deny 2>/dev/null && " +
                        "cmd appops set com.miui.powerkeeper GET_USAGE_STATS deny 2>/dev/null && " +
                        "cmd appops set com.miui.powerkeeper WRITE_SETTINGS deny 2>/dev/null",
                    6
                )
                powerKeeperRestrictedInSession = pkRes.success
            } else if (powerKeeperRestrictedInSession) {
                runPrivilegedCommand(
                    "cmd appops set com.miui.powerkeeper RUN_IN_BACKGROUND allow 2>/dev/null; " +
                        "cmd appops set com.miui.powerkeeper GET_USAGE_STATS allow 2>/dev/null; " +
                        "cmd appops set com.miui.powerkeeper WRITE_SETTINGS allow 2>/dev/null",
                    6
                )
                powerKeeperRestrictedInSession = false
            }
        }

        // 5. Politica Dual-SIM Auto Data Switch (Shizuku / Root)
        if (isPrivileged) {
            if (settings.dualSimAutoSwitchEnabled) {
                dualSimPolicyAppliedInSession = invokeDualSimAutoSwitchAidl(true)
            } else if (dualSimPolicyAppliedInSession) {
                invokeDualSimAutoSwitchAidl(false)
                dualSimPolicyAppliedInSession = false
            }
        }

        lastAppliedDeepSignature = signature
        val verifiedGlobal = isGlobalKernelTuningVerifiedInOs()
        val simCount = readActiveSimCountSafe()
        val updated = DeepTuningStatus(
            globalKernelTweaksVerified = verifiedGlobal,
            carrierConfigOverridden = carrierConfigAppliedInSession,
            hyperOsShieldActive = hyperOsShieldAppliedInSession || isHyperOsFreezerDisabledInOs(),
            dualSimPolicyActive = dualSimPolicyAppliedInSession,
            powerKeeperRestricted = powerKeeperRestrictedInSession,
            detectedSimCount = simCount,
            lastSummaryRo = buildDeepStatusSummaryRo(
                verifiedGlobal = verifiedGlobal,
                carrierOk = carrierConfigAppliedInSession,
                hyperOk = hyperOsShieldAppliedInSession || isHyperOsFreezerDisabledInOs(),
                dualSimOk = dualSimPolicyAppliedInSession
            )
        )
        _deepTuningStatus.value = updated
        updated
    }

    /**
     * Readuce la valorile din fabrica toate cele 5 mecanisme avansate (Settings.Global, CarrierConfig,
     * Dual-SIM si PowerKeeper), fara niciun efect rezidual.
     */
    suspend fun restoreFactorySystemDefaults(
        subId: Int,
        simSlotZeroBased: Int
    ): ModemActionResult = deepTuningMutex.withLock {
        val st = _status.value
        val canWrite = st.hasWriteSecureSettings || st.activeTier != PrivilegeTier.NORMAL
        val isPriv = st.activeTier != PrivilegeTier.NORMAL

        if (canWrite) {
            deleteGlobalSettingSmart(KEY_DSRM_DURATION)
            deleteGlobalSettingSmart(KEY_DSRM_ACTIONS)
            deleteGlobalSettingSmart("dns_resolver_sample_validity_seconds")
            deleteGlobalSettingSmart("dns_resolver_min_samples")
            deleteGlobalSettingSmart("dns_resolver_success_threshold_percent")
            deleteGlobalSettingSmart("wifi_score_params")
            deleteGlobalSettingSmart("network_metered_multipath_preference")
            deleteGlobalSettingSmart("network_default_daily_multipath_quota_bytes")
            deleteGlobalSettingSmart("cached_apps_freezer")
            putGlobalSettingSmart("wifi_scan_throttle_enabled", "1")
        }

        if (isPriv) {
            invokeCarrierConfigBoostAidl(subId, simSlotZeroBased, false)
            invokeDualSimAutoSwitchAidl(false)
            val pkg = BuildConfig.APPLICATION_ID
            val restoreCmd = buildString {
                if (isXiaomiBrandDevice()) {
                    val cleanedPrio = removePackageFromCsvWhitelist(
                        runCatching { Settings.System.getString(context.contentResolver, "cloud_network_priority_whitelist") }.getOrNull(),
                        pkg
                    )
                    val cleanedLowLat = removePackageFromCsvWhitelist(
                        runCatching { Settings.System.getString(context.contentResolver, "cloud_lowlatency_whitelist") }.getOrNull(),
                        pkg
                    )
                    val cleanedMem = removePackageFromCsvWhitelist(
                        runCatching { Settings.System.getString(context.contentResolver, "cloud_memory_freeze_whitelist") }.getOrNull(),
                        pkg
                    )
                    append("settings delete system cloud_sleepmode_networkpolicy_enabled 2>/dev/null; ")
                    append("settings delete system cloud_greezer_enable 2>/dev/null; ")
                    append("settings put system cloud_network_priority_whitelist '$cleanedPrio' 2>/dev/null; ")
                    append("settings put system cloud_lowlatency_whitelist '$cleanedLowLat' 2>/dev/null; ")
                    append("settings put system cloud_memory_freeze_whitelist '$cleanedMem' 2>/dev/null; ")
                    append("cmd appops set com.miui.powerkeeper RUN_IN_BACKGROUND allow 2>/dev/null; ")
                    append("cmd appops set com.miui.powerkeeper GET_USAGE_STATS allow 2>/dev/null; ")
                    append("cmd appops set com.miui.powerkeeper WRITE_SETTINGS allow 2>/dev/null; ")
                }
                append("cmd wifi set-one-shot-screen-on-delay-ms 200 2>/dev/null")
            }
            runPrivilegedCommand(restoreCmd, 8)
        }

        carrierConfigAppliedInSession = false
        dualSimPolicyAppliedInSession = false
        hyperOsShieldAppliedInSession = false
        powerKeeperRestrictedInSession = false
        lastAppliedDeepSignature = null

        val statusAfter = DeepTuningStatus(
            globalKernelTweaksVerified = false,
            carrierConfigOverridden = false,
            hyperOsShieldActive = false,
            dualSimPolicyActive = false,
            powerKeeperRestricted = false,
            detectedSimCount = readActiveSimCountSafe(),
            lastSummaryRo = ro.netstabil.app.i18n.trNow(
                "Valori din fabrică restaurate",
                "Factory defaults restored"
            )
        )
        _deepTuningStatus.value = statusAfter

        ModemActionResult(
            success = canWrite || isPriv,
            messageRo = if (canWrite || isPriv) {
                ro.netstabil.app.i18n.trNow(
                    "Toate setările avansate de sistem, CarrierConfig și HyperOS au revenit la valorile din fabrică.",
                    "All advanced system, CarrierConfig, and HyperOS settings have been restored to factory defaults."
                )
            } else {
                ro.netstabil.app.i18n.trNow(
                    "Nicio permisiune de sistem activă — setările sunt deja cele implicite.",
                    "No system permissions active — settings are already at system defaults."
                )
            }
        )
    }

    private fun buildDeepStatusSummaryRo(
        verifiedGlobal: Boolean,
        carrierOk: Boolean,
        hyperOk: Boolean,
        dualSimOk: Boolean
    ): String {
        val isRo = ro.netstabil.app.i18n.I18n.currentIsRo
        val parts = buildList {
            if (verifiedGlobal) add(if (isRo) "Nucleu DSRM + RF" else "DSRM + RF Kernel")
            if (carrierOk) add("CarrierConfig VoLTE/4G+")
            if (hyperOk) add(if (isRo) "Scut Ecran Stins" else "Screen-Off Shield")
            if (dualSimOk) add("Dual-SIM Failover")
        }
        return if (parts.isEmpty()) {
            "Standard Android"
        } else {
            parts.joinToString(" + ")
        }
    }

    private fun parseAndRecordModemAction(rawJson: String?, missingPrivilegeMsg: String): ModemActionResult {
        if (rawJson.isNullOrBlank()) {
            return ModemActionResult(false, missingPrivilegeMsg)
        }
        val isRo = ro.netstabil.app.i18n.I18n.currentIsRo
        val obj = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return ModemActionResult(
                false,
                if (isRo) "Răspuns nevalid de la serviciul modem." else "Invalid response from modem service."
            )
        val ok = obj.optBoolean("ok", false)
        val fallbackMsg = if (ok) {
            if (isRo) "Comandă aplicată." else "Command applied."
        } else {
            if (isRo) "Comanda a fost refuzată." else "Command was denied."
        }
        val msg = if (isRo) {
            obj.optString("msg", fallbackMsg)
        } else {
            obj.optString("msgEn", obj.optString("msg", fallbackMsg))
        }
        val backendStr = if (isRo) {
            obj.optString("backend", "")
        } else {
            obj.optString("backendEn", obj.optString("backend", ""))
        }

        _modemCapability.update { current ->
            current.copy(
                probed = true,
                activeBackendRo = backendStr.ifBlank { current.activeBackendRo },
                activeBandsSummary = if (obj.has("bandsSummary")) {
                    obj.optString("bandsSummary", "")
                } else {
                    current.activeBandsSummary
                },
                isCellLocked = if (obj.has("cellLocked")) {
                    obj.optBoolean("cellLocked", false)
                } else {
                    current.isCellLocked
                },
                lastActionMessageRo = msg
            )
        }
        return ModemActionResult(ok, msg)
    }

    private fun parseAidlResponse(raw: String): ShellResult {
        val newlineIdx = raw.indexOf('\n')
        if (!raw.startsWith("EXIT:") || newlineIdx < 0) {
            return ShellResult(false, 1, raw)
        }
        val code = raw.substring(5, newlineIdx).trim().toIntOrNull() ?: 1
        val body = raw.substring(newlineIdx + 1).trim()
        return ShellResult(code == 0, code, body)
    }

    private suspend fun grantPermanentSystemPermissionsInternal() {
        val pkg = BuildConfig.APPLICATION_ID
        runPrivilegedCommand(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; " +
                "cmd appops set $pkg RUN_IN_BACKGROUND allow 2>/dev/null; " +
                "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null; " +
                "cmd appops set $pkg WAKE_LOCK allow 2>/dev/null; " +
                "dumpsys deviceidle whitelist +$pkg 2>/dev/null; " +
                "cmd deviceidle except-idle-whitelist +$pkg 2>/dev/null",
            8
        )
    }

    fun isInVoiceCall(): Boolean {
        val inAudioCall = runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val mode = am.mode
            mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_RINGTONE
        }.getOrDefault(false)
        if (inAudioCall) return true

        return runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            tm.callState != TelephonyManager.CALL_STATE_IDLE
        }.getOrDefault(false)
    }

    /**
     * Reseteaza sesiunea de date mobile (fara sa intrerupa apelurile GSM/VoLTE),
     * compatibil Android 10-16 (svc data + fallback cmd phone data).
     */
    suspend fun resetMobileDataSession(): Boolean {
        if (isInVoiceCall()) return false
        isInternalRadioResetInProgress = true
        try {
            val disableRes = runPrivilegedCommand(
                "svc data disable 2>/dev/null || cmd phone data disable 2>/dev/null",
                6
            )
            if (!disableRes.success) return false
            delay(1800L)
            val enableRes = runPrivilegedCommand(
                "svc data enable 2>/dev/null || cmd phone data enable 2>/dev/null",
                6
            )
            return enableRes.success
        } finally {
            isInternalRadioResetInProgress = false
        }
    }

    suspend fun cycleAirplaneRadio(): Boolean {
        if (isInVoiceCall()) return false
        isInternalRadioResetInProgress = true
        try {
            val onCmd = "cmd connectivity airplane-mode enable 2>/dev/null || " +
                "(settings put global airplane_mode_on 1 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true)"
            val offCmd = "cmd connectivity airplane-mode disable 2>/dev/null || " +
                "(settings put global airplane_mode_on 0 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false)"

            val onRes = runPrivilegedCommand(onCmd, 8)
            if (!onRes.success) return false
            delay(2400L)
            val offRes = runPrivilegedCommand(offCmd, 8)
            return offRes.success
        } finally {
            isInternalRadioResetInProgress = false
        }
    }

    suspend fun disableWeakWifiAndSwitchToMobile(): Boolean {
        val res = runPrivilegedCommand(
            "(svc wifi disable || cmd wifi set-wifi-enabled disabled) && " +
                "(svc data enable || cmd phone data enable)",
            6
        )
        return res.success
    }

    suspend fun reassociateWifiSession(): Boolean {
        val res = runPrivilegedCommand(
            "cmd wifi force-hi-perf-mode enabled 2>/dev/null; " +
                "(svc wifi disable && sleep 1 && svc wifi enable) 2>/dev/null || " +
                "(cmd wifi set-wifi-enabled disabled && sleep 1 && cmd wifi set-wifi-enabled enabled) 2>/dev/null",
            8
        )
        return res.success
    }

    suspend fun disableWifiScanThrottling(): Boolean {
        if (_status.value.hasWriteSecureSettings) {
            val ok = runCatching {
                Settings.Global.putInt(context.contentResolver, "wifi_scan_throttle_enabled", 0)
            }.getOrDefault(false)
            if (ok) return true
        }
        return runPrivilegedCommand("settings put global wifi_scan_throttle_enabled 0", 5).success
    }

    suspend fun applyNativePrivateDns(provider: DnsProvider): Boolean {
        val expectedMode = if (provider == DnsProvider.CARRIER_DEFAULT) "opportunistic" else "hostname"
        val host = provider.dotHostname

        if (_status.value.hasWriteSecureSettings) {
            val directVerified = runCatching {
                val cr = context.contentResolver
                if (provider == DnsProvider.CARRIER_DEFAULT) {
                    Settings.Global.putString(cr, "private_dns_mode", "opportunistic")
                } else {
                    Settings.Global.putString(cr, "private_dns_specifier", host)
                    Settings.Global.putString(cr, "private_dns_mode", "hostname")
                }
                val readMode = Settings.Global.getString(cr, "private_dns_mode")
                val readSpec = Settings.Global.getString(cr, "private_dns_specifier") ?: ""
                readMode == expectedMode && (provider == DnsProvider.CARRIER_DEFAULT || readSpec == host)
            }.getOrDefault(false)
            if (directVerified) return true
        }

        val cmd = if (provider == DnsProvider.CARRIER_DEFAULT) {
            "settings put global private_dns_mode opportunistic"
        } else {
            "settings put global private_dns_specifier $host && settings put global private_dns_mode $expectedMode"
        }
        return runPrivilegedCommand(cmd, 6).success
    }

    fun getCurrentSystemPrivateDns(): String {
        val isRo = ro.netstabil.app.i18n.I18n.currentIsRo
        return runCatching {
            val cr = context.contentResolver
            val mode = Settings.Global.getString(cr, "private_dns_mode") ?: "unknown"
            val spec = Settings.Global.getString(cr, "private_dns_specifier") ?: ""
            when (mode) {
                "hostname" -> if (spec.isNotBlank()) {
                    if (isRo) "Activ ($spec)" else "Active ($spec)"
                } else {
                    if (isRo) "Activ" else "Active"
                }
                "off" -> if (isRo) "Oprit" else "Off"
                else -> if (isRo) "Automat (Operator)" else "Automatic (Carrier)"
            }
        }.getOrDefault("Standard")
    }

    suspend fun applyRootKernelTcpTuning(): Boolean {
        if (!_status.value.isRootAvailable) return false
        val script = """
            sysctl -w net.ipv4.tcp_keepalive_time=45 2>/dev/null
            sysctl -w net.ipv4.tcp_keepalive_intvl=10 2>/dev/null
            sysctl -w net.ipv4.tcp_keepalive_probes=4 2>/dev/null
            sysctl -w net.ipv4.tcp_fastopen=3 2>/dev/null
            if grep -q bbr /proc/sys/net/ipv4/tcp_available_congestion_control 2>/dev/null; then
                sysctl -w net.ipv4.tcp_congestion_control=bbr 2>/dev/null
            fi
        """.trimIndent()
        return runPrivilegedCommand(script, 6).success
    }

    companion object {
        const val KEY_DSRM_DURATION = "dsrm_duration_millis"
        const val KEY_DSRM_ACTIONS = "dsrm_enabled_actions"
        const val FAST_DSRM_DURATIONS = "12000,15000,20000,30000,0"
        const val FAST_DSRM_ACTIONS = "true,true,false,true,false"
        // MULTIPATH_PREFERENCE_HANDOVER (1) | RELIABILITY (2) | PERFORMANCE (4) = 7
        const val MULTIPATH_ALL_ENABLED = "7"
        const val MULTIPATH_DAILY_QUOTA_BYTES = "536870912" // 512 MiB/zi pentru migrare QUIC/HTTP3

        /**
         * Construieste expresia valida AOSP wifi_score_params monotona crescatoare
         * (bad <= entry <= low <= good) calibrata pe pragul dBm ales de utilizator.
         */
        fun buildWifiScoreParams(wifiCutoffDbm: Int): String {
            val bad2 = wifiCutoffDbm.coerceIn(-88, -72)
            val entry2 = (bad2 + 3).coerceAtMost(-69)
            val low2 = (entry2 + 6).coerceAtMost(-63)
            val good2 = -58

            val bad5 = (bad2 + 2).coerceIn(-86, -70)
            val entry5 = (bad5 + 3).coerceAtMost(-67)
            val low5 = (entry5 + 6).coerceAtMost(-61)
            val good5 = -56

            return "rssi2=$bad2:$entry2:$low2:$good2,rssi5=$bad5:$entry5:$low5:$good5"
        }

        private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9_.]+$")

        fun mergePackageIntoCsvWhitelist(existingCsv: String?, pkg: String): String {
            val cleanPkg = pkg.trim()
            if (cleanPkg.isEmpty() || !PACKAGE_NAME_REGEX.matches(cleanPkg)) {
                return existingCsv.orEmpty()
            }
            val items = existingCsv.orEmpty()
                .split(',')
                .map { it.trim() }
                .filter {
                    it.isNotEmpty() &&
                        !it.equals("null", ignoreCase = true) &&
                        PACKAGE_NAME_REGEX.matches(it)
                }
                .toMutableSet()
            items.add(cleanPkg)
            return items.joinToString(",")
        }

        fun removePackageFromCsvWhitelist(existingCsv: String?, pkg: String): String {
            val cleanPkg = pkg.trim()
            return existingCsv.orEmpty()
                .split(',')
                .map { it.trim() }
                .filter {
                    it.isNotEmpty() &&
                        !it.equals("null", ignoreCase = true) &&
                        PACKAGE_NAME_REGEX.matches(it) &&
                        it != cleanPkg
                }
                .distinct()
                .joinToString(",")
        }
    }
}
