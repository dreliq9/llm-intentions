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

External CapApps can expose tools under multiple namespaces if they provide distinct capability groups (e.g., Files exposes both `files.*` for user-facing operations and `fs.*` for low-level filesystem access).

## Data Flow: Tool Call

1. MCP client sends `tools/call` with `name: "files.file_read"` to Hub
2. Hub's namespace router identifies the target CapApp: `com.llmintentions.files`
3. Hub constructs an Android Intent with the tool name and parameters as extras
4. Intent is delivered to the CapApp's `CommandGatewayService`
5. CapApp executes the operation and returns results via the Intent response
6. Hub wraps the result in MCP format and returns it to the client

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

See [capapps/template/](capapps/template/) for a starter project and [spec/capapp-protocol.md](spec/capapp-protocol.md) for the registration protocol.
