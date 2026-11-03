package ro.netstabil.app.data

enum class AppLanguage(val badgeLabel: String) {
    AUTO("AUTO"),
    RO("RO"),
    EN("EN")
}

enum class StabilizerProfile(
    val titleRo: String,
    val titleEn: String,
    val subtitleRo: String,
    val subtitleEn: String,
    val keepAliveIntervalSec: Int,
    val watchdogIntervalSec: Int,
    val failureThreshold: Int,
    val holdPartialWakeLock: Boolean
) {
    ECONOMIC(
        titleRo = "Economic",
        titleEn = "Eco",
        subtitleRo = "Consum minim baterie. Verificare la 25s, reconectare după 3 eșecuri.",
        subtitleEn = "Minimal battery use. Watchdog every 25s, reconnect after 3 failures.",
        keepAliveIntervalSec = 25,
        watchdogIntervalSec = 25,
        failureThreshold = 3,
        holdPartialWakeLock = false
    ),
    BALANCED(
        titleRo = "Echilibrat",
        titleEn = "Balanced",
        subtitleRo = "Stabilitate zilnică. Keepalive la 12s, reconectare după 2 eșecuri.",
        subtitleEn = "Daily stability. Keepalive every 12s, reconnect after 2 failures.",
        keepAliveIntervalSec = 12,
        watchdogIntervalSec = 12,
        failureThreshold = 2,
        holdPartialWakeLock = false
    ),
    MAX_POWER(
        titleRo = "Putere Maximă",
        titleEn = "Max Power",
        subtitleRo = "Ancorare modem 4G, puls Turbo RRC la 3.2s anti-adormire, reacție rapidă anti-înghețare și anti-2G.",
        subtitleEn = "4G modem anchor, 3.2s RRC anti-idle pulse, fast anti-stall and anti-2G recovery.",
        keepAliveIntervalSec = 6,
        watchdogIntervalSec = 6,
        failureThreshold = 2,
        holdPartialWakeLock = true
    );

    fun title(isRo: Boolean): String = if (isRo) titleRo else titleEn
}

enum class DnsProvider(
    val titleRo: String,
    val titleEn: String,
    val descriptionRo: String,
    val descriptionEn: String,
    val primaryIpv4: String,
    val secondaryIpv4: String,
    val dotHostname: String,
    val dohEndpointUrl: String
) {
    CARRIER_DEFAULT(
        titleRo = "Implicit Operator",
        titleEn = "Carrier Default",
        descriptionRo = "Serverele DNS implicite ale operatorului mobil sau Wi-Fi",
        descriptionEn = "Default DNS servers provided by mobile carrier or Wi-Fi router",
        primaryIpv4 = "",
        secondaryIpv4 = "",
        dotHostname = "",
        dohEndpointUrl = ""
    ),
    CLOUDFLARE(
        titleRo = "Cloudflare 1.1.1.1",
        titleEn = "Cloudflare 1.1.1.1",
        descriptionRo = "Răspuns ultra-rapid Anycast (~8-15 ms) cu suport DoH / DoT",
        descriptionEn = "Ultra-fast global Anycast DNS (~8-15 ms) with DoH / DoT",
        primaryIpv4 = "1.1.1.1",
        secondaryIpv4 = "1.0.0.1",
        dotHostname = "one.one.one.one",
        dohEndpointUrl = "https://1.1.1.1/dns-query"
    ),
    QUAD9(
        titleRo = "Quad9 9.9.9.9",
        titleEn = "Quad9 9.9.9.9",
        descriptionRo = "Securitate ridicată, blochează domenii malițioase (DoT 853)",
        descriptionEn = "High security, blocks malicious domains (DoT port 853)",
        primaryIpv4 = "9.9.9.9",
        secondaryIpv4 = "149.112.112.112",
        dotHostname = "dns.quad9.net",
        dohEndpointUrl = ""
    ),
    GOOGLE(
        titleRo = "Google 8.8.8.8",
        titleEn = "Google 8.8.8.8",
        descriptionRo = "Stabilitate globală maximă și rezolvare constantă",
        descriptionEn = "Maximum global availability and consistent resolution",
        primaryIpv4 = "8.8.8.8",
        secondaryIpv4 = "8.8.4.4",
        dotHostname = "dns.google",
        dohEndpointUrl = "https://8.8.8.8/dns-query"
    ),
    ADGUARD(
        titleRo = "AdGuard DNS",
        titleEn = "AdGuard DNS",
        descriptionRo = "Filtrează reclamele și trackerele direct din DNS",
        descriptionEn = "Filters ads and trackers directly at DNS level",
        primaryIpv4 = "94.140.14.14",
        secondaryIpv4 = "94.140.15.15",
        dotHostname = "dns.adguard-dns.com",
        dohEndpointUrl = "https://94.140.14.14/dns-query"
    );

    fun title(isRo: Boolean): String = if (isRo) titleRo else titleEn
    fun description(isRo: Boolean): String = if (isRo) descriptionRo else descriptionEn
}

