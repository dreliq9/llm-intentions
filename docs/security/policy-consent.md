# H2 Policy, Consent, Grants, and Authorization Audit

## Purpose

H2 makes the Android Hub—not MCP tool hints, the relay, or a model—the execution authority for device capabilities.

The security boundary is:

```text
trusted transport identity
        ↓
McpDispatcher tools/call choke point
        ↓
persisted principal/tool grant
        ↓
deterministic tool policy
        ↓
ALLOW | DENY | MCP input_required
        ↓
handler executes only after ALLOW
```

Tool arguments, MCP `_meta`, and `clientInfo` never establish caller identity.

## Trusted policy metadata

CapApps may publish richer policy metadata through authenticated Binder discovery:

- `MutationClass`: `READ_ONLY`, `MUTATING`, `UNKNOWN`
- `SensitiveDataClass`
- `ConfirmationMode`
- `destructive`
- idempotence / latency / Android permission metadata

Standard MCP ToolAnnotations are derived from explicit semantics when possible, but remain advisory hints. Missing metadata is represented conservatively rather than inferred as read-only.

Older Binder-v1 or legacy-v0 descriptors that do not carry rich metadata are treated as `UNKNOWN` for remote policy.

## Remote-provider default policy

A future authenticated `REMOTE_PROVIDER` invocation follows these defaults:

1. no persisted principal/tool grant → **DENY**
2. credential-bearing tool → **DENY** by default
3. incomplete/unknown safety metadata → **CONFIRM**
4. destructive or mutating tool → **CONFIRM**
5. sensitive read → **CONFIRM**
6. explicitly granted `READ_ONLY + NONE` → **ALLOW**

An author-supplied hint cannot override a hard denial such as remote credential access.

The existing bearer-authenticated localhost path enters as `LOCAL_DEVELOPER`. H2 intentionally preserves that already-authenticated development workflow while policy metadata is being backfilled.

## MCP input_required confirmation

For MCP `2026-07-28` clients, a confirmation decision is returned before the tool handler executes:

```text
resultType = input_required
inputRequests.llm_intentions_confirmation.method = elicitation/create
requestState = <opaque signed state>
```

A valid retry must provide:

- the byte-exact `requestState` returned by the Hub;
- an `accept` elicitation response;
- `approved=true`;
- the same authenticated principal;
- the same namespaced tool;
- the same canonical arguments;
- an unexpired, still-pending nonce.

The state is HMAC-bound to principal, tool, canonical argument hash, issue time, expiry time, and nonce. Canonical JSON hashing makes object-key reordering stable without allowing content changes.

The pending-confirmation store is bounded. Expired entries are removed, overflow evicts the soonest-expiring entry, and every valid retry consumes its nonce before the response is interpreted. Declines, malformed responses, replay, tampering, expiry, eviction, or a Hub restart therefore fail closed.

Clients that cannot perform `input_required` do not receive an implicit allow for remote confirmation-required actions.

## Grants

`HubGrantStore` persists the minimal authorization fact:

```text
authenticated principal ID + namespaced tool name
```

Being connected to a relay is not a grant. Device enrollment is not a grant. Provider authentication is not a grant. H3/H4 onboarding must create/revoke grants explicitly through user-directed policy UX.

## Authorization audit

`HubAuditLog` is an app-private rolling JSONL log. H2 currently writes **authorization decisions** through `HubToolAuthorizer`.

Each record may contain:

- timestamp;
- authorization phase;
- authenticated principal ID;
- invocation origin;
- namespaced tool name;
- `ALLOW`, `DENY`, or `INPUT_REQUIRED`;
- a generic decision reason.

It deliberately does **not** contain:

- tool arguments;
- tool output;
- signed `requestState`;
- elicitation contents;
- bearer/relay credentials.

Handler execution/result auditing is intentionally not claimed in H2 until trusted transport context is carried through an execution-audit boundary as well.

## H2 validation

Automated exit criteria:

- dispatcher deny/input-required returns before handler execution;
- missing trusted transport context fails closed when authorization is installed;
- grant/no-grant and remote safety decisions are covered;
- HMAC state is bound to principal/tool/arguments/expiry;
- approval is one-use and replay is rejected;
- pending state is bounded and expired state cannot authorize;
- authenticated localhost development remains compatible;
- Hub, SDK, and every bundled CapApp still assemble.

The full `:mcp-core:test` suite and bundled APK assembly passed on code head `76182520d6e4a831f6c3757015b4684e5949171c`.

Remaining manual proof: install the stacked Hub build on a real device, verify the existing authenticated localhost path still invokes harmless tools, then exercise a synthetic `REMOTE_PROVIDER` call through H3 before granting access to sensitive CapApps.
