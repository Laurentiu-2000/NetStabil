package ro.netstabil.app.privileged

import android.annotation.SuppressLint
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.PersistableBundle
import android.os.Process
import android.telephony.AccessNetworkConstants.AccessNetworkType
import android.telephony.CarrierConfigManager
import android.telephony.RadioAccessSpecifier
import android.telephony.TelephonyManager
import androidx.annotation.Keep
import dalvik.system.PathClassLoader
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Punte hardware directa catre modemul celular executata in context privilegiat
 * (Shizuku UID 2000 ADB Shell sau Root UID 0).
 *
 * Combina 3 mecanisme reale Android / MediaTek:
 * 1. AOSP ITelephony (setAllowedNetworkTypesForReason / setPreferredNetworkType) pentru blocare
 *    directa Doar 4G LTE fara meniul ascuns *#*#4636#*#*.
 * 2. Passthrough MediaTek RIL AT (phoneEx / IMtkTelephonyEx.sendAtCmd sau TTY Root) pentru
 *    blocare benzi LTE (AT+EPBSE / AT+EPBSEH) si blocare stalp PCI/EARFCN (AT+EMMCHLCK) pe
 *    procesoare Helio G100 Ultra (Redmi Note 15 4G) si Helio G85 (Moto G31).
 * 3. Fallback Android Radio HAL (ITelephony.setSystemSelectionChannels) pentru modemuri AOSP.
 */
@Keep
object ModemHardwareBridge {

    private const val REASON_USER = 0
    private const val LEGACY_MODE_LTE_ONLY = 11
    private const val LEGACY_MODE_LTE_GSM_WCDMA = 9
    private const val LEGACY_MODE_NR_LTE = 23

    // Formula bitmask AOSP: 1L shl (NETWORK_TYPE_* - 1), compatibila API 29..36 fara deprecari
    private const val BIT_GPRS = 1L shl (TelephonyManager.NETWORK_TYPE_GPRS - 1)       // 1
    private const val BIT_EDGE = 1L shl (TelephonyManager.NETWORK_TYPE_EDGE - 1)       // 2
    private const val BIT_UMTS = 1L shl (TelephonyManager.NETWORK_TYPE_UMTS - 1)       // 4
    private const val BIT_HSDPA = 1L shl (TelephonyManager.NETWORK_TYPE_HSDPA - 1)     // 128
    private const val BIT_HSUPA = 1L shl (TelephonyManager.NETWORK_TYPE_HSUPA - 1)     // 256
    private const val BIT_HSPA = 1L shl (TelephonyManager.NETWORK_TYPE_HSPA - 1)       // 512
    private const val BIT_LTE = 1L shl (TelephonyManager.NETWORK_TYPE_LTE - 1)         // 4096
    private const val BIT_HSPAP = 1L shl (TelephonyManager.NETWORK_TYPE_HSPAP - 1)     // 16384
    private const val BIT_GSM = 1L shl (TelephonyManager.NETWORK_TYPE_GSM - 1)         // 32768
    private const val BIT_TD_SCDMA = 1L shl (TelephonyManager.NETWORK_TYPE_TD_SCDMA - 1) // 65536
    private const val BIT_LTE_CA = 1L shl (19 - 1)                                     // 262144
    private const val BIT_NR = 1L shl (TelephonyManager.NETWORK_TYPE_NR - 1)           // 524288

    private const val RAF_2G: Long = BIT_GPRS or BIT_EDGE or BIT_GSM
    private const val RAF_3G: Long = BIT_UMTS or BIT_HSDPA or BIT_HSUPA or BIT_HSPA or BIT_HSPAP or BIT_TD_SCDMA
    private const val RAF_4G: Long = BIT_LTE or BIT_LTE_CA
    private const val RAF_5G: Long = BIT_NR

    private val ROOT_TTY_CANDIDATES = listOf(
        "/dev/radio/pttycmd1",
        "/dev/radio/atci1",
        "/dev/ttyC0"
    )

    private val FRAMEWORK_DIRS = listOf(
        "/system/framework",
        "/system_ext/framework",
        "/product/framework",
        "/vendor/framework"
    )

    private val JAR_NAME_REGEX = Regex("mediatek|mtk|telephony|oem|radio", RegexOption.IGNORE_CASE)

    private val ioPool = Executors.newCachedThreadPool { r ->
        Thread(r, "netstabil-modem-bridge").apply { isDaemon = true }
    }

    private class AtChannel(
        val nameRo: String,
        val send: (slotId: Int, atCmd: String, timeoutMs: Long) -> String?
    )

    @Volatile
    private var cachedAtChannel: AtChannel? = null

    private val vendorClassLoaders: List<ClassLoader> by lazy {
        val base = ModemHardwareBridge::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
        val jars = FRAMEWORK_DIRS
            .flatMap { dir -> File(dir).listFiles()?.toList().orEmpty() }
            .filter { it.name.endsWith(".jar") && JAR_NAME_REGEX.containsMatchIn(it.name) }
        listOf(base) + jars.mapNotNull { jar ->
            runCatching { PathClassLoader(jar.absolutePath, base) }.getOrNull()
        }
    }

    private val callingPkg: String
        get() = if (Process.myUid() == 0) "root" else "com.android.shell"

    /**
     * Codificare pura (testabila unitar) a listei de benzi 3GPP LTE (1..64) in cuvinte de 32 biti
     * fara semn conform specificatiei MediaTek +EPBSE.
     */
    fun encodeLteBandWords(bands: List<Int>, wordCount: Int = 8): LongArray {
        val out = LongArray(wordCount)
        for (b in bands) {
            if (b < 1) continue
            val wIdx = (b - 1) / 32
            val bitIdx = (b - 1) % 32
            if (wIdx in 0 until wordCount) {
                out[wIdx] = (out[wIdx] or (1L shl bitIdx)) and 0xFFFFFFFFL
            }
        }
        return out
    }

    /**
     * Decodificare pura (testabila unitar) a cuvintelor +EPBSE in numere de benzi 3GPP LTE (1..256).
     */
    fun decodeLteBandWords(words: LongArray): List<Int> {
        val result = ArrayList<Int>()
        for (wIdx in words.indices) {
            val w = words[wIdx] and 0xFFFFFFFFL
            if (w == 0L) continue
            for (bit in 0 until 32) {
                if ((w and (1L shl bit)) != 0L) {
                    result.add(wIdx * 32 + bit + 1)
                }
            }
        }
        return result
    }

