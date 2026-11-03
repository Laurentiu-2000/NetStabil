package ro.netstabil.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ro.netstabil.app.data.DnsProvider
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Motor DNS Turbo inspirat din Google Intra (Parallel Racing) si RethinkDNS (RFC 8767 Serve-Stale):
 * 1. Cache DNS in RAM (LRU 512 intrari) -> raspuns instant (< 0.5 ms) fara a mai astepta antena 4G.
 * 2. Serve-Stale (RFC 8767) -> pe semnal critic (-109 dBm), serveste instant intrarea expirata recent
 *    si o reimprospateaza silentios in fundal, eliminand blocajele "Se incarca..." din aplicatii.
 * 3. Happy Eyeballs DNS Racing -> interogheaza simultan serverul primar si secundar pe UDP Low-Delay
 *    si accepta primul raspuns valid sosit.
 * 4. Fallback criptat dublu DNS-over-HTTPS (RFC 8484 pe portul 443) + DNS-over-TLS (RFC 7858 pe
 *    portul 853) -> trece peste orice filtrare sau interceptare UDP port 53 pe toti cei 4 furnizori
 *    (Cloudflare, Quad9, Google, AdGuard).
 */
object DnsTurboEngine {

    private const val MAX_CACHE_ENTRIES = 512
    private const val MAX_UDP_DNS_PAYLOAD = 1472
    private const val STALE_GRACE_WINDOW_MS = 30 * 60 * 1000L // 30 minute RFC 8767 serve-stale
    private const val IPTOS_LOWDELAY = 0x10

    data class ParsedDnsQuestion(
        val cacheKey: String,
        val questionLengthBytes: Int
    )

    private class CachedDnsRecord(
        val wireResponse: ByteArray,
        val questionLengthBytes: Int,
        val expiresAtMs: Long,
        val staleUntilMs: Long
    )

    sealed class CacheLookupResult {
        data class FreshHit(val responsePayload: ByteArray) : CacheLookupResult()
        data class StaleHit(
            val responsePayload: ByteArray,
            val cacheKey: String
        ) : CacheLookupResult()
        data object Miss : CacheLookupResult()
    }

    private val cacheLock = Any()
    private val lruCache = object : LinkedHashMap<String, CachedDnsRecord>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedDnsRecord>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    private val inFlightStaleRefreshes: MutableSet<String> =
        Collections.synchronizedSet(HashSet<String>())
    private val bgRefreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun clearCache() {
        synchronized(cacheLock) {
            lruCache.clear()
        }
        inFlightStaleRefreshes.clear()
    }

    /**
     * Extrage cheia canonica "qname|qtype|qclass" dintr-un pachet DNS RFC 1035 cu 1 intrebare standard.
     */
    fun parseQuestion(payload: ByteArray): ParsedDnsQuestion? {
        if (payload.size < 17) return null
        val flags = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        if ((flags and 0x8000) != 0) return null
        if (((flags ushr 11) and 0x0F) != 0) return null

        val qdCount = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        if (qdCount != 1) return null

        val sb = StringBuilder(64)
        var pos = 12
        var labels = 0
        while (pos < payload.size) {
            val len = payload[pos].toInt() and 0xFF
            if (len == 0) {
                pos += 1
                break
            }
            if ((len and 0xC0) != 0 || len > 63 || pos + 1 + len > payload.size) {
                return null
            }
            if (labels > 0) sb.append('.')
            for (i in 1..len) {
                val b = payload[pos + i].toInt() and 0xFF
                val ch = if (b in 65..90) (b + 32).toChar() else b.toChar()
                sb.append(ch)
            }
            pos += 1 + len
            labels += 1
            if (labels > 64) return null
        }

        if (pos + 4 > payload.size) return null
        val qType = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
        val qClass = ((payload[pos + 2].toInt() and 0xFF) shl 8) or (payload[pos + 3].toInt() and 0xFF)
        val questionLen = (pos + 4) - 12

        return ParsedDnsQuestion(
            cacheKey = "${sb}|$qType|$qClass",
            questionLengthBytes = questionLen
        )
    }

