# Contributing to AndroClaw

Thanks for your interest in AndroClaw! This is a single-developer project right now, but contributions — bug reports, feature ideas, and PRs — are very welcome.

## Quick Links

- **Project README:** [`README.md`](README.md)
- **Architecture map:** [`CLAUDE.md`](CLAUDE.md)
- **License:** [`LICENSE`](LICENSE) (MIT)

## Maintainers

- **Pranav Patil** — Creator and current sole maintainer. Designs the agentic loop, tools, and UI.

If you'd like to help maintain a specific surface (a new LLM provider, a new tool category, the accessibility automation layer, the overlay UX, etc.), open an issue first and say hello.

## How to Contribute

1. **Bugs & small fixes** → Open a PR. Please include reproduction steps, the device model + Android version, and a logcat snippet if relevant.
2. **New tools or LLM providers** → Open an issue or draft PR first so we can agree on the schema and naming before you go deep. The tool surface is part of the agent's contract with the model — every addition is permanent-ish.
3. **New features / UX changes** → Open an issue first with a short description and (ideally) a screenshot or mock.
4. **Refactor-only PRs** → Please don't, unless a specific refactor was requested in an issue. Cleanups that touch many files are hard to review against an actively-evolving agent loop.
5. **Questions** → Open a GitHub Discussion or an issue tagged `question`.

## Before You Open a PR

- Build a debug APK and install it on a real device or emulator: `./gradlew installDebug`.
- Run the unit tests: `./gradlew test`.
- Run lint: `./gradlew lint`.
- Manually exercise any tool or screen you touched. For tools that hit the network (LLM providers, GitHub, web search/fetch), confirm they don't run on the main thread — wrap blocking work in `withContext(Dispatchers.IO)`.
- Keep PRs **focused**. One thing per PR. Don't bundle a bug fix with a refactor with a new tool.
- Describe **what** changed and **why**. Screenshots / screen recordings are gold for any UI change.
- Use **American English** spelling in code, comments, docs, and UI strings.

## Code Style

- **Language:** Kotlin, targeting JDK 17 / Android 10+ (`minSdk` 29).
- **UI:** Jetpack Compose + Material 3. Prefer stateless `@Composable` functions; lift state into ViewModels.
- **DI:** Hilt. Tool handlers are `@Singleton` and receive their dependencies via constructor injection.
- **Concurrency:** Coroutines. Anything that does network or disk I/O must run on `Dispatchers.IO`. Tool handlers' `execute` is `suspend`, but that does not automatically move you off the main thread — wrap blocking work explicitly.
- **Errors:** Return user-facing strings from tool handlers; never let exceptions escape `ToolExecutor.execute`. Log with `Log.e(TAG, "...", e)` so failures are debuggable from logcat.
- **No telemetry, no analytics, no crash reporters** unless explicitly opted-in by the user.
- **Comments:** Brief, only where the logic is non-obvious. Don't narrate code that already reads clearly.
- **File size:** Aim for < ~700 LOC per file. Extract helpers when a tool handler grows past that.

## Adding a New Tool

When adding a tool, all six steps must be done in the same PR or the tool won't be reachable:

1. Create `app/src/main/java/com/androclaw/tools/MyToolHandler.kt` as a `@Singleton` with `suspend fun execute(input: Map<String, Any>): String`. Wrap network/disk in `withContext(Dispatchers.IO)`.
2. Add the schema in `app/src/main/java/com/androclaw/api/ToolDefinitions.kt`. Use `enum` for closed string sets so the model picks valid values.
3. Wire it into `app/src/main/java/com/androclaw/tools/ToolExecutor.kt` (constructor injection + `when` branch in `dispatch`).
4. Register the name in `app/src/main/java/com/androclaw/utils/Constants.kt` (`ALL_TOOL_NAMES` + the system-prompt guidance block).
5. Add a `describeToolCall` entry in `app/src/main/java/com/androclaw/api/ClaudeRepository.kt` so the UI shows a friendly progress line.
6. If you need new permissions, update `AndroidManifest.xml` and `app/src/main/java/com/androclaw/utils/PermissionHelper.kt`.

Try to keep tool input schemas tight and self-describing. The model only sees the schema — clear `description` strings massively improve tool-call quality.

## Adding a New LLM Provider

1. Implement `api/provider/LlmProvider.kt`.
2. Register the provider in `api/provider/ProviderRegistry.kt`.
3. If the provider is OpenAI-compatible, you can just instantiate `OpenAIProvider` with a custom `baseUrl` and `supportedModels`.
4. Add a settings entry so users can paste the API key.
5. If the provider supports SSE streaming, wire it into the streaming path; otherwise it falls back to non-streaming automatically.

## Database Migrations

Room migrations live in `app/src/main/java/com/androclaw/db/AndroClawDatabase.kt`. Always add a real `Migration(from, to)` when changing the schema. `fallbackToDestructiveMigration()` is kept only as a final safety net — never rely on it.

## AI / Vibe-Coded PRs Welcome

Built with Claude, Codex, Cursor, or another AI tool? Awesome — just mark it.

In the PR description, please include:

- [ ] Note that the PR is AI-assisted
- [ ] Degree of testing (untested / lightly tested / fully tested on a real device)
- [ ] Confirm you understand what the code does and have read the diff yourself
- [ ] Resolve or reply to bot review comments after addressing them

## Reporting a Vulnerability

If you find a security issue (token leakage, accessibility-service abuse, intent injection, sandbox escapes, etc.), **do not open a public issue.** Email the maintainer privately with:

1. Title and severity assessment
2. Affected component (file path + Android version + device)
3. Step-by-step reproduction
4. Demonstrated impact
5. Suggested remediation

Reports without reproduction steps and demonstrated impact will be deprioritized.

## Code of Conduct

Be kind. Assume good faith. Disagree with code, not with people. Harassment, discrimination, or personal attacks will get you removed from the project — there are no exceptions and no warnings.
