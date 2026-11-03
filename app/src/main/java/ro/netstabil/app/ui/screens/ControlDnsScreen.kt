package ro.netstabil.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ro.netstabil.app.data.DeepTuningStatus
import ro.netstabil.app.data.DiagnosticReport
import ro.netstabil.app.data.DnsProvider
import ro.netstabil.app.data.StabilizerSettings
import ro.netstabil.app.i18n.LocalIsRo
import ro.netstabil.app.i18n.tr
import ro.netstabil.app.privileged.PrivilegeStatus
import ro.netstabil.app.privileged.PrivilegeTier
import ro.netstabil.app.ui.components.SectionCard
import ro.netstabil.app.ui.theme.AmberWarn
import ro.netstabil.app.ui.theme.CrimsonAlert
import ro.netstabil.app.ui.theme.EmeraldPrimary
import kotlin.math.roundToInt

@Composable
fun ControlDnsScreen(
    settings: StabilizerSettings,
    privilegeStatus: PrivilegeStatus,
    deepTuningStatus: DeepTuningStatus,
    isXiaomiDevice: Boolean,
    systemPrivateDnsLabel: String,
    isLocalDnsTunnelActive: Boolean,
    dnsCacheHits: Long,
    diagnosticReport: DiagnosticReport?,
    isRunningDiagnostic: Boolean,
    onRunDiagnostic: () -> Unit,
    onSelectDns: (DnsProvider) -> Unit,
    onKeepAliveChange: (Boolean) -> Unit,
    onPinCellularChange: (Boolean) -> Unit,
    onRrcTurboChange: (Boolean) -> Unit,
    onAutoReconnectChange: (Boolean) -> Unit,
    onAnti2gChange: (Boolean) -> Unit,
    onSmartWifiChange: (Boolean) -> Unit,
    onWifiCutoffChange: (Int) -> Unit,
    onCarrierConfigBoostChange: (Boolean) -> Unit,
    onFastKernelDataStallChange: (Boolean) -> Unit,
    onConnectivityRfBoostChange: (Boolean) -> Unit,
    onHyperOsSleepShieldChange: (Boolean) -> Unit,
    onDualSimAutoSwitchChange: (Boolean) -> Unit,
    onRestrictPowerKeeperChange: (Boolean) -> Unit,
    onReapplyDeepNow: () -> Unit,
    onRestoreFactoryDefaults: () -> Unit,
    onStartOnBootChange: (Boolean) -> Unit,
    onOpenApnSettings: () -> Unit
) {
    val isRo = LocalIsRo.current
    val scroll = rememberScrollState()
    val hasSystemSettingsAccess =
        privilegeStatus.hasWriteSecureSettings || privilegeStatus.activeTier != PrivilegeTier.NORMAL
    val isPrivileged = privilegeStatus.activeTier != PrivilegeTier.NORMAL

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Card 1: Diagnostic in 4 Segmente
        SectionCard(
            title = tr(
                isRo,
                "Diagnostic Rețea în 4 Segmente",
                "4-Segment Network Diagnostic"
            )
        ) {
            Text(
                text = tr(
                    isRo,
                    "Verifică separat antena telefonului, routerul sau sesiunea operatorului, serverul DNS și ieșirea în Internet.",
                    "Independently probes the device radio link, local Wi-Fi gateway or carrier GTP tunnel, DNS server, and Internet HTTP 204."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onRunDiagnostic,
                enabled = !isRunningDiagnostic,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (isRunningDiagnostic) {
                        tr(isRo, "Se verifică segmentele...", "Probing segments...")
                    } else {
                        tr(isRo, "Rulează Diagnostic Complet", "Run Full Diagnostic")
                    }
                )
            }

            if (diagnosticReport != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = diagnosticReport.summaryRo,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (diagnosticReport.overallHealthy) EmeraldPrimary else CrimsonAlert
                )
                diagnosticReport.segments.forEach { seg ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = seg.titleRo,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                val badge = buildString {
                                    append(
                                        if (seg.passed) "OK"
                                        else tr(isRo, "BLOCAT", "FAILED")
                                    )
                                    if (seg.latencyMs != null && seg.latencyMs > 0) {
                                        append(" • ${seg.latencyMs} ms")
                                    }
                                }
                                Text(
                                    text = badge,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (seg.passed) EmeraldPrimary else CrimsonAlert
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = seg.detailRo,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Card 2: Selector DNS Rapid
        SectionCard(title = tr(isRo, "DNS Rapid și Stabil", "Fast & Reliable DNS")) {
            val dnsModeInfo = when {
                isLocalDnsTunnelActive -> {
                    val cacheSuffix = if (dnsCacheHits > 0) {
                        tr(isRo, " • $dnsCacheHits din cache 0ms", " • $dnsCacheHits 0ms cache hits")
                    } else {
                        ""
                    }
                    tr(
                        isRo,
                        "Mod activ: Tunel Turbo DNS (Paralel + Cache RAM + DoH/DoT)$cacheSuffix",
                        "Active mode: Turbo DNS Tunnel (Parallel + RAM Cache + DoH/DoT)$cacheSuffix"
                    )
                }
                else -> tr(
                    isRo,
                    "Private DNS Sistem: $systemPrivateDnsLabel",
                    "System Private DNS: $systemPrivateDnsLabel"
                )
            }
            Text(
                text = dnsModeInfo,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )

            DnsProvider.entries.forEach { provider ->
                val selected = settings.dnsProvider == provider
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectDns(provider) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { onSelectDns(provider) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = provider.title(isRo),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = provider.description(isRo),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Card 3: Reguli Automatizare Putere Maxima
        SectionCard(
            title = tr(
                isRo,
                "Reguli de Stabilizare și Reconectare",
                "Stabilization & Auto-Recovery Rules"
            )
        ) {
            SettingToggleRow(
                title = tr(isRo, "Ancorare Hardware Modem 4G", "Hardware 4G Modem Anchor"),
                subtitle = tr(
                    isRo,
                    "Menține interfața celulară activă în kernel (requestNetwork + profil latență scăzută + bindSocket).",
                    "Keeps the cellular interface pinned in the OS kernel (requestNetwork + low-latency profile + bindSocket)."
                ),
                checked = settings.pinCellularRadioEnabled,
                onCheckedChange = onPinCellularChange
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            SettingToggleRow(
                title = tr(isRo, "Mod Turbo RRC Anti-Adormire (3.2s)", "RRC Anti-Dormancy Turbo (3.2s)"),
                subtitle = tr(
                    isRo,
                    "Micro-pulsuri Low-Delay sub pragul de 5s al antenei 4G pentru a bloca căderea în RRC_IDLE (~0.2 MB/oră).",
                    "Low-delay micro-pulses under the 5s eNodeB timer to prevent 4G RRC_IDLE dormancy (~0.2 MB/hour)."
                ),
                checked = settings.rrcTurboEnabled,
                onCheckedChange = onRrcTurboChange
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            SettingToggleRow(
                title = tr(isRo, "Keepalive Activ Adaptiv", "Adaptive Active Keepalive"),
                subtitle = tr(
                    isRo,
                    "Menține deschis canalul radio 4G RRC, offload-ul baseband NATT și tabela CGNAT a operatorului.",
                    "Keeps the 4G RRC channel, baseband NATT hardware offload, and carrier CGNAT table alive."
                ),
                checked = settings.keepAliveEnabled,
                onCheckedChange = onKeepAliveChange
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            SettingToggleRow(
                title = tr(isRo, "Reconectare Automată la Înghețare", "Auto-Reconnect on Frozen Data"),
                subtitle = tr(
                    isRo,
                    "Reacționează la Watchdog și la alertele kernel Data Stall când pachetele nu mai circulă.",
                    "Responds to Watchdog failures and OS kernel Data Stall alerts when packets stop flowing."
                ),
                checked = settings.autoReconnectEnabled,
                onCheckedChange = onAutoReconnectChange
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            SettingToggleRow(
                title = tr(isRo, "Protecție Anti-Blocaj în 2G EDGE", "Anti-2G EDGE Trap Protection"),
                subtitle = tr(
                    isRo,
                    "Forțează revenirea pe 4G LTE dacă telefonul cade accidental în 2G (nu întrerupe apelurile).",
                    "Forces re-attachment to 4G LTE if the device accidentally drops to 2G (never interrupts voice calls)."
                ),
                checked = settings.anti2gTrapEnabled,
                onCheckedChange = onAnti2gChange
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            SettingToggleRow(
                title = tr(isRo, "Tăiere Automată Wi-Fi Slab", "Smart Weak Wi-Fi Cut-Off"),
                subtitle = tr(
                    isRo,
                    "Trece pe 4G când semnalul Wi-Fi scade sub pragul ales sau când routerul pierde fibra.",
                    "Switches to mobile data when Wi-Fi drops below threshold or when the router loses upstream Internet."
                ),
                checked = settings.smartWifiCutoffEnabled,
                onCheckedChange = onSmartWifiChange
            )

            if (settings.smartWifiCutoffEnabled) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = tr(isRo, "Prag minim Wi-Fi acceptat", "Minimum Wi-Fi RSSI threshold"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${settings.wifiCutoffRssiDbm} dBm",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = settings.wifiCutoffRssiDbm.toFloat(),
                        onValueChange = { onWifiCutoffChange(it.roundToInt()) },
                        valueRange = -88f..-68f,
                        steps = 19
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            SettingToggleRow(
                title = tr(isRo, "Pornire Automată la Deschiderea Telefonului", "Start Automatically on Boot"),
                subtitle = tr(
                    isRo,
                    "Repornește serviciul de stabilizare imediat după restart.",
                    "Resumes the signal stabilization service automatically after device reboot."
                ),
                checked = settings.startOnBoot,
                onCheckedChange = onStartOnBootChange
            )
        }

        // Card 4: Cele 5 Mecanisme Avansate de Plafon Maxim (Android 16 + CarrierConfig + HyperOS)
        SectionCard(
            title = tr(
                isRo,
                "Plafon Maxim: Nucleu Android, SIM și HyperOS",
                "Max Ceiling: Android Kernel, SIM & HyperOS"
            )
        ) {
            val statusColor = if (deepTuningStatus.activeCount > 0) EmeraldPrimary else AmberWarn
            Text(
                text = tr(
                    isRo,
                    "Stare activă: ${deepTuningStatus.lastSummaryRo}",
                    "Active state: ${deepTuningStatus.lastSummaryRo}"
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                fontWeight = FontWeight.SemiBold
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            SettingToggleRow(
                title = tr(
                    isRo,
                    "1. Profil Operator SIM (VoLTE + VoWiFi + 4G+)",
                    "1. SIM Carrier Profile (VoLTE + VoWiFi + 4G+)"
                ),
                subtitle = tr(
                    isRo,
                    "Suprascrie în memorie CarrierConfig prin Shizuku: deblochează VoLTE/Apel Wi-Fi, 4G+ și dezactivează preferința 2G.",
                    "In-memory CarrierConfig override via Shizuku: unlocks VoLTE/Wi-Fi Calling, 4G+ CA icon, and disables 2G preference."
                ),
                checked = settings.carrierConfigBoostEnabled,
                enabled = isPrivileged,
                onCheckedChange = onCarrierConfigBoostChange
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            SettingToggleRow(
                title = tr(
                    isRo,
                    "2. Reparare Internă Rapidă Android (DSRM + DNS)",
                    "2. Fast OS Recovery (DSRM + DnsResolver)"
                ),
                subtitle = tr(
                    isRo,
                    "Coboară timpii DataStallRecoveryManager din sistem la 12-30s (față de 1-5 min) și accelerează DnsResolver. Rămâne permanent.",
                    "Lowers Android DataStallRecoveryManager timers to 12-30s (vs 1-5 min) and tunes DnsResolver thresholds."
                ),
                checked = settings.fastKernelDataStallEnabled,
                enabled = hasSystemSettingsAccess,
                onCheckedChange = onFastKernelDataStallChange
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            SettingToggleRow(
                title = tr(
                    isRo,
                    "3. Comutare 0ms LTE + Multipath QUIC + Mainline Wi-Fi",
                    "3. 0ms LTE Handover + QUIC Multipath + Wi-Fi Hi-Perf"
                ),
                subtitle = tr(
                    isRo,
                    "Activează migrarea din mers Wi-Fi/4G (Multipath = 7 pentru Chrome/YouTube), mobile_data_always_on și oprește scanările ascunse prin cmd wifi.",
                    "Enables seamless Wi-Fi/4G Multipath (= 7), mobile_data_always_on, and disables background scan throttling via cmd wifi."
                ),
                checked = settings.connectivityRfBoostEnabled,
                enabled = hasSystemSettingsAccess,
                onCheckedChange = onConnectivityRfBoostChange
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            SettingToggleRow(
                title = tr(
                    isRo,
                    "4. Scut Rețea cu Ecranul Stins (HyperOS / Freezer)",
                    "4. Screen-Off Network Shield (HyperOS / Freezer)"
                ),
                subtitle = tr(
                    isRo,
                    "Dezactivează înghețarea aplicațiilor (Greezer / Freezer) și înscrie aplicația în lista internă de prioritate rețea și latență minimă.",
                    "Disables cached-app freezing during Doze and registers NetStabil in OS low-latency & standby exemption lists."
                ),
                checked = settings.hyperOsSleepShieldEnabled,
                enabled = hasSystemSettingsAccess,
                onCheckedChange = onHyperOsSleepShieldChange
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            SettingToggleRow(
                title = tr(
                    isRo,
                    "5. Comutare Automată Urgență Dual-SIM",
                    "5. Dual-SIM Emergency Auto Data Switch"
                ),
                subtitle = tr(
                    isRo,
                    "Activează politica nativă Android AUTO_DATA_SWITCH + date active în timpul apelului pe a doua cartelă SIM.",
                    "Enables native Android AUTO_DATA_SWITCH + active data failover onto the secondary SIM when primary loses signal."
                ),
                checked = settings.dualSimAutoSwitchEnabled,
                enabled = isPrivileged,
                onCheckedChange = onDualSimAutoSwitchChange
            )

            if (isXiaomiDevice) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                SettingToggleRow(
                    title = tr(
                        isRo,
                        "Opțional: Oprește PowerKeeper în Fundal (Xiaomi)",
                        "Optional: Restrict Xiaomi PowerKeeper Background"
                    ),
                    subtitle = tr(
                        isRo,
                        "Blochează serviciul com.miui.powerkeeper să mai închidă procesele și conexiunile cu ecranul stins.",
                        "Prevents com.miui.powerkeeper from killing background sockets while the screen is off."
                    ),
                    checked = settings.restrictXiaomiPowerKeeperBg,
                    enabled = isPrivileged,
                    onCheckedChange = onRestrictPowerKeeperChange
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onReapplyDeepNow,
                    enabled = hasSystemSettingsAccess,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(tr(isRo, "Aplică Acum", "Apply Now"))
                }
                OutlinedButton(
                    onClick = onRestoreFactoryDefaults,
                    enabled = hasSystemSettingsAccess,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(tr(isRo, "Valori Fabrică", "Factory Defaults"))
                }
            }
        }

        // Card 5: Optimizare APN pentru Date Mobile (fara Wi-Fi)
        SectionCard(
            title = tr(
                isRo,
                "Optimizare APN Date Mobile",
                "Mobile Data APN Optimization"
            )
        ) {
            Text(
                text = tr(
                    isRo,
                    "Pentru latență mai mică pe 4G fără Wi-Fi:\n" +
                        "• La Suport (Bearer): bifați LTE în loc de Nespecificat.\n" +
                        "• Rețele CGNAT IPv4 (ex. Vodafone RO live.vodafone.com): setați Protocol APN = IPv4.\n" +
                        "• Rețele Dual-Stack (ex. Orange, Digi, T-Mobile): lăsați Protocol APN = IPv4/IPv6.",
                    "For lower 4G latency on mobile data:\n" +
                        "• Set Bearer = LTE instead of Unspecified.\n" +
                        "• Pure IPv4 CGNAT carriers (e.g. Vodafone RO): set APN Protocol = IPv4.\n" +
                        "• Dual-Stack IPv6 carriers (e.g. Orange, Digi, T-Mobile): keep APN Protocol = IPv4/IPv6."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onOpenApnSettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(tr(isRo, "Deschide Setări APN", "Open APN Settings"))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}
