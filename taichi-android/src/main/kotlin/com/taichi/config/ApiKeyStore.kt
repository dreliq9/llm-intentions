package com.taichi.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores API keys in SharedPreferences (encrypted in a future version).
 * MCP tools and scrapers read keys from here instead of env vars.
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("taichi_api_keys", Context.MODE_PRIVATE)

    var cryptoPanicToken: String?
        get() = prefs.getString(KEY_CRYPTOPANIC, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_CRYPTOPANIC, value).apply()

    var redditClientId: String?
        get() = prefs.getString(KEY_REDDIT_ID, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_REDDIT_ID, value).apply()

    var redditClientSecret: String?
        get() = prefs.getString(KEY_REDDIT_SECRET, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_REDDIT_SECRET, value).apply()

    var coinGeckoApiKey: String?
        get() = prefs.getString(KEY_COINGECKO, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_COINGECKO, value).apply()

    /**
     * Summary of which keys are configured.
     */
    fun status(): Map<String, Boolean> = mapOf(
        "cryptopanic" to (cryptoPanicToken != null),
        "reddit" to (redditClientId != null && redditClientSecret != null),
        "coingecko" to (coinGeckoApiKey != null)
    )

    companion object {
        private const val KEY_CRYPTOPANIC = "cryptopanic_auth_token"
        private const val KEY_REDDIT_ID = "reddit_client_id"
        private const val KEY_REDDIT_SECRET = "reddit_client_secret"
        private const val KEY_COINGECKO = "coingecko_api_key"
    }
}
