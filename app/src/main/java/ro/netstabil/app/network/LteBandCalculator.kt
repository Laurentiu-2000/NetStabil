package ro.netstabil.app.network

import ro.netstabil.app.data.LteBandInfo

object LteBandCalculator {

    /**
     * Rezolvare directa dupa numarul benzii 3GPP (ex. raportat de CellIdentityLte.getBands() pe API 30+).
     */
    fun fromBandNumber(bandNumber: Int): LteBandInfo? {
        return when (bandNumber) {
            1 -> LteBandInfo(
                bandNumber = 1,
                frequencyMhz = 2100,
                duplexMode = "FDD",
                commonName = "B1 (2100 MHz)",
                coverageRoleRo = "Capacitate ridicată 4G / 4G+ (Vodafone / Orange / Digi)",
                coverageRoleEn = "High-capacity 4G / 4G+ layer (2100 MHz FDD)"
            )
            2 -> LteBandInfo(
                bandNumber = 2,
                frequencyMhz = 1900,
                duplexMode = "FDD",
                commonName = "B2 (1900 MHz)",
                coverageRoleRo = "Capacitate medie FDD",
                coverageRoleEn = "Mid-band FDD capacity layer"
            )
            3 -> LteBandInfo(
                bandNumber = 3,
                frequencyMhz = 1800,
                duplexMode = "FDD",
                commonName = "B3 (1800 MHz)",
                coverageRoleRo = "Bandă principală de viteză 4G (Toți operatorii RO)",
                coverageRoleEn = "Primary high-speed 4G LTE band (1800 MHz FDD)"
            )
            4 -> LteBandInfo(
                bandNumber = 4,
                frequencyMhz = 1700,
                duplexMode = "FDD",
                commonName = "B4 (1700 MHz)",
                coverageRoleRo = "Capacitate FDD",
                coverageRoleEn = "AWS FDD capacity band"
            )
            5 -> LteBandInfo(
                bandNumber = 5,
                frequencyMhz = 850,
                duplexMode = "FDD",
                commonName = "B5 (850 MHz)",
                coverageRoleRo = "Acoperire frecvență joasă",
                coverageRoleEn = "Sub-1 GHz wide coverage band"
            )
            7 -> LteBandInfo(
                bandNumber = 7,
                frequencyMhz = 2600,
                duplexMode = "FDD",
                commonName = "B7 (2600 MHz)",
                coverageRoleRo = "Viteză urbană maximă, penetrare mai slabă prin pereți",
                coverageRoleEn = "Peak urban speed, shorter indoor wall penetration"
            )
            8 -> LteBandInfo(
                bandNumber = 8,
                frequencyMhz = 900,
                duplexMode = "FDD",
                commonName = "B8 (900 MHz)",
                coverageRoleRo = "Acoperire bună în interior și zone rurale",
                coverageRoleEn = "Strong indoor and rural coverage (900 MHz)"
            )
            12 -> LteBandInfo(
                bandNumber = 12,
                frequencyMhz = 700,
                duplexMode = "FDD",
                commonName = "B12 (700 MHz)",
                coverageRoleRo = "Acoperire extinsă",
                coverageRoleEn = "Extended 700 MHz long-range coverage"
            )
            13 -> LteBandInfo(
                bandNumber = 13,
                frequencyMhz = 700,
                duplexMode = "FDD",
                commonName = "B13 (700 MHz)",
                coverageRoleRo = "Acoperire extinsă",
                coverageRoleEn = "Extended 700 MHz long-range coverage"
            )
            17 -> LteBandInfo(
                bandNumber = 17,
                frequencyMhz = 700,
                duplexMode = "FDD",
                commonName = "B17 (700 MHz)",
                coverageRoleRo = "Acoperire extinsă",
                coverageRoleEn = "Extended 700 MHz coverage"
            )
            18 -> LteBandInfo(
                bandNumber = 18,
                frequencyMhz = 850,
                duplexMode = "FDD",
                commonName = "B18 (850 MHz)",
                coverageRoleRo = "Acoperire extinsă",
                coverageRoleEn = "Extended 850 MHz coverage"
            )
            19 -> LteBandInfo(
                bandNumber = 19,
                frequencyMhz = 850,
                duplexMode = "FDD",
                commonName = "B19 (850 MHz)",
                coverageRoleRo = "Acoperire extinsă",
                coverageRoleEn = "Extended 850 MHz coverage"
            )
            20 -> LteBandInfo(
                bandNumber = 20,
                frequencyMhz = 800,
                duplexMode = "FDD",
                commonName = "B20 (800 MHz)",
                coverageRoleRo = "Acoperire lungă interior/rural (Vodafone / Orange / Digi)",
                coverageRoleEn = "Long-range indoor & rural coverage (800 MHz FDD)"
            )
            26 -> LteBandInfo(
                bandNumber = 26,
                frequencyMhz = 850,
                duplexMode = "FDD",
                commonName = "B26 (850 MHz)",
                coverageRoleRo = "Acoperire extinsă",
                coverageRoleEn = "Extended 850 MHz coverage"
            )
            28 -> LteBandInfo(
                bandNumber = 28,
                frequencyMhz = 700,
                duplexMode = "FDD",
                commonName = "B28 (700 MHz)",
                coverageRoleRo = "Penetrare maximă clădiri și distanțe lungi (Orange RO)",
                coverageRoleEn = "Maximum building penetration & long range (700 MHz)"
            )
            32 -> LteBandInfo(
                bandNumber = 32,
                frequencyMhz = 1500,
                duplexMode = "SDL",
                commonName = "B32 (1500 SDL)",
                coverageRoleRo = "Canal suplimentar de descărcare 4G+",
                coverageRoleEn = "Supplemental downlink 4G+ carrier"
            )
            38 -> LteBandInfo(
                bandNumber = 38,
                frequencyMhz = 2600,
                duplexMode = "TDD",
                commonName = "B38 (2600 TDD)",
                coverageRoleRo = "Capacitate TDD urbană densă (Digi / Orange)",
                coverageRoleEn = "Dense urban TDD capacity (2600 MHz)"
            )
            40 -> LteBandInfo(
                bandNumber = 40,
                frequencyMhz = 2300,
                duplexMode = "TDD",
                commonName = "B40 (2300 TDD)",
                coverageRoleRo = "Capacitate TDD 2300 MHz (Digi)",
                coverageRoleEn = "High-density TDD capacity (2300 MHz)"
            )
            41 -> LteBandInfo(
                bandNumber = 41,
                frequencyMhz = 2500,
                duplexMode = "TDD",
                commonName = "B41 (2500 TDD)",
                coverageRoleRo = "Capacitate TDD",
                coverageRoleEn = "High-capacity TDD band (2500 MHz)"
            )
            66 -> LteBandInfo(
                bandNumber = 66,
                frequencyMhz = 1700,
                duplexMode = "FDD",
                commonName = "B66 (AWS-3)",
                coverageRoleRo = "Capacitate extinsă FDD",
                coverageRoleEn = "Extended AWS-3 FDD capacity"
            )
            71 -> LteBandInfo(
                bandNumber = 71,
                frequencyMhz = 600,
                duplexMode = "FDD",
                commonName = "B71 (600 MHz)",
                coverageRoleRo = "Acoperire ultra-lungă 600 MHz",
                coverageRoleEn = "Ultra-long-range 600 MHz coverage"
            )
            else -> null
        }
    }

