# MCP Wisdom Gap Closure — Wave 2C + 2D Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining MCP-wisdom gaps after Wave 2A/2B:
- **Wave 2C (Node side):** Port the envelope to `termux-mcp/server.js` so every Termux tool response matches the `OK: <name> succeeded` / `FAIL: <name> failed: <msg> / Hint: ...` shape the Kotlin side now produces. Verify `hub/hub-proxy.js` preserves envelope text end-to-end.
- **Wave 2D (Kotlin CapApp backfill):** Apply the same rich-metadata pattern Taichi and tool-files-dev received to the remaining three CapApps (`tool-device`, `tool-notify`, `tool-people`). Simultaneously flip any handler that catches hard errors and returns a string — letting them throw so `Envelope.fromException` can consult `failureModes` for actionable hints.

**Architecture:** No new types. Wave 2A's `Envelope` + `toolMetadata { }` DSL + Wave 2B's `HubMetaTools` migration establish all the patterns. This wave is pure application.

For the Node side, we introduce a small `envelope.js` helper module that mirrors the Kotlin `Envelope.ok/fail/fromException` + `textTool` wrapper surface, so the wire contract is language-independent.

**Tech Stack:** Kotlin + Gradle (Wave 2D). Node.js 20+ with built-in `node:test` runner (Wave 2C). No new dependencies.

**Scope boundary:**
- **IN (this plan):** envelope helper in termux-mcp, pass-through verification of hub-proxy, rich-metadata backfills of 4-6 tools each on tool-device/tool-notify/tool-people, handler-throw flips where a permission / hard-failure is currently being stringified.
- **OUT:** Full migration of every termux-mcp tool (we enrich 6 canaries; the rest is follow-up Wave 2E). Wave 2D does NOT cover every tool in each CapApp — only the high-value ones. Hub-proxy code changes only happen IF the verification smoke shows envelope text is being mangled.

**Pre-flight:**
1. Confirm current branch is `wave-2b/hub-meta-and-fs` with the 4 Wave 2B commits present (`008720f`, `f971dd1`, `f601a8f`, `f95b843`).
2. Adam's unstaged Taichi WIP is still in `taichi-android/.../TaichiToolService.kt` — **do not touch**.
3. Create the feature branch: `git checkout -b wave-2cd/node-and-capapps wave-2b/hub-meta-and-fs`.
4. Baseline: `./gradlew :mcp-core:test :tool-device:compileDebugKotlin :tool-notify:compileDebugKotlin :tool-people:compileDebugKotlin` — must pass. (Two pre-existing failing tests in mcp-core: ignore.)
5. Baseline node: `cd termux-mcp && node --version` — must be ≥ 20 for built-in `node:test`. If < 20, use the workspace's `package.json` to add a devDependency on an assert helper and the plan will still work with `node --test` in Node 20+.

---

## File Structure

```
termux-mcp/
├── server.js                              # MODIFIED — imports envelope helper, 6 canary tools use it
├── envelope.js                            # NEW — Envelope + textTool wrapper in JS
└── test/
    └── envelope.test.js                   # NEW — unit tests via node --test

hub/
└── hub-proxy.js                           # READ-ONLY (no changes unless smoke fails)

tool-device/src/main/kotlin/com/llmintentions/device/
└── DeviceToolRegistrar.kt                 # MODIFIED — rich metadata on 5 tools + handler-throw flips

tool-notify/src/main/kotlin/com/llmintentions/notify/
└── NotifyToolRegistrar.kt                 # MODIFIED — rich metadata on 5 tools + handler-throw flips

tool-people/src/main/kotlin/com/llmintentions/people/
└── PeopleToolRegistrar.kt                 # MODIFIED — rich metadata on 6 tools + handler-throw flips
```

---

## Task 1: termux-mcp envelope helper + migrate 6 canary tools

Create a JS module mirroring the Kotlin `Envelope` + `textTool` surface. Use it for 6 high-value tools — destructive / permission-heavy / known-to-fail ones. The other 18 stay on the old shape for now (Wave 2E will sweep).

**Files:**
- Create: `termux-mcp/envelope.js`
- Create: `termux-mcp/test/envelope.test.js`
- Modify: `termux-mcp/server.js` — import helpers, migrate 6 tools.

