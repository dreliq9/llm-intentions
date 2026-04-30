# Future Development

For the current working state, see [README.md](README.md) and [ARCHITECTURE.md](ARCHITECTURE.md). This document is what's coming.

## Done — Wave 2 (April 2026)

The headline shift was making tool failures self-explanatory.

- **Canonical envelope.** Every tool response renders as `OK|WARN|FAIL: <summary>` plus an optional `Hint:` line and the data or exception payload (`mcp-core` via `Envelope.renderText()`). Clients no longer parse free-form text to tell success from failure.
- **`toolMetadata` DSL.** Tools declare `destructive`, `idempotent`, `latencyClass`, required permissions, regex `failureMode(pattern, hint)` entries, and parameter examples alongside the schema.
- **Failure-mode hints.** When a handler throws, `Envelope.fromException` regex-matches the exception against the tool's `failureModes` and injects the actionable hint. The LLM sees `Hint: Grant POST_NOTIFICATIONS in Settings → Apps → Notify` instead of `Error: SecurityException`.
- **`textTool` / `envelopeTool` helpers.** New tools register through these wrappers; handlers just throw on hard errors and the framework formats the envelope.
- **Coverage so far.** `tool-device` (6), `tool-notify` (5), `tool-people` (5), `taichi-android` (7), and the canary set in `termux-mcp` are on the new envelope + metadata.

See [AGENTS.md](AGENTS.md) for the CapApp-author contract and the common antipatterns to avoid.

---

## In flight

### Finish the metadata sweep

- **termux-mcp** — 6 tools on `textTool`, 18 still on the legacy `register()` pattern. Mechanical follow-up; same wire format, same metadata shape.
- **Catch-and-stringify cleanup** — periodic audit for handlers that swallow exceptions and return formatted error strings (renders as `OK: succeeded` with garbage data). Easy to spot, tedious to fix.
- **Wrapper-metadata threading** — anywhere a handler is re-registered (logging wrappers, call recorders), `metadata = tool.metadata` has to be threaded through or failure-mode hints silently break. Regression test lives in `mcp-intent-api`.

### Shared CapApp UI module

Four CapApps (`tool-device`, `tool-notify`, `tool-people`, `tool-files-dev`) duplicate ~95% of the same in-app code (tool list adapter, execute sheet, call log, log dialog). Extract into `:tool-ui-common` when someone needs to modify a copy — premature extraction is a worse failure mode than duplication here.

### Hub proxy retirement

`hub/hub-proxy.js` is a pass-through Node server on `:8381` that normalizes HTTP wire details for clients that don't tolerate the Hub's raw-socket output. Workaround, not a permanent layer — long-term goal is for MCP clients to hit `:8379/mcp` directly.

---

## Roadmap

### Cross-platform

- **macOS Hub** — working prototype at `llm-intentions-mac/` (stdlib Python HTTP server, JSON tool manifests, AppleScript bridge, 10 demo tools). Same wire protocol as Android. Next: bring the envelope + metadata DSL to the Mac side, publish the tool catalog.
- **iOS** — not started. App Intents / SiriKit are the obvious bridges. The protocol and envelope are reusable.

### SDK distribution

- **Maven Central** — publish `mcp-intent-api` and `mcp-core` so third parties can build CapApps without vendoring source. Main blocker is signing infrastructure.
- **Play Store** — Hub APK on Play. The previous blocker (`exported=true` Content Provider) is gone with the HTTP-only architecture; remaining work is store-listing assets, ProGuard rules for `kotlinx-serialization`, and a privacy policy.

### Protocol

- **Intent Mesh** — still an experiment (see [spec/intent-mesh.md](spec/intent-mesh.md)). Real workflows are manual today; the target is automated routing across multiple LLMs, picked per task. Needs a formal handshake, a session contract, and output attribution.
- **Server → client push** — currently the Hub is strictly request/response. For inbox notifications and price alerts to reach the LLM without polling, the Hub would need to emit MCP notifications. Possible over streamable-http with the right transport.

### Capabilities to grow into

- **On-device LLM tool-use** — when llama.cpp / MLX-Android / equivalent ship Android tool-use, they hit the Hub on localhost with no network round-trip. The path is already supported; we just need a local client.
- **Accessibility Service** — read screen content and perform UI actions. Extremely powerful, requires manual user enable, large privacy surface. Would unlock "what's on my screen?" queries and full UI automation.
- **Camera / mic capture as CapApps** — currently exposed via `termux-mcp`. Native-Android camera in a CapApp requires a foreground activity; tradeoff between UX and tool availability.

---

This list is not exhaustive — it's what's actively on the radar. Issues and PRs welcome.
