# AndroClaw Overview

AndroClaw is a powerful AI-powered Android assistant that runs directly on your device. It connects to multiple LLM providers and executes real actions on your phone — from sending messages and making calls to searching the web, reading files, taking notes, and controlling other apps.

## Architecture

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Dependency Injection:** Hilt (Dagger)
- **Database:** Room (SQLite) with versioned migrations (v2-v8)
- **Networking:** OkHttp + Retrofit + Jsoup
- **Background Tasks:** WorkManager
- **Min SDK:** 29 (Android 10)
- **Target SDK:** 35

## Core Components

| Component | Description |
|-----------|-------------|
| **Agentic Loop** | Up to 10 tool-use iterations per request with automatic tool chaining |
| **Multi-Provider LLM** | Claude, OpenAI, Gemini, Groq, OpenRouter — 5 providers, 15+ models |
| **30+ Device Tools** | SMS, calls, files, web, device control, media, productivity, and more |
| **Streaming** | SSE token-by-token output with real-time UI updates |
| **Context Management** | Token estimation, auto-compaction, tool result pruning |
| **Modular System Prompt** | Customizable persona, user profile, and standing instructions |
| **Typed Memory** | Persistent cross-session memory with types: user_profile, preference, fact, instruction, reference |
| **Skills System** | Custom slash commands with 10 built-in skills + import/export |
| **Scheduled Automation** | One-shot and recurring AI tasks via WorkManager |
| **Tool Interceptor** | Rate limiting, audit logging, and safety classification |
| **Bootstrap** | First-run getting-to-know-you conversation |
| **Floating Assistant** | Always-on overlay button for quick access from any app |
| **Accessibility** | UI automation for controlling other apps |

## Project Structure

```
app/src/main/java/com/androclaw/
├── api/                    # LLM providers, agentic loop, prompt management
│   ├── provider/           # Multi-LLM provider abstraction
│   ├── BootstrapManager    # First-run onboarding conversation
│   ├── ClaudeRepository    # Agentic loop + streaming
│   ├── ContextManager      # Token accounting + compaction
│   ├── SystemPromptManager # Modular prompt assembly
│   └── ToolDefinitions     # All 30+ tool schemas
├── db/                     # Room database entities + DAOs
├── di/                     # Hilt dependency injection
├── service/                # Background services
│   ├── AccessibilityService
│   ├── FloatingButtonService
│   ├── NotificationReaderService
│   ├── ScheduleWorker
│   └── BootReceiver
├── tools/                  # 30+ tool handlers
│   ├── ToolExecutor        # Central dispatcher
│   └── ToolInterceptor     # Rate limiting + audit
├── ui/                     # Jetpack Compose screens
│   ├── ChatScreen
│   ├── SettingsScreen
│   ├── OnboardingScreen
│   └── components/
└── utils/                  # Constants, permissions, voice input
```
