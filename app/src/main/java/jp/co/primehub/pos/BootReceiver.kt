package jp.co.primehub.pos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** after the tablet restarts, printing comes back by itself */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try { PrintService.start(context) } catch (_: Exception) {}
        }
    }
}
