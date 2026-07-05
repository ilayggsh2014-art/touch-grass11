package app.touchgrass

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
    name = "BackgroundService",
    permissions = [
        Permission(strings = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION], alias = "foregroundLocation"),
        Permission(strings = [Manifest.permission.ACTIVITY_RECOGNITION], alias = "activityRecognition"),
        Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = "notifications"),
        Permission(strings = [Manifest.permission.ACCESS_BACKGROUND_LOCATION], alias = "backgroundLocation"),
    ]
)
class BackgroundServicePlugin : Plugin() {

    // ---- JS API --------------------------------------------------------

    @PluginMethod
    fun requestForegroundPermissions(call: PluginCall) {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            call.resolve(JSObject().put("granted", true))
        } else {
            requestPermissionForAliases(arrayOf("foregroundLocation", "activityRecognition", "notifications"), call, "foregroundPermCallback")
        }
    }

    @PermissionCallback
    private fun foregroundPermCallback(call: PluginCall) {
        val loc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        call.resolve(JSObject().put("granted", loc))
    }

    @PluginMethod
    fun requestBackgroundLocation(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            call.resolve(JSObject().put("granted", true)); return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            call.resolve(JSObject().put("granted", true)); return
        }
        requestPermissionForAlias("backgroundLocation", call, "bgLocationCallback")
    }

    @PermissionCallback
    private fun bgLocationCallback(call: PluginCall) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        call.resolve(JSObject().put("granted", granted))
    }

    @PluginMethod
    fun startTracking(call: PluginCall) {
        val locGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!locGranted) { call.reject("foreground_location_required"); return }

        val svc = Intent(context, BackgroundTrackingService::class.java)
            .setAction(BackgroundTrackingService.ACTION_START)
        ContextCompat.startForegroundService(context, svc)
        call.resolve()
    }

    @PluginMethod
    fun stopTracking(call: PluginCall) {
        context.startService(Intent(context, BackgroundTrackingService::class.java).setAction(BackgroundTrackingService.ACTION_STOP))
        call.resolve()
    }

    @PluginMethod
    fun updateCredentials(call: PluginCall) {
        val prefs = context.getSharedPreferences("TouchGrassPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("supabaseUrl",  call.getString("supabaseUrl", ""))
            .putString("supabaseKey",  call.getString("supabaseKey", ""))
            .putString("accessToken",  call.getString("accessToken", ""))
            .putString("userId",       call.getString("userId", ""))
            .putFloat("homeLat",       call.getFloat("homeLat", 0f)!!)
            .putFloat("homeLng",       call.getFloat("homeLng", 0f)!!)
            .putFloat("homeRadius",    call.getFloat("homeRadius", 100f)!!)
            .apply()
        call.resolve()
    }

    @PluginMethod
    fun getSteps(call: PluginCall) {
        val prefs = context.getSharedPreferences("TouchGrassPrefs", Context.MODE_PRIVATE)
        call.resolve(JSObject().put("steps", prefs.getInt("steps", 0)))
    }

    @PluginMethod
    fun getStatus(call: PluginCall) {
        val prefs = context.getSharedPreferences("TouchGrassPrefs", Context.MODE_PRIVATE)
        call.resolve(JSObject()
            .put("steps", prefs.getInt("steps", 0))
            .put("isOutside", prefs.getBoolean("isOutside", false))
        )
    }
}