enum class PreferredRatMode(val code: String, val labelRo: String, val labelEn: String) {
    LTE_ONLY("LTE_ONLY", "Doar 4G LTE", "4G LTE Only"),
    LTE_NR("LTE_NR", "4G/5G (Fără 2G)", "4G/5G (No 2G)"),
    AUTO_DEFAULT("AUTO", "Automat (Implicit)", "Auto (Default)");

    fun label(isRo: Boolean): String = if (isRo) labelRo else labelEn
}

data class ModemControlCapability(
    val probed: Boolean = false,
    val hasDirectRatControl: Boolean = false,
    val hasMtkAtPassthrough: Boolean = false,
    val hasHalChannelLock: Boolean = false,
    val activeBackendRo: String = ro.netstabil.app.i18n.trNow("Neverificat", "Unprobed"),
    val activeBandsSummary: String = "",
    val isCellLocked: Boolean = false,
    val lastActionMessageRo: String = ""
)

data class DeepTuningStatus(
    val globalKernelTweaksVerified: Boolean = false,
    val carrierConfigOverridden: Boolean = false,
    val hyperOsShieldActive: Boolean = false,
    val dualSimPolicyActive: Boolean = false,
    val powerKeeperRestricted: Boolean = false,
    val detectedSimCount: Int = 1,
    val lastSummaryRo: String = ro.netstabil.app.i18n.trNow("În curs de verificare...", "Checking...")
) {
    val activeCount: Int
        get() = listOf(
            globalKernelTweaksVerified,
            carrierConfigOverridden,
            hyperOsShieldActive,
            dualSimPolicyActive
        ).count { it }

    val totalApplicableCount: Int
        get() = if (detectedSimCount >= 2 || dualSimPolicyActive) 4 else 3
}

enum class ConnectionHeadline(val textRo: String, val textEn: String) {
    STABLE("Conexiune stabilă", "Connection stable"),
    WEAK_KEEPALIVE("Semnal slab - menținere activă", "Weak signal - active keepalive"),
    CAPTIVE_PORTAL("Portal Captiv Wi-Fi - autentificare necesară", "Captive Portal Wi-Fi - sign-in required"),
    FROZEN_RECONNECTING("Conexiune înghețată - reconectare", "Data stalled - reconnecting"),
    RECOVERING_2G("Blocaj 2G detectat - revenire 4G", "2G trap detected - restoring 4G"),
    WIFI_DEGRADED("Wi-Fi degradat - comutare date", "Degraded Wi-Fi - switching to data"),
    AIRPLANE_MODE("Mod Avion activ", "Airplane Mode active"),
    OFFLINE("Fără conexiune activă", "No active connection");

    fun text(isRo: Boolean): String = if (isRo) textRo else textEn
}

data class LteBandInfo(
    val bandNumber: Int,
    val frequencyMhz: Int,
    val duplexMode: String,
    val commonName: String,
    val coverageRoleRo: String,
    val coverageRoleEn: String = coverageRoleRo
) {
    fun coverageRole(isRo: Boolean): String = if (isRo) coverageRoleRo else coverageRoleEn
}

data class NeighborCellSnapshot(
    val networkGeneration: String,
    val pciOrCid: Int,
    val bandLabel: String,
    val rsrpDbm: Int
)

