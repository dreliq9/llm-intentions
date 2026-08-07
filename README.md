# LLM Intentions

> An Android capability substrate for AI assistants: installable CapApps, a local Hub, and a model-independent MCP surface.

**Status: Alpha** — deployed on real Android hardware. The Hub and bundled CapApps are being hardened for use beyond CLI agents.

## What is this?

LLM Intentions introduces a new kind of Android app: the **CapApp** (Capability App). A CapApp exposes semantic capabilities—contacts, files, notifications, sensors, application-specific operations—to an AI through the [Model Context Protocol (MCP)](https://modelcontextprotocol.io).

The **Hub** discovers installed CapApps, aggregates their tools into one namespaced MCP surface, and mediates access to device capabilities.

The long-term model is deliberately provider-independent:

```text
Claude / ChatGPT / Gemini / Grok / local model
                     |
              public HTTPS MCP
                     |
              Intentions Relay
                     |
          outbound authenticated tunnel
                     |
              Android Hub
                     |
          trusted local CapApp IPC
             /       |       \
        People     Files    Device ...
```

The phone remains the authority over its capabilities. The relay is transport and routing infrastructure; it does not implicitly authorize device actions.

## Current security model

The project began as a personal prototype and is being hardened before remote consumer access is enabled.

### Hub MCP

The Android Hub's developer MCP endpoint:

- binds to `127.0.0.1` only;
- is **not** exposed on Wi‑Fi/LAN by default;
- requires an app-private bearer token even on localhost;
- supports immediate bearer-token rotation/revocation from the Hub UI;
- validates browser `Origin` values;
- bounds and byte-parses HTTP requests;
- supports MCP `2026-07-28` stateless request semantics while retaining an initialize-era compatibility path.

Loopback is intentionally not treated as caller identity: another installed Android application can open a localhost socket. The bearer credential prevents an unrelated APK from using the Hub as an unauthenticated capability deputy.

### CapApp IPC

**Bundled rebuilt CapApps now use CapApp Protocol v1**, an authenticated Binder/AIDL transport. Every Binder transaction verifies the official Hub package identity and matching signer before capability work begins, and results return over Binder callbacks rather than a caller-selected broadcast target.

The Hub retains the original started-service/broadcast v0 path only as a migration fallback for older already-installed CapApp APKs. New `ToolAppService` subclasses fail closed: v0 execution is disabled unless explicitly opted into.

Remote relay access should remain disabled for sensitive user data until the Hub-side policy/consent layer is in place and the Binder path has passed installed-device security testing.

See [`spec/capapp-protocol.md`](spec/capapp-protocol.md) and [`docs/superpowers/specs/2026-08-06-trusted-consumer-access.md`](docs/superpowers/specs/2026-08-06-trusted-consumer-access.md).

## Self-explanatory tool failures

Every tool response can render a canonical envelope:

```text
OK: contacts_search returned 3 contacts

[
  { "name": "Ada Lovelace", "phone": "+1..." },
  ...
]
```

or:

```text
FAIL: notify.post failed: SecurityException
Hint: Grant POST_NOTIFICATIONS in Settings → Apps → Notify

Raw:
{ "type": "SecurityException", "message": "..." }
```

The `Hint:` line comes from a tool's `toolMetadata { failureMode(...) }` declaration. Permission failures, rate limits, unavailable sensors, and other known failure shapes can therefore tell the model what to do next instead of returning an opaque stack trace.

CapApp authors get this behavior through the common registry helpers. See [AGENTS.md](AGENTS.md) for the author contract.

## Architecture

```text
┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐
│  Files  │    │ Notify  │    │ People  │    │  Your   │
│ CapApp  │    │ CapApp  │    │ CapApp  │    │ CapApp  │
└────┬────┘    └────┬────┘    └────┬────┘    └────┬────┘
     │              │              │              │
     └──────────────┼──────────────┼──────────────┘
                    │ authenticated Binder v1
                    ▼
     ┌─────────────────────────────────────┐
     │            Android Hub              │
     │ MCP gateway / discovery / routing   │
     │                                     │
     │  localhost MCP: 127.0.0.1:8379     │
     │  authenticated developer access     │
     └──────────────────┬──────────────────┘
                        │
                        ▼
                 local MCP client
```

A future consumer relay adds an **outbound** phone connection; it does not turn port `8379` into an Internet-facing server.

## MCP modernization

The Hub is migrating from MCP `2025-06-18` to MCP `2026-07-28` without breaking the deployed prototype all at once.

The modern path includes:

- `server/discover`;
- stateless per-request metadata;
- `MCP-Protocol-Version`, `Mcp-Method`, and `Mcp-Name` validation;
- deterministic tool/resource lists;
- cache hints;
- standard MCP tool annotations;
- modern server identity metadata;
- JSON responses for modern requests.

The initialize-era path remains a compatibility surface during migration.

## Included CapApps

| CapApp | Package | Current rebuilt transport | Role |
|---|---|---|---|
| **Files** | `com.llmintentions.files` | Binder v1 | Scoped/sandboxed file operations |
| **Files Dev** | `com.llmintentions.files.dev` | Binder v1 | Broad development filesystem operations |
| **Notify** | `com.llmintentions.notify` | Binder v1 | Notification read/post/reply workflows |
| **People** | `com.llmintentions.people` | Binder v1 | Contacts and calendar capabilities |
| **Device** | `com.llmintentions.device` | Binder v1 | Sensors, TTS, vibration, clipboard |
| **Taichi** | `com.taichi.android` | Binder v1 | Paper crypto trading, market/on-chain analysis |

The Hub Apps screen shows the detected transport for each installed provider (`Binder v1` or `Legacy v0`) and performs a transport-aware health check. The exact tool count evolves quickly; query `tools/list` or use the Hub UI rather than treating a README count as authoritative.

## Built-in Hub capabilities

The Hub also exposes Android/system capabilities for application launching, sharing, deep links, device state, inbox/share-to-agent flows, Hub health, and discovery/refresh.

## Authenticated local quick start

This is the **developer/advanced-user path**. The consumer path will use the outbound relay and provider-specific onboarding instead of requiring CLI configuration.

### 1. Install

Install:

- the LLM Intentions Hub APK;
- one or more CapApp APKs.

For Binder v1 first-party builds, the Hub and bundled CapApps must use signing identities Android considers a match. See [`docs/release-signing.md`](docs/release-signing.md).

Grant only the Android permissions required by the CapApps you intend to use.

### 2. Start the Hub

Open LLM Intentions and enable the Hub service. It listens on:

```text
http://127.0.0.1:8379/mcp
```

The listener is loopback-only.

### 3. Copy the authenticated MCP config

The Hub dashboard displays an **Authenticated Local MCP Config**. Copy it from the app rather than manually inventing a token.

It has this shape:

```json
{
  "mcpServers": {
    "hub": {
      "type": "http",
      "url": "http://127.0.0.1:8379/mcp",
      "headers": {
        "Authorization": "Bearer <device-local-token>"
      }
    }
  }
}
```

The token is generated on-device and stored in app-private, no-backup storage. Do not publish it, place it in repository files, or reuse it as a future relay credential. The Hub can rotate it; rotation immediately revokes previously copied configurations.

### 4. Verify

Connect an MCP client that can reach the phone's localhost context and call a harmless Hub status/discovery tool. The Hub should return the installed CapApps and their aggregated tools.

On a v1-capable rebuilt installation, the Apps screen should identify the bundled CapApps as **Binder v1** with healthy authenticated descriptor round-trips.

## Termux

Termux is still useful for advanced experiments and the separate `termux-mcp` Node server, but **Termux is not intended to remain a prerequisite for ordinary LLM Intentions users**.

The former `hub-proxy`/Wi‑Fi workflow should be treated as prototype-era tooling rather than the recommended security architecture.

## Consumer access roadmap

The current implementation sequence is:

1. **H0 — local transport hardening + MCP modernization** — code/CI hardened; installed-phone smoke test remains.
2. **H1 — authenticated Binder CapApp IPC** — bundled migration implemented; installed-device trust/signing tests remain.
3. **H2 — policy, consent, grants, and audit**
4. **H3 — outbound authenticated Intentions Relay**
5. **H4 — consumer provider onboarding**
6. **H5 — aggressive capability expansion**

The target user experience is eventually:

1. install Hub;
2. install/enable CapApps;
3. tap **Connect assistant**;
4. choose a supported provider;
5. select a policy preset such as **Read only** or **Ask before changes**;
6. use the normal mobile chat app.

No Termux, port forwarding, router configuration, or hand-edited MCP JSON should be required for that path.

## Build your own CapApp

See:

- [`capapp-template/`](capapp-template/) for the starter pattern;
- [AGENTS.md](AGENTS.md) for the tool/failure author contract;
- [`spec/capapp-protocol.md`](spec/capapp-protocol.md) for the local CapApp protocol;
- [`mcp-intent-api/`](mcp-intent-api/) for the Android IPC SDK;
- [`mcp-core/`](mcp-core/) for the pure-JVM MCP/tool primitives.

New privileged CapApps should use Binder v1. Third-party independently signed CapApps will use the explicit pairing/trust-store path rather than weakening the first-party signer check.

## Intent Mesh

`intent-mesh/` remains an experimental multi-model routing idea. It is intentionally below the trust substrate in the roadmap: secure capabilities and consumer access are higher priority than multi-model orchestration.

## Project structure

```text
llm-intentions/
├── README.md
├── ARCHITECTURE.md
├── AGENTS.md
├── FUTURE.md
├── mcp-core/                 # Pure-JVM MCP/tool core
├── mcp-intent-api/           # Android CapApp IPC SDK
├── llm-intentions/           # Android Hub APK
├── tool-device/              # Device CapApp
├── tool-notify/              # Notifications CapApp
├── tool-people/              # Contacts/calendar CapApp
├── tool-files/               # Sandboxed files CapApp
├── tool-files-dev/           # Broad-access development files CapApp
├── taichi-android/           # Taichi CapApp
├── termux-mcp/               # Optional Termux MCP experiments
├── intent-mesh/              # Experimental multi-LLM routing
├── capapp-template/
├── spec/
└── docs/
```

## Open ecosystem

The protocol is intended to support both open-source and proprietary CapApps. A third-party trust/pairing model is the next Binder-security step so independently signed CapApps do not have to share the first-party signing key.

## License

Apache 2.0 — see [LICENSE](LICENSE). Includes an explicit patent grant.
