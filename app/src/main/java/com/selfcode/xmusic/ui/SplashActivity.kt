package com.selfcode.xmusic.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.selfcode.xmusic.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fade in content
        val fadeIn = ObjectAnimator.ofFloat(binding.splashContent, View.ALPHA, 0f, 1f).apply {
            duration = 800
            interpolator = DecelerateInterpolator()
        }
        val scaleX = ObjectAnimator.ofFloat(binding.splashContent, View.SCALE_X, 0.85f, 1f).apply {
            duration = 800
            interpolator = DecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(binding.splashContent, View.SCALE_Y, 0.85f, 1f).apply {
            duration = 800
            interpolator = DecelerateInterpolator()
        }
        AnimatorSet().apply {
            playTogether(fadeIn, scaleX, scaleY)
            start()
        }

        // After 2.5s — fade out and go to MainActivity
        binding.splashContent.postDelayed({
            val fadeOut = ObjectAnimator.ofFloat(binding.root, View.ALPHA, 1f, 0f).apply {
                duration = 700
                interpolator = android.view.animation.AccelerateInterpolator()
            }
            fadeOut.start()
            fadeOut.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    finish()
                }
            })
        }, 2500)
    }
}
