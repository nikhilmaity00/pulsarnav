package com.pulsarnav.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import com.pulsarnav.app.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class ParkedLocation(
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val timestampMs: Long
)

class ParkingManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pulsarnav_parking", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val KEY_LAT = "park_lat"
        private const val KEY_LNG = "park_lng"
        private const val KEY_ADDRESS = "park_address"
        private const val KEY_TIMESTAMP = "park_timestamp"

        @Volatile
        private var instance: ParkingManager? = null

        fun getInstance(context: Context): ParkingManager {
            return instance ?: synchronized(this) {
                instance ?: ParkingManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _lastParkedLocation = MutableStateFlow<ParkedLocation?>(loadSavedLocation())
    val lastParkedLocation: StateFlow<ParkedLocation?> = _lastParkedLocation.asStateFlow()

    private fun loadSavedLocation(): ParkedLocation? {
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lng = prefs.getString(KEY_LNG, null)?.toDoubleOrNull() ?: return null
        val address = prefs.getString(KEY_ADDRESS, "Saved Parking Spot") ?: "Saved Parking Spot"
        val time = prefs.getLong(KEY_TIMESTAMP, 0L)
        return ParkedLocation(lat, lng, address, time)
    }

    @SuppressLint("MissingPermission")
    fun onBikeDisconnected() {
        if (!AppSettings.getInstance(context).isAutoParkEnabled) return

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        var location: Location? = null

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }
            if (location == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: Exception) { }

        if (location != null) {
            saveLocation(location.latitude, location.longitude)
        }
    }

    fun saveLocation(lat: Double, lng: Double, customAddress: String? = null) {
        val timestamp = System.currentTimeMillis()
        scope.launch {
            val resolvedAddress = customAddress ?: resolveAddress(lat, lng)
            prefs.edit()
                .putString(KEY_LAT, lat.toString())
                .putString(KEY_LNG, lng.toString())
                .putString(KEY_ADDRESS, resolvedAddress)
                .putLong(KEY_TIMESTAMP, timestamp)
                .apply()

            _lastParkedLocation.value = ParkedLocation(lat, lng, resolvedAddress, timestamp)
        }
    }

    fun getWalkingDirectionsIntent(): Intent? {
        val loc = _lastParkedLocation.value ?: return null
        val uri = Uri.parse("google.navigation:q=${loc.latitude},${loc.longitude}&mode=w")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
    }

    fun getFallbackWalkingDirectionsIntent(): Intent? {
        val loc = _lastParkedLocation.value ?: return null
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${loc.latitude},${loc.longitude}&travelmode=walking")
        return Intent(Intent.ACTION_VIEW, uri)
    }

    private fun resolveAddress(lat: Double, lng: Double): String {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val list = geocoder.getFromLocation(lat, lng, 1)
            if (!list.isNullOrEmpty()) {
                val addr = list[0]
                val feature = addr.subLocality ?: addr.featureName ?: addr.thoroughfare
                val locality = addr.locality ?: addr.subAdminArea
                if (feature != null && locality != null) "$feature, $locality"
                else addr.getAddressLine(0) ?: "%.5f, %.5f".format(lat, lng)
            } else {
                "%.5f, %.5f".format(lat, lng)
            }
        } catch (_: Exception) {
            "%.5f, %.5f".format(lat, lng)
        }
    }

    fun getElapsedParkedTimeFormatted(): String {
        val loc = _lastParkedLocation.value ?: return "No record"
        val diffMs = System.currentTimeMillis() - loc.timestampMs
        val mins = (diffMs / 60000).toInt()
        val hours = mins / 60
        val days = hours / 24

        return when {
            days > 0 -> "$days d ago"
            hours > 0 -> "$hours h ago"
            mins > 0 -> "$mins m ago"
            else -> "Just now"
        }
    }
}
