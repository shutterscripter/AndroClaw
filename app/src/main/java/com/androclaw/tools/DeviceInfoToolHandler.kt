package com.androclaw.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceInfoToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun execute(input: Map<String, Any>): String {
        val query = input["query"] as? String ?: "all"

        return when (query.lowercase()) {
            "battery" -> getBatteryInfo()
            "storage", "disk" -> getStorageInfo()
            "memory", "ram" -> getMemoryInfo()
            "network", "wifi", "internet" -> getNetworkInfo()
            "device", "phone" -> getDeviceInfo()
            "screen" -> getScreenInfo()
            "all" -> getAllInfo()
            else -> "Unknown query: $query. Use: battery, storage, memory, network, device, screen, all"
        }
    }

    private fun getBatteryInfo(): String {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percentage = if (scale > 0) (level * 100) / scale else -1

        val status = when (batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }

        val plugged = when (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Not plugged in"
        }

        val temp = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val health = when (batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

        return """
Battery: $percentage%
Status: $status
Power source: $plugged
Temperature: ${temp}°C
Health: $health
        """.trim()
    }

    private fun getStorageInfo(): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.totalBytes
        val freeBytes = stat.availableBytes
        val usedBytes = totalBytes - freeBytes

        fun fmt(bytes: Long): String = when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            else -> "%.1f KB".format(bytes / 1024.0)
        }

        val usedPct = (usedBytes * 100) / totalBytes

        return """
Internal storage:
- Total: ${fmt(totalBytes)}
- Used: ${fmt(usedBytes)} ($usedPct%)
- Free: ${fmt(freeBytes)}
        """.trim()
    }

    private fun getMemoryInfo(): String {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        fun fmt(bytes: Long): String = "%.1f GB".format(bytes / 1_073_741_824.0)

        val usedRam = memInfo.totalMem - memInfo.availMem
        val usedPct = (usedRam * 100) / memInfo.totalMem

        return """
RAM:
- Total: ${fmt(memInfo.totalMem)}
- Used: ${fmt(usedRam)} ($usedPct%)
- Available: ${fmt(memInfo.availMem)}
- Low memory: ${memInfo.lowMemory}
        """.trim()
    }

    private fun getNetworkInfo(): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        val connectionType = when {
            capabilities == null -> "No connection"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Unknown"
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ssid = wifiInfo?.ssid?.replace("\"", "") ?: "Unknown"

        val downSpeed = capabilities?.linkDownstreamBandwidthKbps?.let { "${it / 1000} Mbps" } ?: "Unknown"
        val upSpeed = capabilities?.linkUpstreamBandwidthKbps?.let { "${it / 1000} Mbps" } ?: "Unknown"

        return """
Network:
- Connection: $connectionType
- WiFi SSID: $ssid
- Signal strength: ${wifiInfo?.rssi ?: "N/A"} dBm
- Download bandwidth: $downSpeed
- Upload bandwidth: $upSpeed
- Internet access: ${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false}
        """.trim()
    }

    private fun getDeviceInfo(): String {
        return """
Device:
- Brand: ${Build.BRAND}
- Model: ${Build.MODEL}
- Manufacturer: ${Build.MANUFACTURER}
- Android version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
- Security patch: ${Build.VERSION.SECURITY_PATCH}
- Device name: ${Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL}
        """.trim()
    }

    private fun getScreenInfo(): String {
        val displayMetrics = context.resources.displayMetrics
        val brightness = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            128
        )
        val brightnessPct = (brightness * 100) / 255
        val autoMode = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            0
        )

        return """
Screen:
- Resolution: ${displayMetrics.widthPixels} x ${displayMetrics.heightPixels}
- Density: ${displayMetrics.densityDpi} dpi
- Brightness: $brightnessPct%
- Auto-brightness: ${if (autoMode == 1) "On" else "Off"}
        """.trim()
    }

    private fun getAllInfo(): String {
        return listOf(
            getBatteryInfo(),
            getStorageInfo(),
            getMemoryInfo(),
            getNetworkInfo(),
            getDeviceInfo()
        ).joinToString("\n\n")
    }
}
