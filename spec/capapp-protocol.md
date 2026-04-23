# CapApp Protocol Specification

**Version:** 0.2.0
**Date:** 2026-04-23
**Status:** Matches the implementation in `mcp-intent-api/` and `tool-*` modules as of 2026-04-23.

## Abstract

This document specifies how an Android application becomes a **CapApp** — a Capability App that exposes MCP tools to LLMs via the LLM Intentions Hub, using Android Intents as the transport.

## 1. Overview

A CapApp is an Android APK that:

1. Extends `ToolAppService` from `com.androidmcp.intent`.
2. Registers tools programmatically via a `*ToolRegistrar` using the `textTool` / `envelopeTool` DSL from `com.androidmcp.core.registry`.
3. Is discovered automatically by the Hub via an Intent filter + metadata marker.

Tools are registered in code, not in XML. The Hub enumerates CapApps via `PackageManager.queryIntentServices` for `com.androidmcp.tool.LIST_TOOLS` + the `com.androidmcp.TOOL_APP` meta-data marker.

## 2. AndroidManifest requirements

A CapApp's `AndroidManifest.xml` must declare its `ToolAppService` subclass as an exported service with the MCP intent filter and two meta-data entries:

```xml
<service
    android:name=".MyToolService"
    android:exported="true">
    <intent-filter>
        <action android:name="com.androidmcp.tool.EXECUTE" />
        <action android:name="com.androidmcp.tool.LIST_TOOLS" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
    <meta-data android:name="com.androidmcp.TOOL_APP" android:value="true" />
    <meta-data android:name="com.androidmcp.NAMESPACE" android:value="myapp" />
</service>
```

The `NAMESPACE` meta-data is the prefix applied to every tool in the Hub's aggregated registry (`myapp.tool_name`).

## 3. Service implementation

Extend `ToolAppService` and override `onCreateTools(registry: ToolRegistry)`:

```kotlin
class MyToolService : ToolAppService() {
    override fun onCreateTools(registry: ToolRegistry) {
        MyToolRegistrar.register(registry, context = this)
    }
}
```

Tool registration lives in a separate `MyToolRegistrar` (convention — it keeps the service class thin and allows the same tools to be exercised in an in-app UI).

## 4. Tool registration DSL

Use `textTool` for tools that return a text result; use `envelopeTool` for tools that want to emit `WARN` / `FAIL` envelopes directly.

```kotlin
registry.textTool(
    name = "<tool-name>",
    description = "<agent-facing description>",
    params = jsonSchema {
        string("arg1", "description", required = true)
        enum("mode", "description", values = listOf("fast", "slow"), required = true)
        number("count", "description", required = false)
        boolean("dry_run", "description", required = false)
    },
    metadata = toolMetadata {
        destructive = false
        idempotent = true
        latencyClass = LatencyClass.FAST
        permission("ANDROID_PERMISSION_NAME")
        failureMode(pattern = "regex against exception message", hint = "what to do")
        failureMode(exceptionType = "FileNotFoundException", hint = "...")
        example(intent = "one-line user intent") { args ->
            args["arg1"] = "value"
            args["mode"] = "fast"
        }
    },
) { args ->
    // Return a String. Throw on hard errors — do NOT catch and return error strings.
    doWork(args)
}
```

## 5. Tool invocation flow

1. MCP client calls `tools/call { name: "myapp.tool_name", arguments: {...} }` on the Hub.
2. Hub strips the namespace prefix, locates the CapApp, and sends a `com.androidmcp.tool.EXECUTE` Intent to the CapApp's `ToolAppService` with extras `tool_name`, `arguments` (JSON string), `callback_id`, `reply_to`.
3. `ToolAppService.handleExecute` dispatches to the registered handler.
4. If the handler returns a String (via `textTool`), it's wrapped in `Envelope.ok(summary = "<name> succeeded", data = {"output": <string>}).renderText()`.
5. If the handler returns an `Envelope` (via `envelopeTool`), that envelope is rendered directly.
6. If the handler throws, `ToolAppService` catches and produces `Envelope.fromException(toolName, metadata, exception)`, which consults `metadata.failureModes` for an actionable hint.
7. The result is sent back to the Hub via broadcast Intent `com.androidmcp.tool.RESULT` with the rendered envelope text inside a `ToolCallResult { content: [ContentBlock.text(<envelope>)], isError: <bool> }`.

## 6. Response envelope

Every response a CapApp sends to the Hub — success, tool-not-found, or caught exception — is a single text block in the canonical envelope format:

```
OK: <tool-name> succeeded

{
  "output": "<handler's string return value>"
}
```

or

```
FAIL: <tool-name> failed: <exception message>
Hint: <actionable hint from metadata.failureModes, or empty>

Raw:
{
  "exception": "<simple class name>",
  "message": "<exception message>"
}
```

`WARN:` envelopes are produced only by tools using `envelopeTool` that explicitly emit `Envelope.warn(...)`.

## 7. Lifecycle

### 7.1 Installation & discovery

When a new CapApp APK is installed, the Hub does NOT auto-refresh. The user (or an agent) must call `hub.refresh` to trigger re-discovery. On Android 12+, the OS may also require the CapApp's launcher Activity to have been started at least once before the Hub's `PackageManager.queryIntentServices` sees its services (confirmed behavior on Pixel_8 / API 35 emulator).

### 7.2 Updates

Reinstalling a CapApp APK takes effect immediately for future tool invocations. Call `hub.refresh` if the tool list or metadata changed. Note: Android background-start restrictions may prevent the Hub from `startService`-ing into a freshly-installed CapApp until that CapApp's process has been launched at least once (again, a launcher Activity tap or `am start` suffices).

### 7.3 Removal

When a CapApp is uninstalled, the Hub's cached registry still references its tools until `hub.refresh` is called — tool invocations will time out or produce `FAIL` envelopes with transport errors.

## 8. Permissions

CapApps request their own Android permissions independently via their `AndroidManifest.xml`. The Hub does not proxy or escalate permissions.

Tool `metadata.permissions` is documentation-only — a list of permission strings the CapApp needs for this tool to succeed. Agents read this via `hub.status` or by inspecting rendered failure hints.

## 9. Author convention: throw, don't catch

**Rule:** A CapApp handler should let exceptions propagate for hard failures. Catching and returning an error string like `"Error: file not found"` causes the framework to wrap the string as `OK: <tool> succeeded`, which:

- Hides the failure from `isError` in `ToolCallResult`.
- Skips the `metadata.failureModes` matching that would otherwise inject an actionable hint.
- Masks retriable error categories (transient network, permission-denied, rate-limit) as successes.

When you genuinely need `WARN` (partial success) or `FAIL` without an exception to throw (e.g., input validation before any operation ran), use `envelopeTool` and return `Envelope.warn(...)` / `Envelope.fail(...)` directly. Worked example: `llm-intentions/.../HubMetaTools.kt::registerClaude`.

## 10. In-app tool UI (optional)

The base pattern (implemented in `tool-device`, `tool-notify`, `tool-people`, `tool-files-dev`) gives each CapApp a `ToolAdapter` / `ToolExecuteSheet` / `ToolCallLog` / `LogDialog` so users can manually inspect and invoke tools from the app itself, alongside LLM-driven invocations. The call log records both LLM-originated and UI-originated calls with result preview + error flag. This is not required by the protocol — it's a CapApp-author convenience.

See `capapp-template/` for a minimal starter.
