package com.pulsarnav.app.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.pulsarnav.app.BuildConfig
import com.pulsarnav.app.R
import com.pulsarnav.app.ble.BajajBleManager
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.databinding.IncludeLabsTftAndLogsBinding
import com.pulsarnav.app.debug.DebugFilter
import com.pulsarnav.app.debug.DebugLogEntry
import com.pulsarnav.app.debug.DebugLogRepository
import com.pulsarnav.app.service.GoogleMapsNotificationListener
import com.pulsarnav.app.telephony.CallManager
import com.pulsarnav.app.wifi.TftDebugFrame
import com.pulsarnav.app.wifi.TftMirrorState
import com.pulsarnav.app.wifi.TftMirrorStatus
import com.pulsarnav.app.wifi.TftWifiMirrorManager
import com.pulsarnav.app.wifi.TftWifiProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

class TftLabsController(
    private val activity: AppCompatActivity,
    private val dbg: IncludeLabsTftAndLogsBinding,
    private val appSettings: AppSettings,
    private val bleManager: BajajBleManager,
    private val callManager: CallManager,
    private val isAccountTabVisible: StateFlow<Boolean>,
    private val themeProvider: () -> CockpitTheme,
    private val onHaptic: () -> Unit,
    private val onFeedback: (String, Boolean) -> Unit
) {

    private var activeDebugFilter: DebugFilter = DebugFilter.ALL
    private var isDebugLogPaused: Boolean = false
    private var fullscreenTftDialog: Dialog? = null
    private var fullscreenTftImageView: ImageView? = null
    private var fullscreenTftMetricsView: TextView? = null
    private var fullscreenTftTouchView: TextView? = null
    private var renderLogJob: Job? = null
    private var lastRenderedLogText: String? = null
    private val previewDecodeLock = Any()
    private val previewBitmapSlots = arrayOfNulls<Bitmap>(3)
    private var previewBitmapSlotIdx = 0
    private var lastDecodedFrameIndex = -1L
    private val fullscreenBitmapSlots = arrayOfNulls<Bitmap>(3)
    private var fullscreenBitmapSlotIdx = 0
    private var lastFullscreenFrameIndex = -1L

    fun isFullscreenShowing(): Boolean = fullscreenTftDialog?.isShowing == true

    fun hasRenderedLogsOnce(): Boolean = lastRenderedLogText != null

    fun renderCurrentLogsForce() {
        renderDebugLogsUi(DebugLogRepository.entries.value, forceRender = true)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun setup() {
        val wifiMgr = TftWifiMirrorManager.getInstance(activity)

        attachTftTouchMapper(dbg.ivTftDebugPreview) { x, y ->
            val live = wifiMgr.mirrorState.value.status != TftMirrorStatus.IDLE &&
                wifiMgr.mirrorState.value.status != TftMirrorStatus.ERROR
            if (!wifiMgr.isLocalPreviewActiveFlow.value && !live) {
                onFeedback("Tap 'Start Preview' first to interact with the cluster", false)
                return@attachTftTouchMapper
            }
            wifiMgr.simulateClusterTouch(9, x, y)
            dbg.tvTftDbgTouchInfo.text = "Touch mapped @ ($x, $y) on 800×480"
        }

        dbg.btnTftDbgTogglePreview.setOnClickListener {
            onHaptic()
            if (wifiMgr.isLocalPreviewActiveFlow.value) {
                wifiMgr.stopLocalDebugPreview()
            } else {
                wifiMgr.startLocalDebugPreview()
                onFeedback("800×480 Local TFT Preview started", false)
            }
        }

        dbg.btnTftDbgFullscreen.setOnClickListener {
            onHaptic()
            if (!wifiMgr.isLocalPreviewActiveFlow.value &&
                wifiMgr.mirrorState.value.status == TftMirrorStatus.IDLE
            ) {
                wifiMgr.startLocalDebugPreview()
            }
            showFullscreenTftDebuggerDialog()
        }

        dbg.btnTftDbgNavV2Test.setOnClickListener {
            onHaptic()
            wifiMgr.runNavV2LoopbackDiagnostics()
            onFeedback("NAV_V2 loopback suite queued — check Log Console", false)
        }

        dbg.btnTftDbgRecenter.setOnClickListener {
            onHaptic()
            wifiMgr.startLocalDebugPreview()
            wifiMgr.simulateHandlebarRecenter()
        }

        dbg.btnTftDbgDaylight.setOnClickListener {
            onHaptic()
            wifiMgr.startLocalDebugPreview()
            val highContrast = wifiMgr.toggleDebugDaylightContrast()
            dbg.btnTftDbgDaylight.text = if (highContrast) "Night UI" else "Daylight"
        }

        dbg.btnTftDbgMotion.setOnClickListener {
            onHaptic()
            wifiMgr.startLocalDebugPreview()
            val stationary = wifiMgr.toggleDebugStationaryMode()
            dbg.btnTftDbgMotion.text = if (stationary) "Park Mode" else "Ride Mode"
        }

        dbg.btnTftDbgHitboxes.setOnClickListener {
            onHaptic()
            wifiMgr.startLocalDebugPreview()
            val guides = wifiMgr.toggleDebugHitboxOverlay()
            dbg.btnTftDbgHitboxes.text = if (guides) "Guides ON" else "Guides"
        }

        var lastLogTouchY = 0f
        dbg.scrollDebugLogs.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastLogTouchY = event.y
                    val canScroll = v.canScrollVertically(1) || v.canScrollVertically(-1)
                    v.parent?.requestDisallowInterceptTouchEvent(canScroll)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - lastLogTouchY
                    lastLogTouchY = event.y
                    val canConsume = when {
                        dy > 0f -> v.canScrollVertically(-1)
                        dy < 0f -> v.canScrollVertically(1)
                        else -> v.canScrollVertically(1) || v.canScrollVertically(-1)
                    }
                    v.parent?.requestDisallowInterceptTouchEvent(canConsume)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        val filterButtons = listOf(
            dbg.btnLogFilterAll to DebugFilter.ALL,
            dbg.btnLogFilterBle to DebugFilter.BLE,
            dbg.btnLogFilterWifi to DebugFilter.WIFI_NAV_V2,
            dbg.btnLogFilterCall to DebugFilter.TELEPHONY,
            dbg.btnLogFilterMaps to DebugFilter.MAPS,
            dbg.btnLogFilterErr to DebugFilter.ERRORS
        )
        for ((btn, filter) in filterButtons) {
            btn.setOnClickListener {
                onHaptic()
                activeDebugFilter = filter
                updateDebugFilterChipsUi()
                renderDebugLogsUi(DebugLogRepository.entries.value, forceRender = true)
            }
        }

        dbg.btnLogPauseResume.setOnClickListener {
            onHaptic()
            isDebugLogPaused = !isDebugLogPaused
            dbg.btnLogPauseResume.text = if (isDebugLogPaused) "Resume" else "Pause"
            if (!isDebugLogPaused) {
                renderDebugLogsUi(DebugLogRepository.entries.value, forceRender = true)
            }
        }

        dbg.btnLogCopy.setOnClickListener {
            onHaptic()
            activity.lifecycleScope.launch {
                val (count, payload) = withContext(Dispatchers.Default) {
                    val filtered = DebugLogRepository.filterEntries(DebugLogRepository.entries.value, activeDebugFilter)
                    val formatted = DebugLogRepository.formatLogsForExport(
                        filtered,
                        headerInfo = "${bleManager.getDebugDiagnosticsSummary()} • Model=${appSettings.selectedModelId}"
                    )
                    filtered.size to formatted
                }
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("PulsarNav Logs", payload))
                onFeedback("Copied $count filtered log entries", false)
            }
        }

        dbg.btnLogShare.setOnClickListener {
            onHaptic()
            shareDebugLogText()
        }

        dbg.btnLogClear.setOnClickListener {
            onHaptic()
            DebugLogRepository.clear()
            renderDebugLogsUi(emptyList(), forceRender = true)
            onFeedback("Debug log buffer cleared", false)
        }

        suspend fun renderFrameIfVisible(
            frame: TftDebugFrame?,
            mirrorState: TftMirrorState,
            anyActive: Boolean,
            accountVisible: Boolean
        ) {
            if (frame != null && anyActive) {
                val fullscreenVisible = fullscreenTftDialog?.isShowing == true
                if (accountVisible && frame.frameIndex != lastDecodedFrameIndex) {
                    val targetSlot = (previewBitmapSlotIdx + 1) % previewBitmapSlots.size
                    val bmp = decodeSlotBitmap(frame, previewBitmapSlots, targetSlot)
                    if (bmp != null && isAccountTabVisible.value && !activity.isDestroyed && !activity.isFinishing) {
                        dbg.ivTftDebugPreview.setImageBitmap(bmp)
                        dbg.layoutTftDebugEmpty.visibility = View.GONE
                        previewBitmapSlotIdx = targetSlot
                        lastDecodedFrameIndex = frame.frameIndex
                    }
                }
                if (fullscreenVisible && frame.frameIndex != lastFullscreenFrameIndex) {
                    val targetFullSlot = (fullscreenBitmapSlotIdx + 1) % fullscreenBitmapSlots.size
                    val fullBmp = decodeSlotBitmap(frame, fullscreenBitmapSlots, targetFullSlot)
                    val ivFull = fullscreenTftImageView
                    if (fullBmp != null && ivFull != null && fullscreenTftDialog?.isShowing == true) {
                        ivFull.setImageBitmap(fullBmp)
                        fullscreenBitmapSlotIdx = targetFullSlot
                        lastFullscreenFrameIndex = frame.frameIndex
                    }
                }
                if (accountVisible || fullscreenVisible) {
                    val kb = frame.byteSize / 1024f
                    val motionBadge = if (mirrorState.isBikeStationary) "PARK" else "RIDE"
                    val metricsText = String.format(
                        Locale.US,
                        "%dx%d • %.1f KB / 30 KB • Q=%d • #%d • %s",
                        frame.width,
                        frame.height,
                        kb,
                        frame.quality,
                        frame.frameIndex,
                        motionBadge
                    )
                    if (accountVisible) {
                        dbg.tvTftDbgMetrics.text = metricsText
                        dbg.pbTftJpegBudget.progress =
                            frame.byteSize.coerceIn(0, TftWifiProtocol.UDP_MAX_JPEG_BYTES)
                    }
                    fullscreenTftMetricsView?.text = metricsText
                }
            } else if (!anyActive) {
                lastDecodedFrameIndex = -1L
                lastFullscreenFrameIndex = -1L
                dbg.layoutTftDebugEmpty.visibility = View.VISIBLE
                dbg.pbTftJpegBudget.progress = 0
                dbg.tvTftDbgMetrics.text = activity.getString(R.string.labs_tft_metrics_idle)
            }
        }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        wifiMgr.mirrorState,
                        wifiMgr.isLocalPreviewActiveFlow
                    ) { mirrorState, localActive ->
                        mirrorState to localActive
                    }.collect { (mirrorState, localActive) ->
                        val liveWifi = mirrorState.status != TftMirrorStatus.IDLE &&
                            mirrorState.status != TftMirrorStatus.ERROR
                        val anyActive = liveWifi || localActive

                        dbg.btnTftDbgTogglePreview.text = when {
                            liveWifi -> "Live Wi-Fi Active"
                            localActive -> "Stop Preview"
                            else -> "Start Preview"
                        }

                        dbg.tvTftDbgStatus.text = when {
                            liveWifi -> "LIVE WI-FI"
                            localActive -> "LOCAL PREVIEW"
                            else -> "IDLE"
                        }
                        dbg.tvTftDbgStatus.setTextColor(
                            if (anyActive) ContextCompat.getColor(activity, R.color.status_connected)
                            else themeProvider().textSecondaryColor
                        )

                        val touchSummary = mirrorState.lastTouchInfo.ifBlank { "None" }
                        dbg.tvTftDbgTouchInfo.text = "Last Cluster Input: $touchSummary"
                        fullscreenTftTouchView?.text = "Input: $touchSummary"

                        if (!anyActive) {
                            renderFrameIfVisible(null, mirrorState, anyActive = false, accountVisible = isAccountTabVisible.value)
                        }
                    }
                }

                launch {
                    combine(
                        wifiMgr.latestDebugFrame,
                        isAccountTabVisible,
                        wifiMgr.isLocalPreviewActiveFlow
                    ) { frame, accountVisible, localActive ->
                        Triple(frame, accountVisible, localActive)
                    }.collectLatest { (frame, accountVisible, localActive) ->
                        val mirrorState = wifiMgr.mirrorState.value
                        val liveWifi = mirrorState.status != TftMirrorStatus.IDLE &&
                            mirrorState.status != TftMirrorStatus.ERROR
                        val anyActive = liveWifi || localActive
                        renderFrameIfVisible(frame, mirrorState, anyActive, accountVisible)
                    }
                }

                launch {
                    combine(
                        GoogleMapsNotificationListener.currentNavStep,
                        bleManager.simulatedNavStep,
                        bleManager.simulatedAlert,
                        callManager.callInfo
                    ) { _, _, _, _ -> Unit }.collectLatest {
                        wifiMgr.refreshDebugCockpitFrame()
                    }
                }

                launch {
                    DebugLogRepository.entries.collectLatest { entries ->
                        renderDebugLogsUi(entries, forceRender = false)
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTftTouchMapper(imageView: ImageView, onMappedTouch: (Int, Int) -> Unit) {
        val touchSlop = ViewConfiguration.get(imageView.context).scaledTouchSlop.toFloat()
        var downX = 0f
        var downY = 0f
        var movedBeyondSlop = false

        imageView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    movedBeyondSlop = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!movedBeyondSlop &&
                        hypot(event.x - downX, event.y - downY) > touchSlop
                    ) {
                        movedBeyondSlop = true
                    }
                    !movedBeyondSlop
                }
                MotionEvent.ACTION_UP -> {
                    if (movedBeyondSlop) return@setOnTouchListener false
                    val w = v.width
                    val h = v.height
                    if (w <= 0 || h <= 0 || event.x < 0f || event.x >= w || event.y < 0f || event.y >= h) {
                        return@setOnTouchListener false
                    }
                    val x = ((event.x / w.toFloat()) * TftWifiProtocol.TFT_WIDTH).roundToInt()
                        .coerceIn(0, TftWifiProtocol.TFT_WIDTH - 1)
                    val y = ((event.y / h.toFloat()) * TftWifiProtocol.TFT_HEIGHT).roundToInt()
                        .coerceIn(0, TftWifiProtocol.TFT_HEIGHT - 1)
                    onHaptic()
                    onMappedTouch(x, y)
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    movedBeyondSlop = true
                    false
                }
                else -> false
            }
        }
    }

    private suspend fun decodeSlotBitmap(
        frame: TftDebugFrame,
        slots: Array<Bitmap?>,
        targetSlot: Int
    ): Bitmap? = withContext(Dispatchers.Default) {
        synchronized(previewDecodeLock) {
            val reusable = slots[targetSlot]
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
                if (reusable != null && !reusable.isRecycled &&
                    reusable.width == frame.width && reusable.height == frame.height
                ) {
                    inBitmap = reusable
                }
            }
            val decoded = try {
                BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size, opts)
            } catch (_: IllegalArgumentException) {
                opts.inBitmap = null
                BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size, opts)
            }
            if (decoded != null) {
                slots[targetSlot] = decoded
            }
            decoded
        }
    }

    private fun showFullscreenTftDebuggerDialog() {
        fullscreenTftDialog?.dismiss()
        val wifiMgr = TftWifiMirrorManager.getInstance(activity)
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val dp = { v: Int -> CockpitThemeApplier.dpToPx(activity, v) }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080B10"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvTitle = TextView(activity).apply {
            text = "800×480 TFT LIVE BENCH"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTouch = TextView(activity).apply {
            text = "Tap display to inspect touch (x,y)"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(8), 0)
        }
        val btnClose = MaterialButton(activity).apply {
            text = "Close"
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            maxLines = 1
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1E293B"))
            setOnClickListener { dialog.dismiss() }
        }
        topBar.addView(tvTitle)
        topBar.addView(tvTouch)
        topBar.addView(btnClose)
        root.addView(topBar)

        val previewFrame = ConstraintLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
        val ivFull = ImageView(activity).apply {
            id = View.generateViewId()
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                dimensionRatio = "5:3"
            }
        }
        previewFrame.addView(ivFull)
        root.addView(previewFrame)

        val tvMetrics = TextView(activity).apply {
            text = dbg.tvTftDbgMetrics.text
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(tvMetrics)

        val actionBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun addBarBtn(label: String, onClick: () -> Unit) {
            val b = MaterialButton(activity).apply {
                text = label
                textSize = 11f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minimumWidth = 0
                minimumHeight = 0
                setPadding(dp(4), 0, dp(4), 0)
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1E293B"))
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener {
                    onHaptic()
                    onClick()
                }
            }
            actionBar.addView(b)
        }
        addBarBtn("Recenter") { wifiMgr.simulateHandlebarRecenter() }
        addBarBtn("Day/Night") { wifiMgr.toggleDebugDaylightContrast() }
        addBarBtn("Park/Ride") { wifiMgr.toggleDebugStationaryMode() }
        addBarBtn("Guides") { wifiMgr.toggleDebugHitboxOverlay() }
        addBarBtn("NAV_V2") { wifiMgr.runNavV2LoopbackDiagnostics() }
        root.addView(actionBar)

        attachTftTouchMapper(ivFull) { x, y ->
            wifiMgr.simulateClusterTouch(9, x, y)
            tvTouch.text = "Touch @ ($x, $y)"
        }

        wifiMgr.latestDebugFrame.value?.let { frame ->
            activity.lifecycleScope.launch {
                val targetFullSlot = (fullscreenBitmapSlotIdx + 1) % fullscreenBitmapSlots.size
                val seeded = decodeSlotBitmap(frame, fullscreenBitmapSlots, targetFullSlot)
                if (seeded != null && dialog.isShowing && ivFull === fullscreenTftImageView) {
                    ivFull.setImageBitmap(seeded)
                    fullscreenBitmapSlotIdx = targetFullSlot
                    lastFullscreenFrameIndex = frame.frameIndex
                }
            }
        }

        fullscreenTftImageView = ivFull
        fullscreenTftMetricsView = tvMetrics
        fullscreenTftTouchView = tvTouch
        fullscreenTftDialog = dialog

        dialog.setOnDismissListener {
            if (fullscreenTftDialog === dialog) {
                fullscreenTftDialog = null
                fullscreenTftImageView = null
                fullscreenTftMetricsView = null
                fullscreenTftTouchView = null
                lastFullscreenFrameIndex = -1L
                if (!isAccountTabVisible.value && wifiMgr.isLocalPreviewActiveFlow.value) {
                    wifiMgr.stopLocalDebugPreview()
                }
            }
        }
        dialog.setContentView(root)
        dialog.show()
    }

    fun updateDebugFilterChipsUi() {
        val theme = themeProvider()
        val chips = listOf(
            dbg.btnLogFilterAll to DebugFilter.ALL,
            dbg.btnLogFilterBle to DebugFilter.BLE,
            dbg.btnLogFilterWifi to DebugFilter.WIFI_NAV_V2,
            dbg.btnLogFilterCall to DebugFilter.TELEPHONY,
            dbg.btnLogFilterMaps to DebugFilter.MAPS,
            dbg.btnLogFilterErr to DebugFilter.ERRORS
        )
        for ((btn, filter) in chips) {
            val selected = activeDebugFilter == filter
            if (selected) {
                btn.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
                btn.strokeColor = ColorStateList.valueOf(theme.primaryColor)
                btn.setTextColor(theme.onPrimaryColor)
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(theme.subtleColor)
                btn.strokeColor = ColorStateList.valueOf(theme.cardBorderColor)
                btn.setTextColor(theme.textSecondaryColor)
            }
        }
    }

    private fun renderDebugLogsUi(entries: List<DebugLogEntry>, forceRender: Boolean) {
        if (!forceRender && !isAccountTabVisible.value) return
        dbg.tvBleDiagBanner.text = bleManager.getDebugDiagnosticsSummary()
        val filterSnapshot = activeDebugFilter
        val errCount = DebugLogRepository.errorCount.value

        if (isDebugLogPaused && !forceRender) {
            val filteredCount = DebugLogRepository.filterEntries(entries, filterSnapshot).size
            dbg.tvDebugLogStats.text = "$filteredCount/${entries.size} • $errCount err • PAUSED"
            return
        }

        renderLogJob?.cancel()
        renderLogJob = activity.lifecycleScope.launch {
            val (statsText, formattedText) = withContext(Dispatchers.Default) {
                val filtered = DebugLogRepository.filterEntries(entries, filterSnapshot)
                val pauseTag = if (isDebugLogPaused) " • PAUSED" else ""
                val stats = "${filtered.size}/${entries.size} • $errCount err$pauseTag"
                val recent = if (filtered.size > 140) filtered.takeLast(140) else filtered
                val body = if (recent.isEmpty()) {
                    "No entries matching '${filterSnapshot.label}'."
                } else {
                    recent.joinToString("\n") { DebugLogRepository.formatEntryLine(it) }
                }
                stats to body
            }

            if (activity.isDestroyed || activity.isFinishing) return@launch
            dbg.tvDebugLogStats.text = statsText

            if (formattedText != lastRenderedLogText) {
                val isFirstRender = lastRenderedLogText == null
                val inner = dbg.scrollDebugLogs.getChildAt(0)
                val wasNearBottom = inner == null ||
                    !dbg.scrollDebugLogs.canScrollVertically(1) ||
                    (inner.bottom - (dbg.scrollDebugLogs.height + dbg.scrollDebugLogs.scrollY) < CockpitThemeApplier.dpToPx(activity, 24))
                lastRenderedLogText = formattedText
                dbg.tvDebugLogOutput.text = formattedText

                if (!isFirstRender && !isDebugLogPaused && wasNearBottom) {
                    dbg.scrollDebugLogs.post {
                        val content = dbg.scrollDebugLogs.getChildAt(0) ?: return@post
                        val targetY = (content.height - dbg.scrollDebugLogs.height).coerceAtLeast(0)
                        dbg.scrollDebugLogs.scrollTo(0, targetY)
                    }
                }
            }
        }
    }

    private fun shareDebugLogText() {
        val entriesSnapshot = DebugLogRepository.entries.value
        val filterSnapshot = activeDebugFilter
        val header = "PulsarNav v${BuildConfig.VERSION_NAME} • Model=${appSettings.selectedModelId} • ${bleManager.getDebugDiagnosticsSummary()}"
        activity.lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val filtered = DebugLogRepository.filterEntries(entriesSnapshot, filterSnapshot)
                    val payload = DebugLogRepository.formatLogsForExport(filtered, headerInfo = header)
                    val dir = File(activity.cacheDir, "debug_logs").apply { mkdirs() }
                    val logFile = File(dir, "pulsarnav-log.txt")
                    logFile.writeText(payload, Charsets.UTF_8)
                    FileProvider.getUriForFile(
                        activity,
                        "${activity.applicationContext.packageName}.fileprovider",
                        logFile
                    )
                }
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "PulsarNav Diagnostic Log (${activeDebugFilter.label})")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("PulsarNav Diagnostic Log", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(sendIntent, "Share PulsarNav Diagnostic Log"))
            } catch (e: Exception) {
                onFeedback("Unable to open Share sheet: ${e.message}", true)
            }
        }
    }

    fun cleanup() {
        renderLogJob?.cancel()
        renderLogJob = null
        fullscreenTftDialog?.dismiss()
        fullscreenTftDialog = null
        fullscreenTftImageView = null
        fullscreenTftMetricsView = null
        fullscreenTftTouchView = null
        dbg.ivTftDebugPreview.setImageDrawable(null)
        synchronized(previewDecodeLock) {
            previewBitmapSlots.fill(null)
            fullscreenBitmapSlots.fill(null)
        }
    }
}
