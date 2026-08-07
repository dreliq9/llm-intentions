# LLM Intentions Policy and Consent Architecture

**Date:** 2026-08-06  
**Status:** H2 implementation specification

## Goal

Put one deterministic authorization boundary in front of every MCP tool handler before any future remote relay is allowed to reach device capabilities.

The policy layer must not trust model/provider claims, MCP `clientInfo`, tool annotations, or CapApp behavior as authority. Those inputs may describe context, but execution authority comes from authenticated transport identity, user grants, Hub policy, and—when necessary—an explicit user confirmation.

## Trust flow

```text
provider / local client
        |
        | authenticated transport
        v
ToolInvocationSecurityContext
        |
        v
McpDispatcher
        |
        +--> ToolCallAuthorizer
        |       |
        |       +--> persisted principal/tool grant
        |       +--> trusted ToolMetadata
        |       +--> deterministic risk policy
        |       +--> MRTR confirmation when required
        |
        +--> ALLOW ----> tool handler executes exactly once
        +--> DENY  ----> handler never runs
        +--> INPUT_REQUIRED -> handler never runs
```

## Security context

`ToolInvocationSecurityContext` is created only by an authenticated transport.

Initial origins:

- `LOCAL_DEVELOPER` — the bearer-authenticated localhost Hub endpoint.
- `REMOTE_PROVIDER` — reserved for the outbound relay once it can provide a verified principal.

The dispatcher must fail closed if a tool authorizer is installed but a tool call arrives without a trusted security context.

MCP request `_meta`, including `clientInfo`, is never converted into a security principal.

## Rich tool metadata

The trusted local descriptor extends the existing metadata with:

```text
mutation       READ_ONLY | MUTATING | UNKNOWN
sensitiveData  NONE | DEVICE | PERSONAL | COMMUNICATIONS | FINANCIAL | CREDENTIALS | UNKNOWN
confirmation   NEVER | POLICY | ALWAYS
openWorld      boolean | unknown
```

Defaults are conservative:

```text
mutation      UNKNOWN
sensitiveData UNKNOWN
confirmation  POLICY
```

`UNKNOWN` therefore cannot silently become an automatic remote allow.

Standard MCP ToolAnnotations are derived only where semantics are explicit. They remain advisory client hints, not an authorization source.

## Descriptor propagation

Binder v1 CapApps return authenticated `CapAppToolDescriptor` records:

```text
ToolInfo + ToolMetadata
```

The Hub retains the metadata in the namespaced proxy `McpToolDef`.

Rolling upgrade behavior:

- H2 descriptor payload: rich metadata is retained.
- earlier H1 Binder `List<ToolInfo>` payload: accepted, metadata becomes UNKNOWN.
- legacy v0 descriptor: accepted only as migration compatibility, metadata becomes UNKNOWN.

Thus compatibility does not become optimistic authorization.

## Baseline remote policy

For `REMOTE_PROVIDER`:

1. no persisted principal/tool grant -> **DENY**
2. credential-bearing tool -> **DENY** by default, even if author metadata says `NEVER` confirm
3. incomplete/UNKNOWN safety metadata -> **CONFIRM**
4. destructive operation -> **CONFIRM**
5. any mutation -> **CONFIRM**
6. sensitive read (`DEVICE`, `PERSONAL`, `COMMUNICATIONS`, `FINANCIAL`) -> **CONFIRM**
7. known `READ_ONLY + NONE` with grant -> **ALLOW**

Future argument-scoped grants may be narrower, but they may not weaken the hard credential denial without a separately reviewed policy change.

## Local developer compatibility

During H2 metadata backfill, the already bearer-authenticated `LOCAL_DEVELOPER` path is allowed through the Hub authorizer without remote-style grant/confirmation friction.

This preserves the deployed development workflow while keeping the remote boundary fail closed.

The local bypass is a deliberate transport-origin policy, not a consequence of absent metadata.

## MCP mid-request confirmation

MCP `2026-07-28` `input_required` / MRTR is the preferred interaction channel.

Initial mutating/sensitive call:

```json
{
  "resultType": "input_required",
  "inputRequests": {
    "llm_intentions_confirmation": {
      "method": "elicitation/create",
      "params": {
        "mode": "form",
        "message": "LLM Intentions wants to run 'people.event_create'...",
        "requestedSchema": {
          "type": "object",
          "properties": {
            "approved": { "type": "boolean" }
          },
          "required": ["approved"],
          "additionalProperties": false
        }
      }
    }
  },
  "requestState": "<opaque-authenticated-state>"
}
```

The client retries the original `tools/call` with `inputResponses` and the byte-exact `requestState`.

Legacy clients that cannot understand `input_required` do not receive an implicit allow; confirmation-required remote calls are denied.

## Confirmation requestState

The opaque state is HMAC-authenticated and binds:

```text
version
verified principal ID
tool name
SHA-256 of canonicalized arguments
issued time
expiry time
random nonce
```

Verification checks:

- HMAC signature
- supported state version
- expiration/lifetime
- principal match
- tool match
- canonical arguments hash match

The Hub also records issued nonces in memory and atomically consumes a nonce on the first valid retry. This prevents replay within the cryptographic TTL.

A Hub restart intentionally invalidates outstanding confirmations because the pending nonce set is not restored. The user can simply initiate the action again; failing closed is preferable to accepting stale authority after restart.

## User response rules

Only:

```text
action = accept
content.approved = true
```

allows the exact pending invocation.

`decline`, `cancel`, missing content, malformed content, changed arguments, changed principal, changed tool, bad signature, expiry, unknown nonce, and replay all deny execution.

A valid retry consumes its state before interpreting the answer, so a captured state cannot be retried repeatedly after a malformed or declined response.

## Persisted grants

`HubGrantStore` initially persists a boolean grant for:

```text
authenticated principal ID + namespaced tool name
```

It does not yet persist argument scopes or policy presets. The API intentionally allows that to be extended later without changing dispatcher security context.

Remote execution remains impossible until a relay supplies a verified principal and a user-facing flow creates grants.

## Android secret storage

The MRTR HMAC secret is separate from the localhost bearer token and future relay/device credentials.

H2 stores a random 256-bit confirmation-state secret in app-private, no-backup storage. It is never returned through MCP or included in diagnostics.

## Async execution

`JobManager.submit()` accepts and propagates `ToolInvocationSecurityContext`. Moving a tool call to background execution must not erase transport identity or bypass authorization.

## Required tests

Core tests cover:

- no grant -> deny
- known harmless remote read + grant -> allow
- remote mutation -> input_required before handler
- user accept -> handler allowed once
- decline/cancel -> deny
- replay -> deny
- changed arguments -> deny
- changed tool -> deny
- changed principal -> deny
- tampered HMAC -> deny
- expired state -> deny
- authorizer installed + missing transport context -> deny before handler
- input_required result -> handler remains uncalled
- local authenticated development path remains compatible

## H2 exit criteria

Before H3 relay traffic may reach real device tools:

1. the full core suite and all bundled APKs build green;
2. the shared Hub dispatcher is the sole MCP tool execution choke point;
3. local bearer-authenticated traffic supplies `LOCAL_DEVELOPER` context at the HTTP boundary;
4. no remote principal can execute without a persisted grant;
5. all confirmation-required retries use authenticated, one-time requestState;
6. legacy/old metadata is conservative, never auto-allowed;
7. an on-device smoke test confirms local tools still work after installing H2.
