# Future Development

## Critical: Content Provider Authentication

The Content Provider is currently `exported=true` with no access control. Any app on the device can call MCP tools. This is fine for the demo but **must be locked down before Taichi ships** — paper trading balances, API keys, and analysis data should not be accessible to arbitrary apps.

### Options (pick one or layer them)

**Signature-level permission (recommended)**
Declare a custom permission with `protectionLevel="signature"`. Only apps signed with the same key can access the provider. Problem: Termux is signed by its own developer, not us. This only works if the bridge is bundled into the Taichi APK itself.

**Token-based auth**
Bridge passes a shared secret via the Bundle extras. Taichi checks it before processing. The token lives in a file in Termux's private storage, written once during setup. Simple, works cross-signature.

**Android App Links / package verification**
Check `Binder.getCallingUid()` in the Content Provider and verify it belongs to an allowlisted package (e.g., `com.termux`). Lightweight, no tokens, but spoofable on rooted devices.

**Hybrid approach**
Package check (is the caller Termux?) + token (does Termux have the right secret?). Two layers, covers rooted devices.

### When to implement
Before any real API keys, portfolio data, or trading logic goes through the Content Provider. Lock it down before Taichi goes live.

---

## Completed in v2

- ~~Service discovery between apps~~ — `AppDiscoveryManager` + `PackageChangeReceiver`
- ~~Notification tools~~ — send, cancel, list channels
- ~~File sharing~~ — `android.share_file` (path) + `android.share_content` (base64) via FileProvider
- ~~Device control tools~~ — torch, vibrate, toast, ringer, brightness, media
- ~~Hub meta-tools~~ — status, health, refresh
- ~~Thread safety~~ — ConcurrentHashMap, background refresh, Android 14+ fix
- ~~Unit tests~~ — ToolRegistry, McpDispatcher, JsonSchemaBuilder

## Completed in v3

- ~~Async job system~~ — `JobManager` in mcp-core, `mcp_async`/`mcp_poll`/`mcp_cancel`/`mcp_jobs` methods on Content Provider. Bridge `--async` mode with transparent polling.
- ~~Reverse channel / Inbox~~ — `InboxManager` stores messages, `hub.inbox` + `hub.inbox_clear` tools for Claude to read.
- ~~Share-to-Claude~~ — `ShareReceiveActivity` registered as Android share target. Text, images, documents all handled. Shows "Send to Claude" in share sheet.
- ~~Intent inbox~~ — `InboxReceiver` accepts `com.androidmcp.SEND_MESSAGE` broadcasts from automation apps (Tasker, MacroDroid, etc.)
- ~~Job management tools~~ — `hub.jobs` lists active/recent, `hub.cancel_job` cancels running jobs
- ~~JobManager tests~~ — submit, poll, cancel, fail, list, response ID matching

---

## v4+ Roadmap

### On-device LLM integration
When local models (llama.cpp, MLX) can run on Android with tool-use support, the Content Provider path means they get MCP tools for free — same `content://` URI, no HTTP needed.

### Play Store distribution
`exported=true` Content Providers will get flagged in Play Store review. The auth solution above resolves this. Also need ProGuard rules for kotlinx-serialization.

### iOS equivalent
iOS doesn't have Content Providers. The equivalent would be App Groups + shared container, or a local XPC service. Separate project if needed.

### Additional tool categories to consider
- **Contacts / SMS** — Read contacts, send SMS. Needs dangerous permissions (READ_CONTACTS, SEND_SMS). Add when there's a clear use case.
- **Location** — Get GPS coordinates. Needs ACCESS_FINE_LOCATION. Useful for context-aware actions.
- **Camera capture** — Take a photo and return the path. Needs CAMERA permission + foreground activity. Complex to implement from a Content Provider.
- **Sensor data** — Accelerometer, gyroscope, proximity. Interesting for hardware projects but niche.
- **Accessibility Service** — Read screen content, perform UI actions. Extremely powerful but requires user to manually enable in settings. Could enable "what's on my screen?" queries.

### Bridge improvements to consider
- **Bidirectional channel** — Currently the bridge only handles client→server. For true push notifications (inbox alerts, price alerts from Taichi), the bridge would need to poll and emit MCP notifications on stdout. This is a protocol extension beyond standard MCP.
- **WebSocket transport** — Alternative to stdio that supports bidirectional communication natively. Would need a small HTTP server in Termux or the Hub.