    fun probeCapabilitiesJson(rawSubId: Int, rawSlot: Int): String {
        val slot = rawSlot.coerceAtLeast(0)
        val subId = resolveEffectiveSubId(rawSubId, slot)
        val phone = getSystemAidlInterface("phone", "com.android.internal.telephony.ITelephony")
        val hasRat = phone != null && (
            findCompatibleMethod(phone, "setAllowedNetworkTypesForReason", 3) != null ||
                findCompatibleMethod(phone, "setPreferredNetworkType", 2) != null
            )
        val hasHal = phone != null &&
            findCompatibleMethod(phone, "setSystemSelectionChannels", 3) != null

        val atChannel = discoverWorkingAtChannel(slot)
        var activeBandsStr = ""
        var cellLocked = false

        if (atChannel != null) {
            val epbse = readCurrentOrSupportedEpbse(atChannel, slot, querySupported = false)
            if (epbse != null) {
                val lteBands = decodeLteBandWords(epbse.words.copyOfRange(2, epbse.words.size))
                if (lteBands.isNotEmpty()) {
                    activeBandsStr = lteBands.joinToString(", ") { "B$it" }
                }
            }
            val lockReply = atChannel.send(slot, "AT+EMMCHLCK?", 2500L).orEmpty()
            val lockLine = lockReply.lineSequence().firstOrNull { it.trim().startsWith("+EMMCHLCK:") }
            if (lockLine != null) {
                val onFlag = lockLine.substringAfter("+EMMCHLCK:").split(',').firstOrNull()?.trim()
                cellLocked = (onFlag == "1")
            }
        }

        val backendLabel = when {
            atChannel != null -> atChannel.nameRo
            hasHal -> "Android Radio HAL"
            hasRat -> "AOSP ITelephony"
            else -> "Limitat de firmware"
        }
        val backendLabelEn = when {
            atChannel != null -> atChannel.nameRo
            hasHal -> "Android Radio HAL"
            hasRat -> "AOSP ITelephony"
            else -> "Firmware-restricted"
        }

        return JSONObject()
            .put("ok", hasRat || atChannel != null || hasHal)
            .put("backend", backendLabel)
            .put("backendEn", backendLabelEn)
            .put("hasDirectRat", hasRat || atChannel != null)
            .put("hasMtkAt", atChannel != null)
            .put("hasHalLock", hasHal)
            .put("bandsSummary", activeBandsStr)
            .put("cellLocked", cellLocked)
            .put(
                "msg",
                if (atChannel != null) {
                    "Modem MediaTek conectat direct ($backendLabel)."
                } else if (hasRat) {
                    "Control rețea activ prin $backendLabel."
                } else {
                    "Serviciul telefonic nu a răspuns."
                }
            )
            .put(
                "msgEn",
                if (atChannel != null) {
                    "MediaTek modem connected directly ($backendLabelEn)."
                } else if (hasRat) {
                    "Network control active via $backendLabelEn."
                } else {
                    "Telephony service did not respond."
                }
            )
            .toString()
    }

    fun setPreferredRatModeJson(rawSubId: Int, rawSlot: Int, modeCode: String): String {
        val slot = rawSlot.coerceAtLeast(0)
        val subId = resolveEffectiveSubId(rawSubId, slot)
        val phone = getSystemAidlInterface("phone", "com.android.internal.telephony.ITelephony")

        val wantedMask = when (modeCode) {
            "LTE_ONLY" -> RAF_4G
            "LTE_NR" -> RAF_4G or RAF_5G
            else -> RAF_2G or RAF_3G or RAF_4G or RAF_5G
        }
        val legacyMode = when (modeCode) {
            "LTE_ONLY" -> LEGACY_MODE_LTE_ONLY
            "LTE_NR" -> LEGACY_MODE_NR_LTE
            else -> LEGACY_MODE_LTE_GSM_WCDMA
        }
        val modeTitleRo = when (modeCode) {
            "LTE_ONLY" -> "Doar 4G LTE"
            "LTE_NR" -> "Doar 4G/5G (fără 2G/3G)"
            else -> "Automat 4G/3G/2G"
        }
        val modeTitleEn = when (modeCode) {
            "LTE_ONLY" -> "4G LTE Only"
            "LTE_NR" -> "4G/5G Only (no 2G/3G)"
            else -> "Auto 4G/3G/2G"
        }

        var appliedVia = ""
        if (phone != null && subId >= 0) {
            val supportedRaf = runCatching {
                (invokeTelephony(phone, "getRadioAccessFamily", slot, callingPkg) as? Number)?.toLong()
            }.getOrNull()?.takeIf { it > 0L }

            val candidateMasks = buildList {
                if (modeCode != "LTE_ONLY" && modeCode != "LTE_NR" && supportedRaf != null) {
                    add(supportedRaf)
                }
                supportedRaf?.let { wantedMask and it }?.takeIf { it > 0L }?.let { add(it) }
                add(wantedMask)
            }.distinct()

            for (mask in candidateMasks) {
                val ok = runCatching {
                    when {
                        findCompatibleMethod(phone, "setAllowedNetworkTypesForReason", 3) != null ->
                            invokeBooleanOrVoidTelephony(phone, "setAllowedNetworkTypesForReason", subId, REASON_USER, mask)
                        findCompatibleMethod(phone, "setAllowedNetworkTypes", 2) != null ->
                            invokeBooleanOrVoidTelephony(phone, "setAllowedNetworkTypes", subId, mask)
                        findCompatibleMethod(phone, "setPreferredNetworkType", 2) != null ->
                            invokeBooleanOrVoidTelephony(phone, "setPreferredNetworkType", subId, legacyMode)
                        else -> false
                    }
                }.getOrDefault(false)

                if (ok) {
                    appliedVia = "AOSP ITelephony"
                    break
                }
            }
        }

        if (appliedVia.isEmpty()) {
            val atChannel = discoverWorkingAtChannel(slot)
            if (atChannel != null) {
                val eratCmd = when (modeCode) {
                    "LTE_ONLY" -> "AT+ERAT=3,0"
                    "LTE_NR" -> "AT+ERAT=19,128"
                    else -> "AT+ERAT=6,4"
                }
                val reply = atChannel.send(slot, eratCmd, 4000L).orEmpty()
                if (isAtOk(reply)) {
                    appliedVia = atChannel.nameRo
                }
            }
        }

        return if (appliedVia.isNotEmpty()) {
            JSONObject()
                .put("ok", true)
                .put("backend", appliedVia)
                .put("backendEn", appliedVia)
                .put("msg", "Mod rețea aplicat direct: $modeTitleRo ($appliedVia).")
                .put("msgEn", "Network mode applied directly: $modeTitleEn ($appliedVia).")
                .toString()
        } else {
            JSONObject()
                .put("ok", false)
                .put("backend", "Refuzat")
                .put("backendEn", "Denied")
                .put(
                    "msg",
                    "Modemul a refuzat comutarea directă în $modeTitleRo. Folosiți butonul RadioInfo de mai jos."
                )
                .put(
                    "msgEn",
                    "Modem refused direct switch to $modeTitleEn. Use the RadioInfo button below."
                )
                .toString()
        }
    }

