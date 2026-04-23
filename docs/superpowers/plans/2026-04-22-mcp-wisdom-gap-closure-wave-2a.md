# MCP Wisdom Gap Closure — Wave 2A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the Android LLM Intentions Hub + CapApp protocol up to the same MCP-creation standards proven in the Mac prototype (Wave 1): canonical `OK`/`WARN`/`FAIL` envelope with actionable hints, rich tool metadata (permissions, destructive, latency, failure_modes, examples), LLM-facing operator guide.

**Architecture:** The envelope lives in `mcp-core` (pure JVM, testable with JUnit). `ToolAppService` (in `mcp-intent-api`) automatically wraps every CapApp response in an envelope — framework-emitted errors (tool-not-found, timeout, exception) become canonical `FAIL` envelopes with hints. Tool authors opt in to richer metadata via a new `richTool` DSL on `ToolRegistry`. Rich fields are Hub-internal (used by the in-app UI, smoke harness, and AGENTS.md generator); Claude Code's `tools/list` continues to see only the MCP-standard `name` / `description` / `inputSchema` triplet to stay strictly protocol-compliant.

**Tech Stack:** Kotlin + kotlinx-serialization-json. JUnit5 for `mcp-core` tests (pattern already established — see `mcp-core/src/test/kotlin/com/androidmcp/core/`). Gradle via the existing wrappers. Android Studio or headless builds via `./gradlew`. The in-app `ToolExecuteSheet` UI (landed in the `wip-landing` refactor) serves as the manual smoke harness per CapApp.

**Scope boundary (Wave 2A vs 2B):**
- **IN (Wave 2A):** Envelope in `mcp-core`, `ToolAppService` wrap, rich metadata types, backfill of `taichi-android` as canary with 8 tools enriched, root `AGENTS.md`.
- **OUT (Wave 2B — follow-up plan):** Backfill of `tool-device`, `tool-notify`, `tool-people`, `tool-files-dev`. Update of `spec/capapp-protocol.md`. Port of envelope to `termux-mcp/server.js` and `hub/hub-proxy.js`. Extraction of shared `:tool-ui-common` Gradle module (the optional WIP followup).

