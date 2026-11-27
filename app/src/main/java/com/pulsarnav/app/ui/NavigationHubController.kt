package com.pulsarnav.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsarnav.app.R
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.data.VehicleCatalog
import com.pulsarnav.app.databinding.BottomSheetNavigationHubBinding
import com.pulsarnav.app.service.BikeBleForegroundService
import com.pulsarnav.app.service.GoogleMapsNotificationListener
import com.pulsarnav.app.wifi.TftMapMathAndParser
import com.pulsarnav.app.wifi.TftMirrorStatus
import com.pulsarnav.app.wifi.TftNavigationMapEngine
import com.pulsarnav.app.wifi.TftPasswordGenerator
import com.pulsarnav.app.wifi.TftWifiMirrorManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class NavigationHubController(
    private val activity: AppCompatActivity,
    private val appSettings: AppSettings,
    private val isAccountTabVisible: StateFlow<Boolean>,
    private val isFullscreenShowing: () -> Boolean,
    private val themeProvider: () -> CockpitTheme,
    private val createThemedEditText: (String?, String?, Int?) -> EditText,
    private val isWifiEnabled: () -> Boolean,
    private val isLocationEnabled: () -> Boolean,
    private val promptEnableWifiForConfig: (onReady: () -> Unit) -> Unit,
    private val isNotificationAccessGranted: () -> Boolean,
    private val onLaunchGoogleMapsRide: () -> Unit,
    private val onHaptic: () -> Unit,
    private val onFeedback: (String, Boolean) -> Unit
) {

    fun showTftWifiConfigDialog() {
        val theme = themeProvider()
        val dp = { v: Int -> CockpitThemeApplier.dpToPx(activity, v) }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, dp(12), pad, 0)
        }

        if (!isWifiEnabled()) {
            val wifiWarningCard = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = dp(12)
                val padV = dp(8)
                setPadding(padH, padV, padH, padV)
                val amberColor = ContextCompat.getColor(activity, R.color.amber_accent)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(ColorUtils.setAlphaComponent(amberColor, 35))
                    setStroke(dp(1), amberColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(12)) }
            }
            val tvWarn = TextView(activity).apply {
                text = "Wi-Fi is turned off on your phone. Turn on Wi-Fi to connect and cast to your cluster."
                textSize = 12f
                setTextColor(ContextCompat.getColor(activity, R.color.amber_accent))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnEnable = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "Turn On"
                textSize = 12f
                setTextColor(ContextCompat.getColor(activity, R.color.amber_accent))
                setOnClickListener {
                    promptEnableWifiForConfig { showTftWifiConfigDialog() }
                }
            }
            wifiWarningCard.addView(tvWarn)
            wifiWarningCard.addView(btnEnable)
            container.addView(wifiWarningCard)
        }

        val infoText = TextView(activity).apply {
            text = "For Bajaj NS400Z / TFT clusters. Leave SSID blank to auto-use the connected PULSAR device name. Enter your 17-char VIN to derive the OEM SoftAP WPA2 password automatically."
            textSize = 12f
            setTextColor(theme.textSecondaryColor)
        }
        container.addView(infoText)

        val etSsid = createThemedEditText("Custom Wi-Fi SSID (optional, e.g. PULSAR1854)", null, null).apply {
            setText(appSettings.tftWifiSsid)
        }
        container.addView(etSsid)

        val etVin = createThemedEditText("17-char Bike VIN (for auto WPA2 password)", null, null).apply {
            setText(appSettings.tftWifiVin)
        }
        container.addView(etVin)

        val etPass = createThemedEditText("Or manual WPA2 passphrase (optional)", null, null).apply {
            setText(appSettings.tftWifiPassphrase)
        }
        container.addView(etPass)

        val tvPreview = TextView(activity).apply {
            textSize = 11f
            setTextColor(theme.accentColor)
            setPadding(0, dp(8), 0, 0)
        }
        fun refreshPreview() {
            val vin = etVin.text.toString().trim()
            val manual = etPass.text.toString().trim()
            tvPreview.text = when {
                manual.isNotEmpty() -> "Using custom WPA2 passphrase (${manual.length} chars)"
                vin.isNotEmpty() -> "Derived OEM PSK: ${TftPasswordGenerator.deriveSoftApPassword(vin)}"
                else -> "Tip: Joining PULSAR Wi-Fi manually in Android Settings works without VIN too."
            }
        }
        refreshPreview()
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshPreview() }
        }
        etVin.addTextChangedListener(watcher)
        etPass.addTextChangedListener(watcher)
        container.addView(tvPreview)

        val tvConsoleHeader = TextView(activity).apply {
            text = "Cluster Hardware Type (LCD vs TFT):"
            textSize = 12f
            setPadding(0, dp(12), 0, dp(4))
            setTextColor(theme.textPrimaryColor)
        }
        container.addView(tvConsoleHeader)

        val rgConsoleType = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val rbConsoleAuto = RadioButton(activity).apply {
            text = "Auto-Detect (GATT Hardware Check • Any Bajaj Model)"
            setTextColor(theme.textSecondaryColor)
            textSize = 12f
        }
        val rbConsoleTft = RadioButton(activity).apply {
            text = "Force Color TFT (NS400Z / NS200 TFT / N250 TFT)"
            setTextColor(theme.textSecondaryColor)
            textSize = 12f
        }
        val rbConsoleLcd = RadioButton(activity).apply {
            text = "Force Monochrome LCD (NS200 LCD / N250 LCD / Dominar)"
            setTextColor(theme.textSecondaryColor)
            textSize = 12f
        }
        rgConsoleType.addView(rbConsoleAuto)
        rgConsoleType.addView(rbConsoleTft)
        rgConsoleType.addView(rbConsoleLcd)
        when (appSettings.clusterConsoleType) {
            AppSettings.CLUSTER_CONSOLE_TFT -> rbConsoleTft.isChecked = true
            AppSettings.CLUSTER_CONSOLE_LCD -> rbConsoleLcd.isChecked = true
            else -> rbConsoleAuto.isChecked = true
        }
        container.addView(rgConsoleType)

        val tvModeHeader = TextView(activity).apply {
            text = "Mirror Mode (Screen-Off Safe):"
            textSize = 12f
            setPadding(0, dp(12), 0, dp(4))
            setTextColor(theme.textPrimaryColor)
        }
        container.addView(tvModeHeader)

        val rgMirrorMode = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val rbLiveMap = RadioButton(activity).apply {
            text = "PulsarNav Live Map (Google Maps TBT Sync • Full 800×480 • Lock-Proof)"
            setTextColor(theme.textSecondaryColor)
            textSize = 12f
        }
        val rbHud = RadioButton(activity).apply {
            text = "Cockpit HUD (Minimalist TBT Dashboard • Lock-Proof)"
            setTextColor(theme.textSecondaryColor)
            textSize = 12f
        }
        rgMirrorMode.addView(rbLiveMap)
        rgMirrorMode.addView(rbHud)
        if (appSettings.tftMirrorMode == AppSettings.TFT_MODE_LIVE_MAP) rbLiveMap.isChecked = true else rbHud.isChecked = true
        container.addView(rgMirrorMode)

        val tvCastHeader = TextView(activity).apply {
            text = "Cast App NS400Z Safe Viewport & Landscape:"
            textSize = 12f
            setPadding(0, dp(10), 0, dp(4))
            setTextColor(theme.textPrimaryColor)
        }
        container.addView(tvCastHeader)

        val rgCastLayout = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val rbCastFit = RadioButton(activity).apply {
            text = "Fit Viewport (Uncropped • Above Speedo)"
            setTextColor(theme.textSecondaryColor)
            textSize = 12f
        }
        val rbCastFill = RadioButton(activity).apply {
            text = "Fill Safe Viewport (Top-Anchored • Above Speedo)"
            setTextColor(theme.textSecondaryColor)
            textSize = 12f
        }
        val rbCastRot90 = RadioButton(activity).apply {
            text = "Auto-Panorama (Full 784px Wide • Above Speedo)"
            setTextColor(theme.textSecondaryColor)
            textSize = 12f
        }
        rgCastLayout.addView(rbCastFit)
        rgCastLayout.addView(rbCastFill)
        rgCastLayout.addView(rbCastRot90)
        when (appSettings.tftCastLayoutMode) {
            AppSettings.CAST_LAYOUT_FILL -> rbCastFill.isChecked = true
            AppSettings.CAST_LAYOUT_ROT90 -> rbCastRot90.isChecked = true
            else -> rbCastFit.isChecked = true
        }
        container.addView(rgCastLayout)

        val cbAutoOpenMaps = CheckBox(activity).apply {
            text = "Auto-open Google Maps when starting Live Cast"
            isChecked = appSettings.isTftCastAutoOpenMapsEnabled
            setTextColor(theme.textSecondaryColor)
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        container.addView(cbAutoOpenMaps)

        val cbAlignLeft = CheckBox(activity).apply {
            text = "Reposition Cast to Left (Split Cockpit • Anti-Tachometer)"
            isChecked = appSettings.isTftCastAlignLeftEnabled
            setTextColor(theme.textSecondaryColor)
            textSize = 12f
        }
        container.addView(cbAlignLeft)

        val canWriteRotation = Settings.System.canWrite(activity)
        val cbForceLandscape = CheckBox(activity).apply {
            text = if (canWriteRotation) {
                "Auto-lock phone to Landscape during Live Cast"
            } else {
                "Auto-lock phone to Landscape during Cast (tap to allow)"
            }
            isChecked = appSettings.isTftCastForceLandscapeEnabled
            setTextColor(theme.textSecondaryColor)
            textSize = 12f
            setOnClickListener {
                if (isChecked && !Settings.System.canWrite(activity)) {
                    try {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
        }
        container.addView(cbForceLandscape)

        val scrollWrapper = ScrollView(activity).apply { addView(container) }

        MaterialAlertDialogBuilder(activity)
            .setTitle("TFT Wi-Fi & Display Config")
            .setView(scrollWrapper)
            .setPositiveButton("Save") { _, _ ->
                appSettings.tftWifiSsid = etSsid.text.toString().trim()
                appSettings.tftWifiVin = etVin.text.toString().trim()
                appSettings.tftWifiPassphrase = etPass.text.toString().trim()
                appSettings.clusterConsoleType = when {
                    rbConsoleTft.isChecked -> AppSettings.CLUSTER_CONSOLE_TFT
                    rbConsoleLcd.isChecked -> AppSettings.CLUSTER_CONSOLE_LCD
                    else -> AppSettings.CLUSTER_CONSOLE_AUTO
                }
                val nextMode = if (rbLiveMap.isChecked) AppSettings.TFT_MODE_LIVE_MAP else AppSettings.TFT_MODE_HUD
                appSettings.tftMirrorMode = nextMode
                appSettings.tftCastLayoutMode = when {
                    rbCastFill.isChecked -> AppSettings.CAST_LAYOUT_FILL
                    rbCastRot90.isChecked -> AppSettings.CAST_LAYOUT_ROT90
                    else -> AppSettings.CAST_LAYOUT_FIT
                }
                appSettings.isTftCastAutoOpenMapsEnabled = cbAutoOpenMaps.isChecked
                appSettings.isTftCastAlignLeftEnabled = cbAlignLeft.isChecked
                appSettings.isTftCastForceLandscapeEnabled = cbForceLandscape.isChecked
                TftWifiMirrorManager.getInstance(activity).switchHeadlessModeIfActive(nextMode)
                if (!isWifiEnabled()) {
                    onFeedback("TFT settings saved • Remember to turn on Wi-Fi before casting", true)
                } else {
                    onFeedback("TFT settings saved", false)
                }
            }
            .setNeutralButton("Clear Wi-Fi") { _, _ ->
                appSettings.tftWifiSsid = ""
                appSettings.tftWifiVin = ""
                appSettings.tftWifiPassphrase = ""
                onFeedback("TFT Wi-Fi credentials cleared", false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showNavigationHubBottomSheet(initialInput: String? = null, autoStart: Boolean = false) {
        val mapEngine = TftNavigationMapEngine.getInstance(activity)
        val dialog = BottomSheetDialog(activity, R.style.ObsidianBottomSheetDialog)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val sheetBinding = BottomSheetNavigationHubBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(sheetBinding.root)

        val bikeName = VehicleCatalog.getLocalizedBikeName(
            appSettings.getActiveBikeProfile(),
            appSettings.getActiveCountryProfile()
        )
        sheetBinding.tvNavHubSubtitle.text = "$bikeName 800×480 Live Map & Cluster TBT"

        sheetBinding.ivDismissNavHub.setOnClickListener { dialog.dismiss() }

        sheetBinding.etNavDestination.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                sheetBinding.ivNavClearInput.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        sheetBinding.ivNavClearInput.setOnClickListener {
            sheetBinding.etNavDestination.setText("")
        }

        val currentState = mapEngine.uiState.value
        val isAlreadyRunning = currentState.routeActive || mapEngine.hasPendingRoute()
        if (isAlreadyRunning) {
            sheetBinding.cardNavPreview.visibility = View.VISIBLE
            sheetBinding.tvNavPreviewTitle.text = currentState.destinationLabel.ifBlank { "Active Route" }
            sheetBinding.tvNavPreviewStats.text = listOfNotNull(
                currentState.remainingDistanceText.ifBlank { null },
                currentState.remainingEtaText.ifBlank { null }
            ).joinToString(" • ").ifBlank { "Active on TFT Cluster" }
            sheetBinding.btnStopTftRoute.visibility = View.VISIBLE
        }

        fun performStartRoute(targetInput: String) {
            val q = targetInput.trim()
            if (q.isBlank()) {
                onFeedback("Please enter a destination or address", true)
                return
            }
            sheetBinding.cardNavPreview.visibility = View.VISIBLE
            sheetBinding.pbNavResolving.visibility = View.VISIBLE
            sheetBinding.tvNavPreviewTitle.text = q
            sheetBinding.tvNavPreviewStats.text = "Resolving coordinates over mobile data..."
            sheetBinding.btnStartTftRoute.isEnabled = false
            sheetBinding.btnStopTftRoute.visibility = View.GONE

            val wifiMgr = TftWifiMirrorManager.getInstance(activity)
            BikeBleForegroundService.startForLocationNavigation(activity)
            if (wifiMgr.mirrorState.value.status == TftMirrorStatus.IDLE) {
                appSettings.tftMirrorMode = AppSettings.TFT_MODE_LIVE_MAP
                if (isAccountTabVisible.value || isFullscreenShowing()) {
                    wifiMgr.startLocalDebugPreview()
                }
            }

            mapEngine.startRouteFromInput(q) { ok, message ->
                activity.runOnUiThread {
                    sheetBinding.pbNavResolving.visibility = View.GONE
                    sheetBinding.btnStartTftRoute.isEnabled = true
                    if (ok) {
                        val state = mapEngine.uiState.value
                        sheetBinding.tvNavPreviewTitle.text = state.destinationLabel.ifBlank { q }
                        sheetBinding.tvNavPreviewStats.text = listOfNotNull(
                            state.remainingDistanceText.ifBlank { null },
                            state.remainingEtaText.ifBlank { null }
                        ).joinToString(" • ").ifBlank { "Route ready" }
                        sheetBinding.btnStopTftRoute.visibility = View.VISIBLE
                        if (!isLocationEnabled()) {
                            onFeedback("GPS is OFF — $message (turn ON Location for live tracking)", true)
                        } else {
                            onFeedback(message, false)
                        }
                        if (autoStart) {
                            activity.lifecycleScope.launch {
                                delay(1200)
                                if (dialog.isShowing) dialog.dismiss()
                            }
                        }
                    } else {
                        sheetBinding.tvNavPreviewStats.text = message
                        onFeedback(message, true)
                    }
                }
            }
        }

        fun refreshRecentList() {
            val dp = { v: Int -> CockpitThemeApplier.dpToPx(activity, v) }
            sheetBinding.layoutRecentList.removeAllViews()
            val recents = TftMapMathAndParser.parseRecentDests(appSettings.navV2RecentDestsJson)
            if (recents.isEmpty()) {
                val emptyTv = TextView(activity).apply {
                    text = "No recent trips yet. Search or share from Google Maps."
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                    setPadding(0, dp(4), 0, dp(8))
                }
                sheetBinding.layoutRecentList.addView(emptyTv)
            } else {
                for (dest in recents.take(5)) {
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(44)
                        )
                        isClickable = true
                        isFocusable = true
                        setBackgroundResource(R.drawable.rounded_box)
                        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.surface_card))
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        setOnClickListener {
                            sheetBinding.etNavDestination.setText(dest.name)
                            sheetBinding.etNavDestination.setSelection(dest.name.length)
                            performStartRoute(dest.name)
                        }
                    }
                    val icon = ImageView(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                        setImageResource(R.drawable.ic_trips)
                        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.wine_red_light))
                    }
                    val label = TextView(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = dp(10)
                        }
                        text = dest.name
                        textSize = 13f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                    }
                    val arrow = ImageView(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                        setImageResource(R.drawable.ic_navigation)
                        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.text_secondary))
                        rotation = 45f
                    }
                    row.addView(icon)
                    row.addView(label)
                    row.addView(arrow)
                    sheetBinding.layoutRecentList.addView(row)
                    val space = View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
                    }
                    sheetBinding.layoutRecentList.addView(space)
                }
            }
        }
        refreshRecentList()

        sheetBinding.chipNavHome.setOnClickListener {
            val addr = appSettings.homeAddress.ifBlank { "Home" }
            sheetBinding.etNavDestination.setText(addr)
            sheetBinding.etNavDestination.setSelection(sheetBinding.etNavDestination.text.length)
            if (addr != "Home") performStartRoute(addr)
        }
        sheetBinding.chipNavWork.setOnClickListener {
            val addr = appSettings.workAddress.ifBlank { "Work" }
            sheetBinding.etNavDestination.setText(addr)
            sheetBinding.etNavDestination.setSelection(sheetBinding.etNavDestination.text.length)
            if (addr != "Work") performStartRoute(addr)
        }
        sheetBinding.chipNavPetrol.setOnClickListener {
            sheetBinding.etNavDestination.setText("Petrol Pump")
            sheetBinding.etNavDestination.setSelection(sheetBinding.etNavDestination.text.length)
            performStartRoute("Petrol Pump")
        }
        sheetBinding.chipNavHospital.setOnClickListener {
            sheetBinding.etNavDestination.setText("Hospital")
            sheetBinding.etNavDestination.setSelection(sheetBinding.etNavDestination.text.length)
            performStartRoute("Hospital")
        }
        sheetBinding.chipNavService.setOnClickListener {
            sheetBinding.etNavDestination.setText("Bajaj Service Center")
            sheetBinding.etNavDestination.setSelection(sheetBinding.etNavDestination.text.length)
            performStartRoute("Bajaj Service Center")
        }

        sheetBinding.btnStartTftRoute.setOnClickListener {
            onHaptic()
            performStartRoute(sheetBinding.etNavDestination.text.toString())
        }

        sheetBinding.etNavDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                performStartRoute(sheetBinding.etNavDestination.text.toString())
                true
            } else false
        }

        sheetBinding.btnLaunchGoogleMaps.setOnClickListener {
            val q = sheetBinding.etNavDestination.text.toString().trim()
            if (q.isNotBlank()) {
                if (!isLocationEnabled()) {
                    onFeedback("GPS is OFF — turn ON Location for live navigation tracking", true)
                }
                val gmmIntentUri = Uri.parse("google.navigation:q=" + Uri.encode(q))
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                    setPackage("com.google.android.apps.maps")
                }
                try {
                    activity.startActivity(mapIntent)
                    dialog.dismiss()
                } catch (_: Exception) {
                    onLaunchGoogleMapsRide()
                }
            } else {
                onLaunchGoogleMapsRide()
                dialog.dismiss()
            }
        }

        sheetBinding.btnStopTftRoute.setOnClickListener {
            mapEngine.clearActiveRoute()
            sheetBinding.cardNavPreview.visibility = View.GONE
            sheetBinding.btnStopTftRoute.visibility = View.GONE
            onFeedback("PulsarNav Live Map route cleared", false)
        }

        if (!initialInput.isNullOrBlank()) {
            sheetBinding.etNavDestination.setText(initialInput)
            sheetBinding.etNavDestination.setSelection(initialInput.length)
            if (autoStart) {
                performStartRoute(initialInput)
            }
        }

        dialog.show()
    }

    fun showMapsTbtTroubleshooterDialog() {
        val granted = isNotificationAccessGranted()
        val diag = GoogleMapsNotificationListener.diagnostics.value
        val activeRoute = TftNavigationMapEngine.getInstance(activity).uiState.value

        val statusLines = buildString {
            append("• Notification Access: ${if (granted) "GRANTED" else "MISSING"}\n")
            append("• Listener Service: ${if (diag.isConnected) "CONNECTED" else "DISCONNECTED"}\n")
            append("• Maps Notif in Shade: ${if (diag.mapsActiveInShade) "DETECTED" else "NOT FOUND"}\n")
            if (diag.lastNavSummary.isNotBlank()) {
                append("• Last TBT: ${diag.lastNavSummary}\n")
            }
            if (activeRoute.routeActive) {
                append("• Built-in Live Map Route: ${activeRoute.destinationLabel} (${activeRoute.remainingDistanceText})\n")
            }
            append("\nIf Google Maps is in mini PiP window with NO notification in the top shade (common on ColorOS / OxygenOS / RealmeUI):\n")
            append("1. Tap \"Maps Notifs\" below and turn ON Notifications -> \"Navigation\".\n")
            append("2. Tap \"Maps Battery\" and set to Unrestricted / Allow background activity.\n")
            append("3. Or inside Google Maps, tap Share -> PulsarNav to route directly on our 800×480 Live Map (works locked & needs zero Maps notification).")
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("Google Maps TBT Diagnostics")
            .setMessage(statusLines)
            .setPositiveButton("Maps Notifs") { _, _ ->
                openAppNotificationSettings("com.google.android.apps.maps")
            }
            .setNeutralButton("Maps Battery") { _, _ ->
                openAppDetailsSettings("com.google.android.apps.maps")
            }
            .setNegativeButton("Route in App") { _, _ ->
                showNavigationHubBottomSheet()
            }
            .show()
    }

    private fun openAppNotificationSettings(targetPackage: String) {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, targetPackage)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsSettings(targetPackage)
        }
    }

    private fun openAppDetailsSettings(targetPackage: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$targetPackage")
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            onFeedback("Unable to open settings: ${e.message}", true)
        }
    }
}