    /**
     * Tabel conform 3GPP TS 36.101 pentru conversia exacta EARFCN Downlink -> Banda 4G LTE.
     */
    fun fromEarfcn(earfcn: Int): LteBandInfo? {
        if (earfcn < 0 || earfcn == Int.MAX_VALUE) return null
        val band = when (earfcn) {
            in 0..599 -> 1
            in 600..1199 -> 2
            in 1200..1949 -> 3
            in 1950..2399 -> 4
            in 2400..2649 -> 5
            in 2750..3449 -> 7
            in 3450..3799 -> 8
            in 5010..5179 -> 12
            in 5180..5279 -> 13
            in 5730..5849 -> 17
            in 5850..5999 -> 18
            in 6000..6149 -> 19
            in 6150..6449 -> 20
            in 8690..9039 -> 26
            in 9210..9659 -> 28
            in 9920..10359 -> 32
            in 37750..38249 -> 38
            in 38650..39649 -> 40
            in 39650..41589 -> 41
            in 66436..67335 -> 66
            in 68586..68935 -> 71
            else -> -1
        }
        return if (band > 0) fromBandNumber(band) else null
    }

    /**
     * Indicele CQI (1..15) conform 3GPP TS 36.213 Table 7.2.3-1 indica schema reala de modulatie radio.
     */
    fun describeCqiModulationRo(cqi: Int): String = describeCqiModulation(cqi, isRo = true)

    fun describeCqiModulation(cqi: Int, isRo: Boolean): String = if (isRo) {
        when {
            cqi >= 13 -> "256-QAM (Viteză maximă)"
            cqi >= 10 -> "64-QAM (Viteză ridicată)"
            cqi >= 7 -> "16-QAM (Viteză medie)"
            cqi >= 1 -> "QPSK (Acoperire de bază)"
            else -> "Necunoscut"
        }
    } else {
        when {
            cqi >= 13 -> "256-QAM (Peak speed)"
            cqi >= 10 -> "64-QAM (High speed)"
            cqi >= 7 -> "16-QAM (Medium speed)"
            cqi >= 1 -> "QPSK (Base coverage)"
            else -> "Unknown"
        }
    }

    fun wifiChannelFromFrequency(freqMhz: Int, isRo: Boolean = true): String {
        val word = if (isRo) "Canal" else "Ch"
        return when {
            freqMhz == 2484 -> "2.4 GHz ($word 14)"
            freqMhz in 2412..2472 -> {
                val ch = (freqMhz - 2407) / 5
                "2.4 GHz ($word $ch)"
            }
            freqMhz in 5170..5835 -> {
                val ch = (freqMhz - 5000) / 5
                "5 GHz ($word $ch)"
            }
            freqMhz in 5955..7115 -> {
                val ch = (freqMhz - 5950) / 5
                "6 GHz ($word $ch)"
            }
            else -> "--"
        }
    }

    fun shortWifiBandLabel(freqMhz: Int): String {
        return when {
            freqMhz in 2400..2500 -> "Wi-Fi 2.4 GHz"
            freqMhz in 4900..5900 -> "Wi-Fi 5 GHz"
            freqMhz >= 5925 -> "Wi-Fi 6 GHz"
            else -> "Wi-Fi"
        }
    }

    /**
     * Unele modemuri MediaTek (ex. Android 11 RIL) raporteaza RSSNR in zecimi de dB (ex. 145 = 14.5 dB),
     * in timp ce AOSP standard raporteaza direct dB (-20..30). Normalizeaza ambele variante.
     */
    fun normalizeMediaTekSinrDb(rawRssnr: Int): Int? {
        if (rawRssnr == Int.MAX_VALUE || rawRssnr == Int.MIN_VALUE) return null
        return when {
            rawRssnr in -25..40 -> rawRssnr
            rawRssnr in -250..400 -> rawRssnr / 10
            else -> null
        }
    }
}
