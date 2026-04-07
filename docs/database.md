# Database

AndroClaw uses Room (SQLite) with versioned migrations. Current version: **8**.

## Entities

### ConversationEntity
Represents a chat conversation.

| Column | Type | Description |
|--------|------|-------------|
| `id` | Long (PK, auto-increment) | Unique ID |
| `title` | String | Display title (default: "New Chat") |
| `createdAt` | Long | Creation timestamp |
| `updatedAt` | Long | Last activity timestamp |
| `messageCount` | Int | Number of messages |

### MessageEntity
Represents a single message within a conversation.

| Column | Type | Description |
|--------|------|-------------|
| `id` | Long (PK, auto-increment) | Unique ID |
| `conversationId` | Long (FK) | Parent conversation (CASCADE delete) |
| `role` | String | "user", "assistant", or "tool_status" |
| `content` | String | Message text or JSON-serialized content |
| `toolName` | String? | Tool name (for tool_status messages) |
| `toolStatus` | String? | Execution status |
| `timestamp` | Long | Message timestamp |

### MemoryEntity
Persistent typed key-value store.

| Column | Type | Description |
|--------|------|-------------|
| `id` | Long (PK, auto-increment) | Unique ID |
| `key` | String (unique index) | Memory label |
| `value` | String | Memory content |
| `category` | String | Organization category (default: "general") |
| `type` | String | Memory type: user_profile, preference, fact, instruction, reference |
| `createdAt` | Long | Creation timestamp |
| `updatedAt` | Long | Last update timestamp |

### NoteEntity
Local notes with tags.

| Column | Type | Description |
|--------|------|-------------|
| `id` | Long (PK, auto-increment) | Unique ID |
| `title` | String | Note title |
| `content` | String | Note body |
| `tags` | String | Comma-separated tags |
| `createdAt` | Long | Creation timestamp |
| `updatedAt` | Long | Last update timestamp |

### SkillEntity
Custom slash commands.

| Column | Type | Description |
|--------|------|-------------|
| `id` | Long (PK, auto-increment) | Unique ID |
| `name` | String | Display name |
| `trigger` | String (unique index) | Slash command trigger (e.g., "morning") |
| `prompt` | String | AI instruction to execute |
| `description` | String | Short description |
| `category` | String | routine, productivity, utility, social, general |
| `isBuiltIn` | Boolean | True for bundled skills |
| `createdAt` | Long | Creation timestamp |

### ScheduleEntity
Scheduled AI tasks.

| Column | Type | Description |
|--------|------|-------------|
| `id` | Long (PK, auto-increment) | Unique ID |
| `name` | String | Schedule name |
| `prompt` | String | AI instruction to execute |
| `type` | String | "once" or "recurring" |
| `intervalMinutes` | Int | Repeat interval (recurring only) |
| `scheduledAt` | Long | Target run time (one-shot only) |
| `lastRunAt` | Long | Last execution time |
| `nextRunAt` | Long | Next scheduled execution |
| `isActive` | Boolean | Active or paused |
| `workManagerId` | String | WorkManager unique work name |
| `createdAt` | Long | Creation timestamp |

## Migrations

| Migration | Changes |
|-----------|---------|
| v2 → v3 | Add `memories` table |
| v3 → v4 | Add `notes` table |
| v4 → v5 | Add `skills` table |
| v5 → v6 | Add `category` and `isBuiltIn` columns to skills |
| v6 → v7 | Add `schedules` table |
| v7 → v8 | Add `type` column to memories |

A `fallbackToDestructiveMigration()` is kept as a last resort for unhandled version jumps.
