package com.pulsarnav.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.widget.EditText
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.databinding.ActivityMainBinding
import java.util.Locale

object CockpitThemeApplier {

    fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    fun createThemedInputDrawable(context: Context, theme: CockpitTheme): Drawable {
        val fillColor = if (theme.isLight) Color.parseColor("#FFFFFF") else Color.parseColor("#1C1B20")
        val normalBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(context, 12).toFloat()
            setColor(fillColor)
            setStroke(dpToPx(context, 1), theme.cardBorderColor)
        }
        val focusedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(context, 12).toFloat()
            setColor(fillColor)
            setStroke(dpToPx(context, 2), theme.accentColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedBg)
            addState(intArrayOf(), normalBg)
        }
    }

    fun applyThemeToEditText(context: Context, editText: EditText, theme: CockpitTheme) {
        editText.setTextColor(theme.textPrimaryColor)
        editText.setHintTextColor(theme.textTertiaryColor)
        editText.highlightColor = ColorUtils.setAlphaComponent(theme.accentColor, 60)
        editText.background = createThemedInputDrawable(context, theme)
        editText.setPadding(
            dpToPx(context, 14),
            dpToPx(context, 12),
            dpToPx(context, 14),
            dpToPx(context, 12)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            editText.textCursorDrawable = null
            editText.textSelectHandle?.setTint(theme.accentColor)
            editText.textSelectHandleLeft?.setTint(theme.accentColor)
            editText.textSelectHandleRight?.setTint(theme.accentColor)
        }
    }

    fun applyRoundedSurfaceBox(
        context: Context,
        view: View,
        fillColor: Int,
        strokeColor: Int,
        radiusDp: Int = 10
    ) {
        val pl = view.paddingLeft
        val pt = view.paddingTop
        val pr = view.paddingRight
        val pb = view.paddingBottom
        view.backgroundTintList = null
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(context, radiusDp).toFloat()
            setColor(fillColor)
            setStroke(dpToPx(context, 1), strokeColor)
        }
        view.setPadding(pl, pt, pr, pb)
    }

    private fun styleSubtleOutlinedButton(btn: MaterialButton, theme: CockpitTheme) {
        btn.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        btn.strokeColor = ColorStateList.valueOf(theme.borderColor)
        btn.setTextColor(theme.accentColor)
    }

    private fun stylePrimaryFilledButton(btn: MaterialButton, theme: CockpitTheme) {
        btn.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
        btn.setTextColor(theme.onPrimaryColor)
    }

    fun apply(
        context: Context,
        binding: ActivityMainBinding,
        theme: CockpitTheme,
        themeKey: String,
        appSettings: AppSettings,
        isBleConnected: Boolean,
        setCustomHexSafely: (String) -> Unit,
        onUpdateDebugFilterChips: () -> Unit
    ) {
        val cockpit = binding.tabCockpit
        val fuel = binding.tabFuel
        val support = binding.tabSupport
        val account = binding.tabAccount

        // 1. Root Canvases, ScrollViews & Dividers
        binding.rootLayout.setBackgroundColor(theme.backgroundColor)
        binding.layoutTopBar.setBackgroundColor(theme.surfaceColor)
        binding.layoutContentFrame.setBackgroundColor(theme.backgroundColor)
        cockpit.root.setBackgroundColor(theme.backgroundColor)
        fuel.root.setBackgroundColor(theme.backgroundColor)
        support.root.setBackgroundColor(theme.backgroundColor)
        account.root.setBackgroundColor(theme.backgroundColor)
        binding.viewTopDivider.setBackgroundColor(theme.cardBorderColor)
        binding.tvTopBarTitle.setTextColor(theme.textPrimaryColor)

        // 2. Appearance Mode Buttons
        val activeBtn = if (theme.isLight) account.btnModeLight else account.btnModeDark
        val inactiveBtn = if (theme.isLight) account.btnModeDark else account.btnModeLight
        activeBtn.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        activeBtn.strokeColor = ColorStateList.valueOf(theme.accentColor)
        activeBtn.setTextColor(theme.accentColor)
        activeBtn.strokeWidth = dpToPx(context, 2)

        inactiveBtn.backgroundTintList = ColorStateList.valueOf(theme.cardColor)
        inactiveBtn.strokeColor = ColorStateList.valueOf(theme.cardBorderColor)
        inactiveBtn.setTextColor(theme.textSecondaryColor)
        inactiveBtn.strokeWidth = dpToPx(context, 1)

        // 3. Themed Icons across all Tabs
        val allThemedIcons = listOf(
            cockpit.ivIconHome,
            cockpit.ivIconWork,
            fuel.ivFuelHeaderIcon,
            support.ivRsaIcon,
            support.ivManualIcon,
            support.ivIssuesIcon,
            account.ivProfileAvatar,
            account.ivMyBikeIcon,
            account.ivGloveboxIcon,
            account.ivVaultQrIcon,
            account.ivVaultBackupIcon,
            account.ivNavAppPrefIcon,
            account.ivInterceptorIcon,
            account.ivBatteryOptIcon,
            account.ivSmsRejectIcon,
            account.ivTelephonyPermsIcon
        )
        val accentTint = ColorStateList.valueOf(theme.accentColor)
        for (icon in allThemedIcons) {
            icon.imageTintList = accentTint
        }

        // 4. Content Cards across all Tabs
        val contentCards: List<MaterialCardView> = listOf(
            cockpit.cardHeroRange,
            cockpit.cardConnection,
            cockpit.cardTftWifiMirror,
            cockpit.cardEmergencyHome,
            cockpit.cardQuickHome,
            cockpit.cardQuickWork,
            fuel.cardNearbyPumps,
            fuel.cardAddFuel,
            fuel.cardFuelBreakdown,
            fuel.cardFuelLog,
            support.cardServiceHistory,
            support.cardChainLube,
            support.cardService,
            support.cardSupportOptions,
            support.cardBikeIssues,
            account.cardProfile,
            account.cardMyBike,
            account.cardOwnership,
            account.cardDataVault,
            account.cardNavPreference,
            account.cardHudMirror,
            account.cardCallerId,
            account.labsTftDebug.cardTftScreenDebugger,
            account.labsTftDebug.cardDebugLogConsole,
            account.cardToggles,
            account.cardQuickDestinations,
            account.cardAboutLegal,
            account.cardCustomRgb
        )
        for (card in contentCards) {
            card.setCardBackgroundColor(theme.cardColor)
            card.strokeColor = theme.cardBorderColor
        }

        // 5. Theme Selection Cards
        val themeCards = listOf(
            account.cardThemeWineRed to (themeKey == AppSettings.THEME_WINE_RED),
            account.cardThemeAmoled to (themeKey == AppSettings.THEME_AMOLED),
            account.cardThemeStealth to (themeKey == AppSettings.THEME_STEALTH),
            account.cardThemeAmber to (themeKey == AppSettings.THEME_AMBER),
            account.cardThemeMint to (themeKey == AppSettings.THEME_MINT),
            account.cardThemeBlue to (themeKey == AppSettings.THEME_BLUE),
            account.cardThemeCustom to (themeKey == AppSettings.THEME_CUSTOM)
        )
        for ((card, isSelected) in themeCards) {
            card.setCardBackgroundColor(theme.cardColor)
            card.strokeColor = if (isSelected) theme.accentColor else theme.cardBorderColor
            card.strokeWidth = dpToPx(context, if (isSelected) 2 else 1)
        }
        account.tvThemeAmoledTitle.text = if (theme.isLight) "Pearl Minimal" else "AMOLED Black"
        account.viewThemeAmoledDot.backgroundTintList = null
        account.viewThemeAmoledDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (theme.isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#000000"))
            setStroke(
                dpToPx(context, 2),
                if (theme.isLight) Color.parseColor("#94A3B8") else Color.parseColor("#52525B")
            )
        }

        // Custom RGB Swatches & Highlight
        val customColor = appSettings.getCustomAccentColor()
        account.viewCustomThemeDot.backgroundTintList = ColorStateList.valueOf(customColor)
        account.viewRgbLivePreview.backgroundTintList = ColorStateList.valueOf(customColor)
        account.etCustomHex.background = createThemedInputDrawable(context, theme)
        account.etCustomHex.setTextColor(theme.textPrimaryColor)
        account.etCustomHex.setHintTextColor(theme.textTertiaryColor)
        if (!account.etCustomHex.isFocused) {
            val hex = String.format(
                Locale.US,
                "#%02X%02X%02X",
                appSettings.customAccentRed,
                appSettings.customAccentGreen,
                appSettings.customAccentBlue
            )
            if (account.etCustomHex.text.toString() != hex) {
                setCustomHexSafely(hex)
            }
        }
        account.btnApplyHex.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
        account.btnApplyHex.setTextColor(theme.onPrimaryColor)
        if (themeKey == AppSettings.THEME_CUSTOM) {
            account.cardCustomRgb.strokeColor = theme.accentColor
            account.cardCustomRgb.strokeWidth = dpToPx(context, 2)
        } else {
            account.cardCustomRgb.strokeColor = theme.cardBorderColor
            account.cardCustomRgb.strokeWidth = dpToPx(context, 1)
        }

        // Quick Palette Chips
        val presetColors = if (theme.isLight) {
            listOf(
                account.chipPresetPurple to ("#EEF2FF" to "#4F46E5"),
                account.chipPresetStrava to ("#FFF7ED" to "#EA580C"),
                account.chipPresetCyan to ("#ECFEFF" to "#0891B2"),
                account.chipPresetPink to ("#FDF2F8" to "#DB2777"),
                account.chipPresetLime to ("#F7FEE7" to "#4D7C0F")
            )
        } else {
            listOf(
                account.chipPresetPurple to ("#242138" to "#828FFF"),
                account.chipPresetStrava to ("#331A12" to "#FC4C02"),
                account.chipPresetCyan to ("#122A36" to "#06B6D4"),
                account.chipPresetPink to ("#361525" to "#EC4899"),
                account.chipPresetLime to ("#243312" to "#84CC16")
            )
        }
        for ((chip, colors) in presetColors) {
            chip.backgroundTintList = ColorStateList.valueOf(Color.parseColor(colors.first))
            chip.setTextColor(Color.parseColor(colors.second))
        }

        // 6. Cockpit Tab Elements
        styleSubtleOutlinedButton(binding.btnTopSos, theme)
        cockpit.tvProjectedRange.setTextColor(theme.accentColor)
        cockpit.tvDteSubtext.setTextColor(theme.accentColor)
        styleSubtleOutlinedButton(cockpit.btnRecalibrateCluster, theme)
        cockpit.progressFuelLevel.setIndicatorColor(theme.primaryColor)
        cockpit.progressFuelLevel.trackColor = theme.cardBorderColor

        cockpit.tvQuickHomeTitle.setTextColor(theme.accentColor)
        cockpit.tvQuickWorkTitle.setTextColor(theme.accentColor)
        cockpit.cardQuickHome.strokeColor = theme.borderColor
        cockpit.cardQuickWork.strokeColor = theme.borderColor

        cockpit.layoutSearchBar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(context, 14).toFloat()
            setColor(if (theme.isLight) Color.parseColor("#FFFFFF") else Color.parseColor("#222128"))
            setStroke(dpToPx(context, 1), theme.cardBorderColor)
        }
        cockpit.tvConnectionStatus.setTextColor(theme.textPrimaryColor)
        cockpit.tvSubStatus.setTextColor(theme.textSecondaryColor)

        cockpit.cardActiveTftNavigation.setCardBackgroundColor(theme.cardColor)
        cockpit.cardActiveTftNavigation.strokeColor = theme.primaryColor
        cockpit.tvActiveNavNextTurn.setTextColor(theme.textPrimaryColor)
        cockpit.tvActiveNavTripSummary.setTextColor(theme.textSecondaryColor)
        cockpit.tvActiveNavStatusBadge.setTextColor(theme.accentColor)
        cockpit.tvActiveNavStatusBadge.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        cockpit.btnActiveNavReroute.setTextColor(theme.textSecondaryColor)
        cockpit.btnActiveNavStop.setTextColor(theme.primaryColor)

        cockpit.btnEmergencyHospitals.strokeColor = ColorStateList.valueOf(theme.borderColor)
        styleSubtleOutlinedButton(cockpit.btnEmergencyAmbulance, theme)
        cockpit.tvBloodGroupBadge.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        cockpit.tvBloodGroupBadge.setTextColor(theme.accentColor)
        cockpit.tvEditMedicalLink.setTextColor(theme.accentColor)

        stylePrimaryFilledButton(cockpit.btnEnableNotificationAccess, theme)
        cockpit.cardNotificationPermissionBanner.strokeColor = theme.cardBorderColor

        if (!isBleConnected) {
            stylePrimaryFilledButton(cockpit.btnScan, theme)
        } else {
            cockpit.btnScan.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
            cockpit.btnScan.setTextColor(theme.accentColor)
        }

        styleSubtleOutlinedButton(cockpit.btnConfigureTftWifi, theme)
        stylePrimaryFilledButton(cockpit.btnToggleTftHudMirror, theme)
        styleSubtleOutlinedButton(cockpit.btnCastLiveScreen, theme)

        cockpit.tvHomeOdometer.setTextColor(theme.accentColor)
        cockpit.tvHomeMileage.setTextColor(theme.accentColor)

        // 7. Fuel Tab Elements
        stylePrimaryFilledButton(fuel.btnFindPumps, theme)
        fuel.tvNearbyPumpsSubtext.setTextColor(theme.accentColor)
        fuel.tvTankSpecs.setTextColor(theme.textPrimaryColor)
        styleSubtleOutlinedButton(fuel.btnFuelAction, theme)
        styleSubtleOutlinedButton(fuel.btnAddFuel, theme)
        fuel.tvTotalInvestment.setTextColor(theme.accentColor)
        fuel.progressFuelInvestment.setIndicatorColor(theme.primaryColor)
        fuel.progressFuelInvestment.trackColor = theme.cardBorderColor

        fuel.rowFuelPriceSetting.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(context, 12).toFloat()
            setColor(if (theme.isLight) Color.parseColor("#F1F3F7") else Color.parseColor("#1C1B20"))
            setStroke(dpToPx(context, 1), theme.cardBorderColor)
        }
        fuel.tvFuelPriceTitle.setTextColor(theme.textPrimaryColor)
        fuel.tvFuelPriceCountrySubtitle.setTextColor(theme.textSecondaryColor)
        fuel.tvEditFuelPriceAction.setTextColor(theme.accentColor)

        // 8. Support Tab Elements
        styleSubtleOutlinedButton(support.tvServiceHistoryAction, theme)
        support.tvSupportTotalInvested.setTextColor(theme.accentColor)
        support.tvSupportServiceRemaining.setTextColor(theme.accentColor)
        support.progressSupportService.setIndicatorColor(theme.primaryColor)
        support.progressSupportService.trackColor = theme.cardBorderColor
        support.tvIssuesAction.setTextColor(theme.accentColor)
        support.tvServiceRemaining.setTextColor(theme.accentColor)
        support.progressService.setIndicatorColor(theme.primaryColor)
        support.progressService.trackColor = theme.cardBorderColor
        styleSubtleOutlinedButton(support.btnMarkServiced, theme)
        styleSubtleOutlinedButton(support.btnSetServiceTarget, theme)
        support.tvChainRemaining.setTextColor(theme.accentColor)
        support.progressChain.setIndicatorColor(theme.primaryColor)
        support.progressChain.trackColor = theme.cardBorderColor
        styleSubtleOutlinedButton(support.btnMarkChainLubed, theme)

        // 9. Account & Labs Tab Elements
        account.tvAccountOdometer.setTextColor(theme.accentColor)
        styleSubtleOutlinedButton(account.btnEditOdo, theme)
        account.btnEditBikeInfo.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        account.btnEditBikeInfo.setTextColor(theme.accentColor)
        account.btnSetupBikeModel.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        account.btnSetupBikeModel.setTextColor(theme.accentColor)
        account.tvDocumentsBadge.setTextColor(theme.accentColor)
        account.tvVaultQrAction.setTextColor(theme.accentColor)
        account.tvVaultBackupAction.setTextColor(theme.accentColor)

        listOf(
            account.btnConfigHome,
            account.btnConfigWork,
            account.btnConfigFavPlace,
            account.btnEditSmsRejectMsg
        ).forEach { btn ->
            styleSubtleOutlinedButton(btn, theme)
            btn.strokeWidth = dpToPx(context, 1)
        }

        account.layoutManeuverIcon.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        account.tvManeuverIcon.setTextColor(theme.accentColor)
        account.tvDistance.setTextColor(theme.accentColor)
        account.tvMapsTbtFixLink.setTextColor(theme.accentColor)
        account.tvEtaBadge.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        account.tvEtaBadge.setTextColor(theme.accentColor)

        account.tvMediaBanner.setTextColor(theme.accentColor)
        account.tvCallerStatus.setTextColor(theme.accentColor)
        account.tvWhatsAppAlertBanner.setTextColor(theme.accentColor)
        account.tvClusterActionStatus.setTextColor(theme.accentColor)

        listOf(
            account.tvPacketHex,
            account.tvPacketDecoded,
            account.tvWhatsAppAlertBanner,
            account.tvClusterActionStatus,
            account.labsTftDebug.tvBleDiagBanner,
            account.labsTftDebug.tvTftDbgTouchInfo,
            account.labsTftDebug.scrollDebugLogs
        ).forEach { applyRoundedSurfaceBox(context, it, theme.surfaceColor, theme.cardBorderColor, 10) }

        account.tvPacketHex.setTextColor(theme.textSecondaryColor)
        account.tvPacketDecoded.setTextColor(theme.textSecondaryColor)
        account.labsTftDebug.tvBleDiagBanner.setTextColor(theme.textPrimaryColor)
        account.labsTftDebug.tvTftDbgTouchInfo.setTextColor(theme.textSecondaryColor)
        account.labsTftDebug.tvTftDbgMetrics.setTextColor(theme.textSecondaryColor)
        account.labsTftDebug.tvDebugLogStats.setTextColor(theme.textSecondaryColor)
        account.labsTftDebug.tvDebugLogOutput.setTextColor(theme.textSecondaryColor)
        applyRoundedSurfaceBox(context, account.tvMissedCallBadge, theme.subtleColor, theme.borderColor, 16)
        account.tvMissedCallBadge.setTextColor(theme.accentColor)
        applyRoundedSurfaceBox(context, account.labsTftDebug.tvTftDbgStatus, theme.subtleColor, theme.borderColor, 16)
        if (account.labsTftDebug.tvTftDbgStatus.text == "IDLE") {
            account.labsTftDebug.tvTftDbgStatus.setTextColor(theme.textSecondaryColor)
        }

        listOf(
            account.btnTestLeft, account.btnTestStraight, account.btnTestRight, account.btnTestRoundabout,
            account.btnSimulateCall, account.btnSimulateWhatsApp, account.btnSimulateSms, account.btnSimulateMissedCall,
            account.btnSyncPhonebookCalls, account.btnGrantTelephonyPerms,
            account.labsTftDebug.btnTftDbgFullscreen, account.labsTftDebug.btnTftDbgNavV2Test,
            account.labsTftDebug.btnTftDbgRecenter, account.labsTftDebug.btnTftDbgDaylight,
            account.labsTftDebug.btnTftDbgMotion, account.labsTftDebug.btnTftDbgHitboxes,
            account.labsTftDebug.btnLogPauseResume, account.labsTftDebug.btnLogCopy,
            account.labsTftDebug.btnLogShare, account.labsTftDebug.btnLogClear
        ).forEach { styleSubtleOutlinedButton(it, theme) }

        account.labsTftDebug.pbTftJpegBudget.progressTintList = ColorStateList.valueOf(theme.primaryColor)
        stylePrimaryFilledButton(account.labsTftDebug.btnTftDbgTogglePreview, theme)
        onUpdateDebugFilterChips()
        account.btnStopSimulation.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
        account.btnStopSimulation.strokeColor = ColorStateList.valueOf(theme.borderColor)
        account.btnStopSimulation.setTextColor(theme.textSecondaryColor)

        // Switches
        val switchStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val trackTintList = ColorStateList(switchStates, intArrayOf(theme.primaryColor, theme.cardBorderColor))
        val thumbTintList = ColorStateList(switchStates, intArrayOf(theme.onPrimaryColor, theme.textTertiaryColor))
        listOf(
            account.switchAutoConnect,
            account.switchTwoWheeler,
            account.switchAutoPark,
            account.switchRideTracking,
            account.switchMediaControl,
            account.switchWhatsAppAlerts,
            account.switchTelemetry,
            account.switchSkipHeadunitNav,
            account.switchSmsReject
        ).forEach { sw ->
            sw.trackTintList = trackTintList
            sw.thumbTintList = thumbTintList
        }

        // Bottom Navigation Bar
        binding.bottomNav.setBackgroundColor(theme.surfaceColor)
        binding.bottomNav.itemActiveIndicatorColor = ColorStateList.valueOf(theme.subtleColor)
        val navColorList = ColorStateList(switchStates, intArrayOf(theme.accentColor, theme.textTertiaryColor))
        binding.bottomNav.itemIconTintList = navColorList
        binding.bottomNav.itemTextColor = navColorList
    }
}
