package ro.netstabil.app.tile

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.netstabil.app.NetStabilApp
import ro.netstabil.app.R
import ro.netstabil.app.network.LteBandCalculator

class NetStabilTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val app = application as NetStabilApp
        val controller = app.controller

        syncTileSnapshot(app)

        collectJob?.cancel()
        collectJob = tileScope.launch {
            launch(Dispatchers.IO) {
                if (!controller.isServiceRunning.value) {
                    runCatching { controller.signalMonitor.pollOnce() }
                }
            }
            combine(
                controller.isServiceRunning,
                controller.signalMonitor.cellular,
                controller.signalMonitor.wifi,
                controller.healthScore
            ) { running, cell, wifi, score ->
                buildSubtitle(running, wifi.isConnected, wifi.frequencyMhz, wifi.rssiDbm, cell.bandInfo?.bandNumber, cell.networkGeneration, cell.rsrpDbm, score) to running
            }.collect { (subtitleText, running) ->
                applyTileUi(running = running, subtitle = subtitleText)
            }
        }
    }

    override fun onStopListening() {
        collectJob?.cancel()
        collectJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val app = application as NetStabilApp
        val controller = app.controller
        val currentlyRunning = controller.isServiceRunning.value

        if (currentlyRunning) {
            applyTileUi(
                running = false,
                subtitle = ro.netstabil.app.i18n.trNow("Se oprește...", "Stopping...")
            )
            controller.stopStabilizer()
        } else {
            applyTileUi(
                running = true,
                subtitle = ro.netstabil.app.i18n.trNow("Se pornește...", "Starting...")
            )
            controller.startStabilizerFromUi()
        }
    }

    override fun onDestroy() {
        collectJob?.cancel()
        tileScope.cancel()
        super.onDestroy()
    }

    private fun syncTileSnapshot(app: NetStabilApp) {
        val controller = app.controller
        val running = controller.isServiceRunning.value
        val cell = controller.signalMonitor.cellular.value
        val wifi = controller.signalMonitor.wifi.value
        val score = controller.healthScore.value
        val sub = buildSubtitle(
            running = running,
            wifiConnected = wifi.isConnected,
            wifiFreqMhz = wifi.frequencyMhz,
            wifiRssi = wifi.rssiDbm,
            lteBand = cell.bandInfo?.bandNumber,
            netGen = cell.networkGeneration,
            cellRsrp = cell.rsrpDbm,
            score = score
        )
        applyTileUi(running, sub)
    }

    private fun applyTileUi(running: Boolean, subtitle: String) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_signal)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = subtitle
        tile.contentDescription = "NetStabil: $subtitle"
        runCatching { tile.updateTile() }
    }

    companion object {
        fun buildSubtitle(
            running: Boolean,
            wifiConnected: Boolean,
            wifiFreqMhz: Int,
            wifiRssi: Int?,
            lteBand: Int?,
            netGen: String,
            cellRsrp: Int?,
            score: Int
        ): String {
            if (!running) {
                val stoppedWord = ro.netstabil.app.i18n.trNow("Oprit", "Off")
                val passiveDbm = if (wifiConnected) wifiRssi else cellRsrp
                return if (passiveDbm != null) "$stoppedWord • $passiveDbm dBm" else stoppedWord
            }
            return if (wifiConnected) {
                val band = if (wifiFreqMhz >= 4900) "5G" else "2.4G"
                "Wi-Fi $band • ${wifiRssi ?: "--"} dBm"
            } else {
                val bandStr = lteBand?.let { "B$it" } ?: netGen
                val dbmStr = cellRsrp?.let { "$it dBm" } ?: "$score/100"
                "$bandStr • $dbmStr"
            }
        }

        fun requestSyncTile(context: Context) {
            runCatching {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, NetStabilTileService::class.java)
                )
            }
        }
    }
}
