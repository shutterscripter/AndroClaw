package com.androclaw.api.provider

import android.content.SharedPreferences
import com.androclaw.utils.Constants
import com.androclaw.utils.normalizeOllamaOpenAiBaseUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Registry of all available LLM providers.
 * Manages provider instances and model lookups.
 */
@Singleton
class ProviderRegistry @Inject constructor(
    okHttpClient: OkHttpClient,
    @Named("regular") private val prefs: SharedPreferences
) {
    private val providers = mutableMapOf<String, LlmProvider>()

    init {
        val claude = ClaudeProvider(okHttpClient)
        val openai = OpenAIProvider(okHttpClient)
        val gemini = GeminiProvider(okHttpClient)

        // Groq uses OpenAI-compatible API
        val groq = OpenAIProvider(
            okHttpClient = okHttpClient,
            id = "groq",
            displayName = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            supportedModels = listOf(
                ModelInfo("llama-3.3-70b-versatile", "Llama 3.3 70B", 131072),
                ModelInfo("llama-3.1-8b-instant", "Llama 3.1 8B", 131072),
                ModelInfo("mixtral-8x7b-32768", "Mixtral 8x7B", 32768),
                ModelInfo("gemma2-9b-it", "Gemma 2 9B", 8192)
            )
        )

        // OpenRouter — aggregates many models via OpenAI-compatible API
        val openRouter = OpenAIProvider(
            okHttpClient = okHttpClient,
            id = "openrouter",
            displayName = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            supportedModels = listOf(
                ModelInfo("anthropic/claude-sonnet-4", "Claude Sonnet 4 (via OR)", 200000),
                ModelInfo("openai/gpt-4o", "GPT-4o (via OR)", 128000),
                ModelInfo("google/gemini-2.5-pro", "Gemini 2.5 Pro (via OR)", 1000000),
                ModelInfo("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B (via OR)", 131072),
                ModelInfo("deepseek/deepseek-r1", "DeepSeek R1 (via OR)", 65536)
            )
        )

        // Ollama — OpenAI-compatible endpoint (ollama serve). Base URL from Settings.
        val ollama = OpenAIProvider(
            okHttpClient = okHttpClient,
            id = "ollama",
            displayName = "Ollama (local)",
            baseUrl = "http://10.0.2.2:11434/v1",
            dynamicBaseUrl = {
                val saved = prefs.getString(Constants.PREF_OLLAMA_BASE_URL, "")?.trim().orEmpty()
                if (saved.isNotEmpty()) normalizeOllamaOpenAiBaseUrl(saved) else "http://10.0.2.2:11434/v1"
            },
            supportedModels = listOf(
                ModelInfo("llama3.2", "Llama 3.2", 131072),
                ModelInfo("llama3.1", "Llama 3.1", 131072),
                ModelInfo("mistral", "Mistral", 32768),
                ModelInfo("qwen2.5", "Qwen 2.5", 32768),
                ModelInfo("phi3", "Phi 3", 131072),
                ModelInfo("gemma2", "Gemma 2", 8192),
                ModelInfo("deepseek-r1:8b", "DeepSeek R1 8B", 8192)
            )
        )

        register(claude)
        register(openai)
        register(gemini)
        register(groq)
        register(openRouter)
        register(ollama)
    }

    fun register(provider: LlmProvider) {
        providers[provider.id] = provider
    }

    fun getProvider(id: String): LlmProvider? = providers[id]

    fun getAllProviders(): List<LlmProvider> = providers.values.toList()

    fun getProviderForModel(modelId: String): LlmProvider? {
        return providers.values.firstOrNull { provider ->
            provider.supportedModels.any { it.id == modelId }
        }
    }

    /**
     * Get all available models across all providers.
     * Returns pairs of (providerId, ModelInfo).
     */
    fun getAllModels(): List<Pair<String, ModelInfo>> {
        return providers.flatMap { (providerId, provider) ->
            provider.supportedModels.map { providerId to it }
        }
    }
}
