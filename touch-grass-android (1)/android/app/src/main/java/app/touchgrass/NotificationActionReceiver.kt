package app.touchgrass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra("notifId", -1)
        if (notifId != -1) {
            NotificationManagerCompat.from(context).cancel(notifId)
        }
    }
}
