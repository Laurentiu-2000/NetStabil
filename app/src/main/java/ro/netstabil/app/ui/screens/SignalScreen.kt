package ro.netstabil.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ro.netstabil.app.data.CellularTelemetry
import ro.netstabil.app.data.ModemControlCapability
import ro.netstabil.app.data.PreferredRatMode
import ro.netstabil.app.data.ProbeSnapshot
import ro.netstabil.app.data.WifiTelemetry
import ro.netstabil.app.i18n.LocalIsRo
import ro.netstabil.app.i18n.tr
import ro.netstabil.app.network.LteBandCalculator
import ro.netstabil.app.privileged.PrivilegeStatus
import ro.netstabil.app.privileged.PrivilegeTier
import ro.netstabil.app.ui.components.SectionCard
import ro.netstabil.app.ui.components.SignalSparkline
import ro.netstabil.app.ui.components.dbmQualityColor
import ro.netstabil.app.ui.theme.AmberWarn
import ro.netstabil.app.ui.theme.EmeraldPrimary

@Composable
fun SignalScreen(
    cellular: CellularTelemetry,
    wifi: WifiTelemetry,
    probe: ProbeSnapshot,
    signalHistoryDbm: List<Int>,
    privilegeStatus: PrivilegeStatus,
    modemCapability: ModemControlCapability,
    isModemCommandBusy: Boolean,
    isMediaTekDevice: Boolean,
    isXiaomiDevice: Boolean,
    onApplyDirectRatMode: (PreferredRatMode) -> Unit,
    onApplyDirectBandLock: (List<Int>) -> Unit,
    onApplyDirectCellLock: () -> Unit,
    onUnlockAllModem: () -> Unit,
    onOpenRadioInfo: () -> Unit,
    onOpenMtkBandSelect: () -> Unit,
    onOpenMtkEngineer: () -> Unit,
    onOpenApnSettings: () -> Unit,
    onCopyDialCode: (String, String) -> Unit
) {
    val isRo = LocalIsRo.current
    val scroll = rememberScrollState()
    var selectedBandsCsv by rememberSaveable { mutableStateOf("3,20") }
    val selectedBands = selectedBandsCsv
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .distinct()
        .sorted()

    fun toggleBandSelection(bandNum: Int) {
        val next = if (bandNum in selectedBands) {
            selectedBands - bandNum
        } else {
            (selectedBands + bandNum).sorted()
        }
        selectedBandsCsv = next.joinToString(",")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Card 1: Evolutie grafica in timp real a puterii semnalului
        SectionCard(title = tr(isRo, "Evoluție Putere Semnal (dBm)", "Live Signal Strength (dBm)")) {
            val currentDbm = if (wifi.isConnected) wifi.rssiDbm else cellular.rsrpDbm
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (wifi.isConnected) {
                        tr(isRo, "Sursă activă: Wi-Fi", "Active source: Wi-Fi")
                    } else {
                        tr(isRo, "Sursă activă: Date Mobile", "Active source: Mobile Data")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = currentDbm?.let { "$it dBm" } ?: "-- dBm",
                    style = MaterialTheme.typography.titleMedium,
                    color = dbmQualityColor(currentDbm, wifi.isConnected),
                    fontWeight = FontWeight.Bold
                )
            }
            SignalSparkline(samplesDbm = signalHistoryDbm)
        }

        // Card 2: Telemetrie Celulara detaliata
        val simSuffix = if (cellular.simSlotIndex > 0) " (SIM ${cellular.simSlotIndex})" else ""
        SectionCard(
            title = tr(
                isRo,
                "Radio Mobil: ${cellular.operatorName}$simSuffix",
                "Cellular Radio: ${cellular.operatorName}$simSuffix"
            )
        ) {
            TelemetryRow(
                tr(isRo, "Tehnologie și MCC-MNC", "Technology & MCC-MNC"),
                "${cellular.networkGeneration} • ${cellular.mccMnc}"
            )
            val bandLabel = buildString {
                append(
                    cellular.bandInfo?.commonName
                        ?: if (cellular.earfcn > 0) "EARFCN ${cellular.earfcn}"
                        else tr(isRo, "Indisponibil", "Unavailable")
                )
                if (cellular.secondaryBandsLabel.isNotBlank()) {
                    append(" [${cellular.secondaryBandsLabel}]")
                }
            }
            TelemetryRow(tr(isRo, "Bandă 4G LTE", "4G LTE Band"), bandLabel)
            if (cellular.bandInfo != null) {
                Text(
                    text = cellular.bandInfo.coverageRole(isRo),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            TelemetryRow(
                tr(isRo, "Putere RSRP", "RSRP Power"),
                cellular.rsrpDbm?.let { "$it dBm (${describeRsrp(it, isRo)})" } ?: "--"
            )
            TelemetryRow(
                tr(isRo, "Calitate RSRQ", "RSRQ Quality"),
                cellular.rsrqDb?.let { "$it dB" } ?: "--"
            )
            TelemetryRow(
                tr(isRo, "Raport Semnal/Zgomot SINR", "Signal-to-Noise SINR"),
                cellular.sinrDb?.let { "$it dB (${describeSinr(it, isRo)})" } ?: "--"
            )
            if (cellular.cqi != null) {
                TelemetryRow(
                    tr(isRo, "Modulație Canal (CQI)", "Channel Modulation (CQI)"),
                    "${cellular.cqi}/15 • ${LteBandCalculator.describeCqiModulation(cellular.cqi, isRo)}"
                )
            }
            TelemetryRow(
                tr(isRo, "EARFCN / Lățime bandă", "EARFCN / Bandwidth"),
                buildString {
                    append(if (cellular.earfcn > 0) cellular.earfcn.toString() else "--")
                    if (cellular.bandwidthKhz > 0) {
                        append(" • ${cellular.bandwidthKhz / 1000} MHz")
                    }
                }
            )
            if (cellular.downlinkKbps > 0) {
                val dlMbps = (cellular.downlinkKbps / 1000).coerceAtLeast(1)
                val ulMbps = (cellular.uplinkKbps / 1000).coerceAtLeast(1)
                TelemetryRow(
                    tr(isRo, "Capacitate estimată purtătoare", "Estimated Carrier Link"),
                    "DL ~${dlMbps} Mbps / UL ~${ulMbps} Mbps"
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            TelemetryRow(
                tr(isRo, "Stâlp eNodeB / Sector", "eNodeB Tower / Sector"),
                if (cellular.eNodeBId != null && cellular.sectorId != null) {
                    "${cellular.eNodeBId} / Sector ${cellular.sectorId}"
                } else {
                    "--"
                }
            )
            TelemetryRow(
                "Cell ID / PCI / TAC",
                "${cellular.cellId ?: "--"} / ${cellular.pci ?: "--"} / ${cellular.tac ?: "--"}"
            )
            if (cellular.estimatedDistanceMeters != null) {
                TelemetryRow(
                    tr(isRo, "Distanță estimată (TA)", "Estimated Distance (TA)"),
                    "~${cellular.estimatedDistanceMeters} m (TA ${cellular.timingAdvance})"
                )
            }
        }

        // Card 3: Telemetrie Wi-Fi + Router
        SectionCard(title = tr(isRo, "Rețea Wi-Fi și Latență", "Wi-Fi Network & Latency")) {
            if (wifi.isConnected) {
                TelemetryRow(tr(isRo, "Nume rețea (SSID)", "Network Name (SSID)"), wifi.ssid)
                TelemetryRow(tr(isRo, "Frecvență și Canal", "Frequency & Channel"), wifi.channelLabel)
                TelemetryRow(tr(isRo, "Putere RSSI", "RSSI Strength"), "${wifi.rssiDbm ?: "--"} dBm")
                TelemetryRow(tr(isRo, "Viteză Link Fizic", "PHY Link Speed"), "${wifi.linkSpeedMbps} Mbps")
                TelemetryRow(tr(isRo, "IP Telefon / Router", "Device / Gateway IP"), "${wifi.localIp} / ${wifi.gatewayIp}")
                TelemetryRow(
                    tr(isRo, "Ping Router vs Internet", "Gateway vs Internet Ping"),
                    "${probe.gatewayLatencyMs?.let { "$it ms" } ?: "--"} vs ${probe.ipLatencyMs?.let { "$it ms" } ?: "--"}"
                )
            } else {
                Text(
                    text = tr(
                        isRo,
                        "Wi-Fi neconectat. Traficul rulează direct pe rețeaua mobilă.",
                        "Wi-Fi disconnected. Traffic runs directly over mobile data."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TelemetryRow(tr(isRo, "Ping IP Direct", "Direct IP Ping"), probe.ipLatencyMs?.let { "$it ms" } ?: "--")
                TelemetryRow(tr(isRo, "Variație (Jitter)", "Jitter"), probe.jitterMs?.let { "$it ms" } ?: "--")
                TelemetryRow(tr(isRo, "Timp Rezolvare DNS", "DNS Lookup Time"), probe.dnsLatencyMs?.let { "$it ms" } ?: "--")
            }
        }

        // Card 4: Celule vecine detectate
        SectionCard(
            title = tr(
                isRo,
                "Celule Vecine Detectate (${cellular.neighbors.size})",
                "Neighboring Cells (${cellular.neighbors.size})"
            )
        ) {
            if (cellular.neighbors.isEmpty()) {
                Text(
                    text = tr(
                        isRo,
                        "Modemul nu raportează alte celule vecine în acest moment sau locația este oprită.",
                        "Modem reports no neighboring cells right now or Location is turned off."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                cellular.neighbors.forEach { nb ->
                    TelemetryRow(
                        "${nb.networkGeneration} • PCI ${nb.pciOrCid} (${nb.bandLabel})",
                        "${nb.rsrpDbm} dBm"
                    )
                }
            }
        }

        // Card 5: Control Direct Hardware Modem (Shizuku / Root)
        val isPrivileged = privilegeStatus.activeTier != PrivilegeTier.NORMAL
        SectionCard(
            title = tr(
                isRo,
                "Control Direct Modem (Shizuku / Root)",
                "Direct Modem Control (Shizuku / Root)"
            )
        ) {
            val statusText = if (isPrivileged) {
                val backend = if (modemCapability.probed) {
                    modemCapability.activeBackendRo
                } else {
                    privilegeStatus.activeTier.short(isRo)
                }
                tr(
                    isRo,
                    "Activ ($backend) • Comenzile se trimit direct către modem fără meniuri ascunse.",
                    "Active ($backend) • Commands are sent directly to the modem without hidden menus."
                )
            } else {
                tr(
                    isRo,
                    "În așteptare (Mod Normal) • Porniți Shizuku din tab-ul Jurnal pentru blocare directă din aplicație.",
                    "Standby (Normal Mode) • Start Shizuku in the Log tab for direct in-app modem locking."
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isPrivileged) EmeraldPrimary else AmberWarn,
                fontWeight = FontWeight.Medium
            )

            if (modemCapability.activeBandsSummary.isNotBlank()) {
                TelemetryRow(
                    tr(isRo, "Benzi LTE active în modem", "Active LTE Bands in Modem"),
                    modemCapability.activeBandsSummary
                )
            }
            if (modemCapability.isCellLocked) {
                TelemetryRow(
                    tr(isRo, "Blocare stâlp (Cell Lock)", "Serving Tower Cell Lock"),
                    tr(isRo, "Activă", "Active")
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            Text(
                text = tr(
                    isRo,
                    "1. Mod Rețea Direct (activați VoLTE înainte de Doar 4G LTE):",
                    "1. Direct RAT Mode (verify VoLTE is active before choosing 4G LTE Only):"
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PreferredRatMode.entries.forEach { mode ->
                    OutlinedButton(
                        onClick = { onApplyDirectRatMode(mode) },
                        enabled = isPrivileged && !isModemCommandBusy,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = mode.label(isRo),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            Text(
                text = tr(
                    isRo,
                    "2. Blocare Benzi 4G LTE (B20/B28 = acoperire interior, B3/B1/B7 = viteză):",
                    "2. 4G LTE Band Lock (B20/B28 = indoor range, B3/B1/B7 = high speed):"
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            BandSelectionRow(
                bands = listOf(1 to "B1", 3 to "B3", 7 to "B7", 8 to "B8"),
                selectedBands = selectedBands,
                onToggleBand = ::toggleBandSelection,
                enabled = isPrivileged && !isModemCommandBusy
            )
            BandSelectionRow(
                bands = listOf(20 to "B20", 28 to "B28", 38 to "B38", 40 to "B40"),
                selectedBands = selectedBands,
                onToggleBand = ::toggleBandSelection,
                enabled = isPrivileged && !isModemCommandBusy
            )

            Button(
                onClick = { onApplyDirectBandLock(selectedBands) },
                enabled = isPrivileged && !isModemCommandBusy && selectedBands.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                val chosen = selectedBands.joinToString("+") { "B$it" }
                Text(
                    if (isModemCommandBusy) {
                        tr(isRo, "Se comunică cu modemul...", "Communicating with modem...")
                    } else {
                        tr(isRo, "Fixează Benzile Alese ($chosen)", "Lock Selected Bands ($chosen)")
                    }
                )
            }

            if (cellular.earfcn > 0) {
                val cellDesc = buildString {
                    append("EARFCN ${cellular.earfcn}")
                    if (cellular.pci != null) append(" / PCI ${cellular.pci}")
                }
                OutlinedButton(
                    onClick = onApplyDirectCellLock,
                    enabled = isPrivileged && !isModemCommandBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        tr(
                            isRo,
                            "Blochează Stâlpul Curent ($cellDesc)",
                            "Lock Current Tower ($cellDesc)"
                        )
                    )
                }
            }

            OutlinedButton(
                onClick = onUnlockAllModem,
                enabled = isPrivileged && !isModemCommandBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, EmeraldPrimary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldPrimary)
            ) {
                Text(
                    text = tr(
                        isRo,
                        "Deblochează Tot Modemul (Revenire Implicită)",
                        "Unlock All Modem Locks (Restore Defaults)"
                    ),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Card 6: Meniuri Radio de Sistem si Coduri Directe
        SectionCard(title = tr(isRo, "Meniuri Radio de Sistem și APN", "System Radio Menus & APN")) {
            Text(
                text = tr(
                    isRo,
                    "Alternative clasice de sistem (utile în Mod Normal sau pe versiuni de firmware unde producătorul restricționează comenzile AT):",
                    "System shortcuts (useful in Normal Mode or on OEM firmware builds that restrict direct AT commands):"
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenRadioInfo,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("RadioInfo (LTE)")
                }
                if (isMediaTekDevice) {
                    OutlinedButton(
                        onClick = onOpenMtkBandSelect,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(tr(isRo, "BandSelect MTK", "MTK BandSelect"))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenApnSettings,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(tr(isRo, "Setări APN", "APN Settings"))
                }
                if (isMediaTekDevice) {
                    OutlinedButton(
                        onClick = onOpenMtkEngineer,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(tr(isRo, "Inginer MTK", "MTK Engineer"))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onCopyDialCode("*#*#4636#*#*", "LTE Only") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(tr(isRo, "Cod 4636", "Code 4636"))
                }
                if (isXiaomiDevice) {
                    OutlinedButton(
                        onClick = {
                            onCopyDialCode(
                                "*#*#86583#*#*",
                                tr(isRo, "Activare VoLTE", "Enable VoLTE")
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(tr(isRo, "Cod VoLTE Xiaomi", "Xiaomi VoLTE Code"))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun BandSelectionRow(
    bands: List<Pair<Int, String>>,
    selectedBands: List<Int>,
    onToggleBand: (Int) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        bands.forEach { (bandNum, label) ->
            val isSelected = bandNum in selectedBands
            FilterChip(
                selected = isSelected,
                onClick = { onToggleBand(bandNum) },
                enabled = enabled,
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
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
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.05f, fill = false)
        )
    }
}

private fun describeRsrp(rsrp: Int, isRo: Boolean): String = when {
    rsrp >= -85 -> if (isRo) "Excelent" else "Excellent"
    rsrp >= -98 -> if (isRo) "Bun" else "Good"
    rsrp >= -108 -> if (isRo) "Slab" else "Weak"
    else -> if (isRo) "Critic" else "Critical"
}

private fun describeSinr(sinr: Int, isRo: Boolean): String = when {
    sinr >= 13 -> if (isRo) "Curat" else "Clean"
    sinr >= 5 -> if (isRo) "Normal" else "Normal"
    sinr >= 0 -> if (isRo) "Zgomot ridicat" else "High noise"
    else -> if (isRo) "Interferență severă" else "Severe interference"
}
