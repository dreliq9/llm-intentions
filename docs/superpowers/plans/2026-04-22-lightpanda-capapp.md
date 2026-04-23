# Lightpanda CapApp Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a `com.llmintentions.lightpanda` CapApp APK that exposes Lightpanda's native MCP browser tools under the `browser.*` namespace, running Lightpanda as a stock `aarch64-linux` binary under proot inside the CapApp's own sandbox.

**Architecture:** Standard LLM Intentions CapApp (Intent-in / broadcast-out), plus a bound `LightpandaBridgeService` that the Hub binds on discovery to keep the CapApp process alive. A singleton `LightpandaSession` owns a long-lived subprocess started via `ProcessBuilder(proot-android, -r, rootfs, -- , lightpanda, mcp)`, and speaks JSON-RPC over stdio with id-multiplexing. Large payloads (screenshots, >256 KB text) exit via `FileProvider` `content://` URIs in the protocol's `resource` content type. The rootfs (proot binary + minimal glibc + Lightpanda + Mozilla CA bundle) is downloaded on first launch from a pinned URL and SHA256-verified.

**Tech Stack:** Kotlin, Android Gradle Plugin, AIDL, `org.json`, `kotlinx.coroutines`, `ProcessBuilder`, Android `FileProvider`, `okhttp` for the rootfs download, JUnit + Robolectric for unit tests. No V8, no Zig — we ship the upstream Lightpanda binary as-is.

**Source spec:** `docs/superpowers/specs/2026-04-22-lightpanda-capapp-design.md`.

**Execution model:** Each task below is 2–5 minutes. Run tasks in order within a phase; phases 1–9 are mostly sequential. Commit after every task.

---

## Phase 0 — M0 Proof-of-life spike (gate: no Kotlin until this passes)

**Purpose:** Prove that the stock `lightpanda-aarch64-linux` binary runs under proot on the target phone and serves its MCP tools over stdio. If this fails, pivot the design to a LAN sidecar architecture before writing Kotlin.

### Task 0.1: Build / source the rootfs tarball

**Files:**
- Create: `capapps/lightpanda/rootfs/build-rootfs.sh` (host-side builder script; committed for reproducibility)
- Create: `capapps/lightpanda/rootfs/README.md` (what the tarball contains + SHA256 of last pinned version)

- [ ] **Step 1: Create the builder script**

Create `capapps/lightpanda/rootfs/build-rootfs.sh`:

```bash
#!/usr/bin/env bash
# Build the Lightpanda CapApp rootfs tarball.
#
# Output: lightpanda-rootfs-<version>-aarch64.tar.zst
# Contents:
#   proot-android          (static ELF, from termux/proot builds)
#   rootfs/                (minimal glibc Debian minbase tree)
#     etc/ssl/certs/cacert.pem
#     lightpanda           (stock aarch64-linux release)
#
# Run on a Linux host (x86_64 or aarch64). Requires: debootstrap, zstd, curl.
set -euo pipefail

VERSION="${1:-0.1.0}"
LIGHTPANDA_URL="https://github.com/lightpanda-io/browser/releases/download/nightly/lightpanda-aarch64-linux"
PROOT_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.4.0-1_aarch64.deb"
CACERT_URL="https://curl.se/ca/cacert.pem"

WORK=$(mktemp -d)
trap "rm -rf $WORK" EXIT

echo "==> fetching lightpanda"
curl -fsSL "$LIGHTPANDA_URL" -o "$WORK/lightpanda"
chmod +x "$WORK/lightpanda"

echo "==> fetching proot (extracting from termux .deb)"
curl -fsSL "$PROOT_URL" -o "$WORK/proot.deb"
mkdir "$WORK/proot-extract"
dpkg-deb -x "$WORK/proot.deb" "$WORK/proot-extract"
cp "$WORK/proot-extract/data/data/com.termux/files/usr/bin/proot" "$WORK/proot-android"

echo "==> fetching cacert"
mkdir -p "$WORK/rootfs/etc/ssl/certs"
curl -fsSL "$CACERT_URL" -o "$WORK/rootfs/etc/ssl/certs/cacert.pem"

echo "==> building minimal debian minbase rootfs"
sudo debootstrap --arch=arm64 --variant=minbase --foreign bookworm "$WORK/rootfs" \
  http://deb.debian.org/debian/

# Strip everything we don't need (docs, locales, man, cache)
sudo rm -rf "$WORK/rootfs/usr/share/doc" \
            "$WORK/rootfs/usr/share/man" \
            "$WORK/rootfs/usr/share/locale" \
            "$WORK/rootfs/var/cache/apt" \
            "$WORK/rootfs/var/lib/apt/lists"

cp "$WORK/lightpanda" "$WORK/rootfs/lightpanda"

TARBALL="lightpanda-rootfs-${VERSION}-aarch64.tar.zst"
tar -C "$WORK" --use-compress-program="zstd -19" -cf "$TARBALL" proot-android rootfs/
sha256sum "$TARBALL"
ls -lh "$TARBALL"
echo "done: $TARBALL"
```

- [ ] **Step 2: Commit the builder script**

```bash
git add capapps/lightpanda/rootfs/build-rootfs.sh
git commit -m "spike: add rootfs builder script for Lightpanda CapApp"
```

- [ ] **Step 3: Run the builder on a Linux host (not Mac — debootstrap requires Linux)**

Options for a Linux host:
- A Linux VM (UTM on Mac, or a cloud VM)
- WSL2 on a Windows machine
- A temporary Docker `debian:bookworm` container with `--privileged`

Command:

```bash
chmod +x capapps/lightpanda/rootfs/build-rootfs.sh
sudo capapps/lightpanda/rootfs/build-rootfs.sh 0.1.0
```

Expected output: `lightpanda-rootfs-0.1.0-aarch64.tar.zst` in the current dir (~40–55 MB), and a SHA256 printed to stdout.

- [ ] **Step 4: Record the SHA256 + tarball URL destination**

Open `capapps/lightpanda/rootfs/README.md` and write:

```markdown
# Lightpanda CapApp Rootfs

Built by `build-rootfs.sh`. Host it as a GitHub Release asset so the CapApp can
download + verify it on first launch.

## Pinned version

| Version | SHA256 | URL |
|---------|--------|-----|
| 0.1.0   | `<PASTE SHA256 FROM STEP 3>` | https://github.com/dreliq9/llm-intentions/releases/download/lightpanda-rootfs-v0.1.0/lightpanda-rootfs-0.1.0-aarch64.tar.zst |
```

- [ ] **Step 5: Commit**

```bash
git add capapps/lightpanda/rootfs/README.md
git commit -m "spike: record pinned rootfs SHA256"
```

### Task 0.2: Push to phone and verify via adb

- [ ] **Step 1: Push the tarball to the phone**

```bash
adb push lightpanda-rootfs-0.1.0-aarch64.tar.zst /data/local/tmp/
```

Expected: file transferred (~40–55 MB).

- [ ] **Step 2: Extract on-device**

```bash
adb shell
cd /data/local/tmp
tar --use-compress-program=unzstd -xf lightpanda-rootfs-0.1.0-aarch64.tar.zst
ls -la proot-android rootfs/lightpanda
```

Expected: both files present, executable bit set.

- [ ] **Step 3: Test proot + lightpanda cold-start**

Still in the adb shell:

```bash
cd /data/local/tmp
CURL_CA_BUNDLE=/etc/ssl/certs/cacert.pem ./proot-android -r rootfs -- /lightpanda mcp
```

You should now be sitting at a stdin prompt (no echo). Type:

```
{"jsonrpc":"2.0","id":1,"method":"tools/list"}
```

Press Enter. Expected: a JSON response with a `tools` array including `goto`, `evaluate`, `screenshot`, `markdown`, `structuredData`, `semantic_tree`, `interactiveElements`, `click`, `fill`.

- [ ] **Step 4: Test networking + TLS**

In the same session:

```
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"goto","arguments":{"url":"https://example.com"}}}
```

Expected: success response.

Then:

```
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"markdown","arguments":{}}}
```

Expected: response containing the "Example Domain" text.

Ctrl-D to exit.

- [ ] **Step 5: M0 GATE**

If all four steps above succeeded → the design is feasible as written. Proceed to Phase 1.

If any failed:
- **proot won't start** (`ptrace: Operation not permitted`): phone's SELinux blocks ptrace. Need to test inside Termux's existing proot context (which we know works, since Claude Code runs there). If still blocked, pivot to LAN sidecar.
- **lightpanda won't start** (`ld-linux-aarch64.so.1: No such file or directory`): rootfs is missing the dynamic linker; `--variant=minbase` may have stripped too much. Re-run the build with `--include=libc6`.
- **TLS fails** (`SslCacertBadfile`): cacert path wrong. `CURL_CA_BUNDLE` must point to the file inside the rootfs as seen from the proot'd process's view.
- **tools/list returns empty / errors:** the release binary may not implement MCP yet — verify against Lightpanda source `src/mcp/tools.zig`. Consider building from source or using an older/newer release.

