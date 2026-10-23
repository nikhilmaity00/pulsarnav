package com.pulsarnav.app.maintenance

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class FuelEntry(
    val id: String,
    val timestampMs: Long,
    val odometerKm: Double,
    val liters: Double,
    val totalCostInr: Double,
    val calculatedKmpl: Double?
) {
    val formattedDate: String
        get() = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timestampMs))
}

data class FuelStats(
    val averageKmpl: Double,
    val totalSpentInr: Double,
    val costPerKm: Double
)

class FuelLogManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pulsarnav_fuel", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FUEL_LIST = "saved_fuel_entries"

        @Volatile
        private var instance: FuelLogManager? = null

        fun getInstance(context: Context): FuelLogManager {
            return instance ?: synchronized(this) {
                instance ?: FuelLogManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _entries = MutableStateFlow<List<FuelEntry>>(loadEntries())
    val entries: StateFlow<List<FuelEntry>> = _entries.asStateFlow()

    private val _stats = MutableStateFlow(computeStats(_entries.value))
    val stats: StateFlow<FuelStats> = _stats.asStateFlow()

    fun addFuelEntry(odometerKm: Double, liters: Double, totalCostInr: Double): FuelEntry {
        val current = _entries.value.toMutableList()
        var calculatedKmpl: Double? = null

        if (current.isNotEmpty()) {
            val lastEntry = current[0]
            val distanceSinceLast = (odometerKm - lastEntry.odometerKm).coerceAtLeast(0.0)
            if (liters > 0 && distanceSinceLast > 1.0) {
                calculatedKmpl = distanceSinceLast / liters
            }
        }

        val entry = FuelEntry(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            odometerKm = odometerKm,
            liters = liters,
            totalCostInr = totalCostInr,
            calculatedKmpl = calculatedKmpl
        )

        current.add(0, entry)
        _entries.value = current
        _stats.value = computeStats(current)
        saveEntries(current)

        MaintenanceManager.getInstance(context).setOdometer(odometerKm)
        return entry
    }

    private fun computeStats(list: List<FuelEntry>): FuelStats {
        if (list.isEmpty()) return FuelStats(38.5, 0.0, 2.7) // NS200 default average 38-40 km/l

        var totalSpent = 0.0
        var totalLiters = 0.0
        var validKmplCount = 0
        var kmplSum = 0.0

        for (e in list) {
            totalSpent += e.totalCostInr
            totalLiters += e.liters
            if (e.calculatedKmpl != null && e.calculatedKmpl > 5.0 && e.calculatedKmpl < 70.0) {
                kmplSum += e.calculatedKmpl
                validKmplCount++
            }
        }

        val avgKmpl = if (validKmplCount > 0) kmplSum / validKmplCount else 38.5
        val totalDistance = if (list.size > 1) (list.first().odometerKm - list.last().odometerKm).coerceAtLeast(1.0) else 50.0
        val costPerKm = if (totalDistance > 0) totalSpent / totalDistance else 2.7

        return FuelStats(avgKmpl, totalSpent, costPerKm)
    }

    private fun saveEntries(list: List<FuelEntry>) {
        val array = JSONArray()
        for (e in list) {
            val obj = JSONObject().apply {
                put("id", e.id)
                put("timestampMs", e.timestampMs)
                put("odometerKm", e.odometerKm)
                put("liters", e.liters)
                put("totalCostInr", e.totalCostInr)
                if (e.calculatedKmpl != null) put("calculatedKmpl", e.calculatedKmpl)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_FUEL_LIST, array.toString()).apply()
    }

    private fun loadEntries(): List<FuelEntry> {
        val raw = prefs.getString(KEY_FUEL_LIST, null) ?: return emptyList()
        val list = mutableListOf<FuelEntry>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    FuelEntry(
                        id = obj.getString("id"),
                        timestampMs = obj.getLong("timestampMs"),
                        odometerKm = obj.getDouble("odometerKm"),
                        liters = obj.getDouble("liters"),
                        totalCostInr = obj.getDouble("totalCostInr"),
                        calculatedKmpl = if (obj.has("calculatedKmpl")) obj.getDouble("calculatedKmpl") else null
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }
}
