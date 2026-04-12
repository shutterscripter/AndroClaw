package com.androclaw.tools

import javax.inject.Inject
import javax.inject.Singleton

/**
 * `think` — a deliberate no-op tool.
 *
 * It exists so the model can pause between perception (screen_observe /
 * take_screenshot) and action (navigate_guide / control_app_ui) and write
 * out a short plan: "what I see → what the user wants → next concrete
 * step". This dramatically improves multi-step reliability for flows like
 * "send 100 to Rucha" because the model isn't forced to pick the next tap
 * in the same turn it first sees the screen.
 *
 * The call does nothing except echo a short confirmation, which keeps the
 * agentic loop alive for one more turn. The thought itself is visible in
 * the chat transcript via ClaudeRepository.describeToolCall.
 */
@Singleton
class ThinkToolHandler @Inject constructor() {

    suspend fun execute(input: Map<String, Any>): String {
        val thought = (input["thought"] as? String)?.trim().orEmpty()
        if (thought.isEmpty()) {
            return "think: no thought provided — pass a short plan as 'thought'."
        }
        val preview = thought.lineSequence().firstOrNull()?.take(80) ?: ""
        return "Thought recorded: $preview"
    }
}
