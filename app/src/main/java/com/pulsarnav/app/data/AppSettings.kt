package com.pulsarnav.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pulsarnav_settings", Context.MODE_PRIVATE)

    companion object {
        const val KEY_AUTO_PARK = "feature_auto_park"
        const val KEY_RIDE_TRACKING = "feature_ride_tracking"
        const val KEY_MEDIA_CONTROL = "feature_media_control"
        const val KEY_WHATSAPP_ALERTS = "feature_whatsapp_alerts"
        const val KEY_MAINTENANCE_ALERTS = "feature_maintenance_alerts"
        const val KEY_TELEMETRY_SYNC = "feature_telemetry_sync"
        const val KEY_TWO_WHEELER_MODE = "feature_two_wheeler_mode"

        const val KEY_HOME_ADDRESS = "quicknav_home_address"
        const val KEY_WORK_ADDRESS = "quicknav_work_address"

        const val KEY_APP_THEME = "app_theme"
        const val KEY_PETROL_PRICE = "petrol_price_per_liter"
        const val KEY_LAST_BIKE_MAC = "last_connected_bike_mac"

        const val THEME_WINE_RED = "wine_red"
        const val THEME_AMOLED = "amoled"
        const val THEME_STEALTH = "stealth"
        const val THEME_AMBER = "amber"
        const val THEME_MINT = "mint"
        const val THEME_BLUE = "blue"

        @Volatile
        private var instance: AppSettings? = null

        fun getInstance(context: Context): AppSettings {
            return instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _autoParkFlow = MutableStateFlow(prefs.getBoolean(KEY_AUTO_PARK, true))
    val autoParkFlow: StateFlow<Boolean> = _autoParkFlow.asStateFlow()

    private val _rideTrackingFlow = MutableStateFlow(prefs.getBoolean(KEY_RIDE_TRACKING, true))
    val rideTrackingFlow: StateFlow<Boolean> = _rideTrackingFlow.asStateFlow()

    private val _mediaControlFlow = MutableStateFlow(prefs.getBoolean(KEY_MEDIA_CONTROL, true))
    val mediaControlFlow: StateFlow<Boolean> = _mediaControlFlow.asStateFlow()

    private val _whatsappAlertsFlow = MutableStateFlow(prefs.getBoolean(KEY_WHATSAPP_ALERTS, true))
    val whatsappAlertsFlow: StateFlow<Boolean> = _whatsappAlertsFlow.asStateFlow()

    private val _maintenanceAlertsFlow = MutableStateFlow(prefs.getBoolean(KEY_MAINTENANCE_ALERTS, true))
    val maintenanceAlertsFlow: StateFlow<Boolean> = _maintenanceAlertsFlow.asStateFlow()

    private val _telemetrySyncFlow = MutableStateFlow(prefs.getBoolean(KEY_TELEMETRY_SYNC, true))
    val telemetrySyncFlow: StateFlow<Boolean> = _telemetrySyncFlow.asStateFlow()

    private val _twoWheelerModeFlow = MutableStateFlow(prefs.getBoolean(KEY_TWO_WHEELER_MODE, true))
    val twoWheelerModeFlow: StateFlow<Boolean> = _twoWheelerModeFlow.asStateFlow()

    var isAutoParkEnabled: Boolean
        get() = _autoParkFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_PARK, value).apply()
            _autoParkFlow.value = value
        }

    var isRideTrackingEnabled: Boolean
        get() = _rideTrackingFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_RIDE_TRACKING, value).apply()
            _rideTrackingFlow.value = value
        }

    var isMediaControlEnabled: Boolean
        get() = _mediaControlFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_MEDIA_CONTROL, value).apply()
            _mediaControlFlow.value = value
        }

    var isWhatsAppAlertsEnabled: Boolean
        get() = _whatsappAlertsFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_WHATSAPP_ALERTS, value).apply()
            _whatsappAlertsFlow.value = value
        }

    var isMaintenanceAlertsEnabled: Boolean
        get() = _maintenanceAlertsFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_MAINTENANCE_ALERTS, value).apply()
            _maintenanceAlertsFlow.value = value
        }

    var isTelemetrySyncEnabled: Boolean
        get() = _telemetrySyncFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_TELEMETRY_SYNC, value).apply()
            _telemetrySyncFlow.value = value
        }

    var isTwoWheelerMode: Boolean
        get() = _twoWheelerModeFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_TWO_WHEELER_MODE, value).apply()
            _twoWheelerModeFlow.value = value
        }

    var homeAddress: String
        get() = prefs.getString(KEY_HOME_ADDRESS, "Home") ?: "Home"
        set(value) {
            prefs.edit().putString(KEY_HOME_ADDRESS, value).apply()
        }

    var workAddress: String
        get() = prefs.getString(KEY_WORK_ADDRESS, "Work") ?: "Work"
        set(value) {
            prefs.edit().putString(KEY_WORK_ADDRESS, value).apply()
        }

    var appTheme: String
        get() = prefs.getString(KEY_APP_THEME, THEME_WINE_RED) ?: THEME_WINE_RED
        set(value) {
            prefs.edit().putString(KEY_APP_THEME, value).apply()
        }

    var petrolPricePerLiter: Double
        get() = prefs.getFloat(KEY_PETROL_PRICE, 104.0f).toDouble()
        set(value) {
            prefs.edit().putFloat(KEY_PETROL_PRICE, value.toFloat()).apply()
        }

    var lastConnectedBikeMac: String
        get() = prefs.getString(KEY_LAST_BIKE_MAC, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LAST_BIKE_MAC, value).apply()
        }
}
