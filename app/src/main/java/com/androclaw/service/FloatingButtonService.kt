package com.androclaw.service

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
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.androclaw.AndroClawApplication
import com.androclaw.ChatActivity
import com.androclaw.R
import com.androclaw.api.ClaudeRepository
import com.androclaw.db.ConversationDao
import com.androclaw.db.MessageDao
import com.androclaw.ui.theme.AndroClawTheme
import com.androclaw.utils.Constants
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FloatingButtonService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    // Lifecycle support for ComposeView in a Service
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    @Inject lateinit var repository: ClaudeRepository
    @Inject lateinit var messageDao: MessageDao
    @Inject lateinit var conversationDao: ConversationDao

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var prefs: SharedPreferences

    private var chatOverlayView: View? = null
    private var chatManager: OverlayChatManager? = null
    private var isChatOpen = false

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isMoving = false
    private var longPressTriggered = false

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        vibrate()
        openFullApp()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        createFloatingButton()
        startForegroundNotification()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
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

        floatingView = FrameLayout(this).apply {
            val logoBitmap = assets.open("app_logo.png").use { android.graphics.BitmapFactory.decodeStream(it) }
            val button = android.widget.ImageView(this@FloatingButtonService).apply {
                setImageBitmap(logoBitmap)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(16, 16, 16, 16)
                elevation = 8f
            }
            addView(button, FrameLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE))
        }

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
                            prefs.edit()
                                .putInt(Constants.PREF_FLOATING_BUTTON_X, params.x)
                                .putInt(Constants.PREF_FLOATING_BUTTON_Y, params.y)
                                .apply()
                        } else if (!longPressTriggered) {
                            vibrate()
                            toggleChatOverlay()
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingView, params)
    }

    // ── Chat Overlay ──

    private fun toggleChatOverlay() {
        if (isChatOpen) {
            closeChatOverlay()
        } else {
            openChatOverlay()
        }
    }

    private fun openChatOverlay() {
        if (isChatOpen) return

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        // Initialize chat manager on first open
        if (chatManager == null) {
            chatManager = OverlayChatManager(repository, messageDao, conversationDao, prefs)
        }
        // Always re-resolve in case the user switched chats in the main app
        chatManager!!.ensureConversation()

        val dm = resources.displayMetrics
        val chatWidth = (dm.widthPixels * 0.92f).toInt()
        val chatHeight = (dm.heightPixels * 0.65f).toInt()

        val chatParams = WindowManager.LayoutParams(
            chatWidth,
            chatHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingButtonService)
            setViewTreeSavedStateRegistryOwner(this@FloatingButtonService)
            setViewTreeViewModelStoreOwner(this@FloatingButtonService)
            setContent {
                AndroClawTheme {
                    OverlayChatUI(
                        chatManager = chatManager!!,
                        onClose = { closeChatOverlay() }
                    )
                }
            }
        }

        chatOverlayView = composeView
        windowManager.addView(composeView, chatParams)
        isChatOpen = true

        // Hide the floating button while chat is open
        floatingView.visibility = View.GONE
    }

    private fun closeChatOverlay() {
        if (!isChatOpen) return

        chatOverlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        chatOverlayView = null
        isChatOpen = false

        // Show the floating button again
        floatingView.visibility = View.VISIBLE

        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    private fun openFullApp() {
        closeChatOverlay()
        val intent = Intent(this, ChatActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    // ── Utils ──

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
        closeChatOverlay()
        try { windowManager.removeView(floatingView) } catch (_: Exception) {}
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
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
