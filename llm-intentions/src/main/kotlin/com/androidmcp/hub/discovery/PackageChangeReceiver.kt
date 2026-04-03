package com.androidmcp.hub.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Listens for app install/uninstall/update events and triggers
 * tool re-discovery on the Hub provider.
 *
 * Registered dynamically by HubMcpProvider so it has a direct
 * reference to the refresh callback.
 */
class PackageChangeReceiver(
    private val onPackageChanged: (String, ChangeType) -> Unit
) : BroadcastReceiver() {

    enum class ChangeType { ADDED, REMOVED, REPLACED }

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        // Don't react to our own package
        if (packageName == context.packageName) return

        val type = when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false))
                    ChangeType.REPLACED
                else
                    ChangeType.ADDED
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false))
                    return // Will get ADDED next for the replacement
                ChangeType.REMOVED
            }
            Intent.ACTION_PACKAGE_REPLACED -> ChangeType.REPLACED
            else -> return
        }

        Log.i("MCP-Hub", "Package ${type.name.lowercase()}: $packageName — refreshing tools")
        onPackageChanged(packageName, type)
    }
}
