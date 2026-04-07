# AndroClaw — On-Device AI Assistant for Android

<p align="center">
  <strong>Your phone, but agentic.</strong>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge" alt="MIT License"></a>
  <img src="https://img.shields.io/badge/Platform-Android%2010%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 10+">
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin + Compose">
  <img src="https://img.shields.io/badge/LLM-Multi--provider-FF6F00?style=for-the-badge" alt="Multi-provider LLM">
</p>

**AndroClaw** is a powerful AI assistant that runs natively on your Android device. It connects to multiple LLM providers and executes _real_ actions on your phone — sending messages, making calls, searching the web, reading files, taking notes, controlling other apps via accessibility, capturing and reasoning about screenshots, and more.

It is designed as a single-user, local-first assistant: keys live in `EncryptedSharedPreferences`, conversations and memory live in a Room database on-device, and tool calls run inside the app process. There is no backend to point at and no account to create.

Built by **Pranav Patil**.

## Highlights

- **Multi-provider LLMs** — Anthropic Claude (Opus 4.6 / Sonnet 4.6 / Haiku 4.5), OpenAI (GPT-4o family), Google Gemini (2.5 Pro / Flash, 2.0 Flash), Groq (Llama 3.x, Mixtral, Gemma 2), OpenRouter (100+ models), and any OpenAI-compatible endpoint.
- **Real streaming** — token-by-token Server-Sent Events for Claude, OpenAI, and Gemini. Automatic fallback to non-streaming when a model doesn't support it.
- **30+ device tools** — communication, web, files, memory, notes, apps, media, device controls, productivity, and more (full table below).
- **Agentic loop** — up to 10 tool-use iterations per request, automatic tool chaining, vision support (screenshots → base64 → model), memory auto-injection into the system prompt.
- **Skills system** — create your own slash commands (`/morning`, `/commute`, `/health`, …) that chain multiple tools. Stored locally and persisted across sessions.
- **Floating button + overlay chat** — talk to AndroClaw from anywhere via an overlay launcher.
- **Accessibility-powered automation** — taps, swipes, text input, and on-screen reasoning across other apps.
- **Notification reading** — surface and act on incoming notifications via `NotificationListenerService`.
- **Voice in/out** — speech-to-text input and TTS replies.
- **Security-first** — API keys in `EncryptedSharedPreferences` (AES-256-GCM), runtime permissions for every sensitive surface, no telemetry.

## Quick start

You'll need Android Studio (Hedgehog or newer), JDK 17, and an Android device or emulator running **Android 10 (API 29)** or higher.

```bash
git clone <your fork of this repo>
cd AndroClaw

# Build a debug APK
./gradlew assembleDebug

# Or install directly to a connected device / running emulator
./gradlew installDebug

# Run unit tests
./gradlew test
```

On first launch:

1. Walk through onboarding.
2. Open **Settings** and paste an API key for at least one provider (Claude, OpenAI, Gemini, Groq, OpenRouter, or a custom OpenAI-compatible base URL).
3. Optionally paste a **GitHub Personal Access Token** to enable the `github` tool.
4. Grant the runtime permissions you want to use (SMS, contacts, location, notifications, accessibility, overlay, …). Each tool prompts only when first used.
5. Start chatting, or tap the floating button to summon AndroClaw on top of any app.

## Architecture

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **DI:** Hilt (Dagger)
- **Database:** Room (SQLite) with versioned migrations
- **Networking:** OkHttp + Retrofit + Jsoup
- **API:** Multi-provider LLM abstraction with SSE streaming
- **Min SDK:** 29 (Android 10)
- **Target SDK:** 35

```
app/src/main/java/com/androclaw/
├── api/
│   ├── provider/                 # Multi-LLM provider abstraction
│   │   ├── LlmProvider.kt
│   │   ├── ClaudeProvider.kt
│   │   ├── OpenAIProvider.kt
│   │   ├── GeminiProvider.kt
│   │   └── ProviderRegistry.kt
│   ├── ClaudeRepository.kt       # Agentic loop, tool orchestration, streaming
│   ├── ClaudeApiService.kt
│   ├── ClaudeResponseParser.kt
│   ├── ToolDefinitions.kt        # 30+ tool schemas
│   └── models/ClaudeModels.kt
├── db/                           # Room: conversations, messages, memory, notes, skills
├── di/AppModule.kt               # Hilt graph
├── service/                      # Accessibility, floating button, notification reader, overlay chat, boot
├── tools/                        # 30+ tool handlers + ToolExecutor router
├── ui/                           # Compose screens (chat, settings, onboarding) + components
└── utils/                        # Constants, permissions, voice input
```

For a deeper, file-by-file map, see [`CLAUDE.md`](CLAUDE.md).

## Tools

| Category          | Tools                                                                                                          |
| ----------------- | -------------------------------------------------------------------------------------------------------------- |
| **Communication** | `send_sms`, `read_sms`, `make_phone_call`, `call_log`, `send_whatsapp`, `send_email`, `get_contacts`           |
| **Web**           | `web_search`, `web_fetch`, `browse_web`                                                                        |
| **Files**         | `file_manager` (find / open / share / list / info / recent / read / tree / grep / glob)                        |
| **Memory & Notes**| `memory` (save / recall / search), `notes` (create / read / update / delete / list / search)                   |
| **Apps**          | `open_app`, `list_apps`, `control_app_ui`                                                                      |
| **Media**         | `media_control`, `take_screenshot` (with vision analysis), `text_to_speech`                                    |
| **Device**        | `device_info`, `toggle_setting`, `brightness_control`, `get_location`                                          |
| **Productivity**  | `create_calendar_event`, `set_reminder`, `set_alarm`, `clipboard`, `notifications` (read & act)                |
| **Social**        | `auto_scroll_feed` (Reels, Shorts, TikTok, Snapchat Spotlight), `share_content`                                |
| **GitHub**        | `github` — PRs, issues, CI runs, repo file read/write/delete via the Contents API, search, raw REST escape hatch |
| **Custom**        | `skills` — create / list / run custom slash commands                                                           |