Do not proceed past this gate if any failure is unresolved.

---

## Phase 1 — Module scaffolding

**Purpose:** Create the Gradle module, manifest, and empty service skeleton. End-state: the APK builds, installs, shows "Lightpanda CapApp — not ready" in its settings activity, and does nothing else.

### Task 1.1: Register the Gradle module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `capapps/lightpanda/build.gradle.kts`

- [ ] **Step 1: Write the failing check**

Run from the repo root:

```bash
./gradlew :capapps:lightpanda:tasks
```

Expected: FAIL — `Project 'capapps:lightpanda' not found`.

- [ ] **Step 2: Add the module to settings.gradle.kts**

Open `settings.gradle.kts`. After the line `include(":tool-notify")`, add:

```kotlin
include(":capapps:lightpanda")
```

- [ ] **Step 3: Create the module's build.gradle.kts**

Create `capapps/lightpanda/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.llmintentions.lightpanda"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.llmintentions.lightpanda"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
```

- [ ] **Step 4: Run the check**

```bash
./gradlew :capapps:lightpanda:tasks
```

Expected: PASS (a Gradle task list is printed). If it fails with a missing directory error, create `capapps/lightpanda/src/main/` as a placeholder and retry.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts capapps/lightpanda/build.gradle.kts
git commit -m "feat(lightpanda): register Gradle module"
```

### Task 1.2: Write the AndroidManifest

**Files:**
- Create: `capapps/lightpanda/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create the manifest**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- Signature-level permission; only apps signed with the same key (Hub) can call our services. -->
    <permission
        android:name="com.llmintentions.permission.MCP_TOOL"
        android:protectionLevel="signature" />
    <uses-permission android:name="com.llmintentions.permission.MCP_TOOL" />

    <application
        android:name=".LightpandaApp"
        android:label="Lightpanda Browser"
        android:icon="@android:drawable/ic_menu_compass"
        android:allowBackup="false">

        <!-- Settings / first-run UI -->
        <activity
            android:name=".CapAppSettingsActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Tool invocation (protocol: Intent → broadcast reply) -->
        <service
            android:name=".CommandGatewayService"
            android:exported="true"
            android:permission="com.llmintentions.permission.MCP_TOOL">
            <intent-filter>
                <action android:name="com.llmintentions.ACTION_MCP_TOOL" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <!-- Dynamic tool list — no static res/xml/mcp_tools.xml for this CapApp. -->
            <meta-data
                android:name="com.llmintentions.mcp.requires-liveness-bind"
                android:value="true" />
        </service>

        <!-- Dynamic discovery: returns Lightpanda's tools/list JSON. -->
        <receiver
            android:name=".LightpandaListToolsReceiver"
            android:exported="true"
            android:permission="com.llmintentions.permission.MCP_TOOL">
            <intent-filter>
                <action android:name="com.llmintentions.ACTION_LIST_TOOLS" />
            </intent-filter>
        </receiver>

        <!-- Bound service for Hub-held liveness. -->
        <service
            android:name=".LightpandaBridgeService"
            android:exported="true"
            android:permission="com.llmintentions.permission.MCP_TOOL" />

        <!-- FileProvider for large tool outputs (screenshots, big markdown). -->
        <provider
            android:name=".LightpandaFileProvider"
            android:authorities="com.llmintentions.lightpanda.files"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_provider_paths" />
        </provider>
    </application>
</manifest>
```

- [ ] **Step 2: Create the FileProvider paths resource**

Create `capapps/lightpanda/src/main/res/xml/file_provider_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <files-path name="tool-outputs" path="tool-outputs/" />
</paths>
```

- [ ] **Step 3: Create stub strings resource**

Create `capapps/lightpanda/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Lightpanda Browser</string>
</resources>
```

- [ ] **Step 4: Build**

```bash
./gradlew :capapps:lightpanda:assembleDebug
```

Expected: build succeeds. (Classes referenced in the manifest don't exist yet — Android will accept this until install-time; Gradle compiles only the classes that exist.)

Actually — AGP will fail because the manifest references unresolvable classes. Create empty stubs in the next task.

- [ ] **Step 5: Commit**

```bash
git add capapps/lightpanda/src/main/AndroidManifest.xml \
        capapps/lightpanda/src/main/res/xml/file_provider_paths.xml \
        capapps/lightpanda/src/main/res/values/strings.xml
git commit -m "feat(lightpanda): manifest + resources"
```

### Task 1.3: Create empty class skeletons so AGP is happy

**Files:**
- Create: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/LightpandaApp.kt`
- Create: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/CapAppSettingsActivity.kt`
- Create: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/CommandGatewayService.kt`
- Create: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/LightpandaListToolsReceiver.kt`
- Create: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/LightpandaBridgeService.kt`
- Create: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/LightpandaFileProvider.kt`

- [ ] **Step 1: LightpandaApp.kt**

```kotlin
package com.llmintentions.lightpanda

import android.app.Application

class LightpandaApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
```

- [ ] **Step 2: CapAppSettingsActivity.kt**

```kotlin
package com.llmintentions.lightpanda

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class CapAppSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Lightpanda CapApp — not ready" })
    }
}
```

- [ ] **Step 3: CommandGatewayService.kt**

```kotlin
package com.llmintentions.lightpanda

import android.app.Service
import android.content.Intent
import android.os.IBinder

class CommandGatewayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelfResult(startId)
        return START_NOT_STICKY
    }
}
```

- [ ] **Step 4: LightpandaListToolsReceiver.kt**

```kotlin
package com.llmintentions.lightpanda

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LightpandaListToolsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Phase 1: stub. Real implementation in Phase 6.
    }
}
```

- [ ] **Step 5: LightpandaBridgeService.kt**

```kotlin
package com.llmintentions.lightpanda

import android.app.Service
import android.content.Intent
import android.os.IBinder

class LightpandaBridgeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null // Phase 6 returns the AIDL binder
}
```

- [ ] **Step 6: LightpandaFileProvider.kt**

```kotlin
package com.llmintentions.lightpanda

import androidx.core.content.FileProvider

class LightpandaFileProvider : FileProvider()
```

- [ ] **Step 7: Build and commit**

```bash
./gradlew :capapps:lightpanda:assembleDebug
```

Expected: PASS.

```bash
git add capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/
git commit -m "feat(lightpanda): empty class skeletons"
```

### Task 1.4: Install on phone, confirm launcher icon + screen

- [ ] **Step 1: Build and install**

```bash
./gradlew :capapps:lightpanda:installDebug
```

- [ ] **Step 2: Verify on phone**

Open the app drawer, find "Lightpanda Browser", tap. Expected: a screen with "Lightpanda CapApp — not ready".

- [ ] **Step 3: If the Hub is also installed, confirm discovery doesn't crash**

```bash
adb logcat -d -s Hub:* | tail -50
```

Expected: the Hub may log "discovered com.llmintentions.lightpanda" or equivalent; no crash. If the Hub isn't installed yet, skip.

- [ ] **Step 4: Commit any adjustment (no code changes expected)**

No commit needed unless something broke.

---

## Phase 2 — JSON-RPC core (pure Kotlin, TDD)

**Purpose:** Build and test the JSON-RPC framing, id multiplexer, and pending-request map as pure Kotlin classes independent of Android. Full JVM unit tests. This is where TDD actually earns its keep — the rest is Android lifecycle.

### Task 2.1: `JsonRpcFrame` — line-delimited JSON framing

**Files:**
- Create: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/JsonRpcFrame.kt`
- Create: `capapps/lightpanda/src/test/java/com/llmintentions/lightpanda/session/JsonRpcFrameTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.llmintentions.lightpanda.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonRpcFrameTest {
    @Test
    fun `encodes object with trailing newline`() {
        val obj = JSONObject().put("jsonrpc", "2.0").put("id", 7).put("method", "tools/list")
        val encoded = JsonRpcFrame.encode(obj)
        assertEquals("""{"jsonrpc":"2.0","id":7,"method":"tools/list"}""" + "\n", encoded)
    }

    @Test
    fun `decodes a single frame`() {
        val frame = """{"id":7,"result":{"ok":true}}"""
        val obj = JsonRpcFrame.decode(frame)
        assertEquals(7, obj.getInt("id"))
        assertEquals(true, obj.getJSONObject("result").getBoolean("ok"))
    }

    @Test(expected = org.json.JSONException::class)
    fun `decode throws on malformed`() {
        JsonRpcFrame.decode("not json")
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

```bash
./gradlew :capapps:lightpanda:testDebugUnitTest --tests "*JsonRpcFrameTest*"
```

Expected: FAIL — `JsonRpcFrame` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.llmintentions.lightpanda.session

import org.json.JSONObject

object JsonRpcFrame {
    fun encode(obj: JSONObject): String = obj.toString() + "\n"
    fun decode(line: String): JSONObject = JSONObject(line)
}
```

- [ ] **Step 4: Run — expect PASS**

