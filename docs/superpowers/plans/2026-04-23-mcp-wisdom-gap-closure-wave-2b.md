# MCP Wisdom Gap Closure — Wave 2B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close two gaps that Wave 2A left open, surfaced by the emulator round-trip smoke test:
1. **Hub's own meta-tools (`hub.status`, `hub.health`, `hub.refresh`, `hub.claude`) bypass the envelope.** They register directly on `ToolRegistry` with hand-rolled `ToolCallResult` handlers, so their responses come back as raw text without the canonical `OK:` / `FAIL:` prefix. Migrating them to the `textTool` / `envelopeTool` DSL brings them into the canonical wire contract.
2. **A shipped-to-agent convention that CapApp handlers should THROW on hard errors rather than catch-and-return-error-strings.** The Wave 2A smoke showed `device.sensor_read { sensor: "not_a_real_sensor" }` returning `OK: sensor_read succeeded\n"Unknown sensor: ..."` because the handler caught internally. That masks real failures as success and prevents `failure_modes` from ever matching. The fix isn't a framework change — it's a CapApp-author convention, documented.

Plus one substantive backfill:
3. **Rich metadata on `tool-files-dev`** — proven to work end-to-end in the Wave 2A smoke (`fs.fs_tree → OK: fs_tree succeeded`). Now enrich the destructive / permission-heavy tools with `toolMetadata { ... }` so FAIL paths carry actionable hints.

Deferred to **Wave 2C** (Node-side): `termux-mcp/server.js` and `hub/hub-proxy.js` envelope port.
Deferred to **Wave 2D** (follow-up per-CapApp passes): rich metadata on `tool-device`, `tool-notify`, `tool-people` — same pattern as Taichi + tool-files-dev, mechanical.

**Architecture:** All work is within `~/Desktop/android-mcp-sdk/`. The DSLs (`textTool`, `envelopeTool`, `toolMetadata`) and the envelope type landed in Wave 2A — this wave just applies them. No new types needed.

**Tech Stack:** Kotlin + Gradle. Existing JUnit5 pattern in `mcp-core/src/test/kotlin/`. Smoke via Android emulator (Pixel_8 AVD) + `adb forward tcp:8379 tcp:8379` + curl, same setup as Wave 2A smoke.

**Pre-flight:**
1. Confirm `~/Desktop/android-mcp-sdk/` is on branch `wave-2a/envelope-and-metadata` with the 4 committed Wave 2A commits (`6c60ea2`, `10a77c0`, `2a1bb92`, `ca6b426`). Unstaged Taichi changes in `taichi-android/src/main/kotlin/com/taichi/tools/TaichiToolService.kt` are Adam's in-progress — **do not touch them**.
2. Create the feature branch: `git checkout -b wave-2b/hub-meta-and-fs wave-2a/envelope-and-metadata`.
3. The unstaged Taichi changes will travel with the branch. Leave them alone; commits in this plan only touch Hub + tool-files-dev + docs.
4. Baseline: `./gradlew :mcp-core:test :llm-intentions:compileDebugKotlin :tool-files-dev:compileDebugKotlin` — must pass. (Two pre-existing test failures in mcp-core — `McpDispatcherTest.initialize` and `JobManagerTest.failed-job-reports-error` — are NOT regressions; ignore.)

---

## File Structure

```
llm-intentions/src/main/kotlin/com/androidmcp/hub/meta/
└── HubMetaTools.kt                     # MODIFIED — migrate all 4 register* methods to textTool / envelopeTool

tool-files-dev/src/main/kotlin/com/llmintentions/files/dev/
└── FilesDevToolRegistrar.kt            # MODIFIED — add metadata on 6 high-value tools

spec/
└── capapp-protocol.md                   # REWRITTEN — match as-implemented protocol

AGENTS.md                                # MODIFIED — append "CapApp handler convention: throw, don't catch-and-stringify"
```

---

## Task 1: Migrate HubMetaTools to envelope-wrapping DSL

