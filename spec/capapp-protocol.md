# CapApp Protocol Specification

**Version:** 1.0.0-draft  
**Date:** 2026-08-06  
**Status:** Binder v1 implemented for bundled CapApps; Intent v0 retained only as Hub compatibility for older installed APKs.

## Abstract

A **CapApp** is an independently installable Android application that exposes typed capabilities to the LLM Intentions Hub. The Hub aggregates those capabilities into its MCP tool surface.

CapApp Protocol v1 replaces the prototype's unauthenticated started-service + broadcast callback path with authenticated Android Binder IPC. Discovery remains package-visible metadata; discovery does **not** grant execution authority.

## 1. Architecture

```text
MCP client / relay
       |
       v
Intentions Hub
       |
       | authenticated Binder v1
       v
    CapApp APK
       |
       v
Android capability / app-owned data
```

The CapApp owns its Android permissions and data access. The Hub may request a capability, but the CapApp authenticates the local caller before executing it. Separately, the Hub's localhost MCP listener is authenticated; otherwise another APK could invoke the trusted Hub as an indirect deputy.

## 2. Protocol versions

| Version | Transport | Trust model | Status |
| --- | --- | --- | --- |
| v1 | bound Binder/AIDL + Binder callback | per-transaction package + signing identity | preferred/default |
| v0 | `startService()` + broadcast reply | no sufficient caller authentication | compatibility for older APKs only |

The Hub discovers v1 first. A service discovered through v1 is not duplicated through v0.

The SDK fails closed: `ToolAppService.legacyIntentProtocolEnabled` defaults to `false`. A subclass must deliberately opt in to v0 compatibility.

## 3. Binder v1 discovery

A v1 CapApp declares an exported service with the v1 bind action and protocol metadata:

```xml
<service
    android:name=".MyToolService"
    android:exported="true">
    <intent-filter>
        <action android:name="com.androidmcp.capapp.BIND_V1" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>

    <meta-data android:name="com.androidmcp.TOOL_APP" android:value="true" />
    <meta-data android:name="com.androidmcp.NAMESPACE" android:value="myapp" />
    <meta-data android:name="com.androidmcp.PROTOCOL_VERSION" android:value="1" />
</service>
```

The Hub discovers matching services with `PackageManager.queryIntentServices()`, reads only non-secret manifest metadata, then binds explicitly to the resolved `ComponentName`.

`NAMESPACE` is the prefix used in the Hub's MCP registry, for example `myapp.search`.

## 4. AIDL surface

The SDK defines:

```text
ICapAppService
  getDescriptorJson() -> String
  listTools(ICapAppCallback)
  execute(requestId, toolName, argumentsJson, ICapAppCallback)

ICapAppCallback
  onTools(toolsJson)
  onResult(requestId, resultJson)
  onError(requestId, code, message)
```

Tool execution is asynchronous. Slow capabilities therefore do not hold a Binder thread for the duration of the operation.

There is no caller-selected `replyTo` package in v1. Results return over the callback Binder object supplied by the authenticated caller.

## 5. Caller authentication

### 5.1 Transaction identity

Every v1 service method authenticates the caller **while the Binder transaction is being handled**, before dispatching work to another coroutine or thread.

The service captures `Binder.getCallingUid()` and resolves/checks the installed caller through Android package/signing APIs. The UID is only an identity lookup key; it is not the trust decision by itself.

### 5.2 Default first-party policy

`ToolAppService` currently defaults to `OfficialHubSameSignerTrustPolicy`.

A caller is trusted only when either:

- it is the CapApp's own UID; or
- the calling UID resolves to the official Hub package `com.llmintentions` **and** Android reports a matching signing identity between Hub and CapApp.

Both package identity and signer are required. Same-signer-only trust is intentionally insufficient because unrelated development apps may share a debug signing key.

### 5.3 Third-party trust

Third-party CapApps will normally be signed independently and therefore require explicit pairing. The intended trust record binds at least:

```text
Hub package name
Hub signing certificate digest / signing lineage
first-approved timestamp
last-seen timestamp
protocol version
```

A third-party CapApp must not weaken authentication to a package-name-only check. The user must be able to inspect and revoke the paired Hub identity.

## 6. `ToolAppService`

A new CapApp normally extends the SDK base class without enabling legacy IPC:

```kotlin
class MyToolService : ToolAppService() {
    override fun onCreateTools(registry: ToolRegistry) {
        MyToolRegistrar.register(registry, applicationContext)
    }
}
```

The base class:

1. builds the in-memory `ToolRegistry`;
2. exposes Binder v1 from `onBind()` only for `com.androidmcp.capapp.BIND_V1`;
3. authenticates every Binder transaction before performing work;
4. serializes tool descriptors over the callback;
5. executes tools asynchronously and returns `ToolCallResult` JSON over Binder;
6. converts uncaught handler exceptions into the canonical failure envelope;
7. rejects v0 started-service invocation unless a subclass explicitly opts in.

## 7. Tool descriptors

Tool registration continues to use the common registry DSL:

```kotlin
registry.textTool(
    name = "search",
    description = "Search app-owned data",
    params = jsonSchema {
        string("query", "Search query")
    },
    metadata = toolMetadata {
        destructive = false
        idempotent = true
        latencyClass = LatencyClass.FAST
        permission("ANDROID_PERMISSION_NAME")
        failureMode(
            pattern = "permission|SecurityException",
            hint = "Grant the required Android permission.",
        )
    },
) { args ->
    doWork(args)
}
```

The MCP-visible descriptor includes standard fields such as `name`, `description`, `inputSchema`, `outputSchema`, and annotations. Rich LLM Intentions policy metadata is expanded in the H2 policy/consent layer.

## 8. Invocation flow

For a Binder v1 CapApp:

1. MCP client calls `tools/call` on the Hub through an authenticated local or relay transport.
2. Hub resolves `namespace.tool` to the discovered CapApp/component.
3. Hub explicitly binds using `com.androidmcp.capapp.BIND_V1`.
4. CapApp receives a Binder transaction and authenticates the Hub's calling UID, package identity, and signer.
5. If authentication fails, the service throws `SecurityException` and performs no tool work.
6. Hub supplies a fresh request ID, original tool name, JSON arguments, and callback Binder.
7. CapApp executes the handler on its worker coroutine.
8. CapApp returns serialized `ToolCallResult` through `ICapAppCallback.onResult()`.
9. Hub unbinds after completion or timeout.

The v1 path contains no exported result BroadcastReceiver and no caller-controlled reply package.

## 9. Response envelope

The canonical human-readable envelope remains supported inside MCP text content:

```text
OK: <tool-name> succeeded

{ ... }
```

or:

```text
FAIL: <tool-name> failed: <exception message>
Hint: <actionable recovery hint>

Raw:
{ ... }
```

`ToolCallResult.structuredContent` and `outputSchema` may also carry typed machine-readable output; callers must not assume all results are text-only.

## 10. Failure behavior

Hard handler failures should propagate as exceptions. `ToolAppService` converts them using `Envelope.fromException()`, allowing `metadata.failureModes` to produce an actionable recovery hint.

Do not catch a hard failure and return a string such as `"Error: ..."`; that misclassifies the operation as successful.

Use `envelopeTool` when a tool intentionally returns `WARN` or a structured `FAIL` without throwing.

## 11. Lifecycle

### Installation / refresh

The Hub discovers installed CapApps during its discovery pass. `hub.refresh` refreshes the aggregated registry after a CapApp install/update or descriptor change.

### Binding

The current v1 implementation binds on discovery to retrieve the tool catalog and binds per execution. A future connection pool may optimize this, but it must preserve per-transaction authorization and correctly handle package update, signing change, binding death, and trust revocation.

### Removal

If a CapApp disappears, binding fails and the Hub returns a transport error until discovery refresh removes the stale tool descriptor.

## 12. Android permissions

CapApps request and enforce their own Android permissions. The Hub does not acquire a CapApp's Android permissions and must never become a permission-escalation proxy.

A permission granted to a CapApp authorizes that CapApp process to use the Android API; it does **not** authorize every installed application to cause the CapApp to exercise the permission. Binder caller authentication separates those concerns.

## 13. v0 compatibility appendix

Protocol v0 uses:

```text
Hub -> startService(ACTION_EXECUTE / ACTION_LIST_TOOLS)
CapApp -> sendBroadcast(ACTION_TOOL_RESULT)
```

with callback IDs and a caller-provided `replyTo` package. It is not an acceptable trust boundary for privileged CapApps.

The current Hub keeps a v0 fallback so an upgraded Hub can continue discovering older installed CapApp APKs during migration. Newly rebuilt bundled CapApps no longer advertise the v0 service actions, and the SDK default rejects v0 started-service invocation.

Remote relay access must not expose sensitive v0-only CapApps.

## 14. Bundled migration state

The bundled Android CapApps have been moved to Binder v1 manifests:

- `tool-device`
- `tool-notify`
- `tool-people`
- `tool-files`
- `tool-files-dev`
- `taichi-android`

CI assembles the Hub, SDK, and all of these CapApps together to catch cross-module API breaks.

## 15. Upgrade ordering

The transition is intentionally asymmetric:

- **new Hub + old CapApp:** supported through the Hub's v0 compatibility fallback;
- **new Hub + new CapApp:** preferred Binder v1 path;
- **old Hub + new v1-only CapApp:** not guaranteed to discover or invoke the CapApp.

Therefore coordinated releases should update the Hub before, or together with, bundled CapApps. Consumer packaging should make this ordering automatic rather than requiring users to reason about protocol versions.

## 16. Exit criteria for v1 hardening

Before enabling a real remote relay for sensitive capabilities:

1. all bundled privileged CapApps build with Binder v1 and do not advertise v0 execution;
2. an unrelated APK cannot invoke a v1 CapApp directly;
3. an unrelated APK cannot bypass Binder trust by invoking the Hub over unauthenticated localhost TCP;
4. the Hub still supports explicitly recognized older CapApp APKs during migration;
5. package/signing changes and Binder death fail closed;
6. the user-visible third-party pairing/revocation path is implemented before independently signed CapApps are treated as trusted.
