package ro.netstabil.app.system

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import ro.netstabil.app.R
import ro.netstabil.app.privileged.PrivilegeManager
import ro.netstabil.app.tile.NetStabilTileService
import ro.netstabil.app.widget.NetStabilWidgetProvider
import java.util.Locale

data class DeviceProfileInfo(
    val brandTitle: String,
    val modelName: String,
    val androidVersionLabel: String,
    val isXiaomiHyperOsOrMiui: Boolean,
    val isMotorola: Boolean,
    val isMediaTekSoC: Boolean,
    val isBatteryOptimizationIgnored: Boolean
)

object DeviceOptimizationHelper {

    fun inspectDevice(context: Context): DeviceProfileInfo {
        val manufacturer = Build.MANUFACTURER ?: ""
        val brand = Build.BRAND ?: ""
        val model = Build.MODEL ?: "Android"
        val hardware = (Build.HARDWARE ?: "").lowercase(Locale.ROOT)
        val board = (Build.BOARD ?: "").lowercase(Locale.ROOT)
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (Build.SOC_MODEL ?: "").lowercase(Locale.ROOT)
        } else {
            ""
        }

        val isXiaomi = manufacturer.contains("xiaomi", true) ||
            brand.contains("redmi", true) ||
            brand.contains("poco", true) ||
            brand.contains("xiaomi", true)

        val isMotorola = manufacturer.contains("motorola", true) ||
            brand.contains("motorola", true)

