# LLM Intentions

> An open protocol and toolkit for turning Android apps into MCP tool servers, using Android Intents as the native routing layer.

**Status: Alpha** — Running in production on a real device. 118 tools, 8 sources, one phone.

## What is this?

LLM Intentions introduces a new kind of Android app: the **CapApp** (Capability App). A CapApp has no traditional UI. It exists purely to expose capabilities to LLMs via the [Model Context Protocol (MCP)](https://modelcontextprotocol.io). Any developer can build one.

The **Hub** is the central gateway. It discovers installed CapApps via Android Intents, aggregates their tools into a single MCP endpoint, and namespaces them by source. Connect any MCP client — Claude, Grok, a custom agent — and your phone becomes an AI-native device.

**Intent Mesh** takes it further: multiple LLMs can route work to each other through the same Intent layer. Claude delegates X/Twitter sentiment analysis to Grok. Grok sends structured data back. The phone becomes a multi-LLM orchestration bus.

This isn't a spec or a proposal. It's a working system.

## Architecture

```
┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐
│  Files  │    │ Notify  │    │ People  │    │  Your   │
│  CapApp   │    │  CapApp   │    │  CapApp   │    │  CapApp   │
└────┬────┘    └────┬────┘    └────┬────┘    └────┬────┘
     │              │              │              │
     └──────────────┼──────────────┼──────────────┘
                    │  Android Intents
                    │
     ┌──────────────▼──────────────────────┐
     │         Hub (port 8379)             │
     │    MCP Gateway / Tool Aggregator    │
     │                                     │
     │  ┌───────────┐  ┌────────────────┐  │
     │  │ Discovery │  │  Namespace     │  │
     │  │  Engine   │  │  Router        │  │
     │  └───────────┘  └────────────────┘  │
     └──────────────┬──────────────────────┘
                    │ MCP (streamable-http)
                    ▼
            ┌───────────────┐
            │  MCP Client   │
            │ Claude / Grok │
            │ / Any Agent   │
            └───────────────┘
```

### Intent Mesh — Multi-LLM Routing

```
┌──────────────┐   PROCESS_TEXT    ┌──────────────┐
│    Claude    │ ──── Intent ────> │     Grok     │
│  (Taichi TA) │                   │ (X Firehose) │
│              │ <── Clipboard ─── │              │
└──────────────┘    or xAI API     └──────────────┘
         │                                │
         └────── Intent Mesh ─────────────┘
              Synthesized Alpha Signal
```

Each LLM contributes what it's best at. Claude runs technical analysis via Taichi. Grok reads real-time X/Twitter sentiment. Intent Mesh merges both into a single signal.

## What's a CapApp?

A CapApp is an Android APK that:

1. **Exposes MCP tools** via Android Intents — no REST API, no server process needed
2. **Registers with Hub** — Hub discovers it automatically via the AndroidMCP protocol
3. **Has no UI** — it's a headless service that exists to be called by LLMs
4. **Runs natively** — full Android API access (files, contacts, notifications, sensors, etc.)

Think of it like a microservice, but instead of HTTP endpoints, it exposes LLM tools. Instead of a container, it runs as an Android app.

### Example CapApps (included)

| CapApp | Package | Tools | What it does |
|---------|---------|-------|-------------|
| **Files** | `com.llmintentions.files` | 15 | File system operations, downloads, media access |
| **Notify** | `com.llmintentions.notify` | 7 | Read, filter, dismiss, reply to notifications |
| **People** | `com.llmintentions.people` | 10 | Contacts, calendars, events |

### Built-in Hub Tools

| Namespace | Tools | What it does |
|-----------|-------|-------------|
| `android.*` | 15 | Intents, app launch, sharing, deep links, maps, dialer |
| `system.*` | 15 | Battery, clipboard, wifi, volume, torch, brightness, media control |
| `hub.*` | 5 | Status, health, refresh, inbox (Android share → LLM) |

## How It Differs from mobile-mcp

| | LLM Intentions | mobile-mcp |
|---|---|---|
| **Architecture** | Distributed CapApps + central Hub | Single MCP server |
| **Extensibility** | Any APK can be a CapApp — install and go | Monolithic codebase |
| **Multi-LLM** | Intent Mesh routes between LLMs | Single client model |
| **Tool count** | 118 tools across 8 sources | Spec-stage |
| **Transport** | Android Intents (native IPC) | Android Intents |
| **Discovery** | Automatic via AndroidMCP protocol | PackageManager query |
| **Production status** | Running daily on real hardware | Published spec |
| **Commercial ecosystem** | Supports proprietary CapApps | N/A |

## Quick Start

### Prerequisites

- Android device with [Termux](https://termux.dev) installed
- [Termux:API](https://wiki.termux.com/wiki/Termux:API) addon
- Node.js (`pkg install nodejs` in Termux)
- LLM Intentions Hub APK installed
- One or more CapApp APKs installed

### 1. Install termux-mcp

```bash
# In Termux
cd ~
git clone https://github.com/dreliq9/llm-intentions.git
cd llm-intentions/termux-mcp
npm install
```

### 2. Start the servers

```bash
# Terminal 1: termux-mcp (exposes Termux APIs as MCP tools)
node server.js

# Terminal 2: hub-proxy (bridges Hub to MCP clients)
node hub-proxy.js
```

### 3. Connect your MCP client

Add to your MCP client config (e.g., `~/.mcp.json`):

```json
{
  "mcpServers": {
    "hub": {
      "type": "http",
      "url": "http://127.0.0.1:8379/mcp"
    }
  }
}
```

### 4. Verify

Call `hub.status` — you should see all discovered CapApps and their tool counts.

## Build Your Own CapApp

See the [CapApp template](capapps/template/) for a skeleton you can fork.

See the [CapApp Protocol Spec](spec/capapp-protocol.md) for the full registration and discovery protocol.

## Project Structure

```
llm-intentions/
├── README.md                 # You are here
├── ARCHITECTURE.md           # Detailed system design
├── LICENSE                   # Apache 2.0
├── hub/                      # Hub proxy and bridge code
├── termux-mcp/               # Termux API → MCP server
├── intent-mesh/              # Multi-LLM routing scripts
├── capapps/
│   └── template/             # Skeleton for building new CapApps
├── spec/
│   ├── capapp-protocol.md      # How CapApps register and expose tools
│   └── intent-mesh.md        # Multi-LLM routing protocol
└── docs/
    └── prior-art-log.md      # Discovery timeline and prior art
```

## Income & Commercial CapApps

The LLM Intentions ecosystem supports both open source and proprietary CapApps. The protocol is open — build a CapApp, keep it closed, sell it. The Hub doesn't care.

For commercial integration or custom CapApp development, open an issue or reach out.

## License

Apache 2.0 — see [LICENSE](LICENSE). Includes an explicit patent grant.

## Acknowledgments

Built on [Model Context Protocol (MCP)](https://modelcontextprotocol.io) by Anthropic.
