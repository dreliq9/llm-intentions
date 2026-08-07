package com.androidmcp.hub.health

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import com.androidmcp.hub.discovery.CapAppTransport
import com.androidmcp.hub.discovery.DiscoveredApp
import com.androidmcp.intent.McpIntentConstants
import com.androidmcp.intent.v1.ICapAppService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Health monitor for discovered CapApps.
 *
 * Binder v1 health is an authenticated descriptor round-trip. Legacy v0 APKs retain the old
 * started-service ping so health reporting follows the same transport the Hub will actually use.
 */
class IntentHealthMonitor(private val context: Context) {

    data class HealthStatus(
        val packageName: String,
        val namespace: String,
        val alive: Boolean,
        val roundTripMs: Long = -1,
        val error: String? = null,
    )

    fun checkAll(apps: List<DiscoveredApp>): List<HealthStatus> =
        apps.map { app ->
            when (app.transport) {
                CapAppTransport.BINDER_V1 -> checkBinder(app)
                CapAppTransport.INTENT_V0 -> checkLegacyIntent(app)
            }
        }

    private fun checkBinder(app: DiscoveredApp): HealthStatus {
        return try {
            runBlocking {
                val startedAt = System.currentTimeMillis()
                val serviceDeferred = CompletableDeferred<ICapAppService>()

                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val service = binder?.let(ICapAppService.Stub::asInterface)
                        if (service != null) {
                            serviceDeferred.complete(service)
                        } else if (!serviceDeferred.isCompleted) {
                            serviceDeferred.completeExceptionally(
                                IllegalStateException("Null Binder interface")
                            )
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        if (!serviceDeferred.isCompleted) {
                            serviceDeferred.completeExceptionally(
                                IllegalStateException("Service disconnected")
                            )
                        }
                    }

                    override fun onNullBinding(name: ComponentName?) {
                        if (!serviceDeferred.isCompleted) {
                            serviceDeferred.completeExceptionally(
                                IllegalStateException("Null binding")
                            )
                        }
                    }

                    override fun onBindingDied(name: ComponentName?) {
                        if (!serviceDeferred.isCompleted) {
                            serviceDeferred.completeExceptionally(
                                IllegalStateException("Binding died")
                            )
                        }
                    }
                }

                val intent = Intent(McpIntentConstants.ACTION_BIND_V1).apply {
                    component = app.serviceComponent
                }
                if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                    return@runBlocking HealthStatus(
                        app.packageName,
                        app.namespace,
                        false,
                        error = "Bind failed",
                    )
                }

                try {
                    val service = withTimeoutOrNull(HEALTH_TIMEOUT_MS) { serviceDeferred.await() }
                        ?: return@runBlocking HealthStatus(
                            app.packageName,
                            app.namespace,
                            false,
                            error = "Timeout (${HEALTH_TIMEOUT_MS / 1000}s)",
                        )

                    // The CapApp authenticates this transaction before returning the descriptor.
                    val descriptor = service.descriptorJson
                    if (descriptor.isBlank()) {
                        HealthStatus(
                            app.packageName,
                            app.namespace,
                            false,
                            error = "Empty v1 descriptor",
                        )
                    } else {
                        HealthStatus(
                            app.packageName,
                            app.namespace,
                            true,
                            System.currentTimeMillis() - startedAt,
                        )
                    }
                } finally {
                    try { context.unbindService(connection) } catch (_: Exception) { }
                }
            }
        } catch (e: SecurityException) {
            HealthStatus(app.packageName, app.namespace, false, error = "Trust rejected")
        } catch (e: Exception) {
            HealthStatus(app.packageName, app.namespace, false, error = e.message)
        }
    }

    private fun checkLegacyIntent(app: DiscoveredApp): HealthStatus {
        return try {
            runBlocking {
                val startedAt = System.currentTimeMillis()
                val callbackId = UUID.randomUUID().toString()
                val deferred = CompletableDeferred<Boolean>()

                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val id = intent.getStringExtra(McpIntentConstants.EXTRA_CALLBACK_ID)
                        if (id == callbackId) {
                            deferred.complete(true)
                            try { context.unregisterReceiver(this) } catch (_: Exception) { }
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
                    component = app.serviceComponent
                    putExtra(McpIntentConstants.EXTRA_TOOL_NAME, "__ping__")
                    putExtra(McpIntentConstants.EXTRA_ARGUMENTS, "{}")
                    putExtra(McpIntentConstants.EXTRA_CALLBACK_ID, callbackId)
                    putExtra(McpIntentConstants.EXTRA_REPLY_TO, context.packageName)
                }

                context.startService(intent)

                val result = withTimeoutOrNull(HEALTH_TIMEOUT_MS) { deferred.await() }
                val elapsed = System.currentTimeMillis() - startedAt
                try { context.unregisterReceiver(receiver) } catch (_: Exception) { }

                if (result == true) {
                    HealthStatus(app.packageName, app.namespace, true, elapsed)
                } else {
                    HealthStatus(
                        app.packageName,
                        app.namespace,
                        false,
                        error = "Legacy timeout (${HEALTH_TIMEOUT_MS / 1000}s)",
                    )
                }
            }
        } catch (e: Exception) {
            HealthStatus(app.packageName, app.namespace, false, error = e.message)
        }
    }

    companion object {
        private const val HEALTH_TIMEOUT_MS = 5_000L
    }
}
