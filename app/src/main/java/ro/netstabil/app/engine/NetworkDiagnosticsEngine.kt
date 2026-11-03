package ro.netstabil.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.netstabil.app.data.CellularTelemetry
import ro.netstabil.app.data.ConnectionHeadline
import ro.netstabil.app.data.DiagnosticReport
import ro.netstabil.app.data.DiagnosticSegment
import ro.netstabil.app.data.DnsProvider
import ro.netstabil.app.data.ProbeSnapshot
import ro.netstabil.app.data.WifiTelemetry
import ro.netstabil.app.network.CellularRadioAnchor
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.math.abs
import kotlin.random.Random

class NetworkDiagnosticsEngine(
    private val context: Context,
    private val cellularAnchor: CellularRadioAnchor? = null
) {

    @Volatile
    private var lastIpLatencyMs: Int? = null
    @Volatile
    private var consecutiveFailures: Int = 0

    /**
     * Micro-puls ultra-usor (~200 octeti dus-intors) marcat cu prioritate IP Low-Delay (0x10)
     * si TCP_NODELAY, legat direct pe interfata hardware 4G LTE.
     * Mentine modemul MediaTek in starea RRC_CONNECTED sub pragul de inactivitate de 5s al celulei.
     */
    suspend fun sendRrcTurboLowDelayPulse(isWifiConnected: Boolean): Int? =
        withContext(Dispatchers.IO) {
            measureTcpHandshakeMs("1.1.1.1", 443, 1400, isWifiConnected)
                ?: measureTcpHandshakeMs("8.8.8.8", 443, 1400, isWifiConnected)
        }

    enum class Http204Status {
        PASSED_204,
        CAPTIVE_PORTAL,
        UNREACHABLE
    }

    data class Http204ProbeResult(
        val status: Http204Status,
        val httpCode: Int = 0
    )

    /**
     * Verifica strict codul HTTP 204 pe domeniile oficiale generate_204 (fara redirectare).
     * Distinge clar intre Internet liber (204), Portal Captiv Wi-Fi (200/302) si lipsa rutei.
     */
    suspend fun probeHttp204Strict(isWifiConnected: Boolean = false): Http204ProbeResult =
        withContext(Dispatchers.IO) {
            // cp.cloudflare.com (vendor-neutral) este verificat primul pentru compatibilitate maxima cu
            // utilizatorii F-Droid / GrapheneOS / CalyxOS / AdAway care blocheaza domeniile Google.
            val urls = arrayOf(
                "http://cp.cloudflare.com/generate_204",
                "http://connectivitycheck.gstatic.com/generate_204"
            )
            val pinnedMobileNet = if (!isWifiConnected) cellularAnchor?.getPinnedNetwork() else null
            var detectedPortalCode = 0
            for (targetUrl in urls) {
                val code = runCatching {
                    val parsedUrl = URL(targetUrl)
                    val rawConn = if (pinnedMobileNet != null) {
                        pinnedMobileNet.openConnection(parsedUrl)
                    } else {
                        parsedUrl.openConnection()
                    }
                    val conn = (rawConn as HttpURLConnection).apply {
                        connectTimeout = 1800
                        readTimeout = 1800
                        instanceFollowRedirects = false
                        useCaches = false
                        setRequestProperty("Connection", "close")
                    }
                    try {
                        conn.responseCode
                    } finally {
                        conn.disconnect()
                    }
                }.getOrDefault(-1)

                if (code == 204) {
                    return@withContext Http204ProbeResult(Http204Status.PASSED_204, 204)
                }
                if (code in 200..399 && detectedPortalCode == 0) {
                    detectedPortalCode = code
                }
            }
            if (detectedPortalCode != 0) {
                Http204ProbeResult(Http204Status.CAPTIVE_PORTAL, detectedPortalCode)
            } else {
                Http204ProbeResult(Http204Status.UNREACHABLE, 0)
            }
        }

    /**
     * Trimite un micro-puls de cativa zeci de octeti pentru a pastra activ canalul radio
     * 4G RRC_CONNECTED si intrarea din tabela CGNAT a operatorului.
     */
    suspend fun sendKeepAliveMicroPulse(isWifiConnected: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (probeHttp204Strict(isWifiConnected).status == Http204Status.PASSED_204) {
                return@withContext true
            }
            measureTcpHandshakeMs("1.1.1.1", 443, 1800, isWifiConnected) != null
        }

    /**
     * Verificare dubla Watchdog:
     * 1. Ruta IP directa (fara DNS) catre 1.1.1.1 / 8.8.8.8 / 9.9.9.9
     * 2. Interogare DNS reala RFC 1035 prin UDP + fallback DoT sistem
     * 3. Latenta catre routerul Wi-Fi (daca Wi-Fi este activ)
     */
    suspend fun runWatchdogProbe(
        wifi: WifiTelemetry,
        preferredDns: DnsProvider
    ): ProbeSnapshot = withContext(Dispatchers.IO) {
        val ipTargets = listOf(
            "1.1.1.1" to 443,
            "8.8.8.8" to 443,
            "9.9.9.9" to 53
        )

        var measuredIpMs: Int? = null
        for ((host, port) in ipTargets) {
            val ms = measureTcpHandshakeMs(host, port, 2100, wifi.isConnected)
            if (ms != null) {
                measuredIpMs = ms
                break
            }
        }

        val dnsServerIp = resolveActiveDnsServerIp(preferredDns)
        val dnsMs = measureDnsLatencySmart(dnsServerIp, 2000, wifi.isConnected)

        val gwMs = if (wifi.isConnected && wifi.gatewayIp != "--") {
            measureGatewayLatencyMs(wifi.gatewayIp, 1200)
        } else {
            null
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeCaps = runCatching {
            cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        }.getOrNull()
        val osReportsCaptive =
            activeCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
        val osValidated =
            activeCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        val captivePortalDetected = when {
            osReportsCaptive -> true
            wifi.isConnected && !osValidated -> {
                probeHttp204Strict(isWifiConnected = true).status == Http204Status.CAPTIVE_PORTAL
            }
            else -> false
        }

        val reachable = !captivePortalDetected && (measuredIpMs != null || dnsMs != null)
        val effectiveIpMs = measuredIpMs ?: dnsMs

        val jitter = if (effectiveIpMs != null && lastIpLatencyMs != null) {
            abs(effectiveIpMs - lastIpLatencyMs!!)
        } else {
            null
        }
        if (effectiveIpMs != null) {
            lastIpLatencyMs = effectiveIpMs
        }

        if (reachable || captivePortalDetected) {
            consecutiveFailures = 0
        } else {
            consecutiveFailures += 1
        }

        ProbeSnapshot(
            isInternetReachable = reachable,
            isCaptivePortal = captivePortalDetected,
            ipLatencyMs = effectiveIpMs,
            jitterMs = jitter,
            dnsLatencyMs = dnsMs,
            gatewayLatencyMs = gwMs,
            consecutiveFailures = consecutiveFailures,
            lastCheckedTimestampMs = System.currentTimeMillis()
        )
    }

    fun resetFailureCounter() {
        consecutiveFailures = 0
    }

    fun computeHealthScore(
        cellular: CellularTelemetry,
        wifi: WifiTelemetry,
        probe: ProbeSnapshot
    ): Int {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val hasActiveNet = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } != null
        return calculatePureHealthScore(hasActiveNet, cellular, wifi, probe)
    }

    fun determineHeadline(
        cellular: CellularTelemetry,
        wifi: WifiTelemetry,
        probe: ProbeSnapshot,
        wifiCutoffDbm: Int
    ): ConnectionHeadline {
        val airplaneOn = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        }.getOrDefault(false)
        if (airplaneOn && !wifi.isConnected) return ConnectionHeadline.AIRPLANE_MODE

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (cm.activeNetwork == null) return ConnectionHeadline.OFFLINE

        if (probe.isCaptivePortal) {
            return ConnectionHeadline.CAPTIVE_PORTAL
        }
        if (probe.lastCheckedTimestampMs > 0L && !probe.isInternetReachable) {
            return ConnectionHeadline.FROZEN_RECONNECTING
        }
        if (!wifi.isConnected && cellular.networkGeneration.startsWith("2G")) {
            return ConnectionHeadline.RECOVERING_2G
        }
        if (wifi.isConnected && (wifi.rssiDbm ?: -60) <= wifiCutoffDbm) {
            return ConnectionHeadline.WIFI_DEGRADED
        }
        val weakCell = !wifi.isConnected &&
            ((cellular.rsrpDbm ?: -90) <= -107 || (cellular.sinrDb ?: 10) <= 0)
        if (weakCell) {
            return ConnectionHeadline.WEAK_KEEPALIVE
        }
        return ConnectionHeadline.STABLE
    }

    suspend fun runFourSegmentDiagnostic(
        cellular: CellularTelemetry,
        wifi: WifiTelemetry,
        preferredDns: DnsProvider
    ): DiagnosticReport = withContext(Dispatchers.IO) {
        val isRo = ro.netstabil.app.i18n.I18n.currentIsRo
        val segments = ArrayList<DiagnosticSegment>(4)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNet = cm.activeNetwork
        val caps = activeNet?.let { cm.getNetworkCapabilities(it) }

        val hasTransport = caps != null && (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            )
        val seg1Detail = when {
            !hasTransport -> if (isRo) {
                "Nicio interfață radio de date activă (verificați Datele Mobile sau Wi-Fi)."
            } else {
                "No active data radio interface (check Mobile Data or Wi-Fi)."
            }
            wifi.isConnected -> if (isRo) {
                "Wi-Fi conectat la ${wifi.ssid} (${wifi.rssiDbm ?: "--"} dBm, ${wifi.channelLabel})."
            } else {
                "Wi-Fi connected to ${wifi.ssid} (${wifi.rssiDbm ?: "--"} dBm, ${wifi.channelLabel})."
            }
            else -> {
                val band = cellular.bandInfo?.commonName ?: cellular.networkGeneration
                if (isRo) {
                    "${cellular.operatorName} pe $band (RSRP ${cellular.rsrpDbm ?: "--"} dBm, SINR ${cellular.sinrDb ?: "--"} dB)."
                } else {
                    "${cellular.operatorName} on $band (RSRP ${cellular.rsrpDbm ?: "--"} dBm, SINR ${cellular.sinrDb ?: "--"} dB)."
                }
            }
        }
        segments.add(
            DiagnosticSegment(
                stepNumber = 1,
                titleRo = if (isRo) "1. Interfață Radio Telefon" else "1. Device Radio Link",
                passed = hasTransport && !(!wifi.isConnected && cellular.networkGeneration.startsWith("2G")),
                latencyMs = 0,
                detailRo = if (!wifi.isConnected && cellular.networkGeneration.startsWith("2G")) {
                    if (isRo) {
                        "Telefon blocat pe 2G (${cellular.networkGeneration}) - lățime de bandă insuficientă."
                    } else {
                        "Device trapped on 2G (${cellular.networkGeneration}) - insufficient bandwidth."
                    }
                } else {
                    seg1Detail
                }
            )
        )

        if (wifi.isConnected && wifi.gatewayIp != "--") {
            val gwMs = measureGatewayLatencyMs(wifi.gatewayIp, 1500)
            segments.add(
                DiagnosticSegment(
                    stepNumber = 2,
                    titleRo = if (isRo) {
                        "2. Legătură Router Wi-Fi (${wifi.gatewayIp})"
                    } else {
                        "2. Local Wi-Fi Gateway (${wifi.gatewayIp})"
                    },
                    passed = gwMs != null,
                    latencyMs = gwMs,
                    detailRo = if (gwMs != null) {
                        if (isRo) "Routerul local răspunde în $gwMs ms."
                        else "Local gateway responded in $gwMs ms."
                    } else {
                        if (isRo) "Routerul Wi-Fi nu răspunde - semnal local blocat sau interferență puternică."
                        else "Wi-Fi router unreachable - local link stalled or heavy interference."
                    }
                )
            )
        } else {
            val tcpEdgeMs = measureTcpHandshakeMs("1.1.1.1", 443, 2200, false)
                ?: measureTcpHandshakeMs("8.8.8.8", 443, 2200, false)
            segments.add(
                DiagnosticSegment(
                    stepNumber = 2,
                    titleRo = if (isRo) {
                        "2. Sesiune Date Operator (${cellular.operatorName})"
                    } else {
                        "2. Carrier Data Session (${cellular.operatorName})"
                    },
                    passed = tcpEdgeMs != null,
                    latencyMs = tcpEdgeMs,
                    detailRo = if (tcpEdgeMs != null) {
                        if (isRo) "Tunelul GTP/CGNAT al operatorului transportă pachete IP ($tcpEdgeMs ms)."
                        else "Carrier GTP/CGNAT tunnel is routing IP packets ($tcpEdgeMs ms)."
                    } else {
                        if (isRo) "Sesiune de date înghețată la operator - necesită resetare Date Mobile."
                        else "Carrier data session stalled - Mobile Data reset required."
                    }
                )
            )
        }

        val dnsIp = resolveActiveDnsServerIp(preferredDns)
        val dnsMs = measureDnsLatencySmart(dnsIp, 2200, wifi.isConnected)
        segments.add(
            DiagnosticSegment(
                stepNumber = 3,
                titleRo = "3. DNS ($dnsIp)",
                passed = dnsMs != null,
                latencyMs = dnsMs,
                detailRo = if (dnsMs != null) {
                    if (isRo) "Domeniile sunt rezolvate corect în $dnsMs ms."
                    else "Domains resolved cleanly in $dnsMs ms."
                } else {
                    if (isRo) "Serverul DNS $dnsIp nu răspunde - comutați pe Cloudflare 1.1.1.1 sau Quad9."
                    else "DNS server $dnsIp timed out - switch to Cloudflare 1.1.1.1 or Quad9."
                }
            )
        )

        val httpStart = System.nanoTime()
        val httpRes = probeHttp204Strict(wifi.isConnected)
        val http204Ok = httpRes.status == Http204Status.PASSED_204
        val httpMs = if (httpRes.status != Http204Status.UNREACHABLE) {
            ((System.nanoTime() - httpStart) / 1_000_000L).toInt().coerceAtLeast(1)
        } else {
            null
        }
        segments.add(
            DiagnosticSegment(
                stepNumber = 4,
                titleRo = if (isRo) "4. Acces Internet Complet (HTTP 204)" else "4. Full Internet Reachability (HTTP 204)",
                passed = http204Ok,
                latencyMs = if (http204Ok) httpMs else null,
                detailRo = when (httpRes.status) {
                    Http204Status.PASSED_204 -> if (isRo) {
                        "Traficul extern funcționează fără portal captiv sau blocaj ($httpMs ms)."
                    } else {
                        "External traffic verified without captive portal or block ($httpMs ms)."
                    }
                    Http204Status.CAPTIVE_PORTAL -> if (isRo) {
                        "Portal captiv detectat (HTTP ${httpRes.httpCode}) - autentificați-vă în rețeaua Wi-Fi."
                    } else {
                        "Captive portal detected (HTTP ${httpRes.httpCode}) - sign in to the Wi-Fi network."
                    }
                    Http204Status.UNREACHABLE -> if (isRo) {
                        "Pachetele nu ajung în Internet (cădere furnizor sau filtrare externă)."
                    } else {
                        "Packets cannot reach the Internet (upstream outage or firewall)."
                    }
                }
            )
        )

        val allOk = segments.all { it.passed }
        val summary = when {
            allOk -> if (isRo) "Toate cele 4 segmente funcționează normal." else "All 4 network segments are healthy."
            !segments[0].passed -> if (isRo) "Problemă la nivelul semnalului radio al telefonului." else "Issue detected at the device radio layer."
            !segments[1].passed && wifi.isConnected -> if (isRo) "Semnalul Wi-Fi până la router este compromis." else "Local Wi-Fi link to router is degraded."
            !segments[1].passed -> if (isRo) "Sesiunea de date mobile a operatorului este înghețată." else "Carrier mobile data session is stalled."
            segments[1].passed && !segments[2].passed -> if (isRo) "Ruta IP merge, dar serverul DNS este căzut." else "IP routing works, but the DNS server is down."
            httpRes.status == Http204Status.CAPTIVE_PORTAL -> if (isRo) "Rețeaua cere autentificare în portal captiv." else "Network requires captive portal login."
            else -> if (isRo) "Conexiunea externă către Internet este întreruptă." else "Upstream Internet connectivity is down."
        }

        DiagnosticReport(
            timestampMs = System.currentTimeMillis(),
            overallHealthy = allOk,
            summaryRo = summary,
            segments = segments
        )
    }

    private fun resolveActiveDnsServerIp(preferredDns: DnsProvider): String {
        if (preferredDns != DnsProvider.CARRIER_DEFAULT && preferredDns.primaryIpv4.isNotBlank()) {
            return preferredDns.primaryIpv4
        }
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) }
            lp?.dnsServers
                ?.firstOrNull { it is Inet4Address }
                ?.hostAddress
                ?: "1.1.1.1"
        }.getOrDefault("1.1.1.1")
    }

    private fun measureTcpHandshakeMs(
        hostIp: String,
        port: Int,
        timeoutMs: Int,
        isWifiConnected: Boolean = false
    ): Int? {
        return try {
            Socket().use { socket ->
                cellularAnchor?.bindSocketIfMobileActive(socket, isWifiConnected)
                runCatching { socket.tcpNoDelay = true }
                runCatching { socket.trafficClass = IPTOS_LOWDELAY }
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(hostIp, port), timeoutMs)
                val elapsed = ((System.nanoTime() - start) / 1_000_000L).toInt()
                elapsed.coerceAtLeast(1)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun measureGatewayLatencyMs(gatewayIp: String, timeoutMs: Int): Int? {
        for (port in intArrayOf(53, 80, 443)) {
            val start = System.nanoTime()
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(gatewayIp, port), timeoutMs / 2)
                }
                return ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                if (msg.contains("refused") || msg.contains("econnrefused")) {
                    return ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
                }
            }
        }
        return try {
            val start = System.nanoTime()
            val ok = InetAddress.getByName(gatewayIp).isReachable(timeoutMs)
            if (ok) ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun measureDnsLatencySmart(
        dnsServerIp: String,
        timeoutMs: Int,
        isWifiConnected: Boolean = false
    ): Int? {
        val rawMs = measureRawUdpDnsMs(dnsServerIp, "cloudflare.com", timeoutMs, isWifiConnected)
        if (rawMs != null) return rawMs

        // Fallback prin rezolvorul de sistem (suporta DNS-over-TLS port 853 daca UDP 53 e filtrat in Wi-Fi public)
        for (fallbackDomain in arrayOf("cp.cloudflare.com", "connectivitycheck.gstatic.com")) {
            try {
                val start = System.nanoTime()
                val addrs = InetAddress.getAllByName(fallbackDomain)
                if (addrs.isNotEmpty()) {
                    return ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
                }
            } catch (_: Exception) {
                // continuam cu domeniul urmator
            }
        }
        return null
    }

    private fun measureRawUdpDnsMs(
        dnsServerIp: String,
        domain: String,
        timeoutMs: Int,
        isWifiConnected: Boolean = false
    ): Int? {
        return try {
            val txId = Random.nextInt(1, 65534)
            val baos = ByteArrayOutputStream(64)
            val dos = DataOutputStream(baos)
            dos.writeShort(txId)
            dos.writeShort(0x0100)
            dos.writeShort(1)
            dos.writeShort(0)
            dos.writeShort(0)
            dos.writeShort(0)
            for (label in domain.split('.')) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
            dos.writeByte(0)
            dos.writeShort(1)
            dos.writeShort(1)
            dos.flush()

            val queryBytes = baos.toByteArray()
            DatagramSocket().use { socket ->
                cellularAnchor?.bindDatagramIfMobileActive(socket, isWifiConnected)
                runCatching { socket.trafficClass = IPTOS_LOWDELAY }
                socket.soTimeout = timeoutMs
                val addr = InetAddress.getByName(dnsServerIp)
                val packet = DatagramPacket(queryBytes, queryBytes.size, addr, 53)
                val recvBuf = ByteArray(512)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)

                val start = System.nanoTime()
                socket.send(packet)
                socket.receive(recvPacket)
                val elapsed = ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)

                if (isValidDnsSuccessResponse(recvBuf, recvPacket.length, txId)) {
                    elapsed
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val IPTOS_LOWDELAY = 0x10

        /**
         * Valideaza ca pachetul UDP DNS primit este un raspuns autentic (QR=1), cu acelasi Transaction ID,
         * RCODE=0 (NOERROR - respingand SERVFAIL/REFUSED returnate instant de routere cu fibra cazuta)
         * si cel putin 1 inregistrare in sectiunea Answer (ANCOUNT >= 1).
         */
        fun isValidDnsSuccessResponse(
            recvBuf: ByteArray,
            length: Int,
            expectedTxId: Int
        ): Boolean {
            if (length < 12 || recvBuf.size < 12) return false
            val respId = ((recvBuf[0].toInt() and 0xFF) shl 8) or (recvBuf[1].toInt() and 0xFF)
            if (respId != expectedTxId) return false
            val flags = ((recvBuf[2].toInt() and 0xFF) shl 8) or (recvBuf[3].toInt() and 0xFF)
            if ((flags and 0x8000) == 0) return false
            if ((flags and 0x000F) != 0) return false
            val anCount = ((recvBuf[6].toInt() and 0xFF) shl 8) or (recvBuf[7].toInt() and 0xFF)
            return anCount >= 1
        }

        fun calculatePureHealthScore(
            hasActiveNet: Boolean,
            cellular: CellularTelemetry,
            wifi: WifiTelemetry,
            probe: ProbeSnapshot
        ): Int {
            if (!hasActiveNet) return 0
            if (probe.isCaptivePortal) return 35

            val onWifi = wifi.isConnected
            if (probe.lastCheckedTimestampMs > 0L && !probe.isInternetReachable) {
                return if (probe.consecutiveFailures >= 2) 12 else 25
            }

            val rfPowerScore = if (onWifi) {
                val rssi = wifi.rssiDbm ?: -72
                when {
                    rssi >= -58 -> 40
                    rssi >= -67 -> 35
                    rssi >= -74 -> 27
                    rssi >= -80 -> 18
                    rssi >= -86 -> 10
                    else -> 4
                }
            } else {
                val rsrp = cellular.rsrpDbm ?: -98
                val genPenalty = if (cellular.networkGeneration.startsWith("2G")) 18 else 0
                val raw = when {
                    rsrp >= -82 -> 40
                    rsrp >= -92 -> 34
                    rsrp >= -100 -> 26
                    rsrp >= -108 -> 18
                    rsrp >= -114 -> 10
                    else -> 4
                }
                (raw - genPenalty).coerceAtLeast(2)
            }

            val rfQualityScore = if (onWifi) {
                when {
                    wifi.linkSpeedMbps >= 200 -> 25
                    wifi.linkSpeedMbps >= 72 -> 21
                    wifi.linkSpeedMbps >= 24 -> 15
                    wifi.linkSpeedMbps > 0 -> 9
                    else -> 15
                }
            } else {
                val sinr = cellular.sinrDb
                if (sinr != null) {
                    when {
                        sinr >= 15 -> 25
                        sinr >= 8 -> 21
                        sinr >= 3 -> 15
                        sinr >= -1 -> 9
                        else -> 4
                    }
                } else {
                    val rsrq = cellular.rsrqDb
                    when {
                        rsrq == null -> 18
                        rsrq >= -9 -> 24
                        rsrq >= -13 -> 18
                        rsrq >= -17 -> 11
                        else -> 5
                    }
                }
            }

            val ping = probe.ipLatencyMs
            val latencyScore = if (ping == null) {
                22
            } else {
                val base = when {
                    ping <= 28 -> 35
                    ping <= 50 -> 31
                    ping <= 85 -> 25
                    ping <= 140 -> 17
                    ping <= 250 -> 10
                    else -> 4
                }
                val jitterPenalty = when {
                    (probe.jitterMs ?: 0) > 60 -> 6
                    (probe.jitterMs ?: 0) > 30 -> 3
                    else -> 0
                }
                (base - jitterPenalty).coerceAtLeast(2)
            }

            return (rfPowerScore + rfQualityScore + latencyScore).coerceIn(0, 100)
        }
    }
}