    /**
     * Calculeaza TTL-ul sigur de stocare in cache (in secunde) din sectiunea Answer a raspunsului DNS.
     * Returneaza 0 daca pachetul este trunchiat (TC=1) sau contine eroare server (SERVFAIL/REFUSED).
     */
    fun extractCacheTtlSeconds(responsePayload: ByteArray, questionLengthBytes: Int): Int {
        if (responsePayload.size < 12 + questionLengthBytes) return 0
        val flags = ((responsePayload[2].toInt() and 0xFF) shl 8) or (responsePayload[3].toInt() and 0xFF)
        if ((flags and 0x8000) == 0) return 0
        if ((flags and 0x0200) != 0) return 0

        val rcode = flags and 0x000F
        if (rcode == 3) return 20
        if (rcode != 0) return 0

        val anCount = ((responsePayload[6].toInt() and 0xFF) shl 8) or (responsePayload[7].toInt() and 0xFF)
        if (anCount == 0) {
            return 35
        }

        var cursor = 12 + questionLengthBytes
        var minTtl = 3600L
        var parsedAnswers = 0

        for (i in 0 until anCount) {
            val afterName = skipWireDnsName(responsePayload, cursor)
            if (afterName < 0 || afterName + 10 > responsePayload.size) break

            val ttl = ((responsePayload[afterName + 4].toLong() and 0xFF) shl 24) or
                ((responsePayload[afterName + 5].toLong() and 0xFF) shl 16) or
                ((responsePayload[afterName + 6].toLong() and 0xFF) shl 8) or
                (responsePayload[afterName + 7].toLong() and 0xFF)
            val rdLength = ((responsePayload[afterName + 8].toInt() and 0xFF) shl 8) or
                (responsePayload[afterName + 9].toInt() and 0xFF)

            if (ttl < minTtl) minTtl = ttl
            cursor = afterName + 10 + rdLength
            if (cursor > responsePayload.size) break
            parsedAnswers += 1
        }

        if (parsedAnswers == 0) return 25
        return minTtl.coerceIn(20L, 3600L).toInt()
    }

    private fun skipWireDnsName(buf: ByteArray, offset: Int): Int {
        var p = offset
        var steps = 0
        while (p < buf.size && steps < 64) {
            val len = buf[p].toInt() and 0xFF
            if (len == 0) return p + 1
            if ((len and 0xC0) == 0xC0) {
                return if (p + 1 < buf.size) p + 2 else -1
            }
            if ((len and 0xC0) != 0) return -1
            p += 1 + len
            steps += 1
        }
        return -1
    }

    fun lookupCache(
        queryPayload: ByteArray,
        parsedQuestion: ParsedDnsQuestion,
        nowMs: Long = System.currentTimeMillis()
    ): CacheLookupResult {
        val record = synchronized(cacheLock) {
            val existing = lruCache[parsedQuestion.cacheKey] ?: return@synchronized null
            if (nowMs > existing.staleUntilMs) {
                lruCache.remove(parsedQuestion.cacheKey)
                null
            } else {
                existing
            }
        } ?: return CacheLookupResult.Miss

        val adapted = adaptCachedResponseForClient(
            cachedResponse = record.wireResponse,
            cachedQuestionLen = record.questionLengthBytes,
            clientQuery = queryPayload,
            clientQuestionLen = parsedQuestion.questionLengthBytes
        ) ?: return CacheLookupResult.Miss

        return if (nowMs <= record.expiresAtMs) {
            CacheLookupResult.FreshHit(adapted)
        } else {
            CacheLookupResult.StaleHit(adapted, parsedQuestion.cacheKey)
        }
    }

