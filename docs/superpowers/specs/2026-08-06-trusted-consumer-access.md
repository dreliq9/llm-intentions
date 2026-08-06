# Trusted Consumer Access Architecture

**Date:** 2026-08-06  
**Status:** Proposed implementation direction  
**Goal:** Make LLM Intentions safe to expose beyond local CLI agents and usable by ordinary mobile chat users without requiring Termux, port forwarding, tunnels, or hand-edited MCP configuration.

## 1. Product direction

LLM Intentions should become a model-independent Android capability substrate:

```text
Claude / ChatGPT / Gemini / Grok / other MCP clients
                     |
               public HTTPS MCP
                     |
              Intentions Relay
                     |
          outbound authenticated tunnel
                     |
              Android Hub
                     |
          trusted local capability IPC
            /        |         \
        People      Files      Device ...
        CapApp      CapApp      CapApp
```

The phone remains the authority over its capabilities. The relay is transport, identity, and routing infrastructure; it must never become an implicit authorization authority for device actions.

## 2. Non-negotiable security properties

1. **Hub MCP stays loopback-only by default.** The local MCP listener binds to `127.0.0.1`, not `0.0.0.0`.
2. **No direct Internet exposure of port 8379.** Remote model providers connect to a separate authenticated relay endpoint.
3. **CapApps authenticate the Hub.** Merely knowing the Intent action names must not be sufficient to invoke privileged tools.
4. **No caller-controlled reply package.** Tool results must return over an authenticated IPC channel rather than an arbitrary `reply_to` broadcast target.
5. **Authorization is per request.** A relay connection or model identity does not imply blanket permission to invoke every tool.
6. **The user can see and revoke trust.** Hub, CapApp, provider, and tool grants must have a user-visible control surface.
7. **Destructive and sensitive operations are fail-closed.** Unknown metadata or missing policy must not silently become approval.

## 3. MCP modernization

During migration the Hub should support two protocol eras:

- `2025-06-18` through the existing initialize-era compatibility path.
- `2026-07-28` as the preferred stateless path.

The modern path must support at minimum:

- `server/discover`
- per-request protocol metadata
- `MCP-Protocol-Version`, `Mcp-Method`, and `Mcp-Name` validation
- deterministic list ordering
- `ttlMs` / `cacheScope` on cacheable results
- standard tool annotations
- server identity in modern result `_meta`
- JSON responses for ordinary modern requests

MRTR (`input_required`) should be added when the policy/confirmation layer lands so a tool can ask for consent without requiring an always-open bidirectional session.

## 4. CapApp Protocol v1: authenticated Binder IPC

The current exported started-service + broadcast-response design is useful as a prototype but is not a sufficient trust boundary for third-party deployment. CapApp Protocol v1 should move invocation onto Android Binder.

### 4.1 Discovery

PackageManager Intent discovery can remain. A CapApp advertises a bindable capability service and protocol version in manifest metadata.

Discovery metadata should contain only non-sensitive descriptors. Discovery itself does not authorize execution.

### 4.2 Invocation

A CapApp exposes a bound service with operations conceptually equivalent to:

```text
getDescriptor()
listTools()
execute(toolName, arguments, requestContext)
```

Execution responses return directly over Binder (or a Binder callback for async work). The protocol does not accept an arbitrary reply package.

### 4.3 Caller authentication

For every Binder transaction the CapApp obtains the calling UID and resolves the package/signing identity through PackageManager. Trust records bind at least:

```text
package name
signing certificate digest / signing lineage
protocol version
first-approved timestamp
last-seen timestamp
```

The official Hub signer may be pre-trusted by first-party CapApps. Third-party CapApps and alternate Hub builds must support explicit pairing so the user can approve the installed Hub identity rather than trusting a package name alone.

A custom normal permission is not an acceptable authentication mechanism. A signature permission is useful for same-signer first-party components, but it is not sufficient as the general third-party CapApp trust model. Android's Binder caller identity plus explicit certificate trust avoids custom-permission ownership/race problems and supports independently signed CapApps.

### 4.4 Migration

Protocol v0 Intent execution remains available only behind an explicit compatibility mode while bundled CapApps migrate. Once v1 coverage is complete, v0 execution should be disabled by default and eventually removed.

## 5. Tool descriptor v1

The Hub needs enough information to make policy decisions before invoking a tool. CapApps should publish a descriptor that includes standard MCP fields plus LLM Intentions policy metadata.

Proposed metadata:

```text
readOnly               boolean
mutatesState            boolean
destructive             boolean
idempotent              boolean
latencyClass            fast | slow | very_slow
permissions             Android permission names
sensitiveData           none | device | personal | communications | financial | credentials
confirmation            never | policy | always
failureModes            structured recovery hints
examples                optional agent-facing examples
```

