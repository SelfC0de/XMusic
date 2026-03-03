package com.selfcode.xmusic.ui.views

import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

object BounceEffect {

    fun apply(vararg views: View) {
        for (v in views) {
            val scaleX = SpringAnimation(v, DynamicAnimation.SCALE_X, 1f).apply {
                spring = SpringForce(1f).setDampingRatio(0.35f).setStiffness(600f)
            }
            val scaleY = SpringAnimation(v, DynamicAnimation.SCALE_Y, 1f).apply {
                spring = SpringForce(1f).setDampingRatio(0.35f).setStiffness(600f)
            }
            v.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        scaleX.cancel()
                        scaleY.cancel()
                        view.scaleX = 0.85f
                        view.scaleY = 0.85f
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        scaleX.start()
                        scaleY.start()
                        if (event.action == MotionEvent.ACTION_UP) {
                            view.performClick()
                        }
                    }
                }
                true
            }
        }
    }
}