**Six canary tools** (destructive / permission-heavy / visible failure modes):
1. `sms_send` — destructive; needs SEND_SMS + default-SMS-app role; fails with permission error.
2. `notification_send` — needs POST_NOTIFICATIONS; fails silently on denied.
3. `location` — needs ACCESS_FINE_LOCATION; provider timeouts common.
4. `camera_photo` — needs CAMERA; fails if lens unavailable (emulator).
5. `tts_speak` — destructive (audible); needs TTS engine available.
6. `clipboard_set` — destructive; can silently fail on secure-keyboard overlay.

### 1) `termux-mcp/envelope.js` — write exactly

```javascript
// Canonical OK/WARN/FAIL envelope for Termux tool responses.
// Mirrors com.androidmcp.core.protocol.Envelope on the Kotlin side so the
// wire contract is language-independent.
//
// Shape:
//   { status: "ok" | "warn" | "fail", summary, hint, data, raw }
//
// Rendered text:
//   OK|WARN|FAIL: <summary>
//   [Hint: <hint>]
//
//   <data JSON>
//   [Raw: <raw JSON> on non-OK]

export const Status = Object.freeze({ OK: "ok", WARN: "warn", FAIL: "fail" });

export function ok(summary, data = {}, raw = {}) {
  return { status: Status.OK, summary, hint: "", data, raw };
}

export function warn(summary, hint = "", data = {}, raw = {}) {
  return { status: Status.WARN, summary, hint, data, raw };
}

export function fail(summary, hint = "", data = {}, raw = {}) {
  return { status: Status.FAIL, summary, hint, data, raw };
}

/**
 * Convert an Error into a FAIL envelope, consulting the tool's metadata
 * (if any) for an actionable hint via pattern or exceptionType matching.
 *
 * @param {string} toolName
 * @param {object|null} metadata - { failureModes: [{ pattern?, exceptionType?, hint }] }
 * @param {Error} err
 */
export function fromException(toolName, metadata, err) {
  const message = err?.message ?? String(err);
  const typeName = err?.name ?? err?.constructor?.name ?? "Error";
  let hint = "";
  if (metadata?.failureModes) {
    for (const fm of metadata.failureModes) {
      if (fm.pattern && new RegExp(fm.pattern).test(message)) { hint = fm.hint; break; }
      if (fm.exceptionType && (typeName === fm.exceptionType || fm.exceptionType === "Error")) { hint = fm.hint; break; }
    }
  }
  return fail(
    `${toolName} failed: ${message}`,
    hint,
    {},
    { exception: typeName, message },
  );
}

export function renderText(env) {
  const prefix = env.status.toUpperCase();
  const lines = [`${prefix}: ${env.summary}`];
  if (env.hint) lines.push(`Hint: ${env.hint}`);
  if (env.data && Object.keys(env.data).length) {
    lines.push("");
    lines.push(JSON.stringify(env.data, null, 2));
  }
  if (env.status !== Status.OK && env.raw && Object.keys(env.raw).length) {
    lines.push("");
    lines.push("Raw:");
    lines.push(JSON.stringify(env.raw, null, 2));
  }
  return lines.join("\n");
}

/**
 * Register a tool whose handler returns a String (or throws).
 * String return → OK envelope. Throw → FAIL envelope with failureModes hint.
 *
 * Usage:
 *   textTool(server, {
 *     name: "sms_send",
 *     description: "...",
 *     schema: { number: z.string(), text: z.string() },
 *     metadata: { destructive: true, latencyClass: "SLOW",
 *       failureModes: [{ pattern: "permission denied", hint: "Grant SEND_SMS..." }] },
 *   }, async ({ number, text }) => {
 *     // let exceptions propagate
 *     return await doSomething();
 *   });
 */
export function textTool(server, { name, description, schema, metadata = null }, handler) {
  server.tool(name, description, schema, async (args) => {
    try {
      const result = await handler(args);
      const text = typeof result === "string" ? result : JSON.stringify(result);
      const env = ok(`${name} succeeded`, { output: text });
      return { content: [{ type: "text", text: renderText(env) }], isError: false };
    } catch (err) {
      const env = fromException(name, metadata, err);
      return { content: [{ type: "text", text: renderText(env) }], isError: true };
    }
  });
}

/**
 * Register a tool whose handler returns an Envelope directly — useful for emitting
 * WARN or a specific FAIL without throwing.
 */
export function envelopeTool(server, { name, description, schema, metadata = null }, handler) {
  server.tool(name, description, schema, async (args) => {
    try {
      const env = await handler(args);
      return {
        content: [{ type: "text", text: renderText(env) }],
        isError: env.status === Status.FAIL,
      };
    } catch (err) {
      const env = fromException(name, metadata, err);
      return { content: [{ type: "text", text: renderText(env) }], isError: true };
    }
  });
}
```

