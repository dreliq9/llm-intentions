# AGENTS.md — LLM Intentions Android Hub

You are an LLM with access to the LLM Intentions Hub running on an Android device. This guide tells you how to use it well. Read this once before your first tool call.

## Connection

The Hub speaks MCP over streamable-http on `http://127.0.0.1:8379/mcp` (proxied to your machine via Tailscale or USB). Tools are namespaced by CapApp:

| Namespace | CapApp | Capability |
|---|---|---|
| `hub.*` | Hub built-in | Discovery, status, `hub.claude` relay to Claude Code in Termux |
| `taichi.*` | Taichi | Paper crypto trading, on-chain data, portfolio |
| `device.*` | tool-device | Sensors, TTS, vibration, clipboard on Android |
| `notify.*` | tool-notify | Notification read / post / reply, listener events |
| `people.*` | tool-people | Contacts, calendar, SMS, call log |
| `files.*` | tool-files-dev | Filesystem read/write on scoped storage |
| `termux.*` | termux-mcp | Termux APIs: battery, camera, location, etc. |

## Return format

Every tool call returns a canonical envelope rendered as text:

```
OK: <one-line summary>

<data JSON>
```

or

```
FAIL: <one-line summary>
Hint: <what to do next>

Raw:
<exception / stderr JSON>
```

**When you see `FAIL:`, read the `Hint:` line before retrying.** Hints map known failure patterns to concrete actions (grant permission, call a prerequisite tool first, wait and retry on rate limit, etc.). If a hint is absent, the `Raw:` block is your next best clue.

## Permissions you cannot grant yourself

Android permissions for each CapApp are requested in-app by the user. If a tool returns FAIL with a hint about permissions, tell the user which setting to toggle — do not silently retry.

| Permission | CapApp / Tools |
|---|---|
| `POST_NOTIFICATIONS` | `notify.*` post |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | `notify.*` read |
| `READ_CONTACTS`, `WRITE_CONTACTS` | `people.contacts_*` |
| `READ_CALENDAR`, `WRITE_CALENDAR` | `people.calendar_*` |
| `READ_SMS`, `SEND_SMS` | `people.sms_*` (requires default-SMS-app role on modern Android) |
| `MANAGE_EXTERNAL_STORAGE` | `files.file_*` |
| `ACCESS_FINE_LOCATION` | `device.location`, `termux.location` |
| Camera / Microphone | `termux.camera_*`, `termux.microphone_*` |

## Destructive tools — confirm before calling

These modify device state, send messages, or write to user-visible databases. Ask the user first unless intent is unambiguous.

- `taichi.paper_trade` (action=buy/sell/short/cover) — mutates the paper portfolio
- `taichi.paper_reset` — wipes the paper portfolio
- `notify.post`, `notify.reply` — visible to the user
- `people.sms_send` — actually sends an SMS
- `people.calendar_create_event`, `people.contacts_write` — writes to the user's data
- `files.file_write`, `files.file_delete` — writes/deletes on disk
- `device.tts_speak` — audible
- `device.vibrate` — physical feedback

## Latency classes

- `FAST` — <1s: clipboard ops, sensor reads, enum queries, status reads.
- `SLOW` — 1-10s: network calls, on-chain lookups, OCR, shell invocations.
- `VERY_SLOW` — may exceed 30s: `hub.claude` (LLM call), multi-page contact scans.

## Common sequences

### "Buy some ETH with $100"
1. `taichi.search_token` with `query="ETH"` to confirm the token exists.
2. (Confirm the trade with the user.)
3. `taichi.paper_trade` with `symbol=ETH action=buy amount_usd=100`.
4. `taichi.paper_portfolio` to verify the entry.

### "What's on my calendar?"
1. `people.calendar_read` with `days_ahead=7`.
2. If FAIL with permission hint → ask user to grant Calendar access.

### "Send a reminder"
1. `notify.post` with a title + body.
2. Don't retry if FAIL on permission — tell the user.

### "Relay a question to Claude Code"
1. `hub.claude` with `message="<question>"`.
2. Expected latency VERY_SLOW.

### "Read on-device text from the foreground app"
1. Check if an OCR tool is available in the current build.
2. Otherwise, use `termux.*` shell-based extraction or prompt the user to describe what they're looking at.

## Parameter conventions

- String parameters with an `enum` in the schema must be one of those values — anything else will FAIL with a hint citing the valid values. Example: `taichi.paper_trade` `action` accepts only `buy | sell | short | cover`.
- Timestamps are Unix epoch seconds unless otherwise documented.
- Absolute file paths always (no `~` expansion, no relative paths).
- Numeric inputs are JSON numbers — don't pass them as strings.

## When a tool isn't enough

The Hub is curated. If you need a raw Android API no CapApp exposes, tell the user — do not try to smuggle arbitrary shell through `termux.*` or similar. A new CapApp is a separate APK; the user can build one from the template at `capapp-template/`.

## Tools you probably shouldn't call unprompted

- `people.sms_*`, `people.contacts_write`, `people.calendar_create_event` — only on explicit user request.
- `hub.claude` — consumes the user's Claude subscription budget; confirm before routing work there.
- Anything marked destructive above.
