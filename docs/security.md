# Security

AndroClaw implements multiple layers of security to protect user data and prevent unauthorized actions.

## API Key Storage

API keys are stored in **EncryptedSharedPreferences** using AES-256-GCM encryption:
- Encryption scheme: `AES256_SIV` for key encryption, `AES256_GCM` for value encryption
- Per-provider key storage (`api_key_claude`, `api_key_openai`, etc.)
- Keys never appear in logs or tool outputs

## Permission Management

### Runtime Permissions
AndroClaw requests permissions at runtime as needed:
- **Core:** SMS, contacts, phone, calendar
- **Storage:** Media access (images, video, audio), external storage
- **Location:** Fine and coarse GPS
- **System:** Overlay (floating button), notifications, alarms
- **Media:** Microphone (voice input), camera

### Special Permissions (require manual grant)
- **Accessibility Service:** Settings > Accessibility > AndroClaw
- **Notification Access:** Settings > Notification Access > AndroClaw
- **Usage Stats:** Settings > Usage Access > AndroClaw
- **Overlay:** Settings > Display Over Other Apps

## Tool Safety

### Rate Limiting
The tool interceptor prevents runaway loops:
- Messaging tools (SMS, WhatsApp, email, phone): max 3 per turn
- All other tools: max 10 per turn
- Counters reset with each user message

### Destructive Tool Classification
Tools that send outbound messages or modify device state are classified as destructive:
- `send_sms`, `send_whatsapp`, `send_email`, `make_phone_call`
- `control_app_ui`, `toggle_setting`

The AI is instructed (via system prompt) to only confirm before destructive actions and to act directly on safe operations.

### Audit Logging
All tool executions are logged with inputs, results, timing, and success/failure status. Last 100 entries kept in memory for debugging.

## Tool Enable/Disable

Users can disable individual tools in Settings > Enabled Tools. Disabled tools are not included in the LLM's tool definitions, preventing the AI from using them.

## Data Storage

| Data | Storage Method |
|------|---------------|
| API keys | EncryptedSharedPreferences (AES-256-GCM) |
| Conversations & messages | Room (SQLite, local only) |
| Memories & notes | Room (SQLite, local only) |
| Skills & schedules | Room (SQLite, local only) |
| User preferences | SharedPreferences (unencrypted, non-sensitive) |
| Prompt customizations | SharedPreferences (unencrypted, non-sensitive) |

All data is stored locally on the device. Nothing is synced to external servers except LLM API calls to the configured provider.

## Network Security

- All API calls use HTTPS
- OkHttp with configurable timeouts (60s connect, 120s read, 60s write)
- No data sent to Anthropic/OpenAI/Google beyond the conversation context needed for the API call