### 2) `termux-mcp/test/envelope.test.js` — write exactly

```javascript
import { test } from "node:test";
import assert from "node:assert/strict";
import { ok, warn, fail, fromException, renderText, Status } from "../envelope.js";

test("ok has empty hint", () => {
  const e = ok("clipboard updated");
  assert.equal(e.status, Status.OK);
  assert.equal(e.summary, "clipboard updated");
  assert.equal(e.hint, "");
});

test("fail carries hint", () => {
  const e = fail("permission denied", "grant SEND_SMS");
  assert.equal(e.status, Status.FAIL);
  assert.equal(e.hint, "grant SEND_SMS");
});

test("warn carries hint", () => {
  const e = warn("partial", "3 of 5");
  assert.equal(e.status, Status.WARN);
  assert.equal(e.hint, "3 of 5");
});

test("fromException matches pattern", () => {
  const meta = { failureModes: [{ pattern: "permission denied", hint: "Grant perm." }] };
  const e = fromException("sms_send", meta, new Error("permission denied by user"));
  assert.equal(e.status, Status.FAIL);
  assert.equal(e.hint, "Grant perm.");
});

test("fromException matches exceptionType by constructor name", () => {
  class TimeoutError extends Error {
    constructor(msg) { super(msg); this.name = "TimeoutError"; }
  }
  const meta = { failureModes: [{ exceptionType: "TimeoutError", hint: "retry smaller" }] };
  const e = fromException("slow", meta, new TimeoutError("timed out"));
  assert.equal(e.hint, "retry smaller");
});

test("render ok starts with OK: and includes data JSON", () => {
  const e = ok("found 3 items", { count: 3 });
  const t = renderText(e);
  assert.ok(t.startsWith("OK: found 3 items"));
  assert.ok(t.includes("\"count\": 3"));
});

test("render fail includes hint line", () => {
  const e = fail("denied", "grant perm");
  const t = renderText(e);
  assert.ok(t.startsWith("FAIL: denied"));
  assert.ok(t.includes("Hint: grant perm"));
});

test("render ok omits raw block", () => {
  const e = ok("done", {}, { stdout: "hi" });
  const t = renderText(e);
  assert.ok(!t.includes("Raw:"));
});

test("render fail includes raw block", () => {
  const e = fail("bad", "", {}, { stderr: "boom" });
  const t = renderText(e);
  assert.ok(t.includes("Raw:"));
  assert.ok(t.includes("boom"));
});
```

### 3) `termux-mcp/server.js` migration

The current pattern per tool is:
```js
server.tool("sms_send", "...", { number: z.string(), text: z.string() }, async ({ number, text }) => {
  const out = await run("termux-sms-send", ["-n", number], text);
  return { content: [{ type: "text", text: out || "SMS sent." }] };
});
```

**Also note**: `run()` at the top of `server.js` currently catches errors and returns `"Error: ${e.message}"` — the catch-and-stringify anti-pattern. For the 6 canary tools, **use a throwing version** so the envelope helper can see exceptions. Add this helper next to the existing `run()`:

```js
/** Like run(), but throws on non-zero exit or subprocess error. */
async function runOrThrow(cmd, args = [], input) {
  const opts = {
    timeout: TIMEOUT,
    maxBuffer: 1024 * 1024,
    env: { ...process.env, TMPDIR: process.env.TMPDIR || "/data/data/com.termux/files/usr/tmp" },
  };
  if (input) opts.input = input;
  const { stdout, stderr } = await exec(cmd, args, opts);  // throws on non-zero
  return stdout || stderr || "(no output)";
}
```

Then, for each of the 6 canary tools, migrate from `server.tool(...)` to `textTool(server, { name, description, schema, metadata }, handler)`. Worked example for `sms_send`:

