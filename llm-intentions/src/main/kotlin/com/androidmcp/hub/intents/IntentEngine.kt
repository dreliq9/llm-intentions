package com.androidmcp.hub.intents

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ResultReceiver
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Universal Intent dispatcher — supports all Android Intent communication patterns.
 *
 * This is Claude's adapter to the entire Android Intent ecosystem. Each method
 * corresponds to one of Android's IPC patterns:
 *
 * 1. fireAndForget — startActivity / sendBroadcast / startService
 * 2. orderedBroadcast — sendOrderedBroadcast with ResultReceiver callback
 * 3. withResultReceiver — pack ResultReceiver in extras, await callback
 * 4. withPendingIntent — pack PendingIntent, await callback
 * 5. bindAndMessage — bindService with Messenger for bidirectional messaging
 * 6. activityForResult — startActivityForResult via transparent ProxyActivity
 */
class IntentEngine(private val context: Context) {

    private val pendingRequests = PendingRequestManager()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Result from an Intent-based call.
     */
    data class IntentResult(
        val resultCode: Int = 0,
        val data: Bundle? = null,
        val error: String? = null
    ) {
        val isSuccess: Boolean get() = error == null
    }

    // -----------------------------------------------------------------------
    // 1. Fire-and-forget
    // -----------------------------------------------------------------------

    /**
     * Send an Intent with no result expected.
     * @param deliveryMethod One of "activity", "broadcast", "service"
     */
    fun fireAndForget(intent: Intent, deliveryMethod: String = "activity"): IntentResult {
        return try {
            when (deliveryMethod) {
                "activity" -> {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                "broadcast" -> context.sendBroadcast(intent)
                "service" -> context.startService(intent)
                else -> return IntentResult(error = "Unknown delivery method: $deliveryMethod")
            }
            IntentResult(resultCode = 0)
        } catch (e: Exception) {
            IntentResult(error = "Failed to send: ${e.message}")
        }
    }

    // -----------------------------------------------------------------------
    // 2. Ordered broadcast with result
    // -----------------------------------------------------------------------

    /**
     * Send an ordered broadcast and await the final result.
     * The last receiver in the chain sets the result data.
     */
    suspend fun orderedBroadcast(
        intent: Intent,
        permission: String? = null,
        timeoutMs: Long = 30_000
    ): IntentResult {
        val deferred = CompletableDeferred<IntentResult>()

        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val result = IntentResult(
                    resultCode = resultCode,
                    data = getResultExtras(true)
                )
                deferred.complete(result)
            }
        }

