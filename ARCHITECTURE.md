# Architecture

LLM Intentions is a distributed system where Android apps expose capabilities to LLMs through a central gateway. This document describes how the pieces fit together.

## Core Concepts

### 1. CapApps

A **CapApp** (Capability App) is an Android application that exists to serve tools to LLMs. It has no user-facing UI — no activities, no screens, no launcher icon (optionally). It runs as a background service that responds to Android Intents.

Each CapApp:
- Declares its available tools via the AndroidMCP protocol
- Receives tool invocations as structured Intent extras
- Returns results as Intent response data
- Can access any Android API its permissions allow (files, contacts, sensors, network, etc.)

CapApps are independent APKs. They can be installed, updated, and removed without touching the Hub or any other CapApp. They can be open source or proprietary.

### 2. Hub

The **Hub** is the central gateway. It:

1. **Discovers** CapApps on the device using the AndroidMCP protocol (queries PackageManager for apps that declare MCP tool capabilities)
2. **Aggregates** all discovered tools into a single registry
3. **Namespaces** tools by source (e.g., `files.file_read`, `notify.notifications_list`, `people.contacts_search`)
4. **Exposes** the full tool registry over MCP via streamable-http on port 8379
5. **Routes** incoming tool calls to the correct CapApp via Android Intents
6. **Provides** built-in tools for core Android functions (intents, system controls, sharing)

The Hub also maintains an **inbox** — messages sent to it via Android's share sheet appear as MCP-readable messages. This lets users push content from any Android app directly into an LLM conversation.

### 3. Intent Mesh (Experimental)

> **Status: Early experiment.** Scripts and a draft spec are included, but this is not a production-ready system yet.

**Intent Mesh** is an experimental protocol for routing work between multiple LLMs through the Android Intent system and direct API calls.

The core idea: different LLMs have different strengths. Claude excels at structured analysis. Grok has real-time access to X/Twitter. Rather than trying to make one LLM do everything, Intent Mesh lets them collaborate.

Early experiments:
- **Claude → Grok**: Via the xAI API, Claude sends analysis context and receives Grok's real-time sentiment data
- **Clipboard bridge**: Using Android's PROCESS_TEXT intent to send text to Grok, with clipboard monitoring for the return channel
- **Synthesis**: Both LLM outputs are merged into a single signal (e.g., technical analysis + social sentiment = trade thesis)

The workflow is manual and rough today — the vision is automated multi-LLM orchestration. See [spec/intent-mesh.md](spec/intent-mesh.md) for the draft spec.

## System Topology

```
┌─────────────────────────────────────────────────────┐
│                   Android Device                     │
│                                                      │
│  ┌──────────────────────────────────────────────┐   │
│  │              CapApp Layer                       │   │
│  │                                               │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐        │   │
│  │  │  Files  │ │ Notify  │ │ People  │  ...    │   │
│  │  │  CapApp   │ │  CapApp   │ │  CapApp   │         │   │
│  │  └────┬────┘ └────┬────┘ └────┬────┘        │   │
│  │       │           │           │               │   │
│  └───────┼───────────┼───────────┼───────────────┘   │
│          │   Android Intents     │                    │
│  ┌───────▼───────────▼───────────▼───────────────┐   │
│  │                Hub                             │   │
│  │         MCP Gateway (port 8379)                │   │
│  │                                                │   │
│  │  ┌────────────┐ ┌──────────┐ ┌─────────────┐  │   │
│  │  │ Discovery  │ │ Namespace│ │ Built-in    │  │   │
│  │  │ Engine     │ │ Router   │ │ Tools       │  │   │
│  │  └────────────┘ └──────────┘ └─────────────┘  │   │
│  └───────────────────┬────────────────────────────┘   │
│                      │ streamable-http                 │
│  ┌───────────────────▼────────────────────────────┐   │
│  │             Termux Layer                        │   │
│  │                                                 │   │
│  │  ┌──────────────┐    ┌──────────────────┐      │   │
│  │  │ termux-mcp   │    │ hub-proxy        │      │   │
│  │  │ (port 8378)  │    │ (port 8381)      │      │   │
│  │  │ Termux APIs  │    │ SDK bridge       │      │   │
│  │  └──────────────┘    └────────┬─────────┘      │   │
│  │                               │                 │   │
│  │  ┌────────────────────────────▼──────────────┐  │   │
│  │  │         proot Ubuntu                       │  │   │
│  │  │    Claude Code / MCP Clients               │  │   │
│  │  └────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

## Tool Namespacing

When Hub discovers a CapApp, it prefixes all of that app's tools with a namespace derived from the source. This prevents collisions and makes tool origin clear.

| Source | Namespace | Example |
|--------|-----------|---------|
| Hub built-in (Android) | `android.*` | `android.send_intent` |
| Hub built-in (System) | `system.*` | `system.battery` |
| Hub meta | `hub.*` | `hub.status` |
| Files CapApp | `files.*`, `fs.*` | `files.file_read` |
| Notify CapApp | `notify.*` | `notify.notifications_list` |
| People CapApp | `people.*` | `people.contacts_search` |
| Device CapApp | `device.*` | `device.sensor_read` |
| Taichi CapApp | `taichi.*` | `taichi.paper_trade` |
| Termux bridge | `termux.*` | `termux.battery` |

External CapApps can expose tools under multiple namespaces if they provide distinct capability groups (e.g., Files exposes both `files.*` for user-facing operations and `fs.*` for low-level filesystem access).

## Response Envelope and Tool Metadata

Every tool response in LLM Intentions is wrapped in a canonical envelope. This is the system's primary mechanism for making tool failures self-explanatory to the LLM.

### Envelope shape

The envelope has three states:

```
OK: <one-line summary>

