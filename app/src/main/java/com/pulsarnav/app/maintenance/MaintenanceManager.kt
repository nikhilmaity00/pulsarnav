package com.pulsarnav.app.maintenance

import android.content.Context
import android.content.SharedPreferences
import com.pulsarnav.app.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MaintenanceStatus(
    val totalOdometerKm: Double,
    val chainLubeRemainingKm: Double,
    val chainLubePercent: Int,
    val serviceRemainingKm: Double,
    val servicePercent: Int,
    val isChainLubeDue: Boolean,
    val isServiceDue: Boolean
)

class MaintenanceManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pulsarnav_maintenance", Context.MODE_PRIVATE)

    companion object {
        const val CHAIN_LUBE_INTERVAL_KM = 500.0
        const val SERVICE_INTERVAL_KM = 5000.0

        private const val KEY_ODO = "odo_total_km"
        private const val KEY_LAST_CHAIN_LUBE = "last_chain_lube_km"
        private const val KEY_LAST_SERVICE = "last_service_km"

        @Volatile
        private var instance: MaintenanceManager? = null

        fun getInstance(context: Context): MaintenanceManager {
            return instance ?: synchronized(this) {
                instance ?: MaintenanceManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _status = MutableStateFlow(computeStatus())
    val status: StateFlow<MaintenanceStatus> = _status.asStateFlow()

    private fun computeStatus(): MaintenanceStatus {
        val odo = prefs.getString(KEY_ODO, "120.0")?.toDoubleOrNull() ?: 120.0
        val lastLube = prefs.getString(KEY_LAST_CHAIN_LUBE, "0.0")?.toDoubleOrNull() ?: 0.0
        val lastService = prefs.getString(KEY_LAST_SERVICE, "0.0")?.toDoubleOrNull() ?: 0.0

        val lubeElapsed = (odo - lastLube).coerceAtLeast(0.0)
        val lubeRemaining = (CHAIN_LUBE_INTERVAL_KM - lubeElapsed).coerceAtLeast(0.0)
        val lubePercent = ((lubeRemaining / CHAIN_LUBE_INTERVAL_KM) * 100).toInt().coerceIn(0, 100)

        val serviceElapsed = (odo - lastService).coerceAtLeast(0.0)
        val serviceRemaining = (SERVICE_INTERVAL_KM - serviceElapsed).coerceAtLeast(0.0)
        val servicePercent = ((serviceRemaining / SERVICE_INTERVAL_KM) * 100).toInt().coerceIn(0, 100)

        return MaintenanceStatus(
            totalOdometerKm = odo,
            chainLubeRemainingKm = lubeRemaining,
            chainLubePercent = lubePercent,
            serviceRemainingKm = serviceRemaining,
            servicePercent = servicePercent,
            isChainLubeDue = lubeRemaining <= 50.0,
            isServiceDue = serviceRemaining <= 200.0
        )
    }

    fun addDistance(km: Double) {
        val currentOdo = prefs.getString(KEY_ODO, "120.0")?.toDoubleOrNull() ?: 120.0
        val newOdo = currentOdo + km
        prefs.edit().putString(KEY_ODO, "%.1f".format(newOdo)).apply()
        _status.value = computeStatus()
    }

    fun setOdometer(newOdo: Double) {
        prefs.edit().putString(KEY_ODO, "%.1f".format(newOdo)).apply()
        _status.value = computeStatus()
    }

    fun markChainLubed() {
        val currentOdo = prefs.getString(KEY_ODO, "120.0")?.toDoubleOrNull() ?: 120.0
        prefs.edit().putString(KEY_LAST_CHAIN_LUBE, currentOdo.toString()).apply()
        _status.value = computeStatus()
    }

    fun markServiceDone() {
        val currentOdo = prefs.getString(KEY_ODO, "120.0")?.toDoubleOrNull() ?: 120.0
        prefs.edit().putString(KEY_LAST_SERVICE, currentOdo.toString()).apply()
        _status.value = computeStatus()
    }
}
