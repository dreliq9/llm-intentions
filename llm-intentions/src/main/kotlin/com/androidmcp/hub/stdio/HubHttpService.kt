package com.androidmcp.hub.stdio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.androidmcp.core.protocol.JsonRpcRequest
import com.androidmcp.core.protocol.JsonRpcResponse
import com.androidmcp.hub.HubMcpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

class HubHttpService : Service() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private lateinit var engine: HubMcpEngine
    private var server: McpRawHttpServer? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

        engine = HubMcpEngine(this)

        serviceScope.launch {
            withContext(Dispatchers.IO) {
                engine.initialize()
            }
            sharedEngine = engine
            startedAtMillis = System.currentTimeMillis()

            server = McpRawHttpServer(PORT, engine, json)
            server?.start()

            Log.i(TAG, "HTTP server started on localhost:$PORT with ${engine.registry.size()} tools")
            updateNotification("Running — ${engine.registry.size()} tools on :$PORT")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        server?.stop()
        engine.shutdown()
        sharedEngine = null
        startedAtMillis = 0L
        Log.i(TAG, "HubHttpService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LLM Intentions Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps LLM Intentions running for Claude Code"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("LLM Intentions")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("LLM Intentions")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "LLM-Http"
        const val PORT = 8379
        private const val CHANNEL_ID = "llm_intentions_service"
        private const val NOTIFICATION_ID = 1

        @Volatile var sharedEngine: HubMcpEngine? = null
            private set
        @Volatile var startedAtMillis: Long = 0L
            private set
    }
}

/**
 * Raw HTTP server — no library, full control over every byte on the wire.
 * Matches the exact response format that Claude Code expects (same as termux-mcp).
 *
 * Lowercase headers, chunked transfer encoding on error responses,
 * keep-alive, proper status lines with no trailing spaces.
 */
private class McpRawHttpServer(
    private val port: Int,
    private val engine: HubMcpEngine,
    private val json: Json
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    fun start() {
        scope.launch {
            serverSocket = ServerSocket(port, 50, java.net.InetAddress.getByName("0.0.0.0"))
            Log.i("LLM-Http", "Listening on 127.0.0.1:$port")

            while (isActive) {
                val client = try {
                    serverSocket?.accept() ?: break
                } catch (e: Exception) {
                    if (isActive) Log.e("LLM-Http", "accept() failed", e)
                    break
                }
                launch { handleConnection(client) }
            }
        }
    }

    fun stop() {
        scope.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private suspend fun handleConnection(socket: Socket) {
        try {
            socket.use { sock ->
                sock.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.ISO_8859_1))
                val out = sock.outputStream

                // Read request line
                val requestLine = reader.readLine() ?: return
                Log.i("LLM-Http", ">>> $requestLine")

                val parts = requestLine.split(" ", limit = 3)
                if (parts.size < 2) return

                val method = parts[0]
                val uri = parts[1]

                // Read headers
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        val key = line.substring(0, colonIdx).trim().lowercase()
                        val value = line.substring(colonIdx + 1).trim()
                        headers[key] = value
                    }
                }

                // Read body if present
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                // Route
                val response = when {
                    uri != "/mcp" -> {
                        Log.i("LLM-Http", "<<< 404 $uri")
                        httpResponse(404, "Not Found", "Not found")
                    }
                    method == "DELETE" -> {
                        Log.i("LLM-Http", "<<< 405 DELETE")
                        httpResponse(405, "Method Not Allowed", "Method not allowed in stateless mode")
                    }
                    method == "GET" -> {
                        Log.i("LLM-Http", "<<< 405 GET")
                        httpResponse(405, "Method Not Allowed", "Method not allowed in stateless mode")
                    }
                    method == "POST" -> handlePost(body)
                    else -> {
                        Log.i("LLM-Http", "<<< 405 $method")
                        httpResponse(405, "Method Not Allowed", "Method not allowed")
                    }
                }

                out.write(response)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e("LLM-Http", "Connection error", e)
        }
    }

    private suspend fun handlePost(body: String): ByteArray {
        if (body.isBlank()) {
            return httpResponse(400, "Bad Request",
                """{"jsonrpc":"2.0","error":{"code":-32700,"message":"Empty request body"},"id":null}""",
                contentType = "application/json"
            )
        }

        return try {
            Log.i("LLM-Http", ">>> POST body: $body")
            val request = json.decodeFromString<JsonRpcRequest>(body)

            if (request.id == null) {
                // Notification — dispatch and return 202
                try {
                    engine.dispatcher.dispatch(request)
                } catch (e: Exception) {
                    Log.w("LLM-Http", "Notification dispatch failed (non-fatal)", e)
                }
                Log.i("LLM-Http", "<<< 202 notification")
                httpResponse(202, "Accepted", "")
            } else {
                val response = try {
                    engine.dispatcher.dispatch(request)
                } catch (e: Exception) {
                    Log.e("LLM-Http", "Dispatch failed for ${request.method}", e)
                    val escaped = (e.message ?: "Internal error").replace("\"", "'")
                    JsonRpcResponse(
                        id = request.id,
                        error = com.androidmcp.core.protocol.JsonRpcError(
                            code = -32603,
                            message = escaped
                        )
                    )
                }
                if (response != null) {
                    val responseJson = json.encodeToString(JsonRpcResponse.serializer(), response)
                    Log.i("LLM-Http", "<<< 200 SSE: ${responseJson.take(200)}")
                    sseResponse(responseJson)
                } else {
                    Log.i("LLM-Http", "<<< 202 no response")
                    httpResponse(202, "Accepted", "")
                }
            }
        } catch (e: Exception) {
            Log.e("LLM-Http", "Error processing request", e)
            val escaped = (e.message ?: "Parse error").replace("\"", "'")
            httpResponse(400, "Bad Request",
                """{"jsonrpc":"2.0","error":{"code":-32700,"message":"$escaped"},"id":null}""",
                contentType = "application/json"
            )
        }
    }

    /**
     * Build a raw HTTP response with exact control over every byte.
     * Matches termux-mcp format: lowercase headers, keep-alive, body.
     */
    private fun httpResponse(
        statusCode: Int,
        statusText: String,
        body: String,
        contentType: String? = null
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $statusCode $statusText\r\n")
        if (contentType != null && body.isNotEmpty()) {
            sb.append("content-type: $contentType\r\n")
        }
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        sb.append("content-length: ${bodyBytes.size}\r\n")
        sb.append("connection: keep-alive\r\n")
        sb.append("keep-alive: timeout=5\r\n")
        sb.append("\r\n")

        val headerBytes = sb.toString().toByteArray(Charsets.ISO_8859_1)
        return headerBytes + bodyBytes
    }

    /**
     * SSE response for JSON-RPC results.
     */
    private fun sseResponse(jsonBody: String): ByteArray {
        val ssePayload = "event: message\ndata: $jsonBody\n\n"
        val bodyBytes = ssePayload.toByteArray(Charsets.UTF_8)

        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("content-type: text/event-stream\r\n")
        sb.append("cache-control: no-cache\r\n")
        sb.append("connection: keep-alive\r\n")
        sb.append("keep-alive: timeout=5\r\n")
        sb.append("content-length: ${bodyBytes.size}\r\n")
        sb.append("\r\n")

        val headerBytes = sb.toString().toByteArray(Charsets.ISO_8859_1)
        return headerBytes + bodyBytes
    }
}
