package com.androidmcp.intent.v1

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * Authorization hook for CapApp Binder transactions.
 *
 * Binder.getCallingUid() must be captured by the service while it is still handling the
 * transaction, then passed into this policy. A UID is only an identity lookup key; trust is
 * established by resolving/checking the installed caller's package and signing identity.
 */
fun interface CapAppCallerTrustPolicy {
    fun isTrusted(context: Context, callingUid: Int): Boolean
}

/**
 * H1 first-party policy: trust this CapApp's own UID or the official Hub package when Android
 * confirms that the Hub and CapApp share a signing identity.
 *
 * Requiring both package name and signer avoids granting every app built with the same debug or
 * release key the ability to invoke capabilities. Third-party CapApps should replace this with an
 * explicit user-approved certificate trust store rather than weakening the check.
 */
object OfficialHubSameSignerTrustPolicy : CapAppCallerTrustPolicy {
    override fun isTrusted(context: Context, callingUid: Int): Boolean {
        if (callingUid == Process.myUid()) return true

        val packages = context.packageManager.getPackagesForUid(callingUid) ?: return false
        if (OFFICIAL_HUB_PACKAGE !in packages) return false

        return context.packageManager.checkSignatures(callingUid, Process.myUid()) ==
            PackageManager.SIGNATURE_MATCH
    }

    const val OFFICIAL_HUB_PACKAGE = "com.llmintentions"
}
