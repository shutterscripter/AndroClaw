package com.androclaw.service

import android.animation.ValueAnimator
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.androclaw.AndroClawApplication
import com.androclaw.ChatActivity
import com.androclaw.R
import com.androclaw.utils.Constants

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var prefs: SharedPreferences
    private var pulseAnimator: ValueAnimator? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isMoving = false
    private var longPressTriggered = false

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        vibrate()
        showQuickActions()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        createFloatingButton()
        startForegroundNotification()
        startPulseAnimation()
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ChatActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, AndroClawApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AndroClaw")
            .setContentText(getString(R.string.notification_content))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createFloatingButton() {
        val savedX = prefs.getInt(Constants.PREF_FLOATING_BUTTON_X, 0)
        val savedY = prefs.getInt(Constants.PREF_FLOATING_BUTTON_Y, 200)

        val params = WindowManager.LayoutParams(
            BUTTON_SIZE,
            BUTTON_SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        // Create the floating button view
        floatingView = FrameLayout(this).apply {
            val logoBitmap = assets.open("app_logo.png").use { android.graphics.BitmapFactory.decodeStream(it) }
            val button = android.widget.ImageView(this@FloatingButtonService).apply {
                setImageBitmap(logoBitmap)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(16, 16, 16, 16)
                setBackgroundResource(R.drawable.floating_button_bg)
                elevation = 8f
            }
            addView(button, FrameLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE))
        }

        // Touch handler for drag, tap, long press
        floatingView.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        longPressTriggered = false
                        v.handler?.postDelayed(longPressRunnable, LONG_PRESS_DURATION)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > MOVE_THRESHOLD || Math.abs(dy) > MOVE_THRESHOLD) {
                            isMoving = true
                            v.handler?.removeCallbacks(longPressRunnable)
                        }
                        if (isMoving) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            windowManager.updateViewLayout(floatingView, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.handler?.removeCallbacks(longPressRunnable)
                        if (isMoving) {
                            // Save position
                            prefs.edit()
                                .putInt(Constants.PREF_FLOATING_BUTTON_X, params.x)
                                .putInt(Constants.PREF_FLOATING_BUTTON_Y, params.y)
                                .apply()
                        } else if (!longPressTriggered) {
                            // Single tap — open chat
                            vibrate()
                            openChat()
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingView, params)
    }

    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.1f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val scale = animation.animatedValue as Float
                floatingView.scaleX = scale
                floatingView.scaleY = scale
            }
            start()
        }
    }

    private fun openChat() {
        val intent = Intent(this, ChatActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun showQuickActions() {
        // For quick actions, just open chat for now
        // A full popup menu would require a more complex overlay
        openChat()
    }

    private fun vibrate() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
        try {
            windowManager.removeView(floatingView)
        } catch (_: Exception) {}
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val BUTTON_SIZE = 160
        const val MOVE_THRESHOLD = 10
        const val LONG_PRESS_DURATION = 500L

        fun start(context: Context) {
            val intent = Intent(context, FloatingButtonService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingButtonService::class.java))
        }
    }
}
