# CapApp Protocol Specification

**Version:** 1.0.0-draft  
**Date:** 2026-08-06  
**Status:** Binder v1 canary implemented; Intent v0 retained for migration.

## Abstract

A **CapApp** is an independently installable Android application that exposes typed capabilities to the LLM Intentions Hub. The Hub aggregates those capabilities into its MCP tool surface.

CapApp Protocol v1 replaces the prototype's unauthenticated started-service + broadcast callback path with an authenticated Android Binder interface. Discovery remains package-visible metadata; discovery does **not** grant execution authority.

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

The CapApp owns its Android permissions and data access. The Hub may request a capability, but the CapApp remains responsible for authenticating the local caller before executing it.

## 2. Protocol versions

| Version | Transport | Trust model | Status |
| --- | --- | --- | --- |
| v1 | bound Binder/AIDL + Binder callback | per-transaction caller identity/signing trust | preferred |
| v0 | `startService()` + broadcast reply | none beyond Android component reachability | migration only |

A service discovered through v1 is not duplicated through v0. New first-party CapApps should disable v0 execution.

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

The Hub discovers matching services with `PackageManager.queryIntentServices()`, reads only the non-secret manifest metadata, then binds explicitly to the resolved `ComponentName`.

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

Tool execution is asynchronous. A slow capability therefore does not hold a Binder thread for the duration of the operation.

There is no caller-selected `replyTo` package in v1. Results return over the callback Binder object supplied by the authenticated caller.

## 5. Caller authentication

### 5.1 Transaction identity

Every v1 service method authenticates the caller **while the Binder transaction is being handled**, before dispatching work to another coroutine/thread.

The service captures `Binder.getCallingUid()` and resolves/checks the installed caller through Android package/signing APIs. The UID is a lookup handle, not the trust decision by itself.

### 5.2 Default H1 policy

`ToolAppService` currently defaults to `SameSignerCallerTrustPolicy`:

- the CapApp's own UID is trusted;
- another app is trusted only when Android reports that its signing identity matches the CapApp's signing identity.

This is the first-party canary policy. It closes the privilege-proxy problem for bundled Hub/CapApp builds signed together.

### 5.3 Third-party trust

Third-party CapApps will generally be signed by a different developer and therefore require explicit pairing. The intended follow-on trust record binds at least:

```text
Hub package name
Hub signing certificate digest / signing lineage
first-approved timestamp
last-seen timestamp
protocol version
```

A third-party CapApp must not weaken authentication to package-name-only checks. The user must be able to inspect and revoke a paired Hub identity.

## 6. `ToolAppService`

A CapApp normally extends the SDK base class:

```kotlin
class MyToolService : ToolAppService() {
    protected override val legacyIntentProtocolEnabled = false

    override fun onCreateTools(registry: ToolRegistry) {
        MyToolRegistrar.register(registry, applicationContext)
    }
}
```

The base class:

1. builds the in-memory `ToolRegistry`;
2. exposes Binder v1 from `onBind()` only for `com.androidmcp.capapp.BIND_V1`;
3. authenticates every Binder transaction before performing work;
4. serializes `tools/list` descriptors over the callback;
5. executes tools asynchronously and returns `ToolCallResult` JSON over Binder;
6. converts uncaught handler exceptions into the canonical failure envelope.

`legacyIntentProtocolEnabled` defaults to `true` only for migration. A migrated CapApp should set it to `false`.

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

The MCP-visible descriptor includes standard fields such as `name`, `description`, `inputSchema`, `outputSchema`, and standard annotations. Rich LLM Intentions policy metadata is being expanded separately for the H2 policy/consent layer.

## 8. Invocation flow

For a Binder v1 CapApp:

1. MCP client calls `tools/call` on the Hub.
2. Hub resolves `namespace.tool` to the discovered CapApp/component.
3. Hub explicitly binds to that component using `com.androidmcp.capapp.BIND_V1`.
4. CapApp receives a Binder transaction and authenticates the Hub's calling UID/signing identity.
5. If authentication fails, the service throws `SecurityException` and performs no tool work.
6. Hub supplies a fresh request ID, original tool name, JSON arguments, and callback Binder.
7. CapApp executes the handler on its worker coroutine.
8. CapApp returns serialized `ToolCallResult` through `ICapAppCallback.onResult()`.
9. Hub unbinds after completion/timeout.

The v1 path contains no exported result BroadcastReceiver and no caller-controlled reply package.

## 9. Response envelope

The existing canonical human-readable envelope remains supported inside MCP text content:

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

Do not catch a hard failure and return a string such as `"Error: ..."`; doing so misclassifies the operation as successful.

Use `envelopeTool` when a tool intentionally returns `WARN` or a structured `FAIL` without throwing.

## 11. Lifecycle

### Installation / refresh

The Hub discovers installed CapApps during its discovery pass. `hub.refresh` refreshes the aggregated registry after a CapApp install/update or descriptor change.

### Binding

The initial v1 implementation binds on discovery to retrieve the tool catalog and binds per execution. A future connection pool may optimize this, but it must preserve per-transaction authorization and must correctly handle package update, signing change, binding death, and revocation.

### Removal

If a CapApp disappears, binding fails and the Hub returns a transport error until discovery refresh removes the stale tool descriptor.

## 12. Android permissions

CapApps request and enforce their own Android permissions. The Hub does not acquire a CapApp's Android permissions and must never become a permission-escalation proxy.

A permission granted to the CapApp authorizes the CapApp process to use that Android API; it does **not** authorize every installed application to cause the CapApp to use that permission. Binder caller authentication is what separates those concerns.

## 13. v0 compatibility appendix

Protocol v0 uses:

```text
Hub -> startService(ACTION_EXECUTE / ACTION_LIST_TOOLS)
CapApp -> sendBroadcast(ACTION_TOOL_RESULT)
```

with callback IDs and a caller-provided `replyTo` package. It remains supported only while existing CapApps migrate.

Security rules for v0 during migration:

- do not expose new privileged CapApps solely through v0;
- prefer v1 whenever the same component advertises it;
- migrated services should set `legacyIntentProtocolEnabled = false`;
- remote relay access must not be enabled for sensitive v0-only capabilities;
- remove v0 once bundled CapApps and the developer template have migrated.

## 14. Current canary

`tool-device` is the first Binder v1 canary. Its manifest advertises only `BIND_V1`, it declares protocol version 1, and its service disables legacy v0 invocation.

The remaining bundled CapApps continue through the v0 fallback until the canary path is validated on-device and then migrated one at a time.