## Skills

Skills are user-defined slash commands stored in the local database. They chain multiple tools into a single trigger:

```
/morning   → "Check weather, read my notifications, list today's calendar events"
/commute   → "Get my location, search for traffic conditions to my work address"
/findme    → "Get my location and share it via WhatsApp to my partner"
```

Create them in-app under **Skills**, or via the `skills` tool from chat.

## GitHub tool

The `github` tool gives AndroClaw direct access to the GitHub REST API from your phone — no `gh` CLI, no backend, just a Personal Access Token stored in `EncryptedSharedPreferences` and HTTPS calls straight to `api.github.com`. All HTTP work runs on `Dispatchers.IO`, so the main thread is never blocked.

**Setup:** create a [PAT](https://github.com/settings/tokens) with the scopes you need (`repo` for private repos and file writes, `workflow` for re-running CI, `notifications` for the inbox), then paste it under **Settings → GitHub** and tap **Save**. The indicator should read `Saved (40 chars)`.

**What you can do from chat:**

- **Pull requests** — `list_prs`, `view_pr`, `pr_checks`, `create_pr_comment`, `merge_pr` (squash / merge / rebase)
- **Issues** — `list_issues`, `view_issue`, `create_issue`, `comment_issue`, `close_issue`
- **GitHub Actions / CI** — `list_runs`, `view_run`, `rerun` (with optional `failed_only`)
- **Repos / user / search** — `list_repos`, `list_notifications`, `search_repos`, `search_issues`, `get_user`
- **File contents** — `read_file`, `write_file`, `delete_file`, `list_dir`. Edits commit straight to a branch via the Contents API; `sha` is auto-fetched on writes, so you can pass just `repo` + `path` + `content`. If `branch` is omitted, the repo's default branch is used.
- **Raw escape hatch** — `api` for any GitHub REST endpoint with a custom `method` and `body`.

You normally don't call this tool by hand — you ask AndroClaw in plain English:

> "show me my open PRs in pranavpatil/AndroClaw"
> "merge PR #42 with squash and post a thanks comment"
> "create an issue titled 'Reels auto-scroll skips first item' in pranavpatil/AndroClaw"
> "read the README from openclaw/openclaw and summarize it"
> "rerun the failed jobs of run 9876543210"

The model picks the action and parameters automatically. The tool handler returns the result inline so the agent can chain follow-up calls in the same turn.

**Security:** the PAT is stored in `EncryptedSharedPreferences` (AES-256-GCM) under the key `github_token` and only ever sent in the `Authorization: Bearer …` header to `api.github.com`. Revoke at <https://github.com/settings/tokens> if your phone is lost. Prefer fine-grained tokens scoped to the repos you actually use.

Full reference (every action, all parameters, example payloads): [`docs/github-tool.md`](docs/github-tool.md).

## Security

- API keys are stored in `EncryptedSharedPreferences` (AES-256-GCM, hardware-backed where available).
- Every sensitive operation goes through `PermissionHelper` and the standard Android runtime permission flow.
- Accessibility service is **opt-in** and only used when you enable it in system settings.
- Notification reading requires explicitly granting `NotificationListenerService` access.
- No analytics, no telemetry, no remote logging.
- Inbound messages and notifications should be treated as **untrusted input** — the agentic loop has guardrails, but you still own the device.

## Adding a new tool

1. Create `tools/MyToolHandler.kt` — `@Singleton` class with `suspend fun execute(input: Map<String, Any>): String`. Network and disk work belongs on `Dispatchers.IO`.
2. Add the tool definition in `api/ToolDefinitions.kt`.
3. Register it in `tools/ToolExecutor.kt` (constructor + `when` branch).
4. Add the tool name to `utils/Constants.kt` → `ALL_TOOL_NAMES` and the system-prompt guidance block.
5. Add a `describeToolCall` entry in `api/ClaudeRepository.kt` so the UI shows a friendly progress line.
6. If new permissions are required, update `AndroidManifest.xml` and `utils/PermissionHelper.kt`.

## Adding a new LLM provider

1. Implement `api/provider/LlmProvider.kt`.
2. Register the implementation in `api/provider/ProviderRegistry.kt`.
3. For OpenAI-compatible APIs, just instantiate `OpenAIProvider` with a custom `baseUrl` and `supportedModels` list.

## Database migrations

Room migrations live in `db/AndroClawDatabase.kt`:

- v2 → v3: `memories` table
- v3 → v4: `notes` table
- v4 → v5: `skills` table

Always add a real migration. `fallbackToDestructiveMigration()` is kept only as a last resort.

## Contributing

PRs and issues are welcome. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the workflow, code style, and what kinds of changes are in/out of scope.

## License

[MIT](LICENSE) © 2026 AndroClaw / Pranav Patil
