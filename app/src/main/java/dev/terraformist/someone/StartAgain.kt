package dev.terraformist.someone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After the phone restarts (or the app updates), carry on sharing if it was paired. */
class StartAgain : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                SomeoneWidget.refreshAll(context)
                // Android only lets location sharing start from here with "Allow all the time".
                if (Where.hasBackground(context)) SharingService.start(context)
            }
        }
    }
}
