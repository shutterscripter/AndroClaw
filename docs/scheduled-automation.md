# Scheduled Automation

AndroClaw can schedule AI tasks to run automatically in the background, inspired by OpenClaw's cron system. Scheduled tasks execute with full tool access and deliver results as Android notifications.

## Schedule Types

### One-Shot
Runs once after a specified delay.

**Example:** "Remind me to check my email in 30 minutes"
```json
{
  "action": "create",
  "name": "Check Email",
  "prompt": "Check my recent notifications for any email alerts and summarize them.",
  "type": "once",
  "delay_minutes": 30
}
```

### Recurring
Runs repeatedly at a fixed interval (minimum 15 minutes, WorkManager limitation).

**Example:** "Check the news every 2 hours"
```json
{
  "action": "create",
  "name": "News Check",
  "prompt": "Search for top 3 breaking news headlines and summarize them.",
  "type": "recurring",
  "interval_minutes": 120
}
```

## How It Works

1. **Creation:** The AI or user creates a schedule via the `schedule` tool
2. **WorkManager:** A `ScheduleWorker` job is enqueued with the appropriate delay/interval
3. **Execution:** When the schedule fires, the worker runs a mini agentic loop:
   - Loads system prompt with memory context
   - Sends the scheduled prompt to the LLM
   - Executes up to 5 tool iterations
   - Collects the final response
4. **Delivery:** The result is delivered as an Android notification
5. **Tracking:** The schedule entity is updated with `lastRunAt` and `nextRunAt`

## Schedule Actions

| Action | Description | Required Parameters |
|--------|-------------|-------------------|
| `create` | Create a new schedule | `name`, `prompt`, `type`, `delay_minutes` or `interval_minutes` |
| `list` | List all schedules with status | — |
| `delete` | Cancel and remove | `id` |
| `pause` | Pause without deleting | `id` |
| `resume` | Re-activate a paused schedule | `id` |
| `info` | Get schedule details | `id` |
| `run_now` | Execute immediately | `id` |

## Lifecycle

- **One-shot schedules** become inactive after running
- **Recurring schedules** continue until paused or deleted
- **Paused schedules** cancel their WorkManager job but keep the database record
- **Resumed schedules** re-enqueue the WorkManager job

## Limitations

- Minimum recurring interval is 15 minutes (Android WorkManager constraint)
- Background execution depends on Android battery optimization settings
- The device must have an active API key configured
- Scheduled tasks use the same model/provider as the main chat
- Maximum 5 tool iterations per scheduled run (vs 10 for interactive chat)

## Notifications

Results are delivered via the `androclaw_agent` notification channel with:
- Title: schedule name (or "schedule name (failed)" on error)
- Body: AI response text (truncated to 200 chars in preview, full in expanded view)
- Auto-cancel on tap
