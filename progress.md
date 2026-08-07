# LLM Intentions — Progress

## 2026-08-07

### H0 — local transport hardening + MCP modernization
- Implemented on `agent/trusted-modern-mcp` / PR #2.
- Loopback-only authenticated Hub endpoint, token rotation/revocation, Origin/request hardening, MCP 2026-07-28 dual-era compatibility, CI baseline.
- Code/CI green; installed-phone bearer and rotation smoke test remains.

### H1 — authenticated Binder CapApp IPC
- Implemented on `agent/trusted-capapp-ipc` / PR #3.
- Binder/AIDL v1, Hub package + signer trust, v0 fail-closed SDK default, all bundled CapApps migrated, transport-aware health/UI.
- Code/CI green across Hub, SDK, and all bundled CapApps; physical-device signing/trust smoke test remains.

### H2 — deterministic policy and consent
- Implemented on `agent/policy-consent` / PR #4.
- Explicit mutation/sensitivity/confirmation metadata, rich Binder descriptors, shared dispatcher authorization, persisted grants, MCP input_required confirmation, HMAC-bound one-time state, bounded pending-confirmation store, and app-private authorization audit.
- Full `:mcp-core:test` and bundled Hub/SDK/CapApp assembly passed on code head `76182520d6e4a831f6c3757015b4684e5949171c`.
- Local on-device policy compatibility smoke test remains.

### Next: H3 — outbound authenticated Intentions Relay
- Build from the final green H2 stack.
- One-time device enrollment with proof of possession.
- Android Keystore device signing identity.
- Outbound WSS client attached to the Hub foreground-service lifetime.
- Relay challenge authentication, bounded frames/in-flight work, no offline tool queue.
- Relay-verified provider identity enters H2 only as `REMOTE_PROVIDER`.
- Provider-facing MCP remains synthetic/harmless until end-to-end device tests pass.
