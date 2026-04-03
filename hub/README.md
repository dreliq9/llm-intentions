# Hub Bridge

The Hub is an Android app (APK) that serves as the MCP gateway. This directory contains the **hub-proxy** — a Node.js bridge that translates between the Hub's streamable-http MCP endpoint and MCP SDK clients.

## hub-proxy.js

A thin MCP SDK proxy that:
1. Connects to Hub on port 8379
2. Forwards `tools/list` and `tools/call` requests
3. Exposes a standard MCP endpoint on port 8381

### Why a proxy?

Some MCP clients require specific transport features (like session ID headers) that Hub's built-in HTTP server doesn't provide. The proxy bridges this gap using the official MCP SDK transport.

### Usage

```bash
# In Termux
cd hub
npm install @modelcontextprotocol/sdk
node hub-proxy.js
```

Proxy listens on `http://127.0.0.1:8381/mcp`.

## Hub APK

The Hub Android app itself is not yet open-sourced in this repository. The APK:
- Discovers CapApps via the AndroidMCP protocol
- Aggregates tools with namespace routing
- Exposes 35 built-in tools (android.*, system.*, hub.*)
- Serves MCP over streamable-http on port 8379
- Maintains an inbox for Android share sheet → LLM messaging

APK source will be added in a future release.
