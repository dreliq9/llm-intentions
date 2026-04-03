# CapApp Template

A minimal starter project for building an LLM Intentions CapApp (Capability App).

## What is a CapApp?

A CapApp is an Android app with no UI that exposes MCP tools to LLMs via the Hub. Install it, call `hub.refresh`, and your tools are available to any MCP client.

## Quick Start

### 1. Create a new Android project

Use Android Studio or your preferred setup. Target API 26+ (`minSdk 26`, `targetSdk 35`, `compileSdk 35`).

### 2. Define the custom permission

In `AndroidManifest.xml`, declare a signature-level permission so only apps signed with the same key (i.e. the Hub) can invoke your tools:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Only apps with the same signing key can call this service -->
    <permission
        android:name="com.llmintentions.permission.MCP_TOOL"
        android:protectionLevel="signature" />

    <application>
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
    </application>
</manifest>
```

### 3. Define your tools

Create `res/xml/mcp_tools.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<mcp-tools namespace="mytool" version="1.0">
    <tool name="hello"
          description="Say hello to someone">
        <param name="name" type="string" required="true"
               description="Name to greet" />
    </tool>
    <tool name="add"
          description="Add two numbers">
        <param name="a" type="number" required="true"
               description="First number" />
        <param name="b" type="number" required="true"
               description="Second number" />
    </tool>
</mcp-tools>
```

### 4. Implement the service

Create `CommandGatewayService.kt`:

```kotlin
import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executors

class CommandGatewayService : Service() {

    private val executor = Executors.newCachedThreadPool()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_NOT_STICKY

        val toolName = intent.getStringExtra("tool_name")
        val paramsStr = intent.getStringExtra("params")
        val requestId = intent.getStringExtra("request_id") ?: ""

        if (toolName == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        // Handle off the main thread to avoid ANR
        executor.execute {
            val params = try {
                if (paramsStr != null) JSONObject(paramsStr) else JSONObject()
            } catch (e: JSONException) {
                sendResult(requestId, errorResult("Invalid params JSON: ${e.message}"))
                stopSelfResult(startId)
                return@execute
            }

            val result = when (toolName) {
                "hello" -> handleHello(params)
                "add" -> handleAdd(params)
                else -> errorResult("Unknown tool: $toolName")
            }

            sendResult(requestId, result)
            stopSelfResult(startId)
        }

        return START_NOT_STICKY
    }

    private fun sendResult(requestId: String, result: JSONObject) {
        val resultIntent = Intent("com.llmintentions.ACTION_TOOL_RESULT").apply {
            setPackage("com.llmintentions.hub")
            putExtra("request_id", requestId)
            putExtra("result", result.toString())
        }
        sendBroadcast(resultIntent, "com.llmintentions.permission.MCP_TOOL")
    }

    private fun handleHello(params: JSONObject): JSONObject {
        val name = params.optString("name", "")
        if (name.isEmpty()) return errorResult("Missing required parameter: name")
        return successResult("Hello, $name!")
    }

    private fun handleAdd(params: JSONObject): JSONObject {
        if (!params.has("a") || !params.has("b")) {
            return errorResult("Missing required parameters: a, b")
        }
        return try {
            val a = params.getDouble("a")
            val b = params.getDouble("b")
            successResult("${a + b}")
        } catch (e: JSONException) {
            errorResult("Parameters a and b must be numbers")
        }
    }

    private fun successResult(text: String) = JSONObject().apply {
        put("success", true)
        put("content", JSONArray().put(JSONObject().apply {
            put("type", "text")
            put("text", text)
        }))
    }

    private fun errorResult(msg: String) = JSONObject().apply {
        put("success", false)
        put("error", msg)
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

### 5. Build and install

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 6. Register with Hub

Call `hub.refresh` from your MCP client. Your tools will appear as `mytool.hello` and `mytool.add`.

## Tips

- **No launcher activity needed** — CapApps don't need a UI
- **Request your own permissions** — if your CapApp needs storage, contacts, etc., declare and request them in the manifest
- **Multiple namespaces** — a single CapApp can expose tools under multiple namespaces for logical grouping
- **Dynamic tools** — implement the `ACTION_LIST_TOOLS` intent handler to register tools at runtime

## Examples

See the reference CapApps in this repo:
- [Files CapApp](https://github.com/dreliq9/llm-intentions) — file system operations
- [Notify CapApp](https://github.com/dreliq9/llm-intentions) — notification management
- [People CapApp](https://github.com/dreliq9/llm-intentions) — contacts and calendar
