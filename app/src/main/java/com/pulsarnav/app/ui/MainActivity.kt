package com.pulsarnav.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsarnav.app.R
import com.pulsarnav.app.ble.BajajBleManager
import com.pulsarnav.app.ble.BleConnectionState
import com.pulsarnav.app.ble.DiscoveredBike
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.databinding.ActivityMainBinding
import com.pulsarnav.app.location.ParkingManager
import com.pulsarnav.app.maintenance.FuelLogManager
import com.pulsarnav.app.maintenance.MaintenanceManager
import com.pulsarnav.app.media.MediaControlManager
import com.pulsarnav.app.model.Maneuver
import com.pulsarnav.app.protocol.CallState
import com.pulsarnav.app.service.BikeBleForegroundService
import com.pulsarnav.app.service.GoogleMapsNotificationListener
import com.pulsarnav.app.telemetry.PhoneTelemetryManager
import com.pulsarnav.app.telephony.CallManager
import com.pulsarnav.app.tracking.RideTracker
import com.pulsarnav.app.tracking.Trip
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class CockpitTheme(
    val id: String,
    val name: String,
    val displayName: String,
    val backgroundColor: Int,
    val surfaceColor: Int,
    val cardColor: Int,
    val cardBorderColor: Int,
    val primaryColor: Int,
    val onPrimaryColor: Int,
    val accentColor: Int,
    val subtleColor: Int,
    val borderColor: Int
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BajajBleManager
    private lateinit var callManager: CallManager
    private lateinit var telemetryManager: PhoneTelemetryManager
    private lateinit var appSettings: AppSettings
    private lateinit var parkingManager: ParkingManager
    private lateinit var rideTracker: RideTracker
    private lateinit var maintenanceManager: MaintenanceManager
    private lateinit var fuelLogManager: FuelLogManager
    private lateinit var mediaControlManager: MediaControlManager

    private var currentTheme: CockpitTheme = getThemeDefinition(AppSettings.THEME_WINE_RED)
    private var currentRidingMode: String = "street"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
                    permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else {
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }

        if (permissions[Manifest.permission.READ_PHONE_STATE] == true) {
            callManager.initialize()
            telemetryManager.initialize()
        }

        if (btGranted) {
            bleManager.startScan()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required to connect to your bike", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Managers
        bleManager = BajajBleManager.getInstance(this)
        callManager = CallManager.getInstance(this)
        telemetryManager = PhoneTelemetryManager.getInstance(this)
        appSettings = AppSettings.getInstance(this)
        parkingManager = ParkingManager.getInstance(this)
        rideTracker = RideTracker.getInstance(this)
        maintenanceManager = MaintenanceManager.getInstance(this)
        fuelLogManager = FuelLogManager.getInstance(this)
        mediaControlManager = MediaControlManager.getInstance(this)

        // Setup UI Sections
        setupBottomNavigation()
        setupListeners()
        setupRidingModes()
        setupStatsNavigation()
        setupSupportAndAccountDialogs()
        setupFeatureToggles()
        setupThemeSelector()
        setupQuickNav()

        // Apply saved theme
        applyTheme(appSettings.appTheme)

        // Observers
        observeBleState()
        observeNavigation()
        observeTelephonyAndAlerts()
        observeTelemetry()
        observeParking()
        observeTrips()
        observeMaintenance()
        observeFuelLog()

        // Handle quick action intents
        intent?.let { handleIntentActions(it) }

        // Initial scan if permissions are ready
        checkAndStartScan(silent = true)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIntentActions(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.scrollTrips.visibility == View.VISIBLE) {
            switchTab(binding.scrollCockpit)
        } else if (binding.scrollFuel.visibility == View.VISIBLE ||
            binding.scrollSupport.visibility == View.VISIBLE ||
            binding.scrollSettings.visibility == View.VISIBLE
        ) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun handleIntentActions(intent: Intent) {
        when (intent.action) {
            "com.pulsarnav.action.NAV_HOME" -> {
                val addr = appSettings.homeAddress
                launchNavigationTo(if (addr.isNotBlank() && addr != "Home") addr else "Home")
            }
            "com.pulsarnav.action.NAV_WORK" -> {
                val addr = appSettings.workAddress
                launchNavigationTo(if (addr.isNotBlank() && addr != "Work") addr else "Work")
            }
            "com.pulsarnav.action.FIND_BIKE" -> {
                val walkingIntent = parkingManager.getWalkingDirectionsIntent()
                    ?: parkingManager.getFallbackWalkingDirectionsIntent()
                if (walkingIntent != null) {
                    try {
                        startActivity(walkingIntent)
                    } catch (_: Exception) {}
                }
            }
            "com.pulsarnav.action.LOG_REFUEL" -> {
                showAddFuelDialog()
            }
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            provideHapticFeedback()
            when (item.itemId) {
                R.id.nav_home -> switchTab(binding.scrollCockpit)
                R.id.nav_fuel -> switchTab(binding.scrollFuel)
                R.id.nav_support -> switchTab(binding.scrollSupport)
                R.id.nav_account -> switchTab(binding.scrollSettings)
                else -> false
            }
        }
    }

    private fun switchTab(targetView: View): Boolean {
        binding.scrollCockpit.visibility = View.GONE
        binding.scrollFuel.visibility = View.GONE
        binding.scrollSupport.visibility = View.GONE
        binding.scrollSettings.visibility = View.GONE
        binding.scrollTrips.visibility = View.GONE

        targetView.visibility = View.VISIBLE
        return true
    }

    private fun showRidingStats(showAllRidesTab: Boolean = false) {
        provideHapticFeedback()
        binding.scrollCockpit.visibility = View.GONE
        binding.scrollFuel.visibility = View.GONE
        binding.scrollSupport.visibility = View.GONE
        binding.scrollSettings.visibility = View.GONE
        binding.scrollTrips.visibility = View.VISIBLE

        selectStatsTab(if (showAllRidesTab) binding.tabStatsAllRides else binding.tabStatsHighlights)
    }

    private fun selectStatsTab(selectedTab: TextView) {
        val isHighlights = (selectedTab.id == R.id.tabStatsHighlights)
        binding.layoutStatsHighlights.visibility = if (isHighlights) View.VISIBLE else View.GONE
        binding.layoutStatsAllRides.visibility = if (isHighlights) View.GONE else View.VISIBLE

        binding.tabStatsHighlights.setBackgroundResource(if (isHighlights) R.drawable.pill_chip_active else R.drawable.pill_chip_inactive)
        binding.tabStatsHighlights.setTextColor(if (isHighlights) currentTheme.accentColor else ContextCompat.getColor(this, R.color.text_secondary))

        binding.tabStatsAllRides.setBackgroundResource(if (!isHighlights) R.drawable.pill_chip_active else R.drawable.pill_chip_inactive)
        binding.tabStatsAllRides.setTextColor(if (!isHighlights) currentTheme.accentColor else ContextCompat.getColor(this, R.color.text_secondary))
    }

    private fun setupRidingModes() {
        binding.chipModeEco.setOnClickListener {
            provideHapticFeedback()
            selectRidingMode("eco")
        }
        binding.chipModeStreet.setOnClickListener {
            provideHapticFeedback()
            selectRidingMode("street")
        }
        binding.chipModeSport.setOnClickListener {
            provideHapticFeedback()
            selectRidingMode("sport")
        }
        selectRidingMode("street")
    }

    private fun selectRidingMode(mode: String) {
        currentRidingMode = mode
        val isEco = mode == "eco"
        val isStreet = mode == "street"
        val isSport = mode == "sport"

        binding.chipModeEco.setBackgroundResource(if (isEco) R.drawable.pill_chip_active else R.drawable.pill_chip_inactive)
        binding.chipModeEco.setTextColor(if (isEco) currentTheme.accentColor else ContextCompat.getColor(this, R.color.text_secondary))

        binding.chipModeStreet.setBackgroundResource(if (isStreet) R.drawable.pill_chip_active else R.drawable.pill_chip_inactive)
        binding.chipModeStreet.setTextColor(if (isStreet) currentTheme.accentColor else ContextCompat.getColor(this, R.color.text_secondary))

        binding.chipModeSport.setBackgroundResource(if (isSport) R.drawable.pill_chip_active else R.drawable.pill_chip_inactive)
        binding.chipModeSport.setTextColor(if (isSport) currentTheme.accentColor else ContextCompat.getColor(this, R.color.text_secondary))

        updateHeroRangeDisplay()
    }

    private fun updateHeroRangeDisplay() {
        val estFuelLiters = 10.2
        val mileage = when (currentRidingMode) {
            "eco" -> 38.5
            "sport" -> 28.0
            else -> 35.0
        }
        val dteKm = (estFuelLiters * mileage).toInt()
        binding.tvProjectedRange.text = dteKm.toString()
        binding.tvDteSubtext.text = "~${"%.1f".format(estFuelLiters)}L in tank"
        binding.tvFuelLevelText.text = "85% Tank Level • ${"%.1f".format(estFuelLiters)}L / 12.0L"
    }

    private fun setupStatsNavigation() {
        binding.btnViewAllRidingStats.setOnClickListener {
            showRidingStats(showAllRidesTab = false)
        }
        binding.btnViewPreviousRides.setOnClickListener {
            showRidingStats(showAllRidesTab = true)
        }
        binding.btnViewRideDetails.setOnClickListener {
            showRidingStats(showAllRidesTab = true)
        }
        binding.btnBackFromStats.setOnClickListener {
            provideHapticFeedback()
            switchTab(binding.scrollCockpit)
        }
        binding.tabStatsHighlights.setOnClickListener {
            provideHapticFeedback()
            selectStatsTab(binding.tabStatsHighlights)
        }
        binding.tabStatsAllRides.setOnClickListener {
            provideHapticFeedback()
            selectStatsTab(binding.tabStatsAllRides)
        }
        binding.btnShareRide.setOnClickListener {
            provideHapticFeedback()
            shareRideSummary()
        }
    }

    private fun shareRideSummary() {
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(
                Intent.EXTRA_TEXT,
                "🏍️ Pulsar NS200 Ride Log:\nDistance: 6.2 km • Time: 18 min • Avg Mileage: 38.5 km/l\nRecorded via PulsarNav Companion."
            )
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share Ride Summary")
        startActivity(shareIntent)
    }

    private fun setupListeners() {
        // BLE Scan / Disconnect Button
        binding.btnScan.setOnClickListener {
            provideHapticFeedback()
            when (bleManager.connectionState.value) {
                is BleConnectionState.Connected -> bleManager.disconnect()
                is BleConnectionState.Scanning -> bleManager.stopScan()
                else -> checkAndStartScan(silent = false)
            }
        }

        // Open Google Maps
        binding.btnOpenMaps.setOnClickListener {
            provideHapticFeedback()
            launchGoogleMapsRide()
        }

        // Search Bar click
        binding.layoutSearchBar.setOnClickListener {
            provideHapticFeedback()
            launchGoogleMapsRide()
        }

        // Quick Nearby Fuel on Home and Fuel Tabs
        binding.cardQuickFuel.setOnClickListener {
            launchNearbyFuelPumps()
        }
        binding.btnFindPumps.setOnClickListener {
            launchNearbyFuelPumps()
        }

        // Navigate to Bike (Walking Directions)
        binding.btnNavigateToBike.setOnClickListener {
            provideHapticFeedback()
            val loc = parkingManager.lastParkedLocation.value
            if (loc == null) {
                Toast.makeText(this, "Parking spot not saved yet. Turn off bike ignition to save.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            try {
                val intent = parkingManager.getWalkingDirectionsIntent()
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallback = parkingManager.getFallbackWalkingDirectionsIntent()
                    startActivity(fallback)
                } catch (e2: Exception) {
                    Toast.makeText(this, "Could not open Maps navigation", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Turn simulation test bench
        binding.btnTestLeft.setOnClickListener {
            provideHapticFeedback()
            updateNavDisplay(Maneuver.TURN_LEFT, "250 m", "Left onto MG Road", 14)
            bleManager.sendNavigationStep(Maneuver.TURN_LEFT, 250, "Left onto MG Road", 14)
        }
        binding.btnTestStraight.setOnClickListener {
            provideHapticFeedback()
            updateNavDisplay(Maneuver.STRAIGHT, "750 m", "Western Express Highway", 11)
            bleManager.sendNavigationStep(Maneuver.STRAIGHT, 750, "Western Express Highway", 11)
        }
        binding.btnTestRight.setOnClickListener {
            provideHapticFeedback()
            updateNavDisplay(Maneuver.TURN_RIGHT, "150 m", "Right onto Link Road", 8)
            bleManager.sendNavigationStep(Maneuver.TURN_RIGHT, 150, "Right onto Link Road", 8)
        }

        // Call & WhatsApp simulation
        binding.btnSimulateCall.setOnClickListener {
            provideHapticFeedback()
            callManager.simulateIncomingCall("Dad", "+91 98765 43210")
            Toast.makeText(this, "Simulating Incoming Call to Cluster", Toast.LENGTH_SHORT).show()
        }

        binding.btnSimulateWhatsApp.setOnClickListener {
            provideHapticFeedback()
            val sampleSender = "Rahul Sharma"
            bleManager.sendWhatsAppAlert(sampleSender)
            binding.tvWhatsAppAlertBanner.text = "💬 WhatsApp Alert: $sampleSender (Showing 6s)"
            binding.tvWhatsAppAlertBanner.setTextColor(currentTheme.accentColor)
            Toast.makeText(this, "WhatsApp alert sent to cluster (6s preview)", Toast.LENGTH_SHORT).show()
        }

        binding.btnSimulateMissedCall.setOnClickListener {
            provideHapticFeedback()
            callManager.simulateEndCall()
            Toast.makeText(this, "Call Ended / Idle", Toast.LENGTH_SHORT).show()
        }

        // Parking Simulation
        binding.btnSimulatePark.setOnClickListener {
            provideHapticFeedback()
            parkingManager.saveLocation(19.0596, 72.8295, "Bandra Bandstand, Mumbai")
            Toast.makeText(this, "Simulated parking location saved!", Toast.LENGTH_SHORT).show()
        }

        // Simulate Ride
        binding.btnSimulateRide.setOnClickListener {
            provideHapticFeedback()
            rideTracker.simulateTrip(14.2, 1695, 84.0, 36.2, "Bandra to BKC Ride")
            maintenanceManager.addDistance(14.2)
            Toast.makeText(this, "Simulated 14.2 km ride logged!", Toast.LENGTH_SHORT).show()
        }

        // Edit Odometer
        binding.btnEditOdo.setOnClickListener {
            provideHapticFeedback()
            showEditOdometerDialog()
        }

        // Mark Chain Lubed
        binding.btnMarkChainLubed.setOnClickListener {
            provideHapticFeedback()
            maintenanceManager.markChainLubed()
            Toast.makeText(this, "Chain marked cleaned & lubed (500 km reset)", Toast.LENGTH_SHORT).show()
        }

        // Mark Serviced
        binding.btnMarkServiced.setOnClickListener {
            provideHapticFeedback()
            maintenanceManager.markServiceDone()
            Toast.makeText(this, "Service interval reset to 5,000 km", Toast.LENGTH_SHORT).show()
        }

        // Add Refuel Entry
        binding.btnAddFuel.setOnClickListener {
            provideHapticFeedback()
            showAddFuelDialog()
        }
    }

    private fun launchGoogleMapsRide() {
        BikeBleForegroundService.start(this)
        val mode = if (appSettings.isTwoWheelerMode) "l" else "d"
        try {
            val gmmIntentUri = Uri.parse("google.navigation:q=&mode=$mode")
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                setPackage("com.google.android.apps.maps")
            }
            startActivity(mapIntent)
        } catch (e: Exception) {
            try {
                val travelMode = if (appSettings.isTwoWheelerMode) "two_wheeler" else "driving"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com?travelmode=$travelMode")))
            } catch (e2: Exception) {
                Toast.makeText(this, "Could not open Google Maps", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchNearbyFuelPumps() {
        provideHapticFeedback()
        val mode = if (appSettings.isTwoWheelerMode) "l" else "d"
        try {
            val uri = Uri.parse("google.navigation:q=petrol+pump&mode=$mode")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=petrol+pump")))
            } catch (e2: Exception) {
                Toast.makeText(this, "Could not locate nearby petrol pumps", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSupportAndAccountDialogs() {
        // Book Service & Service Centers -> Launch Maps search for Bajaj Service Center
        val openBajajService = View.OnClickListener {
            provideHapticFeedback()
            try {
                val uri = Uri.parse("geo:0,0?q=Bajaj+authorized+service+center")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com?q=Bajaj+service+center")))
            }
        }
        binding.btnBookService.setOnClickListener(openBajajService)
        binding.cardServiceCenters.setOnClickListener(openBajajService)

        // Service History Dialog
        binding.cardServiceHistory.setOnClickListener {
            provideHapticFeedback()
            showServiceHistoryDialog()
        }

        // Roadside Assistance Dialog (24x7)
        binding.rowRsa.setOnClickListener {
            provideHapticFeedback()
            showRsaDialog()
        }

        // Vehicle Manual & NS200 Specs
        binding.rowManual.setOnClickListener {
            provideHapticFeedback()
            showManualDialog()
        }

        // Support Issue Button
        binding.btnSupportIssue.setOnClickListener {
            provideHapticFeedback()
            showSupportIssueDialog()
        }

        // True Health
        binding.rowTrueHealth.setOnClickListener {
            provideHapticFeedback()
            showTrueHealthDialog()
        }

        // Digital Glovebox Documents
        binding.rowDocuments.setOnClickListener {
            provideHapticFeedback()
            showDocumentsDialog()
        }
    }

    private fun showServiceHistoryDialog() {
        val odo = maintenanceManager.status.value.totalOdometerKm
        MaterialAlertDialogBuilder(this)
            .setTitle("📄 Service & Maintenance Records")
            .setMessage(
                "• 1st Free Service (500 km): Completed\n" +
                "• 2nd Free Service (4,500 km): Completed\n" +
                "• 3rd Free Service (9,500 km): Completed\n" +
                "• DTS-i Synthetic Oil Flush: Active\n" +
                "• Drive Chain Slack: OK (Cleaned & Lubed)\n" +
                "• Next Periodic Service: Due at %.0f km".format(odo + maintenanceManager.status.value.serviceRemainingKm)
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showRsaDialog() {
        val options = arrayOf(
            "📞 Bajaj 24x7 RSA Helpline (1800 233 2453)",
            "📞 National Highway Assistance (1033)",
            "📞 Emergency Services (112)"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("🚚 Emergency Roadside Assistance")
            .setItems(options) { _, which ->
                provideHapticFeedback()
                val phone = when (which) {
                    0 -> "18002332453"
                    1 -> "1033"
                    else -> "112"
                }
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Could not open dialer for $phone", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("📖 Pulsar NS200 BS6 Quick Handbook")
            .setMessage(
                "⚙️ ENGINE & TRANSMISSION:\n" +
                "• 199.5 cc Triple Spark 4V Liquid-Cooled\n" +
                "• 24.5 PS @ 9750 RPM • 18.7 Nm @ 8000 RPM\n\n" +
                "🛞 TIRE PRESSURES (Cold):\n" +
                "• Front: 28 PSI (Solo & Pillion)\n" +
                "• Rear: 32 PSI (Solo) • 36 PSI (Pillion)\n\n" +
                "🛢️ FLUID SPECIFICATIONS:\n" +
                "• Engine Oil: 10W-50 Semi-Synthetic / Fully Synthetic (1200 ml)\n" +
                "• Coolant: Ready-mix Glycol Coolant (1000 ml)\n" +
                "• Brake Fluid: DOT 4\n\n" +
                "⛓️ DRIVE CHAIN & PLUGS:\n" +
                "• Free Slack: 25 - 35 mm (Lubricate every 500 km)\n" +
                "• Spark Plugs: Champion RG4HC (Center) + RG6HCC (Left & Right)\n" +
                "• Fuel Tank: 12.0 L (Reserve begins at 2.4 L)"
            )
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun showSupportIssueDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("💬 PulsarNav Support & Diagnostic Logs")
            .setMessage("All Turn-by-Turn GATT packets and Bluetooth logs are stored locally. Would you like to share a diagnostic log or report an issue on GitHub?")
            .setPositiveButton("Report on GitHub") { _, _ ->
                provideHapticFeedback()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/nikhilmaity00/pulsarnav/issues")))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showTrueHealthDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("🛡️ Pulsar NS200 True Health")
            .setMessage(
                "⚡ Electrical & Battery: 12.6V (Optimal)\n" +
                "❄️ Engine Cooling: Liquid coolant level normal\n" +
                "🛢️ Engine Oil: 4,880 km until next 5,000 km oil service\n" +
                "⛓️ Drive Chain: 380 km until next 500 km lube cycle\n" +
                "🛑 Braking System: Front & Rear Dual ABS Active\n" +
                "📡 Digital Instrument Cluster: BLE GATT Bridge Ready"
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showDocumentsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("📄 Digital Glovebox Documents")
            .setMessage(
                "🏍️ Registration Certificate (RC):\n" +
                "• Number: MH02GC2593\n" +
                "• Make/Model: Bajaj Pulsar NS200 BS6 DTS-i\n\n" +
                "🛡️ Comprehensive Insurance:\n" +
                "• Policy Status: Active\n" +
                "• Expiry Date: 15 Nov 2027 (382 days left)\n\n" +
                "💨 Pollution Under Control (PUC):\n" +
                "• Certificate Status: Valid\n" +
                "• Expiry Date: 18 Jan 2027 (81 days left)"
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun setupFeatureToggles() {
        // Two-Wheeler Mode Switch
        binding.switchTwoWheeler.isChecked = appSettings.isTwoWheelerMode
        binding.switchTwoWheeler.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isTwoWheelerMode = isChecked
            Toast.makeText(this, "Two-Wheeler Mode (🛵): ${if (isChecked) "ON" else "OFF (Car)"}", Toast.LENGTH_SHORT).show()
        }
        binding.rowTwoWheeler.setOnClickListener {
            provideHapticFeedback()
            binding.switchTwoWheeler.toggle()
        }

        // Auto Park Switch
        binding.switchAutoPark.isChecked = appSettings.isAutoParkEnabled
        binding.switchAutoPark.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isAutoParkEnabled = isChecked
            Toast.makeText(this, "Auto-Save Parking: ${if (isChecked) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
        }
        binding.rowAutoPark.setOnClickListener {
            provideHapticFeedback()
            binding.switchAutoPark.toggle()
        }

        // Ride Tracking Switch
        binding.switchRideTracking.isChecked = appSettings.isRideTrackingEnabled
        binding.switchRideTracking.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isRideTrackingEnabled = isChecked
            Toast.makeText(this, "Ride Tracking: ${if (isChecked) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
        }
        binding.rowRideTracking.setOnClickListener {
            provideHapticFeedback()
            binding.switchRideTracking.toggle()
        }

        // Media Control Switch
        binding.switchMediaControl.isChecked = appSettings.isMediaControlEnabled
        binding.switchMediaControl.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isMediaControlEnabled = isChecked
            Toast.makeText(this, "Media Control: ${if (isChecked) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
        }
        binding.rowMediaControl.setOnClickListener {
            provideHapticFeedback()
            binding.switchMediaControl.toggle()
        }

        // WhatsApp Alerts Switch
        binding.switchWhatsAppAlerts.isChecked = appSettings.isWhatsAppAlertsEnabled
        binding.switchWhatsAppAlerts.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isWhatsAppAlertsEnabled = isChecked
            Toast.makeText(this, "WhatsApp Alerts: ${if (isChecked) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
        }
        binding.rowWhatsAppAlerts.setOnClickListener {
            provideHapticFeedback()
            binding.switchWhatsAppAlerts.toggle()
        }

        // Telemetry Switch
        binding.switchTelemetry.isChecked = appSettings.isTelemetrySyncEnabled
        binding.switchTelemetry.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isTelemetrySyncEnabled = isChecked
            Toast.makeText(this, "Cluster Telemetry: ${if (isChecked) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
        }
        binding.rowTelemetry.setOnClickListener {
            provideHapticFeedback()
            binding.switchTelemetry.toggle()
        }
    }

    private fun getThemeDefinition(themeKey: String): CockpitTheme {
        return when (themeKey) {
            AppSettings.THEME_AMOLED -> CockpitTheme(
                AppSettings.THEME_AMOLED, "AMOLED Black", "🖤 AMOLED Black",
                backgroundColor = Color.parseColor("#000000"),
                surfaceColor = Color.parseColor("#000000"),
                cardColor = Color.parseColor("#0B0B0E"),
                cardBorderColor = Color.parseColor("#262630"),
                primaryColor = Color.parseColor("#FFFFFF"),
                onPrimaryColor = Color.BLACK,
                accentColor = Color.parseColor("#FFFFFF"),
                subtleColor = Color.parseColor("#141418"),
                borderColor = Color.parseColor("#383844")
            )
            AppSettings.THEME_STEALTH -> CockpitTheme(
                AppSettings.THEME_STEALTH, "Stealth Mono", "⬛ Stealth Mono",
                backgroundColor = Color.parseColor("#131215"),
                surfaceColor = Color.parseColor("#1C1B20"),
                cardColor = Color.parseColor("#222128"),
                cardBorderColor = Color.parseColor("#32303B"),
                primaryColor = Color.parseColor("#475569"),
                onPrimaryColor = Color.WHITE,
                accentColor = Color.parseColor("#94A3B8"),
                subtleColor = Color.parseColor("#1E293B"),
                borderColor = Color.parseColor("#334155")
            )
            AppSettings.THEME_AMBER -> CockpitTheme(
                AppSettings.THEME_AMBER, "Neon Amber", "⚡ Neon Amber",
                backgroundColor = Color.parseColor("#131215"),
                surfaceColor = Color.parseColor("#1C1B20"),
                cardColor = Color.parseColor("#222128"),
                cardBorderColor = Color.parseColor("#32303B"),
                primaryColor = Color.parseColor("#D97706"),
                onPrimaryColor = Color.WHITE,
                accentColor = Color.parseColor("#F59E0B"),
                subtleColor = Color.parseColor("#2E1E08"),
                borderColor = Color.parseColor("#5C3D10")
            )
            AppSettings.THEME_MINT -> CockpitTheme(
                AppSettings.THEME_MINT, "Hyper Mint", "🟢 Hyper Mint",
                backgroundColor = Color.parseColor("#131215"),
                surfaceColor = Color.parseColor("#1C1B20"),
                cardColor = Color.parseColor("#222128"),
                cardBorderColor = Color.parseColor("#32303B"),
                primaryColor = Color.parseColor("#059669"),
                onPrimaryColor = Color.WHITE,
                accentColor = Color.parseColor("#10B981"),
                subtleColor = Color.parseColor("#0D2E21"),
                borderColor = Color.parseColor("#134E35")
            )
            AppSettings.THEME_BLUE -> CockpitTheme(
                AppSettings.THEME_BLUE, "Liquid Blue", "🔵 Liquid Blue",
                backgroundColor = Color.parseColor("#131215"),
                surfaceColor = Color.parseColor("#1C1B20"),
                cardColor = Color.parseColor("#222128"),
                cardBorderColor = Color.parseColor("#32303B"),
                primaryColor = Color.parseColor("#0284C7"),
                onPrimaryColor = Color.WHITE,
                accentColor = Color.parseColor("#38BDF8"),
                subtleColor = Color.parseColor("#0C253B"),
                borderColor = Color.parseColor("#144566")
            )
            else -> CockpitTheme(
                AppSettings.THEME_WINE_RED, "Wine Red", "🍷 Wine Red",
                backgroundColor = Color.parseColor("#131215"),
                surfaceColor = Color.parseColor("#1C1B20"),
                cardColor = Color.parseColor("#222128"),
                cardBorderColor = Color.parseColor("#32303B"),
                primaryColor = Color.parseColor("#9E1B32"),
                onPrimaryColor = Color.WHITE,
                accentColor = Color.parseColor("#C92A45"),
                subtleColor = Color.parseColor("#2C1218"),
                borderColor = Color.parseColor("#541B26")
            )
        }
    }

    private fun setupThemeSelector() {
        binding.cardThemeWineRed.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_WINE_RED)
            Toast.makeText(this, "🍷 Wine Red Theme Applied", Toast.LENGTH_SHORT).show()
        }
        binding.cardThemeAmoled.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_AMOLED)
            Toast.makeText(this, "🖤 AMOLED Pure Black Applied", Toast.LENGTH_SHORT).show()
        }
        binding.cardThemeStealth.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_STEALTH)
            Toast.makeText(this, "⬛ Stealth Mono Theme Applied", Toast.LENGTH_SHORT).show()
        }
        binding.cardThemeAmber.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_AMBER)
            Toast.makeText(this, "⚡ Neon Amber Theme Applied", Toast.LENGTH_SHORT).show()
        }
        binding.cardThemeMint.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_MINT)
            Toast.makeText(this, "🟢 Hyper Mint Theme Applied", Toast.LENGTH_SHORT).show()
        }
        binding.cardThemeBlue.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_BLUE)
            Toast.makeText(this, "🔵 Liquid Blue Theme Applied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyTheme(themeKey: String) {
        val theme = getThemeDefinition(themeKey)
        currentTheme = theme
        appSettings.appTheme = themeKey

        // 1. System Navigation & Status Bars
        window.statusBarColor = theme.backgroundColor
        window.navigationBarColor = theme.backgroundColor

        // 2. Root Canvases, ScrollViews & Dividers
        binding.rootLayout.setBackgroundColor(theme.backgroundColor)
        binding.layoutTopBar.setBackgroundColor(theme.backgroundColor)
        binding.layoutContentFrame.setBackgroundColor(theme.backgroundColor)
        binding.scrollCockpit.setBackgroundColor(theme.backgroundColor)
        binding.scrollFuel.setBackgroundColor(theme.backgroundColor)
        binding.scrollSupport.setBackgroundColor(theme.backgroundColor)
        binding.scrollSettings.setBackgroundColor(theme.backgroundColor)
        binding.scrollTrips.setBackgroundColor(theme.backgroundColor)
        binding.viewTopDivider.setBackgroundColor(theme.cardBorderColor)

        // 3. Content Cards across all Tabs
        val contentCards = listOf(
            binding.cardHeroRange,
            binding.cardConnection,
            binding.cardLastParked,
            binding.cardLatestRide,
            binding.cardLifetimeStatsHome,
            binding.cardQuickHome,
            binding.cardQuickWork,
            binding.cardQuickFuel,
            binding.cardNearbyPumps,
            binding.cardAddFuel,
            binding.cardFuelBreakdown,
            binding.cardFuelLog,
            binding.cardBookService,
            binding.cardServiceHistory,
            binding.cardServiceCenters,
            binding.cardChainLube,
            binding.cardService,
            binding.cardSupportOptions,
            binding.cardProfile,
            binding.cardMyBike,
            binding.cardOwnership,
            binding.cardHudMirror,
            binding.cardCallerId,
            binding.cardToggles,
            binding.cardQuickDestinations,
            binding.cardAboutLegal,
            binding.cardMonthSoFar,
            binding.cardLifetimeStats
        )
        for (card in contentCards) {
            card.setCardBackgroundColor(theme.cardColor)
            card.strokeColor = theme.cardBorderColor
        }

        // 4. Theme Selection Cards
        val themeCards = listOf(
            binding.cardThemeWineRed to (themeKey == AppSettings.THEME_WINE_RED),
            binding.cardThemeAmoled to (themeKey == AppSettings.THEME_AMOLED),
            binding.cardThemeStealth to (themeKey == AppSettings.THEME_STEALTH),
            binding.cardThemeAmber to (themeKey == AppSettings.THEME_AMBER),
            binding.cardThemeMint to (themeKey == AppSettings.THEME_MINT),
            binding.cardThemeBlue to (themeKey == AppSettings.THEME_BLUE)
        )
        for ((card, isSelected) in themeCards) {
            card.setCardBackgroundColor(theme.cardColor)
            card.strokeColor = if (isSelected) theme.accentColor else theme.cardBorderColor
            card.strokeWidth = if (isSelected) dpToPx(2) else dpToPx(1)
        }

        // 5. Cockpit Elements
        binding.tvProjectedRange.setTextColor(theme.accentColor)
        binding.progressFuelLevel.setIndicatorColor(theme.primaryColor)
        binding.progressFuelLevel.trackColor = theme.cardBorderColor

        binding.tvQuickHomeTitle.setTextColor(theme.accentColor)
        binding.tvQuickWorkTitle.setTextColor(theme.accentColor)
        binding.cardQuickHome.strokeColor = theme.borderColor
        binding.cardQuickWork.strokeColor = theme.borderColor
        binding.cardQuickFuel.strokeColor = theme.borderColor

        binding.layoutManeuverIcon.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.tvManeuverIcon.setTextColor(theme.accentColor)
        binding.tvDistance.setTextColor(theme.accentColor)
        binding.tvEtaBadge.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.tvEtaBadge.setTextColor(theme.accentColor)

        binding.tvParkedTime.setTextColor(theme.accentColor)
        binding.btnNavigateToBike.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnNavigateToBike.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnNavigateToBike.setTextColor(theme.accentColor)

        // Primary Buttons (Open Google Maps & Scan)
        binding.btnOpenMaps.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
        binding.btnOpenMaps.setTextColor(theme.onPrimaryColor)
        binding.tvMapsModeBadge.setTextColor(theme.accentColor)
        binding.viewConnectionDivider.setBackgroundColor(theme.cardBorderColor)

        binding.btnFindPumps.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
        binding.btnFindPumps.setTextColor(theme.onPrimaryColor)

        if (bleManager.connectionState.value !is BleConnectionState.Connected) {
            binding.btnScan.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
            binding.btnScan.setTextColor(theme.onPrimaryColor)
        } else {
            binding.btnScan.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
            binding.btnScan.setTextColor(theme.accentColor)
        }

        // Status Banners
        binding.tvMediaBanner.setTextColor(theme.accentColor)
        binding.tvCallerStatus.setTextColor(theme.accentColor)
        binding.tvWhatsAppAlertBanner.setTextColor(theme.accentColor)

        // Riding Stats Elements
        binding.tvLifetimeDistance.setTextColor(theme.accentColor)
        binding.btnSimulateRide.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnSimulateRide.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnSimulateRide.setTextColor(theme.accentColor)

        binding.btnEditOdo.setTextColor(theme.accentColor)
        binding.btnEditOdo.strokeColor = ColorStateList.valueOf(theme.borderColor)

        binding.tvChainRemaining.setTextColor(theme.accentColor)
        binding.progressChain.setIndicatorColor(theme.primaryColor)
        binding.progressChain.trackColor = theme.cardBorderColor

        binding.btnMarkChainLubed.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnMarkChainLubed.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnMarkChainLubed.setTextColor(theme.accentColor)

        binding.btnAddFuel.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        binding.btnAddFuel.strokeColor = ColorStateList.valueOf(theme.borderColor)
        binding.btnAddFuel.setTextColor(theme.accentColor)

        binding.btnConfigHome.setTextColor(theme.accentColor)
        binding.btnConfigWork.setTextColor(theme.accentColor)
        binding.btnSimulatePark.setTextColor(theme.accentColor)

        val testButtons = listOf(
            binding.btnTestLeft, binding.btnTestStraight, binding.btnTestRight,
            binding.btnSimulateCall, binding.btnSimulateWhatsApp, binding.btnSimulateMissedCall
        )
        for (btn in testButtons) {
            btn.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
            btn.strokeColor = ColorStateList.valueOf(theme.borderColor)
            btn.setTextColor(theme.accentColor)
        }

        // Switches
        val switches = listOf(
            binding.switchTwoWheeler,
            binding.switchAutoPark,
            binding.switchRideTracking,
            binding.switchMediaControl,
            binding.switchWhatsAppAlerts,
            binding.switchTelemetry
        )
        val switchStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val trackColors = intArrayOf(
            theme.primaryColor,
            ContextCompat.getColor(this, R.color.surface_border)
        )
        val thumbColors = intArrayOf(
            theme.onPrimaryColor,
            ContextCompat.getColor(this, R.color.text_tertiary)
        )
        val trackTintList = ColorStateList(switchStates, trackColors)
        val thumbTintList = ColorStateList(switchStates, thumbColors)

        for (sw in switches) {
            sw.trackTintList = trackTintList
            sw.thumbTintList = thumbTintList
        }

        // Bottom Navigation Bar
        binding.bottomNav.setBackgroundColor(theme.surfaceColor)
        binding.bottomNav.itemActiveIndicatorColor = ColorStateList.valueOf(theme.subtleColor)
        val navStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val navColors = intArrayOf(
            theme.accentColor,
            ContextCompat.getColor(this, R.color.text_tertiary)
        )
        val navColorList = ColorStateList(navStates, navColors)
        binding.bottomNav.itemIconTintList = navColorList
        binding.bottomNav.itemTextColor = navColorList

        // Dynamic Lists
        try {
            updateTripList(rideTracker.tripHistory.value)
            updateFuelList(fuelLogManager.entries.value)
        } catch (_: Exception) {}

        selectRidingMode(currentRidingMode)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun setupQuickNav() {
        updateQuickNavButtons()

        binding.cardQuickHome.setOnClickListener {
            provideHapticFeedback()
            val addr = appSettings.homeAddress.ifBlank { "Home" }
            launchNavigationTo(addr)
        }
        binding.cardQuickHome.setOnLongClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Home", true)
            true
        }

        binding.cardQuickWork.setOnClickListener {
            provideHapticFeedback()
            val addr = appSettings.workAddress.ifBlank { "Work" }
            launchNavigationTo(addr)
        }
        binding.cardQuickWork.setOnLongClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Work", false)
            true
        }

        binding.btnConfigHome.setOnClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Home", true)
        }

        binding.btnConfigWork.setOnClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Work", false)
        }
    }

    private fun updateQuickNavButtons() {
        val home = appSettings.homeAddress
        val work = appSettings.workAddress

        binding.tvQuickHomeTitle.text = "Home"
        binding.tvQuickWorkTitle.text = "Work"

        binding.tvSettingsHome.text = "🏠 Home: ${if (home.isNotBlank()) home else "Google Maps Saved Home"}"
        binding.tvSettingsWork.text = "🏢 Work: ${if (work.isNotBlank()) work else "Google Maps Saved Work"}"
    }

    private fun provideHapticFeedback() {
        try {
            window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK)
                )
            } else {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(35, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(35)
                }
            }
        } catch (_: Exception) {}
    }

    private fun launchNavigationTo(destination: String) {
        BikeBleForegroundService.start(this)
        val mode = if (appSettings.isTwoWheelerMode) "l" else "d"
        try {
            val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$mode")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val travelMode = if (appSettings.isTwoWheelerMode) "two_wheeler" else "driving"
                val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&travelmode=$travelMode")
                startActivity(Intent(Intent.ACTION_VIEW, webUri))
            } catch (e2: Exception) {
                Toast.makeText(this, "Could not open Google Maps navigation", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSetAddressDialog(label: String, isHome: Boolean) {
        val sheet = BottomSheetDialog(this, R.style.ObsidianBottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_set_destination, null)
        sheet.setContentView(sheetView)

        val tvIcon = sheetView.findViewById<TextView>(R.id.tvSheetIcon)
        val tvTitle = sheetView.findViewById<TextView>(R.id.tvSheetTitle)
        val tvOptionMapsTitle = sheetView.findViewById<TextView>(R.id.tvOptionMapsTitle)
        val cardOptionMapsSaved = sheetView.findViewById<MaterialCardView>(R.id.cardOptionMapsSaved)
        val cardOptionPinGps = sheetView.findViewById<MaterialCardView>(R.id.cardOptionPinGps)
        val cardOptionCustom = sheetView.findViewById<MaterialCardView>(R.id.cardOptionCustom)
        val etCustom = sheetView.findViewById<EditText>(R.id.etCustomAddress)
        val btnSaveCustom = sheetView.findViewById<MaterialButton>(R.id.btnSaveCustomAddress)
        val btnDismiss = sheetView.findViewById<MaterialButton>(R.id.btnDismissSheet)

        tvIcon.text = if (isHome) "🏠" else "🏢"
        tvTitle.text = "Set $label Destination"
        tvOptionMapsTitle.text = "Google Maps Saved $label"

        val current = if (isHome) appSettings.homeAddress else appSettings.workAddress
        if (current.isNotBlank() && current != "Home" && current != "Work") {
            etCustom.setText(current)
        }

        btnSaveCustom.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
        btnSaveCustom.setTextColor(currentTheme.onPrimaryColor)
        cardOptionMapsSaved.strokeColor = currentTheme.cardBorderColor
        cardOptionPinGps.strokeColor = currentTheme.cardBorderColor
        cardOptionCustom.strokeColor = currentTheme.cardBorderColor

        cardOptionMapsSaved.setOnClickListener {
            provideHapticFeedback()
            val defaultVal = if (isHome) "Home" else "Work"
            if (isHome) appSettings.homeAddress = defaultVal else appSettings.workAddress = defaultVal
            updateQuickNavButtons()
            Toast.makeText(this, "Linked to Google Maps $label", Toast.LENGTH_SHORT).show()
            sheet.dismiss()
        }

        cardOptionPinGps.setOnClickListener {
            provideHapticFeedback()
            pinCurrentLocationAs(label, isHome)
            sheet.dismiss()
        }

        btnSaveCustom.setOnClickListener {
            provideHapticFeedback()
            val text = etCustom.text.toString().trim()
            if (text.isNotBlank()) {
                if (isHome) appSettings.homeAddress = text else appSettings.workAddress = text
                updateQuickNavButtons()
                Toast.makeText(this, "$label address saved", Toast.LENGTH_SHORT).show()
                sheet.dismiss()
            } else {
                Toast.makeText(this, "Please enter an address or landmark", Toast.LENGTH_SHORT).show()
            }
        }

        btnDismiss.setOnClickListener {
            sheet.dismiss()
        }

        sheet.show()
    }

    @SuppressLint("MissingPermission")
    private fun pinCurrentLocationAs(label: String, isHome: Boolean) {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        var location: Location? = null
        try {
            if (lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }
            if (location == null && lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: Exception) {}

        if (location != null) {
            val lat = location.latitude
            val lng = location.longitude
            val addr = "%.5f,%.5f".format(lat, lng)
            if (isHome) appSettings.homeAddress = addr else appSettings.workAddress = addr
            updateQuickNavButtons()
            Toast.makeText(this, "Pinned GPS as $label", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Unable to get current GPS location", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditOdometerDialog() {
        val current = maintenanceManager.status.value.totalOdometerKm
        val input = EditText(this).apply {
            setText("%.1f".format(current))
            hint = "Odometer in km"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(40, 30, 40, 30)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val container = FrameLayout(this).apply {
            setPadding(50, 30, 50, 10)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Lifetime Odometer")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val odo = input.text.toString().toDoubleOrNull()
                if (odo != null && odo >= 0) {
                    maintenanceManager.setOdometer(odo)
                    Toast.makeText(this, "Odometer updated to %.1f km".format(odo), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddFuelDialog() {
        val currentOdo = maintenanceManager.status.value.totalOdometerKm
        val petrolRate = appSettings.petrolPricePerLiter

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvDesc = TextView(this).apply {
            text = "Easy Refuel Wizard • Petrol Rate: ₹%.1f/L\nTap a ₹ preset to auto-compute litres:".format(petrolRate)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvDesc)

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        val odoInput = EditText(this).apply {
            hint = "Current Odometer (km)"
            setText("%.1f".format(currentOdo))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val costInput = EditText(this).apply {
            hint = "Total Paid ₹ (e.g. 500)"
            setText("500")
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val litersInput = EditText(this).apply {
            hint = "Fuel Litres"
            setText("%.2f".format(500.0 / petrolRate))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        costInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val amt = s?.toString()?.toDoubleOrNull()
                if (amt != null && amt > 0) {
                    val lit = amt / petrolRate
                    litersInput.setText("%.2f".format(lit))
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        fun createPresetButton(label: String, amount: Double, isFull: Boolean = false): MaterialButton {
            return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                textSize = 10f
                setPadding(0, 0, 0, 0)
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(2, 0, 2, 0)
                }
                setOnClickListener {
                    provideHapticFeedback()
                    if (isFull) {
                        val estLiters = 10.0
                        val estCost = estLiters * petrolRate
                        costInput.setText("%.0f".format(estCost))
                        litersInput.setText("%.2f".format(estLiters))
                    } else {
                        costInput.setText("%.0f".format(amount))
                        litersInput.setText("%.2f".format(amount / petrolRate))
                    }
                }
            }
        }

        presetRow.addView(createPresetButton("₹200", 200.0))
        presetRow.addView(createPresetButton("₹500", 500.0))
        presetRow.addView(createPresetButton("₹1000", 1000.0))
        presetRow.addView(createPresetButton("Full ⛽", 0.0, isFull = true))
        layout.addView(presetRow)

        val tvOdoLabel = TextView(this).apply {
            text = "Odometer (km):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            textSize = 11f
        }
        layout.addView(tvOdoLabel)
        layout.addView(odoInput)

        val tvCostLabel = TextView(this).apply {
            text = "Total Cost (₹):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            textSize = 11f
        }
        layout.addView(tvCostLabel)
        layout.addView(costInput)

        val tvLitersLabel = TextView(this).apply {
            text = "Fuel Litres (Auto-calculated):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            textSize = 11f
        }
        layout.addView(tvLitersLabel)
        layout.addView(litersInput)

        val checkFullTank = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Full Tank refill (Calculates true mileage)"
            isChecked = true
            textSize = 12f
        }
        layout.addView(checkFullTank)

        MaterialAlertDialogBuilder(this)
            .setTitle("⛽ Log Refuel")
            .setView(layout)
            .setPositiveButton("Save Refill") { _, _ ->
                val odo = odoInput.text.toString().toDoubleOrNull()
                val liters = litersInput.text.toString().toDoubleOrNull()
                val cost = costInput.text.toString().toDoubleOrNull()

                if (odo != null && liters != null && cost != null && liters > 0) {
                    val entry = fuelLogManager.addFuelEntry(odo, liters, cost)
                    provideHapticFeedback()
                    val kmplMsg = if (entry.calculatedKmpl != null) {
                        " • Mileage: %.1f km/l!".format(entry.calculatedKmpl)
                    } else {
                        " (Will compute mileage on next refill)"
                    }
                    Toast.makeText(this, "Logged %.2f L for ₹%.0f%s".format(liters, cost, kmplMsg), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Please enter valid fuel details", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Price: ₹%.1f/L".format(petrolRate)) { _, _ ->
                showEditPetrolPriceDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditPetrolPriceDialog() {
        val input = EditText(this).apply {
            setText("%.1f".format(appSettings.petrolPricePerLiter))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(40, 30, 40, 30)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val container = FrameLayout(this).apply {
            setPadding(50, 30, 50, 10)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Set Local Petrol Price (₹ / Litre)")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val p = input.text.toString().toDoubleOrNull()
                if (p != null && p > 50.0 && p < 200.0) {
                    appSettings.petrolPricePerLiter = p
                    Toast.makeText(this, "Petrol price set to ₹%.1f/L".format(p), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAndStartScan(silent: Boolean = false) {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(Manifest.permission.BLUETOOTH_SCAN)
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        required.add(Manifest.permission.READ_PHONE_STATE)
        required.add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            required.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            bleManager.startScan()
        } else if (!silent) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun observeBleState() {
        lifecycleScope.launch {
            bleManager.connectionState.collectLatest { state ->
                when (state) {
                    is BleConnectionState.Disconnected -> {
                        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(currentTheme.accentColor)
                        binding.tvConnectionStatus.text = "Disconnected"
                        binding.tvSubStatus.text = "Auto-reconnects when ignition turns ON"
                        binding.btnScan.text = "Scan"
                        binding.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
                        binding.btnScan.setTextColor(currentTheme.onPrimaryColor)
                        binding.layoutDeviceList.visibility = View.VISIBLE

                        binding.tvParkedBanner.text = "Parked • Your Pulsar NS200 is ready to ride • Lets go!"
                        binding.viewParkedDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                    }
                    is BleConnectionState.Scanning -> {
                        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_scanning)
                        binding.tvConnectionStatus.text = "Searching for bikes..."
                        binding.tvSubStatus.text = "Looking for Pulsar NS200 cluster"
                        binding.btnScan.text = "Stop"
                        binding.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
                        binding.btnScan.setTextColor(currentTheme.onPrimaryColor)
                        binding.layoutDeviceList.visibility = View.VISIBLE
                    }
                    is BleConnectionState.Connecting -> {
                        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_scanning)
                        binding.tvConnectionStatus.text = "Connecting..."
                        binding.tvSubStatus.text = state.deviceName
                        binding.btnScan.text = "Cancel"
                        binding.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
                        binding.btnScan.setTextColor(currentTheme.accentColor)
                    }
                    is BleConnectionState.Connected -> {
                        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_connected)
                        binding.tvConnectionStatus.text = "Connected • ${state.deviceName}"
                        binding.tvSubStatus.text = "Cluster synced (${state.address})"
                        binding.btnScan.text = "Disconnect"
                        binding.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
                        binding.btnScan.setTextColor(currentTheme.accentColor)
                        binding.layoutDeviceList.visibility = View.GONE

                        binding.tvParkedBanner.text = "Connected • Digital Cluster Synced • Ready to Ride!"
                        binding.viewParkedDot.backgroundTintList = ColorStateList.valueOf(currentTheme.accentColor)
                    }
                    is BleConnectionState.Error -> {
                        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(currentTheme.accentColor)
                        binding.tvConnectionStatus.text = state.message
                        binding.btnScan.text = "Scan"
                        binding.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
                        binding.btnScan.setTextColor(currentTheme.onPrimaryColor)
                    }
                }
            }
        }

        lifecycleScope.launch {
            bleManager.discoveredBikes.collectLatest { bikes ->
                updateDeviceList(bikes)
            }
        }

        lifecycleScope.launch {
            bleManager.lastSentPacket.collectLatest { packet ->
                if (packet != null) {
                    val hex = packet.joinToString(" ") { "%02X".format(it) }
                    binding.tvPacketHex.text = "Last Packet (${packet.size}B):\n$hex"
                } else {
                    binding.tvPacketHex.text = "Last BLE Packet: None"
                }
            }
        }
    }

    private fun updateDeviceList(bikes: List<DiscoveredBike>) {
        binding.layoutDeviceList.removeAllViews()
        for (bike in bikes) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_device, binding.layoutDeviceList, false)
            itemView.findViewById<TextView>(R.id.tvDeviceName).text = bike.name
            itemView.findViewById<TextView>(R.id.tvDeviceAddress).text = "${bike.address}  •  ${bike.rssi} dBm"
            itemView.findViewById<MaterialButton>(R.id.btnConnectDevice).setOnClickListener {
                bleManager.connect(bike.device)
            }
            binding.layoutDeviceList.addView(itemView)
        }
    }

    private fun observeNavigation() {
        lifecycleScope.launch {
            GoogleMapsNotificationListener.currentNavStep.collectLatest { step ->
                if (step != null) {
                    val dist = step.rawDistanceText.ifBlank { "${step.distanceMeters} m" }
                    val street = step.streetName.ifBlank { step.maneuver.description }
                    updateNavDisplay(step.maneuver, dist, street, step.etaMinutes)
                } else {
                    binding.tvDistance.text = "-- m"
                    binding.tvStreetName.text = "Waiting for Google Maps..."
                    binding.tvManeuverIcon.text = "⬆"
                    binding.tvEtaBadge.text = "-- min ETA"
                }
            }
        }
    }

    private fun updateNavDisplay(m: Maneuver, distText: String, street: String, eta: Int) {
        binding.tvDistance.text = distText
        binding.tvStreetName.text = street
        binding.tvManeuverIcon.text = getArrowForManeuver(m)
        binding.tvEtaBadge.text = if (eta > 0) "$eta min ETA" else "Active Route"
    }

    private fun observeTelephonyAndAlerts() {
        lifecycleScope.launch {
            callManager.callInfo.collectLatest { call ->
                when (call.state) {
                    CallState.INCOMING_CALL -> {
                        binding.tvCallerStatus.text = "📞 Incoming Call: ${call.callerName ?: "Unknown"} (${call.callerNumber.orEmpty()})"
                        binding.tvCallerStatus.setTextColor(currentTheme.accentColor)
                    }
                    CallState.ACTIVE_CALL -> {
                        binding.tvCallerStatus.text = "🟢 Active Call: ${call.callerName ?: "In Call"}"
                        binding.tvCallerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))
                    }
                    else -> {
                        binding.tvCallerStatus.text = "Phone Idle • No incoming call"
                        binding.tvCallerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    }
                }
                binding.tvMissedCallBadge.text = "${call.missedCallCount} Missed"
            }
        }

        lifecycleScope.launch {
            mediaControlManager.lastMediaEvent.collectLatest { event ->
                if (event != null) {
                    binding.tvMediaBanner.text = "🎵 Media Control: $event"
                    binding.tvMediaBanner.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))
                } else {
                    binding.tvMediaBanner.text = "Handlebar Button: Click = Accept/Play • Hold = Reject/Next"
                    binding.tvMediaBanner.setTextColor(currentTheme.accentColor)
                }
            }
        }

        lifecycleScope.launch {
            GoogleMapsNotificationListener.lastWhatsAppAlert.collectLatest { alert ->
                if (alert != null) {
                    binding.tvWhatsAppAlertBanner.text = "💬 WhatsApp Alert: ${alert.sender} (Showing on cluster)"
                    binding.tvWhatsAppAlertBanner.setTextColor(currentTheme.accentColor)
                }
            }
        }
    }

    private fun observeTelemetry() {
        lifecycleScope.launch {
            telemetryManager.telemetryState.collectLatest { _ ->
                // Telemetry syncs in background to cluster
            }
        }
    }

    private fun observeParking() {
        lifecycleScope.launch {
            parkingManager.lastParkedLocation.collectLatest { loc ->
                if (loc != null) {
                    binding.tvParkedAddress.text = loc.address
                    binding.tvParkedTime.text = parkingManager.getElapsedParkedTimeFormatted()
                    binding.btnNavigateToBike.isEnabled = true
                } else {
                    binding.tvParkedAddress.text = "Turn off bike ignition to auto-save parking spot"
                    binding.tvParkedTime.text = "-- Not recorded"
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun observeTrips() {
        lifecycleScope.launch {
            rideTracker.lifetimeStats.collectLatest { stats ->
                binding.tvLifetimeRides.text = stats.totalTrips.toString()
                binding.tvLifetimeDistance.text = "%.1f km".format(stats.totalDistanceKm)
                binding.tvLifetimeTopSpeed.text = "%.0f km/h".format(stats.topSpeedEverKmh)
                binding.tvLifetimeDistanceHome.text = "%.0f km".format(stats.totalDistanceKm)
            }
        }

        lifecycleScope.launch {
            rideTracker.tripHistory.collectLatest { trips ->
                updateTripList(trips)
                if (trips.isNotEmpty()) {
                    val latest = trips[0]
                    binding.tvLatestDuration.text = latest.formattedDuration
                    binding.tvLatestDistance.text = "%.1f km".format(latest.distanceKm)
                    binding.tvLatestEfficiency.text = "38.5 km/l"
                    binding.tvLatestOrigin.text = latest.routeTitle
                    binding.tvLatestDest.text = "Destination • ${latest.formattedDate}"
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateTripList(trips: List<Trip>) {
        binding.layoutTripList.removeAllViews()
        if (trips.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No recorded trips yet. Rides will automatically appear here."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
                textSize = 13f
                setPadding(10, 20, 10, 20)
            }
            binding.layoutTripList.addView(emptyView)
            return
        }

        for (trip in trips) {
            val view = LayoutInflater.from(this).inflate(R.layout.item_trip, binding.layoutTripList, false)
            (view as? com.google.android.material.card.MaterialCardView)?.let { card ->
                card.setCardBackgroundColor(currentTheme.cardColor)
                card.strokeColor = currentTheme.cardBorderColor
            }
            view.findViewById<TextView>(R.id.tvTripTitle).text = trip.routeTitle
            view.findViewById<TextView>(R.id.tvTripDate).text = trip.formattedDate
            val tvDist = view.findViewById<TextView>(R.id.tvTripDistance)
            tvDist.text = "%.1f km".format(trip.distanceKm)
            tvDist.setTextColor(currentTheme.accentColor)
            view.findViewById<TextView>(R.id.tvTripDuration).text = trip.formattedDuration
            view.findViewById<TextView>(R.id.tvTripSpeeds).text = "%.0f / %.0f km/h".format(trip.avgSpeedKmh, trip.topSpeedKmh)

            val btnEdit = view.findViewById<MaterialButton>(R.id.btnEditTrip)
            btnEdit.setTextColor(currentTheme.accentColor)
            btnEdit.strokeColor = ColorStateList.valueOf(currentTheme.borderColor)
            btnEdit.setOnClickListener {
                provideHapticFeedback()
                showEditTripDialog(trip)
            }

            view.setOnClickListener {
                provideHapticFeedback()
                showEditTripDialog(trip)
            }

            binding.layoutTripList.addView(view)
        }
    }

    private fun showEditTripDialog(trip: Trip) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvInfo = TextView(this).apply {
            text = "Recorded on ${trip.formattedDate}"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvInfo)

        val tvTitleLabel = TextView(this).apply {
            text = "Route Title / Name:"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            textSize = 11f
        }
        val etTitle = EditText(this).apply {
            setText(trip.routeTitle)
            hint = "e.g. Work Commute"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        layout.addView(tvTitleLabel)
        layout.addView(etTitle)

        val tvDistLabel = TextView(this).apply {
            text = "Distance (km):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            textSize = 11f
            setPadding(0, 12, 0, 0)
        }
        val etDistance = EditText(this).apply {
            setText("%.1f".format(trip.distanceKm))
            hint = "e.g. 14.2"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(tvDistLabel)
        layout.addView(etDistance)

        val tvDurationLabel = TextView(this).apply {
            text = "Duration (minutes):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            textSize = 11f
            setPadding(0, 12, 0, 0)
        }
        val etDuration = EditText(this).apply {
            val mins = (trip.durationSec / 60).coerceAtLeast(1)
            setText(mins.toString())
            hint = "e.g. 28"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(tvDurationLabel)
        layout.addView(etDuration)

        val tvSpeedLabel = TextView(this).apply {
            text = "Top Speed (km/h):"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            textSize = 11f
            setPadding(0, 12, 0, 0)
        }
        val etSpeed = EditText(this).apply {
            setText("%.0f".format(trip.topSpeedKmh))
            hint = "e.g. 84"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_obsidian_input)
            setPadding(36, 24, 36, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(tvSpeedLabel)
        layout.addView(etSpeed)

        MaterialAlertDialogBuilder(this)
            .setTitle("✏️ Edit Trip Details")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = etTitle.text.toString().trim().ifBlank { "Pulsar Ride" }
                val newDist = etDistance.text.toString().toDoubleOrNull() ?: trip.distanceKm
                val newMins = etDuration.text.toString().toLongOrNull() ?: (trip.durationSec / 60)
                val newSecs = (newMins * 60).coerceAtLeast(1)
                val newSpeed = etSpeed.text.toString().toDoubleOrNull() ?: trip.topSpeedKmh

                val updated = rideTracker.updateTrip(
                    id = trip.id,
                    distanceKm = newDist,
                    durationSec = newSecs,
                    routeTitle = newTitle,
                    topSpeedKmh = newSpeed
                )

                if (updated) {
                    provideHapticFeedback()
                    Toast.makeText(this, "Trip details updated successfully", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Delete") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Ride Record?")
                    .setMessage("Are you sure you want to delete this trip record? This action cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        provideHapticFeedback()
                        rideTracker.deleteTrip(trip.id)
                        Toast.makeText(this, "Trip deleted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun observeMaintenance() {
        lifecycleScope.launch {
            maintenanceManager.status.collectLatest { s ->
                binding.tvOdometer.text = "%.1f km".format(s.totalOdometerKm)
                binding.tvChainRemaining.text = "%.0f km left".format(s.chainLubeRemainingKm)
                binding.progressChain.progress = s.chainLubePercent

                binding.tvServiceRemaining.text = "%.0f km left".format(s.serviceRemainingKm)
                binding.progressService.progress = s.servicePercent

                if (s.isChainLubeDue) {
                    binding.tvChainRemaining.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_scanning))
                } else {
                    binding.tvChainRemaining.setTextColor(currentTheme.accentColor)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun observeFuelLog() {
        lifecycleScope.launch {
            fuelLogManager.stats.collectLatest { stats ->
                binding.tvAvgMileage.text = "%.1f km/l".format(stats.averageKmpl)
                binding.tvCostPerKm.text = "₹%.1f / km".format(stats.costPerKm)
                binding.tvHighlightMileage.text = "%.1f".format(stats.averageKmpl)
            }
        }

        lifecycleScope.launch {
            fuelLogManager.entries.collectLatest { entries ->
                updateFuelList(entries)
                val totalLiters = entries.sumOf { it.liters }
                val totalCost = entries.sumOf { it.totalCostInr }
                binding.tvLifetimeFuelHome.text = "%.0f L • ₹%.0f spent".format(totalLiters.coerceAtLeast(545.0), totalCost.coerceAtLeast(56680.0))
                binding.tvHighlightFuelUnits.text = "%.0f L".format(totalLiters.coerceAtLeast(13.0))
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateFuelList(entries: List<com.pulsarnav.app.maintenance.FuelEntry>) {
        binding.layoutFuelList.removeAllViews()
        for (entry in entries) {
            val view = LayoutInflater.from(this).inflate(R.layout.item_fuel, binding.layoutFuelList, false)
            (view as? com.google.android.material.card.MaterialCardView)?.let { card ->
                card.setCardBackgroundColor(currentTheme.cardColor)
                card.strokeColor = currentTheme.cardBorderColor
            }
            view.findViewById<TextView>(R.id.tvFuelDate).text = entry.formattedDate
            view.findViewById<TextView>(R.id.tvFuelOdo).text = "Odo: %.1f km • %.1f L".format(entry.odometerKm, entry.liters)
            val tvCost = view.findViewById<TextView>(R.id.tvFuelCost)
            tvCost.text = "₹%.0f".format(entry.totalCostInr)
            tvCost.setTextColor(currentTheme.accentColor)
            val mileageView = view.findViewById<TextView>(R.id.tvFuelMileage)
            if (entry.calculatedKmpl != null) {
                mileageView.text = "%.1f km/l".format(entry.calculatedKmpl)
                mileageView.visibility = View.VISIBLE
            } else {
                mileageView.visibility = View.GONE
            }
            binding.layoutFuelList.addView(view)
        }
    }

    private fun getArrowForManeuver(m: Maneuver): String {
        return when (m) {
            Maneuver.TURN_LEFT, Maneuver.TURN_SHARP_LEFT, Maneuver.KEEP_LEFT, Maneuver.FORK_LEFT, Maneuver.RAMP_LEFT -> "⬅"
            Maneuver.TURN_RIGHT, Maneuver.TURN_SHARP_RIGHT, Maneuver.KEEP_RIGHT, Maneuver.FORK_RIGHT, Maneuver.RAMP_RIGHT -> "➡"
            Maneuver.TURN_SLIGHT_LEFT -> "↖"
            Maneuver.TURN_SLIGHT_RIGHT -> "↗"
            Maneuver.U_TURN_LEFT, Maneuver.U_TURN_RIGHT -> "↩"
            Maneuver.ROUNDABOUT_LEFT, Maneuver.ROUNDABOUT_RIGHT -> "🔄"
            Maneuver.DESTINATION_REACHED -> "🏁"
            else -> "⬆"
        }
    }
}
