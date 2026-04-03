# CapApp Protocol Specification

**Version:** 0.1.0
**Date:** 2026-04-02
**Status:** Working Draft — implemented and running in production

## Abstract

This document specifies how an Android application becomes a **CapApp** (Capability App) — an app that exposes MCP-compatible tools to LLMs via the LLM Intentions Hub, using Android Intents as the transport layer.

## 1. Overview

A CapApp is an Android APK that:

1. Declares its tool capabilities via the AndroidMCP protocol
2. Receives tool invocations as Android Intents
3. Returns results as Intent response data
4. Is discovered automatically by the Hub

The CapApp has no user-facing UI. It runs as a service, responding to structured Intent calls from the Hub.

## 2. Registration

### 2.1 Manifest Declaration

A CapApp declares itself as an MCP tool provider in its `AndroidManifest.xml`. The service MUST be protected by a signature-level permission so that only the Hub (signed with the same key) can invoke it:

```xml
<permission
    android:name="com.llmintentions.permission.MCP_TOOL"
    android:protectionLevel="signature" />

<service
    android:name=".CommandGatewayService"
    android:exported="true"
    android:permission="com.llmintentions.permission.MCP_TOOL">
    <intent-filter>
        <action android:name="com.llmintentions.ACTION_MCP_TOOL" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
    <meta-data
        android:name="com.llmintentions.mcp.tools"
        android:resource="@xml/mcp_tools" />
</service>
```

### 2.2 Tool Declaration

Tools are declared in an XML resource (`res/xml/mcp_tools.xml`) or returned dynamically via a discovery Intent:

```xml
<mcp-tools namespace="files" version="1.0">
    <tool name="file_read"
          description="Read a file from device storage">
        <param name="path" type="string" required="true"
               description="Absolute file path" />
        <param name="encoding" type="string" required="false"
               description="File encoding (default: utf-8)" />
    </tool>
    <tool name="file_write"
          description="Write content to a file">
        <param name="path" type="string" required="true" />
        <param name="content" type="string" required="true" />
    </tool>
</mcp-tools>
```

## 3. Discovery

### 3.1 Hub Discovery Process

When Hub starts or `hub.refresh` is called:

1. Hub queries `PackageManager` for all services with the `com.llmintentions.ACTION_MCP_TOOL` intent filter
2. For each matching service, Hub reads the `com.llmintentions.mcp.tools` metadata
3. Hub registers all declared tools in its aggregated registry, prefixed with the CapApp's namespace
4. Hub reports the discovered CapApps and tool counts via `hub.status`

### 3.2 Dynamic Discovery

CapApps can also support dynamic tool listing by responding to a discovery Intent:

```
Action: com.llmintentions.ACTION_LIST_TOOLS
Response: JSON array of tool definitions
```

This allows CapApps to register tools at runtime based on device state, installed plugins, or user configuration.

## 4. Tool Invocation

### 4.1 Request Flow

When an MCP client calls a namespaced tool (e.g., `files.file_read`):

1. Hub strips the namespace prefix to get the tool name (`file_read`)
2. Hub identifies the target CapApp from the namespace mapping (`files` → `com.llmintentions.files`)
3. Hub constructs an Intent:

```
Action: com.llmintentions.ACTION_MCP_TOOL
Package: com.llmintentions.files
Extras:
  "tool_name": "file_read"
  "params": {"path": "/sdcard/notes.txt"}
  "request_id": "uuid"
```

4. Hub sends the Intent to the CapApp's `CommandGatewayService`
5. The service processes the request and returns the result

### 4.2 Response Format

The CapApp responds with:

```json
{
  "request_id": "uuid",
  "success": true,
  "content": [
    {
      "type": "text",
      "text": "File contents here..."
    }
  ]
}
```

Error responses:

```json
{
  "request_id": "uuid",
  "success": false,
  "error": "File not found: /sdcard/notes.txt"
}
```

### 4.3 Content Types

CapApps can return multiple content types in a single response:

- `text` — plain text or JSON
- `image` — base64-encoded image data
- `resource` — URI reference to a file or content provider

## 5. Namespacing

Each CapApp declares a namespace (or multiple namespaces) in its tool definition. The Hub uses this to:

- Prefix tool names in the aggregated registry
- Route incoming calls to the correct CapApp
- Prevent name collisions between CapApps

A single CapApp can expose multiple namespaces for logical grouping:

| CapApp | Namespaces | Purpose |
|---------|-----------|---------|
| Files | `files.*`, `fs.*` | User-level ops vs low-level filesystem |
| People | `people.*` | Contacts + calendar unified |

## 6. Lifecycle

### 6.1 Installation

When a new CapApp APK is installed:
1. User installs the APK (sideload or app store)
2. User calls `hub.refresh` (or Hub auto-discovers on next start)
3. Hub finds the new CapApp and registers its tools
4. Tools are immediately available to MCP clients

### 6.2 Updates

When a CapApp APK is updated:
1. New version is installed over the old one
2. Hub re-discovers on next `hub.refresh`
3. Tool registry is updated with any new/changed/removed tools

### 6.3 Removal

When a CapApp is uninstalled:
1. Hub detects the missing package on next `hub.refresh`
2. All tools from that namespace are removed from the registry
3. MCP clients see the tools disappear from `tools/list`

## 7. Permissions

CapApps request their own Android permissions independently:

- **Files CapApp**: `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`
- **Notify CapApp**: `BIND_NOTIFICATION_LISTENER_SERVICE`
- **People CapApp**: `READ_CONTACTS`, `WRITE_CONTACTS`, `READ_CALENDAR`, `WRITE_CALENDAR`

The Hub does not proxy or escalate permissions. Each CapApp must have its own permission grants from the user.

## 8. Building a CapApp

See [capapps/template/](../capapps/template/) for a minimal starter project.

The key implementation steps:

1. Create an Android app with a `CommandGatewayService`
2. Declare your tools in the manifest or XML resource
3. Implement tool handlers in the service
4. Build and install the APK
5. Call `hub.refresh` to register with Hub

No SDK dependency is required — CapApps communicate with Hub purely via Android Intents.
