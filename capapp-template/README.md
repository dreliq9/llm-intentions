# CapApp Settings Template

A drop-in settings Activity for LLM Intentions CapApps. Shows your app's namespace, tools, and permission status.

## What it shows

- **App Info** — namespace (from `mcp_tools.xml`), version, tool count
- **Tools** — list of all tools with names and descriptions
- **Permissions** — each requested Android permission with granted/missing status

## How to use

1. **Copy** `CapAppSettingsActivity.kt` into your CapApp's source directory
2. **Change** the package name on line 1 to match your app
3. **Add** the activity to your `AndroidManifest.xml`:

```xml
<activity
    android:name=".CapAppSettingsActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

## Dependencies

The activity uses only standard Android APIs — no extra dependencies needed beyond what your CapApp already has:

- `androidx.appcompat:appcompat`
- `androidx.core:core-ktx`

## Design

The Activity builds its UI programmatically (no XML layout files) so you only need to copy a single file. It reads your tool definitions from the same `res/xml/mcp_tools.xml` that the Hub uses for discovery, so the tools list is always in sync.
