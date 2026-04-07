# Bootstrap Conversation

AndroClaw runs a one-time "getting to know you" conversation when first launched after onboarding. Inspired by OpenClaw's BOOTSTRAP.md initialization ritual.

## When It Runs

The bootstrap triggers when all of these are true:
1. Onboarding is complete (`PREF_ONBOARDING_DONE = true`)
2. An API key is configured
3. Bootstrap hasn't run before (`PREF_BOOTSTRAP_DONE = false`)

It fires automatically when the first conversation is created.

## What It Does

### Auto-Detected Context
The bootstrap prompt includes device information gathered automatically:
- Device manufacturer and model
- Android version and API level
- Language and region
- Number of installed apps
- Timezone

### Conversation Flow
The AI is instructed to:

1. **Introduce itself** as AndroClaw — the user's personal AI phone assistant
2. **Ask a few questions** conversationally (not like a form):
   - What should I call you?
   - What kind of things would you most like help with?
   - Any preferences for how I should communicate?
3. **Save answers to memory** using the memory tool:
   - `user_name` (type: user_profile)
   - `user_preferences` (type: preference)
   - `communication_style` (type: preference)
4. **Confirm and suggest** — confirm what it learned and suggest 2-3 things to try

## After Bootstrap

- The `PREF_BOOTSTRAP_DONE` flag is set immediately to prevent re-triggering
- Saved memories are automatically injected into all future conversations
- The user's profile info personalizes every interaction going forward

## Tone

The bootstrap is designed to feel like "meeting a helpful friend, not filling out a survey." It's warm, brief, and conversational.
