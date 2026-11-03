package ro.netstabil.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ro.netstabil.app.NetStabilApp
import ro.netstabil.app.data.EventSeverity
import ro.netstabil.app.service.StabilizerForegroundService

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pending = goAsync()
            val app = context.applicationContext as NetStabilApp
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val settings = app.controller.settingsRepository.getSnapshot()
                    if (settings.startOnBoot && settings.stabilizerRunningWanted) {
                        app.controller.settingsRepository.appendEvent(
                            EventSeverity.INFO,
                            ro.netstabil.app.i18n.trNow(
                                "Repornire automată a stabilizării după pornirea telefonului.",
                                "Auto-resumed signal stabilization after device boot."
                            )
                        )
                        StabilizerForegroundService.start(context, fromForegroundUi = false)
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
