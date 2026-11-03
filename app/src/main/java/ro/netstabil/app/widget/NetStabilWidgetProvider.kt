package ro.netstabil.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ro.netstabil.app.MainActivity
import ro.netstabil.app.NetStabilApp
import ro.netstabil.app.R
import ro.netstabil.app.network.LteBandCalculator
import java.io.File
import java.security.SecureRandom

class NetStabilWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pending = goAsync()
        val app = context.applicationContext as NetStabilApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!app.controller.isServiceRunning.value) {
                    runCatching { app.controller.refreshTelemetryAndProbeOnce() }
                }
                renderWidgetsInternal(context.applicationContext, appWidgetManager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action == ACTION_WIDGET_REFRESH || action == ACTION_WIDGET_TOGGLE) {
            val token = intent.getStringExtra(EXTRA_WIDGET_TOKEN)
            if (token.isNullOrEmpty() || token != getOrCreateWidgetToken(context)) {
                return
            }
        }
        when (action) {
            ACTION_WIDGET_REFRESH -> {
                val pending = goAsync()
                val app = context.applicationContext as NetStabilApp
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        runCatching { app.controller.refreshTelemetryAndProbeOnce() }
                        updateAllWidgets(context.applicationContext)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_WIDGET_TOGGLE -> {
                val pending = goAsync()
                val app = context.applicationContext as NetStabilApp
                val controller = app.controller
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (controller.isServiceRunning.value) {
                            controller.stopStabilizer()
                        } else {
                            controller.startStabilizerFromUi()
                        }
                        updateAllWidgets(context.applicationContext)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_REFRESH = "ro.netstabil.app.widget.ACTION_REFRESH"
        const val ACTION_WIDGET_TOGGLE = "ro.netstabil.app.widget.ACTION_TOGGLE"
        private const val EXTRA_WIDGET_TOKEN = "extra_widget_auth_token"

        @Volatile
        private var cachedWidgetToken: String? = null
        private val tokenLock = Any()

        private fun getOrCreateWidgetToken(context: Context): String {
            cachedWidgetToken?.let { return it }
            synchronized(tokenLock) {
                cachedWidgetToken?.let { return it }
                val appCtx = context.applicationContext
                val file = File(appCtx.noBackupFilesDir, ".widget_auth_token")
                val existing = runCatching {
                    if (file.exists()) file.readText(Charsets.UTF_8).trim() else ""
                }.getOrDefault("")
                if (existing.length >= 32) {
                    cachedWidgetToken = existing
                    return existing
                }
                val bytes = ByteArray(16)
                SecureRandom().nextBytes(bytes)
                val generated = bytes.joinToString("") { "%02x".format(it) }
                runCatching { file.writeText(generated, Charsets.UTF_8) }
                cachedWidgetToken = generated
                return generated
            }
        }

        fun updateAllWidgets(context: Context) {
            runCatching {
                val appCtx = context.applicationContext
                val mgr = AppWidgetManager.getInstance(appCtx)
                val ids = mgr.getAppWidgetIds(ComponentName(appCtx, NetStabilWidgetProvider::class.java))
                if (ids.isNotEmpty()) {
                    renderWidgetsInternal(appCtx, mgr, ids)
                }
            }
        }

        private fun renderWidgetsInternal(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val app = context.applicationContext as NetStabilApp
            val controller = app.controller
            val isRunning = controller.isServiceRunning.value
            val cell = controller.signalMonitor.cellular.value
            val wifi = controller.signalMonitor.wifi.value
            val probe = controller.probeSnapshot.value
            val score = controller.healthScore.value
            val headline = controller.headline.value

            val isRo = ro.netstabil.app.i18n.I18n.currentIsRo
            val scorePart = if (score > 0) " ($score/100)" else ""
            val titleState = if (isRunning) {
                "NetStabil$scorePart • ${headline.text(isRo)}"
            } else {
                val passiveTxt = if (isRo) "Monitorizare pasivă" else "Passive monitoring"
                "NetStabil$scorePart • $passiveTxt"
            }

            val netBandLabel = formatCompactWidgetNetworkLabel(
                wifiConnected = wifi.isConnected,
                wifiFrequencyMhz = wifi.frequencyMhz,
                operatorName = cell.operatorName,
                lteBandNumber = cell.bandInfo?.bandNumber,
                netGeneration = cell.networkGeneration
            )

            val activeDbm = if (wifi.isConnected) wifi.rssiDbm else cell.rsrpDbm
            val dbmLabel = activeDbm?.let { "$it dBm" } ?: "-- dBm"
            val dbmColor = computeDbmArgb(activeDbm, wifi.isConnected)
            val pingLabel = probe.ipLatencyMs?.let { "$it ms" } ?: "-- ms"

            val openAppIntent = PendingIntent.getActivity(
                context,
                201,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val authToken = getOrCreateWidgetToken(context)

            val refreshIntent = PendingIntent.getBroadcast(
                context,
                202,
                Intent(context, NetStabilWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_REFRESH
                    putExtra(EXTRA_WIDGET_TOKEN, authToken)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val toggleIntent = PendingIntent.getBroadcast(
                context,
                203,
                Intent(context, NetStabilWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_TOGGLE
                    putExtra(EXTRA_WIDGET_TOKEN, authToken)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            for (widgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_netstabil)
                views.setTextViewText(R.id.widget_title_state, titleState)
                views.setTextViewText(R.id.widget_network_band, netBandLabel)
                views.setTextViewText(R.id.widget_dbm, dbmLabel)
                views.setTextColor(R.id.widget_dbm, dbmColor)
                views.setTextViewText(R.id.widget_ping_score, pingLabel)

                if (isRunning) {
                    views.setTextViewText(R.id.widget_btn_toggle, if (isRo) "ACTIV" else "ACTIVE")
                    views.setTextColor(R.id.widget_btn_toggle, Color.parseColor("#032217"))
                    views.setInt(
                        R.id.widget_btn_toggle,
                        "setBackgroundResource",
                        R.drawable.bg_widget_pill_active
                    )
                } else {
                    views.setTextViewText(R.id.widget_btn_toggle, if (isRo) "PORNEȘTE" else "START")
                    views.setTextColor(R.id.widget_btn_toggle, Color.parseColor("#F8FAFC"))
                    views.setInt(
                        R.id.widget_btn_toggle,
                        "setBackgroundResource",
                        R.drawable.bg_widget_pill_inactive
                    )
                }

                views.setOnClickPendingIntent(R.id.widget_info_zone, openAppIntent)
                views.setOnClickPendingIntent(R.id.widget_btn_refresh, refreshIntent)
                views.setOnClickPendingIntent(R.id.widget_btn_toggle, toggleIntent)

                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }

        fun formatCompactWidgetNetworkLabel(
            wifiConnected: Boolean,
            wifiFrequencyMhz: Int,
            operatorName: String,
            lteBandNumber: Int?,
            netGeneration: String
        ): String {
            if (wifiConnected) {
                return LteBandCalculator.shortWifiBandLabel(wifiFrequencyMhz)
            }
            val opClean = operatorName
                .replace(Regex("\\s+RO$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+Romania$", RegexOption.IGNORE_CASE), "")
                .trim()
                .take(10)
            val band = lteBandNumber?.let { "B$it" }
                ?: netGeneration.replace(" LTE-A", "").replace(" LTE", "")
            return "$opClean $band".trim()
        }

        fun computeDbmArgb(dbm: Int?, isWifi: Boolean): Int {
            if (dbm == null) return Color.parseColor("#94A3B8")
            return if (isWifi) {
                when {
                    dbm >= -67 -> Color.parseColor("#10B981")
                    dbm >= -78 -> Color.parseColor("#F59E0B")
                    else -> Color.parseColor("#EF4444")
                }
            } else {
                when {
                    dbm >= -95 -> Color.parseColor("#10B981")
                    dbm >= -108 -> Color.parseColor("#F59E0B")
                    else -> Color.parseColor("#EF4444")
                }
            }
        }
    }
}