The four Hub meta-tools — `hub.status`, `hub.health`, `hub.refresh`, `hub.claude` — register directly via `registry.register(McpToolDef(info=..., handler=<custom>))` that constructs `ToolCallResult(content = listOf(ContentBlock.text(...)))` by hand. They need to go through `textTool` (if the handler returns a clean String) or `envelopeTool` (if the handler wants to emit FAIL/WARN explicitly based on its own logic — e.g., `hub.claude` relay failures).

**Files:**
- Modify: `llm-intentions/src/main/kotlin/com/androidmcp/hub/meta/HubMetaTools.kt`

- [ ] **Step 1.1: Read the full `HubMetaTools.kt` file**

Location: `llm-intentions/src/main/kotlin/com/androidmcp/hub/meta/HubMetaTools.kt` (226 lines). Understand each of the 4 `registerX` methods:
- `registerStatus` (line 39) — returns a status string; simple → `textTool`.
- `registerHealth` (line 69) — returns a health-check report; simple → `textTool`.
- `registerClaude` (line 108) — relays a message to Termux + returns the response; has its own error path on relay failure → `envelopeTool` so it can emit a specific `FAIL` with `hint: "Termux Claude CLI is not running — start it with: ..."`.
- `registerRefresh` (line 199) — triggers re-discovery; returns result summary → `textTool`.

- [ ] **Step 1.2: Migrate `registerStatus` to `textTool`**

The current body looks like:
```kotlin
private fun registerStatus(registry: ToolRegistry) {
    registry.register(McpToolDef(
        info = ToolInfo(name = "hub.status", description = "...", inputSchema = jsonSchema { ... }),
        handler = { args ->
            val statusText = buildStatusReport(...)  // whatever the current handler does
            ToolCallResult(content = listOf(ContentBlock.text(statusText)))
        }
    ))
}
```

Change to:
```kotlin
private fun registerStatus(registry: ToolRegistry) {
    registry.textTool(
        name = "hub.status",
        description = "<keep existing description>",
        params = jsonSchema { /* keep existing schema */ },
        metadata = toolMetadata {
            destructive = false
            idempotent = true
            latencyClass = LatencyClass.FAST
        },
    ) { args ->
        buildStatusReport(...)  // same body, returning String directly
    }
}
```

Copy the inner logic of the old handler but strip the `ToolCallResult(...)` wrapping — return just the String. The `textTool` DSL will wrap in `Envelope.ok(summary = "hub.status succeeded", data = { output: <string> })` automatically.

Add imports at the top of the file if not already present:
```kotlin
import com.androidmcp.core.protocol.LatencyClass
import com.androidmcp.core.protocol.Envelope
import com.androidmcp.core.registry.textTool
import com.androidmcp.core.registry.envelopeTool
import com.androidmcp.core.registry.toolMetadata
```

- [ ] **Step 1.3: Migrate `registerHealth` to `textTool`**

Same pattern as Step 1.2. Likely no metadata needed beyond `latencyClass = LatencyClass.FAST`.

- [ ] **Step 1.4: Migrate `registerRefresh` to `textTool`**

```kotlin
private fun registerRefresh(registry: ToolRegistry) {
    registry.textTool(
        name = "hub.refresh",
        description = "<existing description>",
        params = jsonSchema { /* existing */ },
        metadata = toolMetadata {
            destructive = false
            idempotent = true
            latencyClass = LatencyClass.SLOW   // discovery iterates installed packages
        },
    ) { _ ->
        // existing body — run discovery, return the summary String
    }
}
```

- [ ] **Step 1.5: Migrate `registerClaude` to `envelopeTool`**

`hub.claude` is the interesting one — it relays to Termux and can fail distinctly based on the relay response. Use `envelopeTool` so the handler can return `Envelope.ok/fail` directly.

