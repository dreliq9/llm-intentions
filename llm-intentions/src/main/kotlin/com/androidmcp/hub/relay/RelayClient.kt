package com.androidmcp.hub.relay

import android.content.Context
import android.util.Log
import com.androidmcp.core.policy.InvocationOrigin
import com.androidmcp.core.policy.ToolInvocationSecurityContext
import com.androidmcp.core.protocol.JsonRpcRequest
import com.androidmcp.core.protocol.JsonRpcResponse
import com.androidmcp.core.protocol.MCP_MODERN_PROTOCOL_VERSION
import com.androidmcp.hub.HubMcpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

/**
 * Outbound-only authenticated relay tunnel attached to the Hub lifetime.
 *
 * There is deliberately no offline command queue. Work exists only while the current authenticated
 * WebSocket session exists; disconnect/replacement causes callers to retry from the relay side
 * rather than replaying stale device actions later.
 */
class RelayClient(
    context: Context,
    private val engine: HubMcpEngine,
) {
    private val appContext = context.applicationContext
    private val enrollmentStore = RelayEnrollmentStore(appContext)
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = Semaphore(RELAY_MAX_IN_FLIGHT)
    private val generation = AtomicLong(0)
    private val running = AtomicBoolean(false)
    private val socketLock = Any()
    private var socket: WebSocket? = null

    fun startIfEnabled() {
        val enrollment = enrollmentStore.load()
        if (enrollment?.enabled != true) {
            running.set(false)
            return
        }
        if (!running.compareAndSet(false, true)) return
        val nextGeneration = generation.incrementAndGet()
        connect(nextGeneration, attempt = 0)
    }

    /** Re-read enrollment/enablement after a user changes relay settings. */
    fun refreshFromEnrollment() {
        stopSocketOnly()
        running.set(false)
        startIfEnabled()
    }

    fun stop() {
        running.set(false)
        generation.incrementAndGet()
        stopSocketOnly()
        scope.cancel()
    }

    private fun stopSocketOnly() {
        val existing = synchronized(socketLock) {
            val current = socket
            socket = null
            current
        }
        existing?.close(1000, "relay disabled or Hub stopping")
    }

    private fun connect(expectedGeneration: Long, attempt: Int) {
        scope.launch {
            if (attempt > 0) delay(reconnectDelayMs(attempt))
            if (!running.get() || generation.get() != expectedGeneration) return@launch

            val enrollment = enrollmentStore.load()
            if (enrollment?.enabled != true) {
                running.set(false)
                return@launch
            }

            val request = try {
                Request.Builder()
                    .url(RelayEnrollmentStore.webSocketUrl(enrollment.relayBaseUrl, enrollment.deviceId))
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "Invalid relay enrollment URL", e)
                scheduleReconnect(expectedGeneration, attempt + 1)
                return@launch
            }

            val listener = SessionListener(
                expectedGeneration = expectedGeneration,
                attempt = attempt,
                enrollment = enrollment,
            )
            val created = RelayNetwork.client.newWebSocket(request, listener)
            synchronized(socketLock) {
                if (running.get() && generation.get() == expectedGeneration) {
                    socket = created
                } else {
                    created.close(1000, "stale relay generation")
                }
            }
        }
    }

    private fun scheduleReconnect(expectedGeneration: Long, attempt: Int) {
        if (!running.get() || generation.get() != expectedGeneration) return
        connect(expectedGeneration, attempt)
    }

    private inner class SessionListener(
        private val expectedGeneration: Long,
        private val attempt: Int,
        private val enrollment: RelayEnrollment,
    ) : WebSocketListener() {
        private val terminalHandled = AtomicBoolean(false)
        @Volatile private var authenticated = false
        @Volatile private var expectedSessionId: String? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "Relay socket open; awaiting device challenge")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
            if (text.toByteArray(Charsets.UTF_8).size > RELAY_MAX_TEXT_FRAME_BYTES) {
                webSocket.close(1009, "relay frame too large")
                return
            }

            val obj = try {
                json.parseToJsonElement(text).jsonObject
            } catch (_: Exception) {
                webSocket.close(1003, "invalid relay JSON")
                return
            }
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "auth_challenge" -> handleAuthChallenge(webSocket, text)
                "authenticated" -> handleAuthenticated(webSocket, text)
                "dispatch_request" -> handleDispatchRequest(webSocket, text)
                "session_replaced" -> {
                    webSocket.close(1000, "relay session replaced")
                }
                else -> webSocket.close(1003, "unknown relay frame")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            terminal(webSocket, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            terminal(webSocket, t)
        }

        private fun handleAuthChallenge(webSocket: WebSocket, text: String) {
            if (authenticated) {
                webSocket.close(1008, "duplicate relay authentication")
                return
            }
            val frame = runCatching { json.decodeFromString<RelayAuthChallengeFrame>(text) }
                .getOrElse {
                    webSocket.close(1003, "invalid auth challenge")
                    return
                }
            val challenge = frame.challenge
            val now = System.currentTimeMillis()
            if (challenge.deviceId != enrollment.deviceId
                || challenge.expiresAtMs <= now
                || challenge.expiresAtMs > now + MAX_ACCEPTED_CHALLENGE_FUTURE_MS
            ) {
                webSocket.close(1008, "invalid auth challenge scope")
                return
            }

            expectedSessionId = challenge.sessionId
            val signature = runCatching {
                RelayDeviceIdentity.signDerB64(RelayDeviceIdentity.authProofMessage(challenge))
            }.getOrElse {
                Log.e(TAG, "Unable to sign relay challenge", it)
                webSocket.close(1011, "device signing failed")
                return
            }
            sendJson(
                webSocket,
                json.encodeToString(
                    RelayAuthResponseFrame.serializer(),
                    RelayAuthResponseFrame(signatureDerB64 = signature),
                )
            )
        }

        private fun handleAuthenticated(webSocket: WebSocket, text: String) {
            val frame = runCatching { json.decodeFromString<RelayAuthenticatedFrame>(text) }
                .getOrElse {
                    webSocket.close(1003, "invalid authenticated frame")
                    return
                }
            if (frame.deviceId != enrollment.deviceId || frame.sessionId != expectedSessionId) {
                webSocket.close(1008, "relay authenticated wrong session")
                return
            }
            authenticated = true
            Log.i(TAG, "Relay device session authenticated")
        }

        private fun handleDispatchRequest(webSocket: WebSocket, text: String) {
            if (!authenticated) {
                webSocket.close(1008, "dispatch before authentication")
                return
            }
            val frame = runCatching { json.decodeFromString<RelayDispatchRequestFrame>(text) }
                .getOrElse {
                    webSocket.close(1003, "invalid dispatch request")
                    return
                }
            if (!validDispatchEnvelope(frame)) {
                sendDispatchError(webSocket, frame.requestId, "invalid_request", "Relay dispatch envelope rejected")
                return
            }
            if (!inFlight.tryAcquire()) {
                sendDispatchError(webSocket, frame.requestId, "busy", "Device relay in-flight limit reached")
                return
            }

            scope.launch {
                try {
                    val request = json.decodeFromJsonElement(JsonRpcRequest.serializer(), frame.request)
                    if (request.id == null) {
                        sendDispatchError(webSocket, frame.requestId, "notification_forbidden", "Remote tunnel accepts request/response MCP only")
                        return@launch
                    }
                    if (request.method !in REMOTE_ALLOWED_METHODS) {
                        sendDispatchError(webSocket, frame.requestId, "method_forbidden", "Remote MCP method is not enabled")
                        return@launch
                    }
                    if (!isModernRequest(request)) {
                        sendDispatchError(webSocket, frame.requestId, "protocol_required", "Remote tunnel requires MCP 2026-07-28")
                        return@launch
                    }

                    val remaining = frame.deadlineMs - System.currentTimeMillis()
                    if (remaining <= 0L) {
                        sendDispatchError(webSocket, frame.requestId, "deadline", "Relay request deadline already expired")
                        return@launch
                    }
                    val response = try {
                        withTimeout(remaining) {
                            engine.dispatcher.dispatch(
                                request,
                                ToolInvocationSecurityContext(
                                    origin = InvocationOrigin.REMOTE_PROVIDER,
                                    principalId = frame.principalId,
                                ),
                            )
                        }
                    } catch (_: TimeoutCancellationException) {
                        sendDispatchError(webSocket, frame.requestId, "deadline", "Device dispatch exceeded relay deadline")
                        return@launch
                    }

                    val rpcResponse = response ?: JsonRpcResponse(
                        id = request.id,
                        error = com.androidmcp.core.protocol.JsonRpcError(
                            code = com.androidmcp.core.protocol.JsonRpcError.INTERNAL_ERROR,
                            message = "Remote dispatch produced no response",
                        ),
                    )
                    val responseElement = json.encodeToJsonElement(JsonRpcResponse.serializer(), rpcResponse)
                    val outgoing = RelayDispatchResponseFrame(
                        requestId = frame.requestId,
                        response = responseElement,
                    )
                    sendJson(
                        webSocket,
                        json.encodeToString(RelayDispatchResponseFrame.serializer(), outgoing),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Remote relay dispatch failed", e)
                    sendDispatchError(webSocket, frame.requestId, "dispatch_error", "Device rejected relay dispatch")
                } finally {
                    inFlight.release()
                }
            }
        }

        private fun validDispatchEnvelope(frame: RelayDispatchRequestFrame): Boolean {
            val now = System.currentTimeMillis()
            return frame.requestId.isNotBlank()
                && frame.requestId.length <= RELAY_MAX_REQUEST_ID_CHARS
                && frame.principalId.isNotBlank()
                && frame.principalId.length <= RELAY_MAX_PRINCIPAL_CHARS
                && frame.deadlineMs > now
                && frame.deadlineMs <= now + RELAY_MAX_DEADLINE_AHEAD_MS
        }

        private fun isModernRequest(request: JsonRpcRequest): Boolean =
            request.params
                ?.get("_meta")
                ?.jsonObject
                ?.get("io.modelcontextprotocol/protocolVersion")
                ?.jsonPrimitive
                ?.contentOrNull == MCP_MODERN_PROTOCOL_VERSION

        private fun sendDispatchError(
            webSocket: WebSocket,
            requestId: String,
            code: String,
            message: String,
        ) {
            val boundedId = requestId.take(RELAY_MAX_REQUEST_ID_CHARS)
            sendJson(
                webSocket,
                json.encodeToString(
                    RelayDispatchErrorFrame.serializer(),
                    RelayDispatchErrorFrame(
                        requestId = boundedId,
                        code = code,
                        message = message,
                    ),
                )
            )
        }

        private fun terminal(webSocket: WebSocket, error: Throwable?) {
            if (!terminalHandled.compareAndSet(false, true)) return
            synchronized(socketLock) {
                if (socket === webSocket) socket = null
            }
            if (error != null) Log.w(TAG, "Relay socket failed", error)
            val nextAttempt = if (authenticated) 1 else attempt + 1
            scheduleReconnect(expectedGeneration, nextAttempt)
        }

        private fun isCurrent(webSocket: WebSocket): Boolean =
            running.get()
                && generation.get() == expectedGeneration
                && synchronized(socketLock) { socket === webSocket }
    }

    private fun sendJson(webSocket: WebSocket, payload: String): Boolean {
        if (payload.toByteArray(Charsets.UTF_8).size > RELAY_MAX_TEXT_FRAME_BYTES) return false
        return webSocket.send(payload)
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val exponent = min(attempt.coerceAtLeast(1), 6)
        val base = min(MAX_RECONNECT_MS, 1_000L shl (exponent - 1))
        return base + Random.nextLong(0, RECONNECT_JITTER_MS + 1)
    }

    companion object {
        private const val TAG = "LLM-Relay"
        private const val MAX_ACCEPTED_CHALLENGE_FUTURE_MS = 60_000L
        private const val MAX_RECONNECT_MS = 60_000L
        private const val RECONNECT_JITTER_MS = 750L
        private val REMOTE_ALLOWED_METHODS = setOf(
            "server/discover",
            "ping",
            "tools/list",
            "tools/call",
        )
    }
}