```bash
./gradlew :capapps:lightpanda:testDebugUnitTest --tests "*JsonRpcFrameTest*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/JsonRpcFrame.kt \
        capapps/lightpanda/src/test/java/com/llmintentions/lightpanda/session/JsonRpcFrameTest.kt
git commit -m "feat(lightpanda): JSON-RPC line framing"
```

### Task 2.2: `PendingRequests` — id multiplexer with timeout

**Files:**
- Create: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/PendingRequests.kt`
- Create: `capapps/lightpanda/src/test/java/com/llmintentions/lightpanda/session/PendingRequestsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.llmintentions.lightpanda.session

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PendingRequestsTest {
    @Test
    fun `register then fulfil returns the result`() = runTest {
        val pending = PendingRequests()
        val (id, deferred) = pending.register()
        assertEquals(1, id)
        val result = JSONObject().put("id", 1).put("result", "hi")
        assertTrue(pending.fulfil(id, result))
        assertEquals("hi", deferred.await().getString("result"))
    }

    @Test
    fun `ids increment monotonically`() = runTest {
        val pending = PendingRequests()
        val (a, _) = pending.register()
        val (b, _) = pending.register()
        val (c, _) = pending.register()
        assertEquals(listOf(1, 2, 3), listOf(a, b, c))
    }

    @Test
    fun `fulfil of unknown id returns false and does not throw`() = runTest {
        val pending = PendingRequests()
        assertFalse(pending.fulfil(999, JSONObject()))
    }

    @Test
    fun `failAll fails every in-flight request with same exception`() = runTest {
        val pending = PendingRequests()
        val (_, a) = pending.register()
        val (_, b) = pending.register()
        pending.failAll(RuntimeException("crashed"))
        assertNull(withTimeoutOrNull(100) { a.await() })
        assertNull(withTimeoutOrNull(100) { b.await() })
        assertTrue(a.isCancelled || a.getCompletionExceptionOrNull() is RuntimeException)
        assertTrue(b.isCancelled || b.getCompletionExceptionOrNull() is RuntimeException)
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

```bash
./gradlew :capapps:lightpanda:testDebugUnitTest --tests "*PendingRequestsTest*"
```

- [ ] **Step 3: Implement**

```kotlin
package com.llmintentions.lightpanda.session

import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PendingRequests {
    private val nextId = AtomicInteger(1)
    private val inFlight = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    fun register(): Pair<Int, CompletableDeferred<JSONObject>> {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        inFlight[id] = deferred
        return id to deferred
    }

    fun fulfil(id: Int, result: JSONObject): Boolean {
        val deferred = inFlight.remove(id) ?: return false
        return deferred.complete(result)
    }

    fun failAll(cause: Throwable) {
        val snapshot = inFlight.values.toList()
        inFlight.clear()
        snapshot.forEach { it.completeExceptionally(cause) }
    }

    fun size(): Int = inFlight.size
}
```

- [ ] **Step 4: Run — expect PASS**

```bash
./gradlew :capapps:lightpanda:testDebugUnitTest --tests "*PendingRequestsTest*"
```

- [ ] **Step 5: Commit**

```bash
git add capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/PendingRequests.kt \
        capapps/lightpanda/src/test/java/com/llmintentions/lightpanda/session/PendingRequestsTest.kt
git commit -m "feat(lightpanda): pending-request id multiplexer"
```

### Task 2.3: `RootfsLayout` — paths + validation

**Files:**
- Create: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/RootfsLayout.kt`
- Create: `capapps/lightpanda/src/test/java/com/llmintentions/lightpanda/session/RootfsLayoutTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.llmintentions.lightpanda.session

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootfsLayoutTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `isReady false when marker absent`() {
        val layout = RootfsLayout(tmp.root)
        assertFalse(layout.isReady())
    }

    @Test
    fun `isReady true when marker matches expected version`() {
        val layout = RootfsLayout(tmp.root)
        layout.markerFile.writeText("0.1.0")
        assertTrue(layout.isReady())
    }

    @Test
    fun `isReady false when marker has wrong version`() {
        val layout = RootfsLayout(tmp.root)
        layout.markerFile.writeText("0.0.9")
        assertFalse(layout.isReady())
    }

    @Test
    fun `path helpers resolve to expected locations`() {
        val layout = RootfsLayout(tmp.root)
        assertEquals(tmp.root.resolve("proot-android").absolutePath, layout.prootBinary.absolutePath)
        assertEquals(tmp.root.resolve("rootfs/lightpanda").absolutePath, layout.lightpandaBinary.absolutePath)
        assertEquals(tmp.root.resolve("rootfs").absolutePath, layout.rootfsDir.absolutePath)
        assertEquals("/etc/ssl/certs/cacert.pem", layout.cacertInsideRootfs)
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

```bash
./gradlew :capapps:lightpanda:testDebugUnitTest --tests "*RootfsLayoutTest*"
```

- [ ] **Step 3: Implement**

```kotlin
package com.llmintentions.lightpanda.session

import java.io.File

class RootfsLayout(val baseDir: File) {
    val markerFile: File get() = File(baseDir, ".llm_intentions_marker")
    val prootBinary: File get() = File(baseDir, "proot-android")
    val rootfsDir: File get() = File(baseDir, "rootfs")
    val lightpandaBinary: File get() = File(rootfsDir, "lightpanda")
    val cacertInsideRootfs: String = "/etc/ssl/certs/cacert.pem"

    fun isReady(): Boolean {
        if (!markerFile.exists()) return false
        return markerFile.readText().trim() == EXPECTED_VERSION
    }

    fun writeMarker() {
        markerFile.writeText(EXPECTED_VERSION)
    }

    companion object {
        const val EXPECTED_VERSION = "0.1.0"
    }
}
```

- [ ] **Step 4: Run — expect PASS**

- [ ] **Step 5: Commit**

```bash
git add capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/RootfsLayout.kt \
        capapps/lightpanda/src/test/java/com/llmintentions/lightpanda/session/RootfsLayoutTest.kt
git commit -m "feat(lightpanda): rootfs layout + marker versioning"
```

### Task 2.4: `Sha256Verifier` — streaming hash check

**Files:**
- Create: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/Sha256Verifier.kt`
- Create: `capapps/lightpanda/src/test/java/com/llmintentions/lightpanda/session/Sha256VerifierTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.llmintentions.lightpanda.session

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Sha256VerifierTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `hashes a known file correctly`() {
        // sha256 of "hello\n" is 5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03
        val f = tmp.newFile("hello.txt").apply { writeText("hello\n") }
        val hash = Sha256Verifier.hashFile(f)
        assertEquals("5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03", hash)
    }

    @Test
    fun `verify passes on matching hash`() {
        val f = tmp.newFile("h.txt").apply { writeText("hello\n") }
        assertTrue(Sha256Verifier.verify(f, "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03"))
    }

    @Test
    fun `verify fails on mismatched hash`() {
        val f = tmp.newFile("h.txt").apply { writeText("hello\n") }
        assertFalse(Sha256Verifier.verify(f, "0000000000000000000000000000000000000000000000000000000000000000"))
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement**

```kotlin
package com.llmintentions.lightpanda.session

import java.io.File
import java.security.MessageDigest

object Sha256Verifier {
    fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(file: File, expected: String): Boolean = hashFile(file).equals(expected, ignoreCase = true)
}
```

- [ ] **Step 4: Run — expect PASS**

- [ ] **Step 5: Commit**

```bash
git add capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/Sha256Verifier.kt \
        capapps/lightpanda/src/test/java/com/llmintentions/lightpanda/session/Sha256VerifierTest.kt
git commit -m "feat(lightpanda): SHA256 streaming verifier"
```

---

## Phase 3 — Subprocess management (Android-side)

**Purpose:** Spawn proot + lightpanda as a long-lived subprocess, speak JSON-RPC over stdio, handle concurrency + crash recovery. On-device verification.

### Task 3.1: `LightpandaSession` — core lifecycle and API

**Files:**
- Create: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/LightpandaSession.kt`

- [ ] **Step 1: Write the skeleton with full code**

```kotlin
package com.llmintentions.lightpanda.session

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicReference

class LightpandaSession(
    private val context: Context,
    private val layout: RootfsLayout,
    private val defaultTimeoutMs: Long = 60_000L,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stdinMutex = Mutex()
    private val pending = PendingRequests()
    private val process = AtomicReference<Process?>()
    private var stdout: BufferedReader? = null
    private var stdin: OutputStreamWriter? = null
    private var readerJob: Job? = null
    private val crashTimestamps = ArrayDeque<Long>()
    private var toolsListCache: JSONObject? = null
    @Volatile private var inCoolDown = false

    sealed class CallOutcome {
        data class Ok(val result: JSONObject) : CallOutcome()
        data class Err(val code: Int, val message: String) : CallOutcome()
    }

    suspend fun ensureSpawned() {
        if (process.get()?.isAlive == true) return
        spawnProcess()
    }

    suspend fun getToolList(): JSONObject {
        toolsListCache?.let { return it }
        ensureSpawned()
        val req = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "tools/list")
        val response = sendAndAwait(req, defaultTimeoutMs)
        toolsListCache = response.optJSONObject("result") ?: JSONObject()
        return toolsListCache!!
    }

    suspend fun callTool(name: String, arguments: JSONObject, timeoutMs: Long = defaultTimeoutMs): CallOutcome {
        if (inCoolDown) return CallOutcome.Err(-32099, "browser in cool-down after crash loop")
        ensureSpawned()
        val req = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "tools/call")
            .put("params", JSONObject().put("name", name).put("arguments", arguments))
        return try {
            val response = sendAndAwait(req, timeoutMs)
            response.optJSONObject("error")?.let {
                return CallOutcome.Err(it.optInt("code", -32000), it.optString("message", "unknown error"))
            }
            CallOutcome.Ok(response.optJSONObject("result") ?: JSONObject())
        } catch (e: TimeoutCancellationException) {
            CallOutcome.Err(-32099, "tool call timed out after ${timeoutMs}ms")
        } catch (e: Exception) {
            CallOutcome.Err(-32099, "tool call failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun sendAndAwait(requestTemplate: JSONObject, timeoutMs: Long): JSONObject {
        val (id, deferred) = pending.register()
        val framed = JsonRpcFrame.encode(JSONObject(requestTemplate.toString()).put("id", id))
        try {
            stdinMutex.withLock {
                val writer = stdin ?: throw IllegalStateException("stdin not open")
                writer.write(framed)
                writer.flush()
            }
        } catch (e: Exception) {
            pending.fulfil(id, JSONObject()) // drop the registration
            throw e
        }
        return withTimeout(timeoutMs) { deferred.await() }
    }

    private fun spawnProcess() {
        require(layout.isReady()) { "rootfs not provisioned" }
        Log.i(TAG, "spawning lightpanda: ${layout.prootBinary.absolutePath}")
        val pb = ProcessBuilder(
            layout.prootBinary.absolutePath,
            "-r", layout.rootfsDir.absolutePath,
            "--",
            "/lightpanda", "mcp"
        ).apply {
            environment()["CURL_CA_BUNDLE"] = layout.cacertInsideRootfs
            redirectErrorStream(false)
        }
        val p = pb.start()
        process.set(p)
        stdout = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
        stdin = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
        readerJob = scope.launch { readLoop(p) }
    }

    private suspend fun readLoop(p: Process) {
        val reader = stdout ?: return
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    val obj = JsonRpcFrame.decode(line)
                    val id = obj.optInt("id", -1)
                    if (id >= 0) pending.fulfil(id, obj)
                } catch (e: Exception) {
                    Log.w(TAG, "failed to parse stdout line: $line", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "stdout reader crashed", e)
        } finally {
            Log.w(TAG, "lightpanda process exited (code=${runCatching { p.exitValue() }.getOrNull()})")
            handleDeath()
        }
    }

    private fun handleDeath() {
        val now = System.currentTimeMillis()
        crashTimestamps.addLast(now)
        while (crashTimestamps.isNotEmpty() && now - crashTimestamps.first() > 60_000) {
            crashTimestamps.removeFirst()
        }
        if (crashTimestamps.size >= 3) {
            Log.w(TAG, "entering cool-down (>=3 crashes in 60s)")
            inCoolDown = true
            scope.launch {
                delay(60_000)
                inCoolDown = false
                crashTimestamps.clear()
                Log.i(TAG, "exiting cool-down")
            }
        }
        pending.failAll(RuntimeException("lightpanda subprocess died"))
        toolsListCache = null
        process.set(null)
    }

    fun shutdown() {
        val p = process.getAndSet(null) ?: return
        runCatching { stdin?.close() }
        runCatching { p.destroy() }
        scope.launch {
            delay(2_000)
            if (p.isAlive) p.destroyForcibly()
        }
    }

    companion object {
        private const val TAG = "LightpandaSession"
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

```bash
./gradlew :capapps:lightpanda:assembleDebug
```

Expected: PASS.

- [ ] **Step 3: Wire it into LightpandaApp**

Replace `LightpandaApp.kt`:

```kotlin
package com.llmintentions.lightpanda

import android.app.Application
import com.llmintentions.lightpanda.session.LightpandaSession
import com.llmintentions.lightpanda.session.RootfsLayout
import java.io.File

class LightpandaApp : Application() {
    lateinit var session: LightpandaSession
        private set

    override fun onCreate() {
        super.onCreate()
        val rootfsBase = File(filesDir, "rootfs-bundle")
        val layout = RootfsLayout(rootfsBase)
        session = LightpandaSession(this, layout)
    }
}
```

- [ ] **Step 4: Build**

```bash
./gradlew :capapps:lightpanda:assembleDebug
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/LightpandaSession.kt \
        capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/LightpandaApp.kt
git commit -m "feat(lightpanda): LightpandaSession subprocess manager"
```

---

## Phase 4 — Settings activity for manual testing (pre-Hub)

**Purpose:** A minimal UI inside the CapApp so we can manually spawn lightpanda and call `tools/list` / `goto` / `markdown` without the Hub. Validates subprocess wiring end-to-end before touching Hub code.

### Task 4.1: Build a test UI in CapAppSettingsActivity

**Files:**
- Modify: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/CapAppSettingsActivity.kt`

- [ ] **Step 1: Rewrite the activity**

```kotlin
package com.llmintentions.lightpanda

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.json.JSONObject

class CapAppSettingsActivity : Activity() {
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val status = TextView(this).apply { text = "Lightpanda CapApp — manual test UI" }
        root.addView(status)

        val listToolsBtn = Button(this).apply { text = "tools/list" }
        val gotoBtn = Button(this).apply { text = "goto + markdown (example.com)" }
        val shutdownBtn = Button(this).apply { text = "shutdown subprocess" }
        root.addView(listToolsBtn)
        root.addView(gotoBtn)
        root.addView(shutdownBtn)

        output = TextView(this).apply {
            text = ""
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(output)
        }
        root.addView(scroll)

        setContentView(root)

        val app = application as LightpandaApp

        listToolsBtn.setOnClickListener {
            log("requesting tools/list...")
            uiScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { app.session.getToolList() }
                    log("tools/list OK:\n${result.toString(2)}")
                } catch (e: Exception) {
                    log("tools/list FAILED: ${e.message}")
                }
            }
        }

        gotoBtn.setOnClickListener {
            log("goto + markdown on example.com...")
            uiScope.launch {
                try {
                    val nav = withContext(Dispatchers.IO) {
                        app.session.callTool("goto", JSONObject().put("url", "https://example.com"))
                    }
                    log("goto: $nav")
                    val md = withContext(Dispatchers.IO) {
                        app.session.callTool("markdown", JSONObject())
                    }
                    log("markdown: $md")
                } catch (e: Exception) {
                    log("failed: ${e.message}")
                }
            }
        }

        shutdownBtn.setOnClickListener {
            app.session.shutdown()
            log("shutdown requested")
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun log(msg: String) {
        output.append(msg + "\n\n")
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :capapps:lightpanda:assembleDebug
```

Expected: PASS.

- [ ] **Step 3: Install**

```bash
./gradlew :capapps:lightpanda:installDebug
```

- [ ] **Step 4: Manual test (will fail until Phase 5 — rootfs isn't provisioned)**

Open the app, tap "tools/list". Expected: log line "tools/list FAILED: rootfs not provisioned". Confirms the wiring is in place; we just need the provisioner.

- [ ] **Step 5: Commit**

```bash
git add capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/CapAppSettingsActivity.kt
git commit -m "feat(lightpanda): manual test UI in settings activity"
```

---

## Phase 5 — First-run provisioner

**Purpose:** Download the rootfs tarball, SHA256-verify, unpack. After this phase, tapping the test buttons works end-to-end (sans Hub).

### Task 5.1: `TarZstExtractor` — unpack zstd-compressed tar

**Files:**
- Modify: `capapps/lightpanda/build.gradle.kts` (add zstd-jni)
- Create: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/TarZstExtractor.kt`
- Create: `capapps/lightpanda/src/test/java/com/llmintentions/lightpanda/session/TarZstExtractorTest.kt`

- [ ] **Step 1: Add dependencies**

Edit `capapps/lightpanda/build.gradle.kts`, inside `dependencies { }`, add:

```kotlin
implementation("com.github.luben:zstd-jni:1.5.6-4@aar")
implementation("org.apache.commons:commons-compress:1.26.1")
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.llmintentions.lightpanda.session

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import com.github.luben.zstd.ZstdOutputStream

class TarZstExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `extracts a simple tar zst`() {
        // Build a tar.zst in memory with one file "hello.txt" containing "hi"
        val baos = ByteArrayOutputStream()
        ZstdOutputStream(baos).use { zstd ->
            TarArchiveOutputStream(zstd).use { tar ->
                val entry = TarArchiveEntry("hello.txt")
                entry.size = 2
                tar.putArchiveEntry(entry)
                tar.write("hi".toByteArray())
                tar.closeArchiveEntry()
            }
        }
        val archive = tmp.newFile("test.tar.zst").apply { writeBytes(baos.toByteArray()) }

        val dest = tmp.newFolder("dest")
        TarZstExtractor.extract(archive, dest)

        val extracted = dest.resolve("hello.txt")
        assertTrue(extracted.exists())
        assertEquals("hi", extracted.readText())
    }

    @Test(expected = SecurityException::class)
    fun `rejects path traversal entries`() {
        val baos = ByteArrayOutputStream()
        ZstdOutputStream(baos).use { zstd ->
            TarArchiveOutputStream(zstd).use { tar ->
                val entry = TarArchiveEntry("../evil.txt")
                entry.size = 1
                tar.putArchiveEntry(entry)
                tar.write("x".toByteArray())
                tar.closeArchiveEntry()
            }
        }
        val archive = tmp.newFile("bad.tar.zst").apply { writeBytes(baos.toByteArray()) }
        TarZstExtractor.extract(archive, tmp.newFolder("dest"))
    }
}
```

- [ ] **Step 3: Run — expect FAIL**

```bash
./gradlew :capapps:lightpanda:testDebugUnitTest --tests "*TarZstExtractorTest*"
```

- [ ] **Step 4: Implement**

```kotlin
package com.llmintentions.lightpanda.session

import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream

object TarZstExtractor {
    fun extract(archive: File, destDir: File) {
        if (!destDir.exists()) destDir.mkdirs()
        val destCanonical = destDir.canonicalFile
        archive.inputStream().use { raw ->
            ZstdInputStream(raw).use { zstd ->
                TarArchiveInputStream(zstd).use { tar ->
                    while (true) {
                        val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                        val target = File(destDir, entry.name).canonicalFile
                        if (!target.path.startsWith(destCanonical.path + File.separator) &&
                            target.path != destCanonical.path) {
                            throw SecurityException("tar path traversal: ${entry.name}")
                        }
                        when {
                            entry.isDirectory -> target.mkdirs()
                            entry.isSymbolicLink -> {
                                // Skip symlinks for the minimal v0.1 rootfs — we don't need them.
                                // If the rootfs requires symlinks later, re-enable with traversal checks.
                            }
                            else -> {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out -> tar.copyTo(out) }
                                // Preserve exec bit for binaries (proot, lightpanda).
                                if (entry.mode and 0b001_001_001 != 0) target.setExecutable(true, false)
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Run — expect PASS**

- [ ] **Step 6: Commit**

```bash
git add capapps/lightpanda/build.gradle.kts \
        capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/TarZstExtractor.kt \
        capapps/lightpanda/src/test/java/com/llmintentions/lightpanda/session/TarZstExtractorTest.kt
git commit -m "feat(lightpanda): tar.zst extractor with traversal check"
```

### Task 5.2: `FirstRunProvisioner` — download + verify + unpack

**Files:**
- Create: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/FirstRunProvisioner.kt`

- [ ] **Step 1: Write the class**

```kotlin
package com.llmintentions.lightpanda.session

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class FirstRunProvisioner(
    private val layout: RootfsLayout,
    private val tarballUrl: String = DEFAULT_URL,
    private val tarballSha256: String = DEFAULT_SHA256,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    sealed class Progress {
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : Progress()
        data class Verifying(val file: File) : Progress()
        data class Extracting(val file: File) : Progress()
        data object Done : Progress()
        data class Failed(val reason: String, val cause: Throwable?) : Progress()
    }

    suspend fun provisionIfNeeded(report: (Progress) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (layout.isReady()) {
            report(Progress.Done)
            return@withContext true
        }
        // Clean any partial previous install.
        if (layout.baseDir.exists()) layout.baseDir.deleteRecursively()
        layout.baseDir.mkdirs()

        val archive = File(layout.baseDir, "rootfs.tar.zst")
        try {
            // 1. Download
            val req = Request.Builder().url(tarballUrl).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from $tarballUrl")
                val body = resp.body ?: throw IOException("empty body")
                val total = body.contentLength()
                archive.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf); if (n <= 0) break
                            out.write(buf, 0, n); read += n
                            report(Progress.Downloading(read, total))
                        }
                    }
                }
            }

            // 2. Verify
            report(Progress.Verifying(archive))
            if (!Sha256Verifier.verify(archive, tarballSha256)) {
                val actual = Sha256Verifier.hashFile(archive)
                archive.delete()
                report(Progress.Failed("SHA256 mismatch: got $actual, expected $tarballSha256", null))
                return@withContext false
            }

            // 3. Extract
            report(Progress.Extracting(archive))
            TarZstExtractor.extract(archive, layout.baseDir)
            archive.delete()

            // 4. Validate layout
            if (!layout.prootBinary.exists() || !layout.lightpandaBinary.exists()) {
                report(Progress.Failed("rootfs incomplete after extract", null))
                return@withContext false
            }
            layout.prootBinary.setExecutable(true, false)
            layout.lightpandaBinary.setExecutable(true, false)
            layout.writeMarker()

            report(Progress.Done)
            true
        } catch (e: Exception) {
            Log.w(TAG, "provisioning failed", e)
            report(Progress.Failed(e.message ?: e.javaClass.simpleName, e))
            archive.delete()
            false
        }
    }

    companion object {
        private const val TAG = "FirstRunProvisioner"
        // Update these when a new rootfs tarball is pinned.
        const val DEFAULT_URL = "https://github.com/dreliq9/llm-intentions/releases/download/lightpanda-rootfs-v0.1.0/lightpanda-rootfs-0.1.0-aarch64.tar.zst"
        const val DEFAULT_SHA256 = "REPLACE_ME_WITH_ACTUAL_SHA256"  // filled in via BuildConfig or hard-coded after Task 0.1 step 3

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
```

- [ ] **Step 2: Paste the real SHA256**

Replace `REPLACE_ME_WITH_ACTUAL_SHA256` with the hash you recorded in Task 0.1 Step 3. If you don't have it yet, leave the placeholder — the provisioner will fail loudly on first run, which is the desired signal.

- [ ] **Step 3: Build**

```bash
./gradlew :capapps:lightpanda:assembleDebug
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/FirstRunProvisioner.kt
git commit -m "feat(lightpanda): first-run rootfs provisioner"
```

### Task 5.3: Wire the provisioner into the UI

**Files:**
- Modify: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/CapAppSettingsActivity.kt`

- [ ] **Step 1: Add a provision button and progress reporting**

Replace the activity with:

```kotlin
package com.llmintentions.lightpanda

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.llmintentions.lightpanda.session.FirstRunProvisioner
import com.llmintentions.lightpanda.session.RootfsLayout
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class CapAppSettingsActivity : Activity() {
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        val status = TextView(this).apply { text = "Lightpanda CapApp — manual test UI" }
        val provisionBtn = Button(this).apply { text = "1. Provision rootfs" }
        val listToolsBtn = Button(this).apply { text = "2. tools/list" }
        val gotoBtn = Button(this).apply { text = "3. goto + markdown (example.com)" }
        val shutdownBtn = Button(this).apply { text = "shutdown subprocess" }
        output = TextView(this).apply { setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(output)
        }
        listOf(status, provisionBtn, listToolsBtn, gotoBtn, shutdownBtn, scroll).forEach { root.addView(it) }
        setContentView(root)

        val app = application as LightpandaApp
        val layout = RootfsLayout(File(filesDir, "rootfs-bundle"))
        val provisioner = FirstRunProvisioner(layout)

        provisionBtn.setOnClickListener {
            log("provisioning...")
            uiScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    provisioner.provisionIfNeeded { p ->
                        uiScope.launch { log(p.toString()) }
                    }
                }
                log(if (ok) "provision OK" else "provision FAILED")
            }
        }

        listToolsBtn.setOnClickListener {
            log("tools/list...")
            uiScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { app.session.getToolList() }
                    log("tools/list OK:\n${result.toString(2)}")
                } catch (e: Exception) { log("tools/list FAILED: ${e.message}") }
            }
        }

        gotoBtn.setOnClickListener {
            log("goto + markdown...")
            uiScope.launch {
                try {
                    val nav = withContext(Dispatchers.IO) {
                        app.session.callTool("goto", JSONObject().put("url", "https://example.com"))
                    }
                    log("goto: $nav")
                    val md = withContext(Dispatchers.IO) {
                        app.session.callTool("markdown", JSONObject())
                    }
                    log("markdown: $md")
                } catch (e: Exception) { log("failed: ${e.message}") }
            }
        }

        shutdownBtn.setOnClickListener {
            app.session.shutdown()
            log("shutdown requested")
        }
    }

    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }

    private fun log(msg: String) { output.append(msg + "\n\n") }
}
```

- [ ] **Step 2: Install**

```bash
./gradlew :capapps:lightpanda:installDebug
```

- [ ] **Step 3: Manual test**

Open the app. Tap "1. Provision rootfs". Expected: log lines "Downloading(read, total)" → "Verifying" → "Extracting" → "Done" → "provision OK".

Then tap "2. tools/list". Expected: JSON tool list.

Then tap "3. goto + markdown (example.com)". Expected: CallOutcome.Ok for both with the example.com content.

If provision fails, check:
- Network connectivity on the phone
- The `DEFAULT_URL` / `DEFAULT_SHA256` constants match a tarball actually hosted at that URL
- If the Release hasn't been uploaded yet, use `adb push` to manually stage `rootfs-bundle/` and test the downstream flow

- [ ] **Step 4: Commit**

```bash
git add capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/CapAppSettingsActivity.kt
git commit -m "feat(lightpanda): wire provisioner into settings activity"
```

---

## Phase 6 — Hub integration (CapApp side)

**Purpose:** Replace the Phase-1 stub services with real implementations: ListTools receiver, CommandGatewayService, AIDL bound service. At the end of this phase the CapApp speaks the full protocol; Hub-side changes come in Phase 7.

### Task 6.1: AIDL interface

**Files:**
- Create: `capapps/lightpanda/src/main/aidl/com/llmintentions/lightpanda/ILightpandaBridge.aidl`

- [ ] **Step 1: Write the AIDL**

```aidl
package com.llmintentions.lightpanda;