Target shape:
```kotlin
private fun registerClaude(registry: ToolRegistry) {
    registry.envelopeTool(
        name = "hub.claude",
        description = "<existing description>",
        params = jsonSchema { /* existing: string message */ },
        metadata = toolMetadata {
            destructive = false
            idempotent = false   // Claude responses are non-deterministic
            latencyClass = LatencyClass.VERY_SLOW
            failureMode(
                pattern = "ECONNREFUSED|Connection refused|unreachable",
                hint = "Termux relay is not running. Start it in Termux with: node ~/termux-mcp/server.js"
            )
            failureMode(
                exceptionType = "SocketTimeoutException",
                hint = "Claude CLI took longer than 30s — try a shorter prompt or check Termux logs."
            )
            example(intent = "Ask Claude a one-shot question via your own subscription.") { args ->
                args["message"] = "What is the capital of France?"
            }
        },
    ) { args ->
        val message = args["message"]?.jsonPrimitive?.content ?: return@envelopeTool Envelope.fail(
            summary = "hub.claude requires a 'message' argument.",
            hint = "Pass {\"message\": \"<your question>\"}.",
        )

        val relayResult = callTermuxRelay(message, args)
        if (relayResult.ok) {
            Envelope.ok(
                summary = "hub.claude relayed in ${relayResult.elapsedMs}ms",
                data = buildJsonObject { put("response", relayResult.text) },
            )
        } else {
            Envelope.fail(
                summary = "Termux relay error: ${relayResult.error}",
                hint = "Check Termux server on port 8378, or review its log.",
                raw = buildJsonObject { put("httpStatus", relayResult.httpStatus) },
            )
        }
    }
}
```

Adapt to the actual names in the current file — the key is that `registerClaude` now returns `Envelope` directly and the `envelopeTool` DSL renders it.

- [ ] **Step 1.6: Compile + mcp-core tests (ensure nothing regressed in mcp-core)**

```bash
cd ~/Desktop/android-mcp-sdk
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :llm-intentions:compileDebugKotlin :mcp-core:test 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL + same 2 pre-existing failures, nothing else regressed.

- [ ] **Step 1.7: Emulator smoke — verify `hub.refresh` now envelope-wraps**

If the Pixel_8 emulator from Wave 2A is still running and the Hub APK has been reinstalled:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/Users/adamsteen/Library/Android/sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"

./gradlew :llm-intentions:assembleDebug 2>&1 | tail -3
adb install -r llm-intentions/build/outputs/apk/debug/llm-intentions-debug.apk
adb shell am force-stop com.llmintentions
adb shell am start -n com.llmintentions/com.androidmcp.hub.SplashActivity
sleep 3

curl -s -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":100,"method":"tools/call","params":{"name":"hub.refresh","arguments":{}}}' \
  http://127.0.0.1:8379/mcp | sed 's/data: //' | grep -o '"text":"[^"]*"' | head -1
```
Expected: the `text` field starts with `OK: hub.refresh succeeded` followed by the old refresh summary in the `output` data block.

If the emulator isn't running, skip the smoke and verify via compile + commit — the unit-test coverage in `EnvelopeToolTest` already proves the rendering.

- [ ] **Step 1.8: Commit**

```bash
git add llm-intentions/src/main/kotlin/com/androidmcp/hub/meta/HubMetaTools.kt
git commit -m "refactor(hub): migrate meta-tools to textTool / envelopeTool DSL"
```

---

## Task 2: Rich metadata on tool-files-dev

Enrich the 6 highest-value `fs.*` tools with `metadata = toolMetadata { ... }`. Keep each handler body unchanged.

**File:**
- Modify: `tool-files-dev/src/main/kotlin/com/llmintentions/files/dev/FilesDevToolRegistrar.kt`

**Six target tools** (pick these specific registration sites; if any is named slightly differently, map and report):

