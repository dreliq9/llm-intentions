package com.androidmcp.hub.stdio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.androidmcp.core.protocol.JsonRpcError
import com.androidmcp.core.protocol.JsonRpcRequest
import com.androidmcp.core.protocol.JsonRpcResponse
import com.androidmcp.core.protocol.MCP_LEGACY_PROTOCOL_VERSION
import com.androidmcp.core.protocol.MCP_PROTOCOL_VERSION
import com.androidmcp.core.protocol.SUPPORTED_MCP_PROTOCOL_VERSIONS
import com.androidmcp.hub.HubMcpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Base64

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

            Log.i(TAG, "HTTP server started on 127.0.0.1:$PORT with ${engine.registry.size()} tools")
            updateNotification("Running — ${engine.registry.size()} tools on localhost:$PORT")
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
                description = "Keeps LLM Intentions running for local MCP clients"
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
 * Minimal Streamable HTTP server with a dual-era compatibility path:
 *
 * - MCP 2026-07-28: stateless POSTs, header/body validation, JSON responses.
 * - MCP 2025-06-18: legacy request shape and SSE response compatibility.
 *
 * Security invariants:
 * - The socket binds to loopback only.
 * - Browser Origin values must also be loopback origins.
 * - Modern mirrored headers must match the JSON-RPC body.
 * - Request bodies are size bounded.
 *
 * Remote access must be provided by a separately authenticated relay. Do not change
 * this listener back to 0.0.0.0 to expose the Hub directly to a LAN or the Internet.
 */