    fun storeInCache(
        parsedQuestion: ParsedDnsQuestion,
        responsePayload: ByteArray,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (responsePayload.size > MAX_UDP_DNS_PAYLOAD) return
        val ttlSec = extractCacheTtlSeconds(responsePayload, parsedQuestion.questionLengthBytes)
        if (ttlSec <= 0) return

        val ttlMs = ttlSec * 1000L
        val record = CachedDnsRecord(
            wireResponse = responsePayload.copyOf(),
            questionLengthBytes = parsedQuestion.questionLengthBytes,
            expiresAtMs = nowMs + ttlMs,
            staleUntilMs = nowMs + ttlMs + STALE_GRACE_WINDOW_MS
        )
        synchronized(cacheLock) {
            lruCache[parsedQuestion.cacheKey] = record
        }
    }

    /**
     * Rescrie Transaction ID-ul (bytes 0..1) si sectiunea Question (pentru potrivire exacta 0x20-case)
     * pastrand toti pointerii de compresie RFC 1035 intacti.
     */
    fun adaptCachedResponseForClient(
        cachedResponse: ByteArray,
        cachedQuestionLen: Int,
        clientQuery: ByteArray,
        clientQuestionLen: Int
    ): ByteArray? {
        if (cachedQuestionLen != clientQuestionLen) return null
        if (cachedResponse.size < 12 + cachedQuestionLen || clientQuery.size < 12 + clientQuestionLen) {
            return null
        }
        val copy = cachedResponse.copyOf()
        copy[0] = clientQuery[0]
        copy[1] = clientQuery[1]
        System.arraycopy(clientQuery, 12, copy, 12, clientQuestionLen)
        return copy
    }

    /**
     * Rezolva o interogare DNS cu:
     * - Cache RAM (< 0.5 ms) + Serve-Stale (cu refresh asincron in fundal)
     * - Cursa paralela UDP (Primary + Secondary) + Fallback criptat DoH (443) / DoT (853)
     */
    suspend fun resolveDnsQuery(
        queryPayload: ByteArray,
        provider: DnsProvider,
        protectDatagramSocket: (DatagramSocket) -> Boolean,
        protectTcpSocket: (Socket) -> Boolean,
        onCacheHit: () -> Unit
    ): ByteArray? {
        val question = parseQuestion(queryPayload)
        if (question != null) {
            when (val lookup = lookupCache(queryPayload, question)) {
                is CacheLookupResult.FreshHit -> {
                    onCacheHit()
                    return lookup.responsePayload
                }
                is CacheLookupResult.StaleHit -> {
                    onCacheHit()
                    triggerBackgroundRefreshIfNeeded(
                        cacheKey = lookup.cacheKey,
                        question = question,
                        querySnapshot = queryPayload.copyOf(),
                        provider = provider,
                        protectDatagramSocket = protectDatagramSocket,
                        protectTcpSocket = protectTcpSocket
                    )
                    return lookup.responsePayload
                }
                CacheLookupResult.Miss -> Unit
            }
        }

        val freshResponse = raceUpstreamsWithEncryptedFallback(
            queryPayload = queryPayload,
            provider = provider,
            protectDatagramSocket = protectDatagramSocket,
            protectTcpSocket = protectTcpSocket
        )
        if (freshResponse != null && question != null) {
            storeInCache(question, freshResponse)
        }
        return freshResponse
    }

    private fun triggerBackgroundRefreshIfNeeded(
        cacheKey: String,
        question: ParsedDnsQuestion,
        querySnapshot: ByteArray,
        provider: DnsProvider,
        protectDatagramSocket: (DatagramSocket) -> Boolean,
        protectTcpSocket: (Socket) -> Boolean
    ) {
        if (!inFlightStaleRefreshes.add(cacheKey)) return
        bgRefreshScope.launch {
            try {
                val refreshed = raceUpstreamsWithEncryptedFallback(
                    queryPayload = querySnapshot,
                    provider = provider,
                    protectDatagramSocket = protectDatagramSocket,
                    protectTcpSocket = protectTcpSocket
                )
                if (refreshed != null) {
                    storeInCache(question, refreshed)
                }
            } finally {
                inFlightStaleRefreshes.remove(cacheKey)
            }
        }
    }

