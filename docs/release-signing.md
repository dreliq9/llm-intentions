# First-Party Release Signing

**Status:** Required for the current CapApp Protocol v1 first-party trust policy.

## Invariant

The official Hub (`com.llmintentions`) and every bundled first-party CapApp that uses `OfficialHubSameSignerTrustPolicy` must be installed with signing identities Android considers a match.

The current policy deliberately requires **both**:

1. the Binder caller UID resolves to the official Hub package `com.llmintentions`; and
2. Android reports a matching signing identity between that caller and the CapApp.

Package name alone is not trusted. Signing identity alone is also not trusted.

## Why

CapApps possess their own Android permissions and may therefore hold authority the calling application does not possess. A caller that merely knows the exported Binder component must not be able to cause the CapApp to exercise those permissions.

Requiring the official package plus matching signer gives bundled builds a simple fail-closed first-party trust boundary while the independently signed third-party pairing model is implemented.

It also avoids a development trap: several unrelated debug apps may share a debug signing key. Same-signer-only authorization would therefore be broader than intended.

## Build and distribution consequence

Treat the Hub + bundled CapApps as one signing family.

Before publishing a release, verify the installed signing identity of:

- `com.llmintentions`
- `com.llmintentions.device`
- `com.llmintentions.notify`
- `com.llmintentions.people`
- `com.llmintentions.files`
- `com.llmintentions.files.dev` when distributed for development
- `com.taichi.android` when it is treated as a first-party bundled CapApp

A distribution setup that gives these packages unrelated signing identities will cause Binder v1 authorization to fail by design.

Do not fix such a failure by removing the signer check. Either:

- configure the first-party release so the relevant apps retain a shared signing identity; or
- move that CapApp onto the explicit user-approved Hub certificate trust/pairing path.

## Upgrade ordering

The protocol transition is asymmetric:

- new Hub + old v0 CapApp: Hub can use the migration fallback;
- new Hub + new v1 CapApp: authenticated Binder is preferred;
- old Hub + new v1-only CapApp: the old Hub may not discover/invoke it.

For bundled releases, update the Hub before or together with the CapApps.

## Release verification checklist

For a candidate release:

1. install the Hub and every bundled CapApp using the actual release artifacts;
2. open the Hub Apps screen;
3. confirm each rebuilt CapApp is shown as **Binder v1**, not **Legacy v0**;
4. confirm its health indicator succeeds—the v1 health check performs an authenticated Binder descriptor round-trip;
5. invoke at least one harmless read-only tool through the Hub;
6. attempt a bind/tool call from an unrelated test APK and verify the CapApp rejects it;
7. verify an older v0 CapApp can still be discovered by the new Hub if migration compatibility is required for that release.

CI assembly is necessary but cannot substitute for this installed-artifact signing test because GitHub Actions does not reproduce the final distribution signing topology.
