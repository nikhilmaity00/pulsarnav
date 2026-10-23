package com.pulsarnav.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.pulsarnav.app.model.Maneuver
import com.pulsarnav.app.protocol.CallState
import com.pulsarnav.app.protocol.OtcPacketBuilder
import com.pulsarnav.app.telephony.CallManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    object Scanning : BleConnectionState()
    data class Connecting(val deviceName: String) : BleConnectionState()
    data class Connected(val deviceName: String, val address: String) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}

data class DiscoveredBike(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int
)

private data class QueuedWrite(
    val characteristicUuid: UUID,
    val data: ByteArray
)

@SuppressLint("MissingPermission")
class BajajBleManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BajajBleManager"
        private const val PREFS_NAME = "pulsarnav_prefs"
        private const val PREF_LAST_BIKE_ADDR = "last_connected_bike_address"
        private const val PREF_LAST_BIKE_NAME = "last_connected_bike_name"

        // Official OTC Engineering Bluetooth Service & Characteristic UUIDs
        val OTC_SERVICE_UUID: UUID = UUID.fromString("0010676e-6972-6565-6e69-676e4543544f")
        val TBT_NAV_CHAR_UUID: UUID = UUID.fromString("0110676e-6972-6565-6e69-676e4543544f")
        val GENERAL_CHAR_UUID: UUID = UUID.fromString("0210676e-6972-6565-6e69-676e4543544f")
        val MISSED_CALL_CHAR_UUID: UUID = UUID.fromString("0310676e-6972-6565-6e69-676e4543544f")
        val ALERTS_CHAR_UUID: UUID = UUID.fromString("0410676e-6972-6565-6e69-676e4543544f")
        val BUTTON_CONTROL_CHAR_UUID: UUID = UUID.fromString("0a10676e-6972-6565-6e69-676e4543544f")
        val CCCD_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val BIKE_NAME_PATTERNS = listOf("pulsar", "rouser", "dominar", "ns200", "ns160", "n250", "f250", "otc", "bajaj")

        @Volatile
        private var INSTANCE: BajajBleManager? = null

        fun getInstance(context: Context): BajajBleManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BajajBleManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bluetoothGatt: BluetoothGatt? = null
    private var isWriting = false
    private var userRequestedDisconnect = false

    private val writeTimeoutRunnable = Runnable {
        if (isWriting) {
            Log.w(TAG, "GATT write timeout watchdog triggered. Resetting write lock.")
            isWriting = false
            processNextWrite()
        }
    }

    // Characteristic references
    private var tbtCharacteristic: BluetoothGattCharacteristic? = null
    private var generalCharacteristic: BluetoothGattCharacteristic? = null
    private var missedCallCharacteristic: BluetoothGattCharacteristic? = null
    private var alertsCharacteristic: BluetoothGattCharacteristic? = null
    private var buttonCharacteristic: BluetoothGattCharacteristic? = null

    private val writeQueue = ConcurrentLinkedQueue<QueuedWrite>()

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState

    private val _discoveredBikes = MutableStateFlow<List<DiscoveredBike>>(emptyList())
    val discoveredBikes: StateFlow<List<DiscoveredBike>> = _discoveredBikes

    private val _lastSentPacket = MutableStateFlow<ByteArray?>(null)
    val lastSentPacket: StateFlow<ByteArray?> = _lastSentPacket

    // Telemetry & Call cache
    private var currentBattery = 100
    private var currentCharging = false
    private var currentSignal = 4
    private var currentCallState = CallState.NO_CALL
    private var currentCallerName: String? = null
    private var currentCallerNumber: String? = null
    private var currentMissedCount = 0

    private var isButtonCurrentlyPressed = false

    // BLE Scanner Callback
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { handleScanResult(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            _connectionState.value = BleConnectionState.Error("Scan failed: error code $errorCode")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val name = device.name ?: "Unknown Bike"
        val lowerName = name.lowercase()

        val isBajajBike = BIKE_NAME_PATTERNS.any { lowerName.contains(it) } ||
                result.scanRecord?.serviceUuids?.any { it.uuid == OTC_SERVICE_UUID } == true

        if (isBajajBike) {
            val bike = DiscoveredBike(device, name, device.address, result.rssi)
            val current = _discoveredBikes.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.address == device.address }
            if (existingIndex >= 0) {
                current[existingIndex] = bike
            } else {
                current.add(bike)
            }
            _discoveredBikes.value = current

            // Seamless Auto-Connect if matching last paired bike
            val savedAddress = prefs.getString(PREF_LAST_BIKE_ADDR, null)
            if (savedAddress != null && savedAddress.equals(device.address, ignoreCase = true) &&
                _connectionState.value is BleConnectionState.Scanning) {
                Log.i(TAG, "Recognized saved bike: $name ($savedAddress) -> Initiating Auto-Connect")
                connect(device)
            }
        }
    }

    fun startScan() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            _connectionState.value = BleConnectionState.Error("Bluetooth is turned off")
            return
        }

        _discoveredBikes.value = emptyList()
        _connectionState.value = BleConnectionState.Scanning

        val scanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(OTC_SERVICE_UUID)).build()
        )

        try {
            scanner.startScan(filters, settings, scanCallback)
            mainHandler.postDelayed({ stopScan() }, 15000)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting filtered scan: ${e.message}, falling back to open scan")
            try {
                scanner.startScan(scanCallback)
                mainHandler.postDelayed({ stopScan() }, 15000)
            } catch (ex: Exception) {
                _connectionState.value = BleConnectionState.Error("Failed to start scan")
            }
        }
    }

    fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            if (_connectionState.value is BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Disconnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}")
        }
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        userRequestedDisconnect = false
        val deviceName = device.name ?: "Bajaj Pulsar NS200"

        // Persist bike for automatic background reconnection
        prefs.edit()
            .putString(PREF_LAST_BIKE_ADDR, device.address)
            .putString(PREF_LAST_BIKE_NAME, deviceName)
            .apply()

        _connectionState.value = BleConnectionState.Connecting(deviceName)

        mainHandler.post {
            bluetoothGatt?.close()
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    fun disconnect() {
        userRequestedDisconnect = true
        mainHandler.removeCallbacks(writeTimeoutRunnable)
        writeQueue.clear()
        isWriting = false
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        clearCharacteristics()
        _connectionState.value = BleConnectionState.Disconnected
    }

    private fun clearCharacteristics() {
        tbtCharacteristic = null
        generalCharacteristic = null
        missedCallCharacteristic = null
        alertsCharacteristic = null
        buttonCharacteristic = null
    }

    /**
     * Sends Turn-by-Turn navigation data to characteristic 0110 (48 bytes).
     */
    fun sendNavigationStep(maneuver: Maneuver, distanceMeters: Int, streetName: String, etaMinutes: Int = 0) {
        val packet = OtcPacketBuilder.buildTbtPacket(maneuver, distanceMeters, streetName, etaMinutes)
        enqueueWrite(TBT_NAV_CHAR_UUID, packet)
    }

    /**
     * Updates phone battery %, charging status, and cellular signal bars.
     */
    fun updateTelemetry(batteryPercent: Int, isCharging: Boolean, signalBars: Int) {
        currentBattery = batteryPercent
        currentCharging = isCharging
        currentSignal = signalBars
        dispatchGeneralPacket()
    }

    /**
     * Updates call state (INCOMING_CALL, ACTIVE_CALL, NO_CALL) and caller ID.
     */
    fun updateCallState(
        callState: CallState,
        callerName: String?,
        callerNumber: String?,
        missedCallCount: Int = currentMissedCount
    ) {
        currentCallState = callState
        currentCallerName = callerName
        currentCallerNumber = callerNumber
        currentMissedCount = missedCallCount
        dispatchGeneralPacket()
    }

    /**
     * Acknowledges call accept or reject action from handlebar button.
     */
    fun acknowledgeCallAction(accept: Boolean = false, reject: Boolean = false) {
        dispatchGeneralPacket(acceptAck = accept, rejectAck = reject)
    }

    private fun dispatchGeneralPacket(acceptAck: Boolean = false, rejectAck: Boolean = false) {
        val packet = OtcPacketBuilder.buildGeneralPacketV2(
            batteryPercent = currentBattery,
            isCharging = currentCharging,
            signalBars = currentSignal,
            callState = currentCallState,
            callerName = currentCallerName,
            callerNumber = currentCallerNumber,
            callAcceptAck = acceptAck,
            callRejectAck = rejectAck,
            missedCallCount = currentMissedCount
        )
        enqueueWrite(GENERAL_CHAR_UUID, packet)
    }

    /**
     * Sends WhatsApp alert packet (44 bytes) to characteristic 0410.
     */
    fun sendWhatsAppAlert(senderName: String, count: Int = 1) {
        val packet = OtcPacketBuilder.buildWhatsAppAlertPacket(senderName, count)
        enqueueWrite(ALERTS_CHAR_UUID, packet)
    }

    /**
     * Sends Missed Call packet (63 bytes) to characteristic 0310.
     */
    fun sendMissedCall(callerName: String, callerNumber: String, count: Int = 1) {
        val packet = OtcPacketBuilder.buildMissedCallPacket(callerName, callerNumber, count)
        enqueueWrite(MISSED_CALL_CHAR_UUID, packet)
    }

    private fun enqueueWrite(charUuid: UUID, packet: ByteArray) {
        writeQueue.add(QueuedWrite(charUuid, packet))
        processNextWrite()
    }

    @Synchronized
    private fun processNextWrite() {
        if (isWriting) return
        val gatt = bluetoothGatt ?: return
        val item = writeQueue.poll() ?: return

        val targetChar = when (item.characteristicUuid) {
            TBT_NAV_CHAR_UUID -> tbtCharacteristic
            GENERAL_CHAR_UUID -> generalCharacteristic
            MISSED_CALL_CHAR_UUID -> missedCallCharacteristic
            ALERTS_CHAR_UUID -> alertsCharacteristic
            else -> null
        } ?: return

        isWriting = true
        targetChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        targetChar.value = item.data

        mainHandler.removeCallbacks(writeTimeoutRunnable)
        mainHandler.postDelayed(writeTimeoutRunnable, 2000L)

        val success = gatt.writeCharacteristic(targetChar)
        if (success) {
            _lastSentPacket.value = item.data
            Log.d(TAG, "Dispatched write to ${item.characteristicUuid} (${item.data.size} bytes)")
        } else {
            Log.w(TAG, "Write initiation failed for ${item.characteristicUuid}")
            mainHandler.removeCallbacks(writeTimeoutRunnable)
            isWriting = false
            processNextWrite()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val device = gatt.device
            val name = device.name ?: "Bajaj Pulsar NS200"

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Connected to $name, discovering services...")
                _connectionState.value = BleConnectionState.Connected(name, device.address)
                com.pulsarnav.app.data.AppSettings.getInstance(context).lastConnectedBikeMac = device.address
                mainHandler.postDelayed({ gatt.discoverServices() }, 500)

                // Start ride tracking session
                com.pulsarnav.app.tracking.RideTracker.getInstance(context).onBikeConnected()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from $name (status=$status)")
                clearCharacteristics()
                _connectionState.value = BleConnectionState.Disconnected

                // Finalize ride session & update odometer
                val trip = com.pulsarnav.app.tracking.RideTracker.getInstance(context).onBikeDisconnected()
                if (trip != null) {
                    com.pulsarnav.app.maintenance.MaintenanceManager.getInstance(context).addDistance(trip.distanceKm)
                }

                // Record automatic parking GPS location
                com.pulsarnav.app.location.ParkingManager.getInstance(context).onBikeDisconnected()

                // Automatic Background Reconnect (only if disconnect was not manually triggered by user)
                val savedAddress = prefs.getString(PREF_LAST_BIKE_ADDR, null)
                if (!userRequestedDisconnect && savedAddress != null) {
                    mainHandler.postDelayed({
                        try {
                            if (!userRequestedDisconnect && _connectionState.value is BleConnectionState.Disconnected) {
                                bluetoothAdapter?.getRemoteDevice(savedAddress)?.let { connect(it) }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Auto-reconnect attempt failed: ${e.message}")
                        }
                    }, 4000)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(OTC_SERVICE_UUID)
                if (service != null) {
                    tbtCharacteristic = service.getCharacteristic(TBT_NAV_CHAR_UUID)
                    generalCharacteristic = service.getCharacteristic(GENERAL_CHAR_UUID)
                    missedCallCharacteristic = service.getCharacteristic(MISSED_CALL_CHAR_UUID)
                    alertsCharacteristic = service.getCharacteristic(ALERTS_CHAR_UUID)
                    buttonCharacteristic = service.getCharacteristic(BUTTON_CONTROL_CHAR_UUID)

                    Log.i(TAG, "Discovered OTC Console Services & Characteristics successfully")

                    // Enable Handlebar Button Notifications (0a10)
                    buttonCharacteristic?.let { char ->
                        gatt.setCharacteristicNotification(char, true)
                        char.getDescriptor(CCCD_DESCRIPTOR_UUID)?.let { desc ->
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(desc)
                            Log.i(TAG, "Handlebar button notification CCCD registered")
                        }
                    }

                    // Push initial general telemetry (battery & signal)
                    dispatchGeneralPacket()
                } else {
                    Log.w(TAG, "OTC Service UUID not found on device!")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == BUTTON_CONTROL_CHAR_UUID) {
                val data = characteristic.value ?: return
                Log.d(TAG, "Handlebar button data received: ${data.joinToString(" ") { "%02X".format(it) }}")

                // Byte 4 = Accept, Byte 5 = Reject
                if (data.size > 5) {
                    val acceptByte = data[4].toInt() and 0xFF
                    val rejectByte = data[5].toInt() and 0xFF

                    if (acceptByte == 1) {
                        CallManager.getInstance(context).acceptIncomingCall()
                        return
                    } else if (rejectByte == 1) {
                        CallManager.getInstance(context).rejectIncomingCall()
                        return
                    }
                }

                // General Button Down / Up Timing detection for Single Click vs Long Press
                val isPressed = data.isNotEmpty() && (data[0].toInt() and 0x01 != 0)
                if (isPressed != isButtonCurrentlyPressed) {
                    isButtonCurrentlyPressed = isPressed
                    CallManager.getInstance(context).onHandlebarButtonStateChanged(isPressed)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            mainHandler.removeCallbacks(writeTimeoutRunnable)
            isWriting = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Characteristic write error status=$status on ${characteristic.uuid}")
            }
            processNextWrite()
        }
    }
}
