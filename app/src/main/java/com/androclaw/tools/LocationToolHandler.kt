package com.androclaw.tools

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.androclaw.utils.PermissionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class LocationToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: PermissionHelper
) {

    suspend fun execute(input: Map<String, Any>): String {
        val permError = permissionHelper.ensurePermissionsForTool(context, "get_location")
        if (permError != null) return permError

        val accuracy = (input["accuracy"] as? String)?.lowercase() ?: "high"
        val includeAddress = input["include_address"] as? Boolean ?: true

        return try {
            val location = getLocation(accuracy)
                ?: return "Could not determine location. Make sure location services are enabled."

            formatLocation(location, includeAddress)
        } catch (e: Exception) {
            "Failed to get location: ${e.message}"
        }
    }

    private suspend fun getLocation(accuracy: String): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Try to get last known location first (instant)
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        var bestLocation: Location? = null
        for (provider in providers) {
            try {
                @Suppress("MissingPermission")
                val loc = locationManager.getLastKnownLocation(provider)
                if (loc != null) {
                    if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                        bestLocation = loc
                    }
                }
            } catch (_: Exception) {}
        }

        // If we have a recent location (less than 2 minutes old), use it
        if (bestLocation != null) {
            val age = System.currentTimeMillis() - bestLocation.time
            if (age < 120_000) return bestLocation
        }

        // Request a fresh location with timeout
        val provider = if (accuracy == "high" && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LocationManager.GPS_PROVIDER
        } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            LocationManager.NETWORK_PROVIDER
        } else {
            return bestLocation // Return stale location if no provider available
        }

        val freshLocation = withTimeoutOrNull(10_000) {
            requestSingleLocation(locationManager, provider)
        }

        return freshLocation ?: bestLocation
    }

    @Suppress("MissingPermission")
    private suspend fun requestSingleLocation(
        locationManager: LocationManager,
        provider: String
    ): Location? = suspendCancellableCoroutine { cont ->
        try {
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }

                @Deprecated("Deprecated in API")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    if (cont.isActive) cont.resume(null)
                }
            }

            locationManager.requestSingleUpdate(provider, listener, context.mainLooper)

            cont.invokeOnCancellation {
                locationManager.removeUpdates(listener)
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun formatLocation(location: Location, includeAddress: Boolean): String {
        val sb = StringBuilder()
        sb.appendLine("Location:")
        sb.appendLine("  Latitude: ${location.latitude}")
        sb.appendLine("  Longitude: ${location.longitude}")
        sb.appendLine("  Accuracy: ${location.accuracy.toInt()}m")

        if (location.hasAltitude()) {
            sb.appendLine("  Altitude: ${location.altitude.toInt()}m")
        }
        if (location.hasSpeed()) {
            sb.appendLine("  Speed: ${"%.1f".format(location.speed * 3.6)} km/h")
        }

        val age = (System.currentTimeMillis() - location.time) / 1000
        sb.appendLine("  Age: ${if (age < 60) "${age}s" else "${age / 60}m"} ago")

        if (includeAddress) {
            val address = reverseGeocode(location.latitude, location.longitude)
            if (address != null) {
                sb.appendLine("  Address: $address")
            }
        }

        return sb.toString().trim()
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(lat: Double, lng: Double): String? {
        return try {
            if (!Geocoder.isPresent()) return null
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val parts = mutableListOf<String>()
                // Build address from components
                addr.thoroughfare?.let { parts.add(it) }  // Street
                addr.subThoroughfare?.let { street ->
                    if (parts.isNotEmpty()) parts[0] = "$street ${parts[0]}"
                    else parts.add(street)
                }
                addr.locality?.let { parts.add(it) }  // City
                addr.adminArea?.let { parts.add(it) }  // State
                addr.countryName?.let { parts.add(it) }  // Country
                addr.postalCode?.let { parts.add(it) }

                if (parts.isEmpty()) addr.getAddressLine(0) else parts.joinToString(", ")
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
