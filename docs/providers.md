# LLM Providers

AndroClaw supports 5 LLM providers out of the box, with 15+ models and any OpenAI-compatible API endpoint.

## Provider Registry

All providers implement the `LlmProvider` interface with unified request/response types, streaming support, and tool definitions.

## Anthropic Claude

| Model | Context Window | Vision | Streaming | Tools |
|-------|---------------|--------|-----------|-------|
| Claude Opus 4.6 | 200,000 | Yes | Yes (SSE) | Yes |
| Claude Sonnet 4.6 | 200,000 | Yes | Yes (SSE) | Yes |
| Claude Haiku 4.5 | 200,000 | Yes | Yes (SSE) | Yes |

- **API:** `https://api.anthropic.com/v1/messages`
- **Auth:** `x-api-key` header
- **Key format:** `sk-ant-api03-...`

## OpenAI

| Model | Context Window | Vision | Streaming | Tools |
|-------|---------------|--------|-----------|-------|
| GPT-4o | 128,000 | Yes | Yes (SSE) | Yes |
| GPT-4o Mini | 128,000 | Yes | Yes (SSE) | Yes |
| GPT-4 Turbo | 128,000 | Yes | Yes (SSE) | Yes |
| GPT-3.5 Turbo | 16,385 | No | Yes (SSE) | Yes |

- **API:** `https://api.openai.com/v1/chat/completions`
- **Auth:** `Bearer` token
- **Key format:** `sk-...`

## Google Gemini

| Model | Context Window | Vision | Streaming | Tools |
|-------|---------------|--------|-----------|-------|
| Gemini 2.5 Pro | 1,000,000 | Yes | Yes | Yes |
| Gemini 2.5 Flash | 1,000,000 | Yes | Yes | Yes |
| Gemini 2.0 Flash | 1,000,000 | Yes | Yes | Yes |

- **API:** Google AI SDK-compatible endpoint
- **Key format:** `AIza...`

## Groq (OpenAI-compatible)

| Model | Context Window | Streaming | Tools |
|-------|---------------|-----------|-------|
| Llama 3.3 70B | 131,072 | Yes | Yes |
| Llama 3.1 8B | 131,072 | Yes | Yes |
| Mixtral 8x7B | 32,768 | Yes | Yes |
| Gemma 2 9B | 8,192 | Yes | Yes |

- **API:** `https://api.groq.com/openai/v1`
- **Key format:** `gsk_...`

## OpenRouter

Access 100+ models through a single API.

| Model | Context Window |
|-------|---------------|
| Claude Sonnet 4 (via OR) | 200,000 |
| GPT-4o (via OR) | 128,000 |
| Gemini 2.5 Pro (via OR) | 1,000,000 |
| Llama 3.3 70B (via OR) | 131,072 |
| DeepSeek R1 (via OR) | 65,536 |

- **API:** `https://openrouter.ai/api/v1`
- **Key format:** `sk-or-v1-...`

## Streaming

All providers support Server-Sent Events (SSE) streaming for token-by-token output:

- `StreamChunk.TextDelta` — text token
- `StreamChunk.ToolUseStart` — tool call begins
- `StreamChunk.ToolInputDelta` — tool input JSON fragment
- `StreamChunk.ToolUseEnd` — tool call complete
- `StreamChunk.Done` — stream finished
- `StreamChunk.Error` — error occurred

Streaming can be toggled per-user in Settings. When disabled, responses use non-streaming with retry (3 attempts, exponential backoff).

## Adding a Provider

For any OpenAI-compatible API, instantiate `OpenAIProvider` with a custom `baseUrl` and `supportedModels`, then register in `ProviderRegistry`. No new code needed for compatible APIs.