    fun lockLteBandsJson(rawSubId: Int, rawSlot: Int, requestedBands: IntArray): String {
        val cleanBands = requestedBands.filter { it in 1..66 }.distinct().sorted()
        if (cleanBands.isEmpty()) {
            return JSONObject()
                .put("ok", false)
                .put("msg", "Selectați cel puțin o bandă 4G (ex. B3 sau B20).")
                .put("msgEn", "Select at least one 4G band (e.g. B3 or B20).")
                .toString()
        }

        val slot = rawSlot.coerceAtLeast(0)
        val subId = resolveEffectiveSubId(rawSubId, slot)
        val atChannel = discoverWorkingAtChannel(slot)

        // Calea 1: MediaTek AT+EPBSE / AT+EPBSEH (precizie hardware nativa pe Helio G100 Ultra / G85)
        if (atChannel != null) {
            val mtkResult = applyMtkBandLock(atChannel, slot, cleanBands)
            if (mtkResult != null) return mtkResult.toString()
        }

        // Calea 2: Fallback Android Radio HAL setSystemSelectionChannels
        val halOk = applyHalChannelSelection(
            subId = subId,
            bands = cleanBands.toIntArray(),
            channels = IntArray(0)
        )
        if (halOk) {
            val label = cleanBands.joinToString(", ") { "B$it" }
            return JSONObject()
                .put("ok", true)
                .put("backend", "Android Radio HAL")
                .put("backendEn", "Android Radio HAL")
                .put("bandsSummary", label)
                .put("msg", "Benzi 4G blocate prin Radio HAL: $label.")
                .put("msgEn", "4G bands locked via Radio HAL: $label.")
                .toString()
        }

        val hw = (android.os.Build.HARDWARE ?: "").lowercase()
        val hint = if (hw.startsWith("mt")) {
            "Firmware-ul modemului nu a acceptat blocarea benzii prin Shizuku fără Root. Încercați butonul BandSelect MTK."
        } else {
            "Firmware-ul acestui modem restricționează blocarea benzilor fără Root."
        }
        val hintEn = if (hw.startsWith("mt")) {
            "Modem firmware denied band locking over rootless Shizuku. Try the MTK BandSelect button."
        } else {
            "This modem firmware restricts LTE band locking without Root."
        }
        return JSONObject()
            .put("ok", false)
            .put("msg", hint)
            .put("msgEn", hintEn)
            .toString()
    }

    fun lockLteCellJson(rawSubId: Int, rawSlot: Int, earfcn: Int, pci: Int): String {
        if (earfcn <= 0) {
            return JSONObject()
                .put("ok", false)
                .put("msg", "EARFCN invalid - așteptați citirea celulei 4G active.")
                .put("msgEn", "Invalid EARFCN - wait for active 4G serving cell telemetry.")
                .toString()
        }
        val slot = rawSlot.coerceAtLeast(0)
        val subId = resolveEffectiveSubId(rawSubId, slot)
        val atChannel = discoverWorkingAtChannel(slot)

        if (atChannel != null) {
            val cmdWithPci = if (pci in 0..503) {
                "AT+EMMCHLCK=1,7,0,$earfcn,$pci,3"
            } else {
                "AT+EMMCHLCK=1,7,0,$earfcn"
            }
            var reply = atChannel.send(slot, cmdWithPci, 4500L).orEmpty()
            if (!isAtOk(reply) && pci in 0..503) {
                // Unele versiuni de firmware MediaTek accepta doar blocarea canalului EARFCN fara PCI
                reply = atChannel.send(slot, "AT+EMMCHLCK=1,7,0,$earfcn", 4500L).orEmpty()
            }
            if (isAtOk(reply)) {
                val targetDesc = if (pci in 0..503) "EARFCN $earfcn / PCI $pci" else "EARFCN $earfcn"
                return JSONObject()
                    .put("ok", true)
                    .put("backend", atChannel.nameRo)
                    .put("backendEn", atChannel.nameRo)
                    .put("cellLocked", true)
                    .put("msg", "Stâlp 4G blocat direct pe $targetDesc.")
                    .put("msgEn", "4G cell tower locked directly onto $targetDesc.")
                    .toString()
            }
        }

        val halOk = applyHalChannelSelection(
            subId = subId,
            bands = IntArray(0),
            channels = intArrayOf(earfcn)
        )
        if (halOk) {
            return JSONObject()
                .put("ok", true)
                .put("backend", "Android Radio HAL")
                .put("backendEn", "Android Radio HAL")
                .put("cellLocked", true)
                .put("msg", "Frecvența EARFCN $earfcn a fost fixată prin Radio HAL.")
                .put("msgEn", "EARFCN $earfcn channel locked via Radio HAL.")
                .toString()
        }

        return JSONObject()
            .put("ok", false)
            .put(
                "msg",
                "Blocarea directă pe stâlp (PCI/EARFCN) este dezactivată în firmware-ul acestui modem fără Root."
            )
            .put(
                "msgEn",
                "Direct cell tower lock (PCI/EARFCN) is disabled in this modem firmware without Root."
            )
            .toString()
    }

