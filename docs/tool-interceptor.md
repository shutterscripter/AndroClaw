# Tool Interceptor

The tool interceptor provides safety guardrails and observability for all tool executions. Inspired by OpenClaw's hook system (`before_tool_call` / `after_tool_call`).

## Features

### Rate Limiting
Prevents runaway agent loops from executing too many actions in a single turn.

| Tool Category | Limit Per Turn |
|---------------|---------------|
| Messaging tools (SMS, WhatsApp, email, phone) | 3 calls |
| All other tools | 10 calls |

Counters reset at the start of each user message. When a limit is hit, the tool returns an error message to the LLM explaining the rate limit, prompting it to ask the user before retrying.

### Audit Logging
Every tool execution is recorded with:
- Tool name
- Summarized input (human-readable, not raw JSON)
- Result preview (first 200 characters)
- Success/failure flag
- Execution duration (milliseconds)
- Timestamp

The last 100 entries are kept in memory. Audit logs are also written to Android Logcat at `TAG=ToolInterceptor`.

### Tool Classification

**Destructive tools** (outbound / state-changing):
- `send_sms`, `send_whatsapp`, `send_email`, `make_phone_call`
- `control_app_ui`, `toggle_setting`

**Safe tools** (read-only):
- `web_search`, `web_fetch`, `device_info`, `list_apps`, `get_contacts`
- `get_location`, `call_log`, `read_sms`, `take_screenshot`, `screen_time`
- `memory`, `notes`, `skills`, `schedule`, `notifications`
- `media_control`, `brightness_control`, `clipboard`, `text_to_speech`

## Execution Flow

```
ToolExecutor.execute(toolName, input)
    │
    ▼
ToolInterceptor.beforeExecute()
    │
    ├── Rate limit exceeded? → Return error string
    │
    ▼
dispatch(toolName, input)  ← actual tool execution
    │
    ▼
ToolInterceptor.afterExecute()  ← audit log entry
    │
    ▼
Return result
```

## API

```kotlin
// Check before executing
val blocked = interceptor.beforeExecute(toolName, input)
if (blocked != null) return blocked  // Rate limited

// Log after executing
interceptor.afterExecute(toolName, input, result, durationMs)

// Reset at start of each user message
interceptor.resetTurnCounters()

// Debugging
interceptor.getAuditSummary(limit = 20)  // Formatted log
interceptor.getTurnStats()                // Map<String, Int> of call counts
interceptor.isDestructive(toolName)       // Safety check
interceptor.isSafe(toolName)              // Safety check
```
