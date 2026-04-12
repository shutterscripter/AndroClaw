# Tools Reference

AndroClaw has 35 built-in tools organized by category. The AI agent selects and chains tools automatically based on your requests.

## Communication

### send_sms
Send SMS messages to contacts or phone numbers.
- **Parameters:** `contact_name` or `phone_number`, `message` (required)
- Auto-resolves contact names to phone numbers

### read_sms
Read SMS history with filtering.
- **Parameters:** `contact_name`, `phone_number`, `query`, `folder` (inbox/sent/all), `limit`
- Returns messages with timestamps and sender info

### make_phone_call
Make phone calls with contact name resolution.
- **Parameters:** `contact_name` or `phone_number`
- Auto-resolves contact names from the address book

### call_log
Query call history with filters.
- **Parameters:** `contact_name`, `type` (missed/incoming/outgoing/rejected), `limit`
- Returns call records with duration and timestamps

### send_whatsapp
Send WhatsApp messages or files.
- **Parameters:** `contact_name` (required), `message`, `file_path`, `file_name`
- Supports text, files (PDFs, images, videos), or both

### send_email
Open email app with pre-filled fields.
- **Parameters:** `to` (required), `subject`, `body`, `cc`, `bcc`

### get_contacts
Search contacts by name.
- **Parameters:** `name_query` (required)
- Returns matching names and phone numbers

## Web Intelligence

### web_search
Search the web via DuckDuckGo with Google fallback.
- **Parameters:** `query` (required), `max_results`
- Returns actual text results the AI can reason about

### web_fetch
Fetch and read webpage content.
- **Parameters:** `url` (required), `extract_mode` (text/links/metadata)
- Strips navigation/ads, extracts readable text

### browse_web
Open a URL or search query in the browser.
- **Parameters:** `url` or `search_query`

## App Control

### open_app
Open installed apps with fuzzy matching.
- **Parameters:** `app_name` (required)
- Fuzzy matching: "insta" matches Instagram

### list_apps
List all installed apps.
- **Parameters:** `filter` (optional name filter)