<data JSON>
```

```
WARN: <one-line summary>
Hint: <optional actionable line>

<partial data JSON>
```

```
FAIL: <one-line summary>
Hint: <actionable line>

Raw:
<exception JSON>
```

The wire format is plain text — `Envelope.renderText()` in `mcp-core` produces it. The Node-side `termux-mcp` server has its own `envelope.js` that mirrors the same contract.

### Tool metadata

Every tool registers with a metadata block alongside its parameter schema:

```kotlin
registry.textTool(
    name = "contacts_search",
    description = "...",
    params = jsonSchema { string("query", "...") },
    metadata = toolMetadata {
        destructive = false
        idempotent = true
        latencyClass = LatencyClass.FAST
        permission("READ_CONTACTS")
        failureMode(
            pattern = "permission|SecurityException",
            hint = "Grant READ_CONTACTS in Settings → Apps → People."
        )
        example(intent = "Find Ada in contacts") { args -> args["query"] = "Ada" }
    },
) { args -> doSearch(args) }
```

The metadata declares:
- **Behavior** — `destructive`, `idempotent`, `latencyClass` so the LLM can decide whether to confirm a call.
- **Permissions** — Android permissions the tool needs. Surfaced in `hub.status` so the user can see what's missing.
- **Failure modes** — regex patterns matched against thrown exception messages, each paired with an actionable hint.
- **Examples** — sample arg sets that the LLM (or a fixture) can use as a starting point.

### Failure-mode resolution

When a tool handler throws, the framework calls `Envelope.fromException(toolName, metadata, exception)`. That function:

1. Captures the exception type and message.
2. Iterates the tool's `failureModes` and returns the first hint whose `pattern` matches the message (or whose `exceptionType` matches the simple/canonical class name).
3. Renders a `FAIL:` envelope with the hint inlined.

The result: the LLM sees `Hint: Grant READ_CONTACTS in Settings → Apps → People.` instead of `Error: SecurityException: Permission denial`.

### Why this matters

A tool that returns "Error: 403" tells an LLM nothing actionable. A tool that returns `FAIL: rate limited / Hint: wait 60s and retry` lets the agent recover without escalating to the user. Aggregated across hundreds of tools, the difference is whether an LLM agent feels reliable or feels broken.

See [AGENTS.md](AGENTS.md) for the CapApp-author rules (throw on hard errors, ship metadata, don't catch-and-stringify) and [FUTURE.md](FUTURE.md) for the rollout status across CapApps.

## Data Flow: Tool Call

1. MCP client sends `tools/call` with `name: "files.file_read"` to Hub
2. Hub's namespace router identifies the target CapApp: `com.llmintentions.files`
3. Hub constructs an Android Intent with the tool name and parameters as extras
4. Intent is delivered to the CapApp's `ToolAppService` via `startService`
5. CapApp executes the operation; result (or thrown exception) goes through `Envelope.renderText()` in the CapApp process
6. CapApp returns the rendered envelope to Hub via a broadcast reply keyed by callback ID
7. Hub forwards the envelope text to the MCP client

## Data Flow: Intent Mesh

1. Claude runs technical analysis via Taichi CapApp (on-device)
2. Claude sends the analysis context to Grok via xAI API (or PROCESS_TEXT intent)
3. Grok processes the context, adds real-time X/Twitter sentiment
4. Grok's response returns to Claude (via API response or clipboard bridge)
5. Claude synthesizes both analyses into a final signal

## Security Model

- CapApps run as separate Android apps with their own permission sandboxes
- Hub communicates with CapApps via Android's IPC mechanism (Intents), which is process-isolated
- MCP transport is local-only (127.0.0.1) — not exposed to the network
- Each CapApp declares its own Android permissions (e.g., Files needs storage access, People needs contacts access)
- The Hub does not grant permissions to CapApps — each app must request its own

## Adding a New CapApp

See [capapp-template/](capapp-template/) for a starter project and [spec/capapp-protocol.md](spec/capapp-protocol.md) for the registration protocol.

The author contract: extend `ToolAppService`, register tools through `registry.textTool(...)` with a `toolMetadata { ... }` block, and let exceptions propagate. The framework formats the envelope and threads the failure-mode hint. See [AGENTS.md](AGENTS.md) for the antipatterns to avoid (the most common is `try { ... } catch (e) { "Error: ${e.message}" }`, which silently masks real failures and renders as `OK: succeeded`).