private class McpRawHttpServer(
    private val port: Int,
    private val engine: HubMcpEngine,
    private val json: Json
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class RequestValidation(
        val modern: Boolean,
        val errorResponse: ByteArray? = null,
    )

    fun start() {
        scope.launch {
            serverSocket = ServerSocket(port, 50, InetAddress.getByName(LOOPBACK_HOST))
            Log.i("LLM-Http", "Listening on $LOOPBACK_HOST:$port")

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

                val requestLine = reader.readLine() ?: return
                Log.i("LLM-Http", ">>> $requestLine")

                val parts = requestLine.split(" ", limit = 3)
                if (parts.size < 2) return

                val method = parts[0]
                val uri = parts[1]

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

                val origin = headers["origin"]
                if (origin != null && !isAllowedOrigin(origin)) {
                    Log.w("LLM-Http", "Rejected non-loopback Origin: $origin")
                    out.write(jsonRpcHttpError(
                        statusCode = 403,
                        statusText = "Forbidden",
                        code = JsonRpcError.INVALID_REQUEST,
                        message = "Origin is not allowed for the local MCP endpoint",
                    ))
                    out.flush()
                    return
                }

                val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                if (contentLength < 0 || contentLength > MAX_REQUEST_BODY_BYTES) {
                    out.write(jsonRpcHttpError(
                        statusCode = 413,
                        statusText = "Payload Too Large",
                        code = JsonRpcError.INVALID_REQUEST,
                        message = "Request body exceeds $MAX_REQUEST_BODY_BYTES bytes",
                    ))
                    out.flush()
                    return
                }

                val body = if (contentLength > 0) {
                    val length = contentLength.toInt()
                    val buf = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val n = reader.read(buf, read, length - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                val response = when {
                    uri != "/mcp" -> httpResponse(404, "Not Found", "Not found")
                    method == "DELETE" || method == "GET" ->
                        httpResponse(405, "Method Not Allowed", "Method not allowed in stateless mode")
                    method == "POST" -> handlePost(body, headers)
                    else -> httpResponse(405, "Method Not Allowed", "Method not allowed")
                }

                out.write(response)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e("LLM-Http", "Connection error", e)
        }
    }

    private suspend fun handlePost(body: String, headers: Map<String, String>): ByteArray {
        if (body.isBlank()) {
            return jsonRpcHttpError(
                statusCode = 400,
                statusText = "Bad Request",
                code = JsonRpcError.PARSE_ERROR,
                message = "Empty request body",
            )
        }

        return try {
            val request = json.decodeFromString<JsonRpcRequest>(body)
            val validation = validateRequest(request, headers)
            validation.errorResponse?.let { return it }

            if (request.id == null) {
                try {
                    engine.dispatcher.dispatch(request)
                } catch (e: Exception) {
                    Log.w("LLM-Http", "Notification dispatch failed (non-fatal)", e)
                }
                return httpResponse(202, "Accepted", "")
            }

            val response = try {
                engine.dispatcher.dispatch(request)
            } catch (e: Exception) {
                Log.e("LLM-Http", "Dispatch failed for ${request.method}", e)
                JsonRpcResponse(
                    id = request.id,
                    error = JsonRpcError(
                        code = JsonRpcError.INTERNAL_ERROR,
                        message = e.message ?: "Internal error"
                    )
                )
            }

            if (response == null) {
                return httpResponse(202, "Accepted", "")
            }

            val responseJson = json.encodeToString(JsonRpcResponse.serializer(), response)
            val statusCode = when {
                validation.modern && response.error?.code == JsonRpcError.METHOD_NOT_FOUND -> 404
                validation.modern && response.error?.code == JsonRpcError.MISSING_REQUIRED_CLIENT_CAPABILITY -> 400
                else -> 200
            }
            val statusText = when (statusCode) {
                400 -> "Bad Request"
                404 -> "Not Found"
                else -> "OK"
            }

            if (validation.modern) {
                jsonResponse(statusCode, statusText, responseJson)
            } else {
                // Preserve the response framing that existing deployed clients already use.
                sseResponse(responseJson)
            }
        } catch (e: Exception) {
            Log.e("LLM-Http", "Error processing request", e)
            val modern = headers["mcp-protocol-version"] == MCP_PROTOCOL_VERSION
            val error = JsonRpcResponse(
                error = JsonRpcError(
                    code = JsonRpcError.PARSE_ERROR,
                    message = e.message ?: "Parse error",
                ),
                id = null,
            )
            val payload = json.encodeToString(JsonRpcResponse.serializer(), error)
            if (modern) jsonResponse(400, "Bad Request", payload)
            else httpResponse(400, "Bad Request", payload, contentType = "application/json")
        }
    }

    private fun validateRequest(request: JsonRpcRequest, headers: Map<String, String>): RequestValidation {
        val headerVersion = headers["mcp-protocol-version"]
        val bodyVersion = request.params
            ?.get("_meta")
            ?.let { it as? JsonObject }
            ?.get("io.modelcontextprotocol/protocolVersion")
            ?.jsonPrimitive
            ?.contentOrNull

        val requestedVersion = headerVersion ?: bodyVersion
        if (requestedVersion != null && requestedVersion !in SUPPORTED_MCP_PROTOCOL_VERSIONS) {
            return RequestValidation(
                modern = true,
                errorResponse = unsupportedVersion(request.id, requestedVersion),
            )
        }

        val modern = headerVersion == MCP_PROTOCOL_VERSION || bodyVersion == MCP_PROTOCOL_VERSION ||
            request.method == "server/discover"

        if (!modern) {
            // Existing 2025-06-18 clients continue through the compatibility path.
            return RequestValidation(modern = false)
        }

        if (headerVersion != MCP_PROTOCOL_VERSION || bodyVersion != MCP_PROTOCOL_VERSION) {
            return RequestValidation(
                modern = true,
                errorResponse = headerMismatch(
                    request.id,
                    "MCP-Protocol-Version must match params._meta.io.modelcontextprotocol/protocolVersion",
                ),
            )
        }

        val methodHeader = headers["mcp-method"]
        if (methodHeader == null || methodHeader != request.method) {
            return RequestValidation(
                modern = true,
                errorResponse = headerMismatch(
                    request.id,
                    "Mcp-Method header must match JSON-RPC method '${request.method}'",
                ),
            )
        }

        val expectedName = when (request.method) {
            "tools/call", "prompts/get" -> request.params?.get("name")?.jsonPrimitive?.contentOrNull
            "resources/read" -> request.params?.get("uri")?.jsonPrimitive?.contentOrNull
            else -> null
        }

        if (expectedName != null) {
            val rawNameHeader = headers["mcp-name"]
            val decodedName = rawNameHeader?.let(::decodeHeaderValue)
            if (decodedName == null || decodedName != expectedName) {
                return RequestValidation(
                    modern = true,
                    errorResponse = headerMismatch(
                        request.id,
                        "Mcp-Name header must match request name/uri",
                    ),
                )
            }
        }

        return RequestValidation(modern = true)
    }

    private fun unsupportedVersion(id: kotlinx.serialization.json.JsonElement?, requested: String): ByteArray {
        val error = JsonRpcResponse(
            id = id,
            error = JsonRpcError(
                code = JsonRpcError.UNSUPPORTED_PROTOCOL_VERSION,
                message = "Unsupported MCP protocol version: $requested",
                data = buildJsonObject {
                    put("supported", kotlinx.serialization.json.JsonArray(
                        SUPPORTED_MCP_PROTOCOL_VERSIONS.map(::JsonPrimitive)
                    ))
                    put("requested", requested)
                },
            ),
        )
        return jsonResponse(
            400,
            "Bad Request",
            json.encodeToString(JsonRpcResponse.serializer(), error),
        )
    }

    private fun headerMismatch(id: kotlinx.serialization.json.JsonElement?, message: String): ByteArray {
        val error = JsonRpcResponse(
            id = id,
            error = JsonRpcError(
                code = JsonRpcError.HEADER_MISMATCH,
                message = "Header mismatch: $message",
            ),
        )
        return jsonResponse(
            400,
            "Bad Request",
            json.encodeToString(JsonRpcResponse.serializer(), error),
        )
    }

    private fun decodeHeaderValue(value: String): String? {
        if (!value.startsWith(BASE64_PREFIX) || !value.endsWith(BASE64_SUFFIX)) return value
        val payload = value.substring(BASE64_PREFIX.length, value.length - BASE64_SUFFIX.length)
        return try {
            String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isAllowedOrigin(origin: String): Boolean {
        return try {
            val host = URI(origin).host?.lowercase() ?: return false
            host == "localhost" || host == "127.0.0.1" || host == "::1"
        } catch (_: Exception) {
            false
        }
    }

    private fun jsonRpcHttpError(
        statusCode: Int,
        statusText: String,
        code: Int,
        message: String,
    ): ByteArray {
        val response = JsonRpcResponse(
            error = JsonRpcError(code = code, message = message),
            id = null,
        )
        return jsonResponse(
            statusCode,
            statusText,
            json.encodeToString(JsonRpcResponse.serializer(), response),
        )
    }

    private fun jsonResponse(statusCode: Int, statusText: String, body: String): ByteArray =
        httpResponse(statusCode, statusText, body, contentType = "application/json")

    private fun httpResponse(
        statusCode: Int,
        statusText: String,
        body: String,
        contentType: String? = null
    ): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $statusCode $statusText\r\n")
        if (contentType != null && body.isNotEmpty()) {
            sb.append("content-type: $contentType\r\n")
        }
        sb.append("content-length: ${bodyBytes.size}\r\n")
        sb.append("connection: close\r\n")
        sb.append("\r\n")

        val headerBytes = sb.toString().toByteArray(Charsets.ISO_8859_1)
        return headerBytes + bodyBytes
    }

    /** Legacy SSE framing retained only for initialize-era client compatibility. */
    private fun sseResponse(jsonBody: String): ByteArray {
        val ssePayload = "event: message\ndata: $jsonBody\n\n"
        val bodyBytes = ssePayload.toByteArray(Charsets.UTF_8)

        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("content-type: text/event-stream\r\n")
        sb.append("cache-control: no-cache\r\n")
        sb.append("connection: close\r\n")
        sb.append("content-length: ${bodyBytes.size}\r\n")
        sb.append("\r\n")

        val headerBytes = sb.toString().toByteArray(Charsets.ISO_8859_1)
        return headerBytes + bodyBytes
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val MAX_REQUEST_BODY_BYTES = 1_048_576L
        private const val BASE64_PREFIX = "=?base64?"
        private const val BASE64_SUFFIX = "?="
    }
}