### screen_observe
Set-of-Mark perception. Captures the current screen, walks the accessibility tree (with on-device ML Kit OCR fallback when the tree is sparse — Compose / React Native / Flutter / custom-canvas apps), draws numbered colored boxes around every interactive element, and returns the marked-up screenshot **plus** a text legend mapping each mark id to its role / label / pixel center / bounds. This is the perception half of the UI-control loop — call it before `control_app_ui` whenever you're operating on a third-party app whose accessibility tree is unreliable.
- **Parameters:** `app_package` (optional — launches the app first; omit to observe whatever's already foreground)
- **Output:** `[image + text legend]` — the model picks a mark number from the legend
- **Capture sources (preferred order):**
  1. **MediaProjection** — opt in via Settings → Screen capture. Faster, no rate limit, works on Android 10+, doesn't require the accessibility service for screen reading. Requires a one-time system consent dialog and shows a persistent foreground-service notification while running.
  2. **AccessibilityService.takeScreenshot** — automatic fallback. Requires the accessibility service AND Android 11+, and Android throttles it to ~1 capture/second.

### control_app_ui
Accessibility-based UI automation. The reliable way to use it on third-party apps is to call `screen_observe` first, then come back here with `tap_mark`.
- **Parameters:** `app_package` (required, can be blank), `actions` (required array)
- **Action types:**
  - `tap_mark` — `{type:"tap_mark", mark:N}` — tap the element marked N by the most recent `screen_observe` (most reliable for Compose / RN / custom UIs)
  - `tap_at` — `{type:"tap_at", x:N, y:N}` — raw pixel tap
  - `swipe` — `{type:"swipe", direction:"up|down|left|right", duration_ms:250}` — full-screen swipe (good for reels/feeds)
  - `tap` — legacy: by text (`text:ButtonLabel`) or ID (`id:com.app:id/button`); fragile on third-party apps
  - `type` — enter text into focused field
  - `scroll` — up or down (uses the first scrollable container in the a11y tree)
  - `wait` — delay in milliseconds
  - `back` / `home` — navigation

## File Management

### file_manager
Multi-action file explorer with 10 sub-actions.

| Action | Description | Key Parameters |
|--------|-------------|----------------|
| `find` | Search by name/type | `query`, `file_type`, `directory` |
| `open` | Open with default app | `path` |
| `share` | Share via system dialog | `path` |
| `list` | Directory contents | `path`, `show_hidden` |
| `info` | File metadata | `path` |
| `recent` | Recently modified files | `limit`, `file_type` |
| `read` | Read file content | `path`, `offset`, `limit` |
| `tree` | Directory structure | `path`, `max_depth`, `show_hidden` |
| `grep` | Search file contents | `pattern`, `directory`, `case_sensitive`, `context_lines` |
| `glob` | Find files by pattern | `pattern`, `directory` |

- PDF reading supported via PDFBox

## Device Control

### toggle_setting
Toggle device settings.
- **Parameters:** `setting` (required), `enable` (boolean)
- **Settings:** wifi, bluetooth, dnd, flashlight, airplane_mode

### brightness_control
Control screen brightness.
- **Actions:** set, get, auto_on, auto_off, increase, decrease, max, min
- **Parameters:** `action` (required), `level` (0-100 for set)

### media_control
Control media playback and volume.
- **Actions:** play, pause, play_pause, next, previous, stop, volume_up, volume_down, mute, unmute, set_volume, get_volume
- **Streams:** media, ring, notification, alarm, call

### device_info
Query device information.
- **Parameters:** `query` (battery/storage/memory/network/device/screen/all)

### take_screenshot
Capture screen with optional AI analysis.
- **Parameters:** `delay_ms`, `analyze` (boolean)
- Requires Android 11+ for capture
- Vision analysis sends screenshot to LLM as base64 image

## Memory & Notes

### memory
Persistent typed key-value store across conversations.

| Action | Description |
|--------|-------------|
| `save` | Store a memory with key, value, type, category |
| `recall` | Look up by key or search by query |
| `list` | List all memories, optionally filtered by category |
| `by_type` | List memories filtered by type |
| `delete` | Remove a memory by key |
| `search` | Full-text search across keys and values |
| `clear_category` | Delete all memories in a category |

**Memory Types:**
- `user_profile` — user info (name, role, location)
- `preference` — how the user likes things done
- `fact` — general knowledge (default)
- `instruction` — standing orders to always follow
- `reference` — external links, resources, contacts

Limit: 100 entries. Memories are auto-injected into the system prompt grouped by type.

### notes
Local note-taking with tags.
- **Actions:** create, read, update, delete, list, search
- **Parameters:** `title`, `content`, `tags`, `id`, `query`

## Productivity

### create_calendar_event
Create calendar events.
- **Parameters:** `title` (required), `date`, `time`, `duration_minutes`, `description`, `location`

### set_reminder
Set time-triggered notification reminders.
- **Parameters:** `message` (required), `time`, `date`

### set_alarm
Manage alarms and timers.
- **Actions:** set, timer, dismiss, snooze, show
- **Parameters:** `hour`, `minute`, `label`, `days` (for recurring)

### clipboard
Clipboard operations.
- **Actions:** copy, paste, read, clear
- **Parameters:** `text` (for copy)

### schedule
Schedule AI tasks to run in the background.

| Action | Description |
|--------|-------------|
| `create` | Create a one-shot or recurring schedule |
| `list` | List all scheduled tasks with status |
| `delete` | Cancel and remove a schedule |
| `pause` | Pause without deleting |
| `resume` | Re-schedule a paused task |
| `info` | Get schedule details |
| `run_now` | Execute immediately |

- **One-shot:** `type: "once"`, `delay_minutes` — runs once after delay
- **Recurring:** `type: "recurring"`, `interval_minutes` (minimum 15) — repeats
- Scheduled tasks run with full tool access and deliver results as notifications

## Location & Social

### get_location
Get GPS coordinates and address.
- **Parameters:** `accuracy` (high/low), `include_address` (boolean)
- Supports reverse geocoding

### auto_scroll_feed
Auto-scroll through video feeds.
- **Actions:** scroll, stop, next, previous, like
- **Apps:** instagram_reels, youtube_shorts, tiktok, snapchat, facebook, reddit, twitter
- **Parameters:** `app`, `count`, `interval_seconds`, `direction`

## Media & Sharing

### text_to_speech
Speak text aloud.
- **Actions:** speak, stop, status
- **Parameters:** `text`, `speed` (0.5-2.0), `pitch` (0.5-2.0), `language`

### share_content
Share text or URLs.
- **Parameters:** `text` (required), `app` (optional target app)

### notifications
Read and manage notifications.
- **Actions:** show, clear, settings, read
- `read` shows actual notification titles/messages with timestamps
- **Parameters:** `app` (filter), `limit`

## Analytics

### screen_time
App usage statistics.
- **Actions:** today, yesterday, week, app, summary
- **Parameters:** `app_name` (for app action)
- Requires usage access permission

## Integrations

### github
GitHub REST API: PRs (incl. opening new ones with auto-detected base branch), issues, CI runs, repos (personal **and** organization), organizations (members, teams, cross-repo issue triage), notifications, search, repo creation, branch creation, and direct file editing committed straight to a branch via the Contents API. Supports the full fix-an-issue → branch → edit → PR flow end-to-end.
- **Actions:** list_prs, view_pr, pr_checks, create_pr, create_pr_comment, merge_pr, list_issues, view_issue, create_issue, comment_issue, close_issue, list_runs, view_run, rerun, list_repos, list_notifications, search_repos, search_issues, get_user, list_orgs, view_org, list_org_members, list_org_teams, list_org_issues, create_repo, read_file, write_file, delete_file, list_dir, create_branch, api
- **Parameters:** `repo` (owner/repo — owner can be user or org), `org`, `number`, `state`, `limit`, `title`, `body`, `head`, `base`, `from_branch`, `draft`, `merge_method`, `run_id`, `failed_only`, `user`, `username`, `query`, `path`, `content`, `branch`, `sha`, `message`, `method`, `name`, `description`, `private`, `auto_init`
- Requires a GitHub Personal Access Token (Settings → GitHub). Stored in `EncryptedSharedPreferences`. Org actions need `read:org`; creating repos in an org needs `admin:org`.
- Full reference: [`github-tool.md`](github-tool.md)

## Custom Commands

### skills
Manage custom slash commands.
- **Actions:** create, list, run, delete, edit, info, export, import, by_category
- **Parameters:** `name`, `trigger`, `prompt`, `description`, `category`, `json` (for import)
- **Categories:** routine, productivity, utility, social, general
- Slash commands triggered by typing `/<trigger>` in chat
- Import/export as JSON for sharing
