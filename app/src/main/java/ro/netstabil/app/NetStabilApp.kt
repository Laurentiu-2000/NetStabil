package ro.netstabil.app

import android.app.Application
import ro.netstabil.app.data.SettingsRepository
import ro.netstabil.app.engine.NetworkDiagnosticsEngine
import ro.netstabil.app.engine.StabilizerController
import ro.netstabil.app.network.CellularRadioAnchor
import ro.netstabil.app.network.HardwareModemKeepaliveOffload
import ro.netstabil.app.network.OsConnectivityDiagnosticsMonitor
import ro.netstabil.app.network.SignalMonitor
import ro.netstabil.app.privileged.PrivilegeManager

class NetStabilApp : Application() {

    lateinit var controller: StabilizerController
        private set

    override fun onCreate() {
        super.onCreate()
        val settingsRepo = SettingsRepository(this)
        val privilegeManager = PrivilegeManager(this).also { it.initialize() }
        val signalMonitor = SignalMonitor(this)
        val cellularRadioAnchor = CellularRadioAnchor(this)
        val hardwareKeepaliveOffload = HardwareModemKeepaliveOffload(this)
        val osDiagnosticsMonitor = OsConnectivityDiagnosticsMonitor(this)
        val diagnosticsEngine = NetworkDiagnosticsEngine(this, cellularRadioAnchor)

        controller = StabilizerController(
            appContext = this,
            settingsRepository = settingsRepo,
            privilegeManager = privilegeManager,
            signalMonitor = signalMonitor,
            cellularRadioAnchor = cellularRadioAnchor,
            hardwareKeepaliveOffload = hardwareKeepaliveOffload,
            osDiagnosticsMonitor = osDiagnosticsMonitor,
            diagnosticsEngine = diagnosticsEngine
        )
    }
}