    fun unlockAllModemLocksJson(rawSubId: Int, rawSlot: Int): String {
        val slot = rawSlot.coerceAtLeast(0)
        val subId = resolveEffectiveSubId(rawSubId, slot)
        val actionsDone = ArrayList<String>()
        val actionsDoneEn = ArrayList<String>()

        val atChannel = discoverWorkingAtChannel(slot)
        if (atChannel != null) {
            val cellUnlock = atChannel.send(slot, "AT+EMMCHLCK=0", 3500L).orEmpty()
            if (isAtOk(cellUnlock)) {
                actionsDone.add("stâlp deblocat")
                actionsDoneEn.add("cell unlocked")
            }
            val restoredBands = applyMtkBandLock(atChannel, slot, emptyList())
            if (restoredBands?.optBoolean("ok") == true) {
                actionsDone.add("toate benzile restaurate")
                actionsDoneEn.add("all bands restored")
            }
        }

        if (applyHalChannelSelection(subId, IntArray(0), IntArray(0))) {
            actionsDone.add("canale HAL resetate")
            actionsDoneEn.add("HAL channels reset")
        }

        val ratReset = JSONObject(setPreferredRatModeJson(subId, slot, "AUTO"))
        if (ratReset.optBoolean("ok")) {
            actionsDone.add("mod Automat 4G/3G/2G activat")
            actionsDoneEn.add("Auto 4G/3G/2G enabled")
        }

        val ok = actionsDone.isNotEmpty()
        val summary = if (ok) {
            "Modem readus la valorile implicite (${actionsDone.joinToString(", ")})."
        } else {
            "Nu s-au putut aplica comenzi de deblocare (verificați conexiunea Shizuku)."
        }
        val summaryEn = if (ok) {
            "Modem restored to defaults (${actionsDoneEn.joinToString(", ")})."
        } else {
            "Could not apply unlock commands (verify Shizuku connection)."
        }

        return JSONObject()
            .put("ok", ok)
            .put("cellLocked", false)
            .put("bandsSummary", "")
            .put("msg", summary)
            .put("msgEn", summaryEn)
            .toString()
    }

