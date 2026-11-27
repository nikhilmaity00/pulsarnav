package com.pulsarnav.app.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import java.util.WeakHashMap

/**
 * Tracks pulse animators per dot view so simultaneous status indicators (e.g. top bar dot and
 * connection card dot) animate independently without starving each other, while honoring system
 * reduced-motion settings.
 */
class StatusDotPulseController {

    private val animators = WeakHashMap<View, ObjectAnimator>()

    fun start(dotView: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            dotView.alpha = 1.0f
            return
        }
        val existing = animators[dotView]
        if (existing != null && existing.isRunning) return
        existing?.cancel()

        val animator = ObjectAnimator.ofFloat(dotView, "alpha", 0.35f, 1.0f).apply {
            duration = 800L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        animators[dotView] = animator
    }

    fun stop(dotView: View) {
        animators.remove(dotView)?.cancel()
        dotView.alpha = 1.0f
    }

    fun cancelAll() {
        for ((view, animator) in animators.entries.toList()) {
            animator.cancel()
            view.alpha = 1.0f
        }
        animators.clear()
    }
}
