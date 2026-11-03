package ro.netstabil.app.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import ro.netstabil.app.NetStabilApp
import ro.netstabil.app.data.DnsProvider
import ro.netstabil.app.data.EventSeverity
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.random.Random

/**
 * Tunel DNS Turbo local de tip Split-Route (/32 strict catre IP-urile serverelor DNS alese) folosit
 * in Mod Normal (inainte ca Shizuku/Root sa acorde WRITE_SECURE_SETTINGS pentru Private DNS nativ).
 *
 * 99.99% din traficul internet (web, streaming, jocuri, speedtest) circula direct prin interfata
 * hardware 4G/Wi-Fi fara sa treaca prin tunel, in timp ce interogarile DNS beneficiaza de:
 * - Cache RAM 0 ms + Serve-Stale (RFC 8767)
 * - Cursa paralela UDP (Primary + Secondary) contra pierderilor de pachete pe semnal slab
 * - Fallback criptat DNS-over-HTTPS (RFC 8484 pe portul 443)
 */
class LocalDnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var relayJob: Job? = null
    @Volatile
    private var isStoppingService: Boolean = false
    private val tunnelMutex = Mutex()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as NetStabilApp
        if (intent?.action == ACTION_STOP_DNS_VPN) {
            isStoppingService = true
            closeTunnel(app)
            stopSelf()
            return START_NOT_STICKY
        }

        isStoppingService = false
        val previousJob = relayJob
        previousJob?.cancel()
        runCatching { vpnInterface?.close() }

        relayJob = serviceScope.launch {
            tunnelMutex.withLock {
                previousJob?.join()
                if (!isActive || isStoppingService) {
                    closeTunnel(app)
                    return@withLock
                }
                val provider = app.controller.settingsRepository.getSnapshot().dnsProvider
                if (provider == DnsProvider.CARRIER_DEFAULT || provider.primaryIpv4.isBlank()) {
                    closeTunnel(app)
                    stopSelf()
                    return@withLock
                }

                DnsTurboEngine.clearCache()

                val isRo = ro.netstabil.app.i18n.I18n.currentIsRo
                val pfd = runCatching {
                    vpnInterface?.close()
                    val builder = Builder()
                        .setSession("NetStabil Turbo DNS (${provider.title(isRo)})")
                        .setMtu(1500)
                        .addAddress("10.215.173.1", 30)
                        .addDnsServer(provider.primaryIpv4)
                        .addRoute(provider.primaryIpv4, 32)

                    if (provider.secondaryIpv4.isNotBlank()) {
                        builder.addDnsServer(provider.secondaryIpv4)
                        builder.addRoute(provider.secondaryIpv4, 32)
                    }

                    // Exclude propria aplicatie pentru zero bucle la Keepalive, Watchdog si DoH
                    runCatching { builder.addDisallowedApplication(packageName) }

                    builder.establish()
                }.getOrNull()

                if (pfd == null || !isActive || isStoppingService) {
                    runCatching { pfd?.close() }
                    isActiveInstance = false
                    app.controller.setLocalDnsTunnelState(false)
                    if (!isStoppingService && pfd == null) {
                        stopSelf()
                    }
                    return@withLock
                }

                vpnInterface = pfd
                isActiveInstance = true
                app.controller.setLocalDnsTunnelState(true)
                app.controller.settingsRepository.appendEvent(
                    EventSeverity.ACTION,
                    if (isRo) {
                        "Tunel Turbo DNS activat: ${provider.title(true)} (Paralel + Cache RAM + DoH/DoT)."
                    } else {
                        "Turbo DNS Tunnel enabled: ${provider.title(false)} (Parallel + RAM Cache + DoH/DoT)."
                    }
                )

                runDnsPacketRelayLoop(pfd, provider, app)
            }
        }
        return START_STICKY
    }

    private suspend fun runDnsPacketRelayLoop(
        pfd: ParcelFileDescriptor,
        provider: DnsProvider,
        app: NetStabilApp
    ) {
        val writeLock = Any()
        val relayLimiter = Semaphore(48)
        try {
            FileInputStream(pfd.fileDescriptor).use { tunIn ->
                FileOutputStream(pfd.fileDescriptor).use { tunOut ->
                    val readBuf = ByteArray(2048)
                    while (serviceScope.isActive) {
                        val length = tunIn.read(readBuf)
                        if (length <= 0) continue

                        val parsed = DnsPacketRelay.parseIpv4UdpPacket(readBuf, length) ?: continue
                        if (parsed.dstPort != 53) continue

                        serviceScope.launch {
                            relayLimiter.withPermit {
                                runCatching {
                                    val answer = DnsTurboEngine.resolveDnsQuery(
                                        queryPayload = parsed.payload,
                                        provider = provider,
                                        protectDatagramSocket = { sock -> protect(sock) },
                                        protectTcpSocket = { sock -> protect(sock) },
                                        onCacheHit = { app.controller.incrementDnsCacheHitCounter() }
                                    )

                                    if (answer != null && answer.isNotEmpty()) {
                                        val rawReply = DnsPacketRelay.buildIpv4UdpReplyPacket(
                                            srcIp = parsed.dstIp,
                                            dstIp = parsed.srcIp,
                                            srcPort = parsed.dstPort,
                                            dstPort = parsed.srcPort,
                                            dnsPayload = answer,
                                            dnsPayloadLength = answer.size
                                        )
                                        synchronized(writeLock) {
                                            tunOut.write(rawReply)
                                            tunOut.flush()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun closeTunnel(app: NetStabilApp) {
        isActiveInstance = false
        relayJob?.cancel()
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        app.controller.setLocalDnsTunnelState(false)
    }

    override fun onRevoke() {
        isStoppingService = true
        closeTunnel(application as NetStabilApp)
        super.onRevoke()
    }

    override fun onDestroy() {
        isStoppingService = true
        closeTunnel(application as NetStabilApp)
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_DNS_VPN = "ro.netstabil.app.action.START_DNS_VPN"
        const val ACTION_STOP_DNS_VPN = "ro.netstabil.app.action.STOP_DNS_VPN"

        @Volatile
        var isActiveInstance: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, LocalDnsVpnService::class.java).apply {
                action = ACTION_START_DNS_VPN
            }
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            if (!isActiveInstance) return
            runCatching {
                context.stopService(Intent(context, LocalDnsVpnService::class.java))
            }
        }
    }
}

object DnsPacketRelay {

    class ParsedUdpPacket(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray
    )

    fun parseIpv4UdpPacket(packet: ByteArray, length: Int): ParsedUdpPacket? {
        if (length < 28) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null
        val ihlBytes = (packet[0].toInt() and 0x0F) * 4
        if (ihlBytes < 20 || length < ihlBytes + 8) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null // Doar UDP (17)

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)

        val srcPort = ((packet[ihlBytes].toInt() and 0xFF) shl 8) or
            (packet[ihlBytes + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihlBytes + 2].toInt() and 0xFF) shl 8) or
            (packet[ihlBytes + 3].toInt() and 0xFF)
        val udpLength = ((packet[ihlBytes + 4].toInt() and 0xFF) shl 8) or
            (packet[ihlBytes + 5].toInt() and 0xFF)

        val payloadOffset = ihlBytes + 8
        val payloadLen = (udpLength - 8).coerceAtMost(length - payloadOffset)
        if (payloadLen <= 0) return null

        val payload = packet.copyOfRange(payloadOffset, payloadOffset + payloadLen)
        return ParsedUdpPacket(srcIp, dstIp, srcPort, dstPort, payload)
    }

    fun buildIpv4UdpReplyPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        dnsPayload: ByteArray,
        dnsPayloadLength: Int
    ): ByteArray {
        val udpLen = 8 + dnsPayloadLength
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)

        // Header IPv4 (20 octeti, fara optiuni)
        out[0] = 0x45.toByte()
        out[1] = 0
        out[2] = ((totalLen ushr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        val ipId = Random.nextInt(1, 65535)
        out[4] = ((ipId ushr 8) and 0xFF).toByte()
        out[5] = (ipId and 0xFF).toByte()
        out[6] = 0x40.toByte() // Don't Fragment
        out[7] = 0
        out[8] = 64.toByte() // TTL
        out[9] = 17.toByte() // UDP
        out[10] = 0
        out[11] = 0
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)

        val checksum = computeIpv4HeaderChecksum(out, 20)
        out[10] = ((checksum ushr 8) and 0xFF).toByte()
        out[11] = (checksum and 0xFF).toByte()

        // Header UDP (8 octeti, checksum 0x0000 valid conform RFC 768 pe IPv4)
        out[20] = ((srcPort ushr 8) and 0xFF).toByte()
        out[21] = (srcPort and 0xFF).toByte()
        out[22] = ((dstPort ushr 8) and 0xFF).toByte()
        out[23] = (dstPort and 0xFF).toByte()
        out[24] = ((udpLen ushr 8) and 0xFF).toByte()
        out[25] = (udpLen and 0xFF).toByte()
        out[26] = 0
        out[27] = 0

        System.arraycopy(dnsPayload, 0, out, 28, dnsPayloadLength)
        return out
    }

    fun computeIpv4HeaderChecksum(header: ByteArray, headerLength: Int): Int {
        var sum = 0L
        var i = 0
        while (i < headerLength) {
            val word = ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }
}
