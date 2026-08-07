package com.androidmcp.intent.v1

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * Authorization hook for CapApp Binder transactions.
 *
 * Binder.getCallingUid() must be captured by the service while it is still handling the
 * transaction, then passed into this policy. A UID is only an identity lookup key; trust is
 * established by resolving/checking the installed caller's signing identity.
 */
fun interface CapAppCallerTrustPolicy {
    fun isTrusted(context: Context, callingUid: Int): Boolean
}

/**
 * H1 canary policy: trust the CapApp itself and apps signed by the same signing identity.
 *
 * This removes the v0 confused-deputy path for first-party CapApps while the Hub trust store
 * and explicit third-party pairing UI are built. Third-party CapApps should replace this with
 * an explicit certificate trust policy rather than weakening this check.
 */
object SameSignerCallerTrustPolicy : CapAppCallerTrustPolicy {
    override fun isTrusted(context: Context, callingUid: Int): Boolean {
        if (callingUid == Process.myUid()) return true
        return context.packageManager.checkSignatures(callingUid, Process.myUid()) ==
            PackageManager.SIGNATURE_MATCH
    }
}
