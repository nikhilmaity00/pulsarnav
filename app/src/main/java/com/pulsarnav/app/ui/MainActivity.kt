package com.pulsarnav.app.ui

import android.app.DatePickerDialog
import android.app.Dialog
import android.view.WindowManager
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.graphics.ColorUtils
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.pulsarnav.app.data.VehicleCatalog
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import androidx.core.widget.NestedScrollView
import com.google.android.material.checkbox.MaterialCheckBox
import kotlin.math.roundToInt
import com.google.android.material.snackbar.Snackbar
import com.pulsarnav.app.data.DataVaultManager
import com.pulsarnav.app.maintenance.GloveboxDocumentStatus
import com.pulsarnav.app.maintenance.GloveboxExpiryEvaluator
import com.pulsarnav.app.maintenance.MaintenanceMath
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsarnav.app.R
import com.pulsarnav.app.ble.BajajBleManager
import com.pulsarnav.app.ble.BleConnectionState
import com.pulsarnav.app.ble.DiscoveredBike
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.data.BikeIssue
import com.pulsarnav.app.databinding.ActivityMainBinding
import com.pulsarnav.app.debug.DebugLogRepository
import com.pulsarnav.app.location.ParkingManager
import com.pulsarnav.app.maintenance.FuelEconomyCalculator
import com.pulsarnav.app.maintenance.FuelLogManager
import com.pulsarnav.app.maintenance.FuelSortOrder
import com.pulsarnav.app.maintenance.MaintenanceManager
import com.pulsarnav.app.media.MediaControlManager
import com.pulsarnav.app.model.Maneuver
import com.pulsarnav.app.navigation.NavigationIntentLauncher
import com.pulsarnav.app.protocol.CallState
import com.pulsarnav.app.service.BikeBleForegroundService
import com.pulsarnav.app.service.GoogleMapsNotificationListener
import com.pulsarnav.app.telemetry.PhoneTelemetryManager
import com.pulsarnav.app.telephony.CallManager
import com.pulsarnav.app.wifi.TftMirrorSource
import com.pulsarnav.app.wifi.TftMirrorStatus
import com.pulsarnav.app.wifi.TftNavigationMapEngine
import com.pulsarnav.app.wifi.TftPasswordGenerator
import com.pulsarnav.app.wifi.TftWifiMirrorManager
import com.pulsarnav.app.wifi.TftWifiProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BajajBleManager
    private lateinit var callManager: CallManager
    private lateinit var telemetryManager: PhoneTelemetryManager
    private lateinit var appSettings: AppSettings
    private lateinit var parkingManager: ParkingManager
    private lateinit var maintenanceManager: MaintenanceManager
    private lateinit var fuelLogManager: FuelLogManager
    private lateinit var mediaControlManager: MediaControlManager

    private var currentTheme: CockpitTheme = getThemeDefinition(AppSettings.THEME_WINE_RED, isLightMode = false)

    private var pendingDocType: String? = null
    private var activeServiceHistoryDialog: AlertDialog? = null
    private var roundaboutTestState = 0
    private val documentPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { saveUploadedDocument(it, pendingDocType) }
    }

    private val vaultFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importVaultFromUri(it) }
    }

    private val vaultGetContentPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { importVaultFromUri(it) }
    }

    private val tftScreenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            if (appSettings.isTftCastForceLandscapeEnabled) {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            BikeBleForegroundService.startForTftLiveCast(this, result.resultCode, data)
            if (appSettings.isTftCastAutoOpenMapsEnabled) {
                showFeedback("Starting 800×480 live cast & opening Maps...")
                mainUiHandler.postDelayed({ proceedWithGoogleMapsLaunch() }, 700L)
            } else {
                showFeedback("Starting 800×480 live screen cast to TFT cluster...")
            }
        } else {
            showFeedback("Live screen cast cancelled")
        }
    }

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

        updateHardwareReadinessUi()
        updateTelephonyPermissionsUi()
        if (btGranted) {
            bleManager.startAutoConnect(force = true)
        } else {
            showFeedback("Bluetooth permissions are required to connect to your bike", isAlert = true)
        }
    }

    private val telephonyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        telemetryManager.initialize()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            callManager.initialize()
        }
        updateTelephonyPermissionsUi()
        val remaining = getMissingTelephonyPermissions()
        if (remaining.isEmpty()) {
            showFeedback("Call log, contacts, dial & SMS permissions granted")
        } else {
            showFeedback("Some telephony/POI permissions remain disabled (${remaining.size} left)", isAlert = true)
        }
        bleManager.syncPhonebookAndCalls()
        bleManager.dispatchPlaylistInfoOnBle()
    }

    private var pendingTftWifiAction: (() -> Unit)? = null
    private val tftWifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val action = pendingTftWifiAction
        pendingTftWifiAction = null
        if (getMissingTftWifiPermissions().isEmpty()) {
            action?.invoke()
        } else {
            showFeedback("Grant Nearby Wi-Fi / Location permission to start TFT Wi-Fi mirroring", isAlert = true)
        }
    }

    private var wasLiveCastActive = false
    private enum class PendingWifiActionType { NONE, MIRROR_HUD, LIVE_CAST, CONFIG_DIALOG }
    private var pendingWifiActionType = PendingWifiActionType.NONE
    private var pendingWifiAction: (() -> Unit)? = null
    private val wifiSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val action = pendingWifiAction
        val actionType = pendingWifiActionType
        pendingWifiAction = null
        pendingWifiActionType = PendingWifiActionType.NONE
        if (isWifiEnabled()) {
            showFeedback("Wi-Fi enabled")
            if (action != null) {
                action.invoke()
            } else {
                when (actionType) {
                    PendingWifiActionType.MIRROR_HUD -> startTftHudMirror()
                    PendingWifiActionType.LIVE_CAST -> checkLandscapeAndLaunchCast()
                    PendingWifiActionType.CONFIG_DIALOG -> showTftWifiConfigDialog()
                    PendingWifiActionType.NONE -> {}
                }
            }
        } else {
            showFeedback("Wi-Fi is still turned off. Wi-Fi is required to connect to cluster.", isAlert = true)
        }
    }

    private val statusDotPulseController = StatusDotPulseController()
    private lateinit var fuelListAdapter: FuelListAdapter
    private lateinit var discoveredBikeAdapter: DiscoveredBikeAdapter
    private lateinit var gloveboxController: GloveboxSheetController
    private lateinit var dataVaultController: DataVaultUiController
    private lateinit var tftLabsController: TftLabsController
    private lateinit var navigationHubController: NavigationHubController

    private fun startStatusDotPulse(dotView: View) = statusDotPulseController.start(dotView)
    private fun stopStatusDotPulse(dotView: View) = statusDotPulseController.stop(dotView)

    private val isAccountTabVisible = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var currentActiveTabId: Int = R.id.nav_home
    private var isRestoringTabSelection = false
    private var fuelListThemeDirty = true
    private var isUpdatingHexText = false
    private var pendingSeekThemeRunnable: Runnable? = null
    private val mainUiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        appSettings = AppSettings.getInstance(this)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (appSettings.isDarkMode) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdgeInsets()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.tabFuel.root.visibility == View.VISIBLE ||
                    binding.tabSupport.root.visibility == View.VISIBLE ||
                    binding.tabAccount.root.visibility == View.VISIBLE
                ) {
                    binding.bottomNav.selectedItemId = R.id.nav_home
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Initialize Managers
        bleManager = BajajBleManager.getInstance(this)
        callManager = CallManager.getInstance(this)
        telemetryManager = PhoneTelemetryManager.getInstance(this)
        parkingManager = ParkingManager.getInstance(this)
        maintenanceManager = MaintenanceManager.getInstance(this)
        fuelLogManager = FuelLogManager.getInstance(this)
        mediaControlManager = MediaControlManager.getInstance(this)

        telemetryManager.initialize()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            callManager.initialize()
        }

        // Setup Controllers & RecyclerViews
        setupControllersAndAdapters()

        // Setup UI Sections
        setupBottomNavigation()
        setupListeners()
        setupClusterRecalibrate()
        updateBikeInfoDisplay()
        updateDocumentsSummary()
        updateServiceHistorySummary()
        updateNavAppSelectionUi()
        updateHeroRangeDisplay()
        updateBikeIssuesSummary()
        updateEmergencyHomeCard()
        updateTelephonyPermissionsUi()
        updateSmsRejectUi()
        setupSupportAndAccountDialogs()
        setupFeatureToggles()
        setupThemeSelector()
        setupQuickNav()

        // Apply saved theme
        applyTheme(appSettings.appTheme)

        // Restore active tab (handles theme recreation / configuration changes)
        val savedKey = savedInstanceState?.getString("active_tab_key")
            ?: appSettings.activeTabKey
        val savedAccountScrollY = savedInstanceState?.getInt("account_scroll_y", 0) ?: 0
        restoreActiveTab(tabKeyToId(savedKey), preservedAccountScrollY = savedAccountScrollY)

        // Observers
        observeBleState()
        observeTftWifiMirror()
        setupTftAndLogDebugger()
        observeNavigation()
        observeTelephonyAndAlerts()
        observeMaintenance()
        observeFuelLog()

        // Handle quick action intents
        intent?.let { handleIntentActions(it) }

        // Initial scan if permissions are ready
        checkAndStartScan(silent = true)

        // Show introduction / vehicle setup on first launch if not configured yet
        updateQuickNavButtons()
        updateFuelPriceUi()
        binding.tabAccount.tvAppVersionTitle.text = "PulsarNav Companion v${com.pulsarnav.app.BuildConfig.VERSION_NAME}"

        binding.root.post {
            if (!appSettings.isSetupCompleted) {
                showVehicleSetupBottomSheet()
            }
        }
    }

    private val hardwareStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        updateHardwareReadinessUi()
                        bleManager.onBluetoothEnabled()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        updateHardwareReadinessUi()
                        bleManager.onBluetoothDisabled()
                    }
                    else -> updateHardwareReadinessUi()
                }
            } else {
                updateHardwareReadinessUi()
            }
        }
    }

    private fun setupControllersAndAdapters() {
        fuelListAdapter = FuelListAdapter(
            theme = currentTheme,
            currencySymbol = appSettings.currentCurrencySymbol,
            onEntryClick = { entry ->
                provideHapticFeedback()
                showEditFuelEntryDialog(entry)
            }
        )
        binding.tabFuel.rvFuelList.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            adapter = fuelListAdapter
        }
        binding.tabFuel.btnSortFuel.text =
            "Sort: ${FuelSortOrder.fromKey(appSettings.fuelSortOrder).shortLabel}"

        discoveredBikeAdapter = DiscoveredBikeAdapter(
            theme = currentTheme,
            onConnectClick = { bike -> bleManager.connect(bike.device) }
        )
        binding.tabCockpit.rvDeviceList.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            adapter = discoveredBikeAdapter
        }

        gloveboxController = GloveboxSheetController(
            activity = this,
            appSettings = appSettings,
            themeProvider = { currentTheme },
            createThemedEditText = { hint, initial, inputType -> createThemedEditText(hint, initial, inputType) },
            launchDocumentPicker = { docType ->
                pendingDocType = docType
                try {
                    documentPicker.launch(arrayOf("image/*", "application/pdf"))
                } catch (e: Exception) {
                    showFeedback("Could not open file picker: ${e.message}", isAlert = true)
                }
            },
            onDocumentsChanged = { updateDocumentsSummary() },
            onBikeInfoChanged = { updateBikeInfoDisplay() },
            onRecordSyncTime = { recordSyncTime() },
            onHaptic = { provideHapticFeedback() },
            onFeedback = { msg, isAlert -> showFeedback(msg, isAlert = isAlert) }
        )

        dataVaultController = DataVaultUiController(
            activity = this,
            rootViewProvider = { binding.rootLayout },
            themeProvider = { currentTheme },
            createThemedEditText = { hint, initial, inputType -> createThemedEditText(hint, initial, inputType) },
            launchVaultPickers = {
                try {
                    vaultGetContentPicker.launch("*/*")
                } catch (_: Exception) {
                    try {
                        vaultFilePicker.launch(arrayOf("*/*"))
                    } catch (e2: Exception) {
                        showFeedback("File picker unavailable: ${e2.message}", isAlert = true)
                    }
                }
            },
            onRefreshAfterImport = { refreshAllUiAfterVaultImport() },
            onHaptic = { provideHapticFeedback() },
            onFeedback = { msg, isAlert -> showFeedback(msg, isAlert = isAlert) }
        )

        tftLabsController = TftLabsController(
            activity = this,
            dbg = binding.tabAccount.labsTftDebug,
            appSettings = appSettings,
            bleManager = bleManager,
            callManager = callManager,
            isAccountTabVisible = isAccountTabVisible,
            themeProvider = { currentTheme },
            onHaptic = { provideHapticFeedback() },
            onFeedback = { msg, isAlert -> showFeedback(msg, isAlert = isAlert) }
        )

        navigationHubController = NavigationHubController(
            activity = this,
            appSettings = appSettings,
            isAccountTabVisible = isAccountTabVisible,
            isFullscreenShowing = { tftLabsController.isFullscreenShowing() },
            themeProvider = { currentTheme },
            createThemedEditText = { hint, initial, inputType -> createThemedEditText(hint, initial, inputType) },
            isWifiEnabled = { isWifiEnabled() },
            isLocationEnabled = { isLocationEnabled() },
            promptEnableWifiForConfig = { onReady -> promptEnableWifi(PendingWifiActionType.CONFIG_DIALOG, onReady) },
            isNotificationAccessGranted = { isNotificationAccessGranted() },
            onLaunchGoogleMapsRide = { launchGoogleMapsRide() },
            onHaptic = { provideHapticFeedback() },
            onFeedback = { msg, isAlert -> showFeedback(msg, isAlert = isAlert) }
        )
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAction(LocationManager.MODE_CHANGED_ACTION)
            }
        }
        ContextCompat.registerReceiver(
            this,
            hardwareStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateHardwareReadinessUi()
    }

    override fun onStop() {
        try {
            unregisterReceiver(hardwareStateReceiver)
        } catch (_: Exception) {}
        if (!isChangingConfigurations) {
            TftWifiMirrorManager.getInstance(this).stopLocalDebugPreview()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        DataVaultManager.getInstance(this).pruneExportCache()
        updateNotificationAccessUi()
        updateBatteryOptimizationUi()
        updateHardwareReadinessUi()
        updateTelephonyPermissionsUi()
        updateSmsRejectUi()
        updateBikeInfoDisplay()
        updateQuickNavButtons()
        updateFuelPriceUi()
        updateBikeIssuesSummary()
        updateNavAppSelectionUi()

        // Auto-connect when returning to the app if Bluetooth is active and not connected
        if (bleManager.connectionState.value is BleConnectionState.Disconnected) {
            checkAndStartScan(silent = true)
        }
    }

    override fun onDestroy() {
        pendingSeekThemeRunnable?.let { mainUiHandler.removeCallbacks(it) }
        pendingSeekThemeRunnable = null
        statusDotPulseController.cancelAll()
        if (::tftLabsController.isInitialized) {
            tftLabsController.cleanup()
        }
        if (!isChangingConfigurations) {
            TftWifiMirrorManager.getInstance(this).stopLocalDebugPreview()
        }
        if (isFinishing && !isChangingConfigurations) {
            DataVaultManager.getInstance(this).clearExportCache()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIntentActions(it) }
    }

    private fun handleIntentActions(intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            "com.pulsarnav.action.NAV_HOME" -> {
                val addr = appSettings.homeAddress
                launchNavigationTo(if (addr.isNotBlank() && !addr.equals("Home", ignoreCase = true)) addr else "Home")
            }
            "com.pulsarnav.action.NAV_WORK" -> {
                val addr = appSettings.workAddress
                launchNavigationTo(if (addr.isNotBlank() && !addr.equals("Work", ignoreCase = true)) addr else "Work")
            }
            "com.pulsarnav.action.FIND_BIKE" -> {
                openParkedBikeLocation()
            }
            "com.pulsarnav.action.LOG_REFUEL" -> {
                showAddFuelDialog()
            }
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                if (!sharedText.isNullOrBlank()) {
                    startTftRouteFromSharedInput(sharedText)
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    if (uri.scheme.equals("geo", ignoreCase = true) ||
                        uri.scheme.equals("google.navigation", ignoreCase = true)
                    ) {
                        startTftRouteFromSharedInput(uri.toString())
                    } else {
                        showExternalVaultImportConfirmation(uri)
                    }
                }
            }
        }
        // Consume intent so it does not re-trigger on activity recreation (e.g. rotation, theme changes)
        intent.action = null
        intent.data = null
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.removeExtra(Intent.EXTRA_SUBJECT)
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun openParkedBikeLocation() {
        val primaryIntent = parkingManager.getWalkingDirectionsIntent()
        val fallbackIntent = parkingManager.getFallbackWalkingDirectionsIntent()
        val parked = parkingManager.lastParkedLocation.value
        if (primaryIntent == null || parked == null) {
            showFeedback("No parked bike location saved yet")
            return
        }
        try {
            startActivity(primaryIntent)
            showFeedback("Walking directions to ${parked.address}")
        } catch (_: Exception) {
            if (fallbackIntent != null) {
                try {
                    startActivity(fallbackIntent)
                    showFeedback("Opening parked location: ${parked.address}")
                } catch (_: Exception) {
                    showFeedback("Unable to open Maps for parked location", isAlert = true)
                }
            }
        }
    }

    private fun parseDecimal(raw: CharSequence?): Double? =
        raw?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()

    private fun formatDecimal(value: Number, decimals: Int = 1): String =
        String.format(Locale.US, "%.${decimals}f", value.toDouble())

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val tabKey = tabIdToKey(currentActiveTabId)
        outState.putString("active_tab_key", tabKey)
        outState.putString("key_pending_wifi_action", pendingWifiActionType.name)
        if (::binding.isInitialized) {
            outState.putInt("account_scroll_y", binding.tabAccount.root.scrollY)
        }
        if (::appSettings.isInitialized) {
            appSettings.activeTabKey = tabKey
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val savedKey = savedInstanceState.getString("active_tab_key")
        val savedPendingAction = savedInstanceState.getString("key_pending_wifi_action")
        if (savedPendingAction != null) {
            try {
                pendingWifiActionType = PendingWifiActionType.valueOf(savedPendingAction)
            } catch (_: Exception) {}
        }
        val savedTab = if (savedKey != null) tabKeyToId(savedKey) else currentActiveTabId
        val savedAccountScrollY = savedInstanceState.getInt("account_scroll_y", 0)
        if (savedTab != currentActiveTabId) {
            restoreActiveTab(savedTab, preservedAccountScrollY = savedAccountScrollY)
        } else if (savedTab == R.id.nav_account && savedAccountScrollY > 0) {
            binding.tabAccount.root.post { binding.tabAccount.root.scrollTo(0, savedAccountScrollY) }
        }
    }

    private fun setupEdgeToEdgeInsets() {
        val baseTopLeft = binding.layoutTopBar.paddingLeft
        val baseTopTop = binding.layoutTopBar.paddingTop
        val baseTopRight = binding.layoutTopBar.paddingRight
        val baseTopBottom = binding.layoutTopBar.paddingBottom

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val sysBars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val imeVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            val imeInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())

            binding.layoutTopBar.setPadding(
                baseTopLeft + sysBars.left,
                baseTopTop + sysBars.top,
                baseTopRight + sysBars.right,
                baseTopBottom
            )

            if (imeVisible) {
                if (binding.bottomNav.visibility != View.GONE) {
                    binding.bottomNav.visibility = View.GONE
                }
                root.setPadding(0, 0, 0, imeInsets.bottom)
            } else {
                if (binding.bottomNav.visibility != View.VISIBLE) {
                    binding.bottomNav.visibility = View.VISIBLE
                }
                binding.bottomNav.setPadding(sysBars.left, 0, sysBars.right, sysBars.bottom)
                root.setPadding(0, 0, 0, 0)
            }
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
    }

    private fun tabIdToKey(tabId: Int): String = when (tabId) {
        R.id.nav_fuel -> AppSettings.TAB_FUEL
        R.id.nav_support -> AppSettings.TAB_SUPPORT
        R.id.nav_account -> AppSettings.TAB_ACCOUNT
        else -> AppSettings.TAB_HOME
    }

    private fun tabKeyToId(key: String?): Int = when (AppSettings.normalizeTabKey(key)) {
        AppSettings.TAB_FUEL -> R.id.nav_fuel
        AppSettings.TAB_SUPPORT -> R.id.nav_support
        AppSettings.TAB_ACCOUNT -> R.id.nav_account
        else -> R.id.nav_home
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (isRestoringTabSelection) return@setOnItemSelectedListener true
            provideHapticFeedback()
            if (item.itemId == currentActiveTabId) return@setOnItemSelectedListener true
            currentActiveTabId = item.itemId
            if (::appSettings.isInitialized) {
                appSettings.activeTabKey = tabIdToKey(item.itemId)
            }
            when (item.itemId) {
                R.id.nav_home -> switchTab(binding.tabCockpit.root)
                R.id.nav_fuel -> switchTab(binding.tabFuel.root)
                R.id.nav_support -> switchTab(binding.tabSupport.root)
                R.id.nav_account -> switchTab(binding.tabAccount.root)
                else -> false
            }
        }
    }

    private fun restoreActiveTab(tabId: Int, preservedAccountScrollY: Int = 0) {
        val targetView = when (tabId) {
            R.id.nav_fuel -> binding.tabFuel.root
            R.id.nav_support -> binding.tabSupport.root
            R.id.nav_account -> binding.tabAccount.root
            else -> binding.tabCockpit.root
        }
        val normalizedId = when (targetView) {
            binding.tabFuel.root -> R.id.nav_fuel
            binding.tabSupport.root -> R.id.nav_support
            binding.tabAccount.root -> R.id.nav_account
            else -> R.id.nav_home
        }
        currentActiveTabId = normalizedId
        if (binding.bottomNav.selectedItemId != normalizedId) {
            isRestoringTabSelection = true
            try {
                binding.bottomNav.selectedItemId = normalizedId
            } finally {
                isRestoringTabSelection = false
            }
        }
        switchTab(
            targetView,
            resetAccountScrollToTop = (normalizedId == R.id.nav_account && preservedAccountScrollY == 0)
        )
        if (normalizedId == R.id.nav_account && preservedAccountScrollY > 0) {
            binding.tabAccount.root.post { binding.tabAccount.root.scrollTo(0, preservedAccountScrollY) }
        }
    }

    private fun switchTab(targetView: View, resetAccountScrollToTop: Boolean = true): Boolean {
        val visibleCount = (if (binding.tabCockpit.root.visibility == View.VISIBLE) 1 else 0) +
            (if (binding.tabFuel.root.visibility == View.VISIBLE) 1 else 0) +
            (if (binding.tabSupport.root.visibility == View.VISIBLE) 1 else 0) +
            (if (binding.tabAccount.root.visibility == View.VISIBLE) 1 else 0)
        val alreadyVisible = targetView.visibility == View.VISIBLE && visibleCount == 1
        if (!alreadyVisible) {
            binding.tabCockpit.root.visibility = if (targetView === binding.tabCockpit.root) View.VISIBLE else View.GONE
            binding.tabFuel.root.visibility = if (targetView === binding.tabFuel.root) View.VISIBLE else View.GONE
            binding.tabSupport.root.visibility = if (targetView === binding.tabSupport.root) View.VISIBLE else View.GONE
            binding.tabAccount.root.visibility = if (targetView === binding.tabAccount.root) View.VISIBLE else View.GONE
        }

        val isAccount = targetView === binding.tabAccount.root
        isAccountTabVisible.value = isAccount
        if (isAccount) {
            if (!alreadyVisible && resetAccountScrollToTop) {
                binding.tabAccount.root.scrollTo(0, 0)
            }
            if (::tftLabsController.isInitialized && !tftLabsController.hasRenderedLogsOnce()) {
                tftLabsController.renderCurrentLogsForce()
            }
        } else if (!::tftLabsController.isInitialized || !tftLabsController.isFullscreenShowing()) {
            val mirrorMgr = TftWifiMirrorManager.getInstance(this)
            if (mirrorMgr.isLocalPreviewActiveFlow.value) {
                mirrorMgr.stopLocalDebugPreview()
            }
        }

        if (targetView === binding.tabFuel.root && fuelListThemeDirty && ::fuelLogManager.isInitialized) {
            fuelListThemeDirty = false
            updateFuelList(fuelLogManager.entries.value)
        }
        return true
    }

    private fun setupClusterRecalibrate() {
        binding.btnTopSos.setOnClickListener {
            provideHapticFeedback()
            showEmergencyQuickActionsSheet()
        }
        binding.tabCockpit.btnRecalibrateCluster.setOnClickListener {
            provideHapticFeedback()
            showClusterRecalibrateBottomSheet()
        }
        binding.tabCockpit.layoutProjectedRange.setOnClickListener {
            provideHapticFeedback()
            showClusterRecalibrateBottomSheet()
        }
        binding.tabCockpit.layoutHomeOdometer.setOnClickListener {
            provideHapticFeedback()
            showClusterRecalibrateBottomSheet()
        }
        binding.tabAccount.btnEditOdo.setOnClickListener {
            provideHapticFeedback()
            showClusterRecalibrateBottomSheet()
        }
    }

    private fun recordSyncTime() {
        appSettings.lastSyncedTimestampMs = System.currentTimeMillis()
        updateSyncTimeDisplay()
    }

    private fun updateSyncTimeDisplay() {
        val syncMs = appSettings.lastSyncedTimestampMs
        if (syncMs <= 0L) {
            binding.tabCockpit.tvSyncTimeSubtext.text = "Not synced yet • Tap Recalibrate"
            return
        }
        val elapsedMinutes = ((System.currentTimeMillis() - syncMs) / (60 * 1000)).coerceAtLeast(0)
        val text = when {
            elapsedMinutes < 1 -> "Synced just now"
            elapsedMinutes < 60 -> "Synced ${elapsedMinutes}m ago"
            else -> {
                val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
                "Synced at ${sdf.format(Date(syncMs))}"
            }
        }
        binding.tabCockpit.tvSyncTimeSubtext.text = text
    }

    private fun updateHeroRangeDisplay() {
        val dteKm = appSettings.projectedDteKm
        val fuelPercent = appSettings.fuelLevelPercent
        val fuelLiters = appSettings.fuelLevelLiters
        val model = appSettings.getActiveBikeProfile()
        val tankMax = model.tankCapacityLiters
        val reserveMax = model.reserveCapacityLiters

        if (dteKm <= 0 && fuelPercent <= 0) {
            binding.tabCockpit.tvProjectedRange.text = "--"
            binding.tabCockpit.tvDteSubtext.text = "Tap to sync"
            binding.tabCockpit.tvFuelLevelText.text = "0% Tank Level • 0.0L / ${formatDecimal(tankMax)}L"
            binding.tabCockpit.progressFuelLevel.progress = 0
        } else {
            binding.tabCockpit.tvProjectedRange.text = if (dteKm > 0) dteKm.toString() else "--"
            binding.tabCockpit.tvDteSubtext.text = "~${formatDecimal(fuelLiters.toDouble())}L in tank"
            binding.tabCockpit.tvFuelLevelText.text = "${fuelPercent}% Tank Level • ${formatDecimal(fuelLiters.toDouble())}L / ${formatDecimal(tankMax)}L"
            binding.tabCockpit.progressFuelLevel.progress = fuelPercent
        }

        // Unified Odometer & Mileage metrics across Home and Account tabs
        val totalOdo = maintenanceManager.status.value.totalOdometerKm
        binding.tabCockpit.tvHomeOdometer.text = formatDecimal(totalOdo)
        binding.tabAccount.tvAccountOdometer.text = "${formatDecimal(totalOdo)} km"

        val fuelStats = fuelLogManager.stats.value
        if (fuelStats.averageKmpl > 0.0) {
            binding.tabCockpit.tvHomeMileage.text = "${formatDecimal(fuelStats.averageKmpl)} km/l avg"
        } else {
            binding.tabCockpit.tvHomeMileage.text = "-- km/l"
        }

        binding.tabFuel.tvTankSpecs.text = "${formatDecimal(tankMax)}L Tank Capacity • ${formatDecimal(reserveMax)}L Res"
        binding.tabCockpit.tvTankReserveNote.text = "Reserve triggers below ${formatDecimal(reserveMax)}L"
        updateSyncTimeDisplay()
    }

    private fun updateBikeInfoDisplay() {
        val model = appSettings.getActiveBikeProfile()
        val country = appSettings.getActiveCountryProfile()
        val localizedName = VehicleCatalog.getLocalizedBikeName(model, country)

        binding.tabAccount.tvBikeName.text = localizedName
        val reg = if (appSettings.bikeRegNumber.isNotBlank()) appSettings.bikeRegNumber else "Add Number Plate"
        binding.tabAccount.tvBikeReg.text = "$reg • ${country.labelTag} ${country.name}"
        binding.tabAccount.tvProfileName.text = appSettings.riderName.ifBlank { "Rider Profile" }
        binding.tabFuel.tvTankSpecs.text = "${formatDecimal(model.tankCapacityLiters)}L Tank Capacity • ${formatDecimal(model.reserveCapacityLiters)}L Res"
    }

    private fun updateDocumentsSummary() {
        val rc = appSettings.docRcNumber.ifBlank { "No RC" }
        val ins = appSettings.docInsuranceExpiry.ifBlank { "No Ins" }
        val puc = appSettings.docPucExpiry.ifBlank { "No PUC" }
        val hasRc = appSettings.docRcPath.isNotBlank() && File(appSettings.docRcPath).exists()
        val hasIns = appSettings.docInsurancePath.isNotBlank() && File(appSettings.docInsurancePath).exists()
        val hasPuc = appSettings.docPucPath.isNotBlank() && File(appSettings.docPucPath).exists()
        val uploadedCount = listOf(hasRc, hasIns, hasPuc).count { it }

        binding.tabAccount.tvDocumentsSubtext.text = if (appSettings.docRcNumber.isNotBlank() || uploadedCount > 0) {
            "RC ($rc) • Ins ($ins) • PUC ($puc)"
        } else {
            "Upload RC, Insurance & PUC certificates"
        }
        val insStatus = GloveboxExpiryEvaluator.evaluate(hasIns, appSettings.docInsuranceExpiry)
        val pucStatus = GloveboxExpiryEvaluator.evaluate(hasPuc, appSettings.docPucExpiry)
        binding.tabAccount.tvDocumentsBadge.text = when {
            insStatus == GloveboxDocumentStatus.EXPIRED || pucStatus == GloveboxDocumentStatus.EXPIRED ->
                "$uploadedCount • EXPIRED"
            insStatus == GloveboxDocumentStatus.EXPIRING_SOON || pucStatus == GloveboxDocumentStatus.EXPIRING_SOON ->
                "$uploadedCount • EXPIRING"
            uploadedCount > 0 -> "$uploadedCount Uploaded"
            else -> "0 Uploaded"
        }
    }

    private fun updateBikeInvestmentDisplay() {
        val currency = appSettings.currentCurrencySymbol
        val fuelSpent = fuelLogManager.stats.value.totalSpentInr
        val serviceSpent = maintenanceManager.getTotalServiceCost()
        val totalSpent = fuelSpent + serviceSpent
        val entries = fuelLogManager.entries.value

        binding.tabFuel.tvTotalInvestment.text = "%s%.0f".format(currency, totalSpent)
        binding.tabFuel.tvInvestmentSubtext.text = "%s%.0f Fuel • %s%.0f Service".format(currency, fuelSpent, currency, serviceSpent)
        binding.tabFuel.tvFuelEntriesCount.text = "${entries.size} refill(s) logged"

        val fuelRatio = if (totalSpent > 0.0) ((fuelSpent / totalSpent) * 100).toInt().coerceIn(0, 100) else 0
        binding.tabFuel.progressFuelInvestment.progress = fuelRatio
        binding.tabFuel.tvFuelRatioCaption.text = if (totalSpent > 0.0) {
            "Fuel refills account for $fuelRatio% of total bike expenses"
        } else {
            ""
        }
    }

    private fun updateServiceHistorySummary() {
        val records = maintenanceManager.getServiceHistory()
        val totalServiceSpent = maintenanceManager.getTotalServiceCost()
        val currency = appSettings.currentCurrencySymbol
        val serviceInterval = MaintenanceManager.SERVICE_INTERVAL_KM

        binding.tabSupport.tvSupportTotalInvested.text = String.format(Locale.US, "%s%.0f", currency, totalServiceSpent)
        binding.tabSupport.tvSupportServiceRecordsCount.text = String.format(Locale.US, "%d service(s) logged", records.size)

        val maint = maintenanceManager.status.value
        if (maint.isServiceOverdue) {
            binding.tabSupport.tvSupportServiceRemaining.text = "OVERDUE"
            binding.tabSupport.tvSupportServiceRemainingUnit.visibility = View.GONE
            binding.tabSupport.tvSupportServiceRemaining.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
            binding.tabSupport.tvServiceHistorySummary.text = "Service Overdue • Immediate maintenance required"
            binding.tabSupport.progressSupportService.progress = 100
            binding.tabSupport.tvSupportHealthNote.text = "Schedule periodic service to maintain engine longevity"
        } else {
            binding.tabSupport.tvSupportServiceRemaining.text = String.format(Locale.US, "%.0f", maint.serviceRemainingKm.coerceAtLeast(0.0))
            binding.tabSupport.tvSupportServiceRemainingUnit.visibility = View.VISIBLE
            binding.tabSupport.tvSupportServiceRemaining.setTextColor(currentTheme.accentColor)

            val serviceElapsed = (serviceInterval - maint.serviceRemainingKm.coerceIn(0.0, serviceInterval))
            val serviceProgress = ((serviceElapsed / serviceInterval) * 100).toInt().coerceIn(0, 100)
            binding.tabSupport.progressSupportService.progress = serviceProgress
            binding.tabSupport.tvServiceHistorySummary.text = "Periodic Service • $serviceProgress% of cycle elapsed"
            binding.tabSupport.tvSupportHealthNote.text = String.format(Locale.US, "Next routine service due in %.0f km", maint.serviceRemainingKm.coerceAtLeast(0.0))
        }
        binding.tabSupport.tvSupportChainRemaining.text = String.format(Locale.US, "Chain Lube: %.0f km left", maint.chainLubeRemainingKm.coerceAtLeast(0.0))
        binding.tabSupport.tvSupportServiceStatusPill.text = String.format(Locale.US, "%,.0f km Cycle", serviceInterval)

        updateBikeInvestmentDisplay()
    }

    private fun showClusterRecalibrateBottomSheet() {
        val dialog = BottomSheetDialog(this, R.style.ObsidianBottomSheetDialog)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_recalibrate_cluster, null)
        dialog.setContentView(sheetView)

        val model = appSettings.getActiveBikeProfile()
        val tankMax = model.tankCapacityLiters.toFloat().coerceAtLeast(1.0f)
        val reserveL = model.reserveCapacityLiters.toFloat().coerceIn(0.5f, tankMax)
        val quarterL = (tankMax * 0.25f)
        val halfL = (tankMax * 0.50f)
        val estMileage = fuelLogManager.stats.value.averageKmpl.takeIf { it > 0.0 } ?: 35.0

        val etDte = sheetView.findViewById<EditText>(R.id.etRecalibrateDte)
        val etOdo = sheetView.findViewById<EditText>(R.id.etRecalibrateOdo)
        val etFuel = sheetView.findViewById<EditText>(R.id.etRecalibrateFuelLiters)
        val tvFuelSub = sheetView.findViewById<TextView>(R.id.tvRecalibrateFuelSubtitle)
        listOfNotNull(etDte, etOdo, etFuel).forEach { applyThemeToEditText(it) }
        val chipReserve = sheetView.findViewById<MaterialButton>(R.id.chipPresetReserve)
        val chipQuarter = sheetView.findViewById<MaterialButton>(R.id.chipPresetQuarter)
        val chipHalf = sheetView.findViewById<MaterialButton>(R.id.chipPresetHalf)
        val chipFull = sheetView.findViewById<MaterialButton>(R.id.chipPresetFull)
        val btnSave = sheetView.findViewById<MaterialButton>(R.id.btnSaveRecalibration)

        tvFuelSub?.text = "Quick select from cluster fuel bars or enter liters (Total ${formatDecimal(tankMax.toDouble())}L)"
        chipReserve?.text = "Reserve\n(${formatDecimal(reserveL.toDouble())}L)"
        chipQuarter?.text = "1/4 Tank\n(${formatDecimal(quarterL.toDouble())}L)"
        chipHalf?.text = "1/2 Tank\n(${formatDecimal(halfL.toDouble())}L)"
        chipFull?.text = "Full\n(${formatDecimal(tankMax.toDouble())}L)"

        val currentOdo = maintenanceManager.status.value.totalOdometerKm
        val currentDte = appSettings.projectedDteKm
        if (currentDte > 0) {
            etDte?.setText(currentDte.toString())
        } else {
            etDte?.setText("")
        }
        etOdo?.setText(formatDecimal(currentOdo))
        etFuel?.setText(formatDecimal(appSettings.fuelLevelLiters.toDouble()))

        btnSave?.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
        btnSave?.setTextColor(currentTheme.onPrimaryColor)

        val updatePresetChips = { selectedLiters: Float ->
            val presets = listOf(
                chipReserve to reserveL,
                chipQuarter to quarterL,
                chipHalf to halfL,
                chipFull to tankMax
            )
            for ((chip, lit) in presets) {
                if (chip != null) {
                    val isSelected = Math.abs(selectedLiters - lit) < 0.15f
                    if (isSelected) {
                        chip.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
                        chip.strokeColor = ColorStateList.valueOf(currentTheme.accentColor)
                        chip.setTextColor(currentTheme.accentColor)
                    } else {
                        chip.backgroundTintList = ColorStateList.valueOf(currentTheme.cardColor)
                        chip.strokeColor = ColorStateList.valueOf(currentTheme.cardBorderColor)
                        chip.setTextColor(currentTheme.textSecondaryColor)
                    }
                }
            }
        }

        updatePresetChips(appSettings.fuelLevelLiters)

        val autoFillDteIfEmpty = { litersSelected: Float ->
            val current = etDte?.text?.toString()?.trim().orEmpty()
            if (current.isEmpty() || current == "0") {
                etDte?.setText((litersSelected * estMileage).roundToInt().toString())
            }
        }

        chipReserve?.setOnClickListener {
            provideHapticFeedback()
            etFuel?.setText(formatDecimal(reserveL.toDouble()))
            autoFillDteIfEmpty(reserveL)
            updatePresetChips(reserveL)
        }
        chipQuarter?.setOnClickListener {
            provideHapticFeedback()
            etFuel?.setText(formatDecimal(quarterL.toDouble()))
            autoFillDteIfEmpty(quarterL)
            updatePresetChips(quarterL)
        }
        chipHalf?.setOnClickListener {
            provideHapticFeedback()
            etFuel?.setText(formatDecimal(halfL.toDouble()))
            autoFillDteIfEmpty(halfL)
            updatePresetChips(halfL)
        }
        chipFull?.setOnClickListener {
            provideHapticFeedback()
            etFuel?.setText(formatDecimal(tankMax.toDouble()))
            autoFillDteIfEmpty(tankMax)
            updatePresetChips(tankMax)
        }

        btnSave?.setOnClickListener {
            provideHapticFeedback()
            val enteredDte = (etDte?.text?.toString()?.trim()?.toIntOrNull() ?: appSettings.projectedDteKm).coerceAtLeast(0)
            val enteredOdo = (parseDecimal(etOdo?.text) ?: currentOdo).coerceAtLeast(0.0)
            val enteredLiters = ((parseDecimal(etFuel?.text)?.toFloat()) ?: appSettings.fuelLevelLiters).coerceIn(0.0f, tankMax)
            val percent = ((enteredLiters / tankMax) * 100f).roundToInt().coerceIn(0, 100)

            appSettings.projectedDteKm = enteredDte
            appSettings.fuelLevelPercent = percent
            appSettings.fuelLevelLiters = enteredLiters
            maintenanceManager.setOdometer(enteredOdo)
            recordSyncTime()
            updateHeroRangeDisplay()

            dialog.dismiss()
            showFeedback("Cluster synced: $enteredDte km DTE • ${formatDecimal(enteredOdo)} km ODO")
        }

        dialog.show()
    }

    private fun setupListeners() {
        // BLE Scan / Disconnect Button
        binding.tabCockpit.btnScan.setOnClickListener {
            provideHapticFeedback()
            when (bleManager.connectionState.value) {
                is BleConnectionState.Connected -> {
                    bleManager.disconnect()
                    BikeBleForegroundService.stop(this)
                }
                is BleConnectionState.Scanning -> bleManager.stopScan()
                else -> checkAndStartScan(silent = false)
            }
        }

        // TFT Wi-Fi Full-Screen Mirroring Controls (NS400Z / Bajaj TFT)
        binding.tabCockpit.btnConfigureTftWifi.setOnClickListener {
            provideHapticFeedback()
            showTftWifiConfigDialog()
        }
        binding.tabCockpit.btnToggleTftHudMirror.setOnClickListener {
            provideHapticFeedback()
            val wifiMgr = TftWifiMirrorManager.getInstance(this)
            val mirrorState = wifiMgr.mirrorState.value
            val isSessionActive = mirrorState.status != TftMirrorStatus.IDLE && mirrorState.status != TftMirrorStatus.ERROR
            if (isSessionActive && mirrorState.source == TftMirrorSource.LIVE_SCREEN_CAST) {
                // Seamlessly switch from Screen Cast to lock-proof Live Map / HUD
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                wifiMgr.startPreferredHeadlessMirroring()
                showFeedback("Switched to lock-proof ${if (appSettings.tftMirrorMode == AppSettings.TFT_MODE_HUD) "HUD" else "Live Map"}")
            } else if (isSessionActive) {
                BikeBleForegroundService.stopTftMirror(this)
                showFeedback("TFT Wi-Fi mirror stopped")
            } else {
                ensureWifiEnabled {
                    val missingWifi = getMissingTftWifiPermissions()
                    if (missingWifi.isNotEmpty()) {
                        pendingTftWifiAction = { startTftHudMirror() }
                        tftWifiPermissionLauncher.launch(missingWifi.toTypedArray())
                    } else {
                        startTftHudMirror()
                    }
                }
            }
        }
        binding.tabCockpit.btnCastLiveScreen.setOnClickListener {
            provideHapticFeedback()
            val mirrorState = TftWifiMirrorManager.getInstance(this).mirrorState.value
            if (mirrorState.source == TftMirrorSource.LIVE_SCREEN_CAST &&
                mirrorState.status != TftMirrorStatus.IDLE &&
                mirrorState.status != TftMirrorStatus.ERROR
            ) {
                BikeBleForegroundService.stopTftMirror(this)
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                showFeedback("Live TFT cast stopped")
            } else {
                ensureWifiEnabled {
                    val missingWifi = getMissingTftWifiPermissions()
                    if (missingWifi.isNotEmpty()) {
                        pendingTftWifiAction = { checkLandscapeAndLaunchCast() }
                        tftWifiPermissionLauncher.launch(missingWifi.toTypedArray())
                    } else {
                        checkLandscapeAndLaunchCast()
                    }
                }
            }
        }

        // Notification Access & Maps TBT Troubleshooting
        binding.tabCockpit.btnEnableNotificationAccess.setOnClickListener {
            provideHapticFeedback()
            openNotificationListenerSettings()
        }
        binding.tabAccount.rowNotificationAccess.setOnClickListener {
            provideHapticFeedback()
            if (isNotificationAccessGranted()) {
                showMapsTbtTroubleshooterDialog()
            } else {
                openNotificationListenerSettings()
            }
        }
        binding.tabAccount.tvMapsTbtFixLink.setOnClickListener {
            provideHapticFeedback()
            showMapsTbtTroubleshooterDialog()
        }
        binding.tabAccount.rowBatteryOptimization.setOnClickListener {
            provideHapticFeedback()
            requestIgnoreBatteryOptimization()
        }

        // Active TFT Live Navigation Dashboard Card
        binding.tabCockpit.cardActiveTftNavigation.setOnClickListener {
            provideHapticFeedback()
            showNavigationHubBottomSheet()
        }
        binding.tabCockpit.btnActiveNavReroute.setOnClickListener {
            provideHapticFeedback()
            TftNavigationMapEngine.getInstance(this).triggerManualReroute()
            showFeedback("Recalculating route...")
        }
        binding.tabCockpit.btnActiveNavStop.setOnClickListener {
            provideHapticFeedback()
            TftNavigationMapEngine.getInstance(this).clearActiveRoute()
            showFeedback("PulsarNav Live Map route cleared")
        }

        // Search Bar click -> Built-in Lock-Proof TFT Route OR Google Maps
        binding.tabCockpit.layoutSearchBar.setOnClickListener {
            provideHapticFeedback()
            showNavigationHubBottomSheet()
        }

        // Quick Nearby Fuel on Fuel Tab
        binding.tabFuel.btnFindPumps.setOnClickListener {
            launchNearbyFuelPumps()
        }

        // Turn simulation test bench (Continuous ~800ms stream to motorcycle cluster)
        binding.tabAccount.btnTestLeft.setOnClickListener {
            provideHapticFeedback()
            if (bleManager.isSimulationRunning.value && bleManager.currentActiveManeuver.value == Maneuver.TURN_LEFT) {
                bleManager.stopSimulation()
                showFeedback("Turn simulation stopped")
            } else {
                bleManager.startSimulation(Maneuver.TURN_LEFT, 250, "Left onto MG Road")
                updateNavDisplay(Maneuver.TURN_LEFT, "250 m", "Left onto MG Road", "14 min ETA")
                showFeedback("Simulating Left Turn on Cluster (~800ms stream)")
            }
        }
        binding.tabAccount.btnTestStraight.setOnClickListener {
            provideHapticFeedback()
            if (bleManager.isSimulationRunning.value && bleManager.currentActiveManeuver.value == Maneuver.STRAIGHT) {
                bleManager.stopSimulation()
                showFeedback("Turn simulation stopped")
            } else {
                bleManager.startSimulation(Maneuver.STRAIGHT, 750, "Western Express Highway")
                updateNavDisplay(Maneuver.STRAIGHT, "750 m", "Western Express Highway", "11 min ETA")
                showFeedback("Simulating Straight on Cluster (~800ms stream)")
            }
        }
        binding.tabAccount.btnTestRight.setOnClickListener {
            provideHapticFeedback()
            if (bleManager.isSimulationRunning.value && bleManager.currentActiveManeuver.value == Maneuver.TURN_RIGHT) {
                bleManager.stopSimulation()
                showFeedback("Turn simulation stopped")
            } else {
                bleManager.startSimulation(Maneuver.TURN_RIGHT, 150, "Right onto Link Road")
                updateNavDisplay(Maneuver.TURN_RIGHT, "150 m", "Right onto Link Road", "8 min ETA")
                showFeedback("Simulating Right Turn on Cluster (~800ms stream)")
            }
        }
        binding.tabAccount.btnTestRoundabout.setOnClickListener {
            provideHapticFeedback()
            if (bleManager.isSimulationRunning.value && (bleManager.currentActiveManeuver.value == Maneuver.ROUNDABOUT_LEFT || bleManager.currentActiveManeuver.value == Maneuver.ROUNDABOUT_RIGHT)) {
                if (roundaboutTestState == 1) {
                    roundaboutTestState = 2
                    bleManager.startSimulation(Maneuver.ROUNDABOUT_LEFT, 200, "Exit 2 • Ring Road", roundaboutExit = 2)
                    updateNavDisplay(Maneuver.ROUNDABOUT_LEFT, "200 m", "Exit 2 • Ring Road", "7 min ETA")
                    binding.tabAccount.btnTestRoundabout.text = "Testing Exit 2 (Tap to Stop)"
                    showFeedback("Simulating Roundabout Exit 2 (OTC byte 7 = 0x02)")
                } else {
                    roundaboutTestState = 0
                    bleManager.stopSimulation()
                    binding.tabAccount.btnTestRoundabout.text = "Test Roundabout"
                    showFeedback("Roundabout simulation stopped")
                }
            } else {
                roundaboutTestState = 1
                bleManager.startSimulation(Maneuver.ROUNDABOUT_LEFT, 200, "Roundabout • Ring Road", roundaboutExit = 0)
                updateNavDisplay(Maneuver.ROUNDABOUT_LEFT, "200 m", "Roundabout • Ring Road", "7 min ETA")
                binding.tabAccount.btnTestRoundabout.text = "Testing Safe Exit 0 (Tap for Exit 2)"
                showFeedback("Simulating Safe Roundabout (Exit 0: No exit number misdirection)")
            }
        }
        binding.tabAccount.btnStopSimulation.setOnClickListener {
            provideHapticFeedback()
            roundaboutTestState = 0
            binding.tabAccount.btnTestRoundabout.text = "Test Roundabout"
            bleManager.stopSimulation()
            bleManager.stopNavigation()
            binding.tabAccount.tvDistance.text = "-- m"
            binding.tabAccount.tvStreetName.text = "Navigation stopped"
            binding.tabAccount.tvManeuverIcon.text = "⏹"
            binding.tabAccount.tvEtaBadge.text = "Idle"
            showFeedback("Simulation Stopped & Cluster Cleared")
        }

        // Call & WhatsApp simulation
        binding.tabAccount.btnSimulateCall.setOnClickListener {
            provideHapticFeedback()
            if (callManager.callInfo.value.state == CallState.NO_CALL) {
                callManager.simulateIncomingCall("Dad (Mobile)", "+91 98765 43210")
                showFeedback("Simulating Incoming Call to Cluster (tap again to end)")
            } else {
                callManager.simulateEndCall()
                showFeedback("Call Ended / Idle")
            }
        }

        binding.tabAccount.btnSimulateWhatsApp.setOnClickListener {
            provideHapticFeedback()
            val sampleSender = "Rahul Sharma"
            bleManager.sendWhatsAppAlert(sampleSender, com.pulsarnav.app.protocol.AlertType.WHATSAPP)
            binding.tabAccount.tvWhatsAppAlertBanner.text = "WhatsApp Alert: $sampleSender (Showing 6s)"
            binding.tabAccount.tvWhatsAppAlertBanner.setTextColor(currentTheme.accentColor)
            showFeedback("WhatsApp alert sent to cluster (6s preview)")
        }

        binding.tabAccount.btnSimulateSms.setOnClickListener {
            provideHapticFeedback()
            val sampleSender = "Vikram (+91 98765)"
            bleManager.sendWhatsAppAlert(sampleSender, com.pulsarnav.app.protocol.AlertType.SMS)
            binding.tabAccount.tvWhatsAppAlertBanner.text = "SMS Alert: $sampleSender (Showing 6s)"
            binding.tabAccount.tvWhatsAppAlertBanner.setTextColor(currentTheme.accentColor)
            showFeedback("SMS alert (0410 type=1) sent to cluster (6s preview)")
        }

        binding.tabAccount.btnSimulateMissedCall.setOnClickListener {
            provideHapticFeedback()
            callManager.simulateMissedCall("Mom", "+91 98765 12345")
            showFeedback("Simulating Missed Call Alert on Cluster")
        }

        binding.tabAccount.btnSyncPhonebookCalls.setOnClickListener {
            provideHapticFeedback()
            if (bleManager.isConnectedToTftCluster()) {
                bleManager.syncPhonebookAndCalls()
                bleManager.dispatchPlaylistInfoOnBle()
                showFeedback("Syncing Favs, Call Log & Playlist to cluster...")
            } else {
                showFeedback("Phonebook & Playlist sync is only available on Color TFT clusters (NS400Z)", isAlert = true)
            }
        }

        binding.tabAccount.btnGrantTelephonyPerms.setOnClickListener {
            provideHapticFeedback()
            requestTelephonyAndClusterPermissions()
        }

        // Edit Odometer
        binding.tabAccount.btnEditOdo.setOnClickListener {
            provideHapticFeedback()
            showClusterRecalibrateBottomSheet()
        }

        // Mark Chain Lubed
        binding.tabSupport.btnMarkChainLubed.setOnClickListener {
            provideHapticFeedback()
            maintenanceManager.markChainLubed()
            recordSyncTime()
            showFeedback("Chain marked cleaned & lubed (500 km reset)")
        }

        // Mark Serviced
        binding.tabSupport.btnMarkServiced.setOnClickListener {
            provideHapticFeedback()
            maintenanceManager.markServiceDone()
            recordSyncTime()
            val target = maintenanceManager.status.value.nextServiceTargetOdoKm
            showFeedback("Service recorded. Next service target: %.0f km".format(target))
        }

        // Add Refuel Entry
        binding.tabFuel.btnAddFuel.setOnClickListener {
            provideHapticFeedback()
            showAddFuelDialog()
        }
        binding.tabFuel.btnFuelAction.setOnClickListener {
            provideHapticFeedback()
            showAddFuelDialog()
        }
        binding.tabFuel.btnSortFuel.setOnClickListener {
            provideHapticFeedback()
            showFuelSortDialog()
        }
        binding.tabFuel.layoutFuelInvestment.setOnClickListener {
            provideHapticFeedback()
            showServiceHistoryDialog()
        }
        binding.tabFuel.layoutFuelMileage.setOnClickListener {
            provideHapticFeedback()
            showClusterRecalibrateBottomSheet()
        }


        // Emergency & Medical Assistance
        binding.tabCockpit.btnEmergencyHospitals.setOnClickListener {
            launchEmergencyHospitals()
        }
        binding.tabCockpit.btnEmergencyAmbulance.setOnClickListener {
            provideHapticFeedback()
            showEmergencyQuickActionsSheet()
        }
        binding.tabCockpit.layoutEmergencyRiderInfo.setOnClickListener {
            showEmergencyMedicalDialog()
        }
    }

    private fun launchGoogleMapsRide() {
        if (!isNotificationAccessGranted()) {
            showNotificationAccessDialog { proceedWithGoogleMapsLaunch() }
            return
        }
        proceedWithGoogleMapsLaunch()
    }

    private fun proceedWithGoogleMapsLaunch() {
        BikeBleForegroundService.start(this)
        if (!isLocationEnabled()) {
            showFeedback("GPS is OFF — launching Maps anyway; turn ON Location for live tracking", isAlert = true)
        }
        if (appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage("com.waze")?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    return
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("waze://?navigate=yes")).apply {
                    setPackage("com.waze")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?navigate=yes")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    return
                } catch (_: Exception) {}
            }
        }
        try {
            // First: launch Google Maps directly via launch intent (preserves active navigation or opens to current GPS location)
            val launchIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            if (launchIntent != null) {
                startActivity(launchIntent)
                return
            }
            val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=maps")).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(mapIntent)
        } catch (e: Exception) {
            try {
                val travelMode = if (appSettings.isTwoWheelerMode) "two_wheeler" else "driving"
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com?travelmode=$travelMode")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e2: Exception) {
                showFeedback("Could not open navigation app", isAlert = true)
            }
        }
    }

    private fun launchNearbyFuelPumps() {
        provideHapticFeedback()
        if (appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE) {
            try {
                val uri = Uri.parse("waze://?q=gas+station&navigate=yes")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.waze")
                }
                startActivity(intent)
                return
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=gas+station&navigate=yes")))
                    return
                } catch (_: Exception) {}
            }
        }
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
                showFeedback("Could not locate nearby petrol pumps", isAlert = true)
            }
        }
    }

    private fun setupSupportAndAccountDialogs() {
        // Service History Dialog (Editable)
        binding.tabSupport.cardServiceHistory.setOnClickListener {
            provideHapticFeedback()
            showServiceHistoryDialog()
        }
        binding.tabSupport.tvServiceHistoryAction.setOnClickListener {
            provideHapticFeedback()
            showServiceHistoryDialog()
        }
        binding.tabSupport.layoutSupportSpent.setOnClickListener {
            provideHapticFeedback()
            showServiceHistoryDialog()
        }
        binding.tabSupport.layoutSupportNextService.setOnClickListener {
            provideHapticFeedback()
            showServiceHistoryDialog()
        }

        // Roadside Assistance Dialog (24x7)
        binding.tabSupport.rowRsa.setOnClickListener {
            provideHapticFeedback()
            showRsaDialog()
        }

        // Vehicle Manual & NS200 Specs
        binding.tabSupport.rowManual.setOnClickListener {
            provideHapticFeedback()
            showManualDialog()
        }

        // Support Issue Button
        binding.tabSupport.btnSupportIssue.setOnClickListener {
            provideHapticFeedback()
            showSupportIssueDialog()
        }


        // Digital Glovebox Documents & Uploads
        binding.tabAccount.rowDocuments.setOnClickListener {
            provideHapticFeedback()
            showDocumentsDialog()
        }

        // Edit Motorcycle Info
        binding.tabAccount.cardMyBike.setOnClickListener {
            provideHapticFeedback()
            showEditBikeInfoDialog()
        }
        binding.tabAccount.btnEditBikeInfo.setOnClickListener {
            provideHapticFeedback()
            showEditBikeInfoDialog()
        }
        binding.tabAccount.btnSetupBikeModel.setOnClickListener {
            provideHapticFeedback()
            showVehicleSetupBottomSheet()
        }

        // Hardware warning chips in top bar
        binding.chipBtWarning.setOnClickListener {
            provideHapticFeedback()
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: Exception) {
                showFeedback("Open Bluetooth Settings in Android", isAlert = true)
            }
        }
        binding.chipGpsWarning.setOnClickListener {
            provideHapticFeedback()
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (_: Exception) {
                showFeedback("Open Location Settings in Android", isAlert = true)
            }
        }
        binding.chipPermWarning.setOnClickListener {
            provideHapticFeedback()
            val missing = getMissingPermissions()
            if (missing.isNotEmpty()) {
                permissionLauncher.launch(missing.toTypedArray())
            } else {
                showFeedback("All required permissions are granted")
                updateHardwareReadinessUi()
            }
        }

        // Bike Issues Checklist Card
        binding.tabSupport.cardBikeIssues.setOnClickListener {
            provideHapticFeedback()
            showBikeIssuesChecklistDialog()
        }

        // Service Target / Milestone Schedule
        binding.tabSupport.btnSetServiceTarget.setOnClickListener {
            provideHapticFeedback()
            showServiceTargetDialog()
        }

        // Fuel Price Setting Card Row
        binding.tabFuel.rowFuelPriceSetting.setOnClickListener {
            provideHapticFeedback()
            showEditPetrolPriceDialog()
        }

        // Edit Rider Profile
        binding.tabAccount.cardProfile.setOnClickListener {
            provideHapticFeedback()
            showEditRiderProfileDialog()
        }

        // Data Vault & Phone Migration
        binding.tabAccount.rowVaultQr.setOnClickListener {
            provideHapticFeedback()
            showDataVaultBottomSheet(openQrDirectly = true)
        }
        binding.tabAccount.rowVaultBackup.setOnClickListener {
            provideHapticFeedback()
            showDataVaultBottomSheet(openQrDirectly = false)
        }

        // Navigation Provider Preference in Account Tab
        binding.tabAccount.btnNavChooseGmaps.setOnClickListener {
            provideHapticFeedback()
            appSettings.preferredNavApp = AppSettings.NAV_APP_GOOGLE_MAPS
            updateNavAppSelectionUi()
            updateQuickNavButtons()
            showFeedback("Default navigation app set to Google Maps")
        }
        binding.tabAccount.btnNavChooseWaze.setOnClickListener {
            provideHapticFeedback()
            appSettings.preferredNavApp = AppSettings.NAV_APP_WAZE
            updateNavAppSelectionUi()
            updateQuickNavButtons()
            showFeedback("Default navigation app set to Waze")
        }
    }

    private fun showEditBikeInfoDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvInfo = TextView(this).apply {
            text = "Edit your motorcycle registration and specs:"
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvInfo)

        // Bike Name
        val tvNameLabel = TextView(this).apply {
            text = "Motorcycle Name / Nickname:"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvNameLabel)

        val etName = createThemedEditText(hintText = "e.g. Pulsar NS200", initialText = appSettings.bikeName)
        layout.addView(etName)

        // Registration Number (Plate)
        val tvRegLabel = TextView(this).apply {
            text = "\nRegistration Number (Plate):"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvRegLabel)

        val etReg = createThemedEditText(hintText = "e.g. MH02GC2593", initialText = appSettings.bikeRegNumber)
        layout.addView(etReg)

        // Model / Spec
        val tvSpecLabel = TextView(this).apply {
            text = "\nModel & Spec Details:"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvSpecLabel)

        val etSpec = createThemedEditText(hintText = "e.g. BS6 DTS-i 4V Liquid-Cooled", initialText = appSettings.bikeModelSpec)
        layout.addView(etSpec)

        val scrollView: View = ScrollView(this).apply { addView(layout) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Motorcycle Details")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                val reg = etReg.text.toString().trim()
                val spec = etSpec.text.toString().trim()

                if (name.isNotBlank()) appSettings.bikeName = name
                if (reg.isNotBlank()) {
                    appSettings.bikeRegNumber = reg
                    appSettings.docRcNumber = reg
                }
                if (spec.isNotBlank()) appSettings.bikeModelSpec = spec

                updateBikeInfoDisplay()
                updateDocumentsSummary()
                recordSyncTime()
                showFeedback("Motorcycle details updated!")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditRiderProfileDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvInfo = TextView(this).apply {
            text = "Edit your rider profile name and contact number:"
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvInfo)

        val tvNameLabel = TextView(this).apply {
            text = "Rider Name:"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvNameLabel)

        val etName = createThemedEditText(hintText = "e.g. Alex Rider", initialText = appSettings.riderName)
        layout.addView(etName)

        val tvPhoneLabel = TextView(this).apply {
            text = "\nPhone Number:"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvPhoneLabel)

        val etPhone = createThemedEditText(
            hintText = "e.g. +91 98765 43210",
            initialText = appSettings.riderPhone,
            inputTypeFlags = android.text.InputType.TYPE_CLASS_PHONE
        )
        layout.addView(etPhone)

        val scrollView: View = ScrollView(this).apply { addView(layout) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Rider Profile")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                val phone = etPhone.text.toString().trim()

                if (name.isNotBlank()) appSettings.riderName = name
                if (phone.isNotBlank()) appSettings.riderPhone = phone

                updateBikeInfoDisplay()
                recordSyncTime()
                showFeedback("Profile updated!")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showServiceHistoryDialog() {
        activeServiceHistoryDialog?.dismiss()
        activeServiceHistoryDialog = null

        val records = maintenanceManager.getServiceHistory()
        val odo = maintenanceManager.status.value.totalOdometerKm
        val currency = appSettings.currentCurrencySymbol
        val totalServiceSpent = maintenanceManager.getTotalServiceCost()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        val tvSummary = TextView(this).apply {
            text = String.format(
                Locale.US,
                "Total Maintenance Investment: %s%.0f (%d services)\nNext Due at %.0f km • Tap to edit or delete:",
                currency,
                totalServiceSpent,
                records.size,
                odo + maintenanceManager.status.value.serviceRemainingKm
            )
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvSummary)

        if (records.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "No service records logged yet.\nTap 'Add Service Record' below to track maintenance."
                setTextColor(currentTheme.textTertiaryColor)
                textSize = 13f
                setPadding(10, 20, 10, 30)
            }
            layout.addView(tvEmpty)
        }

        for (rec in records) {
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 20)
                }
                setCardBackgroundColor(currentTheme.cardColor)
                strokeColor = currentTheme.cardBorderColor
                strokeWidth = dpToPx(1)
                radius = dpToPx(12).toFloat()
            }

            val cardContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(30, 24, 30, 24)
            }

            val rowTop = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvTitle = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = rec.title
                setTextColor(currentTheme.textPrimaryColor)
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            rowTop.addView(tvTitle)

            val tvCost = TextView(this).apply {
                text = rec.cost
                setTextColor(currentTheme.accentColor)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            rowTop.addView(tvCost)
            cardContent.addView(rowTop)

            val tvMeta = TextView(this).apply {
                text = "${rec.date} • ${formatDecimal(rec.odoKm)} km"
                setTextColor(currentTheme.textTertiaryColor)
                textSize = 11f
                setPadding(0, 4, 0, 4)
            }
            cardContent.addView(tvMeta)

            if (rec.items.isNotEmpty()) {
                val layoutItems = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 6, 0, 4)
                }
                for (item in rec.items) {
                    val rowItem = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 2, 0, 2)
                    }
                    val tvItemName = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text = "• ${item.name}"
                        setTextColor(currentTheme.textSecondaryColor)
                        textSize = 12f
                    }
                    val tvItemCost = TextView(this).apply {
                        text = if (item.cost > 0.0) String.format(Locale.US, "%s%.0f", currency, item.cost) else "--"
                        setTextColor(currentTheme.accentColor)
                        textSize = 12f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }
                    rowItem.addView(tvItemName)
                    rowItem.addView(tvItemCost)
                    layoutItems.addView(rowItem)
                }
                cardContent.addView(layoutItems)
            }

            if (rec.notes.isNotBlank()) {
                val tvNotes = TextView(this).apply {
                    text = rec.notes
                    setTextColor(currentTheme.textTertiaryColor)
                    textSize = 11f
                    setPadding(0, 4, 0, 0)
                }
                cardContent.addView(tvNotes)
            }

            val rowActions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                setPadding(0, 10, 0, 0)
            }

            val btnEdit = MaterialButton(this).apply {
                text = "Edit"
                textSize = 11f
                setTextColor(currentTheme.accentColor)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    provideHapticFeedback()
                    showAddOrEditServiceRecordDialog(rec)
                }
            }
            rowActions.addView(btnEdit)

            val btnDelete = MaterialButton(this).apply {
                text = "Delete"
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_disconnected))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    provideHapticFeedback()
                    maintenanceManager.deleteServiceRecord(rec.id)
                    recordSyncTime()
                    updateServiceHistorySummary()
                    updateBikeInvestmentDisplay()
                    showFeedback("Record deleted")
                    showServiceHistoryDialog()
                }
            }
            rowActions.addView(btnDelete)

            cardContent.addView(rowActions)
            card.addView(cardContent)
            layout.addView(card)
        }

        val scrollView: View = ScrollView(this).apply { addView(layout) }

        activeServiceHistoryDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Service History & Records")
            .setView(scrollView)
            .setPositiveButton("Add Service Record") { _, _ ->
                provideHapticFeedback()
                showAddOrEditServiceRecordDialog(null)
            }
            .setNegativeButton("Close", null)
            .create()
            .apply {
                setOnDismissListener {
                    if (activeServiceHistoryDialog === this) {
                        activeServiceHistoryDialog = null
                    }
                }
                show()
            }
    }

    private fun showAddOrEditServiceRecordDialog(existing: com.pulsarnav.app.maintenance.ServiceRecord?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val isEdit = existing != null

        // Title
        val tvTitleLabel = TextView(this).apply {
            text = "Service Title / Type:"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvTitleLabel)

        val etTitle = createThemedEditText(
            hintText = "e.g. 4th Paid Service, Synthetic Oil Flush",
            initialText = existing?.title ?: "Periodic Maintenance"
        )
        layout.addView(etTitle)

        // Date
        val tvDateLabel = TextView(this).apply {
            text = "\nService Date:"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvDateLabel)

        val today = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
        val etDate = createThemedEditText(
            hintText = "Select Service Date",
            initialText = existing?.date ?: today
        ).apply {
            isFocusable = false
            isClickable = true
            isCursorVisible = false
            setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_trips, 0)
            compoundDrawablePadding = dpToPx(8)
            setOnClickListener {
                provideHapticFeedback()
                val cal = Calendar.getInstance()
                try {
                    val parsed = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).parse(text.toString().trim())
                    if (parsed != null) cal.time = parsed
                } catch (_: Exception) {}

                val dpd = DatePickerDialog(
                    this@MainActivity,
                    { _, y, m, d ->
                        cal.set(y, m, d)
                        setText(SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(cal.time))
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                )
                dpd.show()
            }
        }
        layout.addView(etDate)

        // Odometer
        val currentOdo = maintenanceManager.status.value.totalOdometerKm
        val tvOdoLabel = TextView(this).apply {
            text = "\nOdometer Reading (km):"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvOdoLabel)

        val etOdo = createThemedEditText(
            hintText = "e.g. 14250.0",
            initialText = if (isEdit) formatDecimal(existing!!.odoKm) else formatDecimal(currentOdo),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        layout.addView(etOdo)

        // Total Bill Cost (Editable, auto-sums items)
        val tvCostLabel = TextView(this).apply {
            text = "\nTotal Bill Cost (%s):".format(appSettings.currentCurrencySymbol)
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvCostLabel)

        val etCost = createThemedEditText(
            hintText = "e.g. %s550".format(appSettings.currentCurrencySymbol),
            initialText = existing?.cost ?: "%s0".format(appSettings.currentCurrencySymbol)
        )
        layout.addView(etCost)

        // Repaired & Replaced Parts Itemized List (Custom Editable Rows)
        val rowHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(16), 0, dpToPx(6))
        }

        val tvPartsLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "PARTS & LABOUR (ITEMIZED):"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        rowHeader.addView(tvPartsLabel)

        val btnAddItem = MaterialButton(this).apply {
            text = "+ Add Item"
            textSize = 11f
            setTextColor(currentTheme.accentColor)
            strokeColor = ColorStateList.valueOf(currentTheme.borderColor)
            strokeWidth = dpToPx(1)
            backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
            cornerRadius = dpToPx(12)
            setPadding(dpToPx(10), 0, dpToPx(10), 0)
        }
        rowHeader.addView(btnAddItem)
        layout.addView(rowHeader)

        val itemsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(8))
        }
        layout.addView(itemsContainer)

        data class ServiceRowHolder(val rowView: View, val etName: EditText, val etItemCost: EditText)
        val rowHolders = mutableListOf<ServiceRowHolder>()

        var isCostManuallyEdited = false
        var isUpdatingCostFromItems = false
        val existingCostVal = MaintenanceMath.parseCurrencyAmount(existing?.cost)
        val existingItemsSum = existing?.items?.sumOf { it.cost } ?: 0.0
        if (existing != null && existingCostVal != null &&
            existingCostVal > 0.0 &&
            kotlin.math.abs(existingCostVal - existingItemsSum) >= 0.01
        ) {
            isCostManuallyEdited = true
        }
        etCost.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!isUpdatingCostFromItems) {
                    isCostManuallyEdited = true
                }
            }
        })

        fun recalculateTotalCost() {
            if (isCostManuallyEdited) return
            val sum = rowHolders.sumOf {
                parseDecimal(it.etItemCost.text) ?: 0.0
            }
            if (sum > 0.0 || (existing == null && rowHolders.isNotEmpty())) {
                isUpdatingCostFromItems = true
                try {
                    etCost.setText(
                        "%s%s".format(
                            appSettings.currentCurrencySymbol,
                            MaintenanceMath.formatCurrencyAmount(sum)
                        )
                    )
                } finally {
                    isUpdatingCostFromItems = false
                }
            }
        }

        fun addItemRow(name: String = "", itemCost: Double = 0.0) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dpToPx(4), 0, dpToPx(4))
            }

            val etName = createThemedEditText(
                hintText = "Item / Part / Labour",
                initialText = name,
                inputTypeFlags = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.8f).apply {
                    marginEnd = dpToPx(6)
                }
            }

            val etItemCost = createThemedEditText(
                hintText = "Cost (%s)".format(appSettings.currentCurrencySymbol),
                initialText = if (itemCost > 0.0) MaintenanceMath.formatCurrencyAmount(itemCost) else "",
                inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                    marginEnd = dpToPx(6)
                }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        recalculateTotalCost()
                    }
                })
            }

            val btnRemove = TextView(this).apply {
                text = "✕"
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_disconnected))
                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                isClickable = true
                isFocusable = true
            }

            val holder = ServiceRowHolder(row, etName, etItemCost)
            rowHolders.add(holder)

            btnRemove.setOnClickListener {
                itemsContainer.removeView(row)
                rowHolders.remove(holder)
                recalculateTotalCost()
            }

            row.addView(etName)
            row.addView(etItemCost)
            row.addView(btnRemove)
            itemsContainer.addView(row)
        }

        btnAddItem.setOnClickListener {
            provideHapticFeedback()
            addItemRow()
        }

        if (existing != null && existing.items.isNotEmpty()) {
            for (item in existing.items) {
                addItemRow(item.name, item.cost)
            }
        } else if (existing != null && existing.notes.isNotBlank()) {
            val parts = existing.notes.replace("Replaced:", "").replace("Notes:", "").split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.isNotEmpty()) {
                for (p in parts) {
                    addItemRow(p, 0.0)
                }
            } else {
                addItemRow()
            }
        } else {
            // New record: populate 2 starter rows
            addItemRow()
            addItemRow()
        }

        // Additional Custom Notes
        val tvNotesLabel = TextView(this).apply {
            text = "\nAdditional Remarks / Mechanic Notes:"
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvNotesLabel)

        val etNotes = createThemedEditText(
            hintText = "e.g. Done at local workshop, warranty details",
            initialText = existing?.notes.orEmpty()
        )
        layout.addView(etNotes)

        val scrollView: View = ScrollView(this).apply { addView(layout) }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (isEdit) "Edit Service Record" else "Add Service Record")
            .setView(scrollView)
            .setPositiveButton("Save Record") { _, _ ->
                val title = etTitle.text.toString().trim()
                val date = etDate.text.toString().trim()
                val odoVal = parseDecimal(etOdo.text) ?: currentOdo

                val parsedItems = rowHolders.mapNotNull {
                    val name = it.etName.text.toString().trim()
                    val c = parseDecimal(it.etItemCost.text) ?: 0.0
                    if (name.isNotBlank()) com.pulsarnav.app.maintenance.ServiceItem(name, c) else null
                }
                val costStr = etCost.text.toString().trim().ifBlank {
                    val sum = parsedItems.sumOf { it.cost }
                    "%s%s".format(
                        appSettings.currentCurrencySymbol,
                        MaintenanceMath.formatCurrencyAmount(sum)
                    )
                }
                val extraNotes = etNotes.text.toString().trim()
                val resolvedDate = date.ifBlank { today }
                val resolvedWorkshop = existing?.workshop?.takeIf { it.isNotBlank() } ?: "Self / Workshop"

                val rec = existing?.copy(
                    title = if (title.isNotBlank()) title else "Periodic Service",
                    date = resolvedDate,
                    odoKm = odoVal,
                    workshop = resolvedWorkshop,
                    cost = costStr,
                    notes = extraNotes,
                    items = parsedItems
                ) ?: com.pulsarnav.app.maintenance.ServiceRecord(
                    title = if (title.isNotBlank()) title else "Periodic Service",
                    date = resolvedDate,
                    odoKm = odoVal,
                    workshop = resolvedWorkshop,
                    cost = costStr,
                    notes = extraNotes,
                    items = parsedItems
                )

                if (isEdit) {
                    maintenanceManager.updateServiceRecord(rec)
                    showFeedback("Service record updated")
                } else {
                    maintenanceManager.addServiceRecord(rec)
                    showFeedback("Service record added")
                }

                recordSyncTime()
                updateServiceHistorySummary()
                updateBikeInvestmentDisplay()
                provideHapticFeedback()
                showServiceHistoryDialog()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                show()
            }
    }

    private fun showDocumentsDialog() = gloveboxController.show()

    private fun saveUploadedDocument(uri: Uri, docType: String?) = gloveboxController.saveUploadedDocument(uri, docType)


    private fun showRsaDialog() {
        val country = appSettings.getActiveCountryProfile()
        val entries = linkedMapOf<String, String>()
        if (country.roadsideAssistance.isNotBlank()) {
            entries[country.roadsideAssistance] = "Roadside Assistance (${country.roadsideAssistance})"
        }
        if (country.ambulanceNumber.isNotBlank()) {
            entries.putIfAbsent(country.ambulanceNumber, "Ambulance / Medical (${country.ambulanceNumber})")
        }
        if (country.policeNumber.isNotBlank()) {
            entries.putIfAbsent(country.policeNumber, "Police (${country.policeNumber})")
        }
        if (country.sosNumber.isNotBlank()) {
            entries.putIfAbsent(country.sosNumber, "${country.name} Emergency SOS (${country.sosNumber})")
        }
        val phones = entries.keys.toList()
        val options = entries.values.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Emergency Roadside Assistance (${country.name})")
            .setItems(options) { _, which ->
                provideHapticFeedback()
                val phone = phones.getOrNull(which) ?: country.sosNumber
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    showFeedback("Could not open dialer for $phone", isAlert = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualDialog() {
        val model = appSettings.getActiveBikeProfile()
        val country = appSettings.getActiveCountryProfile()
        val bikeTitle = VehicleCatalog.getLocalizedBikeName(model, country)

        MaterialAlertDialogBuilder(this)
            .setTitle("$bikeTitle Quick Handbook")
            .setMessage(
                "ENGINE & TRANSMISSION:\n" +
                "• ${model.engineSpec}\n\n" +
                "RECOMMENDED COLD TIRE PRESSURES:\n" +
                "• Front: ${model.coldTireFrontPsiSolo} PSI (Solo) • ${model.coldTireFrontPsiPillion} PSI (Pillion)\n" +
                "  (Size: ${model.tireFrontSize})\n" +
                "• Rear: ${model.coldTireRearPsiSolo} PSI (Solo) • ${model.coldTireRearPsiPillion} PSI (Pillion)\n" +
                "  (Size: ${model.tireRearSize})\n\n" +
                "FLUID SPECIFICATIONS:\n" +
                "• Engine Oil: ${model.engineOilSpec}\n" +
                "• Coolant: ${model.coolantSpec}\n" +
                "• Brake Fluid: DOT 4\n\n" +
                "DRIVE CHAIN & PLUGS:\n" +
                "• Free Slack: ${model.chainSlackMm}\n" +
                "• Spark Plugs: ${model.sparkPlugSpec}\n" +
                "• Fuel Tank: ${model.tankCapacityLiters} L (Reserve: ${model.reserveCapacityLiters} L)\n\n" +
                "LOCAL EMERGENCY HELPLINES (${country.name} ${country.labelTag}):\n" +
                "• Primary SOS: ${country.sosNumber}\n" +
                "• Ambulance: ${country.ambulanceNumber} • Police: ${country.policeNumber}\n" +
                "• Roadside Assistance: ${country.roadsideAssistance}"
            )
            .setPositiveButton("Got It", null)
            .setNeutralButton("Change Model") { _, _ ->
                provideHapticFeedback()
                showVehicleSetupBottomSheet()
            }
            .show()
    }

    private fun showSupportIssueDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("PulsarNav Support & Diagnostic Logs")
            .setMessage("All Turn-by-Turn GATT packets and Bluetooth logs are stored locally. Would you like to share a diagnostic log or report an issue on GitHub?")
            .setPositiveButton("Report on GitHub") { _, _ ->
                provideHapticFeedback()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Az-har/pulsarnav/issues")))
            }
            .setNegativeButton("Close", null)
            .show()
    }


    private fun setupFeatureToggles() {
        // Auto-Connect Switch
        binding.tabAccount.switchAutoConnect.isChecked = bleManager.isAutoConnectEnabled
        binding.tabAccount.switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            bleManager.isAutoConnectEnabled = isChecked
            if (isChecked) {
                checkAndStartScan(silent = true)
            }
        }
        binding.tabAccount.rowAutoConnect.setOnClickListener {
            provideHapticFeedback()
            binding.tabAccount.switchAutoConnect.toggle()
        }

        // Two-Wheeler Mode Switch
        binding.tabAccount.switchTwoWheeler.isChecked = appSettings.isTwoWheelerMode
        binding.tabAccount.switchTwoWheeler.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isTwoWheelerMode = isChecked
        }
        binding.tabAccount.rowTwoWheeler.setOnClickListener {
            provideHapticFeedback()
            binding.tabAccount.switchTwoWheeler.toggle()
        }

        // Auto Park Switch
        binding.tabAccount.switchAutoPark.isChecked = appSettings.isAutoParkEnabled
        binding.tabAccount.switchAutoPark.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isAutoParkEnabled = isChecked
        }
        binding.tabAccount.rowAutoPark.setOnClickListener {
            provideHapticFeedback()
            binding.tabAccount.switchAutoPark.toggle()
        }

        // Ride Tracking Switch
        binding.tabAccount.switchRideTracking.isChecked = appSettings.isRideTrackingEnabled
        binding.tabAccount.switchRideTracking.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isRideTrackingEnabled = isChecked
        }
        binding.tabAccount.rowRideTracking.setOnClickListener {
            provideHapticFeedback()
            binding.tabAccount.switchRideTracking.toggle()
        }

        // Media Control Switch
        binding.tabAccount.switchMediaControl.isChecked = appSettings.isMediaControlEnabled
        binding.tabAccount.switchMediaControl.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isMediaControlEnabled = isChecked
        }
        binding.tabAccount.rowMediaControl.setOnClickListener {
            provideHapticFeedback()
            binding.tabAccount.switchMediaControl.toggle()
        }

        // WhatsApp Alerts Switch
        binding.tabAccount.switchWhatsAppAlerts.isChecked = appSettings.isWhatsAppAlertsEnabled
        binding.tabAccount.switchWhatsAppAlerts.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isWhatsAppAlertsEnabled = isChecked
        }
        binding.tabAccount.rowWhatsAppAlerts.setOnClickListener {
            provideHapticFeedback()
            binding.tabAccount.switchWhatsAppAlerts.toggle()
        }

        // Telemetry Switch
        binding.tabAccount.switchTelemetry.isChecked = appSettings.isTelemetrySyncEnabled
        binding.tabAccount.switchTelemetry.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isTelemetrySyncEnabled = isChecked
        }
        binding.tabAccount.rowTelemetry.setOnClickListener {
            provideHapticFeedback()
            binding.tabAccount.switchTelemetry.toggle()
        }

        // Headunit Cluster Skip Switch
        binding.tabAccount.switchSkipHeadunitNav.isChecked = appSettings.isSkipClusterNavWhenHeadunit
        binding.tabAccount.switchSkipHeadunitNav.setOnCheckedChangeListener { _, isChecked ->
            provideHapticFeedback()
            appSettings.isSkipClusterNavWhenHeadunit = isChecked
            showFeedback(
                if (isChecked) "Cluster Nav quiet mode: active (Headunit only)"
                else "Cluster Nav mirror mode: active (Cluster + Headunit)"
            )
        }
        binding.tabAccount.rowSkipHeadunitNav.setOnClickListener {
            provideHapticFeedback()
            binding.tabAccount.switchSkipHeadunitNav.toggle()
        }

        // Reject-with-SMS Auto-Reply Switch & Template
        updateSmsRejectUi()
        binding.tabAccount.switchSmsReject.setOnCheckedChangeListener { _, isChecked ->
            appSettings.isSmsRejectEnabled = isChecked
            updateSmsRejectUi()
            if (isChecked && ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                showFeedback("Grant SEND_SMS permission in Settings to auto-reply from switchgear", isAlert = true)
            }
        }
        binding.tabAccount.rowSmsReject.setOnClickListener {
            provideHapticFeedback()
            binding.tabAccount.switchSmsReject.toggle()
        }
        binding.tabAccount.btnEditSmsRejectMsg.setOnClickListener {
            provideHapticFeedback()
            showEditSmsRejectTemplateDialog()
        }

        // Cluster Phonebook, Call Log, Dialback & SMS Permissions Row
        binding.tabAccount.rowTelephonyPermissions.setOnClickListener {
            provideHapticFeedback()
            requestTelephonyAndClusterPermissions()
        }
    }

    private fun getThemeDefinition(themeKey: String, isLightMode: Boolean? = null): CockpitTheme {
        val isLight = isLightMode ?: if (::appSettings.isInitialized) !appSettings.isDarkMode else false
        val r = if (::appSettings.isInitialized) appSettings.customAccentRed else 158
        val g = if (::appSettings.isInitialized) appSettings.customAccentGreen else 27
        val b = if (::appSettings.isInitialized) appSettings.customAccentBlue else 50
        return CockpitThemePalette.getThemeDefinition(themeKey, isLight, r, g, b)
    }

    private fun setupThemeSelector() {
        binding.tabAccount.btnModeDark.setOnClickListener {
            provideHapticFeedback()
            if (!appSettings.isDarkMode) {
                appSettings.isDarkMode = true
                appSettings.activeTabKey = AppSettings.TAB_ACCOUNT
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                applyTheme(appSettings.appTheme)
                showFeedback("Dark mode active")
            }
        }
        binding.tabAccount.btnModeLight.setOnClickListener {
            provideHapticFeedback()
            if (appSettings.isDarkMode) {
                appSettings.isDarkMode = false
                appSettings.activeTabKey = AppSettings.TAB_ACCOUNT
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
            } else {
                applyTheme(appSettings.appTheme)
                showFeedback("Light mode active")
            }
        }

        binding.tabAccount.cardThemeWineRed.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_WINE_RED)
            showFeedback("Wine Red theme active")
        }
        binding.tabAccount.cardThemeAmoled.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_AMOLED)
            showFeedback(if (appSettings.isDarkMode) "AMOLED Pure Black active" else "Pearl Minimal active")
        }
        binding.tabAccount.cardThemeStealth.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_STEALTH)
            showFeedback("Stealth Mono theme active")
        }
        binding.tabAccount.cardThemeAmber.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_AMBER)
            showFeedback("Neon Amber theme active")
        }
        binding.tabAccount.cardThemeMint.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_MINT)
            showFeedback("Hyper Mint theme active")
        }
        binding.tabAccount.cardThemeBlue.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_BLUE)
            showFeedback("Liquid Blue theme active")
        }
        binding.tabAccount.cardThemeCustom.setOnClickListener {
            provideHapticFeedback()
            applyTheme(AppSettings.THEME_CUSTOM)
            showFeedback("Custom RGB theme active")
        }

        // Initialize SeekBars and Custom RGB values
        binding.tabAccount.seekRed.progress = appSettings.customAccentRed
        binding.tabAccount.seekGreen.progress = appSettings.customAccentGreen
        binding.tabAccount.seekBlue.progress = appSettings.customAccentBlue
        binding.tabAccount.tvRedValue.text = appSettings.customAccentRed.toString()
        binding.tabAccount.tvGreenValue.text = appSettings.customAccentGreen.toString()
        binding.tabAccount.tvBlueValue.text = appSettings.customAccentBlue.toString()
        val initialHex = String.format(Locale.US, "#%02X%02X%02X", appSettings.customAccentRed, appSettings.customAccentGreen, appSettings.customAccentBlue)
        isUpdatingHexText = true
        try {
            binding.tabAccount.etCustomHex.setText(initialHex)
        } finally {
            isUpdatingHexText = false
        }
        val initialCustomColor = appSettings.getCustomAccentColor()
        binding.tabAccount.viewRgbLivePreview.backgroundTintList = ColorStateList.valueOf(initialCustomColor)
        binding.tabAccount.viewCustomThemeDot.backgroundTintList = ColorStateList.valueOf(initialCustomColor)

        val commitCustomAccentTheme = { r: Int, g: Int, b: Int ->
            appSettings.setCustomAccentRgb(r, g, b)
            applyTheme(AppSettings.THEME_CUSTOM)
        }

        val onSeekChanged = { fromUser: Boolean, immediateApply: Boolean ->
            val r = binding.tabAccount.seekRed.progress
            val g = binding.tabAccount.seekGreen.progress
            val b = binding.tabAccount.seekBlue.progress
            binding.tabAccount.tvRedValue.text = r.toString()
            binding.tabAccount.tvGreenValue.text = g.toString()
            binding.tabAccount.tvBlueValue.text = b.toString()
            val hex = String.format(Locale.US, "#%02X%02X%02X", r, g, b)
            if (!binding.tabAccount.etCustomHex.isFocused && binding.tabAccount.etCustomHex.text.toString() != hex) {
                isUpdatingHexText = true
                try {
                    binding.tabAccount.etCustomHex.setText(hex)
                } finally {
                    isUpdatingHexText = false
                }
            }
            val color = Color.rgb(r, g, b)
            binding.tabAccount.viewRgbLivePreview.backgroundTintList = ColorStateList.valueOf(color)
            binding.tabAccount.viewCustomThemeDot.backgroundTintList = ColorStateList.valueOf(color)

            if (fromUser || appSettings.appTheme == AppSettings.THEME_CUSTOM) {
                pendingSeekThemeRunnable?.let { mainUiHandler.removeCallbacks(it) }
                if (immediateApply) {
                    pendingSeekThemeRunnable = null
                    commitCustomAccentTheme(r, g, b)
                } else {
                    val runnable = Runnable {
                        pendingSeekThemeRunnable = null
                        commitCustomAccentTheme(binding.tabAccount.seekRed.progress, binding.tabAccount.seekGreen.progress, binding.tabAccount.seekBlue.progress)
                    }
                    pendingSeekThemeRunnable = runnable
                    mainUiHandler.postDelayed(runnable, 90L)
                }
            }
        }

        val seekListener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onSeekChanged(true, false)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                provideHapticFeedback()
                onSeekChanged(true, true)
            }
        }

        binding.tabAccount.seekRed.setOnSeekBarChangeListener(seekListener)
        binding.tabAccount.seekGreen.setOnSeekBarChangeListener(seekListener)
        binding.tabAccount.seekBlue.setOnSeekBarChangeListener(seekListener)

        // Palette presets
        val setPreset = { r: Int, g: Int, b: Int, name: String ->
            provideHapticFeedback()
            binding.tabAccount.seekRed.progress = r
            binding.tabAccount.seekGreen.progress = g
            binding.tabAccount.seekBlue.progress = b
            val hex = String.format(Locale.US, "#%02X%02X%02X", r, g, b)
            isUpdatingHexText = true
            try {
                binding.tabAccount.etCustomHex.setText(hex)
            } finally {
                isUpdatingHexText = false
            }
            onSeekChanged(true, true)
            showFeedback("$name applied")
        }

        binding.tabAccount.chipPresetPurple.setOnClickListener { setPreset(130, 143, 255, "Linear Purple") }
        binding.tabAccount.chipPresetStrava.setOnClickListener { setPreset(252, 76, 2, "Strava Orange") }
        binding.tabAccount.chipPresetCyan.setOnClickListener { setPreset(6, 182, 212, "Cyber Cyan") }
        binding.tabAccount.chipPresetPink.setOnClickListener { setPreset(236, 72, 153, "Neon Pink") }
        binding.tabAccount.chipPresetLime.setOnClickListener { setPreset(132, 204, 22, "Kawasaki Lime") }

        // Manual Hex Input Handling
        val parseAndApplyHex = { showFeedbackMessage: Boolean ->
            val raw = binding.tabAccount.etCustomHex.text.toString().trim()
            var clean = raw.removePrefix("#").trim()
            if (clean.length == 3) {
                clean = "${clean[0]}${clean[0]}${clean[1]}${clean[1]}${clean[2]}${clean[2]}"
            }
            if (clean.length == 6) {
                try {
                    val color = Color.parseColor("#$clean")
                    val r = Color.red(color)
                    val g = Color.green(color)
                    val b = Color.blue(color)
                    binding.tabAccount.seekRed.progress = r
                    binding.tabAccount.seekGreen.progress = g
                    binding.tabAccount.seekBlue.progress = b
                    binding.tabAccount.tvRedValue.text = r.toString()
                    binding.tabAccount.tvGreenValue.text = g.toString()
                    binding.tabAccount.tvBlueValue.text = b.toString()
                    val canonicalHex = String.format(Locale.US, "#%02X%02X%02X", r, g, b)
                    if (binding.tabAccount.etCustomHex.text.toString() != canonicalHex) {
                        isUpdatingHexText = true
                        try {
                            binding.tabAccount.etCustomHex.setText(canonicalHex)
                            binding.tabAccount.etCustomHex.setSelection(canonicalHex.length)
                        } finally {
                            isUpdatingHexText = false
                        }
                    }
                    binding.tabAccount.viewRgbLivePreview.backgroundTintList = ColorStateList.valueOf(color)
                    binding.tabAccount.viewCustomThemeDot.backgroundTintList = ColorStateList.valueOf(color)

                    pendingSeekThemeRunnable?.let { mainUiHandler.removeCallbacks(it) }
                    pendingSeekThemeRunnable = null
                    appSettings.setCustomAccentRgb(r, g, b)

                    applyTheme(AppSettings.THEME_CUSTOM)
                    if (showFeedbackMessage) {
                        showFeedback("Hex accent $canonicalHex active")
                    }
                    true
                } catch (_: Exception) {
                    if (showFeedbackMessage) showFeedback("Invalid hex color")
                    false
                }
            } else {
                if (showFeedbackMessage) showFeedback("Enter a 6-digit hex code (#RRGGBB)")
                false
            }
        }

        binding.tabAccount.etCustomHex.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isUpdatingHexText) return
                if (binding.tabAccount.etCustomHex.isFocused) {
                    val str = s?.toString()?.trim() ?: ""
                    val clean = str.removePrefix("#").trim()
                    if (clean.length == 6) {
                        parseAndApplyHex(false)
                    }
                }
            }
        })

        binding.tabAccount.etCustomHex.setOnEditorActionListener { v, _, _ ->
            provideHapticFeedback()
            val ok = parseAndApplyHex(true)
            if (ok) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
            }
            ok
        }

        binding.tabAccount.btnApplyHex.setOnClickListener {
            provideHapticFeedback()
            val ok = parseAndApplyHex(true)
            if (ok) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(binding.tabAccount.etCustomHex.windowToken, 0)
                binding.tabAccount.etCustomHex.clearFocus()
            }
        }
    }

    private fun applyTheme(themeKey: String) {
        val theme = getThemeDefinition(themeKey)
        currentTheme = theme
        appSettings.appTheme = themeKey

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = theme.isLight
            isAppearanceLightNavigationBars = theme.isLight
        }
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }

        CockpitThemeApplier.apply(
            context = this,
            binding = binding,
            theme = theme,
            themeKey = themeKey,
            appSettings = appSettings,
            isBleConnected = bleManager.connectionState.value is BleConnectionState.Connected,
            setCustomHexSafely = { hex ->
                isUpdatingHexText = true
                try {
                    binding.tabAccount.etCustomHex.setText(hex)
                } finally {
                    isUpdatingHexText = false
                }
            },
            onUpdateDebugFilterChips = { tftLabsController.updateDebugFilterChipsUi() }
        )
        tftLabsController.updateDebugFilterChipsUi()
        updateSimulationUiState(bleManager.isSimulationRunning.value, bleManager.currentActiveManeuver.value)

        if (::discoveredBikeAdapter.isInitialized) {
            discoveredBikeAdapter.updateTheme(theme)
        }
        if (::fuelListAdapter.isInitialized) {
            fuelListAdapter.updateStyle(theme, appSettings.currentCurrencySymbol)
        }
        if (binding.tabFuel.root.visibility == View.VISIBLE) {
            fuelListThemeDirty = false
            try {
                updateFuelList(fuelLogManager.entries.value)
            } catch (_: Exception) {}
        } else {
            fuelListThemeDirty = true
        }

        updateNavAppSelectionUi()
        updateHeroRangeDisplay()
        updateBikeIssuesSummary()
    }

    private fun applyThemeToEditText(editText: EditText) =
        CockpitThemeApplier.applyThemeToEditText(this, editText, currentTheme)

    private fun createThemedEditText(
        hintText: String? = null,
        initialText: String? = null,
        inputTypeFlags: Int? = null
    ): EditText {
        return EditText(this).apply {
            if (!hintText.isNullOrEmpty()) hint = hintText
            if (!initialText.isNullOrEmpty()) setText(initialText)
            if (inputTypeFlags != null) inputType = inputTypeFlags
            applyThemeToEditText(this)
        }
    }

    private fun dpToPx(dp: Int): Int = CockpitThemeApplier.dpToPx(this, dp)


    private fun setupQuickNav() {
        updateQuickNavButtons()

        binding.tabCockpit.cardQuickHome.setOnClickListener {
            provideHapticFeedback()
            val isWaze = appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE
            val addr = appSettings.homeAddress
            if (isWaze && (addr.isBlank() || addr.equals("Home", ignoreCase = true))) {
                try {
                    val uri = Uri.parse("waze://?favorite=home&navigate=yes")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.waze")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    launchNavigationTo("Home")
                }
            } else {
                launchNavigationTo(addr.ifBlank { "Home" })
            }
        }
        binding.tabCockpit.cardQuickHome.setOnLongClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Home", 0)
            true
        }

        binding.tabCockpit.cardQuickWork.setOnClickListener {
            provideHapticFeedback()
            val isWaze = appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE
            val addr = appSettings.workAddress
            if (isWaze && (addr.isBlank() || addr.equals("Work", ignoreCase = true))) {
                try {
                    val uri = Uri.parse("waze://?favorite=work&navigate=yes")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.waze")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    launchNavigationTo("Work")
                }
            } else {
                launchNavigationTo(addr.ifBlank { "Work" })
            }
        }
        binding.tabCockpit.cardQuickWork.setOnLongClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Work", 1)
            true
        }

        binding.tabAccount.btnConfigHome.setOnClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Home", 0)
        }

        binding.tabAccount.btnConfigWork.setOnClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Work", 1)
        }

        binding.tabAccount.btnConfigFavPlace.setOnClickListener {
            provideHapticFeedback()
            showSetAddressDialog("Favorite", 2)
        }
    }

    private fun updateNavAppSelectionUi() {
        val isWaze = appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE
        if (isWaze) {
            binding.tabAccount.tvNavAppCurrentStatus.text = "Currently: Waze (Turn-by-turn routing active)"
            binding.tabAccount.btnNavChooseWaze.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
            binding.tabAccount.btnNavChooseWaze.setTextColor(currentTheme.accentColor)
            binding.tabAccount.btnNavChooseWaze.strokeColor = ColorStateList.valueOf(currentTheme.borderColor)

            binding.tabAccount.btnNavChooseGmaps.backgroundTintList = ColorStateList.valueOf(currentTheme.cardColor)
            binding.tabAccount.btnNavChooseGmaps.setTextColor(currentTheme.textSecondaryColor)
            binding.tabAccount.btnNavChooseGmaps.strokeColor = ColorStateList.valueOf(currentTheme.cardBorderColor)
        } else {
            binding.tabAccount.tvNavAppCurrentStatus.text = "Currently: Google Maps (Two-Wheeler routing active)"
            binding.tabAccount.btnNavChooseGmaps.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
            binding.tabAccount.btnNavChooseGmaps.setTextColor(currentTheme.accentColor)
            binding.tabAccount.btnNavChooseGmaps.strokeColor = ColorStateList.valueOf(currentTheme.borderColor)

            binding.tabAccount.btnNavChooseWaze.backgroundTintList = ColorStateList.valueOf(currentTheme.cardColor)
            binding.tabAccount.btnNavChooseWaze.setTextColor(currentTheme.textSecondaryColor)
            binding.tabAccount.btnNavChooseWaze.strokeColor = ColorStateList.valueOf(currentTheme.cardBorderColor)
        }
    }

    private fun updateQuickNavButtons() {
        val home = appSettings.homeAddress
        val work = appSettings.workAddress
        val isWaze = appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE
        val defaultHomeLabel = if (isWaze) "Waze Saved Home" else "Google Maps Saved Home"
        val defaultWorkLabel = if (isWaze) "Waze Saved Work" else "Google Maps Saved Work"

        binding.tabCockpit.tvQuickHomeTitle.text = "Home"
        binding.tabCockpit.tvQuickWorkTitle.text = "Work"

        val displayHome = if (home.isNotBlank() && !home.equals("Home", ignoreCase = true)) home else defaultHomeLabel
        val displayWork = if (work.isNotBlank() && !work.equals("Work", ignoreCase = true)) work else defaultWorkLabel
        val fav = appSettings.favPlaceAddress.trim()
        val displayFav = fav.ifBlank { "Not configured (optional 3rd cluster favorite)" }
        binding.tabAccount.tvSettingsHome.text = "Home: $displayHome"
        binding.tabAccount.tvSettingsWork.text = "Work: $displayWork"
        binding.tabAccount.tvSettingsFavPlace.text = "Favorite: $displayFav"
    }

    private fun provideHapticFeedback() {
        try {
            val performed = window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!performed) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                    vibratorManager?.defaultVibrator?.vibrate(
                        android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        } catch (_: Exception) {}
    }

    private fun showFeedback(
        message: String,
        actionText: String? = null,
        isAlert: Boolean = false,
        onAction: (() -> Unit)? = null
    ) {
        try {
            val snackbar = Snackbar.make(
                binding.root,
                message,
                if (actionText != null) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
            )
            snackbar.anchorView = binding.bottomNav
            snackbar.setBackgroundTint(
                if (isAlert) currentTheme.cardColor
                else currentTheme.surfaceColor
            )
            snackbar.setTextColor(
                if (isAlert) ContextCompat.getColor(this, R.color.status_disconnected)
                else currentTheme.textPrimaryColor
            )
            if (actionText != null && onAction != null) {
                snackbar.setActionTextColor(currentTheme.accentColor)
                snackbar.setAction(actionText) {
                    provideHapticFeedback()
                    onAction()
                }
            }
            snackbar.show()
        } catch (_: Exception) {}
    }

    private fun dialEmergencyNumber(number: String) {
        val cleanNumber = number.filter { it.isDigit() || it == '+' }
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber"))
            startActivity(intent)
        } catch (e: Exception) {
            showFeedback("Could not open dialer for $cleanNumber", isAlert = true)
        }
    }

    private fun showEmergencyQuickActionsSheet() {
        val dialog = BottomSheetDialog(this, R.style.ObsidianBottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_emergency, null)
        dialog.setContentView(sheetView)

        val cardSos108 = sheetView.findViewById<MaterialCardView>(R.id.cardSos108)
        val cardSos112 = sheetView.findViewById<MaterialCardView>(R.id.cardSos112)
        val cardSosIce = sheetView.findViewById<MaterialCardView>(R.id.cardSosIce)
        val cardFindHospitals = sheetView.findViewById<MaterialCardView>(R.id.cardFindHospitals)
        val cardMedicalProfile = sheetView.findViewById<MaterialCardView>(R.id.cardMedicalProfile)
        val tvSheetIceSubtitle = sheetView.findViewById<TextView>(R.id.tvSheetIceSubtitle)
        val tvSheetBloodGroup = sheetView.findViewById<TextView>(R.id.tvSheetBloodGroup)
        val tvSheetMedicalNotes = sheetView.findViewById<TextView>(R.id.tvSheetMedicalNotes)
        val btnEditMedicalProfile = sheetView.findViewById<TextView>(R.id.btnEditMedicalProfile)

        listOf(cardSos108, cardSos112, cardSosIce, cardFindHospitals, cardMedicalProfile).forEach { card ->
            card?.setCardBackgroundColor(currentTheme.cardColor)
            card?.strokeColor = currentTheme.cardBorderColor
        }

        val icePhone = appSettings.emergencyContactPhone
        val iceName = appSettings.emergencyContactName
        if (icePhone.isNotBlank() && icePhone != "112") {
            tvSheetIceSubtitle?.text = "$iceName • $icePhone"
        } else {
            tvSheetIceSubtitle?.text = "Tap to configure ICE emergency contact"
        }

        val blood = if (appSettings.riderBloodGroup.isNotBlank() && appSettings.riderBloodGroup != "Not Specified") "Blood Group: ${appSettings.riderBloodGroup}" else "Blood Group --"
        tvSheetBloodGroup?.text = blood
        tvSheetBloodGroup?.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
        tvSheetBloodGroup?.setTextColor(currentTheme.accentColor)

        btnEditMedicalProfile?.setTextColor(currentTheme.accentColor)

        val med = appSettings.medicalNotes
        tvSheetMedicalNotes?.text = if (med.isNotBlank() && med != "No known allergies") med else "No allergies or medical notes recorded. Tap Edit to update."

        val country = appSettings.getActiveCountryProfile()
        val tvAmbTitle = sheetView.findViewById<TextView>(R.id.tvSosAmbulanceTitle)
        val tvAmbSub = sheetView.findViewById<TextView>(R.id.tvSosAmbulanceSubtitle)
        val tvNatTitle = sheetView.findViewById<TextView>(R.id.tvSosNationalTitle)
        val tvNatSub = sheetView.findViewById<TextView>(R.id.tvSosNationalSubtitle)

        tvAmbTitle?.text = "${country.ambulanceNumber} — Ambulance / Medical"
        tvAmbSub?.text = "First Responders (${country.name} ${country.labelTag})"

        tvNatTitle?.text = "${country.sosNumber} — National Emergency SOS"
        tvNatSub?.text = "Police & Emergency Response (${country.name})"

        cardSos108?.setOnClickListener {
            provideHapticFeedback()
            dialEmergencyNumber(country.ambulanceNumber)
        }

        cardSos112?.setOnClickListener {
            provideHapticFeedback()
            dialEmergencyNumber(country.sosNumber)
        }

        cardSosIce?.setOnClickListener {
            provideHapticFeedback()
            if (icePhone.isNotBlank() && icePhone != "112") {
                dialEmergencyNumber(icePhone)
            } else {
                dialog.dismiss()
                showFeedback("Please configure your ICE contact details")
                showEmergencyMedicalDialog()
            }
        }

        cardFindHospitals?.setOnClickListener {
            provideHapticFeedback()
            launchEmergencyHospitals()
        }

        btnEditMedicalProfile?.setOnClickListener {
            provideHapticFeedback()
            dialog.dismiss()
            showEmergencyMedicalDialog()
        }

        cardMedicalProfile?.setOnClickListener {
            provideHapticFeedback()
            dialog.dismiss()
            showEmergencyMedicalDialog()
        }

        dialog.show()
    }

    private fun showDataVaultBottomSheet(openQrDirectly: Boolean = false) =
        dataVaultController.show(openQrDirectly)

    private fun showExternalVaultImportConfirmation(uri: Uri) =
        dataVaultController.showExternalImportConfirmation(uri)

    private fun importVaultFromUri(uri: Uri) =
        dataVaultController.importFromUri(uri)


    private fun refreshAllUiAfterVaultImport() {
        binding.tabAccount.switchAutoConnect.isChecked = appSettings.isAutoConnectEnabled
        binding.tabAccount.switchTwoWheeler.isChecked = appSettings.isTwoWheelerMode
        binding.tabAccount.switchAutoPark.isChecked = appSettings.isAutoParkEnabled
        binding.tabAccount.switchRideTracking.isChecked = appSettings.isRideTrackingEnabled
        binding.tabAccount.switchMediaControl.isChecked = appSettings.isMediaControlEnabled
        binding.tabAccount.switchWhatsAppAlerts.isChecked = appSettings.isWhatsAppAlertsEnabled
        binding.tabAccount.switchTelemetry.isChecked = appSettings.isTelemetrySyncEnabled
        binding.tabAccount.switchSkipHeadunitNav.isChecked = appSettings.isSkipClusterNavWhenHeadunit
        binding.tabAccount.switchSmsReject.isChecked = appSettings.isSmsRejectEnabled
        updateFuelPriceUi()
        updateBikeInfoDisplay()
        updateDocumentsSummary()
        updateServiceHistorySummary()
        updateNavAppSelectionUi()
        updateHeroRangeDisplay()
        updateBikeIssuesSummary()
        updateEmergencyHomeCard()
        updateQuickNavButtons()
        updateTelephonyPermissionsUi()
        updateSmsRejectUi()
        applyTheme(appSettings.appTheme)
    }

    private fun launchNavigationTo(destination: String) {
        if (!isNotificationAccessGranted()) {
            showNotificationAccessDialog { proceedWithNavigationLaunch(destination) }
            return
        }
        proceedWithNavigationLaunch(destination)
    }

    private fun proceedWithNavigationLaunch(destination: String) {
        if (!isLocationEnabled()) {
            showFeedback("GPS is OFF — launching navigation anyway; turn ON Location for live tracking", isAlert = true)
        }
        if (!NavigationIntentLauncher.launchNavigation(this, destination)) {
            showFeedback("Could not open navigation app", isAlert = true)
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    private fun openNotificationListenerSettings() {
        provideHapticFeedback()
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            showFeedback("Unable to open Notification Settings: ${e.message}", isAlert = true)
        }
    }

    private fun updateNotificationAccessUi() {
        val granted = isNotificationAccessGranted()
        val diag = GoogleMapsNotificationListener.diagnostics.value
        binding.tabCockpit.cardNotificationPermissionBanner.visibility = if (granted) View.GONE else View.VISIBLE
        binding.tabAccount.tvNotificationAccessStatus.text = when {
            !granted -> "Action Required (Tap to Enable)"
            diag.mapsActiveInShade -> "Active • Live Maps notification detected"
            else -> "Listener ON • Tap for Maps channel & TBT fix"
        }
        binding.tabAccount.tvNotificationAccessStatus.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.mint_accent else R.color.amber_accent)
        )
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isIgnoringBatteryOptimizations(packageName) ?: true
    }

    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    showFeedback("Set Battery to 'Unrestricted' in App Info -> Battery", isAlert = true)
                }
            }
        } else {
            showFeedback("Background execution is already unrestricted")
        }
    }

    private fun updateBatteryOptimizationUi() {
        val isUnrestricted = isBatteryOptimizationIgnored()
        binding.tabAccount.tvBatteryOptimizationStatus.text = if (isUnrestricted) {
            "Unrestricted (Protected in background)"
        } else {
            "Optimized (Tap to allow unrestricted background)"
        }
        binding.tabAccount.tvBatteryOptimizationStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (isUnrestricted) R.color.mint_accent else R.color.amber_accent
            )
        )
    }

    private fun showNotificationAccessDialog(onContinueAnyway: (() -> Unit)? = null) {
        MaterialAlertDialogBuilder(this, R.style.ObsidianAlertDialog)
            .setTitle("Navigation Interceptor Access")
            .setMessage("Android requires Notification Access for PulsarNav so it can intercept turn-by-turn navigation arrows from Google Maps, Waze, and Android Auto and stream them directly to your Pulsar motorcycle cluster.\n\nPlease enable PulsarNav on the next screen.")
            .setPositiveButton("Open Settings") { _, _ ->
                openNotificationListenerSettings()
            }
            .setNegativeButton("Continue Anyway") { _, _ ->
                onContinueAnyway?.invoke()
            }
            .show()
    }

    private fun showSetAddressDialog(label: String, slot: Int) {
        val sheet = BottomSheetDialog(this, R.style.ObsidianBottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_set_destination, null)
        sheet.setContentView(sheetView)

        val ivIcon = sheetView.findViewById<ImageView>(R.id.ivSheetIcon)
        val tvTitle = sheetView.findViewById<TextView>(R.id.tvSheetTitle)
        val tvSubtitle = sheetView.findViewById<TextView>(R.id.tvSheetSubtitle)
        tvSubtitle?.text = "1-tap shortcut for ${VehicleCatalog.getLocalizedBikeName(appSettings.getActiveBikeProfile(), appSettings.getActiveCountryProfile())}"
        val tvOptionMapsTitle = sheetView.findViewById<TextView>(R.id.tvOptionMapsTitle)
        val cardOptionMapsSaved = sheetView.findViewById<MaterialCardView>(R.id.cardOptionMapsSaved)
        val cardOptionPinGps = sheetView.findViewById<MaterialCardView>(R.id.cardOptionPinGps)
        val cardOptionCustom = sheetView.findViewById<MaterialCardView>(R.id.cardOptionCustom)
        val etCustom = sheetView.findViewById<EditText>(R.id.etCustomAddress)
        etCustom?.let { applyThemeToEditText(it) }
        val btnSaveCustom = sheetView.findViewById<MaterialButton>(R.id.btnSaveCustomAddress)
        val btnDismiss = sheetView.findViewById<MaterialButton>(R.id.btnDismissSheet)

        ivIcon?.setImageResource(if (slot == 0) R.drawable.ic_home else R.drawable.ic_work)
        tvTitle.text = "Set $label Destination"
        val isWaze = appSettings.preferredNavApp == AppSettings.NAV_APP_WAZE
        tvOptionMapsTitle.text = if (isWaze) "Waze Saved $label" else "Google Maps Saved $label"

        val current = when (slot) {
            0 -> appSettings.homeAddress
            1 -> appSettings.workAddress
            else -> appSettings.favPlaceAddress
        }
        if (current.isNotBlank() && current != "Home" && current != "Work") {
            etCustom.setText(current)
        }

        btnSaveCustom.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
        btnSaveCustom.setTextColor(currentTheme.onPrimaryColor)
        ivIcon?.imageTintList = ColorStateList.valueOf(currentTheme.accentColor)
        listOf(cardOptionMapsSaved, cardOptionPinGps, cardOptionCustom).forEach { card ->
            card.setCardBackgroundColor(currentTheme.cardColor)
            card.strokeColor = currentTheme.cardBorderColor
        }

        cardOptionMapsSaved.setOnClickListener {
            provideHapticFeedback()
            when (slot) {
                0 -> {
                    appSettings.homeAddress = "Home"
                    appSettings.homeLat = 0.0
                    appSettings.homeLng = 0.0
                }
                1 -> {
                    appSettings.workAddress = "Work"
                    appSettings.workLat = 0.0
                    appSettings.workLng = 0.0
                }
                else -> {
                    appSettings.favPlaceAddress = ""
                    appSettings.favPlaceLat = 0.0
                    appSettings.favPlaceLng = 0.0
                }
            }
            updateQuickNavButtons()
            showFeedback(if (slot == 2) "Cleared Favorite spot" else "Linked to Google Maps $label")
            sheet.dismiss()
        }

        cardOptionPinGps.setOnClickListener {
            provideHapticFeedback()
            pinCurrentLocationAs(label, slot)
            sheet.dismiss()
        }

        btnSaveCustom.setOnClickListener {
            provideHapticFeedback()
            val text = etCustom.text.toString().trim()
            if (text.isNotBlank()) {
                when (slot) {
                    0 -> {
                        appSettings.homeAddress = text
                        appSettings.homeLat = 0.0
                        appSettings.homeLng = 0.0
                    }
                    1 -> {
                        appSettings.workAddress = text
                        appSettings.workLat = 0.0
                        appSettings.workLng = 0.0
                    }
                    else -> {
                        appSettings.favPlaceAddress = text
                        appSettings.favPlaceLat = 0.0
                        appSettings.favPlaceLng = 0.0
                    }
                }
                updateQuickNavButtons()
                showFeedback("$label address saved")
                sheet.dismiss()
            } else {
                showFeedback("Please enter an address or landmark", isAlert = true)
            }
        }

        btnDismiss.setOnClickListener {
            sheet.dismiss()
        }

        sheet.show()
    }

    @SuppressLint("MissingPermission")
    private fun pinCurrentLocationAs(label: String, slot: Int) {
        val hasLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLoc) {
            showFeedback("Grant Location permission to pin current GPS", isAlert = true)
            return
        }
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
            val addr = "%.5f,%.5f".format(java.util.Locale.US, lat, lng)
            when (slot) {
                0 -> {
                    appSettings.homeAddress = addr
                    appSettings.homeLat = lat
                    appSettings.homeLng = lng
                }
                1 -> {
                    appSettings.workAddress = addr
                    appSettings.workLat = lat
                    appSettings.workLng = lng
                }
                else -> {
                    appSettings.favPlaceAddress = addr
                    appSettings.favPlaceLat = lat
                    appSettings.favPlaceLng = lng
                }
            }
            updateQuickNavButtons()
            showFeedback("Pinned GPS as $label")
        } else {
            showFeedback("Unable to get current GPS location", isAlert = true)
        }
    }

    private fun showFuelDatePicker(currentMs: Long, onDatePicked: (Long) -> Unit) {
        val origCal = Calendar.getInstance().apply { timeInMillis = currentMs }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    set(Calendar.HOUR_OF_DAY, origCal.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, origCal.get(Calendar.MINUTE))
                    set(Calendar.SECOND, origCal.get(Calendar.SECOND))
                    set(Calendar.MILLISECOND, 0)
                }
                val nowMs = System.currentTimeMillis()
                onDatePicked(picked.timeInMillis.coerceAtMost(nowMs))
            },
            origCal.get(Calendar.YEAR),
            origCal.get(Calendar.MONTH),
            origCal.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
            show()
        }
    }

    private fun formatFuelDialogDate(timestampMs: Long): String =
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timestampMs))

    private fun showAddFuelDialog() {
        val currentOdo = maintenanceManager.status.value.totalOdometerKm
        val petrolRate = appSettings.petrolPricePerLiter
        val currSymbol = appSettings.currentCurrencySymbol
        val model = appSettings.getActiveBikeProfile()
        val tankMax = model.tankCapacityLiters.toDouble().coerceAtLeast(1.0)
        val tankMaxFloat = tankMax.toFloat()
        var selectedTimestampMs = System.currentTimeMillis()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvDesc = TextView(this).apply {
            text = String.format(
                Locale.US,
                "Easy Refuel Wizard • Petrol Rate: %s%.2f/L\nTap a quick amount to auto-compute litres:",
                currSymbol,
                petrolRate
            )
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvDesc)

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        val odoInput = createThemedEditText(
            hintText = "Current Odometer (km)",
            initialText = formatDecimal(currentOdo),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val costInput = createThemedEditText(
            hintText = "Total Paid $currSymbol",
            initialText = "500",
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val litersInput = createThemedEditText(
            hintText = "Fuel Litres",
            initialText = formatDecimal(500.0 / petrolRate, 2),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )

        costInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val amt = parseDecimal(s)
                if (amt != null && amt > 0) {
                    val lit = amt / petrolRate
                    litersInput.setText(formatDecimal(lit, 2))
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
                        val estCost = tankMax * petrolRate
                        costInput.setText(String.format(Locale.US, "%.0f", estCost))
                        litersInput.setText(formatDecimal(tankMax, 2))
                    } else {
                        costInput.setText(String.format(Locale.US, "%.0f", amount))
                        litersInput.setText(formatDecimal(amount / petrolRate, 2))
                    }
                }
            }
        }

        val p1 = (petrolRate * 2).roundToInt()
        val p2 = (petrolRate * 5).roundToInt()
        val p3 = (petrolRate * 10).roundToInt()
        presetRow.addView(createPresetButton("${currSymbol}${p1}", p1.toDouble()))
        presetRow.addView(createPresetButton("${currSymbol}${p2}", p2.toDouble()))
        presetRow.addView(createPresetButton("${currSymbol}${p3}", p3.toDouble()))
        presetRow.addView(createPresetButton("Full Tank", 0.0, isFull = true))
        layout.addView(presetRow)

        val tvOdoLabel = TextView(this).apply {
            text = "Odometer (km):"
            setTextColor(currentTheme.textTertiaryColor)
            textSize = 11f
        }
        layout.addView(tvOdoLabel)
        layout.addView(odoInput)

        val tvCostLabel = TextView(this).apply {
            text = "Total Cost ($currSymbol):"
            setTextColor(currentTheme.textTertiaryColor)
            textSize = 11f
        }
        layout.addView(tvCostLabel)
        layout.addView(costInput)

        val tvLitersLabel = TextView(this).apply {
            text = "Fuel Litres (Auto-calculated):"
            setTextColor(currentTheme.textTertiaryColor)
            textSize = 11f
        }
        layout.addView(tvLitersLabel)
        layout.addView(litersInput)

        val tvDateLabel = TextView(this).apply {
            text = "Refill Date:"
            setTextColor(currentTheme.textTertiaryColor)
            textSize = 11f
            setPadding(0, 8, 0, 4)
        }
        val btnDatePicker = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = formatFuelDialogDate(selectedTimestampMs)
            textSize = 12f
            setTextColor(currentTheme.accentColor)
            strokeColor = ColorStateList.valueOf(currentTheme.borderColor)
            setOnClickListener {
                provideHapticFeedback()
                showFuelDatePicker(selectedTimestampMs) { pickedMs ->
                    selectedTimestampMs = pickedMs
                    text = formatFuelDialogDate(selectedTimestampMs)
                }
            }
        }
        layout.addView(tvDateLabel)
        layout.addView(btnDatePicker)

        val checkFullTank = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Full Tank refill (Calculates true mileage)"
            isChecked = true
            setTextColor(currentTheme.textPrimaryColor)
            textSize = 12f
        }
        layout.addView(checkFullTank)

        MaterialAlertDialogBuilder(this)
            .setTitle("Log Refuel")
            .setView(layout)
            .setPositiveButton("Save Refill") { _, _ ->
                val odo = parseDecimal(odoInput.text)
                val liters = parseDecimal(litersInput.text)
                val cost = parseDecimal(costInput.text)

                if (odo != null && liters != null && cost != null && liters > 0) {
                    val isFull = checkFullTank.isChecked
                    val latestExistingMs = fuelLogManager.entries.value.maxOfOrNull { it.timestampMs } ?: 0L
                    val isMostRecentFill = latestExistingMs == 0L || selectedTimestampMs >= latestExistingMs
                    val entry = fuelLogManager.addFuelEntry(
                        odometerKm = odo,
                        liters = liters,
                        totalCostInr = cost,
                        isFullTank = isFull,
                        timestampMs = selectedTimestampMs
                    )
                    provideHapticFeedback()

                    if (isMostRecentFill) {
                        val currentLiters = appSettings.fuelLevelLiters
                        val newLiters = if (isFull) tankMaxFloat else (currentLiters + liters.toFloat()).coerceIn(0.0f, tankMaxFloat)
                        val newPercent = ((newLiters / tankMaxFloat) * 100f).roundToInt().coerceIn(0, 100)
                        val avgMileage = fuelLogManager.stats.value.averageKmpl.coerceIn(25.0, 50.0)
                        val newDte = (newLiters * avgMileage).toInt()

                        appSettings.fuelLevelLiters = newLiters
                        appSettings.fuelLevelPercent = newPercent
                        appSettings.projectedDteKm = newDte
                    }
                    recordSyncTime()
                    updateHeroRangeDisplay()

                    val kmplMsg = if (entry.calculatedKmpl != null) {
                        " • Mileage: ${formatDecimal(entry.calculatedKmpl)} km/l!"
                    } else if (!isFull) {
                        " (Partial fill recorded)"
                    } else {
                        " (Will compute mileage on next refill)"
                    }
                    showFeedback("Logged ${formatDecimal(liters, 2)} L for $currSymbol${String.format(Locale.US, "%.0f", cost)}$kmplMsg")
                } else {
                    showFeedback("Please enter valid fuel details", isAlert = true)
                }
            }
            .setNeutralButton(String.format(Locale.US, "Price: %s%.2f/L", currSymbol, petrolRate)) { _, _ ->
                showEditPetrolPriceDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateFuelPriceUi() {
        val currSymbol = appSettings.currentCurrencySymbol
        binding.tabFuel.tvFuelPriceTitle.text = String.format(Locale.US, "Fuel Price: %s%.2f / L", currSymbol, appSettings.petrolPricePerLiter)
        binding.tabFuel.tvFuelPriceCountrySubtitle.text = "Tap to update default ${appSettings.currentCurrencyCode} rate"
        binding.tabFuel.tvFuelPriceTitle.setTextColor(currentTheme.textPrimaryColor)
        binding.tabFuel.tvFuelPriceCountrySubtitle.setTextColor(currentTheme.textSecondaryColor)
        binding.tabFuel.tvEditFuelPriceAction.setTextColor(currentTheme.accentColor)
    }

    private fun showEditPetrolPriceDialog() {
        val currSymbol = appSettings.currentCurrencySymbol
        val input = createThemedEditText(
            hintText = "Price in $currSymbol",
            initialText = formatDecimal(appSettings.petrolPricePerLiter, 2),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val container = FrameLayout(this).apply {
            setPadding(50, 30, 50, 10)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Set Local Fuel Price ($currSymbol / Litre)")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val p = parseDecimal(input.text)
                if (p != null && p > 0.0) {
                    appSettings.petrolPricePerLiter = p
                    updateFuelPriceUi()
                    updateHeroRangeDisplay()
                    showFeedback("Fuel price set to $currSymbol${formatDecimal(p, 2)}/L")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isWifiEnabled(): Boolean {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        return wm?.isWifiEnabled == true
    }

    private fun promptEnableWifi(
        actionType: PendingWifiActionType = PendingWifiActionType.NONE,
        onWifiReady: (() -> Unit)? = null
    ) {
        pendingWifiActionType = actionType
        pendingWifiAction = onWifiReady
        MaterialAlertDialogBuilder(this)
            .setTitle("Turn On Wi-Fi")
            .setMessage("Wi-Fi is currently turned off on your phone. To cast navigation maps to your motorcycle TFT cluster, Wi-Fi must be enabled.")
            .setPositiveButton("Turn On") { _, _ ->
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        Intent(android.provider.Settings.Panel.ACTION_WIFI)
                    } catch (_: Exception) {
                        Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                    }
                } else {
                    Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                }
                try {
                    wifiSettingsLauncher.launch(intent)
                } catch (e: Exception) {
                    showFeedback("Unable to open Wi-Fi settings: ${e.message}", isAlert = true)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                pendingWifiAction = null
                pendingWifiActionType = PendingWifiActionType.NONE
            }
            .setOnCancelListener {
                pendingWifiAction = null
                pendingWifiActionType = PendingWifiActionType.NONE
            }
            .show()
    }

    private fun ensureWifiEnabled(onReady: () -> Unit) {
        if (!isWifiEnabled()) {
            promptEnableWifi(PendingWifiActionType.NONE, onReady)
        } else {
            onReady()
        }
    }

    private fun startTftHudMirror() {
        if (bleManager.isLinkReady && !bleManager.isConnectedToTftCluster()) {
            showFeedback("Wi-Fi mirroring is for 5\" TFT clusters (NS400Z). LCD clusters receive navigation via Bluetooth.", isAlert = true)
            return
        }
        if (!isWifiEnabled()) {
            promptEnableWifi(PendingWifiActionType.MIRROR_HUD) { startTftHudMirror() }
            return
        }
        val mirrorModeLabel = if (appSettings.tftMirrorMode == AppSettings.TFT_MODE_HUD) {
            "Cockpit HUD"
        } else {
            "800×480 Live Map (Maps-Only, Lock-Proof)"
        }
        TftWifiMirrorManager.getInstance(this).stopLocalDebugPreview()
        BikeBleForegroundService.startForTftCockpitMirror(this)
        showFeedback("Connecting to TFT Wi-Fi for $mirrorModeLabel...")
    }

    private fun checkLandscapeAndLaunchCast() {
        if (bleManager.isLinkReady && !bleManager.isConnectedToTftCluster()) {
            showFeedback("Wi-Fi screen cast is for 5\" TFT clusters (NS400Z). LCD clusters receive navigation via Bluetooth.", isAlert = true)
            return
        }
        if (!isWifiEnabled()) {
            promptEnableWifi(PendingWifiActionType.LIVE_CAST) { checkLandscapeAndLaunchCast() }
            return
        }
        if (appSettings.isTftCastForceLandscapeEnabled && !android.provider.Settings.System.canWrite(this)) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Allow Auto-Landscape")
                .setMessage("To automatically rotate your screen and navigation apps into landscape during TFT Live Cast, PulsarNav needs the 'Modify system settings' permission.")
                .setPositiveButton("Allow") { _, _ ->
                    try {
                        startActivity(
                            Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                        )
                    } catch (_: Exception) {
                        launchTftScreenCapture()
                    }
                }
                .setNegativeButton("Continue Anyway") { _, _ ->
                    launchTftScreenCapture()
                }
                .show()
        } else {
            launchTftScreenCapture()
        }
    }

    private fun launchTftScreenCapture() {
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        if (mpm == null) {
            showFeedback("Screen capture unsupported on this device", isAlert = true)
            return
        }
        try {
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mpm.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForUserChoice()
                )
            } else {
                mpm.createScreenCaptureIntent()
            }
            tftScreenCaptureLauncher.launch(captureIntent)
        } catch (e: Exception) {
            showFeedback("Unable to start screen capture: ${e.message}", isAlert = true)
        }
    }

    private fun getMissingBlePermissions(): List<String> {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(Manifest.permission.BLUETOOTH_SCAN)
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getMissingTftWifiPermissions(): List<String> {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getMissingPermissions(): List<String> {
        val required = getMissingBlePermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                required.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                required.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        return required
    }

    private fun getMissingTelephonyPermissions(): List<String> {
        val required = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        return required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestTelephonyAndClusterPermissions() {
        val missing = getMissingTelephonyPermissions()
        if (missing.isEmpty()) {
            showFeedback("All Call Log, Contacts, Dialback, SMS & POI permissions granted")
            bleManager.syncPhonebookAndCalls()
            bleManager.dispatchPlaylistInfoOnBle()
            updateTelephonyPermissionsUi()
        } else {
            telephonyPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun updateTelephonyPermissionsUi() {
        val missing = getMissingTelephonyPermissions()
        if (missing.isEmpty()) {
            binding.tabAccount.tvTelephonyPermsStatus.text = "All Granted (Favs, Recents, Dialback, SMS, POI GPS)"
            binding.tabAccount.tvTelephonyPermsStatus.setTextColor(ContextCompat.getColor(this, R.color.mint_accent))
        } else {
            val shortNames = missing.joinToString(", ") { it.substringAfterLast('.') }
            binding.tabAccount.tvTelephonyPermsStatus.text = "Tap to grant: $shortNames"
            binding.tabAccount.tvTelephonyPermsStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
        }
    }

    private fun updateSmsRejectUi() {
        binding.tabAccount.switchSmsReject.isChecked = appSettings.isSmsRejectEnabled
        val msg = appSettings.smsRejectMessage.trim().ifBlank { com.pulsarnav.app.data.AppSettings.DEFAULT_REJECT_SMS_TEMPLATE }
        binding.tabAccount.tvSmsRejectSummary.text = if (appSettings.isSmsRejectEnabled) {
            "Reply: \"$msg\""
        } else {
            "Off (Template: \"$msg\")"
        }
    }

    private fun showEditSmsRejectTemplateDialog() {
        val currentMsg = appSettings.smsRejectMessage.trim().ifBlank { com.pulsarnav.app.data.AppSettings.DEFAULT_REJECT_SMS_TEMPLATE }
        val input = createThemedEditText(
            hintText = "SMS reply when rejecting call on switchgear",
            initialText = currentMsg
        )
        val container = FrameLayout(this).apply {
            setPadding(50, 30, 50, 10)
            addView(input)
        }
        MaterialAlertDialogBuilder(this, R.style.ObsidianAlertDialog)
            .setTitle("Call Reject Auto-Reply SMS")
            .setMessage("Sent when Reject with SMS is triggered on your Bajaj TFT cluster switchgear (up to 160 chars).")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    appSettings.smsRejectMessage = text.take(160)
                    updateSmsRejectUi()
                    showFeedback("Reject-with-SMS template saved")
                } else {
                    showFeedback("Template cannot be empty", isAlert = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isLocationEnabled(): Boolean {
        val locManager = getSystemService(LOCATION_SERVICE) as? LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locManager?.isLocationEnabled == true
        } else {
            locManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        }
    }

    private fun updateHardwareReadinessUi() {
        val btAdapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val isBtOn = btAdapter?.isEnabled == true
        val isGpsOn = isLocationEnabled()
        val isGpsRequiredForBle = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

        val missingBle = getMissingBlePermissions()
        val hasPerms = missingBle.isEmpty()

        binding.chipBtWarning.visibility = if (!isBtOn) View.VISIBLE else View.GONE
        binding.chipGpsWarning.visibility = if (!isGpsOn) View.VISIBLE else View.GONE
        binding.chipPermWarning.visibility = if (!hasPerms) View.VISIBLE else View.GONE

        val isConnected = bleManager.connectionState.value is BleConnectionState.Connected
        if (!isConnected) {
            when {
                !hasPerms -> {
                    binding.tabCockpit.tvSubStatus.text = "Permissions needed: Tap PERMS NEEDED above"
                    binding.tabCockpit.tvSubStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
                }
                !isBtOn && isGpsRequiredForBle && !isGpsOn -> {
                    binding.tabCockpit.tvSubStatus.text = "Turn ON Bluetooth & GPS in Quick Settings"
                    binding.tabCockpit.tvSubStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
                }
                !isBtOn -> {
                    binding.tabCockpit.tvSubStatus.text = "Turn ON Bluetooth in Quick Settings"
                    binding.tabCockpit.tvSubStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
                }
                isGpsRequiredForBle && !isGpsOn -> {
                    binding.tabCockpit.tvSubStatus.text = "Turn ON Location (GPS) in Quick Settings"
                    binding.tabCockpit.tvSubStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
                }
                !isGpsOn -> {
                    binding.tabCockpit.tvSubStatus.text = "Location (GPS) is OFF • BLE ready, turn ON for nav"
                    binding.tabCockpit.tvSubStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
                }
                else -> {
                    binding.tabCockpit.tvSubStatus.text = if (bleManager.isAutoConnectEnabled) {
                        "Auto-connect active • waiting for bike"
                    } else {
                        "Auto-connect paused • tap Scan to connect"
                    }
                    binding.tabCockpit.tvSubStatus.setTextColor(currentTheme.textTertiaryColor)
                }
            }
        }
    }

    private fun checkAndStartScan(silent: Boolean = false) {
        val btAdapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val isBtOn = btAdapter?.isEnabled == true
        val isGpsOn = isLocationEnabled()
        val isGpsRequiredForBle = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

        updateHardwareReadinessUi()

        val missingBle = getMissingBlePermissions()
        val btGranted = missingBle.isEmpty()
        if (!btGranted) {
            if (!silent) {
                showFeedback("Grant required permissions to connect to your bike console", isAlert = true)
                permissionLauncher.launch(getMissingPermissions().toTypedArray())
            }
            return
        }

        if (!isBtOn) {
            if (!silent) {
                showFeedback("Bluetooth is OFF. Turn ON Bluetooth to scan for your bike console.", isAlert = true)
                try {
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                } catch (_: Exception) {}
            }
            return
        }

        if (isGpsRequiredForBle && !isGpsOn) {
            if (!silent) {
                showFeedback("Location (GPS) is OFF. Android requires Location to scan for Bluetooth devices.", isAlert = true)
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {}
            }
            return
        }

        bleManager.startAutoConnect(force = !silent)
    }

    private fun showVehicleSetupBottomSheet() {
        val sheet = BottomSheetDialog(this, R.style.ObsidianBottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_vehicle_setup, null)
        sheet.setContentView(sheetView)

        val spinnerCountry = sheetView.findViewById<Spinner>(R.id.spinnerCountry)
        val spinnerModel = sheetView.findViewById<Spinner>(R.id.spinnerModel)
        val btnNavGmaps = sheetView.findViewById<MaterialButton>(R.id.btnNavGoogleMaps)
        val btnNavWaze = sheetView.findViewById<MaterialButton>(R.id.btnNavWaze)
        val tvPreviewTitle = sheetView.findViewById<TextView>(R.id.tvPreviewTitle)
        val tvPreviewEngine = sheetView.findViewById<TextView>(R.id.tvPreviewEngine)
        val tvPreviewFrontTire = sheetView.findViewById<TextView>(R.id.tvPreviewFrontTire)
        val tvPreviewRearTire = sheetView.findViewById<TextView>(R.id.tvPreviewRearTire)
        val tvPreviewTank = sheetView.findViewById<TextView>(R.id.tvPreviewTank)
        val tvPreviewSos = sheetView.findViewById<TextView>(R.id.tvPreviewSos)
        val btnSave = sheetView.findViewById<MaterialButton>(R.id.btnSaveVehicleSetup)

        btnSave?.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
        btnSave?.setTextColor(currentTheme.onPrimaryColor)

        // Setup Country Spinner
        val countryNames = VehicleCatalog.COUNTRIES.map {
            "${it.labelTag} ${it.name} (SOS: ${it.sosNumber})"
        }
        val countryAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, countryNames)
        spinnerCountry?.adapter = countryAdapter

        val activeCountryIndex = VehicleCatalog.COUNTRIES.indexOfFirst {
            it.code.equals(appSettings.selectedCountryCode, ignoreCase = true)
        }.coerceAtLeast(0)
        spinnerCountry?.setSelection(activeCountryIndex)

        // Setup Model Spinner
        val modelNames = VehicleCatalog.MODELS.map {
            "[${it.series}] ${it.name}"
        }
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelNames)
        spinnerModel?.adapter = modelAdapter

        val activeModelIndex = VehicleCatalog.MODELS.indexOfFirst {
            it.id.equals(appSettings.selectedModelId, ignoreCase = true)
        }.coerceAtLeast(0)
        spinnerModel?.setSelection(activeModelIndex)

        // Setup Nav App Selection
        var selectedNavApp = appSettings.preferredNavApp
        fun updateNavAppButtons() {
            if (selectedNavApp == AppSettings.NAV_APP_WAZE) {
                btnNavWaze?.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
                btnNavWaze?.setTextColor(currentTheme.accentColor)
                btnNavWaze?.strokeColor = ColorStateList.valueOf(currentTheme.accentColor)

                btnNavGmaps?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.surface_card))
                btnNavGmaps?.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                btnNavGmaps?.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.surface_border))
            } else {
                btnNavGmaps?.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
                btnNavGmaps?.setTextColor(currentTheme.accentColor)
                btnNavGmaps?.strokeColor = ColorStateList.valueOf(currentTheme.accentColor)

                btnNavWaze?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.surface_card))
                btnNavWaze?.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                btnNavWaze?.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.surface_border))
            }
        }
        updateNavAppButtons()

        btnNavGmaps?.setOnClickListener {
            provideHapticFeedback()
            selectedNavApp = AppSettings.NAV_APP_GOOGLE_MAPS
            updateNavAppButtons()
        }

        btnNavWaze?.setOnClickListener {
            provideHapticFeedback()
            selectedNavApp = AppSettings.NAV_APP_WAZE
            updateNavAppButtons()
        }

        // Function to refresh the live preview card
        fun refreshPreview() {
            val cIdx = (spinnerCountry?.selectedItemPosition ?: 0).coerceIn(0, VehicleCatalog.COUNTRIES.lastIndex)
            val mIdx = (spinnerModel?.selectedItemPosition ?: 0).coerceIn(0, VehicleCatalog.MODELS.lastIndex)
            val country = VehicleCatalog.COUNTRIES[cIdx]
            val model = VehicleCatalog.MODELS[mIdx]
            val bikeDisplayName = VehicleCatalog.getLocalizedBikeName(model, country)

            tvPreviewTitle?.text = "$bikeDisplayName Specs Preview"
            tvPreviewTitle?.setTextColor(currentTheme.accentColor)
            tvPreviewEngine?.text = model.engineSpec
            tvPreviewFrontTire?.text = "${model.coldTireFrontPsiSolo} PSI (Solo) • ${model.coldTireFrontPsiPillion} PSI (Pillion)\n${model.tireFrontSize}"
            tvPreviewRearTire?.text = "${model.coldTireRearPsiSolo} PSI (Solo) • ${model.coldTireRearPsiPillion} PSI (Pillion)\n${model.tireRearSize}"
            tvPreviewTank?.text = "${model.tankCapacityLiters} L (Reserve: ${model.reserveCapacityLiters} L)"
            tvPreviewSos?.text = "${country.sosNumber} (${country.name} SOS)"
        }

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                refreshPreview()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        spinnerCountry?.onItemSelectedListener = listener
        spinnerModel?.onItemSelectedListener = listener
        refreshPreview()

        btnSave?.setOnClickListener {
            provideHapticFeedback()
            val cIdx = (spinnerCountry?.selectedItemPosition ?: 0).coerceIn(0, VehicleCatalog.COUNTRIES.lastIndex)
            val mIdx = (spinnerModel?.selectedItemPosition ?: 0).coerceIn(0, VehicleCatalog.MODELS.lastIndex)
            val country = VehicleCatalog.COUNTRIES[cIdx]
            val model = VehicleCatalog.MODELS[mIdx]

            appSettings.applyBikeAndCountrySelection(model.id, country.code)
            appSettings.preferredNavApp = selectedNavApp

            updateHeroRangeDisplay()
            updateBikeInfoDisplay()
            updateQuickNavButtons()
            updateNavAppSelectionUi()
            val bikeDisplayName = VehicleCatalog.getLocalizedBikeName(model, country)
            showFeedback("Configured for $bikeDisplayName (${country.name})")
            sheet.dismiss()
        }

        sheet.show()
    }

    private fun observeBleState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bleManager.connectionState.collectLatest { state ->
                when (state) {
                    is BleConnectionState.Disconnected -> {
                        stopStatusDotPulse(binding.tabCockpit.viewStatusDot)
                        stopStatusDotPulse(binding.viewTopConnectionDot)
                        binding.tabCockpit.viewStatusDot.backgroundTintList = ColorStateList.valueOf(currentTheme.accentColor)
                        binding.tabCockpit.tvConnectionStatus.text = "Disconnected"
                        binding.tabCockpit.tvConnectionStatus.setTextColor(currentTheme.textPrimaryColor)
                        binding.tabCockpit.tvSubStatus.text = if (bleManager.isAutoConnectEnabled) {
                            "Auto-connect active • waiting for bike"
                        } else {
                            "Auto-connect paused • tap Scan to connect"
                        }
                        binding.tabCockpit.tvSubStatus.setTextColor(currentTheme.textSecondaryColor)
                        binding.tabCockpit.btnScan.text = "Scan"
                        binding.tabCockpit.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
                        binding.tabCockpit.btnScan.setTextColor(currentTheme.onPrimaryColor)
                        binding.tabCockpit.rvDeviceList.visibility = View.VISIBLE

                        binding.viewTopConnectionDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_disconnected)
                        binding.tvTopConnectionStatus.text = "OFFLINE"
                        binding.tvTopConnectionStatus.setTextColor(currentTheme.textTertiaryColor)
                    }
                    is BleConnectionState.Scanning -> {
                        startStatusDotPulse(binding.tabCockpit.viewStatusDot)
                        startStatusDotPulse(binding.viewTopConnectionDot)
                        binding.tabCockpit.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_scanning)
                        binding.tabCockpit.tvConnectionStatus.text = "Searching for bike..."
                        binding.tabCockpit.tvConnectionStatus.setTextColor(currentTheme.textPrimaryColor)
                        binding.tabCockpit.tvSubStatus.text = "Auto-scanning • ensure bike ignition is ON"
                        binding.tabCockpit.tvSubStatus.setTextColor(currentTheme.textSecondaryColor)
                        binding.tabCockpit.btnScan.text = "Stop"
                        binding.tabCockpit.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
                        binding.tabCockpit.btnScan.setTextColor(currentTheme.onPrimaryColor)
                        binding.tabCockpit.rvDeviceList.visibility = View.VISIBLE

                        binding.viewTopConnectionDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_scanning)
                        binding.tvTopConnectionStatus.text = "SEARCHING"
                        binding.tvTopConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_scanning))
                    }
                    is BleConnectionState.Connecting -> {
                        startStatusDotPulse(binding.tabCockpit.viewStatusDot)
                        startStatusDotPulse(binding.viewTopConnectionDot)
                        binding.tabCockpit.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_scanning)
                        binding.tabCockpit.tvConnectionStatus.text = "Connecting..."
                        binding.tabCockpit.tvConnectionStatus.setTextColor(currentTheme.textPrimaryColor)
                        binding.tabCockpit.tvSubStatus.text = state.deviceName
                        binding.tabCockpit.tvSubStatus.setTextColor(currentTheme.textSecondaryColor)
                        binding.tabCockpit.btnScan.text = "Cancel"
                        binding.tabCockpit.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
                        binding.tabCockpit.btnScan.setTextColor(currentTheme.accentColor)

                        binding.viewTopConnectionDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_scanning)
                        binding.tvTopConnectionStatus.text = "CONNECTING"
                        binding.tvTopConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_scanning))
                    }
                    is BleConnectionState.Connected -> {
                        stopStatusDotPulse(binding.tabCockpit.viewStatusDot)
                        stopStatusDotPulse(binding.viewTopConnectionDot)
                        BikeBleForegroundService.start(this@MainActivity)
                        val isTft = bleManager.isConnectedToTftCluster()
                        val clusterTypeLabel = if (isTft) "TFT" else "LCD"
                        binding.tabCockpit.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_connected)
                        binding.tabCockpit.tvConnectionStatus.text = "Connected ($clusterTypeLabel) • ${state.deviceName}"
                        binding.tabCockpit.tvConnectionStatus.setTextColor(currentTheme.textPrimaryColor)
                        binding.tabCockpit.tvSubStatus.text = "Cluster synced (${state.address})"
                        binding.tabCockpit.tvSubStatus.setTextColor(currentTheme.textSecondaryColor)
                        binding.tabCockpit.btnScan.text = "Disconnect"
                        binding.tabCockpit.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
                        binding.tabCockpit.btnScan.setTextColor(currentTheme.accentColor)
                        binding.tabCockpit.rvDeviceList.visibility = View.GONE
                        binding.tabAccount.btnSyncPhonebookCalls.visibility = if (isTft) View.VISIBLE else View.GONE

                        binding.viewTopConnectionDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_connected)
                        binding.tvTopConnectionStatus.text = if (isTft) "TFT CONNECTED" else "LCD CONNECTED"
                        binding.tvTopConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))

                        recordSyncTime()
                    }
                    is BleConnectionState.Error -> {
                        stopStatusDotPulse(binding.tabCockpit.viewStatusDot)
                        stopStatusDotPulse(binding.viewTopConnectionDot)
                        binding.tabCockpit.viewStatusDot.backgroundTintList = ColorStateList.valueOf(currentTheme.accentColor)
                        binding.tabCockpit.tvConnectionStatus.text = state.message
                        binding.tabCockpit.btnScan.text = "Scan"
                        binding.tabCockpit.btnScan.backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
                        binding.tabCockpit.btnScan.setTextColor(currentTheme.onPrimaryColor)

                        binding.viewTopConnectionDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_disconnected)
                        binding.tvTopConnectionStatus.text = "OFFLINE"
                        binding.tvTopConnectionStatus.setTextColor(currentTheme.textTertiaryColor)
                    }
                }
            }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bleManager.discoveredBikes.collectLatest { bikes ->
                    updateDeviceList(bikes)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bleManager.lastSentPacket.collectLatest { packet ->
                    if (packet != null) {
                        val compactHex = DebugLogRepository.toCompactHex(packet, maxBytes = 12)
                        binding.tabAccount.tvPacketHex.text = "Last Frame (${packet.size}B): $compactHex • payload redacted"
                        binding.tabAccount.tvPacketDecoded.text = "Decoded: ${DebugLogRepository.summarizeBlePacket(packet)}"
                    } else {
                        binding.tabAccount.tvPacketHex.text = "Last BLE Packet: None"
                        binding.tabAccount.tvPacketDecoded.text = "Decoded: Waiting for BLE frame..."
                    }
                }
            }
        }
    }

    private fun observeTftWifiMirror() {
        val mirrorManager = TftWifiMirrorManager.getInstance(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(mirrorManager.mirrorState, bleManager.connectionState) { mirror, ble ->
                    mirror to ble
                }.collectLatest { (mirror, ble) ->
                val active = mirror.status != TftMirrorStatus.IDLE && mirror.status != TftMirrorStatus.ERROR
                val dotRes = when (mirror.status) {
                    TftMirrorStatus.STREAMING, TftMirrorStatus.TCP_CONNECTED_IDLE -> R.color.status_connected
                    TftMirrorStatus.CONNECTING_WIFI, TftMirrorStatus.WIFI_CONNECTED_WAITING_TCP -> R.color.status_scanning
                    TftMirrorStatus.IDLE, TftMirrorStatus.ERROR -> R.color.status_disconnected
                }
                binding.tabCockpit.viewTftWifiDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, dotRes)

                val targetSsid = mirror.ssid.ifBlank {
                    appSettings.tftWifiSsid.ifBlank {
                        TftPasswordGenerator.deriveDefaultSsid(bleManager.getConnectedClusterName(), appSettings.tftWifiVin)
                    }
                }
                val isOtcConnected = (ble is BleConnectionState.Connected) && !bleManager.isConnectedToTftCluster()

                binding.tabCockpit.tvTftWifiStatus.text = when (mirror.status) {
                    TftMirrorStatus.IDLE -> {
                        if (isOtcConnected) {
                            "OTC LCD cluster connected • Wi-Fi mirror is for TFT (NS400Z)"
                        } else {
                            "Idle • Target $targetSsid (800×480)"
                        }
                    }
                    TftMirrorStatus.CONNECTING_WIFI -> "Joining SoftAP $targetSsid..."
                    TftMirrorStatus.WIFI_CONNECTED_WAITING_TCP -> "Wi-Fi linked (${mirror.clusterHost}) • Waiting for TCP 5558"
                    TftMirrorStatus.TCP_CONNECTED_IDLE -> "Cluster TCP ready (${mirror.clusterHost}) • Idle"
                    TftMirrorStatus.STREAMING -> {
                        val modeLabel = when (mirror.source) {
                            TftMirrorSource.LIVE_MAP -> "Live Map"
                            TftMirrorSource.COCKPIT_HUD -> "HUD Mirror"
                            TftMirrorSource.LIVE_SCREEN_CAST -> "Live Cast"
                        }
                        String.format(
                            java.util.Locale.US,
                            "%s • %.1f fps • %s:%d",
                            modeLabel,
                            mirror.fps,
                            mirror.clusterHost,
                            com.pulsarnav.app.wifi.TftWifiProtocol.UDP_PORT
                        )
                    }
                    TftMirrorStatus.ERROR -> "Error • Check Wi-Fi / VIN config"
                }

                binding.tabCockpit.tvTftWifiMeta.text = when {
                    !mirror.errorMessage.isNullOrBlank() -> mirror.errorMessage
                    active && mirror.framesSent > 0 -> {
                        val parkTag = if (mirror.isBikeStationary) "Stationary" else "Moving"
                        val touchTag = if (mirror.lastTouchInfo.isNotBlank()) " • ${mirror.lastTouchInfo}" else ""
                        "${mirror.framesSent} frames • ${mirror.lastFrameBytes}B (Q=${mirror.jpegQuality}) • $parkTag$touchTag"
                    }
                    else -> {
                        val credHint = if (appSettings.tftWifiVin.isNotBlank() || appSettings.tftWifiPassphrase.isNotBlank()) {
                            "SoftAP credentials saved for $targetSsid."
                        } else {
                            "Tap Config to enter VIN/WPA2 password or set orientation."
                        }
                        val prefLabel = if (appSettings.tftMirrorMode == AppSettings.TFT_MODE_HUD) {
                            "Cockpit HUD (works screen-off)"
                        } else {
                            "Live Maps 800×480 (Google Maps Sync • works locked)"
                        }
                        "$prefLabel. $credHint"
                    }
                }

                val isHandshaking = mirror.status == TftMirrorStatus.CONNECTING_WIFI ||
                    mirror.status == TftMirrorStatus.WIFI_CONNECTED_WAITING_TCP
                binding.tabCockpit.progressTftHandshake.visibility = if (isHandshaking) View.VISIBLE else View.GONE
                if (isHandshaking) {
                    binding.tabCockpit.progressTftHandshake.setIndicatorColor(
                        if (mirror.status == TftMirrorStatus.CONNECTING_WIFI)
                            ContextCompat.getColor(this@MainActivity, R.color.amber_accent)
                        else
                            ContextCompat.getColor(this@MainActivity, R.color.mint_accent)
                    )
                }

                val isScreenCastActive = active && mirror.source == TftMirrorSource.LIVE_SCREEN_CAST
                val isHeadlessActive = active && mirror.source != TftMirrorSource.LIVE_SCREEN_CAST

                val prefIdleBtn = if (appSettings.tftMirrorMode == AppSettings.TFT_MODE_HUD) "HUD Mirror\n(Screen Off)" else "Live Map (800×480)\n(Google Maps Sync)"
                binding.tabCockpit.btnToggleTftHudMirror.text = when {
                    isHeadlessActive -> "Stop Mirror"
                    isScreenCastActive -> "Switch to\nLive Map"
                    else -> prefIdleBtn
                }
                binding.tabCockpit.btnCastLiveScreen.text = when {
                    isScreenCastActive -> "Stop Cast"
                    isHeadlessActive -> "Switch to\nScreen Cast"
                    else -> "Cast Google Maps\n(Screen Mirror)"
                }
                binding.tabCockpit.btnToggleTftHudMirror.isEnabled = !isHandshaking && !isOtcConnected
                binding.tabCockpit.btnCastLiveScreen.isEnabled = !isHandshaking && !isOtcConnected

                val isLiveCast = active && mirror.source == TftMirrorSource.LIVE_SCREEN_CAST
                if (isLiveCast) {
                    wasLiveCastActive = true
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    if (active && mirror.source == TftMirrorSource.LIVE_MAP) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    if (wasLiveCastActive && requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                        wasLiveCastActive = false
                        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTftAndLogDebugger() = tftLabsController.setup()

    private fun showTftWifiConfigDialog() = navigationHubController.showTftWifiConfigDialog()

    private fun startTftRouteFromSharedInput(rawInput: String) =
        navigationHubController.showNavigationHubBottomSheet(initialInput = rawInput, autoStart = true)

    private fun showDestinationSearchAndRouteDialog() =
        navigationHubController.showNavigationHubBottomSheet()

    private fun showNavigationHubBottomSheet(initialInput: String? = null, autoStart: Boolean = false) =
        navigationHubController.showNavigationHubBottomSheet(initialInput, autoStart)

    private fun showMapsTbtTroubleshooterDialog() = navigationHubController.showMapsTbtTroubleshooterDialog()

    private fun updateDeviceList(bikes: List<DiscoveredBike>) {
        discoveredBikeAdapter.submitList(bikes.toList())
    }


    private fun observeNavigation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        GoogleMapsNotificationListener.currentNavStep,
                        GoogleMapsNotificationListener.diagnostics,
                        TftNavigationMapEngine.getInstance(this@MainActivity).uiState
                    ) { extStep, diag, mapUi ->
                        Triple(extStep, diag, mapUi)
                    }.collectLatest { (extStep, diag, mapUi) ->
                        val step = extStep ?: TftNavigationMapEngine.getInstance(this@MainActivity).getCurrentStepForHud()
                        if (step != null) {
                            val dist = step.rawDistanceText.ifBlank { "${step.distanceMeters} m" }
                            val maneuverDesc = if (step.roundaboutExit > 0) {
                                "Roundabout (Exit ${step.roundaboutExit})"
                            } else {
                                step.maneuver.description
                            }
                            val street = if (step.roundaboutExit > 0 && step.streetName.isNotBlank()) {
                                "Exit ${step.roundaboutExit} • ${step.streetName}"
                            } else {
                                step.streetName.ifBlank { maneuverDesc }
                            }
                            val etaStr = if (step.etaHour > 0) {
                                val ampm = if (step.etaIsAm) "AM" else "PM"
                                "ETA %d:%02d %s".format(step.etaHour, step.etaMinute, ampm)
                            } else {
                                mapUi.remainingEtaText.ifBlank { "Active Route" }
                            }
                            updateNavDisplay(step.maneuver, dist, street, etaStr)
                        } else if (!bleManager.isSimulationRunning.value) {
                            binding.tabAccount.tvDistance.text = "-- m"
                            binding.tabAccount.tvStreetName.text = if (!isNotificationAccessGranted()) {
                                "Enable Notification Access or tap Search"
                            } else if (!diag.mapsActiveInShade) {
                                "Waiting for Maps Notif (tap Maps Fix)"
                            } else {
                                "Waiting for Google Maps..."
                            }
                            binding.tabAccount.tvManeuverIcon.text = "⬆"
                            binding.tabAccount.tvEtaBadge.text = "-- min ETA"
                        }
                        updateNotificationAccessUi()

                        // Update Active TFT Live Navigation Dashboard Card
                        val isTftNavRunning = mapUi.routeActive || mapUi.isLoadingRoute || mapUi.isRerouting
                        if (isTftNavRunning) {
                            binding.tabCockpit.cardActiveTftNavigation.visibility = View.VISIBLE
                            when {
                                mapUi.isRerouting -> {
                                    binding.tabCockpit.tvActiveNavNextTurn.text = "Rerouting..."
                                    binding.tabCockpit.tvActiveNavTripSummary.text = "Recalculating route to ${mapUi.destinationLabel}..."
                                    binding.tabCockpit.tvActiveNavStatusBadge.text = "REROUTING"
                                    binding.tabCockpit.btnActiveNavReroute.isEnabled = false
                                }
                                mapUi.isLoadingRoute -> {
                                    binding.tabCockpit.tvActiveNavNextTurn.text = "Calculating Route"
                                    binding.tabCockpit.tvActiveNavTripSummary.text = "Looking up ${mapUi.destinationLabel}..."
                                    binding.tabCockpit.tvActiveNavStatusBadge.text = "CALCULATING"
                                    binding.tabCockpit.btnActiveNavReroute.isEnabled = false
                                }
                                else -> {
                                    binding.tabCockpit.btnActiveNavReroute.isEnabled = true
                                    val turnTitle = if (step != null) {
                                        val dist = step.rawDistanceText.ifBlank { "${step.distanceMeters} m" }
                                        val street = step.streetName.ifBlank { step.maneuver.description }
                                        "$dist • $street"
                                    } else {
                                        mapUi.destinationLabel.ifBlank { "TFT Live Navigation" }
                                    }
                                    binding.tabCockpit.tvActiveNavNextTurn.text = turnTitle
                                    val stats = listOfNotNull(
                                        mapUi.remainingDistanceText.ifBlank { null },
                                        mapUi.remainingEtaText.ifBlank { null }
                                    ).joinToString(" • ")
                                    binding.tabCockpit.tvActiveNavTripSummary.text = stats.ifBlank { "Active on TFT Cluster" }
                                    binding.tabCockpit.tvActiveNavStatusBadge.text = "TFT LIVE"
                                }
                            }
                        } else {
                            binding.tabCockpit.cardActiveTftNavigation.visibility = View.GONE
                        }
                    }
                }

                launch {
                    combine(bleManager.isSimulationRunning, bleManager.currentActiveManeuver) { running, maneuver ->
                        Pair(running, maneuver)
                    }.collectLatest { (running, maneuver) ->
                        updateSimulationUiState(running, maneuver)
                    }
                }
            }
        }
    }

    private fun updateNavDisplay(m: Maneuver, distText: String, street: String, etaText: String) {
        binding.tabAccount.tvDistance.text = distText
        binding.tabAccount.tvStreetName.text = street
        binding.tabAccount.tvManeuverIcon.text = getArrowForManeuver(m)
        binding.tabAccount.tvEtaBadge.text = etaText
    }

    private fun updateSimulationUiState(running: Boolean, maneuver: Maneuver?) {
        val theme = currentTheme
        val defaultSubtle = ColorStateList.valueOf(theme.subtleColor)
        val defaultBorder = ColorStateList.valueOf(theme.borderColor)
        val defaultText = theme.accentColor

        val activeTint = ColorStateList.valueOf(theme.primaryColor)
        val activeText = theme.onPrimaryColor

        if (running && maneuver == Maneuver.TURN_LEFT) {
            binding.tabAccount.btnTestLeft.backgroundTintList = activeTint
            binding.tabAccount.btnTestLeft.setTextColor(activeText)
        } else {
            binding.tabAccount.btnTestLeft.backgroundTintList = defaultSubtle
            binding.tabAccount.btnTestLeft.strokeColor = defaultBorder
            binding.tabAccount.btnTestLeft.setTextColor(defaultText)
        }

        if (running && maneuver == Maneuver.STRAIGHT) {
            binding.tabAccount.btnTestStraight.backgroundTintList = activeTint
            binding.tabAccount.btnTestStraight.setTextColor(activeText)
        } else {
            binding.tabAccount.btnTestStraight.backgroundTintList = defaultSubtle
            binding.tabAccount.btnTestStraight.strokeColor = defaultBorder
            binding.tabAccount.btnTestStraight.setTextColor(defaultText)
        }

        if (running && maneuver == Maneuver.TURN_RIGHT) {
            binding.tabAccount.btnTestRight.backgroundTintList = activeTint
            binding.tabAccount.btnTestRight.setTextColor(activeText)
        } else {
            binding.tabAccount.btnTestRight.backgroundTintList = defaultSubtle
            binding.tabAccount.btnTestRight.strokeColor = defaultBorder
            binding.tabAccount.btnTestRight.setTextColor(defaultText)
        }

        if (running && (maneuver == Maneuver.ROUNDABOUT_LEFT || maneuver == Maneuver.ROUNDABOUT_RIGHT)) {
            binding.tabAccount.btnTestRoundabout.backgroundTintList = activeTint
            binding.tabAccount.btnTestRoundabout.setTextColor(activeText)
        } else {
            binding.tabAccount.btnTestRoundabout.backgroundTintList = defaultSubtle
            binding.tabAccount.btnTestRoundabout.strokeColor = defaultBorder
            binding.tabAccount.btnTestRoundabout.setTextColor(defaultText)
        }

        if (running) {
            binding.tabAccount.btnStopSimulation.strokeColor = ColorStateList.valueOf(theme.accentColor)
            binding.tabAccount.btnStopSimulation.setTextColor(theme.accentColor)
        } else {
            binding.tabAccount.btnStopSimulation.strokeColor = defaultBorder
            binding.tabAccount.btnStopSimulation.setTextColor(theme.textSecondaryColor)
        }
    }

    private fun observeTelephonyAndAlerts() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    callManager.callInfo.collectLatest { call ->
                        when (call.state) {
                            CallState.INCOMING_CALL -> {
                                binding.tabAccount.tvCallerStatus.text = "Incoming Call: ${call.callerName ?: "Unknown"} (${call.callerNumber.orEmpty()})"
                                binding.tabAccount.tvCallerStatus.setTextColor(currentTheme.accentColor)
                                binding.tabAccount.btnSimulateCall.text = "End Call"
                            }
                            CallState.ACTIVE_CALL -> {
                                binding.tabAccount.tvCallerStatus.text = "Active Call: ${call.callerName ?: "In Call"}"
                                binding.tabAccount.tvCallerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))
                                binding.tabAccount.btnSimulateCall.text = "End Call"
                            }
                            CallState.OUTGOING_CALL -> {
                                binding.tabAccount.tvCallerStatus.text = "Outgoing Call: ${call.callerName ?: "Dialing..."}"
                                binding.tabAccount.tvCallerStatus.setTextColor(currentTheme.accentColor)
                                binding.tabAccount.btnSimulateCall.text = "End Call"
                            }
                            else -> {
                                binding.tabAccount.tvCallerStatus.text = "Phone Idle • No incoming call"
                                binding.tabAccount.tvCallerStatus.setTextColor(currentTheme.textPrimaryColor)
                                binding.tabAccount.btnSimulateCall.text = "Call"
                            }
                        }
                        binding.tabAccount.tvMissedCallBadge.text = "${call.missedCallCount} Missed"
                    }
                }

                launch {
                    mediaControlManager.lastMediaEvent.collectLatest { event ->
                        if (event != null) {
                            binding.tabAccount.tvMediaBanner.text = "Media Control: $event"
                            binding.tabAccount.tvMediaBanner.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))
                        } else {
                            binding.tabAccount.tvMediaBanner.text = "Handlebar Button: Click = Play/Pause • Hold = Next • Click+Hold = Prev"
                            binding.tabAccount.tvMediaBanner.setTextColor(currentTheme.accentColor)
                        }
                    }
                }

                launch {
                    GoogleMapsNotificationListener.lastWhatsAppAlert.collectLatest { alert ->
                        if (alert != null) {
                            val label = if (alert.alertType == com.pulsarnav.app.protocol.AlertType.SMS) {
                                "SMS Alert"
                            } else {
                                "WhatsApp Alert"
                            }
                            binding.tabAccount.tvWhatsAppAlertBanner.text = "$label: ${alert.sender} (Showing on cluster)"
                            binding.tabAccount.tvWhatsAppAlertBanner.setTextColor(currentTheme.accentColor)
                        } else {
                            binding.tabAccount.tvWhatsAppAlertBanner.text = "Messaging Alerts: Listening (WhatsApp & SMS)"
                            binding.tabAccount.tvWhatsAppAlertBanner.setTextColor(currentTheme.textSecondaryColor)
                        }
                    }
                }

                launch {
                    bleManager.lastClusterAction.collectLatest { action ->
                        if (action != null) {
                            binding.tabAccount.tvClusterActionStatus.text = "Cluster Action: $action"
                            binding.tabAccount.tvClusterActionStatus.setTextColor(currentTheme.accentColor)
                        } else {
                            binding.tabAccount.tvClusterActionStatus.text = "Cluster Action: Waiting for switchgear / phonebook sync"
                            binding.tabAccount.tvClusterActionStatus.setTextColor(currentTheme.textSecondaryColor)
                        }
                    }
                }
            }
        }
    }

    private fun showServiceTargetDialog() {
        provideHapticFeedback()
        val status = maintenanceManager.status.value
        val currentOdo = status.totalOdometerKm
        val currentTarget = status.nextServiceTargetOdoKm

        val options = mutableListOf<String>()
        options.add("1st Service (500 km - Break-in / New Bike)")
        options.add("2nd Service (5,000 km)")
        options.add("3rd Service (10,000 km)")
        options.add("Periodic (+5,000 km from current ODO)")
        if (status.isServiceOverdue) {
            options.add("Overdue Extension (+500 km)")
            options.add("Mark Service Completed Today")
        }
        options.add("Custom Target Odometer...")

        val titleText = if (status.isServiceOverdue) {
            "Service OVERDUE by %.0f km (Target: %.0f)".format(status.serviceOverdueKm, currentTarget)
        } else {
            "Service Target (%.0f km left • Target: %.0f km)".format(status.serviceRemainingKm, currentTarget)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(titleText)
            .setItems(options.toTypedArray()) { _, which ->
                provideHapticFeedback()
                when (options[which]) {
                    "1st Service (500 km - Break-in / New Bike)" -> {
                        maintenanceManager.setNextServiceTargetOdo(500.0)
                        recordSyncTime()
                        showFeedback("Next service target set to 500 km (1st Service)")
                    }
                    "2nd Service (5,000 km)" -> {
                        maintenanceManager.setNextServiceTargetOdo(5000.0)
                        recordSyncTime()
                        showFeedback("Next service target set to 5,000 km (2nd Service)")
                    }
                    "3rd Service (10,000 km)" -> {
                        maintenanceManager.setNextServiceTargetOdo(10000.0)
                        recordSyncTime()
                        showFeedback("Next service target set to 10,000 km (3rd Service)")
                    }
                    "Periodic (+5,000 km from current ODO)" -> {
                        val target = currentOdo + MaintenanceManager.SERVICE_INTERVAL_KM
                        maintenanceManager.setNextServiceTargetOdo(target)
                        recordSyncTime()
                        showFeedback(String.format(Locale.US, "Next service target set to %.0f km", target))
                    }
                    "Overdue Extension (+500 km)" -> {
                        maintenanceManager.rescheduleNextService(500.0)
                        recordSyncTime()
                        showFeedback("Service rescheduled. Extended by 500 km.")
                    }
                    "Mark Service Completed Today" -> {
                        maintenanceManager.markServiceDone()
                        recordSyncTime()
                        showFeedback(String.format(Locale.US, "Service marked completed. Next target: %.0f km", maintenanceManager.status.value.nextServiceTargetOdoKm))
                    }
                    "Custom Target Odometer..." -> {
                        showCustomServiceTargetDialog()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomServiceTargetDialog() {
        val input = createThemedEditText(
            hintText = "Target Odometer in km",
            initialText = String.format(Locale.US, "%.0f", maintenanceManager.status.value.nextServiceTargetOdoKm),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val container = FrameLayout(this).apply {
            setPadding(50, 30, 50, 10)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Set Custom Service Target Odometer")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val target = parseDecimal(input.text?.toString())
                if (target != null && target > 0) {
                    maintenanceManager.setNextServiceTargetOdo(target)
                    recordSyncTime()
                    showFeedback(String.format(Locale.US, "Next service target set to %.0f km", target))
                } else {
                    showFeedback("Invalid odometer value", isAlert = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateBikeIssuesSummary() {
        val issues = appSettings.getBikeIssues()
        val openCount = issues.count { !it.isResolved }
        if (openCount > 0) {
            binding.tabSupport.tvIssuesCountBadge.text = "$openCount Open"
            binding.tabSupport.tvIssuesCountBadge.backgroundTintList = ColorStateList.valueOf(currentTheme.subtleColor)
            binding.tabSupport.tvIssuesCountBadge.setTextColor(currentTheme.accentColor)
        } else {
            binding.tabSupport.tvIssuesCountBadge.text = "0 Pending"
            binding.tabSupport.tvIssuesCountBadge.backgroundTintList = ColorStateList.valueOf(if (currentTheme.isLight) Color.parseColor("#EAECEF") else Color.parseColor("#2A2832"))
            binding.tabSupport.tvIssuesCountBadge.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
        }
    }

    private fun showBikeIssuesChecklistDialog() {
        provideHapticFeedback()
        val dialog = BottomSheetDialog(this, R.style.ObsidianBottomSheetDialog)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 48)
            setBackgroundColor(currentTheme.cardColor)
        }

        val title = TextView(this).apply {
            text = "Bike Issues & Repair Checklist"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(currentTheme.textPrimaryColor)
        }
        val subtitle = TextView(this).apply {
            text = "Track mechanical symptoms and parts needing attention before your next service."
            textSize = 12f
            setTextColor(currentTheme.textTertiaryColor)
            setPadding(0, 8, 0, 24)
        }
        root.addView(title)
        root.addView(subtitle)

        // Input row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 24)
        }
        val etNewIssue = createThemedEditText(
            hintText = "Add issue (e.g. front brake squeal)..."
        ).apply {
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnAdd = MaterialButton(this).apply {
            text = "Add"
            textSize = 12f
            backgroundTintList = ColorStateList.valueOf(currentTheme.primaryColor)
            setTextColor(currentTheme.onPrimaryColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 16
            }
        }
        inputRow.addView(etNewIssue)
        inputRow.addView(btnAdd)
        root.addView(inputRow)

        val scrollView = NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(300))
        }
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(listContainer)
        root.addView(scrollView)

        fun renderIssues() {
            listContainer.removeAllViews()
            val issues = appSettings.getBikeIssues()
            if (issues.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = "No issues logged. Your bike is running in top condition."
                    textSize = 13f
                    setTextColor(currentTheme.textTertiaryColor)
                    setPadding(0, 32, 0, 32)
                    gravity = Gravity.CENTER
                }
                listContainer.addView(emptyTv)
                return
            }

            for (issue in issues) {
                val itemRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 16, 0, 16)
                }
                val chk = MaterialCheckBox(this).apply {
                    isChecked = issue.isResolved
                    buttonTintList = ColorStateList.valueOf(currentTheme.accentColor)
                    setOnCheckedChangeListener { _, _ ->
                        provideHapticFeedback()
                        appSettings.toggleBikeIssue(issue.id)
                        updateBikeIssuesSummary()
                        renderIssues()
                    }
                }
                val textLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(8, 0, 8, 0)
                }
                val tvDesc = TextView(this).apply {
                    text = issue.title
                    textSize = 14f
                    if (issue.isResolved) {
                        paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                        setTextColor(currentTheme.textTertiaryColor)
                    } else {
                        setTextColor(currentTheme.textPrimaryColor)
                    }
                }
                val tvDate = TextView(this).apply {
                    text = if (issue.isResolved) "Resolved • ${issue.dateLogged}" else "Added • ${issue.dateLogged}"
                    textSize = 10f
                    setTextColor(currentTheme.textTertiaryColor)
                }
                textLayout.addView(tvDesc)
                textLayout.addView(tvDate)

                val btnEdit = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Edit"
                    textSize = 10f
                    setPadding(0, 0, 0, 0)
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minimumWidth = 0
                    layoutParams = LinearLayout.LayoutParams(dpToPx(46), dpToPx(32)).apply {
                        marginEnd = dpToPx(6)
                    }
                    setOnClickListener {
                        provideHapticFeedback()
                        showEditBikeIssueDialog(issue) {
                            updateBikeIssuesSummary()
                            renderIssues()
                        }
                    }
                }

                val btnDel = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Del"
                    textSize = 10f
                    setPadding(0, 0, 0, 0)
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minimumWidth = 0
                    layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(32))
                    setOnClickListener {
                        provideHapticFeedback()
                        appSettings.deleteBikeIssue(issue.id)
                        updateBikeIssuesSummary()
                        renderIssues()
                    }
                }

                itemRow.addView(chk)
                itemRow.addView(textLayout)
                itemRow.addView(btnEdit)
                itemRow.addView(btnDel)
                listContainer.addView(itemRow)
            }
        }

        btnAdd.setOnClickListener {
            provideHapticFeedback()
            val desc = etNewIssue.text.toString().trim()
            if (desc.isNotBlank()) {
                appSettings.addBikeIssue(desc)
                etNewIssue.text?.clear()
                updateBikeIssuesSummary()
                renderIssues()
            }
        }

        renderIssues()
        dialog.setContentView(root)
        dialog.show()
    }

    private fun showEditBikeIssueDialog(
        issue: BikeIssue,
        onSaved: () -> Unit
    ) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }
        val etEditIssue = createThemedEditText(
            hintText = "Issue description...",
            initialText = issue.title
        ).apply {
            setSelection(text?.length ?: 0)
        }
        layout.addView(etEditIssue)

        MaterialAlertDialogBuilder(this, R.style.ObsidianAlertDialog)
            .setTitle("Edit Repair Issue")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val updated = etEditIssue.text.toString().trim()
                if (appSettings.updateBikeIssue(issue.id, updated)) {
                    provideHapticFeedback()
                    onSaved()
                    showFeedback("Checklist item updated")
                } else {
                    showFeedback("Please enter a valid issue description", isAlert = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun observeMaintenance() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                maintenanceManager.status.collectLatest { s ->
                    updateHeroRangeDisplay()
                    binding.tabSupport.tvChainRemaining.text = String.format(Locale.US, "%.0f km left", s.chainLubeRemainingKm)
                    binding.tabSupport.progressChain.progress = s.chainLubePercent
                    binding.tabSupport.tvSupportChainRemaining.text = String.format(Locale.US, "Chain Lube: %.0f km left", s.chainLubeRemainingKm.coerceAtLeast(0.0))

                    if (s.isServiceOverdue) {
                        binding.tabSupport.tvSupportServiceRemaining.text = "OVERDUE"
                        binding.tabSupport.tvSupportServiceRemainingUnit.visibility = View.GONE
                        binding.tabSupport.tvSupportServiceRemaining.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_disconnected))
                        binding.tabSupport.tvServiceHistorySummary.text = "Service Overdue • Immediate maintenance required"
                        binding.tabSupport.progressSupportService.progress = 100
                        binding.tabSupport.tvSupportHealthNote.text = "Schedule periodic service to maintain engine longevity"
                        binding.tabSupport.tvServiceRemaining.text = String.format(Locale.US, "OVERDUE by %.0f km", s.serviceOverdueKm)
                        binding.tabSupport.tvServiceRemaining.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_disconnected))
                        binding.tabSupport.progressService.progress = 100
                    } else {
                        binding.tabSupport.tvSupportServiceRemaining.text = String.format(Locale.US, "%.0f", s.serviceRemainingKm.coerceAtLeast(0.0))
                        binding.tabSupport.tvSupportServiceRemainingUnit.visibility = View.VISIBLE
                        binding.tabSupport.tvSupportServiceRemaining.setTextColor(currentTheme.accentColor)
                        val serviceInterval = MaintenanceManager.SERVICE_INTERVAL_KM
                        val serviceElapsed = (serviceInterval - s.serviceRemainingKm.coerceIn(0.0, serviceInterval))
                        val serviceProgress = ((serviceElapsed / serviceInterval) * 100).toInt().coerceIn(0, 100)
                        binding.tabSupport.progressSupportService.progress = serviceProgress
                        binding.tabSupport.tvServiceHistorySummary.text = "Periodic Service • $serviceProgress% of cycle elapsed"
                        binding.tabSupport.tvSupportHealthNote.text = String.format(Locale.US, "Next routine service due in %.0f km", s.serviceRemainingKm.coerceAtLeast(0.0))
                        binding.tabSupport.tvServiceRemaining.text = String.format(Locale.US, "%.0f km left (Target: %.0f km)", s.serviceRemainingKm, s.nextServiceTargetOdoKm)
                        binding.tabSupport.tvServiceRemaining.setTextColor(if (s.isServiceDue) ContextCompat.getColor(this@MainActivity, R.color.amber_accent) else currentTheme.accentColor)
                        binding.tabSupport.progressService.progress = s.servicePercent
                    }

                    if (s.isChainLubeDue) {
                        binding.tabSupport.tvChainRemaining.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_scanning))
                    } else {
                        binding.tabSupport.tvChainRemaining.setTextColor(currentTheme.accentColor)
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun observeFuelLog() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    fuelLogManager.stats.collectLatest { stats ->
                        updateHeroRangeDisplay()
                        updateBikeInvestmentDisplay()
                        if (stats.averageKmpl > 0.0) {
                            binding.tabFuel.tvAvgMileage.text = formatDecimal(stats.averageKmpl)
                            binding.tabFuel.tvAvgMileageUnit.visibility = View.VISIBLE
                        } else {
                            binding.tabFuel.tvAvgMileage.text = "--"
                            binding.tabFuel.tvAvgMileageUnit.visibility = View.VISIBLE
                        }
                        if (stats.costPerKm > 0.0) {
                            binding.tabFuel.tvCostPerKm.text = String.format(Locale.US, "Cost: %s%.2f / km", appSettings.currentCurrencySymbol, stats.costPerKm)
                        } else {
                            binding.tabFuel.tvCostPerKm.text = "Cost: -- / km"
                        }
                    }
                }
                launch {
                    fuelLogManager.entries.collectLatest { entries ->
                        if (binding.tabFuel.root.visibility == View.VISIBLE) {
                            fuelListThemeDirty = false
                            updateFuelList(entries)
                        } else {
                            fuelListThemeDirty = true
                        }
                    }
                }
            }
        }
    }

    private fun showFuelSortDialog() {
        val options = FuelSortOrder.entries
        val currentOrder = FuelSortOrder.fromKey(appSettings.fuelSortOrder)
        val currentIdx = options.indexOf(currentOrder).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.ObsidianAlertDialog)
            .setTitle("Sort Refueling History")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), currentIdx) { dialog, which ->
                val picked = options[which]
                appSettings.fuelSortOrder = picked.key
                updateFuelList(fuelLogManager.entries.value)
                provideHapticFeedback()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateFuelList(entries: List<com.pulsarnav.app.maintenance.FuelEntry>) {
        val sortOrder = FuelSortOrder.fromKey(appSettings.fuelSortOrder)
        binding.tabFuel.btnSortFuel.text = "Sort: ${sortOrder.shortLabel}"
        val sorted = FuelEconomyCalculator.sortEntries(entries, sortOrder)
        fuelListAdapter.submitList(sorted)
        val isEmpty = sorted.isEmpty()
        binding.tabFuel.tvFuelEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.tabFuel.rvFuelList.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }


    private fun showEditFuelEntryDialog(entry: com.pulsarnav.app.maintenance.FuelEntry) {
        var selectedTimestampMs = entry.timestampMs
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val tvInfo = TextView(this).apply {
            text = "Tap Refill Date to change when fuel was filled, Save to recalculate running economy, or Delete to remove:"
            setTextColor(currentTheme.textSecondaryColor)
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }
        layout.addView(tvInfo)

        val btnDatePicker = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Refill Date: ${formatFuelDialogDate(selectedTimestampMs)}"
            textSize = 12f
            setTextColor(currentTheme.accentColor)
            strokeColor = ColorStateList.valueOf(currentTheme.borderColor)
            setOnClickListener {
                provideHapticFeedback()
                showFuelDatePicker(selectedTimestampMs) { pickedMs ->
                    selectedTimestampMs = pickedMs
                    text = "Refill Date: ${formatFuelDialogDate(selectedTimestampMs)}"
                }
            }
        }
        layout.addView(btnDatePicker)

        val odoInput = createThemedEditText(
            hintText = "Odometer (km)",
            initialText = formatDecimal(entry.odometerKm),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val litersInput = createThemedEditText(
            hintText = "Fuel Litres",
            initialText = formatDecimal(entry.liters, 2),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val costInput = createThemedEditText(
            hintText = "Total Cost (${appSettings.currentCurrencySymbol})",
            initialText = String.format(Locale.US, "%.0f", entry.totalCostInr),
            inputTypeFlags = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )

        fun addField(label: String, field: View) {
            val tv = TextView(this).apply {
                text = label
                setTextColor(currentTheme.textTertiaryColor)
                textSize = 11f
                setPadding(0, 8, 0, 4)
            }
            layout.addView(tv)
            layout.addView(field)
        }

        addField("Odometer (km):", odoInput)
        addField("Fuel Litres:", litersInput)
        addField("Total Amount Paid (${appSettings.currentCurrencySymbol}):", costInput)

        val checkFullTank = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Full Tank refill (Calculates true mileage)"
            isChecked = entry.isFullTank
            setTextColor(currentTheme.textPrimaryColor)
            buttonTintList = ColorStateList.valueOf(currentTheme.accentColor)
            textSize = 12f
        }
        layout.addView(checkFullTank)

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Refuel Entry")
            .setView(layout)
            .setPositiveButton("Save Changes") { _, _ ->
                val odo = parseDecimal(odoInput.text)
                val lit = parseDecimal(litersInput.text)
                val cost = parseDecimal(costInput.text)
                if (odo != null && odo >= 0 && lit != null && lit > 0 && cost != null && cost >= 0) {
                    fuelLogManager.updateFuelEntry(
                        id = entry.id,
                        odometerKm = odo,
                        liters = lit,
                        totalCostInr = cost,
                        isFullTank = checkFullTank.isChecked,
                        timestampMs = selectedTimestampMs
                    )
                    recordSyncTime()
                    updateHeroRangeDisplay()
                    provideHapticFeedback()
                    showFeedback("Refuel entry updated!")
                } else {
                    showFeedback("Please enter valid fuel details", isAlert = true)
                }
            }
            .setNeutralButton("Delete Entry") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Fuel Entry?")
                    .setMessage("Are you sure you want to delete this refuel record (${entry.formattedDate})? This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        fuelLogManager.deleteFuelEntry(entry.id)
                        recordSyncTime()
                        updateHeroRangeDisplay()
                        provideHapticFeedback()
                        showFeedback("Refuel entry deleted")
                    }
                    .setNegativeButton("Keep Entry", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchEmergencyHospitals() {
        provideHapticFeedback()
        val uri = Uri.parse("geo:0,0?q=hospitals+emergency+near+me")
        val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        try {
            startActivity(mapIntent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/hospitals+emergency+near+me")))
            } catch (_: Exception) {
                showFeedback("Could not open Maps for nearest hospitals", isAlert = true)
            }
        }
    }


    private fun showEmergencyMedicalDialog() {
        provideHapticFeedback()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 10)
        }

        val bloodGroups = arrayOf("O+ Positive", "O- Negative", "A+ Positive", "A- Negative", "B+ Positive", "B- Negative", "AB+ Positive", "AB- Negative")
        var selectedBloodGroup = appSettings.riderBloodGroup
        val bloodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, bloodGroups)
            val idx = bloodGroups.indexOfFirst { it.equals(selectedBloodGroup, ignoreCase = true) }
            if (idx >= 0) setSelection(idx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedBloodGroup = bloodGroups[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        val iceNameInput = createThemedEditText(
            hintText = "ICE Contact Name / Relation",
            initialText = appSettings.emergencyContactName
        )
        val icePhoneInput = createThemedEditText(
            hintText = "ICE Phone Number",
            initialText = appSettings.emergencyContactPhone,
            inputTypeFlags = android.text.InputType.TYPE_CLASS_PHONE
        )
        val notesInput = createThemedEditText(
            hintText = "Medical Allergies / Conditions",
            initialText = appSettings.medicalNotes
        ).apply {
            minLines = 2
        }

        fun addField(label: String, field: View) {
            val tv = TextView(this).apply {
                text = label
                setTextColor(currentTheme.textTertiaryColor)
                textSize = 11f
                setPadding(0, 8, 0, 4)
            }
            layout.addView(tv)
            layout.addView(field)
        }

        addField("Rider Blood Group:", bloodSpinner)
        addField("Emergency Contact Person (ICE):", iceNameInput)
        addField("Emergency Contact Phone:", icePhoneInput)
        addField("Medical Allergies / Emergency Notes:", notesInput)

        val scrollView = ScrollView(this).apply { addView(layout) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Medical & Emergency Profile")
            .setView(scrollView)
            .setPositiveButton("Save Profile") { _, _ ->
                appSettings.riderBloodGroup = selectedBloodGroup
                appSettings.emergencyContactName = iceNameInput.text.toString().trim().ifBlank { "Family / ICE" }
                appSettings.emergencyContactPhone = icePhoneInput.text.toString().trim().ifBlank { "112" }
                appSettings.medicalNotes = notesInput.text.toString().trim().ifBlank { "No known allergies" }
                updateEmergencyHomeCard()
                recordSyncTime()
                provideHapticFeedback()
                showFeedback("Emergency medical profile saved!")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateEmergencyHomeCard() {
        val blood = if (appSettings.riderBloodGroup.isNotBlank() && appSettings.riderBloodGroup != "Not Specified") "${appSettings.riderBloodGroup}" else "Blood Group --"
        binding.tabCockpit.tvBloodGroupBadge.text = blood
        val icePhone = appSettings.emergencyContactPhone
        binding.tabCockpit.tvEmergencyContactName.text = if (icePhone.isNotBlank()) "ICE: $icePhone" else "ICE: Not Configured"
        val iceName = appSettings.emergencyContactName
        val med = appSettings.medicalNotes
        binding.tabCockpit.tvEmergencyMedicalNotes.text = when {
            iceName.isNotBlank() && med.isNotBlank() -> "$iceName • $med"
            iceName.isNotBlank() -> iceName
            med.isNotBlank() -> med
            else -> "Tap to configure ICE emergency contact & info"
        }
    }

    private fun getArrowForManeuver(m: Maneuver): String {
        return when (m) {
            Maneuver.TURN_LEFT, Maneuver.TURN_SHARP_LEFT, Maneuver.KEEP_LEFT, Maneuver.FORK_LEFT, Maneuver.RAMP_LEFT -> "⬅"
            Maneuver.TURN_RIGHT, Maneuver.TURN_SHARP_RIGHT, Maneuver.KEEP_RIGHT, Maneuver.FORK_RIGHT, Maneuver.RAMP_RIGHT -> "➡"
            Maneuver.TURN_SLIGHT_LEFT -> "↖"
            Maneuver.TURN_SLIGHT_RIGHT -> "↗"
            Maneuver.U_TURN_LEFT, Maneuver.U_TURN_RIGHT -> "↩"
            Maneuver.ROUNDABOUT_LEFT -> "↺"
            Maneuver.ROUNDABOUT_RIGHT -> "↻"
            Maneuver.DESTINATION_REACHED -> "ARRIVED"
            else -> "⬆"
        }
    }
}