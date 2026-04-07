# Context Management

AndroClaw manages context window usage to prevent conversations from exceeding model limits. Inspired by OpenClaw's context engine.

## Token Estimation

Tokens are estimated at ~4 characters per token across all models. The estimation covers:
- System prompt text
- All conversation messages (user + assistant)
- Tool use blocks and tool results
- Tool definition schemas
- Per-message overhead (~4 tokens for role/structure)

## Context Windows

Each model has a known context window size:

| Model | Context Window |
|-------|---------------|
| Claude Opus/Sonnet/Haiku | 200,000 tokens |
| GPT-4o / GPT-4o Mini | 128,000 tokens |
| GPT-3.5 Turbo | 16,385 tokens |
| Gemini 2.5 Pro/Flash | 1,000,000 tokens |
| Llama 3.3 70B (Groq) | 131,072 tokens |
| Mixtral 8x7B (Groq) | 32,768 tokens |

Fallback: 128,000 tokens if model info not found.

## Auto-Compaction

When conversation token usage exceeds **75%** of the context window, compaction triggers automatically:

### Step 1: Prune Tool Results
Tool results older than the last 4 message pairs are truncated to 2,000 characters. This recovers context space without losing conversational continuity.

### Step 2: Summarize Old Messages
If pruning isn't enough, the older portion of the conversation is sent to the LLM with a summarization prompt:
- Preserves key facts, decisions, and user preferences
- Preserves important tool results and outcomes
- Preserves ongoing task context
- Target: under 500 words

### Step 3: Replace History
The conversation history is replaced with:
1. A compact summary (as a user/assistant message pair)
2. The most recent 4 message pairs (8 messages), kept intact

## UI Indicators

The context usage is shown in the top bar subtitle:
- **Normal:** `Online · 23% context`
- **Near limit (>75%):** Text turns red
- **During compaction:** Shows `Compacting...`

## Usage Info

The `ContextUsageInfo` data class provides:
- `usedTokens` — estimated tokens consumed
- `contextWindow` — model's total capacity
- `messageCount` — number of messages in history
- `usageFraction` — 0.0 to 1.0+ ratio
- `usagePercent` — integer percentage (0-100)
- `isNearLimit` — true when >75%
- `isOverLimit` — true when >95%
