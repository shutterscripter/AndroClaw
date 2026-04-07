# Agentic Loop

The agentic loop is the core execution engine of AndroClaw. It orchestrates multi-step conversations between the user, the LLM, and device tools.

## Execution Flow

```
User message
    │
    ▼
Reset tool interceptor counters
    │
    ▼
Add message to conversation history
    │
    ▼
Prune old tool results (>2KB in older messages)
    │
    ▼
Check context usage — auto-compact if >75% of window
    │
    ▼
Emit ContextUpdate event to UI
    │
    ▼
┌─► Send to LLM (streaming or non-streaming)
│       │
│       ▼
│   Add assistant response to history
│       │
│       ▼
│   Tool calls in response?
│       │
│   No ─┼──► Return final text response
│       │
│   Yes ▼
│   Execute each tool via ToolInterceptor → ToolExecutor
│       │
│       ▼
│   Add tool results to history
│       │
│       ▼
│   Loop (max 10 iterations)
└───────┘
```

## Key Behaviors

### Tool Execution
- Tools execute sequentially within a turn
- Each tool passes through the interceptor (rate limiting + audit)
- Results are added as tool_result content blocks
- Vision-capable tools (screenshot) return base64 images

### Streaming Mode
When streaming is enabled:
1. Text tokens emit as `StreamingText` events for real-time UI
2. Tool calls are assembled from stream fragments (ToolUseStart → ToolInputDelta → ToolUseEnd)
3. Text is flushed when a tool call begins

### Non-Streaming Mode
When streaming is disabled or unsupported:
1. Full response received at once
2. Retry with exponential backoff on 429/500/502/503 errors (3 attempts)

### Exit Conditions
The loop exits when:
- No tool calls in the response
- `stop_reason` is `"end_turn"` or `"stop"`
- Maximum 10 iterations reached

## Events

The agentic loop emits events for UI consumption:

| Event | When | UI Effect |
|-------|------|-----------|
| `ToolExecuting` | Before tool runs | Shows tool status text |
| `ToolCompleted` | After tool runs | Clears tool status |
| `StreamingText` | Each text token | Appends to streaming bubble |
| `FinalResponse` | Loop complete | Clears streaming, saves message |
| `ContextUpdate` | Before loop starts | Updates context % in top bar |
| `Compacting` | During compaction | Shows "Compacting..." status |
| `Error` | On failure | Shows error message |

## Context Management

Before each agent turn:
1. **Prune** — truncate tool results >2KB in older messages
2. **Compaction check** — if token usage >75% of model's context window:
   - Build summary prompt from old messages
   - Send to LLM for summarization
   - Replace old messages with compact summary
   - Keep last 4 message pairs intact
3. **Usage tracking** — emit current token usage percentage

## System Prompt Assembly

The system prompt is assembled fresh for each turn by `SystemPromptManager`:
1. Identity (hardcoded)
2. Persona (customizable)
3. Tool guidance (hardcoded)
4. User profile (if set)
5. Custom instructions (if set)
6. Memory context (auto-injected from database, grouped by type)
