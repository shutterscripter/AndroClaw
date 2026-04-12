# GitHub Tool

The `github` tool gives AndroClaw direct access to the GitHub REST API from your phone — pull requests, issues, CI workflow runs, repo browsing, file edits committed straight to a branch, search, and a raw REST escape hatch.

It is implemented entirely inside the app (no `gh` CLI, no backend) in `app/src/main/java/com/androclaw/tools/GitHubToolHandler.kt`. All HTTP work runs on `Dispatchers.IO` so the main thread is never blocked.

## Setup

1. Create a [GitHub Personal Access Token](https://github.com/settings/tokens) with the scopes you need:
   - **`repo`** — required for private repos and for `write_file` / `delete_file`.
   - **`workflow`** — required for `rerun`.
   - **`notifications`** — required for `list_notifications`.
   - **`read:org`** — required for `list_orgs`, `list_org_members`, `list_org_teams` (and to see private org membership).
   - **`admin:org`** — required to `create_repo` inside an organization.
   - For public-only, read-only browsing, a fine-grained token with `contents:read` is enough.
2. Open AndroClaw → **Settings → GitHub**, paste the token, and tap **Save**. The indicator should read `Saved (40 chars)` for a classic PAT.
3. The token is stored in `EncryptedSharedPreferences` (AES-256-GCM) under the key `github_token`. It never leaves the device except in the `Authorization: Bearer …` header sent directly to `api.github.com`.

If the token is missing, every action except `get_user` returns a clear error pointing back at the Settings screen.

## Calling the tool

The tool name is `github`. Every call requires an `action` parameter. Repository arguments are always passed as the string `owner/repo` (e.g. `"openclaw/openclaw"`).

In normal use, you don't call this tool by hand — you ask AndroClaw in chat:

> "show me my open PRs in androclaw/AndroClaw"
> "merge PR #42 in pranavpatil/portfolio with squash"
> "create an issue titled 'auto-scroll breaks on Reels' in pranavpatil/AndroClaw"
> "read the README from openclaw/openclaw and summarize it"

The model selects the action and parameters automatically.

## Action reference

### Pull requests

| Action               | Required params                                  | Optional params                       | Notes                                                                                  |
| -------------------- | ------------------------------------------------ | ------------------------------------- | -------------------------------------------------------------------------------------- |
| `list_prs`           | `repo`                                           | `state` (open/closed/all), `limit`    | Lists PRs with number, title, author, state.                                           |
| `view_pr`            | `repo`, `number`                                 | —                                     | Title, author, state, branches, additions/deletions, file count, URL, body.            |
| `pr_checks`          | `repo`, `number`                                 | —                                     | Resolves the PR's head SHA and lists check-run statuses/conclusions for that commit.   |
| `create_pr`          | `repo`, `title`, `head`                          | `base`, `body`, `draft` (bool)        | Opens a PR. `base` defaults to the repo's default branch (auto-detected). Use `head="user:branch"` for cross-fork PRs. |
| `create_pr_comment`  | `repo`, `number`, `body`                         | —                                     | Posts a comment on the PR's conversation.                                              |
| `merge_pr`           | `repo`, `number`                                 | `merge_method` (merge/squash/rebase)  | Defaults to `squash`.                                                                  |

### Issues

| Action          | Required params                | Optional params                    | Notes                                                                |
| --------------- | ------------------------------ | ---------------------------------- | -------------------------------------------------------------------- |
| `list_issues`   | `repo`                         | `state` (open/closed/all), `limit` | Filters out PRs (the GitHub `/issues` endpoint includes them).       |
| `view_issue`    | `repo`, `number`               | —                                  | Title, author, state, URL, truncated body.                           |
| `create_issue`  | `repo`, `title`                | `body`                             | Returns the new issue number and URL.                                |
| `comment_issue` | `repo`, `number`, `body`       | —                                  | Posts a comment on the issue.                                        |
| `close_issue`   | `repo`, `number`               | —                                  | PATCHes `state=closed`.                                              |

### GitHub Actions / CI

| Action      | Required params         | Optional params                    | Notes                                                                              |
| ----------- | ----------------------- | ---------------------------------- | ---------------------------------------------------------------------------------- |
| `list_runs` | `repo`                  | `limit`                            | Most-recent workflow runs with id, name, status/conclusion, branch.                |
| `view_run`  | `repo`, `run_id`        | —                                  | Single run details.                                                                |
| `rerun`     | `repo`, `run_id`        | `failed_only` (boolean)            | When `failed_only=true`, hits `/rerun-failed-jobs`.                                |

### Repos / user / search

| Action               | Required params | Optional params              | Notes                                                                                          |
| -------------------- | --------------- | ---------------------------- | ---------------------------------------------------------------------------------------------- |
| `list_repos`         | —               | `org`, `user`, `limit`       | `org` → `/orgs/{org}/repos`. `user` → `/users/{user}/repos`. Both omitted → your own repos.    |
| `list_notifications` | —               | —                            | Unread inbox: reason, repo, subject title, subject type. Up to 20.                             |
| `search_repos`       | `query`         | `limit`                      | Uses GitHub search syntax: `org:foo language:kotlin stars:>100`.                               |
| `search_issues`      | `query`         | `limit`                      | Same syntax; result rows tag each as `[Issue]` or `[PR]`.                                      |
| `get_user`           | —               | `username`                   | Authenticated user when `username` omitted. The only action that works without auth.           |

### Organizations

All standard `owner/repo` actions (PRs, issues, CI, file edits) already work on organization repos — just pass `repo: "myorg/myrepo"`. The actions below are organization-scoped and don't fit the `owner/repo` shape. Org-introspection actions (`list_orgs`, `list_org_members`, `list_org_teams`) need the `read:org` PAT scope; `create_repo` inside an org needs `admin:org` (or a fine-grained token with the matching org permissions).

| Action             | Required params | Optional params                                       | Notes                                                                                                                  |
| ------------------ | --------------- | ----------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `list_orgs`        | —               | `username`                                            | Authenticated user's orgs by default; pass `username` for someone else's public orgs.                                  |
| `view_org`         | `org`           | —                                                     | Org profile: name, description, location, blog, public repo and follower counts, URL.                                  |
| `list_org_members` | `org`           | `limit`                                               | Lists visible members. Token needs `read:org` to see private members.                                                  |
| `list_org_teams`   | `org`           | `limit`                                               | Lists teams (name, slug, description). Needs `read:org`.                                                               |
| `list_org_issues`  | `org`           | `state` (open/closed/all), `limit`                    | Cross-repo issue feed for the org. Filters out PRs (the `/issues` endpoint includes them).                             |
| `create_repo`      | `name`          | `org`, `description`, `private` (bool), `auto_init`   | Creates a repo. With `org` → `POST /orgs/{org}/repos`. Without → `POST /user/repos`. `auto_init` defaults to true.     |

### File contents and branches

The Contents API commits straight to a branch — there's no PR step from the file action itself. If `branch` is omitted, the repo's default branch is used. For `write_file` and `delete_file`, `sha` is auto-fetched if not provided, so you can just pass `repo`, `path`, and `content`. Pair these with `create_branch` + `create_pr` for the full fix-an-issue flow described below.

| Action          | Required params                       | Optional params                     | Notes                                                                                                      |
| --------------- | ------------------------------------- | ----------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `read_file`     | `repo`, `path`                        | `branch`                            | Returns base64-decoded contents (truncated at ~6 KB) plus file SHA + size.                                 |
| `write_file`    | `repo`, `path`, `content`             | `branch`, `message`, `sha`          | Creates or updates a file. Auto-probes for the existing SHA when not supplied.                             |
| `delete_file`   | `repo`, `path`                        | `branch`, `message`, `sha`          | Auto-fetches SHA when not supplied.                                                                        |
| `list_dir`      | `repo`                                | `path`, `branch`                    | Lists directory entries with type and size. If `path` resolves to a file, says so.                         |
| `create_branch` | `repo`, `branch`                      | `from_branch`                       | Creates a new branch. `branch` is the new name; `from_branch` defaults to the repo's default branch.       |

### Raw API escape hatch

| Action | Required params | Optional params         | Notes                                                                                              |
| ------ | --------------- | ----------------------- | -------------------------------------------------------------------------------------------------- |
| `api`  | `path`          | `method`, `body`        | Direct REST call to any GitHub endpoint. `path` starts with `/` (e.g. `/repos/owner/repo/topics`). |

## Recipe: fix an issue and raise a PR

The full fix-an-issue flow is now first-class. You just say something like:

> "fix issue #42 in pranavpatil/AndroClaw and open a PR"

…and the agent will chain these calls (the exact files it edits depend on the issue):

```jsonc
// 1. Read the issue
{ "action": "view_issue", "repo": "pranavpatil/AndroClaw", "number": 42 }

// 2. Gather context — list relevant files, read the ones that look related
{ "action": "list_dir",  "repo": "pranavpatil/AndroClaw", "path": "app/src/main/java/com/androclaw/tools" }
{ "action": "read_file", "repo": "pranavpatil/AndroClaw", "path": "app/src/main/java/com/androclaw/tools/AutoScrollToolHandler.kt" }

// 3. Create a feature branch (defaults to forking from the repo's default branch)
{ "action": "create_branch", "repo": "pranavpatil/AndroClaw", "branch": "fix/issue-42-reels-skip" }

// 4. Commit the edit on the new branch
{
  "action":  "write_file",
  "repo":    "pranavpatil/AndroClaw",
  "path":    "app/src/main/java/com/androclaw/tools/AutoScrollToolHandler.kt",
  "branch":  "fix/issue-42-reels-skip",
  "message": "fix: don't skip first reel on auto-scroll start",
  "content": "/* …updated file contents… */"
}

// 5. Open the PR — base auto-detects the repo's default branch
{
  "action": "create_pr",
  "repo":   "pranavpatil/AndroClaw",
  "title":  "fix: don't skip first reel on auto-scroll start",
  "head":   "fix/issue-42-reels-skip",
  "body":   "Fixes #42.\n\nThe initial swipe was firing before the feed had a focused item, so the first reel was being skipped. Wait until `onWindowStateChanged` reports a non-empty window before kicking off the swipe loop."
}
```

The agent never edits straight on `main` for this flow — every fix goes through a feature branch + PR so you can review the diff before merging. You can also pass `draft: true` to `create_pr` if you want it to land as a draft.

> **Reality check.** This makes the *plumbing* mistake-proof: branch creation, base-branch detection, SHA fetching, and PR opening all happen without the model having to hand-craft REST payloads. It does **not** guarantee the *fix itself* is correct — that part is on the model and the underlying file context. Always review the PR diff before merging.

## Examples

These show the literal tool input the model produces. You normally just talk to the chat — the model fills these in.

**List open PRs in a repo**

```json
{
  "action": "list_prs",
  "repo": "openclaw/openclaw",
  "state": "open",
  "limit": 10
}
```

**View a specific PR with checks**

The model typically chains `view_pr` followed by `pr_checks`:

```json
{ "action": "view_pr",   "repo": "openclaw/openclaw", "number": 1234 }
{ "action": "pr_checks", "repo": "openclaw/openclaw", "number": 1234 }
```

**Create an issue**

```json
{
  "action": "create_issue",
  "repo": "pranavpatil/AndroClaw",
  "title": "Reels auto-scroll skips first item",
  "body": "Reproduction steps:\n1. Open Instagram\n2. ..."
}
```

**Edit a file directly on `main`**

```json
{
  "action": "write_file",
  "repo": "pranavpatil/notes",
  "path": "ideas/2026.md",
  "content": "# Ideas for 2026\n- Ship AndroClaw 1.0\n- ..."
}
```

The handler probes the existing file to fetch its SHA, then PUTs the update with a default commit message (`Update ideas/2026.md via AndroClaw`). Override with `message` and `branch` if needed.

**Re-run only failed jobs of a workflow run**

```json
{
  "action": "rerun",
  "repo": "openclaw/openclaw",
  "run_id": 9876543210,
  "failed_only": true
}
```

**List repos in an organization**

```json
{
  "action": "list_repos",
  "org": "openclaw",
  "limit": 20
}
```

**View an organization's profile**

```json
{ "action": "view_org", "org": "openclaw" }
```

**Cross-repo issue feed for an org** (great for triage)

```json
{ "action": "list_org_issues", "org": "openclaw", "state": "open", "limit": 30 }
```

**Create a private repo inside an organization**

```json
{
  "action": "create_repo",
  "org": "openclaw",
  "name": "experiments-2026",
  "description": "Throwaway prototypes for the 2026 roadmap",
  "private": true
}
```

**Create a personal repo** (no `org` → goes under your account)

```json
{ "action": "create_repo", "name": "androclaw-notes", "private": false }
```

**Edit a file in an org repo** — there's nothing org-specific here. Org owner just goes in the `repo` parameter:

```json
{
  "action": "write_file",
  "repo": "openclaw/openclaw",
  "path": "docs/note.md",
  "content": "# Note\nAdded from AndroClaw."
}
```

**Raw API call** — list a repo's topics:

```json
{
  "action": "api",
  "method": "GET",
  "path": "/repos/openclaw/openclaw/topics"
}
```

## Error handling

- All HTTP work runs in `withContext(Dispatchers.IO)`. Calling the tool on the main thread is safe.
- Non-2xx responses are surfaced as `GitHub API error <code>: <message from response body>`.
- Network exceptions are caught and returned as `GitHub error: <exception message>` rather than crashing the agent loop.
- File read responses are truncated at ~6,000 characters with a `...[truncated N chars]` marker so a single call can't blow the context window.
- The `github` action enum is locked down by `ToolDefinitions.kt` — the model can only choose one of the supported actions.

## Security notes

- The PAT is stored in `EncryptedSharedPreferences` (key `github_token` in `androclaw_secure_prefs`). It is read on every call rather than cached in memory long-term.
- The token is sent only to `https://api.github.com/...`. There is no proxy, no analytics, no remote logging.
- Treat the token like any other credential: scope it tightly, prefer fine-grained tokens, and rotate it if your phone is lost. Revocation happens at <https://github.com/settings/tokens>.
- File writes commit straight to the target branch — there is no review step. Use `branch` to commit to a feature branch when you don't want changes landing on `main`.

## See also

- [`tools.md`](tools.md) — full tool reference
- [`security.md`](security.md) — encrypted prefs, permission model
- [`tool-interceptor.md`](tool-interceptor.md) — rate limiting and audit log that wraps every tool call