data class CellularTelemetry(
    val operatorName: String = "Necunoscut",
    val mccMnc: String = "--",
    val subscriptionId: Int = -1,
    val simSlotIndex: Int = -1,
    val networkGeneration: String = "--",
    val isCarrierAggregation: Boolean = false,
    val bandInfo: LteBandInfo? = null,
    val secondaryBandsLabel: String = "",
    val earfcn: Int = -1,
    val bandwidthKhz: Int = -1,
    val rsrpDbm: Int? = null,
    val rsrqDb: Int? = null,
    val sinrDb: Int? = null,
    val cqi: Int? = null,
    val asuLevel: Int? = null,
    val signalBars: Int = 0,
    val eNodeBId: Int? = null,
    val sectorId: Int? = null,
    val cellId: Long? = null,
    val pci: Int? = null,
    val tac: Int? = null,
    val timingAdvance: Int? = null,
    val estimatedDistanceMeters: Int? = null,
    val downlinkKbps: Int = 0,
    val uplinkKbps: Int = 0,
    val isRoaming: Boolean = false,
    val isMobileDataActiveTransport: Boolean = false,
    val neighbors: List<NeighborCellSnapshot> = emptyList()
)

data class WifiTelemetry(
    val isConnected: Boolean = false,
    val ssid: String = "--",
    val bssid: String = "--",
    val rssiDbm: Int? = null,
    val frequencyMhz: Int = 0,
    val channelLabel: String = "--",
    val linkSpeedMbps: Int = 0,
    val localIp: String = "--",
    val gatewayIp: String = "--"
)

data class ProbeSnapshot(
    val isInternetReachable: Boolean = false,
    val isCaptivePortal: Boolean = false,
    val ipLatencyMs: Int? = null,
    val jitterMs: Int? = null,
    val dnsLatencyMs: Int? = null,
    val gatewayLatencyMs: Int? = null,
    val consecutiveFailures: Int = 0,
    val lastCheckedTimestampMs: Long = 0L
)

data class DiagnosticSegment(
    val stepNumber: Int,
    val titleRo: String,
    val passed: Boolean,
    val latencyMs: Int?,
    val detailRo
: String
)

data class DiagnosticReport(
    val timestampMs: Long,
    val overallHealthy: Boolean,
    val summaryRo: String,
    val segments: List<DiagnosticSegment>
)

enum class EventSeverity(val tagRo: String, val tagEn: String) {
    INFO("INFO", "INFO"),
    ACTION("ACȚIUNE", "ACTION"),
    ALERT("ALERTĂ", "ALERT");

    fun tag(isRo: Boolean): String = if (isRo) tagRo else tagEn
}

data class StabilizerEvent(
    val id: Long,
    val timestampMs: Long,
    val severity: EventSeverity,
    val messageRo: String
)

data class SessionStats(
    val serviceStartedAtMs: Long = 0L,
    val keepAlivePulsesSent: Long = 0L,
    val rrcTurboPulsesSent: Long = 0L,
    val isCellularAnchorActive: Boolean = false,
    val isLowLatencySliceActive: Boolean = false,
    val isHardwareKeepaliveOffloadActive: Boolean = false,
    val hardwareKeepaliveIntervalSec: Int = 0,
    val dnsCacheHits: Long = 0L,
    val osDataStallsDetected: Int = 0,
    val autoReconnectionsTriggered: Int = 0,
    val trap2gRecoveries: Int = 0,
    val weakWifiSwitches: Int = 0,
    val cellHandoversDetected: Int = 0
)

data class StabilizerSettings(
    val profile: StabilizerProfile = StabilizerProfile.MAX_POWER,
    val keepAliveEnabled: Boolean = true,
    val pinCellularRadioEnabled: Boolean = true,
    val rrcTurboEnabled: Boolean = true,
    val autoReconnectEnabled: Boolean = true,
    val anti2gTrapEnabled: Boolean = true,
    val smartWifiCutoffEnabled: Boolean = true,
    val wifiCutoffRssiDbm: Int = -80,
    val carrierConfigBoostEnabled: Boolean = true,
    val fastKernelDataStallEnabled: Boolean = true,
    val connectivityRfBoostEnabled: Boolean = true,
    val hyperOsSleepShieldEnabled: Boolean = true,
    val dualSimAutoSwitchEnabled: Boolean = true,
    val restrictXiaomiPowerKeeperBg: Boolean = false,
    val startOnBoot: Boolean = true,
    val dnsProvider: DnsProvider = DnsProvider.CARRIER_DEFAULT,
    val darkTheme: Boolean = true,
    val appLanguage: AppLanguage = AppLanguage.AUTO,
    val stabilizerRunningWanted: Boolean = false
)