```js
textTool(server, {
  name: "sms_send",
  description: "Send an SMS message",
  schema: {
    number: z.string().describe("Phone number to send to"),
    text: z.string().describe("Message text"),
  },
  metadata: {
    destructive: true,
    idempotent: false,
    latencyClass: "SLOW",
    permissions: ["SEND_SMS"],
    failureModes: [
      { pattern: "permission denied|not default", hint: "Termux must be the default SMS app, or grant SEND_SMS permission." },
      { pattern: "invalid (number|phone)", hint: "Pass a fully-qualified phone number (country code + number, digits only)." },
    ],
  },
}, async ({ number, text }) => {
  return await runOrThrow("termux-sms-send", ["-n", number], text) || "SMS sent.";
});
```

The other 5 canaries follow the same shape. Suggested metadata skeletons:

| Tool | destructive | latency | permissions | key failure_modes |
|---|---|---|---|---|
| `notification_send` | true | FAST | POST_NOTIFICATIONS | pattern "permission" → "Grant POST_NOTIFICATIONS" |
| `location` | false | SLOW | ACCESS_FINE_LOCATION | pattern "provider disabled" → "Enable Location Services"; pattern "timeout" → "GPS can take 30s indoors; retry outside or use provider=network" |
| `camera_photo` | true (writes file) | SLOW | CAMERA | pattern "(No such|doesn't exist) device" → "No camera available (emulator?). Use termux.camera_info to list lenses." |
| `tts_speak` | true | SLOW | — | pattern "no tts engine" → "No TTS engine installed. Install one from Play Store." |
| `clipboard_set` | true | FAST | — | pattern "cannot access clipboard" → "Some secure keyboards block clipboard_set. Ask the user to switch keyboard." |

- [ ] **Step 1.1: Write `envelope.js` + `test/envelope.test.js`**

Create both files with the content above.

