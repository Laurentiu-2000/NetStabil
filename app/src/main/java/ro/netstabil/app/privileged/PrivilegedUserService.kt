package ro.netstabil.app.privileged

import android.content.Context
import android.os.Process
import androidx.annotation.Keep
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

@Keep
class PrivilegedUserService : IPrivilegedService.Stub {

    @Keep
    constructor() : super()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    override fun destroy() {
        val exitThread = Thread {
            try {
                Thread.sleep(60L)
            } catch (_: InterruptedException) {
            }
            exitProcess(0)
        }
        exitThread.isDaemon = true
        exitThread.start()
    }

    override fun getUid(): Int {
        return Process.myUid()
    }

    override fun execCommand(command: String?, timeoutSec: Int): String {
        if (command.isNullOrBlank()) {
            return "EXIT:1\nEmpty command / Comandă goală"
        }
        val effectiveTimeout = timeoutSec.coerceIn(2, 30).toLong()
        return try {
            val pb = ProcessBuilder("sh", "-c", command)
            pb.redirectErrorStream(true)
            val proc = pb.start()

            val outputBuilder = StringBuilder()
            val readerThread = Thread {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String?
                        var totalChars = 0
                        while (reader.readLine().also { line = it } != null) {
                            if (totalChars < 65536) {
                                outputBuilder.append(line).append('\n')
                                totalChars += (line?.length ?: 0) + 1
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            readerThread.isDaemon = true
            readerThread.start()

            val finished = proc.waitFor(effectiveTimeout, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return "EXIT:124\nCommand execution timeout / Timeout la execuție"
            }
            readerThread.join(500)
            val exitCode = proc.exitValue()
            "EXIT:$exitCode\n${outputBuilder.toString().trim()}"
        } catch (e: Exception) {
            "EXIT:126\nExecution error / Eroare execuție: ${e.message ?: "unknown"}"
        }
    }

    override fun probeModemCapabilities(subId: Int, simSlot: Int): String {
        return runCatching {
            ModemHardwareBridge.probeCapabilitiesJson(subId, simSlot)
        }.getOrElse { err ->
            val detail = err.message ?: "unknown"
            JSONObject()
                .put("ok", false)
                .put("msg", "Eroare interogare modem: $detail")
                .put("msgEn", "Modem query error: $detail")
                .toString()
        }
    }

    override fun setPreferredRatMode(subId: Int, simSlot: Int, modeCode: String?): String {
        return runCatching {
            ModemHardwareBridge.setPreferredRatModeJson(subId, simSlot, modeCode ?: "AUTO")
        }.getOrElse { err ->
            val detail = err.message ?: "unknown"
            JSONObject()
                .put("ok", false)
                .put("msg", "Eroare comutare rețea: $detail")
                .put("msgEn", "Network mode switch error: $detail")
                .toString()
        }
    }

    override fun lockLteBands(subId: Int, simSlot: Int, bands: IntArray?): String {
        return runCatching {
            ModemHardwareBridge.lockLteBandsJson(subId, simSlot, bands ?: IntArray(0))
        }.getOrElse { err ->
            val detail = err.message ?: "unknown"
            JSONObject()
                .put("ok", false)
                .put("msg", "Eroare blocare bandă: $detail")
                .put("msgEn", "LTE band lock error: $detail")
                .toString()
        }
    }

    override fun lockLteCell(subId: Int, simSlot: Int, earfcn: Int, pci: Int): String {
        return runCatching {
            ModemHardwareBridge.lockLteCellJson(subId, simSlot, earfcn, pci)
        }.getOrElse { err ->
            val detail = err.message ?: "unknown"
            JSONObject()
                .put("ok", false)
                .put("msg", "Eroare blocare stâlp: $detail")
                .put("msgEn", "Cell tower lock error: $detail")
                .toString()
        }
    }

    override fun unlockAllModemLocks(subId: Int, simSlot: Int): String {
        return runCatching {
            ModemHardwareBridge.unlockAllModemLocksJson(subId, simSlot)
        }.getOrElse { err ->
            val detail = err.message ?: "unknown"
            JSONObject()
                .put("ok", false)
                .put("msg", "Eroare deblocare modem: $detail")
                .put("msgEn", "Modem unlock error: $detail")
                .toString()
        }
    }

    override fun applyCarrierConfigBoost(subId: Int, simSlot: Int, enable: Boolean): String {
        return runCatching {
            ModemHardwareBridge.applyCarrierConfigBoostJson(subId, simSlot, enable)
        }.getOrElse { err ->
            val detail = err.message ?: "unknown"
            JSONObject()
                .put("ok", false)
                .put("msg", "Eroare CarrierConfig: $detail")
                .put("msgEn", "CarrierConfig error: $detail")
                .toString()
        }
    }

    override fun setDualSimAutoSwitch(enable: Boolean): String {
        return runCatching {
            ModemHardwareBridge.setDualSimAutoSwitchJson(enable)
        }.getOrElse { err ->
            val detail = err.message ?: "unknown"
            JSONObject()
                .put("ok", false)
                .put("msg", "Eroare politică Dual-SIM: $detail")
                .put("msgEn", "Dual-SIM policy error: $detail")
                .toString()
        }
    }
}