interface ILightpandaBridge {
    /** Called by Hub on bind to keep the subprocess warm. No-op if already alive. */
    void keepAlive();
    /** True once the subprocess has responded to tools/list at least once. */
    boolean isReady();
}
```

- [ ] **Step 2: Build — AGP will generate the Kotlin stub**

```bash
./gradlew :capapps:lightpanda:assembleDebug
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add capapps/lightpanda/src/main/aidl/
git commit -m "feat(lightpanda): AIDL for bound liveness service"
```

### Task 6.2: Implement `LightpandaBridgeService`

**Files:**
- Modify: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/LightpandaBridgeService.kt`

- [ ] **Step 1: Rewrite**

```kotlin
package com.llmintentions.lightpanda

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*

class LightpandaBridgeService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val binder = object : ILightpandaBridge.Stub() {
        override fun keepAlive() {
            val app = application as LightpandaApp
            scope.launch { runCatching { app.session.ensureSpawned() } }
        }

        override fun isReady(): Boolean {
            return false // Session will report ready after first tools/list; for v0.1 just return false
                        // if subprocess hasn't spawned, true otherwise.
                        // Simplified: check if session has a cached tool list.
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
```

- [ ] **Step 2: Expose an `isReady` hook on LightpandaSession**

Edit `LightpandaSession.kt`: add public method

