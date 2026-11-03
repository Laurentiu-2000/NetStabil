package ro.netstabil.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ro.netstabil.app.data.DeepTuningStatus
import ro.netstabil.app.data.EventSeverity
import ro.netstabil.app.data.SessionStats
import ro.netstabil.app.data.StabilizerEvent
import ro.netstabil.app.i18n.LocalIsRo
import ro.netstabil.app.i18n.tr
import ro.netstabil.app.privileged.PrivilegeStatus
import ro.netstabil.app.privileged.PrivilegeTier
import ro.netstabil.app.system.DeviceProfileInfo
import ro.netstabil.app.ui.components.SectionCard
import ro.netstabil.app.ui.theme.AmberWarn
import ro.netstabil.app.ui.theme.CrimsonAlert
import ro.netstabil.app.ui.theme.EmeraldPrimary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogSystemScreen(
    deviceInfo: DeviceProfileInfo,
    privilegeStatus: PrivilegeStatus,
    deepTuningStatus: DeepTuningStatus,
    stats: SessionStats,
    events: List<StabilizerEvent>,
    onAddQuickTile: () -> Unit,
    onPinHomeWidget: () -> Unit,
    onAuthorizeShizuku: () -> Unit,
    onOpenShizukuApp: () -> Unit,
    onOpenWirelessDebug: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenXiaomiAutoStart: () -> Unit,
    onRefreshPrivilege: () -> Unit,
    onCopyLogs: () -> Unit,
    onClearLogs: () -> Unit
) {
    val isRo = LocalIsRo.current
    val scroll = rememberScrollState()
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Card 1: Stare Putere Maxima & Dispozitiv
        SectionCard(
            title = tr(
                isRo,
                "Stare Putere Maximă și Sistem",
                "Max Power & System Status"
            )
        ) {
            StatusLine(
                tr(isRo, "Telefon detectat", "Detected Device"),
                "${deviceInfo.brandTitle} ${deviceInfo.modelName}"
            )
            StatusLine(
                tr(isRo, "Sistem de operare", "Operating System"),
                deviceInfo.androidVersionLabel
            )
            StatusLine(
                tr(isRo, "Nivel privilegii activ", "Active Privilege Tier"),
                privilegeStatus.activeTier.short(isRo),
                highlightColor = when (privilegeStatus.activeTier) {
                    PrivilegeTier.ROOT, PrivilegeTier.SHIZUKU -> EmeraldPrimary
                    PrivilegeTier.NORMAL -> AmberWarn
                }
            )
            StatusLine(
                tr(isRo, "Permisiune Setări Sistem (DNS Nativ)", "System Settings Access (Native DNS)"),
                if (privilegeStatus.hasWriteSecureSettings) {
                    tr(isRo, "Acordată permanent", "Granted permanently")
                } else {
                    tr(isRo, "Neacordată încă", "Not granted yet")
                }
            )
            StatusLine(
                tr(isRo, "Excepție Economisire Baterie", "Battery Optimization Exemption"),
                if (deviceInfo.isBatteryOptimizationIgnored) {
                    tr(isRo, "Activă (Neîngrădit)", "Active (Unrestricted)")
                } else {
                    tr(isRo, "Restricționat", "Restricted")
                },
                highlightColor = if (deviceInfo.isBatteryOptimizationIgnored) EmeraldPrimary else CrimsonAlert
            )
            StatusLine(
                tr(isRo, "Optimizări Nucleu / Carrier SIM", "Kernel / SIM Carrier Tuning"),
                if (deepTuningStatus.activeCount > 0) {
                    tr(
                        isRo,
                        "Active (${deepTuningStatus.activeCount}/${deepTuningStatus.totalApplicableCount} verificate)",
                        "Active (${deepTuningStatus.activeCount}/${deepTuningStatus.totalApplicableCount} verified)"
                    )
                } else {
                    "Standard"
                },
                highlightColor = if (deepTuningStatus.activeCount > 0) EmeraldPrimary else AmberWarn
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            if (privilegeStatus.activeTier == PrivilegeTier.NORMAL) {
                Text(
                    text = tr(
                        isRo,
                        "Activare Shizuku (inclusiv pe Android 16 / HyperOS fără Wi-Fi):\n" +
                            "1. Pe Xiaomi bifați o singură dată și „Depanare USB (Setări de securitate)” din Opțiuni dezvoltator.\n" +
                            "2. Porniți Shizuku prin Wi-Fi sau Hotspot 30s de pe alt telefon („Depanare Wireless”).\n" +
                            "3. După pornire, puteți opri „Depanare Wireless” și Hotspot-ul — păstrați doar comutatorul general „Opțiuni dezvoltator” pornit ca Shizuku să rămână activ pe 4G!",
                        "Enabling Shizuku (including on Android 16 / HyperOS without home Wi-Fi):\n" +
                            "1. On Xiaomi/HyperOS, also enable \"USB debugging (Security settings)\" once inside Developer Options.\n" +
                            "2. Start Shizuku via Wi-Fi or a 30s Hotspot from another phone (\"Wireless debugging\").\n" +
                            "3. Once running, you can turn off Wireless Debugging & Hotspot — keep the master \"Developer options\" switch on so Shizuku stays active on 4G!"
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = if (privilegeStatus.isShizukuRunning) onAuthorizeShizuku else onOpenShizukuApp,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (privilegeStatus.isShizukuRunning) {
                                tr(isRo, "Autorizează Shizuku", "Authorize Shizuku")
                            } else if (privilegeStatus.isShizukuInstalled) {
                                tr(isRo, "Deschide Shizuku", "Open Shizuku")
                            } else {
                                tr(isRo, "Instalează Shizuku", "Get Shizuku")
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenWirelessDebug,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(tr(isRo, "Opțiuni Dezvoltator", "Developer Options"))
                    }
                }
            } else {
                Text(
                    text = tr(
                        isRo,
                        "Puterea Maximă este activă. Aplicația poate controla direct benzile 4G, modul Doar LTE, resetarea sesiunii de date și Private DNS-ul nativ.",
                        "Max Power is active. NetStabil can directly control 4G bands, LTE Only mode, data session recovery, and native Private DNS."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = EmeraldPrimary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!deviceInfo.isBatteryOptimizationIgnored) {
                    Button(
                        onClick = onRequestBatteryExemption,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(tr(isRo, "Scoate Restricția Baterie", "Unrestrict Battery"))
                    }
                }
                if (deviceInfo.isXiaomiHyperOsOrMiui) {
                    OutlinedButton(
                        onClick = onOpenXiaomiAutoStart,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("AutoStart HyperOS")
                    }
                }
                OutlinedButton(
                    onClick = onRefreshPrivilege,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(tr(isRo, "Reverifică Sistem", "Re-verify System"))
                }
            }
        }

        // Card 1B: Acces Rapid (Buton Bara de Sus + Widget Minimalist pe Ecran)
        SectionCard(
            title = tr(
                isRo,
                "Acces Rapid: Bară de Sus și Widget",
                "Quick Access: Quick Tile & Widget"
            )
        ) {
            Text(
                text = tr(
                    isRo,
                    "Adăugați butonul rapid lângă Wi-Fi/Lanternă sau widget-ul minimalist pe ecran (arată banda 4G, dBm și ping-ul live, cu pornire/oprire la o atingere).",
                    "Add the Quick Settings tile next to Wi-Fi/Flashlight or pin the live home screen widget (shows 4G band, dBm, and live ping with 1-tap toggle)."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAddQuickTile,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(tr(isRo, "Buton Bară Sus", "Quick Settings Tile"))
                }
                OutlinedButton(
                    onClick = onPinHomeWidget,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(tr(isRo, "Widget pe Ecran", "Pin Home Widget"))
                }
            }
        }

        // Card 2: Statistici Sesiune Curente
        SectionCard(title = tr(isRo, "Statistici Stabilizare", "Session Statistics")) {
            StatusLine(
                tr(isRo, "Ancorare Hardware Modem 4G", "Hardware 4G Modem Anchor"),
                when {
                    stats.isLowLatencySliceActive -> tr(
                        isRo,
                        "Activă (Profil Latență Minimă)",
                        "Active (Low-Latency Slice)"
                    )
                    stats.isCellularAnchorActive -> tr(
                        isRo,
                        "Activă pe interfața LTE",
                        "Pinned on LTE interface"
                    )
                    else -> tr(isRo, "În așteptare", "Standby")
                },
                highlightColor = if (stats.isCellularAnchorActive) EmeraldPrimary else null
            )
            StatusLine(
                tr(isRo, "Keepalive Hardware Cip Modem (0% CPU)", "Baseband Hardware Keepalive (0% CPU)"),
                if (stats.isHardwareKeepaliveOffloadActive) {
                    val sec = if (stats.hardwareKeepaliveIntervalSec > 0) stats.hardwareKeepaliveIntervalSec else 10
                    tr(isRo, "Activ în baseband (${sec}s)", "Active in baseband (${sec}s)")
                } else {
                    tr(isRo, "În așteptare / mod Wi-Fi", "Standby / Wi-Fi mode")
                },
                highlightColor = if (stats.isHardwareKeepaliveOffloadActive) EmeraldPrimary else null
            )
            StatusLine(tr(isRo, "Pulsuri Keepalive totale", "Total Keepalive Pulses"), "${stats.keepAlivePulsesSent}")
            StatusLine(tr(isRo, "Pulsuri Turbo RRC (3.2s Low-Delay)", "RRC Turbo Pulses (3.2s Low-Delay)"), "${stats.rrcTurboPulsesSent}")
            StatusLine(tr(isRo, "Răspunsuri instant Cache DNS (0 ms)", "Instant DNS Cache Hits (0 ms)"), "${stats.dnsCacheHits}")
            StatusLine(tr(isRo, "Alerte sistem (OS Data Stall)", "Kernel OS Data Stall Alerts"), "${stats.osDataStallsDetected}")
            StatusLine(tr(isRo, "Reconectări automate reușite", "Successful Auto-Reconnections"), "${stats.autoReconnectionsTriggered}")
            StatusLine(tr(isRo, "Recuperări din blocaj 2G EDGE", "2G EDGE Trap Recoveries"), "${stats.trap2gRecoveries}")
            StatusLine(tr(isRo, "Comutări de pe Wi-Fi slab", "Weak Wi-Fi Cut-Off Switches"), "${stats.weakWifiSwitches}")
            StatusLine(tr(isRo, "Schimbări celulă (Handover)", "Cell Tower Handovers"), "${stats.cellHandoversDetected}")
        }

        // Card 3: Jurnal Evenimente
        SectionCard(
            title = tr(
                isRo,
                "Jurnal Evenimente (${events.size})",
                "Event Log (${events.size})"
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCopyLogs, enabled = events.isNotEmpty()) {
                    Text(tr(isRo, "Copiază", "Copy"))
                }
                TextButton(onClick = onClearLogs, enabled = events.isNotEmpty()) {
                    Text(tr(isRo, "Șterge", "Clear"))
                }
            }

            if (events.isEmpty()) {
                Text(
                    text = tr(
                        isRo,
                        "Niciun eveniment înregistrat încă. Porniți stabilizarea din ecranul Acasă.",
                        "No events recorded yet. Start stabilization from the Home screen."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                events.take(60).forEach { ev ->
                    val badgeColor = when (ev.severity) {
                        EventSeverity.INFO -> EmeraldPrimary
                        EventSeverity.ACTION -> AmberWarn
                        EventSeverity.ALERT -> CrimsonAlert
                    }
                    val badgeLabel = ev.severity.tag(isRo)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = badgeLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor
                                )
                                Text(
                                    text = timeFormatter.format(Date(ev.timestampMs)),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = ev.messageRo,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
    highlightColor: androidx.compose.ui.graphics.Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(0.95f)
                .padding(end = 8.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = highlightColor ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.05f, fill = false)
        )
    }
}
