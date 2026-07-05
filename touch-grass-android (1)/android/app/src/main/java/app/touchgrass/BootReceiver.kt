package app.touchgrass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("TouchGrassPrefs", Context.MODE_PRIVATE)
            val userId = prefs.getString("userId", null)
            val accessToken = prefs.getString("accessToken", null)
            if (!userId.isNullOrEmpty() && !accessToken.isNullOrEmpty()) {
                val svc = Intent(context, BackgroundTrackingService::class.java)
                    .setAction(BackgroundTrackingService.ACTION_START)
                ContextCompat.startForegroundService(context, svc)
            }
        }
    }
}
