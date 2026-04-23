# Lightpanda CapApp — Design Spec

**Date:** 2026-04-22
**Status:** Draft — awaiting review
**Target:** `capapps/lightpanda/`, applicationId `com.llmintentions.lightpanda`

## 1. Goal

Give LLM Intentions a full agent-browser capability on-device by packaging Lightpanda (the Zig/V8 headless browser) as a CapApp. The CapApp exposes Lightpanda's native MCP tool surface (`goto`, `evaluate`, `screenshot`, `markdown`, `structuredData`, `semantic_tree`, `interactiveElements`, `click`, `fill`, plus anything added upstream) to the Hub, where they appear under the `browser.*` namespace.

v0.1 done definition: **Claude Code running in proot on the phone talks over localhost HTTP MCP to the Hub, Hub routes `browser.*` tool calls to the Lightpanda CapApp, and an agent can complete a multi-step browsing loop (navigate → extract → click → return) on-device, with no Termux shell steps or adb.**

## 2. Architecture

All on-device. No LAN, no Mac.

```
┌──────────────────────────────────────────────────────┐
│ Phone                                                 │
│                                                       │
│  ┌─────────────────────────────┐                      │
│  │ Termux / proot (Claude rootfs) │                      │
│  │  Claude Code (Node.js)      │                      │
│  └──────────────┬──────────────┘                      │
│                 │ HTTP MCP (localhost:8381→8379)      │
│                 ▼                                     │
│  ┌─────────────────────────────┐                      │
│  │ Hub APK (com.llmintentions.hub) │                      │
│  │  + namespace router         │                      │
│  │  + bindService ─────────────┼─────────┐            │
│  └──────────────┬──────────────┘         │            │
│        EXECUTE Intent│                    │ bound     │
│     broadcast reply ▼                    ▼            │
│  ┌──────────────────────────────────────────────┐     │
│  │ Lightpanda CapApp APK (com.llmintentions.lightpanda) │
│  │  ┌──────────────────────┐  ┌────────────────┐│     │
│  │  │ CommandGatewayService│  │ LightpandaBridge│ │     │
│  │  │  (protocol: Intent)  │  │   (bound svc,   │ │     │
│  │  │                      │  │    liveness)    │ │     │
│  │  └──────────┬───────────┘  └────────┬────────┘ │   │
│  │             └────────┬───────────────┘          │   │
│  │                      ▼                          │   │
│  │              LightpandaSession                  │   │
│  │              (singleton subprocess manager)     │   │
│  │                      │                          │   │
│  │                      │ stdio JSON-RPC           │   │
│  │                      ▼                          │   │
│  │  ┌────────────────────────────────────────────┐ │   │
│  │  │ proot-android (bundled in jniLibs)         │ │   │
│  │  │   → glibc rootfs (filesDir/rootfs/)        │ │   │
│  │  │      → /lightpanda mcp                     │ │   │
│  │  │        (stock aarch64-linux binary, AGPL)  │ │   │
│  │  └────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────┘
```

The CapApp process holds one long-lived `LightpandaSession`. Both the `CommandGatewayService` (invocation) and the `LightpandaBridgeService` (liveness, bound by Hub) share that singleton via the Android Application context.

## 3. Module structure

- **Path:** `capapps/lightpanda/`
- **applicationId:** `com.llmintentions.lightpanda`
- **Namespace prefix in Hub:** `browser.*`
- **Signature:** signed with the same key as Hub (required for the `com.llmintentions.permission.MCP_TOOL` signature-level permission).
- **Gradle module name:** `:capapps:lightpanda`
- **minSdk 26 / targetSdk 35** (matches template).

## 4. Components

### 4.1 `CommandGatewayService`
Extends `Service`, protocol-compliant per `spec/capapp-protocol.md`. Receives `ACTION_MCP_TOOL` intents with `tool_name`, `params`, `request_id` extras. Delegates to `LightpandaSession.callTool()`, returns the MCP-shaped result as the `result` extra of an `ACTION_TOOL_RESULT` broadcast to `com.llmintentions.hub`. Uses an executor so `onStartCommand` never blocks the main thread.

### 4.2 `LightpandaBridgeService`
Bound service exposing a minimal AIDL interface:
```aidl
interface ILightpandaBridge {
    void keepAlive();           // Hub calls on bind; session ensures subprocess warm
    boolean isReady();          // Hub calls to verify before routing traffic
    // String getDiagnostics();  // deferred to v0.2: uptime, pid, last-error
}
```
Bound by the Hub at CapApp discovery time (`BIND_AUTO_CREATE`). The bind itself is what keeps the CapApp process alive beyond the fire-and-forget Intent model — so the Lightpanda subprocess stays warm across tool calls without the CapApp needing to run its own foreground service. Survives Android memory pressure via `BIND_AUTO_CREATE` auto-rebind.

