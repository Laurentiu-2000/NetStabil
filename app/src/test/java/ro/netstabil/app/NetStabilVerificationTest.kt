package ro.netstabil.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.netstabil.app.data.CellularTelemetry
import ro.netstabil.app.data.ProbeSnapshot
import ro.netstabil.app.data.StabilizerProfile
import ro.netstabil.app.data.StabilizerSettings
import ro.netstabil.app.data.WifiTelemetry
import ro.netstabil.app.engine.NetworkDiagnosticsEngine
import ro.netstabil.app.network.LteBandCalculator
import ro.netstabil.app.privileged.ModemHardwareBridge
import ro.netstabil.app.service.DnsPacketRelay
import ro.netstabil.app.service.DnsTurboEngine
import ro.netstabil.app.service.StabilizerForegroundService
import ro.netstabil.app.tile.NetStabilTileService
import ro.netstabil.app.widget.NetStabilWidgetProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class NetStabilVerificationTest {

    @Test
    fun verifyRomanianCarrierLteBands3gpp() {
        // B1 (2100 MHz), B3 (1800 MHz), B7 (2600 MHz), B8 (900 MHz), B20 (800 MHz), B28 (700 MHz), B38 (TDD 2600)
        assertEquals(1, LteBandCalculator.fromEarfcn(300)?.bandNumber)
        assertEquals(3, LteBandCalculator.fromEarfcn(1600)?.bandNumber)
        assertEquals(7, LteBandCalculator.fromEarfcn(3100)?.bandNumber)
        assertEquals(8, LteBandCalculator.fromEarfcn(3600)?.bandNumber)
        assertEquals(20, LteBandCalculator.fromEarfcn(6300)?.bandNumber)
        assertEquals(28, LteBandCalculator.fromEarfcn(9410)?.bandNumber)
        assertEquals(32, LteBandCalculator.fromEarfcn(10000)?.bandNumber)
        assertEquals(38, LteBandCalculator.fromEarfcn(38000)?.bandNumber)
        assertEquals(40, LteBandCalculator.fromEarfcn(39000)?.bandNumber)
        assertNull(LteBandCalculator.fromEarfcn(-1))
        assertNull(LteBandCalculator.fromEarfcn(Int.MAX_VALUE))

        // Direct HAL band lookup (API 30+ CellIdentityLte.getBands())
        assertEquals("B20 (800 MHz)", LteBandCalculator.fromBandNumber(20)?.commonName)
        assertEquals("B3 (1800 MHz)", LteBandCalculator.fromBandNumber(3)?.commonName)
        assertNull(LteBandCalculator.fromBandNumber(999))
    }

    @Test
    fun verifyCqiModulationDescriptions() {
        assertTrue(LteBandCalculator.describeCqiModulationRo(4).startsWith("QPSK"))
        assertTrue(LteBandCalculator.describeCqiModulationRo(8).startsWith("16-QAM"))
        assertTrue(LteBandCalculator.describeCqiModulationRo(11).startsWith("64-QAM"))
        assertTrue(LteBandCalculator.describeCqiModulationRo(14).startsWith("256-QAM"))
    }

    @Test
    fun verifyWifiFrequencyFormatting() {
        assertEquals("Wi-Fi 2.4 GHz", LteBandCalculator.shortWifiBandLabel(2412))
        assertEquals("Wi-Fi 2.4 GHz", LteBandCalculator.shortWifiBandLabel(2462))
        assertEquals("Wi-Fi 5 GHz", LteBandCalculator.shortWifiBandLabel(5180))
        assertEquals("2.4 GHz (Canal 6)", LteBandCalculator.wifiChannelFromFrequency(2437))
        assertEquals("5 GHz (Canal 36)", LteBandCalculator.wifiChannelFromFrequency(5180))
    }

    @Test
    fun verifyMediaTekSinrNormalization() {
        assertEquals(18, LteBandCalculator.normalizeMediaTekSinrDb(18))
        assertEquals(-4, LteBandCalculator.normalizeMediaTekSinrDb(-4))
        // MediaTek Helio G85 RIL 0.1 dB reporting (e.g. 145 = 14.5 dB)
        assertEquals(14, LteBandCalculator.normalizeMediaTekSinrDb(145))
        assertNull(LteBandCalculator.normalizeMediaTekSinrDb(Int.MAX_VALUE))
    }

    @Test
    fun verifyDnsPacketRelayParseBuildAndIpv4Checksum() {
        val dnsPayload = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00)
        val clientIp = byteArrayOf(10, 215.toByte(), 173.toByte(), 1)
        val serverIp = byteArrayOf(1, 1, 1, 1)

        val packet = DnsPacketRelay.buildIpv4UdpReplyPacket(
            srcIp = clientIp,
            dstIp = serverIp,
            srcPort = 54321,
            dstPort = 53,
            dnsPayload = dnsPayload,
            dnsPayloadLength = dnsPayload.size
        )

        // Checksum-ul calculat peste un header IPv4 deja semnat trebuie sa dea exact 0 conform RFC 1071
        assertEquals(0, DnsPacketRelay.computeIpv4HeaderChecksum(packet, 20))

        val parsed = DnsPacketRelay.parseIpv4UdpPacket(packet, packet.size)
        assertNotNull(parsed)
        assertArrayEquals(clientIp, parsed!!.srcIp)
        assertArrayEquals(serverIp, parsed.dstIp)
        assertEquals(54321, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertArrayEquals(dnsPayload, parsed.payload)
    }

    @Test
    fun verifyDnsTurboQuestionParserTtlAndServeStaleCache() {
        DnsTurboEngine.clearCache()

        val initialQuery = buildWireDnsQuery(txId = 0x4A2B, domain = "YouTube.COM", qType = 1)
        val parsedQ = DnsTurboEngine.parseQuestion(initialQuery)
        assertNotNull(parsedQ)
        assertEquals("youtube.com|1|1", parsedQ!!.cacheKey)

        val wireAnswer = buildWireDnsARecordAnswer(
            query = initialQuery,
            ttlSeconds = 90,
            ipv4Bytes = byteArrayOf(142.toByte(), 250.toByte(), 180.toByte(), 14)
        )
        assertEquals(90, DnsTurboEngine.extractCacheTtlSeconds(wireAnswer, parsedQ.questionLengthBytes))

        // Eroare SERVFAIL (RCODE=2) nu trebuie stocata niciodata in cache
        val servfail = wireAnswer.copyOf().apply { this[3] = ((this[3].toInt() and 0xF0) or 0x02).toByte() }
        assertEquals(0, DnsTurboEngine.extractCacheTtlSeconds(servfail, parsedQ.questionLengthBytes))

        // Validare Watchdog DNS: accepta raspuns NOERROR cu ANCOUNT >= 1 si respinge SERVFAIL/REFUSED/Query
        assertTrue(NetworkDiagnosticsEngine.isValidDnsSuccessResponse(wireAnswer, wireAnswer.size, 0x4A2B))
        assertFalse(NetworkDiagnosticsEngine.isValidDnsSuccessResponse(servfail, servfail.size, 0x4A2B))
        assertFalse(NetworkDiagnosticsEngine.isValidDnsSuccessResponse(initialQuery, initialQuery.size, 0x4A2B))
        assertFalse(NetworkDiagnosticsEngine.isValidDnsSuccessResponse(wireAnswer, wireAnswer.size, 0x1111))

        // Validare scor Portal Captiv Wi-Fi = 35
        val captiveScore = NetworkDiagnosticsEngine.calculatePureHealthScore(
            hasActiveNet = true,
            cellular = CellularTelemetry(),
            wifi = WifiTelemetry(isConnected = true, rssiDbm = -52),
            probe = ProbeSnapshot(
                isInternetReachable = false,
                isCaptivePortal = true,
                lastCheckedTimestampMs = 1000L
            )
        )
        assertEquals(35, captiveScore)

        val baseTimeMs = 1_000_000L
        DnsTurboEngine.storeInCache(parsedQ, wireAnswer, nowMs = baseTimeMs)

        // 1. Fresh Hit la +30s cu alt Transaction ID si alta capitalizare 0x20
        val secondQuery = buildWireDnsQuery(txId = 0x7E09, domain = "yOuTuBe.cOm", qType = 1)
        val secondParsed = DnsTurboEngine.parseQuestion(secondQuery)!!
        val freshLookup = DnsTurboEngine.lookupCache(secondQuery, secondParsed, nowMs = baseTimeMs + 30_000L)
        assertTrue(freshLookup is DnsTurboEngine.CacheLookupResult.FreshHit)
        val freshBytes = (freshLookup as DnsTurboEngine.CacheLookupResult.FreshHit).responsePayload
        assertEquals(0x7E, freshBytes[0].toInt() and 0xFF)
        assertEquals(0x09, freshBytes[1].toInt() and 0xFF)

        // 2. Serve-Stale Hit (RFC 8767) la +120s (dupa expirarea TTL-ului de 90s, dar in fereastra de 30 min)
        val staleLookup = DnsTurboEngine.lookupCache(secondQuery, secondParsed, nowMs = baseTimeMs + 120_000L)
        assertTrue(staleLookup is DnsTurboEngine.CacheLookupResult.StaleHit)

        // 3. Miss complet dupa expirarea ferestrei Serve-Stale
        val expiredLookup = DnsTurboEngine.lookupCache(secondQuery, secondParsed, nowMs = baseTimeMs + 3_000_000L)
        assertTrue(expiredLookup is DnsTurboEngine.CacheLookupResult.Miss)
    }

    @Test
    fun verifyModemEpbseUnsignedBitmaskEncodingAndDecoding() {
        val romanianAndTddBands = listOf(1, 3, 7, 8, 20, 28, 32, 38, 40, 41)
        val words = ModemHardwareBridge.encodeLteBandWords(romanianAndTddBands, 8)

        // B32 seteaza bitul 31 din primul cuvant LTE: trebuie sa fie pozitiv fara semn (> 2^31 - 1)
        assertTrue(words[0] > Int.MAX_VALUE.toLong())
        // B38, B40, B41 sunt in al doilea cuvant LTE (33..64)
        assertTrue(words[1] > 0L)

        val decoded = ModemHardwareBridge.decodeLteBandWords(words)
        assertEquals(romanianAndTddBands, decoded)
    }

    @Test
    fun verifyHealthScoreScenarios() {
        val strongScore = NetworkDiagnosticsEngine.calculatePureHealthScore(
            hasActiveNet = true,
            cellular = CellularTelemetry(networkGeneration = "4G LTE", rsrpDbm = -80, sinrDb = 16),
            wifi = WifiTelemetry(isConnected = false),
            probe = ProbeSnapshot(isInternetReachable = true, ipLatencyMs = 22, jitterMs = 4, lastCheckedTimestampMs = 1000L)
        )
        assertEquals(100, strongScore)

        val zombieScore = NetworkDiagnosticsEngine.calculatePureHealthScore(
            hasActiveNet = true,
            cellular = CellularTelemetry(networkGeneration = "4G LTE", rsrpDbm = -84, sinrDb = 12),
            wifi = WifiTelemetry(isConnected = false),
            probe = ProbeSnapshot(isInternetReachable = false, consecutiveFailures = 2, lastCheckedTimestampMs = 1000L)
        )
        assertEquals(12, zombieScore)

        val trap2gScore = NetworkDiagnosticsEngine.calculatePureHealthScore(
            hasActiveNet = true,
            cellular = CellularTelemetry(networkGeneration = "2G EDGE", rsrpDbm = -88),
            wifi = WifiTelemetry(isConnected = false),
            probe = ProbeSnapshot(isInternetReachable = true, ipLatencyMs = 220, lastCheckedTimestampMs = 1000L)
        )
        assertTrue(trap2gScore <= 45)
    }

    @Test
    fun verifyQuickSettingsTileSubtitleFormatting() {
        val active4g = NetStabilTileService.buildSubtitle(
            running = true,
            wifiConnected = false,
            wifiFreqMhz = 0,
            wifiRssi = null,
            lteBand = 3,
            netGen = "4G LTE",
            cellRsrp = -102,
            score = 67
        )
        assertEquals("B3 • -102 dBm", active4g)

        val activeWifi = NetStabilTileService.buildSubtitle(
            running = true,
            wifiConnected = true,
            wifiFreqMhz = 5180,
            wifiRssi = -58,
            lteBand = null,
            netGen = "4G LTE",
            cellRsrp = -95,
            score = 92
        )
        assertEquals("Wi-Fi 5G • -58 dBm", activeWifi)

        ro.netstabil.app.i18n.I18n.updateLanguage(ro.netstabil.app.data.AppLanguage.RO)
        val stoppedWithDbmRo = NetStabilTileService.buildSubtitle(
            running = false,
            wifiConnected = false,
            wifiFreqMhz = 0,
            wifiRssi = null,
            lteBand = 20,
            netGen = "4G LTE",
            cellRsrp = -104,
            score = 60
        )
        assertEquals("Oprit • -104 dBm", stoppedWithDbmRo)

        ro.netstabil.app.i18n.I18n.updateLanguage(ro.netstabil.app.data.AppLanguage.EN)
        val stoppedWithDbmEn = NetStabilTileService.buildSubtitle(
            running = false,
            wifiConnected = false,
            wifiFreqMhz = 0,
            wifiRssi = null,
            lteBand = 20,
            netGen = "4G LTE",
            cellRsrp = -104,
            score = 60
        )
        assertEquals("Off • -104 dBm", stoppedWithDbmEn)
        ro.netstabil.app.i18n.I18n.updateLanguage(ro.netstabil.app.data.AppLanguage.AUTO)
    }

    @Test
    fun verifyBilingualLocalizationAndLanguageResolution() {
        assertTrue(
            ro.netstabil.app.i18n.I18n.resolveIsRomanian(
                ro.netstabil.app.data.AppLanguage.AUTO,
                java.util.Locale("ro", "RO")
            )
        )
        assertFalse(
            ro.netstabil.app.i18n.I18n.resolveIsRomanian(
                ro.netstabil.app.data.AppLanguage.AUTO,
                java.util.Locale.US
            )
        )
        assertTrue(
            ro.netstabil.app.i18n.I18n.resolveIsRomanian(
                ro.netstabil.app.data.AppLanguage.RO,
                java.util.Locale.US
            )
        )
        assertFalse(
            ro.netstabil.app.i18n.I18n.resolveIsRomanian(
                ro.netstabil.app.data.AppLanguage.EN,
                java.util.Locale("ro", "RO")
            )
        )

        assertEquals("Putere Maximă", StabilizerProfile.MAX_POWER.title(true))
        assertEquals("Max Power", StabilizerProfile.MAX_POWER.title(false))
        assertEquals("Conexiune stabilă", ro.netstabil.app.data.ConnectionHeadline.STABLE.text(true))
        assertEquals("Connection stable", ro.netstabil.app.data.ConnectionHeadline.STABLE.text(false))
        assertEquals("MOD SHIZUKU", ro.netstabil.app.privileged.PrivilegeTier.SHIZUKU.short(true))
        assertEquals("SHIZUKU MODE", ro.netstabil.app.privileged.PrivilegeTier.SHIZUKU.short(false))
        val b20 = LteBandCalculator.fromEarfcn(6300)!!
        assertTrue(b20.coverageRole(true).contains("Acoperire"))
        assertTrue(b20.coverageRole(false).contains("indoor"))

        val b71 = LteBandCalculator.fromEarfcn(68600)!!
        assertEquals(71, b71.bandNumber)
        assertEquals(600, b71.frequencyMhz)
    }

    @Test
    fun verifyDnsTurboUpstreamClientAnswerValidation() {
        // Pachet valid NOERROR (QR=1, TC=0, RCODE=0 -> flags = 0x8180)
        val okPacket = byteArrayOf(
            0x12, 0x34,
            0x81.toByte(), 0x80.toByte(),
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00
        )
        assertTrue(ro.netstabil.app.service.DnsTurboEngine.isValidClientDnsAnswer(okPacket, okPacket.size, 0x1234))
        assertFalse(ro.netstabil.app.service.DnsTurboEngine.isValidClientDnsAnswer(okPacket, okPacket.size, 0x9999))

        // NXDOMAIN autentic (RCODE=3 -> flags = 0x8183) este raspuns valid de domeniu inexistent
        val nxPacket = okPacket.copyOf().apply { this[3] = 0x83.toByte() }
        assertTrue(ro.netstabil.app.service.DnsTurboEngine.isValidClientDnsAnswer(nxPacket, nxPacket.size, 0x1234))

        // REFUSED (RCODE=5 -> 0x8185), SERVFAIL (RCODE=2 -> 0x8182) si FORMERR (RCODE=1 -> 0x8181) TREBUIE respinse
        val refusedPacket = okPacket.copyOf().apply { this[3] = 0x85.toByte() }
        val servfailPacket = okPacket.copyOf().apply { this[3] = 0x82.toByte() }
        val formerrPacket = okPacket.copyOf().apply { this[3] = 0x81.toByte() }
        assertFalse(ro.netstabil.app.service.DnsTurboEngine.isValidClientDnsAnswer(refusedPacket, refusedPacket.size, 0x1234))
        assertFalse(ro.netstabil.app.service.DnsTurboEngine.isValidClientDnsAnswer(servfailPacket, servfailPacket.size, 0x1234))
        assertFalse(ro.netstabil.app.service.DnsTurboEngine.isValidClientDnsAnswer(formerrPacket, formerrPacket.size, 0x1234))

        // Pachet trunchiat (TC=1 -> 0x8380) TREBUIE respins pentru a declansa fallback pe DoH/DoT TCP
        val truncatedPacket = okPacket.copyOf().apply { this[2] = 0x83.toByte() }
        assertFalse(ro.netstabil.app.service.DnsTurboEngine.isValidClientDnsAnswer(truncatedPacket, truncatedPacket.size, 0x1234))
    }

    @Test
    fun verifyRrcTurboDecisionMatrix() {
        val maxPower = StabilizerSettings(
            profile = StabilizerProfile.MAX_POWER,
            keepAliveEnabled = true,
            rrcTurboEnabled = true
        )
        assertTrue(
            StabilizerForegroundService.shouldRunRrcTurbo(
                settings = maxPower,
                cell = CellularTelemetry(rsrpDbm = -85, sinrDb = 15),
                isWifiConnected = false
            )
        )
        assertFalse(
            StabilizerForegroundService.shouldRunRrcTurbo(
                settings = maxPower,
                cell = CellularTelemetry(rsrpDbm = -105, sinrDb = -2),
                isWifiConnected = true
            )
        )

        val balanced = StabilizerSettings(
            profile = StabilizerProfile.BALANCED,
            keepAliveEnabled = true,
            rrcTurboEnabled = true
        )
        assertTrue(
            StabilizerForegroundService.shouldRunRrcTurbo(
                settings = balanced,
                cell = CellularTelemetry(rsrpDbm = -102, sinrDb = 6),
                isWifiConnected = false
            )
        )
        assertFalse(
            StabilizerForegroundService.shouldRunRrcTurbo(
                settings = balanced,
                cell = CellularTelemetry(rsrpDbm = -82, sinrDb = 14),
                isWifiConnected = false
            )
        )
    }

    @Test
    fun verifyWidgetCompactLabelFormatting() {
        assertEquals(
            "Vodafone B20",
            NetStabilWidgetProvider.formatCompactWidgetNetworkLabel(
                wifiConnected = false,
                wifiFrequencyMhz = 0,
                operatorName = "Vodafone RO",
                lteBandNumber = 20,
                netGeneration = "4G LTE"
            )
        )
        assertEquals(
            "Orange B3",
            NetStabilWidgetProvider.formatCompactWidgetNetworkLabel(
                wifiConnected = false,
                wifiFrequencyMhz = 0,
                operatorName = "Orange Romania",
                lteBandNumber = 3,
                netGeneration = "4G+ LTE-A"
            )
        )
        assertEquals(
            "Wi-Fi 5 GHz",
            NetStabilWidgetProvider.formatCompactWidgetNetworkLabel(
                wifiConnected = true,
                wifiFrequencyMhz = 5240,
                operatorName = "Vodafone RO",
                lteBandNumber = 3,
                netGeneration = "4G LTE"
            )
        )
    }

    @Test
    fun verifyDnsProvidersEncryptedEndpointsConfig() {
        assertEquals("https://1.1.1.1/dns-query", ro.netstabil.app.data.DnsProvider.CLOUDFLARE.dohEndpointUrl)
        assertEquals("https://8.8.8.8/dns-query", ro.netstabil.app.data.DnsProvider.GOOGLE.dohEndpointUrl)
        assertEquals("https://94.140.14.14/dns-query", ro.netstabil.app.data.DnsProvider.ADGUARD.dohEndpointUrl)
        // Quad9 foloseste DoT 853 (dns.quad9.net) deoarece endpoint-ul HTTP/1.1 returneaza HTTP 505
        assertEquals("", ro.netstabil.app.data.DnsProvider.QUAD9.dohEndpointUrl)
        assertEquals("dns.quad9.net", ro.netstabil.app.data.DnsProvider.QUAD9.dotHostname)
    }

    @Test
    fun verifyWifiScoreParamsMonotonicityAcrossCutoffRange() {
        for (cutoff in -88..-68) {
            val expr = ro.netstabil.app.privileged.PrivilegeManager.buildWifiScoreParams(cutoff)
            val groups = expr.split(',')
            assertEquals(2, groups.size)
            for (g in groups) {
                val nums = g.substringAfter('=').split(':').map { it.toInt() }
                assertEquals(4, nums.size)
                assertTrue("Monotonic ascending failed for $expr", nums[0] <= nums[1] && nums[1] <= nums[2] && nums[2] <= nums[3])
            }
        }
    }

    @Test
    fun verifyHyperOsWhitelistMergeAndCleanFactoryRemoval() {
        val mergedFromNull = ro.netstabil.app.privileged.PrivilegeManager.mergePackageIntoCsvWhitelist(
            "null",
            "ro.netstabil.app"
        )
        assertEquals("ro.netstabil.app", mergedFromNull)

        val mergedExisting = ro.netstabil.app.privileged.PrivilegeManager.mergePackageIntoCsvWhitelist(
            "com.whatsapp, com.google.android.gms",
            "ro.netstabil.app"
        )
        assertEquals("com.whatsapp,com.google.android.gms,ro.netstabil.app", mergedExisting)

        // Idempotent la reaplicare
        val mergedAgain = ro.netstabil.app.privileged.PrivilegeManager.mergePackageIntoCsvWhitelist(
            mergedExisting,
            "ro.netstabil.app"
        )
        assertEquals(mergedExisting, mergedAgain)

        // Eliminare curata la revenirea la setarile din fabrica fara sa stearga celelalte aplicatii
        val cleaned = ro.netstabil.app.privileged.PrivilegeManager.removePackageFromCsvWhitelist(
            mergedAgain,
            "ro.netstabil.app"
        )
        assertEquals("com.whatsapp,com.google.android.gms", cleaned)

        // Sanitizare stricta contra intrarilor corupte din Settings.System
        val sanitized = ro.netstabil.app.privileged.PrivilegeManager.mergePackageIntoCsvWhitelist(
            "com.whatsapp, bad'injection;rm, null, org.telegram.messenger",
            "ro.netstabil.app"
        )
        assertEquals("com.whatsapp,org.telegram.messenger,ro.netstabil.app", sanitized)
    }

    @Test
    fun verifyDsrmFiveStepFormatAndDeepTuningActiveCount() {
        val durations = ro.netstabil.app.privileged.PrivilegeManager.FAST_DSRM_DURATIONS.split(',')
        val actions = ro.netstabil.app.privileged.PrivilegeManager.FAST_DSRM_ACTIONS.split(',')
        assertEquals(5, durations.size)
        assertEquals(5, actions.size)
        // Pasul 2 (REREGISTER) si Pasul 4 (RESET_MODEM) raman false pentru siguranta apelurilor de voce
        assertEquals("false", actions[2])
        assertEquals("false", actions[4])

        val status = ro.netstabil.app.data.DeepTuningStatus(
            globalKernelTweaksVerified = true,
            carrierConfigOverridden = true,
            hyperOsShieldActive = true,
            dualSimPolicyActive = false,
            detectedSimCount = 1
        )
        assertEquals(3, status.activeCount)
        assertEquals(3, status.totalApplicableCount)

        val dualSimStatus = status.copy(dualSimPolicyActive = true, detectedSimCount = 2)
        assertEquals(4, dualSimStatus.activeCount)
        assertEquals(4, dualSimStatus.totalApplicableCount)
    }

    @Test
    fun verifyHardwareBasebandKeepaliveIpv4FilterAndMultipathMask() {
        // Vodafone RO / Orange RO CGNAT IPv4 (100.64.x.x si 10.x.x.x) -> acceptate in baseband
        assertTrue(
            ro.netstabil.app.network.HardwareModemKeepaliveOffload.isUsableBasebandIpv4(
                byteArrayOf(100, 64, 12, 45)
            )
        )
        assertTrue(
            ro.netstabil.app.network.HardwareModemKeepaliveOffload.isUsableBasebandIpv4(
                byteArrayOf(10, 124, 88, 19)
            )
        )

        // Loopback 127.0.0.1 si RFC 7335 464XLAT clatd 192.0.0.4 -> excluse curat (evita ERROR_INVALID_IP_ADDRESS)
        assertFalse(
            ro.netstabil.app.network.HardwareModemKeepaliveOffload.isUsableBasebandIpv4(
                byteArrayOf(127, 0, 0, 1)
            )
        )
        assertFalse(
            ro.netstabil.app.network.HardwareModemKeepaliveOffload.isUsableBasebandIpv4(
                byteArrayOf(192.toByte(), 0, 0, 4)
            )
        )

        // Masca Multipath AOSP: HANDOVER (1) | RELIABILITY (2) | PERFORMANCE (4) = 7
        assertEquals("7", ro.netstabil.app.privileged.PrivilegeManager.MULTIPATH_ALL_ENABLED)
        assertEquals(10, ro.netstabil.app.network.HardwareModemKeepaliveOffload.PREFERRED_INTERVAL_SEC)
        assertEquals(
            15,
            ro.netstabil.app.network.HardwareModemKeepaliveOffload.nextFallbackIntervalSec(10)
        )
        assertEquals(
            30,
            ro.netstabil.app.network.HardwareModemKeepaliveOffload.nextFallbackIntervalSec(15)
        )
        assertNull(
            ro.netstabil.app.network.HardwareModemKeepaliveOffload.nextFallbackIntervalSec(30)
        )
    }

    private fun buildWireDnsQuery(txId: Int, domain: String, qType: Int): ByteArray {
        val baos = ByteArrayOutputStream(64)
        val dos = DataOutputStream(baos)
        dos.writeShort(txId)
        dos.writeShort(0x0100) // Standard query, RD=1
        dos.writeShort(1) // QDCOUNT
        dos.writeShort(0) // ANCOUNT
        dos.writeShort(0)
        dos.writeShort(0)
        for (label in domain.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0)
        dos.writeShort(qType)
        dos.writeShort(1) // IN
        dos.flush()
        return baos.toByteArray()
    }

    private fun buildWireDnsARecordAnswer(
        query: ByteArray,
        ttlSeconds: Int,
        ipv4Bytes: ByteArray
    ): ByteArray {
        val baos = ByteArrayOutputStream(128)
        val dos = DataOutputStream(baos)
        dos.write(query)
        // Pointer la numele intrebarii (offset 12 -> 0xC00C)
        dos.writeShort(0xC00C)
        dos.writeShort(1) // Type A
        dos.writeShort(1) // Class IN
        dos.writeInt(ttlSeconds)
        dos.writeShort(4) // RDLENGTH
        dos.write(ipv4Bytes)
        dos.flush()
        val raw = baos.toByteArray()
        // Setam QR=1, RD=1, RA=1 -> 0x8180 si ANCOUNT=1
        raw[2] = 0x81.toByte()
        raw[3] = 0x80.toByte()
        raw[6] = 0x00
        raw[7] = 0x01
        return raw
    }
}