    /**
     * Suprascrie in memorie (persistent=false pentru compatibilitate sigura cu Android 16 QPR / HyperOS)
     * profilul CarrierConfig pe cartela SIM activa:
     * - Activeaza VoLTE + VoWiFi (Apel Wi-Fi) + meniul 4G Imbunatatit
     * - Dezactiveaza preferinta 2G (prefer_2g_bool = false)
     * - Deblocheaza pragul 4G+ LTE_CA (lte_plus_threshold_bandwidth_khz_int = 1)
     * - Scurteaza timpii interni de recuperare DataStall pe SIM
     */
    fun applyCarrierConfigBoostJson(rawSubId: Int, rawSlot: Int, enable: Boolean): String {
        val slot = rawSlot.coerceAtLeast(0)
        val primarySubId = resolveEffectiveSubId(rawSubId, slot)
        val allSubs = discoverAllActiveSubIds(primarySubId, slot)
        if (allSubs.isEmpty()) {
            return JSONObject()
                .put("ok", false)
                .put("msg", "Niciun abonament SIM activ detectat pentru CarrierConfig.")
                .toString()
        }

        val loader = getSystemAidlInterface(
            "carrier_config",
            "com.android.internal.telephony.ICarrierConfigLoader"
        ) ?: return JSONObject()
            .put("ok", false)
            .put("msg", "Serviciul ICarrierConfigLoader este indisponibil pe acest firmware.")
            .toString()

        val bundle: PersistableBundle? = if (enable) {
            PersistableBundle().apply {
                putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, true)
                putBoolean("carrier_volte_provisioned_bool", true)
                putBoolean("editable_enhanced_4g_lte_bool", true)
                putBoolean("enhanced_4g_lte_on_by_default_bool", true)
                putBoolean("hide_enhanced_4g_lte_bool", false)
                putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, true)
                putBoolean("editable_wfc_mode_bool", true)
                putBoolean(CarrierConfigManager.KEY_PREFER_2G_BOOL, false)
                putBoolean("hide_lte_plus_data_icon_bool", false)
                putInt("lte_plus_threshold_bandwidth_khz_int", 1)
            }
        } else {
            null
        }

        val override3 = loader.javaClass.methods.firstOrNull { m ->
            m.name == "overrideConfig" &&
                m.parameterTypes.size == 3 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                PersistableBundle::class.java.isAssignableFrom(m.parameterTypes[1]) &&
                m.parameterTypes[2] == Boolean::class.javaPrimitiveType
        }
        val override2 = loader.javaClass.methods.firstOrNull { m ->
            m.name == "overrideConfig" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                PersistableBundle::class.java.isAssignableFrom(m.parameterTypes[1])
        }
        val getConfig3 = loader.javaClass.methods.firstOrNull { m ->
            m.name == "getConfigForSubIdWithFeature" &&
                m.parameterTypes.size == 3 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        val getConfig2 = loader.javaClass.methods.firstOrNull { m ->
            m.name == "getConfigForSubId" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType
        }

        if (override3 == null && override2 == null) {
            return JSONObject()
                .put("ok", false)
                .put("msg", "Metoda overrideConfig nu este expusă pe acest dispozitiv.")
                .put("msgEn", "overrideConfig method is not exposed on this device.")
                .toString()
        }

        var appliedCount = 0
        for (targetSub in allSubs) {
            // Verificare idempotenta in memoria com.android.phone: daca profilul este deja activ pe acest subId,
            // nu reapelam overrideConfig pentru a nu reseta inutil sesiunea activa de date mobile 4G!
            if (enable) {
                val alreadyActive = runCatching {
                    val current = when {
                        getConfig3 != null -> getConfig3.invoke(loader, targetSub, "com.android.shell", null) as? PersistableBundle
                        getConfig2 != null -> getConfig2.invoke(loader, targetSub, "com.android.shell") as? PersistableBundle
                        else -> null
                    }
                    current != null &&
                        current.getInt("lte_plus_threshold_bandwidth_khz_int", -1) == 1 &&
                        !current.getBoolean(CarrierConfigManager.KEY_PREFER_2G_BOOL, true) &&
                        current.getBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, false)
                }.getOrDefault(false)

                if (alreadyActive) {
                    appliedCount += 1
                    continue
                }
            }

            val ok = runCatching {
                if (override3 != null) {
                    // CRITIC Android 16 (patch ianuarie 2026+): persistent=false previne exceptia asincrona din phone.
                    // overrideConfig trimite deja singur broadcastConfigChangedIntent in CarrierConfigLoader (fara apel dublu).
                    override3.invoke(loader, targetSub, bundle, false)
                } else {
                    override2?.invoke(loader, targetSub, bundle)
                }
                true
            }.getOrDefault(false)
            if (ok) appliedCount += 1
        }

        val success = appliedCount > 0
        val msg = when {
            success && enable ->
                "Profil CarrierConfig optimizat pe $appliedCount SIM (VoLTE/VoWiFi + 4G+ + Anti-2G)."
            success && !enable ->
                "Profilul CarrierConfig a revenit la valorile implicite ale operatorului."
            else ->
                "Sistemul nu a permis suprascrierea CarrierConfig pe acest SIM."
        }
        val msgEn = when {
            success && enable ->
                "CarrierConfig profile optimized on $appliedCount SIM(s) (VoLTE/VoWiFi + 4G+ + Anti-2G)."
            success && !enable ->
                "CarrierConfig profile restored to carrier defaults."
            else ->
                "The OS did not permit overriding CarrierConfig on this SIM."
        }
        return JSONObject()
            .put("ok", success)
            .put("subCount", appliedCount)
            .put("msg", msg)
            .put("msgEn", msgEn)
            .toString()
    }

    /**
     * Activeaza sau dezactiveaza comutarea automata de urgenta a datelor intre SIM 1 si SIM 2
     * (MOBILE_DATA_POLICY_AUTO_DATA_SWITCH + DATA_ON_NON_DEFAULT_DURING_VOICE_CALL pe Android 13-16,
     * cu fallback setDataAllowedDuringVoiceCall pe Android 11-12).
     */
    fun setDualSimAutoSwitchJson(enable: Boolean): String {
        val phone = getSystemAidlInterface("phone", "com.android.internal.telephony.ITelephony")
            ?: return JSONObject()
                .put("ok", false)
                .put("msg", "Serviciul ITelephony este indisponibil.")
                .put("msgEn", "ITelephony service is unavailable.")
                .toString()

        val allSubs = discoverAllActiveSubIds(-1, 0)
        if (allSubs.isEmpty()) {
            return JSONObject()
                .put("ok", false)
                .put("msg", "Niciun SIM activ detectat.")
                .put("msgEn", "No active SIM detected.")
                .toString()
        }

        val policyDuringCall = runCatching {
            TelephonyManager::class.java
                .getDeclaredField("MOBILE_DATA_POLICY_DATA_ON_NON_DEFAULT_DURING_VOICE_CALL")
                .apply { isAccessible = true }
                .getInt(null)
        }.getOrDefault(1)

        val policyAutoSwitch = runCatching {
            TelephonyManager::class.java
                .getDeclaredField("MOBILE_DATA_POLICY_AUTO_DATA_SWITCH")
                .apply { isAccessible = true }
                .getInt(null)
        }.getOrDefault(3)

        val policyMethod = phone.javaClass.methods.firstOrNull { m ->
            (m.name == "setMobileDataPolicyEnabledStatus" || m.name == "setMobileDataPolicyEnabled") &&
                m.parameterTypes.size >= 3 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType &&
                m.parameterTypes[2] == Boolean::class.javaPrimitiveType
        }

        val legacyVoiceDataMethod = phone.javaClass.methods.firstOrNull { m ->
            m.name == "setDataAllowedDuringVoiceCall" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                m.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }

        var updatedSubs = 0
        for (sub in allSubs) {
            var subOk = false
            if (policyMethod != null) {
                for (pol in listOf(policyDuringCall, policyAutoSwitch)) {
                    val ok = runCatching {
                        invokeTelephony(phone, policyMethod.name, sub, pol, enable)
                        true
                    }.getOrDefault(false)
                    if (ok) subOk = true
                }
            } else if (legacyVoiceDataMethod != null) {
                subOk = runCatching {
                    legacyVoiceDataMethod.invoke(phone, sub, enable) as? Boolean ?: true
                }.getOrDefault(false)
            }
            if (subOk) updatedSubs += 1
        }

        val ok = updatedSubs > 0
        val msg = when {
            ok && enable ->
                "Comutare automată de urgență Dual-SIM activată pe $updatedSubs slot(uri) SIM."
            ok && !enable ->
                "Comutarea automată Dual-SIM a fost readusă la implicit."
            else ->
                "Politica Dual-SIM Auto-Switch nu este suportată pe această versiune Android."
        }
        val msgEn = when {
            ok && enable ->
                "Emergency Dual-SIM Auto-Switch enabled on $updatedSubs SIM slot(s)."
            ok && !enable ->
                "Dual-SIM Auto-Switch restored to default."
            else ->
                "Dual-SIM Auto-Switch policy is not supported on this Android version."
        }
        return JSONObject()
            .put("ok", ok)
            .put("subCount", updatedSubs)
            .put("msg", msg)
            .put("msgEn", msgEn)
            .toString()
    }

    // --- Implementare interna MediaTek +EPBSE / +EPBSEH ---

    private class EpbseSnapshot(
        val words: LongArray,
        val fieldCount: Int,
        val fromHexCommand: Boolean,
        val nrHexRaw: String = ""
    )

    private fun readCurrentOrSupportedEpbse(
        channel: AtChannel,
        slot: Int,
        querySupported: Boolean
    ): EpbseSnapshot? {
        val decCmd = if (querySupported) "AT+EPBSE=?" else "AT+EPBSE?"
        val decReply = channel.send(slot, decCmd, 3500L).orEmpty()
        val decLine = decReply.lineSequence().firstOrNull { it.trim().startsWith("+EPBSE:") }
        if (decLine != null) {
            val parts = decLine.substringAfter("+EPBSE:").split(',').map { it.trim() }
            val words = LongArray(10) { idx -> parts.getOrNull(idx)?.toLongOrNull() ?: 0L }
            return EpbseSnapshot(words, parts.size, fromHexCommand = false)
        }

        val hexCmd = if (querySupported) "AT+EPBSEH=?" else "AT+EPBSEH?"
        val hexReply = channel.send(slot, hexCmd, 3500L).orEmpty()
        val hexLine = hexReply.lineSequence().firstOrNull { it.trim().startsWith("+EPBSEH:") }
        if (hexLine != null) {
            val fields = hexLine.substringAfter("+EPBSEH:").split(',').map { it.trim().trim('"') }
            if (fields.size >= 3) {
                val words = LongArray(10)
                words[0] = fields[0].take(8).padEnd(8, '0').toLongOrNull(16) ?: 0L
                words[1] = fields[1].take(8).padEnd(8, '0').toLongOrNull(16) ?: 0L
                val lteChunks = fields[2].chunked(8)
                for (i in 0 until minOf(lteChunks.size, 8)) {
                    words[2 + i] = lteChunks[i].padEnd(8, '0').toLongOrNull(16) ?: 0L
                }
                val nrPart = fields.getOrNull(3).orEmpty()
                return EpbseSnapshot(words, 2 + lteChunks.size, fromHexCommand = true, nrHexRaw = nrPart)
            }
        }
        return null
    }

    private fun applyMtkBandLock(
        channel: AtChannel,
        slot: Int,
        requestedBands: List<Int>
    ): JSONObject? {
        val supported = readCurrentOrSupportedEpbse(channel, slot, querySupported = true)
        val current = readCurrentOrSupportedEpbse(channel, slot, querySupported = false) ?: supported
            ?: return null

        val supportedLteBands = if (supported != null) {
            decodeLteBandWords(supported.words.copyOfRange(2, supported.words.size))
        } else {
            emptyList()
        }

        val targetBands = if (requestedBands.isEmpty()) {
            supportedLteBands
        } else if (supportedLteBands.isNotEmpty()) {
            requestedBands.filter { it in supportedLteBands }
        } else {
            requestedBands
        }

        if (targetBands.isEmpty()) {
            val supStr = supportedLteBands.joinToString(", ") { "B$it" }.ifEmpty { "unknown" }
            return JSONObject()
                .put("ok", false)
                .put("backend", channel.nameRo)
                .put("backendEn", channel.nameRo)
                .put("msg", "Benzile alese nu sunt suportate de acest telefon (suportate: $supStr).")
                .put("msgEn", "Selected bands are not supported by this device (supported: $supStr).")
        }

        val newWords = current.words.copyOf()
        if (requestedBands.isEmpty() && supported != null) {
            newWords[0] = supported.words[0]
            newWords[1] = supported.words[1]
        }
        val lteEncoded = encodeLteBandWords(targetBands, 8)
        System.arraycopy(lteEncoded, 0, newWords, 2, 8)

        val cmd = if (current.fromHexCommand) {
            val gsmHex = "%08x".format(newWords[0] and 0xFFFFFFFFL)
            val umtsHex = "%08x".format(newWords[1] and 0xFFFFFFFFL)
            val lteHex = (2 until 5).joinToString("") { "%08x".format(newWords[it] and 0xFFFFFFFFL) }
            val nrHex = current.nrHexRaw.ifBlank { "00000000" }
            "AT+EPBSEH=\"$gsmHex\",\"$umtsHex\",\"$lteHex\",\"$nrHex\""
        } else {
            val count = if (current.fieldCount > 5) 10 else 4
            "AT+EPBSE=" + (0 until count).joinToString(",") { (newWords[it] and 0xFFFFFFFFL).toString() }
        }

        val reply = channel.send(slot, cmd, 4500L).orEmpty()
        if (!isAtOk(reply)) return null

        val label = targetBands.joinToString(", ") { "B$it" }
        return JSONObject()
            .put("ok", true)
            .put("backend", channel.nameRo)
            .put("backendEn", channel.nameRo)
            .put("bandsSummary", label)
            .put(
                "msg",
                if (requestedBands.isEmpty()) {
                    "Toate benzile LTE suportate ($label) au fost reactivate."
                } else {
                    "Benzi 4G fixate direct în modem: $label."
                }
            )
            .put(
                "msgEn",
                if (requestedBands.isEmpty()) {
                    "All supported LTE bands ($label) have been restored."
                } else {
                    "4G bands locked directly in modem: $label."
                }
            )
    }

    private fun isAtOk(reply: String): Boolean {
        if (reply.isBlank() || reply.contains("Fail for slotId", ignoreCase = true)) return false
        return reply.lineSequence().any { it.trim() == "OK" }
    }

    // --- Descoperire canal AT (Root TTY + MediaTek phoneEx Binder) ---

    private fun discoverWorkingAtChannel(slot: Int): AtChannel? {
        cachedAtChannel?.let { return it }

        if (Process.myUid() == 0) {
            for (ttyPath in ROOT_TTY_CANDIDATES) {
                val f = File(ttyPath)
                if (f.exists() && f.canRead() && f.canWrite()) {
                    val test = sendViaTtyFile(ttyPath, "AT", 1500L)
                    if (test != null && isAtOk(test)) {
                        val ch = AtChannel("MediaTek TTY (Root)") { _, cmd, timeout ->
                            sendViaTtyFile(ttyPath, cmd, timeout)
                        }
                        cachedAtChannel = ch
                        return ch
                    }
                }
            }
        }

        val binder = getServiceBinder("phoneEx")
        if (binder != null) {
            val ch = buildPhoneExAtChannel(binder, slot)
            if (ch != null) {
                cachedAtChannel = ch
                return ch
            }
        }
        return null
    }

    private fun sendViaTtyFile(path: String, cmd: String, timeoutMs: Long): String? {
        return runCatching {
            val out = StringBuilder()
            val done = CountDownLatch(1)
            val worker = ioPool.submit {
                runCatching {
                    RandomAccessFile(path, "rw").use { raf ->
                        raf.write("$cmd\r\n".toByteArray(Charsets.US_ASCII))
                        val deadline = System.currentTimeMillis() + timeoutMs
                        while (System.currentTimeMillis() < deadline) {
                            val line = raf.readLine() ?: break
                            out.append(line).append('\n')
                            val trimmed = line.trim()
                            if (trimmed == "OK" || trimmed == "ERROR" || trimmed.startsWith("+CME ERROR")) {
                                break
                            }
                        }
                    }
                }
                done.countDown()
            }
            if (!done.await(timeoutMs + 300L, TimeUnit.MILLISECONDS)) {
                worker.cancel(true)
                null
            } else {
                out.toString()
            }
        }.getOrNull()
    }

    private fun buildPhoneExAtChannel(binder: IBinder, probeSlot: Int): AtChannel? {
        val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
            ?: "com.mediatek.internal.telephony.IMtkTelephonyEx"
        val stubClass = vendorClassLoaders.firstNotNullOfOrNull { loader ->
            runCatching { Class.forName("$descriptor\$Stub", false, loader) }.getOrNull()
        } ?: return null

        val serviceObj = runCatching {
            stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.getOrNull() ?: return null

        // Varianta 1 (Android 13-16 HyperOS MediaTek): sendAtCmd(int slotId, long token, String cmd, IOemHookCallback cb)
        for (method in serviceObj.javaClass.methods) {
            if (!method.name.equals("sendAtCmd", ignoreCase = true)) continue
            val pTypes = method.parameterTypes
            val cbType = pTypes.lastOrNull() ?: continue
            if (!cbType.isInterface || !IInterface::class.java.isAssignableFrom(cbType)) continue

            val withToken = pTypes.size == 4 &&
                pTypes[0] == Int::class.javaPrimitiveType &&
                pTypes[1] == Long::class.javaPrimitiveType &&
                pTypes[2] == String::class.java
            val withoutToken = pTypes.size == 3 &&
                pTypes[0] == Int::class.javaPrimitiveType &&
                pTypes[1] == String::class.java

            if (!withToken && !withoutToken) continue

            val candidate = AtChannel("MediaTek AT (Shizuku)") { slot, cmd, timeoutMs ->
                invokeAsyncCallbackAtMethod(serviceObj, method, cbType, withToken, slot, cmd, timeoutMs)
            }
            val probeReply = candidate.send(probeSlot, "AT+EPBSE?", 3000L)
                ?: candidate.send(probeSlot, "AT", 2500L)
            if (probeReply != null && (isAtOk(probeReply) || probeReply.contains("+EPBSE"))) {
                return candidate
            }
        }

        // Varianta 2 (Android 11-12 MediaTek ex. Moto G31): invokeOemRilRequestRawBySlot(int, byte[], byte[])
        val rawBySlot = serviceObj.javaClass.methods.firstOrNull { m ->
            m.name == "invokeOemRilRequestRawBySlot" &&
                m.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType, ByteArray::class.java, ByteArray::class.java)
                )
        }
        if (rawBySlot != null) {
            val candidate = AtChannel("MediaTek RIL (Shizuku)") { slot, cmd, _ ->
                runCatching {
                    val req = "$cmd\r\n".toByteArray(Charsets.US_ASCII)
                    val resp = ByteArray(4096)
                    val len = rawBySlot.invoke(serviceObj, slot, req, resp) as Int
                    if (len > 0) String(resp, 0, minOf(len, resp.size), Charsets.ISO_8859_1) else null
                }.getOrNull()
            }
            val probeReply = candidate.send(probeSlot, "AT+EPBSE?", 2500L)
            if (probeReply != null && (isAtOk(probeReply) || probeReply.contains("+EPBSE"))) {
                return candidate
            }
        }

        return null
    }

    private fun invokeAsyncCallbackAtMethod(
        serviceObj: Any,
        method: Method,
        callbackInterface: Class<*>,
        withToken: Boolean,
        slot: Int,
        cmd: String,
        timeoutMs: Long
    ): String? {
        return runCatching {
            val latch = CountDownLatch(1)
            var capturedReply = ""
            val cbDescriptor = runCatching {
                callbackInterface.getField("DESCRIPTOR").get(null) as? String
            }.getOrNull() ?: callbackInterface.name

            val stubCls = runCatching {
                Class.forName("${callbackInterface.name}\$Stub", false, callbackInterface.classLoader)
            }.getOrNull()

            val methodsByCode = HashMap<Int, Method>()
            if (stubCls != null) {
                for (m in callbackInterface.methods) {
                    val code = runCatching {
                        stubCls.getDeclaredField("TRANSACTION_${m.name}")
                            .apply { isAccessible = true }
                            .getInt(null)
                    }.getOrNull()
                    if (code != null) methodsByCode[code] = m
                }
            }

            val binderReceiver = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code !in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
                        return super.onTransact(code, data, reply, flags)
                    }
                    runCatching { data.enforceInterface(cbDescriptor) }
                    val cbMethod = methodsByCode[code]
                    if (cbMethod != null) {
                        val sb = ArrayList<String>()
                        for (pt in cbMethod.parameterTypes) {
                            when (pt) {
                                Int::class.javaPrimitiveType -> data.readInt()
                                Long::class.javaPrimitiveType -> data.readLong()
                                Boolean::class.javaPrimitiveType -> data.readInt()
                                String::class.java -> data.readString()?.let { sb.add(it) }
                                ByteArray::class.java -> data.createByteArray()?.let {
                                    sb.add(String(it, Charsets.ISO_8859_1))
                                }
                                Array<String>::class.java -> data.createStringArray()?.let {
                                    sb.add(it.joinToString("\n"))
                                }
                                else -> break
                            }
                        }
                        capturedReply = sb.joinToString("\n")
                    }
                    reply?.writeNoException()
                    latch.countDown()
                    return true
                }
            }

            val proxy = Proxy.newProxyInstance(
                callbackInterface.classLoader,
                arrayOf(callbackInterface)
            ) { _, m, _ ->
                when (m.name) {
                    "asBinder" -> binderReceiver
                    "hashCode" -> System.identityHashCode(binderReceiver)
                    "equals" -> false
                    "toString" -> "NetStabilOemCallback"
                    else -> null
                }
            }

            if (withToken) {
                method.invoke(serviceObj, slot, System.nanoTime(), cmd, proxy)
            } else {
                method.invoke(serviceObj, slot, cmd, proxy)
            }

            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
            if (capturedReply.isNotBlank() &&
                !capturedReply.contains("Fail for slotId", ignoreCase = true) &&
                !capturedReply.lineSequence().any { it.trim() == "OK" || it.trim().startsWith("ERROR") }
            ) {
                "$capturedReply\nOK"
            } else {
                capturedReply
            }
        }.getOrNull()
    }

    // --- Android Radio HAL setSystemSelectionChannels ---

    @SuppressLint("PrivateApi")
    private fun applyHalChannelSelection(
        subId: Int,
        bands: IntArray,
        channels: IntArray
    ): Boolean {
        if (subId < 0) return false
        val phone = getSystemAidlInterface("phone", "com.android.internal.telephony.ITelephony")
            ?: return false
        val consumerCls = runCatching {
            Class.forName("com.android.internal.telephony.IBooleanConsumer")
        }.getOrNull() ?: return false

        return runCatching {
            val specifiers = when {
                bands.isEmpty() && channels.isEmpty() -> emptyList<RadioAccessSpecifier>()
                bands.size <= 8 -> listOf(RadioAccessSpecifier(AccessNetworkType.EUTRAN, bands, channels))
                else -> bands.toList().chunked(8).map { chunk ->
                    RadioAccessSpecifier(AccessNetworkType.EUTRAN, chunk.toIntArray(), channels)
                }
            }

            val latch = CountDownLatch(1)
            var accepted = false
            val binder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == FIRST_CALL_TRANSACTION) {
                        runCatching { data.enforceInterface("com.android.internal.telephony.IBooleanConsumer") }
                        accepted = (data.readInt() != 0)
                        reply?.writeNoException()
                        latch.countDown()
                        return true
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }
            val consumerProxy = Proxy.newProxyInstance(
                consumerCls.classLoader,
                arrayOf(consumerCls)
            ) { _, m, _ ->
                when (m.name) {
                    "asBinder" -> binder
                    "hashCode" -> System.identityHashCode(binder)
                    "equals" -> false
                    else -> null
                }
            }

            val method = phone.javaClass.methods.firstOrNull { m ->
                m.name == "setSystemSelectionChannels" && m.parameterTypes.size >= 2
            } ?: return false

            var subIdAssigned = false
            val args = method.parameterTypes.map { pt ->
                when {
                    List::class.java.isAssignableFrom(pt) -> specifiers
                    pt == Int::class.javaPrimitiveType -> {
                        if (!subIdAssigned) {
                            subIdAssigned = true
                            subId
                        } else {
                            0
                        }
                    }
                    pt.name.endsWith("IBooleanConsumer") -> consumerProxy
                    pt == String::class.java -> callingPkg
                    pt == Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }.toTypedArray()

            method.invoke(phone, *args)
            latch.await(5000L, TimeUnit.MILLISECONDS) && accepted
        }.getOrDefault(false)
    }

    // --- Utilitare Binder ITelephony / ISub ---

    @SuppressLint("PrivateApi")
    private fun getServiceBinder(serviceName: String): IBinder? {
        return runCatching {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, serviceName) as? IBinder
        }.getOrNull()
    }

    private fun getSystemAidlInterface(serviceName: String, aidlClassName: String): Any? {
        return runCatching {
            val binder = getServiceBinder(serviceName) ?: return null
            Class.forName("$aidlClassName\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.getOrNull()
    }

    private fun resolveEffectiveSubId(subId: Int, slot: Int): Int {
        if (subId >= 0) return subId
        val isub = getSystemAidlInterface("isub", "com.android.internal.telephony.ISub") ?: return -1
        for (getter in listOf("getDefaultDataSubId", "getDefaultSubId")) {
            val id = runCatching {
                (isub.javaClass.getMethod(getter).invoke(isub) as? Number)?.toInt()
            }.getOrNull()
            if (id != null && id >= 0) return id
        }
        val slotId = runCatching {
            val m = isub.javaClass.methods.firstOrNull { it.name == "getSubId" && it.parameterTypes.size == 1 }
            val res = m?.invoke(isub, slot)
            when (res) {
                is IntArray -> res.firstOrNull { it >= 0 }
                is Number -> res.toInt().takeIf { it >= 0 }
                else -> null
            }
        }.getOrNull()
        return slotId ?: -1
    }

    private fun discoverAllActiveSubIds(primarySubId: Int, primarySlot: Int): List<Int> {
        val collected = LinkedHashSet<Int>()
        if (primarySubId >= 0) collected.add(primarySubId)

        val isub = getSystemAidlInterface("isub", "com.android.internal.telephony.ISub")
        if (isub != null) {
            runCatching {
                val listMethod = isub.javaClass.methods.firstOrNull {
                    it.name == "getActiveSubIdList" && it.parameterTypes.size <= 1
                }
                val arr = when (listMethod?.parameterTypes?.size) {
                    0 -> listMethod.invoke(isub) as? IntArray
                    1 -> listMethod.invoke(isub, true) as? IntArray
                    else -> null
                }
                arr?.filter { it >= 0 }?.forEach { collected.add(it) }
            }
            for (slotIdx in listOf(primarySlot, 0, 1)) {
                val id = resolveEffectiveSubId(-1, slotIdx)
                if (id >= 0) collected.add(id)
            }
        }
        return collected.toList()
    }

    private fun findCompatibleMethod(target: Any, name: String, minArgs: Int): Method? {
        val candidates = target.javaClass.methods.filter { it.name == name }
        return candidates.firstOrNull { it.parameterTypes.size == minArgs }
            ?: candidates.filter { it.parameterTypes.size > minArgs }.minByOrNull { it.parameterTypes.size }
    }

    private fun invokeTelephony(target: Any, name: String, vararg providedArgs: Any): Any? {
        val method = findCompatibleMethod(target, name, providedArgs.size) ?: return null
        val pTypes = method.parameterTypes
        val fullArgs = Array<Any?>(pTypes.size) { idx ->
            if (idx < providedArgs.size) {
                val v = providedArgs[idx]
                when (pTypes[idx]) {
                    Int::class.javaPrimitiveType -> (v as Number).toInt()
                    Long::class.javaPrimitiveType -> (v as Number).toLong()
                    else -> v
                }
            } else {
                when (pTypes[idx]) {
                    String::class.java -> callingPkg
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }
        }
        return try {
            method.invoke(target, *fullArgs)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun invokeBooleanOrVoidTelephony(target: Any, name: String, vararg providedArgs: Any): Boolean {
        val method = findCompatibleMethod(target, name, providedArgs.size) ?: return false
        val res = invokeTelephony(target, name, *providedArgs)
        return (res as? Boolean) ?: (method.returnType == Void.TYPE)
    }
}

/**
 * Entrypoint CLI apelat prin app_process sub Root (su) atunci cand Shizuku nu este instalat,
 * oferind exact aceleasi functii hardware ca si serviciul Binder Shizuku.
 */
@Keep
object ModemRootCli {
    @Keep
    @JvmStatic
    fun main(args: Array<String>) {
        val action = args.getOrNull(0) ?: return
        val subId = args.getOrNull(1)?.toIntOrNull() ?: -1
        val slot = args.getOrNull(2)?.toIntOrNull() ?: 0
        val out = when (action) {
            "probe" -> ModemHardwareBridge.probeCapabilitiesJson(subId, slot)
            "rat" -> ModemHardwareBridge.setPreferredRatModeJson(subId, slot, args.getOrNull(3) ?: "AUTO")
            "bands" -> {
                val csv = args.getOrNull(3).orEmpty()
                val arr = csv.split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
                ModemHardwareBridge.lockLteBandsJson(subId, slot, arr)
            }
            "cell" -> {
                val earfcn = args.getOrNull(3)?.toIntOrNull() ?: -1
                val pci = args.getOrNull(4)?.toIntOrNull() ?: -1
                ModemHardwareBridge.lockLteCellJson(subId, slot, earfcn, pci)
            }
            "unlock" -> ModemHardwareBridge.unlockAllModemLocksJson(subId, slot)
            "carrier" -> {
                val en = args.getOrNull(3)?.toBooleanStrictOrNull() ?: true
                ModemHardwareBridge.applyCarrierConfigBoostJson(subId, slot, en)
            }
            "dualsim" -> {
                val en = args.getOrNull(3)?.toBooleanStrictOrNull() ?: true
                ModemHardwareBridge.setDualSimAutoSwitchJson(en)
            }
            else -> JSONObject()
                .put("ok", false)
                .put("msg", "Comandă necunoscută")
                .put("msgEn", "Unknown command")
                .toString()
        }
        println(out)
        System.out.flush()
        exitProcess(0)
    }
}
