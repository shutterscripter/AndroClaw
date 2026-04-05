package com.androclaw.tools

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaControlToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun execute(input: Map<String, Any>): String {
        val action = input["action"] as? String ?: return "Missing action"

        return when (action.lowercase()) {
            "play", "resume" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Playing media")
            "pause" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused media")
            "play_pause", "toggle" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Toggled play/pause")
            "next", "skip" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Skipped to next track")
            "previous", "prev" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Went to previous track")
            "stop" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_STOP, "Stopped media")
            "volume_up" -> adjustVolume(AudioManager.ADJUST_RAISE, input)
            "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER, input)
            "volume_mute" -> muteVolume()
            "volume_unmute" -> unmuteVolume()
            "set_volume" -> setVolume(input)
            "get_volume" -> getVolumeInfo()
            else -> "Unknown media action: $action. Use: play, pause, next, previous, stop, volume_up, volume_down, volume_mute, set_volume, get_volume"
        }
    }

    private fun sendMediaKey(keyCode: Int, successMsg: String): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)
            successMsg
        } catch (e: Exception) {
            "Failed to control media: ${e.message}"
        }
    }

    private fun adjustVolume(direction: Int, input: Map<String, Any>): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val steps = (input["steps"] as? Number)?.toInt() ?: 1
        val stream = resolveStream(input["stream"] as? String)

        repeat(steps) {
            audioManager.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI)
        }

        val current = audioManager.getStreamVolume(stream)
        val max = audioManager.getStreamMaxVolume(stream)
        val pct = (current * 100) / max
        return "Volume ${if (direction == AudioManager.ADJUST_RAISE) "raised" else "lowered"} to $pct% ($current/$max)"
    }

    private fun muteVolume(): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_MUTE,
            AudioManager.FLAG_SHOW_UI
        )
        return "Volume muted"
    }

    private fun unmuteVolume(): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_UNMUTE,
            AudioManager.FLAG_SHOW_UI
        )
        return "Volume unmuted"
    }

    private fun setVolume(input: Map<String, Any>): String {
        val level = (input["level"] as? Number)?.toInt()
        val percentage = (input["percentage"] as? Number)?.toInt()
        val stream = resolveStream(input["stream"] as? String)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(stream)

        val targetLevel = when {
            level != null -> level.coerceIn(0, max)
            percentage != null -> (percentage * max / 100).coerceIn(0, max)
            else -> return "Provide 'level' or 'percentage'"
        }

        audioManager.setStreamVolume(stream, targetLevel, AudioManager.FLAG_SHOW_UI)
        val pct = (targetLevel * 100) / max
        return "Volume set to $pct% ($targetLevel/$max)"
    }

    private fun getVolumeInfo(): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streams = listOf(
            "Media" to AudioManager.STREAM_MUSIC,
            "Ring" to AudioManager.STREAM_RING,
            "Notification" to AudioManager.STREAM_NOTIFICATION,
            "Alarm" to AudioManager.STREAM_ALARM,
            "Call" to AudioManager.STREAM_VOICE_CALL
        )

        return "Volume levels:\n" + streams.joinToString("\n") { (name, stream) ->
            val current = audioManager.getStreamVolume(stream)
            val max = audioManager.getStreamMaxVolume(stream)
            val pct = if (max > 0) (current * 100) / max else 0
            "- $name: $pct% ($current/$max)"
        }
    }

    private fun resolveStream(stream: String?): Int = when (stream?.lowercase()) {
        "ring", "ringtone" -> AudioManager.STREAM_RING
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "alarm" -> AudioManager.STREAM_ALARM
        "call", "voice" -> AudioManager.STREAM_VOICE_CALL
        "system" -> AudioManager.STREAM_SYSTEM
        else -> AudioManager.STREAM_MUSIC
    }
}
