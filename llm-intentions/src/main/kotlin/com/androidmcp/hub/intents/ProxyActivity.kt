package com.androidmcp.hub.intents

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Invisible Activity that bridges startActivityForResult() for the Hub.
 *
 * Since the Hub runs as a Service (no Activity context), it can't call
 * startActivityForResult() directly. This transparent Activity:
 *
 * 1. Receives a target Intent and callback ID from IntentEngine
 * 2. Launches the target via startActivityForResult()
 * 3. Captures the result in onActivityResult()
 * 4. Posts it back to IntentEngine via the registered callback
 * 5. Finishes immediately
 *
 * Declared in manifest with:
 *   android:theme="@android:style/Theme.Translucent.NoTitleBar"
 *   android:noHistory="true"
 *   android:excludeFromRecents="true"
 */
class ProxyActivity : Activity() {

    private var callbackId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        callbackId = intent.getStringExtra(EXTRA_CALLBACK_ID)
        val targetIntent = intent.getParcelableExtra<Intent>(EXTRA_TARGET_INTENT)

        if (callbackId == null || targetIntent == null) {
            Log.w(TAG, "ProxyActivity missing callback_id or target_intent")
            finish()
            return
        }

        try {
            @Suppress("DEPRECATION")
            startActivityForResult(targetIntent, REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch target activity", e)
            callbacks.remove(callbackId)?.invoke(RESULT_CANCELED, null)
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE) {
            callbackId?.let { id ->
                callbacks.remove(id)?.invoke(resultCode, data)
            }
        }

        finish()
    }

    companion object {
        private const val TAG = "MCP-ProxyActivity"
        const val EXTRA_TARGET_INTENT = "com.androidmcp.hub.TARGET_INTENT"
        const val EXTRA_CALLBACK_ID = "com.androidmcp.hub.CALLBACK_ID"
        private const val REQUEST_CODE = 42

        private val callbacks = ConcurrentHashMap<String, (Int, Intent?) -> Unit>()

        fun registerCallback(callbackId: String, callback: (resultCode: Int, data: Intent?) -> Unit) {
            callbacks[callbackId] = callback
        }

        fun removeCallback(callbackId: String) {
            callbacks.remove(callbackId)
        }
    }
}
