package com.androclaw.tools

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        tts = TextToSpeech(context) { status ->
            isInitialized = (status == TextToSpeech.SUCCESS)
            if (isInitialized) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    suspend fun execute(input: Map<String, Any>): String {
        val action = input["action"] as? String ?: "speak"

        return when (action.lowercase()) {
            "speak", "say", "read_aloud" -> speak(input)
            "stop" -> stop()
            "status" -> status()
            else -> "Unknown TTS action: $action. Use: speak, stop, status"
        }
    }

    private suspend fun speak(input: Map<String, Any>): String {
        val text = input["text"] as? String
            ?: return "Missing 'text' to speak."

        if (!isInitialized) return "Text-to-speech engine not ready. Try again in a moment."

        val engine = tts ?: return "Text-to-speech not available."

        // Optional params
        val speed = (input["speed"] as? Number)?.toFloat() ?: 1.0f
        val pitch = (input["pitch"] as? Number)?.toFloat() ?: 1.0f
        val language = input["language"] as? String

        engine.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))

        if (language != null) {
            val locale = Locale.forLanguageTag(language)
            val result = engine.setLanguage(locale)
            if (result == TextToSpeech.LANG_NOT_SUPPORTED) {
                return "Language \"$language\" not supported for TTS."
            }
        }

        val utteranceId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId && deferred.isActive) deferred.complete(true)
            }
            override fun onError(id: String?) {
                if (id == utteranceId && deferred.isActive) deferred.complete(false)
            }
        })

        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

        // Wait up to 30 seconds for speech to complete
        val success = withTimeoutOrNull(30_000) { deferred.await() } ?: true

        return if (success) {
            "Spoke: \"${text.take(80)}${if (text.length > 80) "..." else ""}\""
        } else {
            "TTS failed to speak the text."
        }
    }

    private fun stop(): String {
        tts?.stop()
        return "Stopped speaking."
    }

    private fun status(): String {
        return if (isInitialized) {
            val engine = tts ?: return "TTS not available."
            val speaking = engine.isSpeaking
            val lang = engine.voice?.locale?.displayName ?: "default"
            "TTS ready. Speaking: $speaking. Language: $lang"
        } else {
            "TTS not initialized."
        }
    }
}
