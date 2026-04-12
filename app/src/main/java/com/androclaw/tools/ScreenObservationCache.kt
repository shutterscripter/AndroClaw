package com.androclaw.tools

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton in-memory cache for the most recent screen_observe result.
 *
 * The model produces tool calls in two phases:
 *   1. screen_observe → returns a marked-up screenshot + numbered legend.
 *   2. control_app_ui with action {type: "tap_mark", mark: N} → looks up the
 *      bounds for mark N here and dispatches a coordinate tap.
 *
 * Cache lifetime is intentionally short (60 s) because the foreground UI can
 * change at any moment. Each new screen_observe replaces the previous entry.
 */
@Singleton
class ScreenObservationCache @Inject constructor() {

    private companion object {
        const val TTL_MS = 60_000L
    }

    @Volatile private var elements: List<ScreenElement> = emptyList()
    @Volatile private var capturedAt: Long = 0L
    @Volatile var lastWidth: Int = 0
        private set
    @Volatile var lastHeight: Int = 0
        private set

    fun store(observation: ScreenObservation) {
        elements = observation.elements
        capturedAt = System.currentTimeMillis()
        lastWidth = observation.originalWidth
        lastHeight = observation.originalHeight
    }

    fun isFresh(): Boolean =
        elements.isNotEmpty() && (System.currentTimeMillis() - capturedAt) < TTL_MS

    fun getMark(mark: Int): ScreenElement? {
        if (!isFresh()) return null
        return elements.firstOrNull { it.mark == mark }
    }

    fun ageMs(): Long = System.currentTimeMillis() - capturedAt

    fun clear() {
        elements = emptyList()
        capturedAt = 0L
        lastWidth = 0
        lastHeight = 0
    }
}