```kotlin
fun isReady(): Boolean = process.get()?.isAlive == true && toolsListCache != null
```

- [ ] **Step 3: Use it in the binder**

```kotlin
override fun isReady(): Boolean = (application as LightpandaApp).session.isReady()
```

- [ ] **Step 4: Build**

```bash
./gradlew :capapps:lightpanda:assembleDebug
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/LightpandaBridgeService.kt \
        capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/LightpandaSession.kt
git commit -m "feat(lightpanda): bound bridge service with AIDL"
```

### Task 6.3: Implement `LightpandaListToolsReceiver`

**Files:**
- Modify: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/LightpandaListToolsReceiver.kt`

The CapApp protocol allows dynamic tool discovery via `ACTION_LIST_TOOLS`. The receiver responds via a broadcast matching the request_id, same pattern as `ACTION_TOOL_RESULT`.

- [ ] **Step 1: Rewrite**

```kotlin
package com.llmintentions.lightpanda

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class LightpandaListToolsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val requestId = intent.getStringExtra("request_id") ?: ""
        val app = context.applicationContext as LightpandaApp

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            val reply = Intent("com.llmintentions.ACTION_LIST_TOOLS_RESULT").apply {
                setPackage("com.llmintentions.hub")
                putExtra("request_id", requestId)
                putExtra("source_package", context.packageName)
            }
            try {
                val toolList = app.session.getToolList()
                val tools = toolList.optJSONArray("tools") ?: JSONArray()
                reply.putExtra("success", true)
                reply.putExtra("namespace", "browser")
                reply.putExtra("tools", tools.toString())
            } catch (e: Exception) {
                reply.putExtra("success", false)
                reply.putExtra("error", e.message ?: e.javaClass.simpleName)
            } finally {
                context.sendBroadcast(reply, "com.llmintentions.permission.MCP_TOOL")
                scope.cancel()
                pending.finish()
            }
        }
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :capapps:lightpanda:assembleDebug
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/LightpandaListToolsReceiver.kt
git commit -m "feat(lightpanda): dynamic tool-list broadcast receiver"
```

### Task 6.4: Implement `CommandGatewayService` with payload offload

**Files:**
- Create: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/PayloadOffloader.kt`
- Modify: `capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/CommandGatewayService.kt`

