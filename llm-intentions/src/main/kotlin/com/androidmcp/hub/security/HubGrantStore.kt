package com.androidmcp.hub.security

import android.content.Context
import java.security.MessageDigest
import java.util.Base64

/**
 * Minimal persisted grant store keyed by authenticated principal + namespaced tool.
 *
 * H2 intentionally stores only boolean grants. Argument scopes/presets can extend this model later
 * without changing the dispatcher authorization contract.
 */
class HubGrantStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasGrant(principalId: String, toolName: String): Boolean =
        prefs.getBoolean(key(principalId, toolName), false)

    fun grant(principalId: String, toolName: String) {
        prefs.edit().putBoolean(key(principalId, toolName), true).apply()
    }

    fun revoke(principalId: String, toolName: String) {
        prefs.edit().remove(key(principalId, toolName)).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun key(principalId: String, toolName: String): String {
        val material = "$principalId\n$toolName".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(material)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    companion object {
        private const val PREFS_NAME = "llm_intentions_policy_grants_v1"
    }
}
