package com.androclaw.service

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton bridge between the [ScreenCaptureService] (which owns the
 * MediaProjection / VirtualDisplay / ImageReader pipeline) and the rest of the
 * app — particularly tool handlers that need a Bitmap of the current screen.
 *
 * The service registers itself here when it starts and clears itself when it
 * stops. Callers use [isRunning] / [captureFrame] without needing to know
 * anything about service binding or MediaProjection internals.
 *
 * Why this exists: Hilt-managed tool handlers run in suspend coroutine
 * contexts and have no Activity reference, so they can't bind to the service
 * the usual way. The service publishing itself into a singleton is the
 * simplest pattern that keeps tool handlers ignorant of Android lifecycle.
 */
@Singleton
class ScreenCaptureManager @Inject constructor() {

    @Volatile
    private var service: ScreenCaptureService? = null

    fun attach(service: ScreenCaptureService) {
        this.service = service
    }

    fun detach(service: ScreenCaptureService) {
        if (this.service === service) this.service = null
    }

    /**
     * True when the foreground capture service is running and the
     * MediaProjection has been initialized successfully.
     */
    fun isRunning(): Boolean = service?.isCapturing() == true

    /**
     * Grab the most recent frame as a software ARGB_8888 Bitmap, or null
     * if capture isn't running or the frame couldn't be acquired.
     *
     * The caller owns the returned Bitmap and is responsible for recycling it.
     */
    suspend fun captureFrame(): Bitmap? = service?.captureFrame()
}
