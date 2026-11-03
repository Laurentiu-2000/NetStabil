package ro.netstabil.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "netstabil_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class SettingsRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventIdCounter = AtomicLong(System.currentTimeMillis())
    private val eventsPersistMutex = Mutex()

    private object Keys {
        val PROFILE = stringPreferencesKey("profile")
        val KEEPALIVE_ENABLED = booleanPreferencesKey("keepalive_enabled")
        val PIN_CELLULAR_RADIO = booleanPreferencesKey("pin_cellular_radio")
        val RRC_TURBO_ENABLED = booleanPreferencesKey("rrc_turbo_enabled")
        val AUTO_RECONNECT_ENABLED = booleanPreferencesKey("auto_reconnect_enabled")
        val ANTI_2G_TRAP_ENABLED = booleanPreferencesKey("anti_2g_trap_enabled")
        val SMART_WIFI_CUTOFF = booleanPreferencesKey("smart_wifi_cutoff")
        val WIFI_CUTOFF_DBM = intPreferencesKey("wifi_cutoff_dbm")
        val CARRIER_CONFIG_BOOST = booleanPreferencesKey("carrier_config_boost")
        val FAST_KERNEL_DATA_STALL = booleanPreferencesKey("fast_kernel_data_stall")
        val CONNECTIVITY_RF_BOOST = booleanPreferencesKey("connectivity_rf_boost")
        val HYPEROS_SLEEP_SHIELD = booleanPreferencesKey("hyperos_sleep_shield")
        val DUAL_SIM_AUTO_SWITCH = booleanPreferencesKey("dual_sim_auto_switch")
        val RESTRICT_POWERKEEPER_BG = booleanPreferencesKey("restrict_powerkeeper_bg")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val DNS_PROVIDER = stringPreferencesKey("dns_provider")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val WANTED_RUNNING = booleanPreferencesKey("wanted_running")
        val EVENTS_JSON = stringPreferencesKey("events_json")
    }

    val settingsFlow: Flow<StabilizerSettings> = context.dataStore.data.map { prefs ->
        val profileName = prefs[Keys.PROFILE] ?: StabilizerProfile.MAX_POWER.name
        val dnsName = prefs[Keys.DNS_PROVIDER] ?: DnsProvider.CARRIER_DEFAULT.name
        val langName = prefs[Keys.APP_LANGUAGE] ?: AppLanguage.AUTO.name
        val resolvedLang = runCatching { AppLanguage.valueOf(langName) }
            .getOrDefault(AppLanguage.AUTO)
        ro.netstabil.app.i18n.I18n.updateLanguage(resolvedLang)
        StabilizerSettings(
            profile = runCatching { StabilizerProfile.valueOf(profileName) }
                .getOrDefault(StabilizerProfile.MAX_POWER),
            keepAliveEnabled = prefs[Keys.KEEPALIVE_ENABLED] ?: true,
            pinCellularRadioEnabled = prefs[Keys.PIN_CELLULAR_RADIO] ?: true,
            rrcTurboEnabled = prefs[Keys.RRC_TURBO_ENABLED] ?: true,
            autoReconnectEnabled = prefs[Keys.AUTO_RECONNECT_ENABLED] ?: true,
            anti2gTrapEnabled = prefs[Keys.ANTI_2G_TRAP_ENABLED] ?: true,
            smartWifiCutoffEnabled = prefs[Keys.SMART_WIFI_CUTOFF] ?: true,
            wifiCutoffRssiDbm = (prefs[Keys.WIFI_CUTOFF_DBM] ?: -80).coerceIn(-92, -65),
            carrierConfigBoostEnabled = prefs[Keys.CARRIER_CONFIG_BOOST] ?: true,
            fastKernelDataStallEnabled = prefs[Keys.FAST_KERNEL_DATA_STALL] ?: true,
            connectivityRfBoostEnabled = prefs[Keys.CONNECTIVITY_RF_BOOST] ?: true,
            hyperOsSleepShieldEnabled = prefs[Keys.HYPEROS_SLEEP_SHIELD] ?: true,
            dualSimAutoSwitchEnabled = prefs[Keys.DUAL_SIM_AUTO_SWITCH] ?: true,
            restrictXiaomiPowerKeeperBg = prefs[Keys.RESTRICT_POWERKEEPER_BG] ?: false,
            startOnBoot = prefs[Keys.START_ON_BOOT] ?: true,
            dnsProvider = runCatching { DnsProvider.valueOf(dnsName) }
                .getOrDefault(DnsProvider.CARRIER_DEFAULT),
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            appLanguage = resolvedLang,
            stabilizerRunningWanted = prefs[Keys.WANTED_RUNNING] ?: false
        )
    }

    private val _events = MutableStateFlow<List<StabilizerEvent>>(emptyList())
    val events: StateFlow<List<StabilizerEvent>> = _events.asStateFlow()

    init {
        scope.launch {
            val prefs = context.dataStore.data.first()
            val raw = prefs[Keys.EVENTS_JSON]
            if (!raw.isNullOrBlank()) {
                val diskEvents = decodeEvents(raw)
                _events.update { inMemory ->
                    if (inMemory.isEmpty()) {
                        diskEvents
                    } else {
                        (inMemory + diskEvents).distinctBy { it.id }.take(150)
                    }
                }
            }
        }
    }

    suspend fun getSnapshot(): StabilizerSettings = settingsFlow.first()

    suspend fun setProfile(profile: StabilizerProfile) {
        context.dataStore.edit { it[Keys.PROFILE] = profile.name }
    }

    suspend fun setKeepAliveEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEPALIVE_ENABLED] = enabled }
    }

    suspend fun setPinCellularRadioEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PIN_CELLULAR_RADIO] = enabled }
    }

    suspend fun setRrcTurboEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.RRC_TURBO_ENABLED] = enabled }
    }

    suspend fun setAutoReconnectEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_RECONNECT_ENABLED] = enabled }
    }

    suspend fun setAnti2gTrapEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ANTI_2G_TRAP_ENABLED] = enabled }
    }

    suspend fun setSmartWifiCutoffEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SMART_WIFI_CUTOFF] = enabled }
    }

    suspend fun setWifiCutoffDbm(dbm: Int) {
        context.dataStore.edit { it[Keys.WIFI_CUTOFF_DBM] = dbm.coerceIn(-92, -65) }
    }

    suspend fun setCarrierConfigBoostEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CARRIER_CONFIG_BOOST] = enabled }
    }

    suspend fun setFastKernelDataStallEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FAST_KERNEL_DATA_STALL] = enabled }
    }

    suspend fun setConnectivityRfBoostEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CONNECTIVITY_RF_BOOST] = enabled }
    }

    suspend fun setHyperOsSleepShieldEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HYPEROS_SLEEP_SHIELD] = enabled }
    }

    suspend fun setDualSimAutoSwitchEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DUAL_SIM_AUTO_SWITCH] = enabled }
    }

    suspend fun setRestrictXiaomiPowerKeeperBg(enabled: Boolean) {
        context.dataStore.edit { it[Keys.RESTRICT_POWERKEEPER_BG] = enabled }
    }

    suspend fun resetDeepTweaksFlagsToFactory() {
        context.dataStore.edit {
            it[Keys.CARRIER_CONFIG_BOOST] = false
            it[Keys.FAST_KERNEL_DATA_STALL] = false
            it[Keys.CONNECTIVITY_RF_BOOST] = false
            it[Keys.HYPEROS_SLEEP_SHIELD] = false
            it[Keys.DUAL_SIM_AUTO_SWITCH] = false
            it[Keys.RESTRICT_POWERKEEPER_BG] = false
        }
    }

    suspend fun setStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { it[Keys.START_ON_BOOT] = enabled }
    }

    suspend fun setDnsProvider(provider: DnsProvider) {
        context.dataStore.edit { it[Keys.DNS_PROVIDER] = provider.name }
    }

    suspend fun setDarkTheme(dark: Boolean) {
        context.dataStore.edit { it[Keys.DARK_THEME] = dark }
    }

    suspend fun setAppLanguage(lang: AppLanguage) {
        ro.netstabil.app.i18n.I18n.updateLanguage(lang)
        context.dataStore.edit { it[Keys.APP_LANGUAGE] = lang.name }
    }

    suspend fun setStabilizerWanted(wanted: Boolean) {
        context.dataStore.edit { it[Keys.WANTED_RUNNING] = wanted }
    }

    fun appendEvent(severity: EventSeverity, messageRo: String) {
        val entry = StabilizerEvent(
            id = eventIdCounter.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            severity = severity,
            messageRo = messageRo
        )
        _events.update { current -> (listOf(entry) + current).take(150) }
        scope.launch {
            eventsPersistMutex.withLock {
                val snapshot = _events.value
                val encoded = encodeEvents(snapshot)
                context.dataStore.edit { it[Keys.EVENTS_JSON] = encoded }
            }
        }
    }

    fun clearEvents() {
        _events.value = emptyList()
        scope.launch {
            eventsPersistMutex.withLock {
                context.dataStore.edit { it.remove(Keys.EVENTS_JSON) }
            }
        }
    }

    private fun encodeEvents(list: List<StabilizerEvent>): String {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("ts", item.timestampMs)
            obj.put("sev", item.severity.name)
            obj.put("msg", item.messageRo)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun decodeEvents(raw: String): List<StabilizerEvent> {
        return runCatching {
            val arr = JSONArray(raw)
            val result = ArrayList<StabilizerEvent>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val sevName = obj.optString("sev", EventSeverity.INFO.name)
                result.add(
                    StabilizerEvent(
                        id = obj.optLong("id", i.toLong()),
                        timestampMs = obj.optLong("ts", 0L),
                        severity = runCatching { EventSeverity.valueOf(sevName) }
                            .getOrDefault(EventSeverity.INFO),
                        messageRo = obj.optString("msg", "")
                    )
                )
            }
            result
        }.getOrDefault(emptyList())
    }
}
