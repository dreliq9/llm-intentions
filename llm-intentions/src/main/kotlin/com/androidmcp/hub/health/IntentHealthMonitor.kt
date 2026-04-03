package com.androidmcp.hub.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.androidmcp.hub.discovery.DiscoveredApp
import com.androidmcp.intent.McpIntentConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Health monitor for Intent-based tool apps.
 * Sends a __ping__ EXECUTE and measures broadcast reply round-trip.
 */
class IntentHealthMonitor(private val context: Context) {

    data class HealthStatus(
        val packageName: String,
        val namespace: String,
        val alive: Boolean,
        val roundTripMs: Long = -1,
        val error: String? = null
    )

    fun checkAll(apps: List<DiscoveredApp>): List<HealthStatus> {
        return apps.map { checkOne(it) }
    }

    private fun checkOne(app: DiscoveredApp): HealthStatus {
        val component = app.serviceComponent

        return try {
            runBlocking {
                val start = System.currentTimeMillis()
                val callbackId = UUID.randomUUID().toString()
                val deferred = CompletableDeferred<Boolean>()

                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val id = intent.getStringExtra(McpIntentConstants.EXTRA_CALLBACK_ID)
                        if (id == callbackId) {
                            deferred.complete(true)
                            try { context.unregisterReceiver(this) } catch (_: Exception) {}
                        }
                    }
                }

                val filter = IntentFilter(McpIntentConstants.ACTION_TOOL_RESULT)
                if (Build.VERSION.SDK_INT >= 34) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }

                val intent = Intent(McpIntentConstants.ACTION_EXECUTE).apply {
                    this.component = component
                    putExtra(McpIntentConstants.EXTRA_TOOL_NAME, "__ping__")
                    putExtra(McpIntentConstants.EXTRA_ARGUMENTS, "{}")
                    putExtra(McpIntentConstants.EXTRA_CALLBACK_ID, callbackId)
                    putExtra(McpIntentConstants.EXTRA_REPLY_TO, context.packageName)
                }

                context.startService(intent)

                val result = withTimeoutOrNull(5_000) { deferred.await() }
                val elapsed = System.currentTimeMillis() - start
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}

                if (result == true) {
                    HealthStatus(app.packageName, app.namespace, true, elapsed)
                } else {
                    HealthStatus(app.packageName, app.namespace, false, error = "Timeout (5s)")
                }
            }
        } catch (e: Exception) {
            HealthStatus(app.packageName, app.namespace, false, error = e.message)
        }
    }
}