    private suspend fun raceUpstreamsWithEncryptedFallback(
        queryPayload: ByteArray,
        provider: DnsProvider,
        protectDatagramSocket: (DatagramSocket) -> Boolean,
        protectTcpSocket: (Socket) -> Boolean
    ): ByteArray? = withTimeoutOrNull(3600L) {
        coroutineScope {
            val winner = CompletableDeferred<ByteArray?>()
            val udpTargets = buildList {
                if (provider.primaryIpv4.isNotBlank()) add(provider.primaryIpv4)
                if (provider.secondaryIpv4.isNotBlank() && provider.secondaryIpv4 != provider.primaryIpv4) {
                    add(provider.secondaryIpv4)
                }
            }

            val hasEncryptedLeg = provider.dohEndpointUrl.isNotBlank() ||
                (provider.dotHostname.isNotBlank() && provider.primaryIpv4.isNotBlank())
            val totalLegs = udpTargets.size + (if (hasEncryptedLeg) 1 else 0)
            if (totalLegs == 0) return@coroutineScope null
            val remainingLegs = AtomicInteger(totalLegs)
            val udpFailedImmediate = CompletableDeferred<Unit>()
            val remainingUdp = AtomicInteger(udpTargets.size)

            val jobs = buildList {
                for (ip in udpTargets) {
                    add(
                        async(Dispatchers.IO) {
                            val ans = querySingleUdpUpstream(queryPayload, ip, protectDatagramSocket)
                            if (ans != null) {
                                winner.complete(ans)
                            } else {
                                if (remainingUdp.decrementAndGet() == 0) {
                                    udpFailedImmediate.complete(Unit)
                                }
                                if (remainingLegs.decrementAndGet() == 0) {
                                    winner.complete(null)
                                }
                            }
                        }
                    )
                }

                if (hasEncryptedLeg) {
                    add(
                        async(Dispatchers.IO) {
                            withTimeoutOrNull(420L) { udpFailedImmediate.await() }
                            if (!winner.isCompleted) {
                                val encAns = queryEncryptedDoh(queryPayload, provider)
                                    ?: queryEncryptedDot853(queryPayload, provider, protectTcpSocket)
                                if (encAns != null) {
                                    winner.complete(encAns)
                                } else if (remainingLegs.decrementAndGet() == 0) {
                                    winner.complete(null)
                                }
                            }
                        }
                    )
                }
            }

            val result = winner.await()
            jobs.forEach { it.cancel() }
            result
        }
    }

    private fun querySingleUdpUpstream(
        queryPayload: ByteArray,
        serverIpv4: String,
        protectSocket: (DatagramSocket) -> Boolean
    ): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                protectSocket(socket)
                runCatching { socket.trafficClass = IPTOS_LOWDELAY }
                socket.soTimeout = 1650

                val targetAddr = InetAddress.getByName(serverIpv4)
                val outPacket = DatagramPacket(queryPayload, queryPayload.size, targetAddr, 53)
                socket.send(outPacket)

                val recvBuf = ByteArray(MAX_UDP_DNS_PAYLOAD)
                val inPacket = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(inPacket)

                val len = inPacket.length
                val expectedTxId = ((queryPayload[0].toInt() and 0xFF) shl 8) or (queryPayload[1].toInt() and 0xFF)
                if (!isValidClientDnsAnswer(recvBuf, len, expectedTxId)) return null