- [ ] **Step 1: Create the payload offloader**

```kotlin
package com.llmintentions.lightpanda.session

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PayloadOffloader {
    private const val INLINE_THRESHOLD_BYTES = 256 * 1024
    const val AUTHORITY = "com.llmintentions.lightpanda.files"

    /**
     * Convert a Lightpanda MCP result into the CapApp protocol's content-array form.
     * If the serialized size exceeds INLINE_THRESHOLD_BYTES, write the payload to a
     * private file and emit a {type:"resource", uri:"content://..."} entry.
     */
    fun toContentArray(ctx: Context, requestId: String, result: JSONObject): JSONArray {
        val content = result.optJSONArray("content")
        if (content != null) {
            // Lightpanda already returned MCP-shaped content; offload large entries.
            return offloadContent(ctx, requestId, content)
        }
        // Otherwise wrap the raw result JSON as text.
        val text = result.toString()
        return if (text.length <= INLINE_THRESHOLD_BYTES) {
            JSONArray().put(JSONObject().put("type", "text").put("text", text))
        } else {
            val uri = writeAndGrant(ctx, requestId, "result.json", text.toByteArray())
            JSONArray().put(
                JSONObject().put("type", "resource").put("uri", uri.toString())
            )
        }
    }

    private fun offloadContent(ctx: Context, requestId: String, content: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until content.length()) {
            val entry = content.getJSONObject(i)
            when (entry.optString("type")) {
                "text" -> {
                    val text = entry.optString("text", "")
                    out.put(
                        if (text.length <= INLINE_THRESHOLD_BYTES) entry
                        else {
                            val uri = writeAndGrant(ctx, requestId, "text-$i.txt", text.toByteArray())
                            JSONObject().put("type", "resource").put("uri", uri.toString())
                        }
                    )
                }
                "image" -> {
                    val data = entry.optString("data", "")
                    val bytes = try { android.util.Base64.decode(data, android.util.Base64.DEFAULT) } catch (e: Exception) { ByteArray(0) }
                    if (bytes.size <= INLINE_THRESHOLD_BYTES) out.put(entry)
                    else {
                        val mime = entry.optString("mimeType", "image/png")
                        val ext = if (mime.endsWith("png")) "png" else "bin"
                        val uri = writeAndGrant(ctx, requestId, "image-$i.$ext", bytes)
                        out.put(
                            JSONObject().put("type", "resource").put("uri", uri.toString())
                                .put("mimeType", mime)
                        )
                    }
                }
                else -> out.put(entry)
            }
        }
        return out
    }

    private fun writeAndGrant(ctx: Context, requestId: String, fileName: String, bytes: ByteArray): Uri {
        val dir = File(ctx.filesDir, "tool-outputs").apply { mkdirs() }
        val f = File(dir, "$requestId-$fileName")
        f.writeBytes(bytes)
        return FileProvider.getUriForFile(ctx, AUTHORITY, f)
    }

    /** Called after the Hub has read (or the TTL expires) to release disk. */
    fun cleanup(ctx: Context, requestId: String) {
        val dir = File(ctx.filesDir, "tool-outputs")
        if (!dir.exists()) return
        dir.listFiles { f -> f.name.startsWith("$requestId-") }?.forEach { it.delete() }
    }
}
```

- [ ] **Step 2: Rewrite `CommandGatewayService`**

```kotlin
package com.llmintentions.lightpanda

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.llmintentions.lightpanda.session.LightpandaSession
import com.llmintentions.lightpanda.session.PayloadOffloader
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class CommandGatewayService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: run { stopSelfResult(startId); return START_NOT_STICKY }
        val toolName = intent.getStringExtra("tool_name")
        val paramsStr = intent.getStringExtra("params")
        val requestId = intent.getStringExtra("request_id") ?: ""
        val timeoutMs = intent.getLongExtra("extra_timeout_ms", 60_000L)

        if (toolName == null) {
            replyError(requestId, "missing tool_name")
            stopSelfResult(startId); return START_NOT_STICKY
        }

        val params = try {
            if (paramsStr.isNullOrEmpty()) JSONObject() else JSONObject(paramsStr)
        } catch (e: Exception) {
            replyError(requestId, "invalid params JSON: ${e.message}")
            stopSelfResult(startId); return START_NOT_STICKY
        }

        val app = application as LightpandaApp
        scope.launch {
            val outcome = app.session.callTool(toolName, params, timeoutMs)
            when (outcome) {
                is LightpandaSession.CallOutcome.Ok -> {
                    val content = PayloadOffloader.toContentArray(this@CommandGatewayService, requestId, outcome.result)
                    replySuccess(requestId, content)
                }
                is LightpandaSession.CallOutcome.Err -> replyError(requestId, "${outcome.code}: ${outcome.message}")
            }
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun replySuccess(requestId: String, content: JSONArray) {
        val result = JSONObject().put("success", true).put("content", content)
        broadcast(requestId, result)
    }

    private fun replyError(requestId: String, error: String) {
        val result = JSONObject().put("success", false).put("error", error)
        broadcast(requestId, result)
    }

    private fun broadcast(requestId: String, result: JSONObject) {
        val reply = Intent("com.llmintentions.ACTION_TOOL_RESULT").apply {
            setPackage("com.llmintentions.hub")
            putExtra("request_id", requestId)
            putExtra("result", result.toString())
        }
        sendBroadcast(reply, "com.llmintentions.permission.MCP_TOOL")
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
```