| Tool | destructive | idempotent | latency | permissions | key failure_modes |
|---|---|---|---|---|---|
| `fs_read` / `file_read` | false | true | FAST-SLOW | MANAGE_EXTERNAL_STORAGE / READ_MEDIA_* | `FileNotFoundException` → "Check the path exists and is absolute"; `SecurityException` → "Grant MANAGE_EXTERNAL_STORAGE" |
| `fs_write` / `file_write` | **true** | true | FAST | MANAGE_EXTERNAL_STORAGE | `SecurityException` → "Grant all-files access"; `IOException` with "No space" → "Device storage is full" |
| `fs_delete` / `file_delete` | **true** | true | FAST | MANAGE_EXTERNAL_STORAGE | `SecurityException` → same as write; `FileNotFoundException` → "already deleted — no-op" |
| `fs_tree` | false | true | SLOW | optional | pattern "Permission denied" → "Enable 'All files access' for LLM File Tools" |
| `fs_find` / `fs_grep` | false | true | SLOW | optional | pattern "Permission denied" → same as tree |
| `download_file` | **true** (writes to disk) | false | VERY_SLOW | INTERNET + storage | `UnknownHostException` → "Check internet connection"; `SocketTimeoutException` → "Server too slow; retry" |

### Worked example — `fs_write`:

```kotlin
registry.textTool(
    name = "fs_write",
    description = "<keep existing>",
    params = jsonSchema { /* existing */ },
    metadata = toolMetadata {
        destructive = true
        idempotent = true
        latencyClass = LatencyClass.FAST
        permission("MANAGE_EXTERNAL_STORAGE")
        failureMode(
            pattern = "Permission denied|operation not permitted",
            hint = "Grant 'All files access' to LLM File Tools (Dev) in System Settings > Apps > LLM File Tools (Dev) > Permissions > All files access.",
        )
        failureMode(
            pattern = "No space left|ENOSPC",
            hint = "Device storage is full — free space and retry.",
        )
        failureMode(
            exceptionType = "FileNotFoundException",
            hint = "Parent directory doesn't exist. Use an absolute path whose parent already exists.",
        )
        example(intent = "Write a text note to the device Downloads folder.") { args ->
            args["path"] = "/sdcard/Download/note.txt"
            args["content"] = "hello"
        }
    },
) { args ->
    // existing body — unchanged
}
```

- [ ] **Step 2.1: Grep for the 6 tool names**

```bash
cd ~/Desktop/android-mcp-sdk
for t in fs_read file_read fs_write file_write fs_delete file_delete fs_tree fs_find fs_grep download_file; do
  echo "--- $t ---"
  grep -n "\"$t\"" tool-files-dev/src/main/kotlin/com/llmintentions/files/dev/FilesDevToolRegistrar.kt | head -2
done
```

For any target with no hit, pick the nearest match (e.g., `fs_read` vs `file_read`) and enrich that. Report substitutions.

- [ ] **Step 2.2: Add imports to `FilesDevToolRegistrar.kt`**

At the top, add (if not already present):
```kotlin
import com.androidmcp.core.protocol.LatencyClass
import com.androidmcp.core.registry.toolMetadata
```

- [ ] **Step 2.3: Enrich each target with `metadata = toolMetadata { ... }`**

Use the table + worked example as a guide. Handler bodies stay identical. Convert positional args to named args for the diff's sake.

- [ ] **Step 2.4: Compile**

