# Services

AndroClaw uses several Android services for background functionality and device integration.

## Accessibility Service

**`AndroClawAccessibilityService`**

Provides UI automation capabilities for controlling other apps.

### Capabilities
| Method | Description |
|--------|-------------|
| `tapByText(text)` | Find and tap UI elements by visible text |
| `tapById(viewId)` | Tap by resource ID |
| `tapAtCoordinates(x, y)` | Direct coordinate tap via gesture |
| `typeText(text)` | Type into focused input or auto-find EditText |
| `scroll(direction)` | Scroll UI element or swipe gesture (up/down) |
| `performSwipeGesture(direction, duration)` | Full-screen swipe for feed apps |
| `captureScreenshot()` | Capture screen bitmap (Android 11+) |
| `pressBack()` | Press back button |
| `pressHome()` | Press home button |
| `startAutoScroll()` | Begin auto-scrolling at intervals |
| `stopAutoScroll()` | Stop auto-scrolling |

### Setup
Must be enabled manually: Settings > Accessibility > AndroClaw. Required for:
- `control_app_ui` tool
- `take_screenshot` tool
- `auto_scroll_feed` tool

## Floating Button Service

**`FloatingButtonService`**

Always-on overlay button accessible from any app.

### Features
- **Tap:** Toggle overlay chat panel
- **Long-press:** Open full AndroClaw app
- **Drag:** Move around screen with momentum
- **Persistent position:** Saved in SharedPreferences
- **Pulsing animation:** Visual indicator when idle
- **Foreground service:** Non-dismissible notification

### Requirements
- `SYSTEM_ALERT_WINDOW` permission (overlay)
- Enabled in Settings > Floating Assistant

## Notification Reader Service

**`NotificationReaderService`**

Reads notification content from other apps.

### Capabilities
- `getActiveNotificationsSummary(maxCount)` — list active notifications with titles, text, app names, timestamps
- Required for `notifications` tool with `read` action

### Requirements
- `NotificationListenerService` permission (granted in Android Settings > Notification Access)

## Schedule Worker

**`ScheduleWorker`**

WorkManager-based background task executor for scheduled AI automation.

### How It Works
1. Receives schedule prompt via WorkManager input data
2. Runs a mini agentic loop (max 5 tool iterations)
3. Uses the same provider/model/tools as the main chat
4. Delivers results as Android notifications
5. Updates schedule database with run timestamps

### Configuration
- Hilt-injected via `@HiltWorker` + `HiltWorkerFactory`
- Default WorkManager initializer disabled in AndroidManifest
- Custom `Configuration.Provider` in `AndroClawApplication`

## Boot Receiver

**`BootReceiver`**

Listens for `BOOT_COMPLETED` to auto-start services.

### Behavior
- If floating button is enabled in preferences AND overlay permission is granted:
  - Starts `FloatingButtonService` automatically after device boot
