# LLM Intentions — Trusted Capability Platform Plan

## Goal

Turn the deployed Android CapApp prototype into a capability substrate that can safely serve local agents first and ordinary consumer chat providers later.

The phone remains the final authority. Network reachability, MCP annotations, model intent, and relay connectivity are never substitutes for Android IPC trust or user authorization.

## H0 — Harden local Hub transport + modernize MCP

Status: implemented on `agent/trusted-modern-mcp` / PR #2; code/CI green, physical-device smoke test pending.

- bind MCP server to loopback only;
- authenticate localhost with app-private bearer token;
- support immediate local-token rotation/revocation;
- validate Origin and harden raw HTTP parsing/bounds;
- add current MCP `2026-07-28` stateless compatibility while keeping deployed initialize-era compatibility temporarily;
- add deterministic list order/cache hints and baseline CI.

Exit proof on device:
- valid copied local config connects;
- missing/wrong bearer is rejected;
- rotated credential immediately invalidates old config.

## H1 — Trusted CapApp IPC

Status: implemented on `agent/trusted-capapp-ipc` / PR #3; full bundled APK build green, physical signing/trust smoke test pending.

- replace privileged started-service execution with Binder/AIDL v1;
- authenticate the Binder caller before leaving the calling thread;
- current first-party policy requires exact Hub package + matching Android signer;
- results return by Binder callback, not caller-selected broadcast package;
- SDK v0 execution fails closed unless explicitly re-enabled for migration;
- Hub prefers v1 but can still discover older installed v0 APKs during rollout;
- migrate all bundled rebuilt CapApps to Binder v1;
- make health checks and Hub UI transport-aware.

Exit proof on device:
- bundled v1 providers appear healthy;
- harmless Hub→CapApp calls work;
- unrelated APK cannot bind/invoke successfully;
- old v0 APK remains visible only as the explicit compatibility path.

## H2 — Deterministic policy + MCP consent

Status: implemented on `agent/policy-consent` / PR #4. Full `:mcp-core:test` and Hub/SDK/all bundled CapApp assembly passed on code head `76182520d6e4a831f6c3757015b4684e5949171c`; final documentation-only head is being revalidated.

- explicit mutation, sensitivity, confirmation, idempotence and latency semantics;
- authenticated rich CapApp descriptors preserve policy metadata to Hub;
- older descriptors become conservative `UNKNOWN`, never optimistic read-only;
- shared `McpDispatcher` authorizer gates Hub-native and proxied tools before handler execution;
- caller identity comes only from trusted transport context, never MCP `_meta`;
- remote providers require persisted principal/tool grants;
- deterministic policy:
  - no grant → deny;
  - credentials → deny remotely by default;
  - unknown metadata → confirm;
  - mutation/destructive → confirm;
  - sensitive read → confirm;
  - granted known read-only non-sensitive → allow;
- MCP `input_required` confirmation for modern clients;
- HMAC-bound request state tied to principal/tool/arguments/expiry/nonce;
- one-use, bounded pending confirmation store; replay/expiry/eviction/restart fail closed;
- app-private rolling authorization audit that omits arguments/results/confirmation contents.

Exit proof on device:
- authenticated localhost development still works;
- synthetic `REMOTE_PROVIDER` without grant is denied;
- granted safe read executes;
- confirmation-required synthetic call does not execute before approval;
- approved retry executes once; replay fails.

## H3 — Outbound authenticated Intentions Relay

Status: next implementation slice. No pushed relay branch existed when H3 began; build cleanly from final H2.

### Relay trust model

- Rust relay service;
- no listener by default unless explicitly configured;
- public TLS only;
- device enrollment is explicit, one-time, expiring, and revocable;
- Android device holds a P-256 signing key in Android Keystore;
- enrollment proves possession of the submitted device public key;
- relay authentication uses fresh one-use challenges and ECDSA signatures;
- connection is phone-outbound only;
- bounded frame sizes, bounded in-flight work, absolute deadlines;
- no offline device-action queue;
- device disconnect/session replacement fails pending work rather than replaying later.

### Android client

- attach relay connection lifetime to the existing Hub foreground service;
- require an enrolled relay configuration and explicit enablement;
- use `wss://` and disable cleartext traffic;
- reconnect with bounded exponential backoff/jitter;
- authenticate each new session with the Keystore device key;
- parse only typed/bounded relay frames;
- translate relay-verified provider identity into `ToolInvocationSecurityContext(REMOTE_PROVIDER, principalId)`;
- dispatch through the same H2 `McpDispatcher` authorizer;
- never let relay payload `_meta` replace the transport-authenticated principal.

### Initial public surface

Before sensitive CapApps are remotely usable:
- expose synthetic harmless test operations only;
- prove device auth, routing, grant denial, safe-read allow, MRTR confirmation and replay rejection end-to-end;
- only then begin granting selected real tools.

## H4 — Consumer provider onboarding

- authenticated user/account control plane;
- provider-specific MCP/connect flows;
- user-facing Connect Assistant / revoke UI;
- policy presets such as Read only / Ask before changes;
- per-provider/per-tool grant management;
- device/relation recovery and revocation.

## H5 — Capability expansion

After H0-H4 trust boundaries are proven:
- typed/structured tool outputs and schemas across more CapApps;
- broader semantic CapApps;
- optional UI/accessibility automation as a fallback capability, not the platform identity;
- third-party CapApp pairing/trust beyond first-party shared signing;
- revisit Intent Mesh after the secure capability substrate is stable.
