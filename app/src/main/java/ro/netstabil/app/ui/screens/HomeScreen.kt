package ro.netstabil.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ro.netstabil.app.data.CellularTelemetry
import ro.netstabil.app.data.ConnectionHeadline
import ro.netstabil.app.data.ProbeSnapshot
import ro.netstabil.app.data.StabilizerProfile
import ro.netstabil.app.data.StabilizerSettings
import ro.netstabil.app.data.WifiTelemetry
import ro.netstabil.app.network.LteBandCalculator
import ro.netstabil.app.ui.components.MinimalHealthGauge
import ro.netstabil.app.ui.components.dbmQualityColor
import ro.netstabil.app.ui.theme.CrimsonAlert
import ro.netstabil.app.ui.theme.EmeraldPrimary

@Composable
fun HomeScreen(
    isRunning: Boolean,
    isBusyReconnecting: Boolean,
    healthScore: Int,
    headline: ConnectionHeadline,
    cellular: CellularTelemetry,
    wifi: WifiTelemetry,
    probe: ProbeSnapshot,
    settings: StabilizerSettings,
    hasRadioPermissions: Boolean,
    onToggleStabilizer: () -> Unit,
    onForceReconnect: () -> Unit,
    onSelectProfile: (StabilizerProfile) -> Unit,
    onRequestPermissions: () -> Unit
) {
    val isRo = ro.netstabil.app.i18n.LocalIsRo.current
    val scrollState = rememberScrollState()

    val networkLabel = if (wifi.isConnected) {
        LteBandCalculator.shortWifiBandLabel(wifi.frequencyMhz)
    } else {
        val bandShort = cellular.bandInfo?.let { "B${it.bandNumber}" } ?: cellular.networkGeneration
        "${cellular.operatorName} $bandShort"
    }

    val activeDbm = if (wifi.isConnected) wifi.rssiDbm else cellular.rsrpDbm
    val dbmText = activeDbm?.let { "$it dBm" } ?: "-- dBm"
    val pingText = probe.ipLatencyMs?.let { "$it ms" } ?: "-- ms"

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = viewportHeight)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            if (!hasRadioPermissions) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = ro.netstabil.app.i18n.tr(
                                isRo,
                                "Permite citirea dBm și a benzii 4G",
                                "Allow reading live dBm & 4G band"
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onRequestPermissions) {
                            Text(
                                ro.netstabil.app.i18n.tr(isRo, "Permite", "Allow"),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Zona centrala minimalista: Scor Sanatate + Stare + Rezumat Compact
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                MinimalHealthGauge(
                    score = healthScore,
                    isStabilizerActive = isRunning
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = headline.text(isRo),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp, horizontal = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CompactMetricItem(
                            label = ro.netstabil.app.i18n.tr(isRo, "REȚEA", "NETWORK"),
                            value = networkLabel,
                            valueColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1.25f)
                        )
                        CompactMetricItem(
                            label = ro.netstabil.app.i18n.tr(isRo, "SEMNAL", "SIGNAL"),
                            value = dbmText,
                            valueColor = dbmQualityColor(activeDbm, wifi.isConnected),
                            modifier = Modifier.weight(0.9f)
                        )
                        CompactMetricItem(
                            label = "PING",
                            value = pingText,
                            valueColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(0.85f)
                        )
                    }
                }
            }

            // Zona de actiune: Buton principal + Selector profil minimalist
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = onToggleStabilizer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) CrimsonAlert else EmeraldPrimary,
                        contentColor = if (isRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = if (isRunning) {
                            ro.netstabil.app.i18n.tr(isRo, "Oprește Stabilizarea", "Stop Stabilization")
                        } else {
                            ro.netstabil.app.i18n.tr(isRo, "Pornește Stabilizarea", "Start Stabilization")
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onForceReconnect,
                    enabled = !isBusyReconnecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = if (isBusyReconnecting) {
                            ro.netstabil.app.i18n.tr(isRo, "Reconectare în curs...", "Reconnecting...")
                        } else {
                            ro.netstabil.app.i18n.tr(isRo, "Forțează reconectarea acum", "Force Reconnect Now")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                ProfileSelectorRow(
                    selected = settings.profile,
                    isRo = isRo,
                    onSelect = onSelectProfile
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = when (settings.profile) {
                        StabilizerProfile.ECONOMIC -> ro.netstabil.app.i18n.tr(
                            isRo,
                            "Verificare la 25s • consum minim baterie",
                            "25s watchdog • minimal battery usage"
                        )
                        StabilizerProfile.BALANCED -> if (settings.rrcTurboEnabled) {
                            ro.netstabil.app.i18n.tr(
                                isRo,
                                "Keepalive la 12s • Turbo RRC automat sub -98 dBm",
                                "12s keepalive • auto RRC Turbo below -98 dBm"
                            )
                        } else {
                            ro.netstabil.app.i18n.tr(
                                isRo,
                                "Keepalive la 12s • echilibru baterie și semnal",
                                "12s keepalive • balanced battery & signal"
                            )
                        }
                        StabilizerProfile.MAX_POWER -> if (settings.rrcTurboEnabled) {
                            ro.netstabil.app.i18n.tr(
                                isRo,
                                "Turbo RRC la 3.2s • ancorare modem 4G și anti-2G",
                                "3.2s RRC Turbo • 4G modem anchor & anti-2G"
                            )
                        } else {
                            ro.netstabil.app.i18n.tr(
                                isRo,
                                "Keepalive activ la 6s • protecție maximă 4G și anti-2G",
                                "6s active keepalive • max 4G & anti-2G shield"
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun CompactMetricItem(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ProfileSelectorRow(
    selected: StabilizerProfile,
    isRo: Boolean,
    onSelect: (StabilizerProfile) -> Unit
) {
    val options = listOf(
        StabilizerProfile.ECONOMIC to StabilizerProfile.ECONOMIC.title(isRo),
        StabilizerProfile.BALANCED to StabilizerProfile.BALANCED.title(isRo),
        StabilizerProfile.MAX_POWER to StabilizerProfile.MAX_POWER.title(isRo)
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { (profile, label) ->
                val isSelected = selected == profile
                Button(
                    onClick = { onSelect(profile) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
                        },
                        contentColor = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