Standard MCP annotations are derived from these fields where semantics align. LLM Intentions-specific metadata remains available to the Hub policy engine and management UI.

Unknown or absent safety metadata is treated conservatively.

## 6. Policy and consent engine

Remote access requires a Hub-side authorization layer between MCP routing and CapApp invocation.

A policy decision considers:

```text
provider / connector identity
remote or local origin
CapApp identity
specific tool
metadata / risk class
arguments where relevant
user grant
```

Initial defaults should be conservative:

- read-only, non-sensitive tools: allow when the user has granted that CapApp/provider combination
- ordinary mutations: require policy approval; default to confirmation for remote callers
- destructive operations: confirm each invocation unless the user explicitly creates a narrow rule
- credentials and secrets: deny remote export by default
- unknown metadata: confirm or deny, never silently allow

For MCP 2026-07-28 clients, confirmation should use MRTR when supported. A fallback approval path can surface a native Android notification/sheet with a short-lived request token.

## 7. Intentions Relay

Normal users should not configure a reverse proxy. The Hub should establish the remote path itself.

### 7.1 Enrollment

On first enablement:

1. Hub generates a device key in Android Keystore.
2. User signs into the relay or pairs with a one-time code / QR flow.
3. Relay records the device public identity and creates a public MCP endpoint scoped to that account/device.
4. Hub maintains an outbound TLS connection to the relay.

Remote access is disabled by default and can be revoked from the phone at any time.

### 7.2 Transport

The phone initiates all network connectivity. Candidate transport is a long-lived authenticated WebSocket or HTTP/2 channel carrying independent MCP request/response exchanges.

The public side exposes MCP 2026-07-28 over HTTPS. The relay maps an authenticated remote request to the correct device tunnel. The Hub then applies its own local policy before routing to a CapApp.

No inbound socket, router configuration, dynamic DNS, or LAN exposure is required.

### 7.3 Relay data handling

The relay should minimize retained content:

- no tool payload logging by default
- short request retention only when required for delivery/retry
- separate operational metadata from content
- explicit diagnostic opt-in
- device/user isolation at the routing layer

The eventual design should evaluate end-to-end payload encryption above the relay transport where it is compatible with MCP provider integration.

## 8. Consumer onboarding

The target experience is:

1. Install LLM Intentions Hub.
2. Hub discovers installed CapApps and explains what each can access.
3. User grants Android permissions inside the relevant CapApps.
4. User taps **Connect assistant**.
5. User chooses a supported chat/model provider and signs in or follows a provider-specific connector flow.
6. Hub/relay generates and verifies the remote connection.
7. User chooses capability policy presets such as **Read only**, **Ask before changes**, or **Custom**.
8. A built-in test calls a harmless tool and shows the complete route: provider → relay → Hub → CapApp → result.

Termux and CLI agents remain an advanced/local-development path, not a prerequisite for using the product.

## 9. Implementation phases

### H0 — Local transport hardening + modern protocol

- loopback-only Hub listener
- Origin validation
- bounded request body
- MCP 2026-07-28 header/body validation
- `server/discover`
- deterministic tool/resource lists
- cache hints
- standard annotations preserved through Hub aggregation
- CI build/test baseline

### H1 — Trusted CapApp IPC

- define CapApp Protocol v1 Binder interface
- authenticated calling UID/package/certificate checks
- remove caller-selected reply target
- trust/pairing store and UI
- migrate one canary CapApp end to end
- migrate remaining bundled CapApps

### H2 — Policy + confirmation

- descriptor v1 safety metadata
- policy engine
- provider/CapApp/tool grants
- audit log
- MRTR confirmation path
- native fallback approval UI

### H3 — Remote Relay

- device identity and enrollment
- outbound tunnel
- public stateless MCP endpoint
- per-request authentication
- reconnect/retry semantics
- relay privacy controls

### H4 — Consumer onboarding

- Connect assistant UI
- provider-specific setup helpers
- capability presets
- health/status diagnostics
- Play-distributable Hub and CapApps

### H5 — Capability expansion

Only after the trust and remote-access substrate is stable should the project aggressively expand into additional CapApps, UI automation/accessibility, camera/mic, local models, and cross-model orchestration.

## 10. Exit criterion for the hardening milestone

The hardening/modernization milestone is complete when all of the following are true:

1. A LAN host cannot reach the default Hub MCP endpoint.
2. A malicious unrelated APK cannot invoke a privileged CapApp or receive its output.
3. Existing initialize-era clients still work through compatibility mode.
4. A 2026-07-28 MCP client can discover and call tools through the Hub using compliant headers and response shapes.
5. Tool safety annotations survive CapApp → Hub → MCP client.
6. Automated tests/builds protect these properties from regression.

Only then should remote relay access be enabled for real user data.
