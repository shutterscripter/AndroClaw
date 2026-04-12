package com.androclaw.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.androclaw.MainActivity
import com.androclaw.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Foreground service that owns a [MediaProjection] session and exposes the
 * current screen as a Bitmap on demand.
 *
 * Lifecycle:
 *   1. The user grants screen-capture permission via the system intent
 *      (handled in [MainActivity]).
 *   2. MainActivity starts this service with the consent token in the intent.
 *   3. onStartCommand calls startForeground (mandatory within 5 seconds), then
 *      builds the MediaProjection + VirtualDisplay + ImageReader pipeline.
 *   4. While the service is running, [captureFrame] returns the latest frame
 *      from the ImageReader as a software ARGB_8888 Bitmap.
 *   5. ACTION_STOP (or onDestroy) tears the pipeline down and clears the
 *      manager singleton so callers fall back to other capture paths.
 *
 * Capture is *lazy*: nothing is processed unless captureFrame() is called.
 * The ImageReader buffers up to 2 frames; acquireLatestImage drops older ones.
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {

    @Inject lateinit var manager: ScreenCaptureManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ACTION_STOP — tear everything down and exit.
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Must call startForeground IMMEDIATELY (within 5s) on Android 8+ —
        // Android 14+ additionally requires the mediaProjection foreground type.
        startForegroundNow()

        // The intent must carry the consent extras handed back by the system
        // dialog (resultCode + Intent). Without them we can't get a projection.
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }

        if (resultCode == 0 || resultData == null) {
            Log.w(TAG, "Started without consent extras — cannot init MediaProjection")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            initProjection(resultCode, resultData)
            running = true
            manager.attach(this)
            Log.d(TAG, "Screen capture started: ${screenWidth}x$screenHeight @ $screenDensity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init MediaProjection", e)
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        manager.detach(this)
        super.onDestroy()
    }

    fun isCapturing(): Boolean = running && imageReader != null

    /**
     * Grab the most recent frame from the ImageReader and return it as a
     * software ARGB_8888 Bitmap. The caller owns the returned bitmap.
     *
     * Implementation notes:
     *  - acquireLatestImage discards older buffered frames automatically.
     *  - We have to handle row stride: ImageReader pixels can be padded to a
     *    larger row width than the screen, so we read into a buffer Bitmap of
     *    `(width + rowPadding/pixelStride) × height` and then crop.
     */
    suspend fun captureFrame(): Bitmap? {
        if (!isCapturing()) return null
        val reader = imageReader ?: return null

        // Give the projection a moment on the very first call so the first
        // frame is actually rendered into the ImageReader.
        var image: Image? = reader.acquireLatestImage()
        if (image == null) {
            delay(80)
            image = reader.acquireLatestImage()
        }
        if (image == null) return null

        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val bufferWidth = screenWidth + rowPadding / pixelStride

            val full = Bitmap.createBitmap(bufferWidth, screenHeight, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buffer)

            // Crop off the right-edge padding so the result is the real screen size.
            val cropped = if (bufferWidth != screenWidth) {
                val c = Bitmap.createBitmap(full, 0, 0, screenWidth, screenHeight)
                full.recycle()
                c
            } else full
            cropped
        } catch (e: Exception) {
            Log.e(TAG, "captureFrame failed", e)
            null
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    // ── Internals ────────────────────────────────────────────────────

    private fun startForegroundNow() {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 0, tapIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPi = android.app.PendingIntent.getService(
            this, 1, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroClaw screen capture")
            .setContentText("Screen reading is enabled. Tap to manage in Settings.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .addAction(0, "Stop", stopPi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Screen capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while AndroClaw is allowed to read your screen"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun initProjection(resultCode: Int, resultData: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("getMediaProjection returned null")

        // Required on Android 14+ — must register a callback before creating
        // the VirtualDisplay or the system will throw.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection.onStop — tearing down")
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, captureHandler())

        captureMetrics()

        val reader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )
        // Drain frames continuously so the producer doesn't stall — we still
        // grab the latest one on demand from captureFrame().
        reader.setOnImageAvailableListener({ /* drained by acquireLatestImage */ },
            captureHandler())

        val vd = projection.createVirtualDisplay(
            "AndroClawScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler()
        ) ?: throw IllegalStateException("createVirtualDisplay returned null")

        mediaProjection = projection
        imageReader = reader
        virtualDisplay = vd
    }

    private fun captureMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
            screenDensity = dm.densityDpi
        }
    }

    private fun captureHandler(): Handler {
        var handler = captureHandler
        if (handler == null) {
            val thread = HandlerThread("ScreenCapture").also { it.start() }
            captureThread = thread
            handler = Handler(thread.looper)
            captureHandler = handler
        }
        return handler
    }

    private fun stopCapture() {
        running = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        try { captureThread?.quitSafely() } catch (_: Exception) {}
        captureThread = null
        captureHandler = null
        manager.detach(this)
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "androclaw_screen_capture"
        private const val NOTIFICATION_ID = 0xC1A2

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.androclaw.action.STOP_SCREEN_CAPTURE"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
    }
}