- [ ] **Step 3: Build**

```bash
./gradlew :capapps:lightpanda:assembleDebug
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/session/PayloadOffloader.kt \
        capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/CommandGatewayService.kt
git commit -m "feat(lightpanda): command gateway with payload offload"
```

### Task 6.5: Sanity-check the whole CapApp on-device

- [ ] **Step 1: Install the updated APK**

```bash
./gradlew :capapps:lightpanda:installDebug
```

- [ ] **Step 2: Verify the manual test UI still works**

Open the app. Tap provision → tools/list → goto + markdown. Expected: no regressions from Phase 5 behavior.

- [ ] **Step 3: Verify broadcast delivery with adb**

From the Mac:

```bash
adb shell am start-foreground-service \
  -a com.llmintentions.ACTION_MCP_TOOL \
  -n com.llmintentions.lightpanda/.CommandGatewayService \
  --es tool_name goto \
  --es params '{"url":"https://example.com"}' \
  --es request_id test-1
```

Expected: no crash.

```bash
adb logcat -d | grep -i lightpanda | tail -30
```

Expected: log lines showing the spawn + tool call.

- [ ] **Step 4: Commit any fixes (none expected)**

---

## Phase 7 — Hub-side changes

**Purpose:** Teach the `:llm-intentions` Hub module about two things:
1. `com.llmintentions.mcp.requires-liveness-bind` metadata flag — bind to the bound service at discovery.
2. `resource`-typed content entries that point at `content://` URIs — fetch the bytes, forward them over HTTP MCP.

This assumes the protocol's dynamic-discovery `ACTION_LIST_TOOLS` flow already works in the Hub. If it does not, add that first (one subtask).

### Task 7.1: Survey the Hub code

- [ ] **Step 1: Identify the Hub discovery + routing files**

```bash
grep -rn "ACTION_LIST_TOOLS\|ACTION_MCP_TOOL\|ACTION_TOOL_RESULT\|mcp.tools\|requires-liveness-bind" llm-intentions/src/
```

Report back:
- Which class handles CapApp discovery (probably `CapAppDiscovery.kt` or similar)
- Which class routes tool calls (probably `IntentToolRouter.kt` or similar)
- Whether dynamic discovery via `ACTION_LIST_TOOLS` is already implemented

The rest of Phase 7 branches on what you find. The remainder of this plan assumes:
- Discovery exists, static `mcp_tools` meta-data works, dynamic via broadcast is partially stubbed or missing
- Routing exists, forwards results over SSE to Claude Code

If reality differs, adapt task scope accordingly.

### Task 7.2: Implement dynamic discovery (if missing)

**Files (approximate — confirm from Task 7.1):**
- Modify: `llm-intentions/src/main/java/com/androidmcp/hub/discovery/CapAppDiscovery.kt`
- Modify: `llm-intentions/src/main/java/com/androidmcp/hub/discovery/DiscoveryResultReceiver.kt` (new — broadcast receiver for ACTION_LIST_TOOLS_RESULT)

- [ ] **Step 1: Add dynamic-discovery branch**

When scanning a discovered CapApp's `<service>` metadata, check for the absence of a static `mcp_tools` XML resource. If absent, send an `ACTION_LIST_TOOLS` ordered broadcast with a request_id; register a `BroadcastReceiver` for `ACTION_LIST_TOOLS_RESULT` that parses the response and populates the registry.

Sketch:

```kotlin
fun refreshTools(capAppPackage: String) {
    val intent = Intent("com.llmintentions.ACTION_LIST_TOOLS").apply {
        setPackage(capAppPackage)
        putExtra("request_id", UUID.randomUUID().toString())
    }
    context.sendBroadcast(intent, "com.llmintentions.permission.MCP_TOOL")
    // Results arrive via DiscoveryResultReceiver → updates ToolRegistry
}
```

Exact integration depends on existing Hub structure — adapt to match.

- [ ] **Step 2: Build + install Hub**

```bash
./gradlew :llm-intentions:installDebug
```

- [ ] **Step 3: Verify via log**

```bash
adb logcat -d | grep -i "LightpandaCapApp\|lightpanda\|discovery" | tail -50
```

Expected: Hub logs discovery of Lightpanda, receives tool list, registers under `browser.*`.

- [ ] **Step 4: Commit**

```bash
git add llm-intentions/src/
git commit -m "feat(hub): dynamic tool discovery via ACTION_LIST_TOOLS"
```

### Task 7.3: Bind to CapApps flagged `requires-liveness-bind`

**Files:**
- Modify: Hub discovery / service-management code

- [ ] **Step 1: Extend discovery to read the flag**

In the CapApp discovery loop, when reading service meta-data, check for:

```kotlin
val requiresBind = serviceInfo.metaData?.getBoolean(
    "com.llmintentions.mcp.requires-liveness-bind", false
) ?: false
```

If true, look up a service with the same package suffix `.LightpandaBridgeService` (or more generally, any service declaring the same flag) and:

```kotlin
val intent = Intent().apply {
    component = ComponentName(capAppPackage, "$capAppPackage.LightpandaBridgeService")
}
context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

(For a fully generic implementation, scan the package's services for a component name matching a convention like `*BridgeService`, or add a second intent-filter action — design choice.)

- [ ] **Step 2: Cache the ServiceConnection per CapApp**

Track connections in a map `Map<String, ServiceConnection>` on the Hub so they survive for the Hub's lifetime.

- [ ] **Step 3: Invoke `keepAlive()` on connection**

```kotlin
override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
    val bridge = ILightpandaBridge.Stub.asInterface(service)
    runCatching { bridge.keepAlive() }
}
```

Note: the Hub needs the AIDL file too. Copy `ILightpandaBridge.aidl` to `llm-intentions/src/main/aidl/com/llmintentions/lightpanda/` so the Hub can generate the stub on its side. Keep the two .aidl files textually identical.

- [ ] **Step 4: Build + install Hub**

```bash
./gradlew :llm-intentions:installDebug
```

- [ ] **Step 5: Verify with logcat**

Expected logs:
- Hub discovers `com.llmintentions.lightpanda`
- Hub reads `requires-liveness-bind=true` metadata
- Hub `bindService` call to `.LightpandaBridgeService`
- Hub `keepAlive()` AIDL call lands, Lightpanda session spawns

- [ ] **Step 6: Commit**

```bash
git add llm-intentions/src/main/
git commit -m "feat(hub): bind to CapApps flagged requires-liveness-bind"
```

### Task 7.4: Teach result-handling to follow `resource` URIs

**Files:**
- Modify: Hub tool-result broadcast receiver (e.g., `ToolResultReceiver.kt`)

- [ ] **Step 1: Parse the `content` array**

After parsing the `result` JSON, for each entry in `content`:

- `type: "text"` → pass text through unchanged into the MCP response
- `type: "image"` → pass through (already small, inline base64)
- `type: "resource"` → fetch:

```kotlin
val uri = Uri.parse(entry.getString("uri"))
val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
    ?: throw IOException("resource uri not readable: $uri")
// Determine whether to send as text or as a binary blob based on mimeType / heuristics
val isText = entry.optString("mimeType", "application/json").startsWith("text/") ||
             entry.optString("mimeType", "").contains("json")
val replacement = if (isText) {
    JSONObject().put("type", "text").put("text", String(bytes, Charsets.UTF_8))
} else {
    JSONObject().put("type", "image")
        .put("mimeType", entry.optString("mimeType", "application/octet-stream"))
        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
}
```

- [ ] **Step 2: After reading, trigger cleanup**

Send a broadcast back to the CapApp to clean up:

```kotlin
val cleanup = Intent("com.llmintentions.ACTION_CLEANUP_TOOL_OUTPUT").apply {
    setPackage(capAppPackage)
    putExtra("request_id", requestId)
}
context.sendBroadcast(cleanup, "com.llmintentions.permission.MCP_TOOL")
```

- [ ] **Step 3: In the CapApp, add a receiver for cleanup**

Create `CleanupReceiver.kt` in the lightpanda CapApp:

```kotlin
package com.llmintentions.lightpanda

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.llmintentions.lightpanda.session.PayloadOffloader

class CleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra("request_id") ?: return
        PayloadOffloader.cleanup(context, requestId)
    }
}
```

Register in the CapApp manifest:

```xml
<receiver
    android:name=".CleanupReceiver"
    android:exported="true"
    android:permission="com.llmintentions.permission.MCP_TOOL">
    <intent-filter>
        <action android:name="com.llmintentions.ACTION_CLEANUP_TOOL_OUTPUT" />
    </intent-filter>
</receiver>
```

- [ ] **Step 4: Build both APKs + install**

```bash
./gradlew :llm-intentions:installDebug :capapps:lightpanda:installDebug
```

- [ ] **Step 5: Commit**

```bash
git add llm-intentions/src/ \
        capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/CleanupReceiver.kt \
        capapps/lightpanda/src/main/AndroidManifest.xml
git commit -m "feat: resource-URI content type + cleanup handshake"
```

---

## Phase 8 — End-to-end verification

**Purpose:** Confirm the full Claude Code → Hub → CapApp → Lightpanda loop works on-device.

### Task 8.1: Claude Code in proot → Hub → Lightpanda smoke test

- [ ] **Step 1: Start Claude Code in the Termux proot on the phone**

From a Termux session on the phone (or adb shell into Termux):

```bash
proot-distro login ubuntu -- claude
```

(Or whatever your exact Claude Code launcher is.)

- [ ] **Step 2: Verify Hub is reachable**

Inside the Claude Code MCP config:

```json
{
  "mcpServers": {
    "hub": {
      "type": "http",
      "url": "http://127.0.0.1:8381/mcp"
    }
  }
}
```

- [ ] **Step 3: Call `tools/list`**

In Claude Code, ask the agent: "list all MCP tools." Expected: the response includes `browser.goto`, `browser.markdown`, `browser.screenshot`, etc.

- [ ] **Step 4: Call `browser.goto` + `browser.markdown`**

Ask: "use the browser tools to navigate to https://example.com and return the page markdown."

Expected: within ~5 seconds, Claude Code returns the "Example Domain" content.

- [ ] **Step 5: Call `browser.screenshot` (large payload path)**

Ask: "take a screenshot of https://example.com and describe it."

Expected: the screenshot round-trips via the FileProvider content URI path and Claude Code receives the image bytes. Verify by looking at logcat:

```bash
adb logcat -d | grep -i "PayloadOffloader\|tool-outputs\|resource" | tail -30
```

Expected: see a `writeAndGrant` log line for the screenshot, then a Hub-side fetch.

- [ ] **Step 6: Commit any fixes**

---

## Phase 9 — Stability polish

### Task 9.1: Concurrency stress test

- [ ] **Step 1: Add a stress button to the test activity**

Add a button that fires 10 concurrent `browser.goto` calls to different URLs. Expected: all 10 complete within ~15 s, no crashes, no mixed-up results (id multiplexing correct).

```kotlin
stressBtn.setOnClickListener {
    val urls = listOf(
        "https://example.com", "https://example.org", "https://www.iana.org",
        "https://httpbin.org/get", "https://duckduckgo.com", "https://news.ycombinator.com",
        "https://en.wikipedia.org/wiki/HTTP", "https://www.mozilla.org",
        "https://github.com", "https://www.google.com"
    )
    val start = System.currentTimeMillis()
    uiScope.launch {
        val results = urls.map { url ->
            async(Dispatchers.IO) {
                app.session.callTool("goto", JSONObject().put("url", url))
            }
        }.awaitAll()
        log("10 concurrent goto: ${System.currentTimeMillis() - start}ms, results=${results.count { it is LightpandaSession.CallOutcome.Ok }}")
    }
}
```

- [ ] **Step 2: Run on-device, observe**

Tap stress. Watch logs. Expected: ~10 successes, total ≤15 s. If failures appear, look for id multiplexing bugs or stdin write interleaving.

- [ ] **Step 3: Commit**

```bash
git add capapps/lightpanda/src/main/java/com/llmintentions/lightpanda/CapAppSettingsActivity.kt
git commit -m "test(lightpanda): concurrency stress button"
```

### Task 9.2: Crash-loop guard verification

- [ ] **Step 1: Add a "kill subprocess" button**

```kotlin
killBtn.setOnClickListener {
    // Kills the Lightpanda process directly — simulates a crash
    // (Implement by calling process.destroy() via a debug method on session; expose behind BuildConfig.DEBUG)
}
```

- [ ] **Step 2: Kill three times within 60 s**

Tap the kill button 3 times rapidly. Expected: cool-down engages; 4th tool call returns `CallOutcome.Err(-32099, "browser in cool-down after crash loop")`. After 60 s, cool-down releases.

- [ ] **Step 3: Commit**

```bash
git commit -m "test(lightpanda): crash-loop guard verification"
```

### Task 9.3: Battery-idle smoke test

- [ ] **Step 1: Provision, then leave the phone idle for an hour**

- [ ] **Step 2: Check battery stats**

```bash
adb shell dumpsys batterystats --charged com.llmintentions.lightpanda
```

Expected: <1 % battery over an idle hour. If higher, the subprocess is spinning — investigate.

- [ ] **Step 3: If fine, no commit needed. If issue, fix.**

---

## Phase 10 — Ship v0.1

### Task 10.1: Release gate checklist

- [ ] Phase 0 M0 spike passed
- [ ] All Phase 1–6 unit tests pass: `./gradlew :capapps:lightpanda:test`
- [ ] Manual test UI works end-to-end: provision → tools/list → goto + markdown → screenshot
- [ ] Full end-to-end via Claude Code in Termux → Hub → CapApp → Lightpanda works for at least: `browser.goto`, `browser.markdown`, `browser.screenshot`, `browser.click`, `browser.evaluate`
- [ ] Stress test (Task 9.1) passes — 10 concurrent calls, all complete, correct id routing
- [ ] Crash-loop guard engages after 3 crashes in 60 s (Task 9.2)
- [ ] Battery idle <1% /hour (Task 9.3)
- [ ] Rootfs tarball is hosted at the pinned URL, SHA256 matches the one in `FirstRunProvisioner.DEFAULT_SHA256`
- [ ] AGPL-3.0 source is public at `github.com/dreliq9/llm-intentions` (or equivalent), with a `LICENSE` file and the Lightpanda source pointer

### Task 10.2: Tag and release

- [ ] **Step 1: Bump versionName if needed**

In `capapps/lightpanda/build.gradle.kts`, set `versionName = "0.1.0"` and `versionCode = 1` if not already.

- [ ] **Step 2: Build release APK**

```bash
./gradlew :capapps:lightpanda:assembleRelease
```

- [ ] **Step 3: Tag**

```bash
git tag lightpanda-capapp-v0.1.0
git push origin lightpanda-capapp-v0.1.0
```

- [ ] **Step 4: Upload APK to GitHub Release**

Via `gh release`:

```bash
gh release create lightpanda-capapp-v0.1.0 \
    --title "Lightpanda CapApp v0.1.0" \
    --notes "First release. See docs/superpowers/specs/2026-04-22-lightpanda-capapp-design.md for design." \
    capapps/lightpanda/build/outputs/apk/release/*.apk
```

---

## Self-review

**Spec coverage:**
- §1 Goal → Phase 8 Task 8.1 validates the "done" definition
- §2 Architecture → Phases 1, 3, 6, 7 build every component
- §3 Module structure → Task 1.1, 1.2, 1.3
- §4.1 CommandGatewayService → Task 6.4
- §4.2 LightpandaBridgeService → Task 6.2
- §4.3 LightpandaSession → Task 3.1 + prerequisites in Phase 2
- §4.4 ListToolsReceiver → Task 6.3
- §4.5 LightpandaFileProvider → Tasks 1.2 + 6.4 (via PayloadOffloader)
- §4.6 FirstRunProvisioner → Phase 5
- §5 Tool invocation flow → Task 6.4 + Phase 7 + Task 8.1
- §6 Lifecycle → Task 6.2 keepAlive + Task 7.3 Hub-side bind + Task 3.1 shutdown
- §7 First-run provisioning → Phase 5
- §8 Large payloads → Task 6.4 PayloadOffloader
- §9 Licensing → Task 10.1 gate item
- §10 Milestones → exactly mirrored in phases 0–4 with polish split into 9
- §11 Spikes → rootfs base + screenshot fidelity will be resolved during Phase 0 / Phase 8; Lightpanda release cadence + pin strategy = Task 10.2

**Placeholders:** `DEFAULT_SHA256 = "REPLACE_ME_WITH_ACTUAL_SHA256"` is intentional — the real hash comes from Task 0.1 Step 3 and is filled in before first provisioning. Noted at Task 5.2 Step 2.

**Open-ended items that depend on the current Hub code** are flagged in Task 7.1 and Tasks 7.2–7.4 branch on the findings. This is honest — I don't know the exact current state of the Hub module — and the spec's §11 already calls out "Hub change for liveness bind" as an unknown.

No other placeholders. Type names and method signatures are consistent across tasks.

---

## Next step

Execute the plan. See the header for execution-mode options.