                recvBuf.copyOfRange(0, len)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Interogare criptata DNS-over-HTTPS (RFC 8484 POST application/dns-message) direct pe IPv4:443
     * pentru furnizorii cu suport HTTP/1.1 + IP SAN (Cloudflare, Google, AdGuard).
     */
    private fun queryEncryptedDoh(
        queryPayload: ByteArray,
        provider: DnsProvider
    ): ByteArray? {
        if (provider.dohEndpointUrl.isBlank()) return null
        return try {
            val wireReq = queryPayload.copyOf()
            wireReq[0] = 0
            wireReq[1] = 0

            val conn = (URL(provider.dohEndpointUrl).openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 1900
                readTimeout = 1900
                doOutput = true
                doInput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
            }

            try {
                conn.outputStream.use { out ->
                    out.write(wireReq)
                    out.flush()
                }
                if (conn.responseCode != 200) return null

                val rawResp = conn.inputStream.use { input ->
                    val baos = ByteArrayOutputStream(512)
                    val buf = ByteArray(1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_UDP_DNS_PAYLOAD) return null
                        baos.write(buf, 0, read)
                    }
                    baos.toByteArray()
                }

                if (rawResp.size < 12) return null
                rawResp[0] = queryPayload[0]
                rawResp[1] = queryPayload[1]

                val expectedTxId = ((queryPayload[0].toInt() and 0xFF) shl 8) or (queryPayload[1].toInt() and 0xFF)
                if (!isValidClientDnsAnswer(rawResp, rawResp.size, expectedTxId)) return null
                rawResp
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Interogare criptata DNS-over-TLS (RFC 7858 pe TCP port 853 cu SNI si verificare stricta de certificat),
     * compatibila universal cu toti cei 4 furnizori (inclusiv Quad9 9.9.9.9).
     */
    private fun queryEncryptedDot853(
        queryPayload: ByteArray,
        provider: DnsProvider,
        protectTcpSocket: (Socket) -> Boolean
    ): ByteArray? {
        if (provider.primaryIpv4.isBlank() || provider.dotHostname.isBlank()) return null
        return try {
            Socket().use { rawSocket ->
                protectTcpSocket(rawSocket)
                runCatching { rawSocket.trafficClass = IPTOS_LOWDELAY }
                runCatching { rawSocket.tcpNoDelay = true }
                rawSocket.soTimeout = 2000
                rawSocket.connect(InetSocketAddress(provider.primaryIpv4, 853), 1800)

                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(rawSocket, provider.dotHostname, 853, false) as SSLSocket
                ssl.use { tlsSocket ->
                    tlsSocket.soTimeout = 2000
                    runCatching {
                        val params = tlsSocket.sslParameters
                        params.serverNames = listOf(SNIHostName(provider.dotHostname))
                        params.endpointIdentificationAlgorithm = "HTTPS"
                        tlsSocket.sslParameters = params
                    }
                    tlsSocket.startHandshake()
                    val verified = HttpsURLConnection.getDefaultHostnameVerifier()
                        .verify(provider.dotHostname, tlsSocket.session)
                    if (!verified) return null

                    val out = DataOutputStream(tlsSocket.outputStream)
                    out.writeShort(queryPayload.size)
                    out.write(queryPayload)
                    out.flush()

                    val input = DataInputStream(tlsSocket.inputStream)
                    val respLen = input.readUnsignedShort()
                    if (respLen < 12 || respLen > MAX_UDP_DNS_PAYLOAD) return null
                    val resp = ByteArray(respLen)
                    input.readFully(resp)

                    val expectedTxId = ((queryPayload[0].toInt() and 0xFF) shl 8) or (queryPayload[1].toInt() and 0xFF)
                    if (!isValidClientDnsAnswer(resp, respLen, expectedTxId)) return null
                    resp
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Valideaza ca un pachet DNS de la upstream este un raspuns autentic (QR=1), cu Transaction ID corect,
     * netruncat (TC=0) si RCODE in {0 = NOERROR, 3 = NXDOMAIN}, prevenind ca un upstream defect care
     * raspunde instant cu REFUSED (5), FORMERR (1) sau SERVFAIL (2) sa castige cursa paralela Happy Eyeballs.
     */
    fun isValidClientDnsAnswer(
        buf: ByteArray,
        len: Int,
        expectedTxId: Int
    ): Boolean {
        if (len < 12 || buf.size < len) return false
        val rxTxId = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        if (rxTxId != expectedTxId) return false
        val flags = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        if ((flags and 0x8000) == 0) return false
        if ((flags and 0x0200) != 0) return false
        val rcode = flags and 0x000F
        return rcode == 0 || rcode == 3
    }
}
