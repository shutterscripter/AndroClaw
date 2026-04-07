# Memory System

AndroClaw has a persistent, typed memory system that stores information across conversations. Inspired by OpenClaw's typed memory architecture (user, feedback, project, reference).

## Memory Types

| Type | Purpose | Prompt Section |
|------|---------|---------------|
| `user_profile` | User info — name, role, location | "About the user" (injected first) |
| `preference` | How the user likes things — communication style, favorites | "User preferences" |
| `fact` | General knowledge (default type) | "Known facts" |
| `instruction` | Standing orders to always follow | "Standing instructions" |
| `reference` | External links, resources, contacts | "Reference info" |

## How Memory Works

### Storage
- Memories are key-value pairs stored in Room (SQLite)
- Each memory has: `key`, `value`, `category`, `type`, `createdAt`, `updatedAt`
- Unique constraint on `key` — saving to an existing key updates it
- Maximum 100 entries to prevent database bloat

### Prompt Injection
Before every LLM request, all memories are loaded and grouped by type into the system prompt:

```
About the user (from memory):
- user_name: Alex
- user_role: UX Designer

User preferences:
- communication_style: Brief and casual

Standing instructions (always follow):
- calendar_check: Always check calendar before suggesting meetings

Reference info:
- work_slack: #design-team channel for project updates

Known facts:
- partner_name: Sam
- home_address: 123 Main St, NYC
```

The ordering is intentional — user profile and preferences come first since they're most important for personalization.

## Tool Actions

| Action | Description | Parameters |
|--------|-------------|------------|
| `save` | Store a memory | `key`, `value`, `type`, `category` |
| `recall` | Look up by key or search | `key` or `query` |
| `list` | List all, optionally by category | `category` |
| `by_type` | List filtered by type | `type` |
| `search` | Full-text search | `query` |
| `delete` | Remove by key | `key` |
| `clear_category` | Delete all in a category | `category` |

## Examples

**User says:** "Remember that my name is Alex and I prefer brief responses"

**AI saves:**
- `key: "user_name"`, `value: "Alex"`, `type: "user_profile"`
- `key: "communication_style"`, `value: "Brief responses preferred"`, `type: "preference"`

**User says:** "Always check the weather before my morning briefing"

**AI saves:**
- `key: "morning_weather"`, `value: "Check weather before morning briefing"`, `type: "instruction"`

## Bootstrap Integration

The first-run bootstrap conversation automatically populates memory with:
- `user_name` (type: user_profile)
- `user_preferences` (type: preference)
- `communication_style` (type: preference)