        val isMtk = hardware.startsWith("mt") ||
            board.startsWith("mt") ||
            socModel.contains("mt") ||
            socModel.contains("helio") ||
            socModel.contains("dimensity")

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)

        return DeviceProfileInfo(
            brandTitle = manufacturer.replaceFirstChar { it.uppercase() },
            modelName = model,
            androidVersionLabel = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            isXiaomiHyperOsOrMiui = isXiaomi,
            isMotorola = isMotorola,
            isMediaTekSoC = isMtk,
            isBatteryOptimizationIgnored = ignoringBattery
        )
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            runCatching {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }
        }
    }

    /**
     * Deschide meniul AutoStart / PowerKeeper specific Xiaomi HyperOS 2 (Redmi Note 15 4G)
     * pentru ca sistemul sa nu opreasca serviciul de stabilizare.
     */
    fun openXiaomiAutoStartSettings(context: Context): Boolean {
        val candidates = listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
            )
        )
        for (comp in candidates) {
            val ok = runCatching {
                val intent = Intent().apply {
                    component = comp
                    putExtra("package_name", context.packageName)
                    putExtra("package_label", "NetStabil")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return openAppDetailsSettings(context)
    }

    fun openAppDetailsSettings(context: Context): Boolean {
        return runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * Deschide panoul rapid de conectivitate Android (util in Mod Normal pentru toggle cu 1 tap).
     */
    fun openInternetConnectivityPanel(context: Context) {
        runCatching {
            val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun openPrivateDnsSystemSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openDeveloperWirelessDebuggingSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun openShizukuOrPlayStore(context: Context) {
        val shizukuPkg = "moe.shizuku.privileged.api"
        val launchIntent = context.packageManager.getLaunchIntentForPackage(shizukuPkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launchIntent) }
        } else {
            runCatching {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=$shizukuPkg")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                runCatching {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://shizuku.rikka.app/download/")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    /**
     * Deschide meniul ascuns Android RadioInfo (*#*#4636#*#*) pentru setarea "LTE Only" (cand VoLTE este activ).
     * Foloseste Shizuku/Root daca HyperOS restrictioneaza lansarea directa.
     */
    suspend fun openHiddenRadioInfo(context: Context, privilegeManager: PrivilegeManager): Boolean {
        val components = listOf(
            ComponentName("com.android.phone", "com.android.phone.settings.RadioInfo"),
            ComponentName("com.android.settings", "com.android.settings.RadioInfo"),
            ComponentName("com.android.settings", "com.android.settings.TestingSettings")
        )
        for (comp in components) {
            val directOk = runCatching {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = comp
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (directOk) return true
        }

        val shellOk = privilegeManager.runPrivilegedCommand(
            "am start -n com.android.phone/.settings.RadioInfo 2>/dev/null || " +
                "am start -n com.android.settings/.TestingSettings 2>/dev/null",
            5
        ).success
        return shellOk
    }

    /**
     * Deschide meniul MediaTek EngineerMode (*#*#3646633#*#*) pe procesoarele Helio G100 Ultra si Helio G85.
     */
    suspend fun openMtkEngineerMode(context: Context, privilegeManager: PrivilegeManager): Boolean {
        val comp = ComponentName(
            "com.mediatek.engineermode",
            "com.mediatek.engineermode.EngineerMode"
        )
        val directOk = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                component = comp
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (directOk) return true

        return privilegeManager.runPrivilegedCommand(
            "am start -n com.mediatek.engineermode/.EngineerMode",
            5
        ).success
    }

    /**
     * Deschide direct sub-meniul de blocare banda LTE (BandSelect: B20 / B3 / B1 / B7) pe MediaTek.
     * Pe HyperOS 2 (Android 15), cand Shizuku este activ, sare peste restrictia din Dialer.
     */
    suspend fun openMtkBandSelect(context: Context, privilegeManager: PrivilegeManager): Boolean {
        val bandComp = ComponentName(
            "com.mediatek.engineermode",
            "com.mediatek.engineermode.bandselect.BandSelect"
        )
        val directOk = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                component = bandComp
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (directOk) return true

        val privOk = privilegeManager.runPrivilegedCommand(
            "am start -n com.mediatek.engineermode/.bandselect.BandSelect 2>/dev/null",
            5
        ).success
        if (privOk) return true

        return openMtkEngineerMode(context, privilegeManager)
    }

    /**
     * Deschide direct ecranul Nume Puncte de Acces (APN) pentru setarea Bearer = LTE si Protocol IPv4 / IPv4v6.
     */
    fun openApnSettings(context: Context): Boolean {
        val actions = listOf(
            Settings.ACTION_APN_SETTINGS,
            Settings.ACTION_DATA_ROAMING_SETTINGS,
            Settings.ACTION_WIRELESS_SETTINGS
        )
        for (act in actions) {
            val ok = runCatching {
                context.startActivity(Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /**
     * Copiaza un cod secret Android/Xiaomi in clipboard si deschide tastatura telefonului
     * (deoarece Android blocheaza pre-completarea automata a secventelor *#*# prin tel:).
     */
    fun copySecretCodeAndOpenDialer(context: Context, secretCode: String): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Cod Radio NetStabil", secretCode))
            context.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    /**
     * Cere launcher-ului (HyperOS / Motorola MyUX) fixarea directa a Widget-ului minimalist NetStabil pe ecran.
     */
    fun requestPinHomeWidget(context: Context): Boolean {
        return runCatching {
            val mgr = AppWidgetManager.getInstance(context)
            if (!mgr.isRequestPinAppWidgetSupported) return false
            val provider = ComponentName(context, NetStabilWidgetProvider::class.java)
            mgr.requestPinAppWidget(provider, null, null)
        }.getOrDefault(false)
    }

    /**
     * Pe Android 13+ (ex. Redmi Note 15 4G cu Android 15), deschide dialogul oficial de sistem
     * pentru adaugarea imediata a butonului NetStabil in bara de sus (Quick Settings).
     * Pe Android 10-12 (Moto G31), returneaza ghidul scurt pentru adaugare manuala.
     */
    fun requestAddQuickSettingsTile(context: Context, onFeedback: (String) -> Unit) {
        val isRo = ro.netstabil.app.i18n.I18n.currentIsRo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val sbm = context.getSystemService(StatusBarManager::class.java)
            if (sbm != null) {
                runCatching {
                    sbm.requestAddTileService(
                        ComponentName(context, NetStabilTileService::class.java),
                        context.getString(R.string.tile_label),
                        Icon.createWithResource(context, R.drawable.ic_stat_signal),
                        context.mainExecutor
                    ) { resultCode ->
                        val msg = when (resultCode) {
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                                if (isRo) "Butonul NetStabil a fost adăugat în bara de sus."
                                else "NetStabil Quick Tile added to Quick Settings."
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                                if (isRo) "Butonul NetStabil există deja în bara de sus."
                                else "NetStabil Quick Tile is already in Quick Settings."
                            else ->
                                if (isRo) "Puteți adăuga oricând butonul și din editarea barei de sus."
                                else "You can add the tile anytime by editing Quick Settings."
                        }
                        onFeedback(msg)
                    }
                }.onFailure {
                    onFeedback(
                        if (isRo) "Trageți bara de sus în jos -> Editare -> adăugați butonul NetStabil."
                        else "Pull down Quick Settings -> Edit -> add the NetStabil tile."
                    )
                }
                return
            }
        }
        onFeedback(
            if (isRo) "Trageți bara de sus în jos -> apăsați creionul de Editare -> trageți butonul NetStabil."
            else "Pull down Quick Settings -> tap the Edit pencil -> drag the NetStabil tile."
        )
    }
}
