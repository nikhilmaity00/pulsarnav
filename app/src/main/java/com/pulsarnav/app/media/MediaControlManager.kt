package com.pulsarnav.app.media

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.pulsarnav.app.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MediaControlManager private constructor(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _lastMediaEvent = MutableStateFlow<String?>(null)
    val lastMediaEvent: StateFlow<String?> = _lastMediaEvent.asStateFlow()

    companion object {
        @Volatile
        private var instance: MediaControlManager? = null

        fun getInstance(context: Context): MediaControlManager {
            return instance ?: synchronized(this) {
                instance ?: MediaControlManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun handleHandlebarButton(isLongPress: Boolean): Boolean {
        if (!AppSettings.getInstance(context).isMediaControlEnabled) {
            return false
        }

        return if (isLongPress) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            _lastMediaEvent.value = "⏭ Next Track"
            clearBannerAfterDelay()
            true
        } else {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            _lastMediaEvent.value = "⏯ Play / Pause"
            clearBannerAfterDelay()
            true
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val eventTime = SystemClock.uptimeMillis()
        val down = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
        audioManager?.dispatchMediaKeyEvent(down)
        audioManager?.dispatchMediaKeyEvent(up)
    }

    private fun clearBannerAfterDelay() {
        scope.launch {
            delay(3500)
            _lastMediaEvent.value = null
        }
    }
}
