package app.touchgrass

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

class BackgroundTrackingService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "app.touchgrass.START"
        const val ACTION_STOP  = "app.touchgrass.STOP"

        private const val CHANNEL_TRACKING = "tg_tracking"
        private const val CHANNEL_ALERTS   = "tg_alerts"
        private const val NOTIF_FOREGROUND  = 1
        private const val NOTIF_IDLE        = 2
        private const val NOTIF_PHOTO       = 3

        private const val PING_INTERVAL_MS      = 20_000L
        private const val IDLE_THRESHOLD_MS     = 2 * 60 * 60 * 1000L   // 2 h
        private const val PHOTO_NOTIF_INTERVAL  = 30 * 60 * 1000L        // 30 min
        private const val PING_MAX_DELTA_S      = 60
        private const val MAX_ACCURACY_M        = 100f
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null

    private var lastLocation: Location? = null
    private var stepBase: Int = -1
    private var currentSteps: Int = 0

    // ---- lifecycle -------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createChannels()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_FOREGROUND, buildForegroundNotif("Tracking outdoor time…"))
        startStepSensor()
        startLocationUpdates()
        startPingLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        fusedClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    // ---- step sensor ----------------------------------------------------

    private fun startStepSensor() {
        stepSensor?.let {
            val prefs = prefs()
            stepBase = prefs.getInt("stepSensorBase", -1)
            currentSteps = prefs.getInt("steps", 0)
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val raw = event.values[0].toInt()
        val p = prefs()
        if (stepBase < 0) {
            stepBase = raw
            p.edit().putInt("stepSensorBase", stepBase).apply()
        }
        currentSteps = raw - stepBase
        p.edit().putInt("steps", currentSteps).apply()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---- location -------------------------------------------------------

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            lastLocation = result.lastLocation
        }
    }

    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, PING_INTERVAL_MS)
            .setMinUpdateIntervalMillis(10_000)
            .build()
        try {
            fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    // ---- ping loop ------------------------------------------------------

    private fun startPingLoop() {
        scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                lastLocation?.let { loc ->
                    if (loc.accuracy <= MAX_ACCURACY_M) doPing(loc)
                }
            }
        }
    }

    private suspend fun doPing(loc: Location) {
        val p = prefs()
        val url    = p.getString("supabaseUrl", null)    ?: return
        val key    = p.getString("supabaseKey", null)    ?: return
        val token  = p.getString("accessToken", null)    ?: return
        val userId = p.getString("userId", null)          ?: return
        val homeLat = p.getFloat("homeLat", Float.NaN).toDouble()
        val homeLng = p.getFloat("homeLng", Float.NaN).toDouble()
        val homeRadius = p.getFloat("homeRadius", 100f).toDouble()
        if (homeLat.isNaN()) return

        val dist = haversine(homeLat, homeLng, loc.latitude, loc.longitude)
        val outside = dist > homeRadius
        val now = System.currentTimeMillis()

        p.edit().putBoolean("isOutside", outside).apply()

        // Supabase REST ping
        scope.launch {
            try {
                val lastPingMs = p.getLong("lastPingMs", 0L)
                val rawDelta = ((now - lastPingMs) / 1000).toInt()
                val delta = if (outside && lastPingMs > 0) rawDelta.coerceAtMost(PING_MAX_DELTA_S) else 0

                val patch = if (outside) {
                    """{"last_ping_at":"${isoNow()}","total_seconds":{"${"$"}add":$delta}}"""
                } else {
                    """{"last_ping_at":"${isoNow()}","active_session_id":null}"""
                }
                supabasePatch(url, key, token, "profiles", "user_id=eq.$userId", patch)
                p.edit().putLong("lastPingMs", now).apply()
            } catch (_: Exception) {}
        }

        // Idle notification (2 h inside)
        if (!outside) {
            val lastInside = p.getLong("lastInsideMs", now)
            if (lastInside == now) p.edit().putLong("lastInsideMs", now).apply()
            if (now - lastInside >= IDLE_THRESHOLD_MS) {
                showIdleNotification()
                p.edit().putLong("lastInsideMs", now).apply()
            }
        } else {
            p.edit().putLong("lastInsideMs", now).apply()
        }

        // Photo notification (every 30 min outside)
        if (outside) {
            val lastPhoto = p.getLong("lastPhotoNotifMs", 0L)
            if (now - lastPhoto >= PHOTO_NOTIF_INTERVAL) {
                showPhotoNotification()
                p.edit().putLong("lastPhotoNotifMs", now).apply()
            }
        }
    }

    // ---- Supabase REST --------------------------------------------------

    private fun supabasePatch(baseUrl: String, apiKey: String, token: String, table: String, filter: String, body: String) {
        val conn = URL("$baseUrl/rest/v1/$table?$filter").openConnection() as HttpURLConnection
        conn.requestMethod = "PATCH"
        conn.setRequestProperty("apikey", apiKey)
        if (!apiKey.startsWith("sb_publishable_")) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Prefer", "return=minimal")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.responseCode // execute
        conn.disconnect()
    }

    // ---- notifications --------------------------------------------------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, "Tracking", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Background outdoor tracking" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Go-outside reminders" }
        )
    }

    private fun buildForegroundNotif(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_grass)
            .setContentTitle("Touch Grass")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun showIdleNotification() {
        val dismiss = PendingIntent.getBroadcast(
            this, NOTIF_IDLE,
            Intent(this, NotificationActionReceiver::class.java).putExtra("notifId", NOTIF_IDLE),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_grass)
            .setContentTitle("Time to touch grass!")
            .setContentText("You've been inside for 2 hours. Get outside and earn time!")
            .addAction(0, "Dismiss", dismiss)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(this).notify(NOTIF_IDLE, n) } catch (_: SecurityException) {}
    }

    private fun showPhotoNotification() {
        val open = PendingIntent.getActivity(
            this, NOTIF_PHOTO,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val dismiss = PendingIntent.getBroadcast(
            this, NOTIF_PHOTO,
            Intent(this, NotificationActionReceiver::class.java).putExtra("notifId", NOTIF_PHOTO),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_grass)
            .setContentTitle("Snap a photo for +20s!")
            .setContentText("You're outside — take a photo to earn a bonus.")
            .setContentIntent(open)
            .addAction(0, "Dismiss", dismiss)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(this).notify(NOTIF_PHOTO, n) } catch (_: SecurityException) {}
    }

    // ---- helpers --------------------------------------------------------

    private fun prefs() = getSharedPreferences("TouchGrassPrefs", Context.MODE_PRIVATE)

    private fun isoNow(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * R * asin(sqrt(a))
    }
}
