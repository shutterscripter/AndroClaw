# Skills System

Skills are custom slash commands that chain multiple tools into reusable workflows. Type `/<trigger>` in chat to run a skill.

## Built-in Skills

AndroClaw ships with 10 built-in skills that are seeded on first run:

| Trigger | Name | Category | What It Does |
|---------|------|----------|-------------|
| `/morning` | Morning Briefing | routine | Weather, notifications, calendar, messages |
| `/summary` | Daily Summary | routine | Screen time, missed calls, messages, calendar |
| `/news` | News Briefing | routine | Top 5 news headlines via web search |
| `/note` | Quick Note | productivity | Capture a thought with auto-generated title |
| `/see` | Screenshot & Describe | utility | Take screenshot + AI vision analysis |
| `/findme` | Find My Phone | utility | Max volume + alarm + flashlight |
| `/dnd` | Do Not Disturb | utility | Enable DND + dim screen |
| `/health` | Device Health | utility | Battery, storage, RAM, usage report |
| `/shareloc` | Share Location | social | Share Google Maps link of current location |
| `/reels` | Scroll Reels | social | Auto-scroll 10 Instagram Reels |

## Creating Skills

Skills can be created by the AI or directly via the tool:

**Via chat:** "Create a skill called commute that checks traffic to my office"

**Via tool:**
```json
{
  "action": "create",
  "name": "Commute Check",
  "trigger": "commute",
  "prompt": "Get my location, then search for traffic conditions to 123 Main St.",
  "description": "Check traffic to the office",
  "category": "routine"
}
```

## Categories

| Category | Purpose |
|----------|---------|
| `routine` | Daily/recurring tasks (morning, evening, news) |
| `productivity` | Work and task management |
| `utility` | Device control and system tasks |
| `social` | Social media and sharing |
| `general` | Everything else (default) |

## Skill Actions

| Action | Description |
|--------|-------------|
| `create` | Create a new skill with name, trigger, prompt, description, category |
| `list` | List all skills grouped by category |
| `run` | Execute a skill by trigger |
| `delete` | Remove a skill by ID or trigger |
| `edit` | Update an existing skill's fields |
| `info` | Get skill details |
| `by_category` | List skills in a specific category |
| `export` | Export skills as JSON (all, by trigger, or by category) |
| `import` | Import skills from a JSON array |

## Sharing Skills

### Export
```json
{
  "action": "export",
  "category": "routine"
}
```
Returns a JSON array that can be shared with other users.

### Import
```json
{
  "action": "import",
  "json": "[{\"name\":\"Commute\",\"trigger\":\"commute\",\"prompt\":\"...\",\"category\":\"routine\"}]"
}
```
Skips skills with duplicate triggers.

## Slash Command Resolution

When the user types `/morning good morning!`:
1. Extract trigger: `morning`
2. Look up skill in database
3. If found, use the skill's prompt
4. Append extra text as "Additional context: good morning!"
5. Send to the AI for execution
