# Getting Started

## Installation

### From Source
```bash
cd AndroClaw
./gradlew assembleDebug
./gradlew installDebug
```

### Build Variants
- **Debug:** Full logging, development builds
- **Play Release:** Google Play-safe (excludes restricted SMS/Call Log APIs)
- **Third-Party Release:** Full permissions, sideload distribution

## First Launch

### 1. Onboarding
AndroClaw walks you through a 5-step onboarding:
1. **Welcome** — feature overview
2. **Core Permissions** — calls, messages, contacts, calendar
3. **Files & Media** — file access and storage
4. **Floating Assistant** — overlay permission for the floating button
5. **Summary** — ready to use

Permissions can be skipped and granted later.

### 2. API Key Setup
Go to **Settings** and configure your LLM provider:
1. Select a **Provider** (Claude, OpenAI, Gemini, Groq, or OpenRouter)
2. Enter your **API Key**
3. Choose a **Model**

### 3. Bootstrap Conversation
On your first message, AndroClaw introduces itself and asks a few questions to personalize the experience. It saves your name, preferences, and communication style to memory.

## Basic Usage

### Chat
Type naturally in the input bar:
- "Call Mom"
- "What's the weather like?"
- "Find my resume and share it on WhatsApp"
- "Turn off WiFi and lower brightness"

### Voice Input
Tap the microphone icon for speech-to-text input.

### Slash Commands
Type `/` followed by a skill trigger:
- `/morning` — morning briefing
- `/news` — top headlines
- `/health` — device health report
- `/see` — screenshot and describe

### Floating Button
When enabled, a floating button stays on screen:
- **Tap** — open overlay chat
- **Long-press** — open full app
- **Drag** — reposition

## Settings

| Setting | Description |
|---------|-------------|
| **Provider** | Select LLM provider |
| **API Key** | Per-provider API key (encrypted) |
| **Model** | Select model within provider |
| **Streaming** | Toggle real-time token streaming |
| **Persona** | Customize AI behavior and tone |
| **About You** | User profile for personalization |
| **Custom Instructions** | Standing orders the AI always follows |
| **Floating Assistant** | Enable/disable overlay button |
| **Accessibility** | Link to enable accessibility service |
| **Enabled Tools** | Toggle individual tools on/off |

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Install to device
./gradlew installDebug

# Run tests
./gradlew test

# Lint check
./gradlew ktlintCheck

# Format code
./gradlew ktlintFormat
```

## Adding a New Tool

1. Create `tools/MyToolHandler.kt` — `@Singleton` with `suspend fun execute(input: Map<String, Any>): String`
2. Add tool definition in `api/ToolDefinitions.kt`
3. Register in `tools/ToolExecutor.kt` (constructor + when branch)
4. Add tool name to `utils/Constants.kt` → `ALL_TOOL_NAMES`
5. Add system prompt guidance in `api/SystemPromptManager.kt`
6. Add `describeToolCall` entry in `api/ClaudeRepository.kt`
7. If new permissions needed: update `AndroidManifest.xml` + `utils/PermissionHelper.kt`

## Adding a New LLM Provider

For OpenAI-compatible APIs:
1. Instantiate `OpenAIProvider` with custom `baseUrl` and `supportedModels`
2. Register in `api/provider/ProviderRegistry.kt` init block

For non-compatible APIs:
1. Implement `api/provider/LlmProvider.kt` interface
2. Register in `ProviderRegistry.kt`