**Pre-flight:**
1. Confirm `~/Desktop/android-mcp-sdk/` is on branch `wip-landing` (just landed in this session's earlier work). Commits `3dea89d`, `d4d7b4a`, `9c9002e` must be present.
2. `git status --short` should show only untracked `docs/superpowers/` (Adam's separate `lightpanda-capapp` work — don't touch).
3. Create the feature branch: `git checkout -b wave-2a/envelope-and-metadata wip-landing`.
4. Verify the build is green before starting: `./gradlew :mcp-core:test :mcp-intent-api:compileDebugKotlin :taichi-android:compileDebugKotlin` — must pass.

---

## File Structure

```
mcp-core/src/main/kotlin/com/androidmcp/core/
├── protocol/
│   ├── McpTypes.kt                           # unchanged public shape (wire-compat)
│   └── Envelope.kt                           # NEW — Envelope + render + wrap helpers
└── registry/
    └── ToolRegistry.kt                       # MODIFIED — add ToolMetadata + richTool DSL

mcp-core/src/test/kotlin/com/androidmcp/core/
├── protocol/
│   └── EnvelopeTest.kt                       # NEW
└── registry/
    └── ToolMetadataTest.kt                   # NEW

mcp-intent-api/src/main/kotlin/com/androidmcp/intent/
└── ToolAppService.kt                         # MODIFIED — envelope wrap on all returns

taichi-android/src/main/kotlin/com/taichi/tools/
└── TaichiToolService.kt                      # MODIFIED — 8 canary tools enriched

AGENTS.md                                     # NEW at repo root — LLM operator guide
```

---

## Task 1: Envelope in mcp-core

Add a canonical envelope type and helpers. Pure JVM code; tests run via `./gradlew :mcp-core:test`.

**Files:**
- Create: `mcp-core/src/main/kotlin/com/androidmcp/core/protocol/Envelope.kt`
- Create: `mcp-core/src/test/kotlin/com/androidmcp/core/protocol/EnvelopeTest.kt`

- [ ] **Step 1.1: Write failing tests**

Create `mcp-core/src/test/kotlin/com/androidmcp/core/protocol/EnvelopeTest.kt`:

```kotlin
package com.androidmcp.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EnvelopeTest {

    @Test fun ok_has_empty_hint() {
        val env = Envelope.ok(summary = "clipboard updated")
        assertEquals(EnvelopeStatus.OK, env.status)
        assertEquals("clipboard updated", env.summary)
        assertEquals("", env.hint)
    }

    @Test fun fail_carries_hint() {
        val env = Envelope.fail(summary = "permission denied", hint = "grant screen recording")
        assertEquals(EnvelopeStatus.FAIL, env.status)
        assertEquals("grant screen recording", env.hint)
    }

    @Test fun warn_carries_hint_and_partial_data() {
        val env = Envelope.warn(summary = "partial", hint = "3 of 5", data = buildJsonObject { put("count", 3) })
        assertEquals(EnvelopeStatus.WARN, env.status)
    }

    @Test fun failureMode_pattern_matches_stderr_and_injects_hint() {
        val manifest = ToolMetadata(
            failureModes = listOf(
                FailureMode(pattern = "not authorized", hint = "Grant POST_NOTIFICATIONS permission.")
            )
        )
        val env = Envelope.fromException(
            toolName = "notify",
            metadata = manifest,
            exception = SecurityException("not authorized: POST_NOTIFICATIONS"),
        )
        assertEquals(EnvelopeStatus.FAIL, env.status)
        assertEquals("Grant POST_NOTIFICATIONS permission.", env.hint)
    }

    @Test fun failureMode_exceptionType_matches() {
        val manifest = ToolMetadata(
            failureModes = listOf(
                FailureMode(exceptionType = "TimeoutException", hint = "Retry with smaller scope.")
            )
        )
        val env = Envelope.fromException(
            toolName = "slow_tool",
            metadata = manifest,
            exception = java.util.concurrent.TimeoutException("timed out after 30s"),
        )
        assertEquals("Retry with smaller scope.", env.hint)
    }

    @Test fun render_ok_starts_with_prefix_and_includes_data() {
        val env = Envelope.ok(summary = "found 3 items", data = buildJsonObject { put("count", 3) })
        val text = env.renderText()
        assertTrue(text.startsWith("OK: found 3 items"))
        assertTrue(text.contains("\"count\": 3") || text.contains("\"count\":3"))
    }

    @Test fun render_fail_includes_hint_line() {
        val env = Envelope.fail(summary = "denied", hint = "grant perm")
        val text = env.renderText()
        assertTrue(text.startsWith("FAIL: denied"))
        assertTrue(text.contains("Hint: grant perm"))
    }

    @Test fun render_ok_omits_raw_block() {
        val env = Envelope.ok(summary = "done", raw = buildJsonObject { put("stdout", "hi") })
        val text = env.renderText()
        assertTrue(!text.contains("Raw:"))
    }

    @Test fun render_fail_includes_raw_block_when_present() {
        val env = Envelope.fail(
            summary = "bad",
            hint = "",
            raw = buildJsonObject { put("stderr", "boom") },
        )
        val text = env.renderText()
        assertTrue(text.contains("Raw:"))
        assertTrue(text.contains("boom"))
    }
}
```

- [ ] **Step 1.2: Run tests — expect compile failure (Envelope not defined)**

```bash
cd ~/Desktop/android-mcp-sdk
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :mcp-core:test
```
Expected: compile error on `Envelope`, `EnvelopeStatus`, `ToolMetadata`, `FailureMode`.

- [ ] **Step 1.3: Create `mcp-core/src/main/kotlin/com/androidmcp/core/protocol/Envelope.kt`**

```kotlin
package com.androidmcp.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Canonical OK/WARN/FAIL envelope for every tool response.
 *
 * Shape:
 *     { status, summary, hint, data, raw }
 *
 * Rendered to MCP `content` text as:
 *     OK: <summary>
 *     [Hint: <hint> if non-empty]
 *
 *     <data JSON if any>
 *     [Raw: <raw JSON> if non-OK]
 *
 * Hint text is the LLM's next action — drawn from a tool's ToolMetadata.failureModes.
 */
@Serializable
enum class EnvelopeStatus { OK, WARN, FAIL }

@Serializable
data class Envelope(
    val status: EnvelopeStatus,
    val summary: String,
    val hint: String = "",
    val data: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    fun renderText(): String {
        val prefix = status.name
        val sb = StringBuilder()
        sb.append("$prefix: $summary")
        if (hint.isNotEmpty()) {
            sb.append("\nHint: $hint")
        }
        if (data.isNotEmpty()) {
            sb.append("\n\n")
            sb.append(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), data))
        }
        if (status != EnvelopeStatus.OK && raw.isNotEmpty()) {
            sb.append("\n\nRaw:\n")
            sb.append(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), raw))
        }
        return sb.toString()
    }

    companion object {
        fun ok(summary: String, data: JsonObject = JsonObject(emptyMap()), raw: JsonObject = JsonObject(emptyMap())) =
            Envelope(EnvelopeStatus.OK, summary, "", data, raw)

        fun warn(summary: String, hint: String = "", data: JsonObject = JsonObject(emptyMap()), raw: JsonObject = JsonObject(emptyMap())) =
            Envelope(EnvelopeStatus.WARN, summary, hint, data, raw)

        fun fail(summary: String, hint: String = "", data: JsonObject = JsonObject(emptyMap()), raw: JsonObject = JsonObject(emptyMap())) =
            Envelope(EnvelopeStatus.FAIL, summary, hint, data, raw)

        /**
         * Convert a thrown exception into a FAIL envelope, consulting the tool's
         * metadata.failureModes for an actionable hint. Stderr-style pattern matching
         * against exception message; type-based matching against simple class name.
         */
        fun fromException(toolName: String, metadata: ToolMetadata?, exception: Throwable): Envelope {
            val message = exception.message ?: exception.javaClass.simpleName
            val hint = metadata?.let { matchFailureMode(it, exception) } ?: ""
            val raw = buildJsonObject {
                put("exception", exception.javaClass.simpleName)
                put("message", message)
            }
            return fail(summary = "$toolName failed: $message", hint = hint, raw = raw)
        }

        private fun matchFailureMode(metadata: ToolMetadata, exception: Throwable): String {
            val message = exception.message ?: ""
            val typeName = exception.javaClass.simpleName
            for (fm in metadata.failureModes) {
                fm.pattern?.let { p ->
                    if (Regex(p).containsMatchIn(message)) return fm.hint
                }
                fm.exceptionType?.let { t ->
                    if (typeName == t || exception.javaClass.canonicalName == t) return fm.hint
                }
            }
            return ""
        }
    }
}
```

Note: `ToolMetadata` and `FailureMode` are referenced here but defined in Task 3 (`ToolRegistry.kt`). Because `ToolRegistry.kt` already imports from this package, and we'll add the new types there, the cross-file reference is fine. Write this file now with forward references; Task 3 closes the loop.

- [ ] **Step 1.4: Add skeleton of `ToolMetadata` + `FailureMode` (temporary stubs until Task 3 fills them in)**

In the same `Envelope.kt` file, append at the end (outside the Envelope class):

```kotlin
// Forward-declared here; full definition plus DSL lives in registry/ToolRegistry.kt.
// Placed here only so Envelope.fromException can reference the types from mcp-core/protocol
// without creating a cyclic package dependency. The companion-object helpers will be added
// in Task 3.
@Serializable
data class ToolMetadata(
    val examples: List<ToolExample> = emptyList(),
    val permissions: List<String> = emptyList(),
    val destructive: Boolean = false,
    val idempotent: Boolean = true,
    val latencyClass: LatencyClass = LatencyClass.FAST,
    val failureModes: List<FailureMode> = emptyList(),
)

@Serializable
data class ToolExample(val args: JsonObject, val intent: String)

@Serializable
data class FailureMode(
    val pattern: String? = null,
    val exceptionType: String? = null,
    val hint: String,
)

@Serializable
enum class LatencyClass { FAST, SLOW, VERY_SLOW }
```

This concentrates the data classes in `protocol/` (good for serialization locality). Task 3 adds the DSL on top in `registry/`.

- [ ] **Step 1.5: Run tests — expect pass**

```bash
./gradlew :mcp-core:test --tests "com.androidmcp.core.protocol.EnvelopeTest"
```
Expected: 9 tests pass.

- [ ] **Step 1.6: Commit**

```bash
git add mcp-core/src/main/kotlin/com/androidmcp/core/protocol/Envelope.kt \
        mcp-core/src/test/kotlin/com/androidmcp/core/protocol/EnvelopeTest.kt
git commit -m "feat(mcp-core): canonical OK/WARN/FAIL envelope with failure-mode hints"
```

---

## Task 2: ToolAppService envelope wrapping

Every response a CapApp sends back to the Hub — whether from a successful handler, a framework-caught exception, or a tool-not-found — gets wrapped in an Envelope rendered as text. This is the single point where wisdom like "always produce a FAIL with hint" is enforced.

The handler API remains backwards-compatible: existing `textTool { args -> "raw string" }` handlers still work — their String result is wrapped in `Envelope.ok()` automatically. New `envelopeTool { args -> Envelope.ok/warn/fail(...) }` lets a handler produce a non-OK envelope without throwing.

**Files:**
- Modify: `mcp-core/src/main/kotlin/com/androidmcp/core/registry/ToolRegistry.kt` — change `textTool` to wrap in envelope; add `envelopeTool`.
- Modify: `mcp-intent-api/src/main/kotlin/com/androidmcp/intent/ToolAppService.kt` — catch-path becomes envelope-producing.
- Create: `mcp-core/src/test/kotlin/com/androidmcp/core/registry/EnvelopeToolTest.kt`

- [ ] **Step 2.1: Write failing tests**

Create `mcp-core/src/test/kotlin/com/androidmcp/core/registry/EnvelopeToolTest.kt`:

```kotlin
package com.androidmcp.core.registry

import com.androidmcp.core.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class EnvelopeToolTest {

    @Test fun textTool_wraps_string_in_ok_envelope() = runBlocking {
        val reg = ToolRegistry()
        reg.textTool("greet", "say hi", jsonSchema {}) { args -> "hello" }
        val def = reg.get("greet")!!
        val result = def.handler(buildJsonObject {})
        assertFalse(result.isError)
        val text = result.content.first().text ?: ""
        assertTrue(text.startsWith("OK: greet succeeded"))
        assertTrue(text.contains("hello"))
    }

    @Test fun envelopeTool_preserves_fail_status() = runBlocking {
        val reg = ToolRegistry()
        reg.envelopeTool("check", "validate", jsonSchema {}, metadata = ToolMetadata()) { args ->
            Envelope.fail(summary = "input invalid", hint = "pass a non-empty string")
        }
        val result = reg.get("check")!!.handler(buildJsonObject {})
        assertTrue(result.isError)
        val text = result.content.first().text ?: ""
        assertTrue(text.startsWith("FAIL: input invalid"))
        assertTrue(text.contains("Hint: pass a non-empty string"))
    }

    @Test fun envelopeTool_metadata_is_retrievable_for_framework_error_handling() {
        val reg = ToolRegistry()
        val md = ToolMetadata(
            destructive = true,
            failureModes = listOf(FailureMode(pattern = "denied", hint = "grant perm")),
        )
        reg.envelopeTool("risky", "edits DB", jsonSchema {}, metadata = md) { Envelope.ok("done") }
        val def = reg.get("risky")!!
        assertEquals(true, def.metadata?.destructive)
        assertEquals(1, def.metadata?.failureModes?.size)
    }
}
```

- [ ] **Step 2.2: Run tests — expect compile failure**

```bash
./gradlew :mcp-core:test --tests "com.androidmcp.core.registry.EnvelopeToolTest"
```
Expected: `envelopeTool` / `def.metadata` unresolved.

- [ ] **Step 2.3: Extend `ToolRegistry.kt` with metadata + `envelopeTool` DSL + rewrite `textTool`**

Modify `mcp-core/src/main/kotlin/com/androidmcp/core/registry/ToolRegistry.kt`. Replace the existing `McpToolDef` data class and the existing `textTool` extension (note: `textTool` currently lives in `ToolAppService.kt` as an extension — MOVE it here so it's callable from any registry, not only Android contexts; then remove from `ToolAppService.kt` in Step 2.5).

Replace the `McpToolDef` definition with:

```kotlin
/**
 * A registered MCP tool with its metadata, rich-tool metadata (optional), and handler.
 */
data class McpToolDef(
    val info: ToolInfo,
    val metadata: ToolMetadata? = null,
    val handler: suspend (JsonObject) -> ToolCallResult,
)
```

Add at the bottom of the file (after `jsonSchema`):

```kotlin
/**
 * Register a tool whose handler returns a raw String. The string is wrapped in an OK envelope
 * and rendered as MCP text content. On uncaught exception, the framework (ToolAppService) will
 * convert to a FAIL envelope using any failure_modes in the tool's metadata.
 */
fun ToolRegistry.textTool(
    name: String,
    description: String,
    params: JsonObject,
    metadata: ToolMetadata? = null,
    handler: suspend (JsonObject) -> String,
) {
    register(McpToolDef(
        info = ToolInfo(name = name, description = description, inputSchema = params),
        metadata = metadata,
        handler = { args ->
            val text = handler(args)
            val env = Envelope.ok(summary = "$name succeeded", data = JsonObject(mapOf("output" to JsonPrimitive(text))))
            ToolCallResult(content = listOf(ContentBlock.text(env.renderText())), isError = false)
        }
    ))
}

/**
 * Register a tool whose handler produces an Envelope directly — for when the tool wants to
 * emit WARN or FAIL itself (e.g., recoverable input validation errors) rather than throwing.
 */
fun ToolRegistry.envelopeTool(
    name: String,
    description: String,
    params: JsonObject,
    metadata: ToolMetadata? = null,
    handler: suspend (JsonObject) -> Envelope,
) {
    register(McpToolDef(
        info = ToolInfo(name = name, description = description, inputSchema = params),
        metadata = metadata,
        handler = { args ->
            val env = handler(args)
            ToolCallResult(
                content = listOf(ContentBlock.text(env.renderText())),
                isError = env.status == EnvelopeStatus.FAIL,
            )
        }
    ))
}
```

- [ ] **Step 2.4: Run mcp-core tests**

```bash
./gradlew :mcp-core:test
```
Expected: all tests pass — new EnvelopeToolTest + EnvelopeTest + pre-existing ToolRegistryTest / JsonSchemaBuilderTest / McpDispatcherTest / JobManagerTest.

- [ ] **Step 2.5: Update `ToolAppService.kt` — remove local `textTool` and upgrade the exception/not-found paths to use Envelope**

Read `mcp-intent-api/src/main/kotlin/com/androidmcp/intent/ToolAppService.kt` first to get current line numbers (they shifted after Task 1 on this branch only if Task 1 touched this file; it didn't). Then:

1. **Delete** the `textTool` extension at the bottom of the file (lines 154-168 in the as-landed version). CapApps will pick up the new `textTool` from `com.androidmcp.core.registry.textTool` via their existing import of the registry package.

2. **Replace the `handleExecute` function body** with an envelope-aware version. The current body (roughly lines 64-97) has three error paths: missing extras, tool-not-found, and caught exception. All three become envelope failures:

```kotlin
    private fun handleExecute(intent: Intent) {
        val toolName = intent.getStringExtra(McpIntentConstants.EXTRA_TOOL_NAME)
        val argsJson = intent.getStringExtra(McpIntentConstants.EXTRA_ARGUMENTS) ?: "{}"
        val callbackId = intent.getStringExtra(McpIntentConstants.EXTRA_CALLBACK_ID)
        val replyTo = intent.getStringExtra(McpIntentConstants.EXTRA_REPLY_TO)

        if (toolName == null || callbackId == null || replyTo == null) {
            Log.w(TAG, "EXECUTE missing required extras (tool=$toolName, callback=$callbackId, replyTo=$replyTo)")
            return
        }

        val toolDef = registry.get(toolName)
        if (toolDef == null) {
            val env = com.androidmcp.core.protocol.Envelope.fail(
                summary = "Tool not found: $toolName",
                hint = "Check tools/list for available names in this CapApp's namespace.",
            )
            sendEnvelope(replyTo, callbackId, env)
            return
        }

        scope.launch {
            val env = try {
                val args = json.parseToJsonElement(argsJson).jsonObject
                val result = toolDef.handler(args)
                // Handler already returned a ToolCallResult whose content is envelope-rendered
                // text (via textTool / envelopeTool). Forward as-is.
                sendResult(replyTo, callbackId, isError = result.isError,
                    data = json.encodeToString(ToolCallResult.serializer(), result))
                return@launch
            } catch (e: Exception) {
                Log.e(TAG, "Tool execution failed: $toolName", e)
                com.androidmcp.core.protocol.Envelope.fromException(
                    toolName = toolName,
                    metadata = toolDef.metadata,
                    exception = e,
                )
            }
            sendEnvelope(replyTo, callbackId, env)
        }
    }

    private fun sendEnvelope(replyTo: String, callbackId: String, env: com.androidmcp.core.protocol.Envelope) {
        val result = com.androidmcp.core.protocol.ToolCallResult(
            content = listOf(com.androidmcp.core.protocol.ContentBlock.text(env.renderText())),
            isError = env.status == com.androidmcp.core.protocol.EnvelopeStatus.FAIL,
        )
        sendResult(
            replyTo = replyTo,
            callbackId = callbackId,
            isError = result.isError,
            data = json.encodeToString(com.androidmcp.core.protocol.ToolCallResult.serializer(), result),
        )
    }
```

The existing `sendResult(replyTo, callbackId, isError, data)` helper stays as-is (it's the low-level broadcast); we've just added `sendEnvelope` on top of it.

- [ ] **Step 2.6: Verify the Kotlin still compiles across CapApps**

```bash
./gradlew :mcp-intent-api:compileDebugKotlin :taichi-android:compileDebugKotlin :tool-device:compileDebugKotlin :tool-notify:compileDebugKotlin :tool-people:compileDebugKotlin :tool-files-dev:compileDebugKotlin :llm-intentions:compileDebugKotlin
```
Expected: all compile clean. The `textTool` extension was just moved packages — CapApps import the whole registry package so their calls continue to resolve. If any CapApp has a narrow `import com.androidmcp.intent.textTool`, update it to `import com.androidmcp.core.registry.textTool`.

If there are narrow imports, grep for them and fix:

```bash
grep -rn "import com.androidmcp.intent.textTool" .
```

Fix each with an Edit replacing `com.androidmcp.intent.textTool` with `com.androidmcp.core.registry.textTool`.

- [ ] **Step 2.7: Run full test suite**

```bash
./gradlew :mcp-core:test
```
Expected: all green.

- [ ] **Step 2.8: Commit**

```bash
git add mcp-core/src/main/kotlin/com/androidmcp/core/registry/ToolRegistry.kt \
        mcp-core/src/test/kotlin/com/androidmcp/core/registry/EnvelopeToolTest.kt \
        mcp-intent-api/src/main/kotlin/com/androidmcp/intent/ToolAppService.kt
# Plus any CapApp files whose imports needed fixing:
git add -u
git commit -m "feat(mcp-intent-api): envelope-wrap every CapApp tool response"
```

---

## Task 3: Rich metadata DSL + in-app UI integration

`ToolMetadata` and friends already exist (from Task 1). This task adds a DSL for building metadata inline and wires the in-app `ToolExecuteSheet` / `ToolAdapter` UI (which landed in the `wip-landing` refactor) to surface rich fields.

**Files:**
- Modify: `mcp-core/src/main/kotlin/com/androidmcp/core/registry/ToolRegistry.kt` — add `toolMetadata { }` DSL.
- Create: `mcp-core/src/test/kotlin/com/androidmcp/core/registry/ToolMetadataDslTest.kt`

- [ ] **Step 3.1: Write failing tests**

Create `mcp-core/src/test/kotlin/com/androidmcp/core/registry/ToolMetadataDslTest.kt`:

```kotlin
package com.androidmcp.core.registry

import com.androidmcp.core.protocol.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolMetadataDslTest {
    @Test fun dsl_builds_fully_populated_metadata() {
        val md = toolMetadata {
            destructive = true
            idempotent = false
            latencyClass = LatencyClass.SLOW
            permission("POST_NOTIFICATIONS")
            permission("VIBRATE")
            failureMode(pattern = "denied", hint = "grant perm")
            failureMode(exceptionType = "TimeoutException", hint = "retry smaller")
            example(intent = "post a simple note") { args ->
                args["title"] = "Hi"
                args["body"] = "test"
            }
        }
        assertTrue(md.destructive)
        assertEquals(false, md.idempotent)
        assertEquals(LatencyClass.SLOW, md.latencyClass)
        assertEquals(listOf("POST_NOTIFICATIONS", "VIBRATE"), md.permissions)
        assertEquals(2, md.failureModes.size)
        assertEquals(1, md.examples.size)
        assertEquals("post a simple note", md.examples[0].intent)
    }

    @Test fun dsl_defaults_match_ToolMetadata_defaults() {
        val md = toolMetadata {}
        assertEquals(ToolMetadata(), md)
    }
}
```

- [ ] **Step 3.2: Run test — expect unresolved `toolMetadata`**

```bash
./gradlew :mcp-core:test --tests "com.androidmcp.core.registry.ToolMetadataDslTest"
```

- [ ] **Step 3.3: Add the DSL to `ToolRegistry.kt`**

Append at the bottom of `mcp-core/src/main/kotlin/com/androidmcp/core/registry/ToolRegistry.kt`:

```kotlin
class ToolMetadataBuilder {
    var destructive: Boolean = false
    var idempotent: Boolean = true
    var latencyClass: LatencyClass = LatencyClass.FAST
    private val permissions = mutableListOf<String>()
    private val failureModes = mutableListOf<FailureMode>()
    private val examples = mutableListOf<ToolExample>()

    fun permission(name: String) { permissions.add(name) }

    fun failureMode(pattern: String? = null, exceptionType: String? = null, hint: String) {
        require(pattern != null || exceptionType != null) { "failureMode needs pattern or exceptionType" }
        failureModes.add(FailureMode(pattern, exceptionType, hint))
    }

    fun example(intent: String, argsBuilder: MutableMap<String, Any>.() -> Unit) {
        val map = mutableMapOf<String, Any>()
        map.argsBuilder()
        val args = buildJsonObject {
            for ((k, v) in map) when (v) {
                is String -> put(k, v)
                is Number -> put(k, v)
                is Boolean -> put(k, v)
                else -> put(k, v.toString())
            }
        }
        examples.add(ToolExample(args = args, intent = intent))
    }

    fun build(): ToolMetadata = ToolMetadata(
        examples = examples,
        permissions = permissions,
        destructive = destructive,
        idempotent = idempotent,
        latencyClass = latencyClass,
        failureModes = failureModes,
    )
}

fun toolMetadata(block: ToolMetadataBuilder.() -> Unit): ToolMetadata =
    ToolMetadataBuilder().apply(block).build()
```

- [ ] **Step 3.4: Run mcp-core tests**

```bash
./gradlew :mcp-core:test
```
Expected: all pass.

- [ ] **Step 3.5: Surface rich metadata in the in-app ToolAdapter**

In `taichi-android/src/main/kotlin/com/taichi/ui/TaichiApp.kt` (or wherever the Taichi in-app tool list is rendered — if this app doesn't use the same ToolAdapter pattern as the 4 CapApps, skip this step; manual smoke via MCP calls is still sufficient), find the list renderer. **No change needed here for Wave 2A** — the `ToolAdapter` in `tool-device` et al. will get metadata badges in Wave 2B when those CapApps are backfilled. Taichi's existing UI is sufficient for canary testing.

- [ ] **Step 3.6: Commit**

```bash
git add mcp-core/src/main/kotlin/com/androidmcp/core/registry/ToolRegistry.kt \
        mcp-core/src/test/kotlin/com/androidmcp/core/registry/ToolMetadataDslTest.kt
git commit -m "feat(mcp-core): toolMetadata DSL for rich tool fields"
```

---

## Task 4: Canary backfill — taichi-android

Enrich 8 high-value Taichi tools with metadata + keep their existing text/String handler style. Each gets `permissions`, `destructive`, `idempotent`, `latencyClass`, at least one `failureMode`, and at least one `example`. Manual smoke via an MCP call + visual check of the response envelope.

**Files:**
- Modify: `taichi-android/src/main/kotlin/com/taichi/tools/TaichiToolService.kt`

**Eight canary tools** (pick the ones with visible LLM-facing failure modes — permissions, network, destructive trading ops):

1. `paper_trade` (already updated in landed commit for shorts) — **destructive** (modifies paper portfolio), `SLOW` latency, failure for unknown symbol.
2. `fetch_ohlcv` — network, `SLOW`, failure modes for HTTP 429 / network timeout.
3. `technical_analysis` — `SLOW`, depends on prior fetch.
4. `entry_gate` — `FAST`, destructive=false, failure for missing inputs.
5. `snapshot_portfolio` — `FAST`, read-only, idempotent=true.
6. `search_token` — network, `SLOW`, failure for empty query.
7. `set_autopilot` — **destructive**, flips the autopilot toggle.
8. `reset_paper` — **destructive** (wipes portfolio), idempotent=true (wiping twice is the same).

- [ ] **Step 4.1: Read the current `TaichiToolService.kt` to find each tool's registration site**

```bash
grep -n 'registry.textTool(' taichi-android/src/main/kotlin/com/taichi/tools/TaichiToolService.kt | head -20
```

Expected: a line per `textTool` call. Use the line numbers to locate each of the 8 targets.

- [ ] **Step 4.2: Enrich one tool as the worked example — `paper_trade`**

At the `paper_trade` registration site (around line 275 in the as-landed file), the current code (post-landing) looks like:

```kotlin
registry.textTool(
    "paper_trade",
    "Execute a paper trade (buy, sell, short, or cover). ...",
    jsonSchema { ... }
) { args ->
    // existing body
}
```

Change the `textTool` signature to include a `metadata` argument:

```kotlin
registry.textTool(
    name = "paper_trade",
    description = "Execute a paper trade (buy, sell, short, or cover). Uses live DEX/CEX pricing. 0.1% fee per trade. Shorts simulate margin with collateral, funding rates, and liquidation.",
    params = jsonSchema {
        string("symbol", "Token symbol (e.g., ETH, BTC)")
        enum("action", "buy (open long), sell (close long), short (open short), or cover (close short)",
            values = listOf("buy", "sell", "short", "cover"))
        number("amount_usd", "USD amount (for buys and shorts)", required = false)
        number("quantity", "Token quantity (for sells/covers, or specific amount)", required = false)
        boolean("close_all", "Close entire position (for sells/covers)", required = false)
        number("leverage", "Leverage for shorts (default: 1.0)", required = false)
    },
    metadata = toolMetadata {
        destructive = true
        idempotent = false
        latencyClass = LatencyClass.SLOW
        failureMode(pattern = "unknown symbol|symbol not found", hint = "Call search_token first to resolve the symbol.")
        failureMode(pattern = "insufficient", hint = "Check snapshot_portfolio for available cash; reduce amount_usd.")
        failureMode(exceptionType = "SocketTimeoutException", hint = "DEX/CEX pricing request timed out; retry in a few seconds.")
        example(intent = "Open a \$100 long on ETH.") { args ->
            args["symbol"] = "ETH"
            args["action"] = "buy"
            args["amount_usd"] = 100.0
        }
        example(intent = "Close all of ETH position.") { args ->
            args["symbol"] = "ETH"
            args["action"] = "sell"
            args["close_all"] = true
        }
    },
) { args ->
    // existing body unchanged
}
```

Note the `action` param promoted from `string` to `enum` — the DSL entry `enum("action", ..., listOf("buy","sell","short","cover"))` already exists in `JsonSchemaBuilder`.

- [ ] **Step 4.3: Build + manual smoke test**

```bash
cd ~/Desktop/android-mcp-sdk
./gradlew :taichi-android:assembleDebug 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL. If a type import fails (`toolMetadata`, `LatencyClass`, `FailureMode`), add `import com.androidmcp.core.registry.toolMetadata`, `import com.androidmcp.core.protocol.LatencyClass`, etc.

Then deploy and verify one round-trip from Claude Code:
- Install: follow the existing flow (`tailscale file cp ...apk pauls-s25:` or ADB).
- Restart the Hub on device.
- From Claude Code: `mcp__mac__calendar` no, wrong. Use whatever namespace taichi sits under. Invoke `paper_trade` with deliberately bad input (e.g., `symbol: "NOTATOKEN"`) and observe the returned text. Expected shape:
  ```
  FAIL: paper_trade failed: unknown symbol: NOTATOKEN
  Hint: Call search_token first to resolve the symbol.

  Raw:
  { "exception": "...", "message": "..." }
  ```

If the failure doesn't actually emit a message containing "unknown symbol", adjust the `pattern` on the `failureMode` to match the actual message wording.

- [ ] **Step 4.4: Repeat for the remaining 7 tools**

Apply the same pattern. For each:
- Promote string params with obvious enums to `enum(...)`.
- Write `metadata = toolMetadata { ... }` capturing destructive/idempotent/latency/failure modes/examples.
- Keep handler body unchanged.

Skeleton metadata per tool:

| Tool | destructive | idempotent | latency | permissions | key failure_modes |
|---|---|---|---|---|---|
| `fetch_ohlcv` | false | true | SLOW | [] | SocketTimeoutException → retry; HTTP 429 pattern → wait + retry |
| `technical_analysis` | false | true | SLOW | [] | pattern `no data` → call fetch_ohlcv first |
| `entry_gate` | false | true | FAST | [] | pattern `missing field` → required inputs listed |
| `snapshot_portfolio` | false | true | FAST | [] | — |
| `search_token` | false | true | SLOW | [] | pattern `rate limit` → wait + retry |
| `set_autopilot` | true | true | FAST | [] | pattern `invalid mode` → enum mentioned |
| `reset_paper` | true | true | FAST | [] | — |

- [ ] **Step 4.5: Compile check**

```bash
./gradlew :taichi-android:compileDebugKotlin
```

- [ ] **Step 4.6: Commit**

```bash
git add taichi-android/src/main/kotlin/com/taichi/tools/TaichiToolService.kt
git commit -m "feat(taichi): rich metadata on 8 canary tools (destructive, failure_modes, examples)"
```

---

## Task 5: AGENTS.md at repo root

A top-level `AGENTS.md` that a Claude/Grok instance connecting to the Hub can read. Mirrors the structure of the Wave 1 `LLM_GUIDE.md` but written for the Android ecosystem: Hub at `127.0.0.1:8379`, CapApp namespaces, destructive-tool ask-first rules, permissions, common sequences.

**Files:**
- Create: `AGENTS.md` at `~/Desktop/android-mcp-sdk/AGENTS.md`

- [ ] **Step 5.1: Write `AGENTS.md`**

Create `/Users/adamsteen/Desktop/android-mcp-sdk/AGENTS.md`:

```markdown
# AGENTS.md — LLM Intentions Android Hub

You are an LLM with access to the LLM Intentions Hub running on an Android device. This guide tells you how to use it well. Read this once before your first tool call.

## Connection

The Hub speaks MCP over streamable-http on `http://127.0.0.1:8379/mcp` (proxied to your machine via Tailscale or USB). Tools are namespaced by CapApp:

| Namespace | CapApp | Capability |
|---|---|---|
| `hub.*` | Hub built-in | Discovery, status, `hub.claude` relay to Claude Code in Termux |
| `taichi.*` | Taichi | Paper crypto trading, on-chain data, portfolio |
| `device.*` | tool-device | Sensors, TTS, vibration, clipboard on Android |
| `notify.*` | tool-notify | Notification read/post/reply, notification listener events |
| `people.*` | tool-people | Contacts, calendar, SMS, call log |
| `files.*` | tool-files-dev | Filesystem read/write on scoped storage |
| `termux.*` | termux-mcp | Termux APIs: battery, camera, location, etc. |

## Return format

Every tool call returns a canonical envelope rendered as text:

```
OK: <one-line summary>

<data JSON>
```

or

```
FAIL: <one-line summary>
Hint: <what to do next>

Raw:
<exception / stderr JSON>
```

**When you see `FAIL:`, read the `Hint:` line before retrying.** Hints map known failure patterns to concrete actions (grant permission, call prerequisite tool first, wait and retry on rate limit, etc.). If a hint is absent, the `Raw:` block is your next best clue.

## Permissions you cannot grant yourself

Android permissions for each CapApp are requested in-app by the user. If a tool returns FAIL with a hint about permissions, tell the user which setting to toggle — do not silently retry.

| Permission | CapApp / Tools |
|---|---|
| `POST_NOTIFICATIONS` | notify.* post |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | notify.* read |
| `READ_CONTACTS`, `WRITE_CONTACTS` | people.contacts_* |
| `READ_CALENDAR`, `WRITE_CALENDAR` | people.calendar_* |
| `READ_SMS`, `SEND_SMS` | people.sms_* (requires default-SMS-app role on modern Android) |
| `MANAGE_EXTERNAL_STORAGE` | files.file_* |
| `ACCESS_FINE_LOCATION` | device.location, termux.location |
| Camera / Microphone | termux.camera_*, termux.microphone_* |

## Destructive tools — confirm before calling

These modify device state, send messages, or write to user-visible databases. Ask the user first unless intent is unambiguous.

- `taichi.paper_trade` (non-dry-run) — mutates the paper portfolio
- `taichi.set_autopilot` — flips trading autopilot on/off
- `taichi.reset_paper` — wipes the paper portfolio
- `notify.post`, `notify.reply` — visible to the user
- `people.sms_send` — actually sends an SMS
- `people.calendar_create_event`, `people.contacts_write` — writes to the user's data
- `files.file_write`, `files.file_delete` — writes/deletes on disk
- `device.tts_speak` — audible
- `device.vibrate` — physical feedback

## Latency classes

- `FAST` — <1s: clipboard ops, sensor reads, enum queries, status reads.
- `SLOW` — 1-10s: network calls, on-chain lookups, OCR, shell invocations.
- `VERY_SLOW` — may exceed default 30s: `hub.claude` (LLM call), multi-page contact scans.

## Common sequences

### "Buy some ETH with $100"
1. `taichi.search_token` with `query="ETH"` to confirm the token.
2. (Confirm with user.)
3. `taichi.paper_trade` with `symbol=ETH action=buy amount_usd=100`.
4. `taichi.snapshot_portfolio` to verify the entry.

### "What's on my calendar?"
1. `people.calendar_read` with `days_ahead=7`.
2. If FAIL with permission hint → ask user to grant.

### "Send a reminder"
1. `notify.post` with a title + body.
2. (Don't retry if FAIL on permission — tell the user.)

### "Relay a question to Claude"
1. `hub.claude` with `message="<question>"`.
2. Expected latency VERY_SLOW.

## Parameter conventions

- String parameters with an `enum` listed in the schema must be one of those values — anything else will FAIL with a hint citing the valid values.
- Timestamps are Unix epoch seconds unless otherwise documented.
- Absolute file paths always (no `~` expansion, no relative paths).

## When a tool isn't enough

The Hub is curated. If you need a raw Android API no CapApp exposes, tell the user — do not try to smuggle arbitrary shell through `termux.exec` or similar. A new CapApp is a separate APK; the user can build one from the template at `capapp-template/`.
```

- [ ] **Step 5.2: Commit**

```bash
git add AGENTS.md
git commit -m "docs: AGENTS.md — LLM operator guide for the Hub"
```

---

## Out of scope — Wave 2B (follow-up plan)

Once Wave 2A proves the pattern on Taichi:

1. Backfill `tool-device`, `tool-notify`, `tool-people`, `tool-files-dev` with rich metadata + envelope (should be mechanical copy-paste of the Taichi pattern).
2. Update `spec/capapp-protocol.md` to match the as-implemented protocol: code-based registration via `ToolRegistrar` (not XML), canonical envelope return shape, `ToolMetadata` fields.
3. Port the envelope to `termux-mcp/server.js` and `hub/hub-proxy.js` so JS-side tools share the same wire contract.
4. (Optional) Extract the duplicated in-app tool UI (`ToolAdapter`, `ToolExecuteSheet`, `ToolCallLog`, `LogDialog`) into a shared `:tool-ui-common` Gradle module. Surface ToolMetadata badges in `ToolAdapter` (destructive = red dot, slow = clock icon, permissions = shield + list).
5. Smoke-harness analogue of Wave 1's `lib/smoke.py` — either a JUnit integration test suite that mocks the Hub or a Gradle task that spins up the Hub emulator-side and calls every non-destructive tool once.

---

## Self-Review

- **Spec coverage:** Every meaningful Wave 2A gap identified in the earlier analysis (envelope, rich metadata, AGENTS.md) has at least one task. Wave 2B items are explicitly out of scope. ✓
- **Placeholder scan:** No TBD / TODO / "handle appropriately" — every step has real code or a concrete command. ✓
- **Type consistency:** `Envelope`, `EnvelopeStatus`, `ToolMetadata`, `FailureMode`, `ToolExample`, `LatencyClass` defined in Task 1 and referenced consistently in Tasks 2-4. `textTool(name, description, params, metadata = null, handler)` and `envelopeTool(...)` signatures match across their uses. ✓