### 4.3 `LightpandaSession`
Singleton. Responsibilities:
- Own the subprocess (`proot-android -r <rootfs> -- /lightpanda mcp`) spawned via `ProcessBuilder`
- Read/write stdio pipes on dedicated threads
- Implement JSON-RPC id multiplexing (map of pending callback → in-flight id)
- Serialize stdin writes through a mutex (one pipe, can't interleave frames)
- Detect crashes (EOF on stdout, non-zero exit value), fail in-flight callbacks, respawn lazily on next call
- Enforce per-call timeout (60 s default, overridable via extra `extra_timeout_ms`)
- Crash-loop cool-down: ≥3 crashes in 60 s → return empty `tools/list`, log, back off

### 4.4 `LightpandaListToolsReceiver`
Receives `ACTION_LIST_TOOLS` discovery intent from the Hub. Calls `LightpandaSession.getCachedToolList()` (which calls Lightpanda's `tools/list` lazily the first time, caches for the process lifetime), returns the MCP tool list JSON array as the Intent response. This is how the Hub gets the dynamic tool set rather than a static `res/xml/mcp_tools.xml`.

### 4.5 `LightpandaFileProvider`
Standard `FileProvider` for serving large tool-call outputs (screenshots, full-page markdown) via `content://` URIs. Outputs are written to `filesDir/tool-outputs/<request_id>.<ext>`, granted `FLAG_GRANT_READ_URI_PERMISSION` to the Hub, deleted after Hub reads or after 60 s TTL (whichever first).

### 4.6 `FirstRunProvisioner`
One-time setup worker (runs on CapApp launch / Hub bind if `filesDir/rootfs/` is absent or SHA256 mismatches):
1. Download pinned tarball from hosted URL (GitHub Release on a pinned tag) — ~60–75 MB total: `proot-android` (~1 MB) + minimal glibc rootfs (~35–50 MB) + `lightpanda-aarch64-linux` (~25 MB) + `cacert.pem` (~200 KB).
2. Verify SHA256 against a hash embedded in the APK.
3. Unpack to `filesDir/rootfs/`, write marker `rootfs/.llm_intentions_marker` with version.
4. Handles: network loss (resumable if possible), hash mismatch (abort + user error), disk full.
5. Surfaces progress in the CapApp's `CapAppSettingsActivity` (the only Activity).

## 5. Tool invocation flow

1. MCP client (Claude Code) calls `browser.goto` with `{ url: "..." }`.
2. Hub namespace router: `browser` → `com.llmintentions.lightpanda`. Strip prefix → tool name `goto`.
3. Hub sends `ACTION_MCP_TOOL` intent to `CommandGatewayService` with `tool_name=goto`, `params={url:...}`, `request_id=<uuid>`.
4. `CommandGatewayService` → `LightpandaSession.callTool("goto", params, requestId, timeoutMs=60000)`.
5. Session generates JSON-RPC id `N`, records `N → requestId`, writes `{"jsonrpc":"2.0","id":N,"method":"tools/call","params":{"name":"goto","arguments":{...}}}` + newline to Lightpanda stdin.
6. Stdout reader thread parses `{id:N, result:...}`, looks up `requestId`, hands to session callback.
7. Session builds MCP content array. The threshold is measured on the **serialized size of the full result JSON object** that will be placed in the Intent `result` extra:
   - ≤256 KB: `{type:"text", text:"..."}` (or `{type:"image", data:"<base64>"}` for small images) inline.
   - >256 KB **or** any raw binary payload (e.g. PNG screenshot): write bytes to `filesDir/tool-outputs/<requestId>.<ext>`, emit `{type:"resource", uri:"content://com.llmintentions.lightpanda.files/..."}` instead. The JSON in the Intent extra stays small (just metadata + URI).
8. `sendBroadcast(ACTION_TOOL_RESULT, signaturePermission=MCP_TOOL)` to `com.llmintentions.hub` with `request_id` and `result` JSON string.
9. Hub receives broadcast, if result includes `resource` URI: reads via `ContentResolver`, forwards bytes over HTTP MCP. Deletes source file via `revokeUriPermission` / direct delete.

The `resource`-URI path is necessary because the Intent extras Bundle is serialized via Binder with a ~1 MB per-Parcel ceiling. Keeping `result` text small (just JSON metadata) means Binder never sees the payload.

## 6. Lifecycle

### 6.1 Process lifetime
- Hub discovers CapApp via `PackageManager`, calls `bindService(LightpandaBridgeService, BIND_AUTO_CREATE)` on startup.
- Bind keeps CapApp process alive while Hub (itself a foreground service) is running.
- `LightpandaSession` is instantiated as a field on the Application class — shared between `CommandGatewayService` and `LightpandaBridgeService`.
- Lightpanda subprocess is spawned **lazily** on the first request that needs it — either the first `tools/list` discovery call or the first `tools/call`, whichever arrives first. Not spawned eagerly at bind time — saves battery when the agent is idle.
- On Hub unbind (Hub shutting down), Session sends JSON-RPC `shutdown`, waits 2 s, then `SIGTERM`.

### 6.2 OOM survivability
If Android kills the CapApp process under memory pressure:
- Hub's `ServiceConnection.onServiceDisconnected` fires, `BIND_AUTO_CREATE` auto-rebinds.
- CapApp process restarts, `LightpandaSession` re-instantiates empty.
- Lightpanda subprocess respawns on next tool call (cold-start latency ~1–2 s + proot + V8 init).
- Agent sees no error — just slower first call.

### 6.3 Crash recovery
- `LightpandaSession` detects subprocess death via EOF on stdout or `Process.exitValue()`.
- In-flight callbacks fail with MCP error code `-32099`, message `"browser crashed, restarting"`.
- Lazy respawn on next tool call.
- Crash-loop guard: ≥3 crashes in 60 s → return empty `tools/list` on subsequent discovery, log structured error. Hub sees "no browser tools available," agent degrades.

## 7. First-run provisioning

Hosted at a pinned GitHub Release under the project repo. Blob contents SHA256-pinned inside the APK at build time.

Blob layout (single tarball):
```
lightpanda-rootfs-v0.1.0-aarch64.tar.zst
├── proot-android                    # static, from termux/proot build
├── rootfs/
│   ├── lib/ld-linux-aarch64.so.1    # minimal glibc
│   ├── lib/libc.so.6                # ...
│   ├── lib/libssl.so.3
│   ├── lib/libcrypto.so.3
│   ├── lib/libcurl.so.4
│   ├── etc/ssl/certs/cacert.pem     # Mozilla bundle
│   └── lightpanda                    # stock aarch64-linux release
```

Rootfs is a **minimal glibc environment** (Debian `--variant=minbase` stripped, or a hand-curated minimum — spike will determine which is simpler and smaller). Alpine is ruled out (musl vs. glibc ABI).

CURL_CA_BUNDLE env var passed to proot → `lightpanda` so libcurl finds the CA bundle. This closes the one known runtime blocker from upstream issue [#2103](https://github.com/lightpanda-io/browser/issues/2103).

Upgrade path: a new Lightpanda upstream release → we pin a new tarball URL + SHA256 → ship a CapApp APK update → first launch after update re-provisions. No in-place partial upgrade in v0.1.

## 8. Large payloads

- **Screenshots:** `screenshot` tool writes raw PNG bytes to `filesDir/tool-outputs/<requestId>.png`. Content URI returned. Hub streams to Claude Code.
- **Markdown / full-page content:** same path if >256 KB.
- **Inline threshold:** 256 KB. Below → `{type:"text", text:"..."}`. Above → `{type:"resource", uri:"..."}`.
- **Cleanup:** Source files deleted after Hub reads, or after 60 s TTL via a scheduled `WorkManager` cleanup task. `revokeUriPermission` called defensively.

Future optimization: if the protocol gains a `content` type with `ParcelFileDescriptor` support, switch to streaming pipe-fds for lower latency on screenshots. Not in v0.1.

## 9. Licensing

- **Lightpanda:** AGPL-3.0. Distributing the binary inside the CapApp makes this APK AGPL with source-available. Repository must be public and reflect the built binary.
- **Hub and other CapApps:** unaffected. Binder/Intent IPC is an arm's-length interface, not linking. The Hub stays under whatever license it currently has; only the Lightpanda CapApp inherits AGPL.
- **proot:** GPL-2.0. Same argument — distributable alongside Lightpanda in this APK.
- **glibc:** LGPL-2.1. Dynamic linking; source-available requirement satisfied by upstream Debian/Ubuntu.
- **cacert.pem:** Mozilla Public License 2.0. No issue.

## 10. Milestones

### M0 — Proof of life (half-day)
On-device via adb (SELinux / ptrace already proven because Claude Code proot works on this phone):
1. Push `lightpanda-aarch64-linux`, `proot-android`, minimal glibc rootfs, `cacert.pem`.
2. Run `proot -r rootfs -- /lightpanda mcp` manually, type `tools/list`, confirm response.
3. `tools/call` `goto("https://example.com")` + `markdown`, confirm TLS + networking.

**Gate:** if any of the above fails, pivot to a LAN sidecar architecture (Lightpanda runs on a Tailscale-reachable host, CapApp becomes a thin HTTP proxy) before writing Kotlin.

### M1 — Standalone CapApp shell (2–3 evenings)
New Gradle module `capapps/lightpanda/`. `LightpandaSession` with spawn/stdio/multiplex/crash-recovery. Debug `CapAppSettingsActivity` with "list tools" and "goto example.com → markdown" buttons that call the Session directly (no Hub yet). End-to-end verifies the subprocess management works in-app.

### M2 — Rootfs provisioning UX (1 evening)
`FirstRunProvisioner` + settings activity progress UI. SHA256 verification. Resumable download. Failure states handled.

### M3 — Hub integration (2–3 evenings)
`ListToolsReceiver` for dynamic discovery. `CommandGatewayService` wiring. `LightpandaBridgeService` AIDL + Hub-side bind. Minor Hub change: if a discovered CapApp implements `ILightpandaBridge` style (or if we generalize to a "liveness bind" convention), Hub binds at startup. End-to-end: Claude Code in proot → Hub → CapApp → Lightpanda → back.

### M4 — Stability polish (variable)
Crash-loop cool-down. Concurrency stress (10 parallel `tools/call`). Battery-idle check. Real agent workloads — multi-step flows that previously burned us — as acceptance tests.

### v0.1 ship
Tagged release. APK available as sideload. Spec doc updated with what changed during implementation.

## 11. Spikes and unknowns

- **Rootfs base.** Debian `--variant=minbase` vs. hand-curated minimum glibc tree. Compare size + reproducibility during M0.
- **Proot binary source.** Termux's prebuilt (preferred) or build from `proot-me/PRoot`. Verify version-compatibility with Zig/glibc Lightpanda.
- **Screenshot fidelity on Lightpanda.** 5-minute desktop spike before M3 — run `lightpanda mcp` on a Mac, call `screenshot`, see what comes back. Lightpanda has no visual renderer by design; confirm the tool returns something useful before agents depend on it.
- **Lightpanda release cadence + pin strategy.** If upstream ships frequently, set a policy: pin a known-good tag, upgrade quarterly or on security advisories.
- **Hub change for liveness bind.** Is there a general "Hub-bound CapApp" convention we should add to the protocol, or is this a one-off for Lightpanda? Recommendation: add a manifest metadata flag `com.llmintentions.mcp.requires-liveness-bind=true`, Hub binds to any CapApp that sets it. Future CapApps with long-lived resources (database connections, running ML models) reuse the pattern.
- **Read [kaeawc/android-mcp-sdk](https://github.com/kaeawc/android-mcp-sdk)** before M1 for Kotlin MCP server scaffolding ideas. Its transport is wrong for us; patterns may still save hours.

## 12. Out of scope (follow-ups)

- **v0.2: Native Bionic cross-compile.** Zig + NDK sysroot for `aarch64-linux-android`, drop proot. ~5–20 % perf win, smaller rootfs, cleaner runtime. Separate spec.
- **CDP pass-through.** Lightpanda also speaks CDP. We're MCP-only in v0.1; CDP over a localhost port for Puppeteer/Playwright clients is a follow-up if there's demand.
- **Shared rootfs with Claude Code's Termux proot.** Not possible under Android sandboxing without root; revisit only if the rootfs duplication becomes a real constraint.
- **Hub-side upstream MCP aggregation.** Orthogonal — a general capability (Hub acts as an MCP client to other servers) would benefit more than just Lightpanda, but isn't required for this design.
- **Tool-specific UX polish.** `screenshot` compression, `markdown` chunking for very large pages, `evaluate` result serialization edge cases. Handled as they come up.

## 13. Open questions

1. **Namespace confirmation:** `browser.*` is my proposal (matches `files.*`, `notify.*` style). Alternatives: `lightpanda.*` (product-specific), `web.*` (too generic). If you want a different prefix, name it.
2. **Liveness-bind protocol change:** add the `requires-liveness-bind` manifest flag to the CapApp protocol now, or keep it Lightpanda-specific until a second CapApp needs it? Protocolizing now is cheap and future-proofs.
3. **Hosting for pinned rootfs tarball:** GitHub Releases on `dreliq9/llm-intentions` (public repo, easy) vs. a private bucket (supply-chain control). GitHub is my recommendation unless business-plan-A considerations say otherwise.
