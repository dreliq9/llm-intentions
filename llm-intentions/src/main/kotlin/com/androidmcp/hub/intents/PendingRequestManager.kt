package com.androidmcp.hub.intents

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages pending request/response correlations across all Intent patterns.
 *
 * Each outbound Intent call gets a unique callback ID. The response (from
 * ResultReceiver, PendingIntent, Messenger, or ProxyActivity) is matched
 * by callback ID and delivered to the waiting coroutine.
 */
class PendingRequestManager {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<IntentEngine.IntentResult>>()

    /**
     * Register a new pending request. Returns a Deferred that will be
     * completed when the response arrives.
     */
    fun register(callbackId: String): CompletableDeferred<IntentEngine.IntentResult> {
        val deferred = CompletableDeferred<IntentEngine.IntentResult>()
        pending[callbackId] = deferred
        return deferred
    }

    /**
     * Complete a pending request with a result.
     */
    fun complete(callbackId: String, result: IntentEngine.IntentResult) {
        pending.remove(callbackId)?.complete(result)
    }

    /**
     * Cancel a pending request with an error message.
     */
    fun cancel(callbackId: String, reason: String) {
        pending.remove(callbackId)?.complete(
            IntentEngine.IntentResult(error = reason)
        )
    }

    /**
     * Number of pending requests.
     */
    fun pendingCount(): Int = pending.size
}
