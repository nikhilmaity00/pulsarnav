package com.pulsarnav.app.service

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pulsarnav.app.ble.BajajBleManager
import com.pulsarnav.app.parser.GoogleMapsNotificationParser
import com.pulsarnav.app.parser.ParsedNavigationStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class WhatsAppAlertInfo(
    val sender: String,
    val timestamp: Long = System.currentTimeMillis()
)

class GoogleMapsNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "PulsarNotificationHub"
        private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_REVERT_DELAY_MS = 6000L

        private val _currentNavStep = MutableStateFlow<ParsedNavigationStep?>(null)
        val currentNavStep: StateFlow<ParsedNavigationStep?> = _currentNavStep

        private val _lastWhatsAppAlert = MutableStateFlow<WhatsAppAlertInfo?>(null)
        val lastWhatsAppAlert: StateFlow<WhatsAppAlertInfo?> = _lastWhatsAppAlert

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val revertToNavigationRunnable = Runnable {
        val activeStep = _currentNavStep.value
        val bleManager = BajajBleManager.getInstance(applicationContext)
        if (activeStep != null) {
            Log.d(TAG, "Reverting cluster display back to active turn navigation")
            bleManager.sendNavigationStep(
                maneuver = activeStep.maneuver,
                distanceMeters = activeStep.distanceMeters,
                streetName = activeStep.streetName,
                etaMinutes = activeStep.etaMinutes
            )
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isServiceActive.value = true
        Log.i(TAG, "Notification Listener connected (Google Maps TBT + WhatsApp Alerts)")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isServiceActive.value = false
        Log.w(TAG, "Notification Listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        when (sbn.packageName) {
            GOOGLE_MAPS_PACKAGE -> handleGoogleMapsNotification(sbn)
            WHATSAPP_PACKAGE -> {
                if (com.pulsarnav.app.data.AppSettings.getInstance(applicationContext).isWhatsAppAlertsEnabled) {
                    handleWhatsAppNotification(sbn)
                }
            }
            // SMS and all other apps are strictly excluded
        }
    }

    private fun handleGoogleMapsNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subtext = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        Log.d(TAG, "Google Maps TBT: title='$title', text='$text', subtext='$subtext'")

        val parsedStep = GoogleMapsNotificationParser.parse(title, text, subtext) ?: return
        _currentNavStep.value = parsedStep

        val bleManager = BajajBleManager.getInstance(applicationContext)
        bleManager.sendNavigationStep(
            maneuver = parsedStep.maneuver,
            distanceMeters = parsedStep.distanceMeters,
            streetName = parsedStep.streetName,
            etaMinutes = parsedStep.etaMinutes
        )
    }

    private fun handleWhatsAppNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return

        // Filter out background/ongoing notifications (e.g. WhatsApp Web active, backups)
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notification.extras ?: return
        val senderTitle = extras.getString(Notification.EXTRA_TITLE) ?: return

        // Ignore generic system status titles
        if (senderTitle.contains("WhatsApp", ignoreCase = true) ||
            senderTitle.contains("Checking for new messages", ignoreCase = true) ||
            senderTitle.contains("messages from", ignoreCase = true)) {
            return
        }

        Log.i(TAG, "WhatsApp message alert intercepted from: $senderTitle")
        _lastWhatsAppAlert.value = WhatsAppAlertInfo(senderTitle)

        // Send 44-byte WhatsApp alert packet to cluster characteristic 0410
        val bleManager = BajajBleManager.getInstance(applicationContext)
        bleManager.sendWhatsAppAlert(senderTitle)

        // User preference: Revert back to navigation after 6 seconds
        mainHandler.removeCallbacks(revertToNavigationRunnable)
        mainHandler.postDelayed(revertToNavigationRunnable, WHATSAPP_REVERT_DELAY_MS)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == GOOGLE_MAPS_PACKAGE) {
            _currentNavStep.value = null
            Log.i(TAG, "Google Maps navigation ended or notification removed")
        }
    }
}