        context.sendOrderedBroadcast(
            intent,
            permission,
            resultReceiver,
            mainHandler,
            0,      // initialCode
            null,   // initialData
            null    // initialExtras
        )

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            IntentResult(error = "Ordered broadcast timed out after ${timeoutMs}ms")
        }
    }

    // -----------------------------------------------------------------------
    // 3. ResultReceiver pattern
    // -----------------------------------------------------------------------

    /**
     * Send an Intent with a ResultReceiver packed in extras.
     * The target app calls resultReceiver.send() to deliver the result.
     */
    suspend fun withResultReceiver(
        intent: Intent,
        receiverExtraKey: String = "result_receiver",
        deliveryMethod: String = "service",
        timeoutMs: Long = 60_000
    ): IntentResult {
        val callbackId = UUID.randomUUID().toString()
        val deferred = pendingRequests.register(callbackId)

        val receiver = object : ResultReceiver(mainHandler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                pendingRequests.complete(callbackId, IntentResult(
                    resultCode = resultCode,
                    data = resultData
                ))
            }
        }

        intent.putExtra(receiverExtraKey, receiver)

        return try {
            when (deliveryMethod) {
                "service" -> context.startService(intent)
                "broadcast" -> context.sendBroadcast(intent)
                "activity" -> {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                else -> {
                    pendingRequests.cancel(callbackId, "Unknown delivery method: $deliveryMethod")
                    return IntentResult(error = "Unknown delivery method: $deliveryMethod")
                }
            }
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            pendingRequests.cancel(callbackId, e.message ?: "Timeout")
            IntentResult(error = "ResultReceiver timed out after ${timeoutMs}ms: ${e.message}")
        }
    }

    // -----------------------------------------------------------------------
    // 4. PendingIntent callback
    // -----------------------------------------------------------------------

    /**
     * Send an Intent with a PendingIntent for the target to call back on.
     * The Hub receives the callback via a registered BroadcastReceiver.
     */
    suspend fun withPendingIntent(
        intent: Intent,
        pendingIntentExtraKey: String = "pending_intent",
        deliveryMethod: String = "service",
        timeoutMs: Long = 60_000
    ): IntentResult {
        val callbackId = UUID.randomUUID().toString()
        val deferred = pendingRequests.register(callbackId)

        // Create a PendingIntent that broadcasts back to us
        val callbackAction = "com.androidmcp.hub.CALLBACK.$callbackId"
        val callbackIntent = Intent(callbackAction).apply {
            setPackage(context.packageName)
        }
        val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, callbackId.hashCode(), callbackIntent, flags)

        // Register receiver for the callback
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, receivedIntent: Intent) {
                val result = IntentResult(
                    resultCode = receivedIntent.getIntExtra("result_code", 0),
                    data = receivedIntent.extras
                )
                pendingRequests.complete(callbackId, result)
                context.unregisterReceiver(this)
            }
        }

        val filter = IntentFilter(callbackAction)
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        intent.putExtra(pendingIntentExtraKey, pendingIntent)

        return try {
            when (deliveryMethod) {
                "service" -> context.startService(intent)
                "broadcast" -> context.sendBroadcast(intent)
                "activity" -> {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                else -> {
                    context.unregisterReceiver(receiver)
                    pendingRequests.cancel(callbackId, "Unknown delivery method")
                    return IntentResult(error = "Unknown delivery method: $deliveryMethod")
                }
            }
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            pendingRequests.cancel(callbackId, e.message ?: "Timeout")
            IntentResult(error = "PendingIntent timed out after ${timeoutMs}ms: ${e.message}")
        }
    }

    // -----------------------------------------------------------------------
    // 5. Bound Service + Messenger
    // -----------------------------------------------------------------------

    /**
     * Bind to a Service and send a Message via Messenger.
     * Receives a reply Message back.
     */
    suspend fun bindAndMessage(
        serviceIntent: Intent,
        messageWhat: Int = 0,
        messageData: Bundle? = null,
        timeoutMs: Long = 30_000
    ): IntentResult {
        val callbackId = UUID.randomUUID().toString()
        val deferred = pendingRequests.register(callbackId)

        // Messenger to receive replies
        val replyHandler = Handler(Looper.getMainLooper()) { msg ->
            pendingRequests.complete(callbackId, IntentResult(
                resultCode = msg.what,
                data = msg.data
            ))
            true
        }
        val replyMessenger = Messenger(replyHandler)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                try {
                    val messenger = Messenger(service)
                    val msg = Message.obtain(null, messageWhat).apply {
                        data = messageData
                        replyTo = replyMessenger
                    }
                    messenger.send(msg)
                } catch (e: Exception) {
                    pendingRequests.cancel(callbackId, "Send failed: ${e.message}")
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                pendingRequests.cancel(callbackId, "Service disconnected")
            }
        }

        return try {
            val bound = context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                pendingRequests.cancel(callbackId, "Failed to bind")
                return IntentResult(error = "Cannot bind to service")
            }
            val result = withTimeout(timeoutMs) { deferred.await() }
            context.unbindService(connection)
            result
        } catch (e: Exception) {
            try { context.unbindService(connection) } catch (_: Exception) {}
            pendingRequests.cancel(callbackId, e.message ?: "Timeout")
            IntentResult(error = "Bound service timed out after ${timeoutMs}ms: ${e.message}")
        }
    }

    // -----------------------------------------------------------------------
    // 6. Activity for result (via ProxyActivity)
    // -----------------------------------------------------------------------

    /**
     * Launch an Activity and get its result back via ProxyActivity.
     */
    suspend fun activityForResult(
        intent: Intent,
        timeoutMs: Long = 120_000
    ): IntentResult {
        val callbackId = UUID.randomUUID().toString()
        val deferred = pendingRequests.register(callbackId)

        // Register the callback so ProxyActivity can find it
        ProxyActivity.registerCallback(callbackId) { resultCode, data ->
            pendingRequests.complete(callbackId, IntentResult(
                resultCode = resultCode,
                data = data?.extras
            ))
        }

        // Launch ProxyActivity which will forward to the target
        val proxyIntent = Intent(context, ProxyActivity::class.java).apply {
            putExtra(ProxyActivity.EXTRA_TARGET_INTENT, intent)
            putExtra(ProxyActivity.EXTRA_CALLBACK_ID, callbackId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        }
        context.startActivity(proxyIntent)

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            ProxyActivity.removeCallback(callbackId)
            pendingRequests.cancel(callbackId, e.message ?: "Timeout")
            IntentResult(error = "Activity result timed out after ${timeoutMs}ms: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MCP-IntentEngine"
    }
}