- [ ] **Step 1.2: Run JS tests — expect FAIL (envelope module doesn't exist yet)**

```bash
cd ~/Desktop/android-mcp-sdk/termux-mcp
node --test test/envelope.test.js
```
Expected: module-resolution or assertion failure.

- [ ] **Step 1.3: Re-run after creating envelope.js — expect PASS**

Same command. Expected: 9 tests pass.

- [ ] **Step 1.4: Modify `server.js`**

- Add near the top imports: `import { textTool, envelopeTool } from "./envelope.js";`
- Add `runOrThrow` helper next to the existing `run()`.
- Migrate the 6 canary tools listed above to `textTool(...)`. Leave the other 18 tools as-is.

- [ ] **Step 1.5: Smoke — start the server locally (without Termux) and call an easy canary**

```bash
cd ~/Desktop/android-mcp-sdk/termux-mcp
# Start on a scratch port so you don't collide with anything real:
HUB_PROXY_PORT=18378 node server.js &
SERVER_PID=$!
sleep 1

# tools/list should return all 24 tools; then call clipboard_set with a fake arg —
# on Mac (no termux-clipboard-set binary in PATH), it'll throw from runOrThrow and
# come back FAIL with raw block:
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"clipboard_set","arguments":{"text":"hi"}}}' \
  http://127.0.0.1:18378/mcp | head -20

kill $SERVER_PID 2>/dev/null
wait $SERVER_PID 2>/dev/null
```

Expected: the `text` field of the response starts with `FAIL: clipboard_set failed:` and includes `Hint:` if the error matches the pattern, else an empty hint. The key win is proving the text-on-the-wire shape — absolute truthfulness of the hint wording depends on which error the missing-binary produced.

If `termux-mcp/server.js` refuses to start off-device because of Termux-specific env assumptions, skip the smoke and rely on the unit tests — the helper is fully covered there.

- [ ] **Step 1.6: Commit**

```bash
cd ~/Desktop/android-mcp-sdk
git add termux-mcp/envelope.js termux-mcp/test/envelope.test.js termux-mcp/server.js
git commit -m "feat(termux-mcp): envelope helper + migrate 6 canary tools"
```

---

## Task 2: hub-proxy pass-through verification

`hub/hub-proxy.js` forwards tool calls from the MCP client to the Hub on port 8379 and returns the Hub's response to the client. Since the Hub is now envelope-wrapping (Wave 2A/2B), hub-proxy should carry that text through unchanged. Verify; only change code IF the smoke reveals mangling.

**Files:**
- Read-only unless smoke fails: `hub/hub-proxy.js`

- [ ] **Step 2.1: Start the emulator Hub (if not already running) and the proxy**

Pre-condition: `adb devices` shows `emulator-5554 device`; Hub is running on the emulator; `adb forward tcp:8379 tcp:8379` is set.

```bash
cd ~/Desktop/android-mcp-sdk/hub
# hub/package.json deps should already be installed from earlier sessions;
# if `ls node_modules` is empty, `npm install` first.
HUB_PROXY_PORT=18381 node hub-proxy.js &
PROXY_PID=$!
sleep 1
```

- [ ] **Step 2.2: Call the same tool via proxy and via direct-to-Hub**

```bash
# Via proxy
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"device.battery_status","arguments":{}}}' \
  http://127.0.0.1:18381/mcp | head -5 > /tmp/via-proxy.txt

# Direct to Hub
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"device.battery_status","arguments":{}}}' \
  http://127.0.0.1:8379/mcp | head -5 > /tmp/direct.txt

diff /tmp/via-proxy.txt /tmp/direct.txt
```

Expected: either zero diff (pass-through clean), or only cosmetic SSE/line-break differences. In both cases, the `text` field inside `content[0]` must start with `OK: battery_status succeeded`.

- [ ] **Step 2.3: Tear down + verdict**

```bash
kill $PROXY_PID 2>/dev/null
wait $PROXY_PID 2>/dev/null
```

If the proxy mangled the envelope text (stripped the `OK:` prefix, double-wrapped, etc.), read `hub-proxy.js`, find the point where it transforms the Hub's `result.content` into its own response, and adjust to carry `content` verbatim. Commit the fix as `fix(hub-proxy): preserve envelope text verbatim`.

If the diff is clean, there's no code change; document the verification in the task report and proceed. No commit for this task in that case.

---

## Task 3: tool-device rich metadata + handler-throw flips

Enrich 5 high-value device tools with metadata; flip any handler that currently catches a hard-failure exception and returns a string so the envelope can do its job.

**File:**
- Modify: `tool-device/src/main/kotlin/com/llmintentions/device/DeviceToolRegistrar.kt`

**Five target tools:**

1. `sensor_read` — FAST; failureMode pattern `unknown sensor` → "Call device.all (or sensor_list) to enumerate available sensors on this device."
2. `vibrate` — **destructive**, FAST, permission VIBRATE; failureMode pattern `permission` → "Grant VIBRATE permission."
3. `tts_speak` — **destructive** (audible), SLOW; failureMode pattern `no tts engine|TTS init` → "No TTS engine installed; open Play Store and install one."
4. `flashlight_on` / `flashlight_off` — **destructive**, FAST, permission CAMERA; failureMode pattern `no camera|lens not available` → "This device has no camera with a flash (emulator?). Check with device.all."
5. `clipboard_write` — **destructive**, FAST; failureMode pattern `secure|not allowed` → "Clipboard writes blocked by a secure-keyboard overlay. Ask the user to switch keyboards."

### Handler-throw audit

For each tool above, READ the existing handler body. Look for patterns like:

```kotlin
try {
    doWork(args)
} catch (e: SecurityException) {
    "Permission denied: ${e.message}"   // WRONG — framework reads as OK:
} catch (e: Exception) {
    "Error: ${e.message}"               // WRONG
}
```

For permission / hard-failure catches: **DELETE the try/catch**, let the exception propagate. The new `metadata.failureModes` entries will inject the hint.

For informational catches (e.g., `"No data in range"` that's a valid empty-state, not an error): **leave as-is** OR convert the tool to `envelopeTool` and return `Envelope.warn(...)` explicitly. Don't convert blindly.

If you're uncertain whether a catch is "hard" or "informational," report in the task summary and keep the catch — we can audit together.

### Worked example — `vibrate`:

Current (illustrative — read the actual code):
```kotlin
registry.textTool(
    "vibrate",
    "Vibrate the device",
    jsonSchema { number("duration_ms", "Duration in ms", required = true) }
) { args ->
    try {
        val ms = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: return@textTool "Error: duration_ms required"
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        "Vibrating for ${ms}ms"
    } catch (e: SecurityException) {
        "Error: VIBRATE permission denied"
    }
}
```

After:
```kotlin
registry.textTool(
    name = "vibrate",
    description = "Vibrate the device",
    params = jsonSchema { number("duration_ms", "Duration in ms", required = true) },
    metadata = toolMetadata {
        destructive = true
        idempotent = false
        latencyClass = LatencyClass.FAST
        permission("VIBRATE")
        failureMode(
            pattern = "permission|SecurityException",
            hint = "Grant VIBRATE permission to LLM Device Tools in System Settings.",
        )
        failureMode(
            pattern = "duration_ms required|missing",
            hint = "Pass {\"duration_ms\": <int>} in the call arguments.",
        )
        example(intent = "Vibrate for half a second.") { args ->
            args["duration_ms"] = 500
        }
    },
) { args ->
    val ms = args["duration_ms"]?.jsonPrimitive?.longOrNull
        ?: throw IllegalArgumentException("duration_ms required")
    vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    "Vibrating for ${ms}ms"
}
```

Key diffs: catches removed; `return@textTool "Error: ..."` replaced with `throw IllegalArgumentException(...)`; metadata added.

- [ ] **Step 3.1: Read `DeviceToolRegistrar.kt` and locate the 5 target tools**

```bash
cd ~/Desktop/android-mcp-sdk
grep -n 'registry.textTool("sensor_read\|registry.textTool("vibrate\|registry.textTool("tts_speak\|registry.textTool("flashlight\|registry.textTool("clipboard_write' tool-device/src/main/kotlin/com/llmintentions/device/DeviceToolRegistrar.kt
```
For any target that doesn't match, pick the nearest-named tool and report the substitution.

- [ ] **Step 3.2: Add imports** (if not already present)

```kotlin
import com.androidmcp.core.protocol.LatencyClass
import com.androidmcp.core.registry.toolMetadata
```

- [ ] **Step 3.3: Enrich each target + flip catches** per the pattern above

- [ ] **Step 3.4: Compile**

```bash
./gradlew :tool-device:compileDebugKotlin 2>&1 | tail -10
```

- [ ] **Step 3.5: Build + install + emulator smoke**

```bash
export PATH="/Users/adamsteen/Library/Android/sdk/platform-tools:$PATH"
./gradlew :tool-device:assembleDebug 2>&1 | tail -3
adb install -r tool-device/build/outputs/apk/debug/tool-device-debug.apk
adb shell am start -n com.llmintentions.device/.DeviceActivity
sleep 2

# Forced FAIL: pass an unknown sensor name. If you flipped sensor_read's catch,
# expect FAIL: sensor_read failed: ... Hint: Call device.all ...
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":301,"method":"tools/call","params":{"name":"device.sensor_read","arguments":{"sensor":"not_a_real_sensor"}}}' \
  http://127.0.0.1:8379/mcp | sed 's/data: //' | python3 -c "
import sys, json
raw = sys.stdin.read()
r = json.loads([l for l in raw.split(chr(10)) if l.startswith('{')][0])
print('isError:', r['result'].get('isError'))
print(r['result']['content'][0]['text'])
"
```
Expected (if sensor_read's catch was flipped): `isError: True`, text starts with `FAIL: sensor_read failed: Unknown sensor: not_a_real_sensor`, includes `Hint:`. If you left the catch in place intentionally (it's informational, not hard-failure), note that in the task report.

- [ ] **Step 3.6: Commit**

```bash
git add tool-device/src/main/kotlin/com/llmintentions/device/DeviceToolRegistrar.kt
git commit -m "feat(tool-device): rich metadata + throw on hard errors for 5 core tools"
```

---

## Task 4: tool-notify rich metadata + handler-throw flips

Same pattern as Task 3. Enrich 5 notification tools; flip permission/permission-denied catches to throw.

**File:**
- Modify: `tool-notify/src/main/kotlin/com/llmintentions/notify/NotifyToolRegistrar.kt`

**Five target tools:**

1. `notifications_list` — permission BIND_NOTIFICATION_LISTENER_SERVICE; listener-not-connected failure.
2. `notification_dismiss` — **destructive**; permission BIND_NOTIFICATION_LISTENER_SERVICE.
3. `notification_reply` — **destructive**; needs remote input available on source notification.
4. `notification_history` — read, permission.
5. `notification_filter` — read, permission.

**Metadata skeletons:**

| Tool | destructive | latency | permissions | key failure_modes |
|---|---|---|---|---|
| `notifications_list` | false | FAST | BIND_NOTIFICATION_LISTENER_SERVICE | pattern "listener not connected\|no listener" → "Grant 'Notification Access' to LLM Notify Tools in System Settings > Notifications > Notification Access." |
| `notification_dismiss` | true | FAST | same | same permission hint; failure mode for unknown key → "Call notifications_list to get valid keys." |
| `notification_reply` | true | FAST | same | pattern "no remote input\|reply action missing" → "This notification doesn't have a quick-reply action. Not all notifications support reply." |
| `notification_history` | false | SLOW | same | same permission hint |
| `notification_filter` | false | FAST | same | same permission hint |

- [ ] **Step 4.1-4.6: Same order as Task 3**

Read → add imports → enrich + flip catches → compile → install + smoke → commit.

**Smoke:** try `notifications_list` with no listener granted — expect FAIL with the "Grant Notification Access" hint.

Commit message: `feat(tool-notify): rich metadata + throw on hard errors for 5 core tools`.

---

## Task 5: tool-people rich metadata + handler-throw flips

Same pattern. This is the most permission-heavy CapApp.

**File:**
- Modify: `tool-people/src/main/kotlin/com/llmintentions/people/PeopleToolRegistrar.kt`

**Six target tools:**

1. `sms_send` — **destructive**, permission SEND_SMS, requires default-SMS-app role on modern Android.
2. `contacts_search` — permission READ_CONTACTS.
3. `contact_add` — **destructive**, permission WRITE_CONTACTS.
4. `contact_delete` — **destructive**, permission WRITE_CONTACTS.
5. `event_create` — **destructive**, permission WRITE_CALENDAR.
6. `calendar_today` — permission READ_CALENDAR.

**Metadata skeletons:**

| Tool | destructive | latency | permissions | key failure_modes |
|---|---|---|---|---|
| `sms_send` | true | SLOW | SEND_SMS | pattern "default sms" → "On Android 10+, only the default SMS app can send. Set Termux or your SMS app as default."; pattern "permission" → "Grant SEND_SMS permission." |
| `contacts_search` | false | SLOW | READ_CONTACTS | pattern "permission" → "Grant READ_CONTACTS permission in System Settings." |
| `contact_add` | true | FAST | WRITE_CONTACTS | pattern "permission" → "Grant WRITE_CONTACTS permission."; pattern "missing (name\|phone)" → "Provide at least a name or a phone number." |
| `contact_delete` | true | FAST | WRITE_CONTACTS | pattern "permission" → same; pattern "not found" → "Verify the contact_id via contacts_search first." |
| `event_create` | true | FAST | WRITE_CALENDAR | pattern "permission" → "Grant WRITE_CALENDAR permission."; pattern "invalid (start\|end)" → "Timestamps must be Unix epoch seconds (or ISO 8601 depending on what the handler accepts)." |
| `calendar_today` | false | FAST | READ_CALENDAR | pattern "permission" → "Grant READ_CALENDAR permission." |

- [ ] **Step 5.1-5.6: Same order as Task 3**

Commit message: `feat(tool-people): rich metadata + throw on hard errors for 6 core tools`.

---

## Out of scope — Wave 2E (follow-up)

1. Migrate the other ~18 `termux-mcp` tools to `textTool` helper. Mechanical.
2. Flip remaining catch-and-stringify handlers across all CapApps (not just the 5-6 we target per CapApp here). Would benefit from a grep-driven audit: `grep -rn 'catch.*{$' tool-*/src/main/` and review each.
3. Extract the shared `:tool-ui-common` Gradle module called out in the Wave 1 WIP-landing commit.

---

## Self-Review

- **Spec coverage:** Task 1 closes termux-mcp envelope gap; Task 2 verifies hub-proxy; Tasks 3-5 backfill the three remaining CapApps. All Wave 2A/2B wisdom patterns applied. ✓
- **Placeholder scan:** No TBD / TODO. Worked examples shown for termux-mcp (sms_send), tool-device (vibrate). For tool-notify / tool-people, the table provides enough signal for a subagent to extrapolate using the vibrate example's shape. ✓
- **Type consistency:** Kotlin signatures match Wave 2A/2B exactly. JS surface (`ok`, `warn`, `fail`, `fromException`, `renderText`, `textTool`, `envelopeTool`) mirrors the Kotlin names. Metadata field names (`destructive`, `idempotent`, `latencyClass`, `permissions`, `failureModes`) are identical strings across JS and Kotlin. ✓
