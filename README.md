# NetStabil

**Real 4G LTE / 5G & Wi-Fi Signal Stabilizer, Baseband Keepalive, Turbo DNS & Rootless Modem Tuner for Android 10-16**
**Stabilizator Real de Semnal 4G LTE / 5G si Wi-Fi, Keepalive Baseband, Turbo DNS si Control Modem Fara Root (Android 10-16)**

* **Package ID:** `ro.netstabil.app`
* **Latest Release:** [`v1.6.0` (`versionCode = 13`)](https://github.com/Laurentiu-2000/NetStabil/releases/latest)
* **Platform:** Android 10 (API 29) up to Android 16 (API 36), Kotlin + Jetpack Compose Material 3
* **Languages:** English & Romana (auto-detected + instant in-app `RO / EN` toggle)
* **License:** GNU General Public License v3.0 or later (`GPL-3.0-or-later`)
* **Signing Certificate SHA-256:** `2ff7b82971faf4d7fa3b3c1114c54f661c185ee9b7dab2b3227f783c3e9a844c`
* **Privacy:** Zero ads, zero analytics, zero trackers, zero proprietary SDKs, 100% FOSS

### Download / Descarcare

* **Direct APK (GitHub Releases):** [Download `NetStabil-v1.6.0.apk`](https://github.com/Laurentiu-2000/NetStabil/releases/download/v1.6.0/NetStabil-v1.6.0.apk)
* **Obtainium (Auto-Updates from GitHub):** Add repository URL `https://github.com/Laurentiu-2000/NetStabil` in [Obtainium](https://github.com/ImranR98/Obtainium).

---

## English Documentation

### Honest Technical Scope (No Placebo Claims)

No mobile application can violate RF physics or raise hardware transmit power beyond 3GPP/regulatory limits encoded in the baseband processor. Fake "signal booster" apps merely toggle Airplane Mode or display dummy animations.

**NetStabil** solves the actual engineering causes of mobile data freezes, high tail latency, and signal drops in weak-coverage areas:
1. **RRC State Demotion (`RRC_CONNECTED` -> `RRC_IDLE`):** Carrier eNodeB towers demote idle 4G radios within 5-10 seconds, introducing 200-800 ms wake-up latency and failed handshakes on weak indoor coverage.
2. **Carrier CGNAT UDP/TCP Session Drops:** Aggressive carrier NAT tables silently drop idle sockets without sending `RST`/`FIN` ("zombie data" where 4G bars appear full while packets hang).
3. **Accidental 2G EDGE/GPRS Fallback & Sticky Weak Wi-Fi:** Devices cling to unusable `-85 dBm` Wi-Fi or drop to 2G where data throughput is effectively dead.
4. **Slow Unencrypted Carrier DNS:** Packet loss on UDP port 53 stalls every new connection before TCP/TLS even starts.

### Tri-Mode Architecture

NetStabil automatically adapts to the highest privilege tier available on the device:

1. **Normal Mode (Unprivileged Standard Android):**
   * **Hardware Cellular Radio Anchor:** Pins the LTE/5G cellular transport in the OS kernel via `ConnectivityManager.requestNetwork` + `bindSocket` + passive low-latency slice monitoring.
   * **Baseband Hardware Keepalive Offload (0% CPU):** Uses `ConnectivityManager.createSocketKeepalive` (`NattSocketKeepalive`, Android CDD 7.4.2.6) to offload keepalive packets directly into the modem's baseband chip (`10s -> 15s -> 30s` adaptive interval) with zero CPU wakeups.
   * **RRC Anti-Dormancy Turbo (`3.2s`):** Sends `IPTOS_LOWDELAY` micro-pulses (~0.2 MB/hour) below the 5-second eNodeB dormancy timer.
   * **Kernel Data Stall Listener & Dual-Path Watchdog:** Combines `ConnectivityDiagnosticsManager` (`API 30+` DNS timeout and TCP packet-loss callbacks) with TCP + HTTP 204 reachability checks (including Captive Portal detection) and AOSP double-sequence `reportNetworkConnectivity(true -> false)` revalidation.
   * **Split-Route Turbo DNS Tunnel:** Routes *only* `/32` DNS resolver IPs locally (99.99% of traffic bypasses the tunnel directly over hardware 4G/Wi-Fi), providing a `0 ms` RAM LRU cache, RFC 8767 Serve-Stale, parallel Happy Eyeballs UDP racing, and encrypted DoH (RFC 8484) / DoT (RFC 7858) fallback (Cloudflare `1.1.1.1`, Quad9 `9.9.9.9`, Google `8.8.8.8`, AdGuard `94.140.14.14`).

2. **Shizuku Mode (Rootless ADB UID 2000 — No Bootloader Unlock Required):**
   * Grants permanent `WRITE_SECURE_SETTINGS` so Private DNS (DoT) runs natively in Android without any local VPN service.
   * **Direct Modem Hardware Control:** Switches directly to `4G LTE Only` via `ITelephony` (`setAllowedNetworkTypesForReason`), locks specific 3GPP LTE bands (`B1, B3, B7, B8, B20, B28, B38, B40`) via MediaTek RIL AT passthrough (`AT+EPBSE` / `AT+EPBSEH`) or Android Radio HAL (`setSystemSelectionChannels`), and locks serving cell `EARFCN / PCI` (`AT+EMMCHLCK`).
   * **5 Deep System Layers (with 1-Tap Factory Restore):**
     1. In-memory `ICarrierConfigLoader` override (`persistent = false` for Android 16 safety): unlocks VoLTE, VoWiFi, 4G+ Carrier Aggregation threshold, and disables `prefer_2g_bool`.
     2. Fast `DataStallRecoveryManager` (`dsrm_duration_millis` reduced to 12-30s while keeping voice calls 100% protected) + `DnsResolver` tuning.
     3. Instant 0ms Wi-Fi/LTE handover (`mobile_data_always_on`, QUIC Multipath `= 7`, calibrated monotonic `wifi_score_params`, and `cmd wifi` high-performance mode).
     4. Screen-off Doze/Freezer shield (prevents HyperOS Greezer / Android cached-app freezer from killing sockets when the screen is off).
     5. Dual-SIM automatic emergency data failover (`AUTO_DATA_SWITCH`).
   * Automatic Mobile Data session reset (`svc data` / `cmd phone data`) and weak Wi-Fi cut-off without interrupting active GSM/VoLTE calls.

3. **Root Mode (`su` UID 0):**
   * Includes everything in Shizuku Mode plus direct TTY modem access and Linux kernel TCP `BBR` congestion control + `tcp_fastopen` + aggressive kernel TCP keepalive sysctls.

### Reproducible Builds & FOSS Compliance (F-Droid / IzzyOnDroid)

* `dependenciesInfo { includeInApk = false; includeInBundle = false }` strips Google's proprietary encrypted `0x504b4453` (`DEPENDENCY_INFO_BLOCK`) from the APK signing block.
* `vcsInfo.include = false` ensures deterministic builds across clean source archives and git tags.
* Release builds compile cleanly without any keystore (`./gradlew assembleRelease`), producing `app/build/outputs/apk/release/app-release-unsigned.apk` when `KEYSTORE_PASSWORD` is unset.

```bash
./gradlew testDebugUnitTest lintRelease assembleRelease
```

### Permissions & Network Security Transparency

NetStabil contains **zero analytics, zero ads, zero telemetry SDKs, and zero remote accounts**. Every declared Android permission exists strictly for local radio stabilization or 3GPP telemetry:

| Permission / Declaration | Technical Purpose |
| :--- | :--- |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Required by AOSP `TelephonyManager.requestCellInfoUpdate()` / `getAllCellInfo()` and `WifiInfo` to read 3GPP serving cell `RSRP`, `RSRQ`, `SINR`, `CQI`, `EARFCN`, `PCI`, `eNodeB`, and Wi-Fi `SSID`. Never used for GPS coordinates or stored outside volatile device RAM. App remains functional on Wi-Fi and basic cellular stabilization even if Location is denied. |
| `ACCESS_BACKGROUND_LOCATION` | Optional; required by Android 10+ if the user enables auto-start on boot or background cell/band monitoring while the screen is off. |
| `READ_PHONE_STATE` | Reads active Dual-SIM data `subId`, network generation (`4G LTE`, `4G+`, `5G NSA/NR`), and checks `CALL_STATE_IDLE` so automatic radio recovery never interrupts an active voice call. |
| `BIND_VPN_SERVICE` (`LocalDnsVpnService`) | Used exclusively in unprivileged Normal Mode to intercept local DNS port `53` (`10.215.173.2/30`) for `0 ms` RAM caching, Happy Eyeballs UDP racing, and DoH/DoT fallback. Non-DNS traffic never enters the tunnel. Automatically bypassed when native Android Private DNS is configured via Shizuku/Root. |
| `FOREGROUND_SERVICE` + `SPECIAL_USE` | Keeps the persistent cellular `NetworkCallback` anchor, NATT keepalive offload, and RRC anti-idle loop alive without OS termination. |
| `WRITE_SECURE_SETTINGS` + Shizuku | Optional; granted via Shizuku (`UID 2000`) to configure Private DNS, `dsrm_duration_millis`, `wifi_score_params`, Multipath QUIC, and screen-off Doze/Freezer exemptions without Root, with 1-tap Factory Defaults rollback. |
| Cleartext HTTP (`network_security_config.xml`) | `<base-config cleartextTrafficPermitted="false" />` blocks cleartext globally; plaintext HTTP is permitted solely on `cp.cloudflare.com` (primary, vendor-neutral) and `connectivitycheck.gstatic.com` (fallback) to detect Wi-Fi Captive Portals (`HTTP 204` vs `302/200` redirect per RFC 8910). |

---

## Documentatie in Limba Romana

### Principiu Tehnic Onest (Fara Efect Placebo)

Nicio aplicatie nu poate schimba legile fizicii sau creste puterea de emisie hardware peste limitele din cipul radio. **NetStabil** rezolva cauzele reale pentru care conexiunea 4G/Wi-Fi ingheata sau are lag in zonele cu semnal slab (testat si calibrat inclusiv pe Xiaomi Redmi Note 15 4G / HyperOS Android 16 pe Vodafone RO si Motorola Moto G31 pe Orange RO):

* **Ancorare Hardware Modem 4G + Keepalive direct in Cipul Baseband (0% CPU):** Mentine interfata celulara activa in kernel si deleaga pachetele NATT keepalive direct procesorului radio al modemului (`10s -> 15s -> 30s`).
* **Mod Turbo RRC Anti-Adormire (3.2s):** Previne trecerea antenei in `RRC_IDLE` sub pragul de 5 secunde cu un consum infim (~0.2 MB/ora).
* **Tunel Turbo DNS / Private DNS Nativ:** Cache RAM `0 ms`, Serve-Stale (RFC 8767), interogari UDP paralele si fallback criptat DoH/DoT, cu promovare automata la Private DNS nativ Android (fara VPN) imediat ce Shizuku sau Root este activ.
* **Control Direct Modem & Plafon Maxim prin Shizuku (Fara Root):** Comutare `Doar 4G LTE` din aplicatie, blocare benzi LTE (`B20` acoperire interior / `B3, B1, B7` viteza), blocare stalp `PCI / EARFCN`, profil SIM `CarrierConfig` (VoLTE + VoWiFi + 4G+), reparare rapida `DSRM` (12-30s), `Multipath QUIC`, scut anti-inghetare HyperOS cu ecranul stins si buton dedicat **Valori Fabrica**.
* **Buton Rapid in Bara de Sus (Quick Settings Tile) si Widget Live pe Ecran:** Afiseaza operatorul, banda 4G, puterea in `dBm` si ping-ul in timp real.

### Activare Shizuku Fara Wi-Fi Acasa (Inclusiv Xiaomi HyperOS / Android 16)

1. In *Setari -> Optiuni dezvoltator*, activati o singura data si **Depanare USB (Setari de securitate)** (specific Xiaomi/HyperOS).
2. Porniti serviciul din aplicatia **Shizuku** prin Wi-Fi sau printr-un Hotspot de 30 de secunde de pe alt telefon (*Depanare Wireless*).
3. Dupa ce NetStabil afiseaza insigna verde **MOD SHIZUKU**, puteti opri *Depanare Wireless* si Hotspot-ul — pastrati doar comutatorul general *Optiuni dezvoltator* pornit ca Shizuku sa ramana activ pe 4G pana la urmatorul restart al telefonului.

---

## License

Copyright (C) 2026 NetStabil Project Contributors

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. See [LICENSE](LICENSE) for the full license text.
