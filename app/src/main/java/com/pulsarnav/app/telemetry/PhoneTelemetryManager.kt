package com.pulsarnav.app.telemetry

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.pulsarnav.app.ble.BajajBleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class TelemetryState(
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val signalBars: Int = 4
)

@SuppressLint("MissingPermission")
class PhoneTelemetryManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PhoneTelemetry"
        private const val HEARTBEAT_INTERVAL_MS = 20_000L

        @Volatile
        private var INSTANCE: PhoneTelemetryManager? = null

        fun getInstance(context: Context): PhoneTelemetryManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PhoneTelemetryManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bleManager get() = BajajBleManager.getInstance(context)

    private val _telemetryState = MutableStateFlow(TelemetryState())
    val telemetryState: StateFlow<TelemetryState> = _telemetryState

    private var isInitialized = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                val updated = _telemetryState.value.copy(
                    batteryPercent = pct,
                    isCharging = charging
                )
                _telemetryState.value = updated
                pushTelemetryToCluster()
            }
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            pushTelemetryToCluster()
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    fun initialize() {
        if (isInitialized) return
        isInitialized = true

        // Register Battery Receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)

        // Register Cellular Signal Listener
        setupSignalListener()

        // Start periodic sync heartbeat
        mainHandler.post(heartbeatRunnable)
    }

    private fun setupSignalListener() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "READ_PHONE_STATE not granted; using default cellular signal bars")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager?.registerTelephonyCallback(
                    context.mainExecutor,
                    object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                            val bars = (signalStrength.level + 1).coerceIn(0, 5)
                            _telemetryState.value = _telemetryState.value.copy(signalBars = bars)
                            pushTelemetryToCluster()
                        }
                    }
                )
            } else {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(
                    object : PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                            val level = signalStrength?.level ?: 3
                            val bars = (level + 1).coerceIn(0, 5)
                            _telemetryState.value = _telemetryState.value.copy(signalBars = bars)
                            pushTelemetryToCluster()
                        }
                    },
                    PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Signal strength listener registration failed: ${e.message}")
        }
    }

    private fun pushTelemetryToCluster() {
        if (!com.pulsarnav.app.data.AppSettings.getInstance(context).isTelemetrySyncEnabled) return
        val state = _telemetryState.value
        bleManager.updateTelemetry(
            batteryPercent = state.batteryPercent,
            isCharging = state.isCharging,
            signalBars = state.signalBars
        )
    }
}