```bash
./gradlew :tool-files-dev:compileDebugKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2.5: Emulator smoke — verify envelope + metadata work together**

```bash
./gradlew :tool-files-dev:assembleDebug 2>&1 | tail -3
adb install -r tool-files-dev/build/outputs/apk/debug/tool-files-dev-debug.apk
# Warm up discovery if needed:
adb shell am start -n com.llmintentions.files.dev/.FilesDevActivity
sleep 2
# Happy path:
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":201,"method":"tools/call","params":{"name":"fs.fs_tree","arguments":{"path":"/sdcard/Download"}}}' \
  http://127.0.0.1:8379/mcp | sed 's/data: //' | grep -o '"text":"[^"]*"' | head -1
# Forced FAIL path — try to read a path that doesn't exist:
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":202,"method":"tools/call","params":{"name":"fs.fs_read","arguments":{"path":"/does/not/exist.txt"}}}' \
  http://127.0.0.1:8379/mcp | sed 's/data: //' | grep -o '"text":"[^"]*"' | head -1
```
Expected: first call `OK: ...`, second call `FAIL: ... Hint: <actionable>` PROVIDED the CapApp handler actually throws on missing files. If the handler currently catches and returns an error string, the second call will come back as `OK` with the error inside — that surfaces the convention gap addressed in Task 3.

Report both responses in the commit/task summary.

- [ ] **Step 2.6: Commit**

```bash
git add tool-files-dev/src/main/kotlin/com/llmintentions/files/dev/FilesDevToolRegistrar.kt
git commit -m "feat(files-dev): rich metadata on 6 core fs tools (destructive, failure_modes, examples)"
```

---

## Task 3: Error-handling convention + spec + AGENTS.md update

The smoke test surfaced that many CapApp handlers catch errors internally and return error strings (`"Unknown sensor: not_a_real_sensor"`) instead of throwing. That prevents `Envelope.fromException` from consulting `failure_modes`, so every failure comes back as `OK:`. This is a CapApp-author convention, not a framework fix — document it.

Also, `spec/capapp-protocol.md` is out of date: it describes XML-manifest + `CommandGatewayService` + a custom `{request_id, success, content}` response shape, while the actual code uses `ToolRegistrar` + `ToolAppService` + `ToolCallResult` + envelope text. Rewrite.

**Files:**
- Modify: `AGENTS.md`
- Rewrite: `spec/capapp-protocol.md`

### 3A) AGENTS.md append — CapApp author section

- [ ] **Step 3A.1: Append a new section to `AGENTS.md`**

At the end of `AGENTS.md`, append:

```markdown

---

## For CapApp authors

If you're writing a CapApp (a new tool provider), the framework takes care of envelope rendering — but only if you let it see your failures. Two rules:

### 1. Throw on hard errors. Don't catch-and-return-error-string.

**Wrong** (silent success):
```kotlin
registry.textTool("my_tool", "...", params) { args ->
    try {
        doWork(args)
    } catch (e: Exception) {
        "Error: ${e.message}"   // framework will wrap this as OK: my_tool succeeded
    }
}
```

**Right** (framework sees the exception and produces `FAIL: my_tool failed: <message>` + hint from your metadata.failure_modes):
```kotlin
registry.textTool("my_tool", "...", params, metadata = toolMetadata {
    failureMode(pattern = "sensor unavailable", hint = "This sensor isn't on this device — check device.sensor_list first.")
}) { args ->
    doWork(args)   // let exceptions propagate
}
```

If you genuinely want to produce a `WARN` (partial success) or a specific `FAIL` without throwing, use `envelopeTool` instead of `textTool` and return `Envelope.warn(...)` / `Envelope.fail(...)` directly.

### 2. Ship metadata with every tool that can fail.

`toolMetadata { failureMode(pattern = "...", hint = "...") }` turns an opaque FAIL into a self-healing hint for the LLM. Patterns are regex-matched against the thrown exception's message; `exceptionType` matches the simple class name. See `tool-files-dev/FilesDevToolRegistrar.kt` for a worked pattern.
```

- [ ] **Step 3A.2: Commit the AGENTS.md append**

```bash
git add AGENTS.md
git commit -m "docs(AGENTS): CapApp author convention — throw on hard errors + ship metadata"
```

### 3B) Rewrite spec/capapp-protocol.md

- [ ] **Step 3B.1: Read the current `spec/capapp-protocol.md`**

It's ~200 lines. Skim to understand what's there — XML manifest, CommandGatewayService, custom response JSON.

- [ ] **Step 3B.2: Replace the file entirely**

Write to `/Users/adamsteen/Desktop/android-mcp-sdk/spec/capapp-protocol.md`:

```markdown
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

Use `textTool` for tools that return a text result, `envelopeTool` for tools that want to emit `WARN` / `FAIL` envelopes directly.

```kotlin
registry.textTool(
    name = "<tool-name>",
    description = "<agent-facing description>",
    params = jsonSchema {
        string("arg1", "description", required = true)
        enum("mode", "description", values = listOf("fast", "slow"), required = true)
        // + number, integer, boolean, etc.
    },
    metadata = toolMetadata {
        destructive = false
        idempotent = true
        latencyClass = LatencyClass.FAST
        permission("ANDROID_PERMISSION_NAME")
        failureMode(pattern = "regex", hint = "what to do")
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
4. If the handler returns a String (via `textTool`), it's wrapped in `Envelope.ok(summary="<name> succeeded", data={"output": <string>}).renderText()`.
5. If the handler returns an `Envelope` (via `envelopeTool`), that envelope is rendered directly.
6. If the handler throws, `ToolAppService` catches and produces `Envelope.fromException(toolName, metadata, exception)` which consults `metadata.failureModes` for an actionable hint.
7. The result is sent back to the Hub via broadcast Intent `com.androidmcp.tool.RESULT` with the rendered envelope text as a `ToolCallResult { content: [ContentBlock.text(<envelope>)], isError: <bool> }`.

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

When a new CapApp APK is installed, the Hub does NOT auto-refresh. The user (or an agent) must call `hub.refresh` to trigger re-discovery. On Android 12+, the OS may also require the CapApp's launcher Activity to have been started at least once before the Hub's `PackageManager.queryIntentServices` sees its services.

### 7.2 Updates

Reinstalling a CapApp APK takes effect immediately for future tool invocations. Call `hub.refresh` if the tool list or metadata changed.

### 7.3 Removal

When a CapApp is uninstalled, the Hub's cached registry still references its tools until `hub.refresh` is called — tool invocations will time out or produce `FAIL` envelopes with transport errors.

## 8. Permissions

CapApps request their own Android permissions independently via their `AndroidManifest.xml`. The Hub does not proxy or escalate permissions.

Tool `metadata.permissions` is documentation-only — a list of permission strings the CapApp needs for this tool to succeed. Agents read this via `hub.status` or by inspecting rendered failure hints.

## 9. In-app tool UI (optional)

The base pattern (implemented in `tool-device`, `tool-notify`, `tool-people`, `tool-files-dev`) gives each CapApp a `ToolAdapter` / `ToolExecuteSheet` / `ToolCallLog` / `LogDialog` so users can manually inspect and invoke tools from the app itself, alongside LLM-driven invocations. The call log records both LLM-originated and UI-originated calls with result preview + error flag. This is not required by the protocol — it's a CapApp-author convenience.

See `capapp-template/` for a minimal starter.
```

- [ ] **Step 3B.3: Commit the spec rewrite**

```bash
git add spec/capapp-protocol.md
git commit -m "docs(spec): rewrite capapp-protocol.md to match as-implemented code"
```

---

## Self-Review

- **Spec coverage:** Every gap Wave 2A left open has a task. HubMetaTools (Task 1), fs rich metadata (Task 2), author convention + spec doc (Task 3). Wave 2C/2D items called out as deferred. ✓
- **Placeholder scan:** Task 1 and Task 2 don't include full file listings because each targets specific registrations in existing 200+ line files — the worked examples + grep pointers are concrete enough. Task 3B has the full spec content inline. ✓
- **Type consistency:** `textTool(name, description, params, metadata = null, handler)` and `envelopeTool(...)` signatures from Wave 2A used uniformly. `toolMetadata { destructive; idempotent; latencyClass; permission(); failureMode(pattern? | exceptionType?, hint); example(intent) { args -> ... } }` DSL used uniformly. `LatencyClass { FAST, SLOW, VERY_SLOW }`. ✓
