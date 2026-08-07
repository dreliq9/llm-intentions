package com.androidmcp.hub.stdio

import android.util.Log
import com.androidmcp.core.protocol.JsonRpcError
import com.androidmcp.core.protocol.JsonRpcRequest
import com.androidmcp.core.protocol.JsonRpcResponse
import com.androidmcp.core.protocol.MCP_MODERN_PROTOCOL_VERSION
import com.androidmcp.core.transport.BearerTokenAuthenticator
import com.androidmcp.core.transport.McpHttpRequestValidator
import com.androidmcp.hub.HubMcpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Minimal Streamable HTTP server with a dual-era compatibility path:
 *
 * - MCP 2026-07-28: stateless POSTs, mirrored-header validation, JSON responses.
 * - MCP 2025-06-18: existing initialize-era behavior and SSE response compatibility.
 *
 * Security invariants:
 * - Bind loopback only. Remote access belongs behind the authenticated relay.
 * - Require an app-private bearer credential even on loopback; localhost is not caller identity.
 * - Resolve the expected bearer credential per request so rotation revokes prior configs instantly.
 * - Validate browser Origin to block DNS rebinding.
 * - Parse Content-Length as bytes, not decoded characters.
 * - Bound header/body sizes and reject unsupported transfer encodings.
 * - Reject ambiguous duplicate security-sensitive headers.
 */
internal class McpRawHttpServer(
    private val port: Int,
    private val engine: HubMcpEngine,
    private val json: Json,
    private val accessTokenProvider: () -> String,
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class ParsedHttpRequest(
        val method: String,
        val uri: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private class HttpParseException(
        val statusCode: Int,
        val statusText: String,
        val errorCode: Int,
        override val message: String,
    ) : Exception(message)

    fun start() {
        scope.launch {
            serverSocket = ServerSocket(port, 50, InetAddress.getByName(LOOPBACK_HOST))
            Log.i(TAG, "Listening on authenticated $LOOPBACK_HOST:$port")

            while (isActive) {
                val client = try {
                    serverSocket?.accept() ?: break
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "accept() failed", e)
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
                sock.soTimeout = SOCKET_TIMEOUT_MS
                val out = sock.outputStream

                val request = try {
                    readHttpRequest(sock.inputStream) ?: return
                } catch (e: HttpParseException) {
                    out.write(jsonRpcHttpError(
                        statusCode = e.statusCode,
                        statusText = e.statusText,
                        code = e.errorCode,
                        message = e.message,
                    ))
                    out.flush()
                    return
                }

                Log.i(TAG, ">>> ${request.method} ${request.uri}")

                if (!McpHttpRequestValidator.isAllowedLocalOrigin(request.headers["origin"])) {
                    Log.w(TAG, "Rejected non-loopback Origin: ${request.headers["origin"]}")
                    out.write(jsonRpcHttpError(
                        statusCode = 403,
                        statusText = "Forbidden",
                        code = JsonRpcError.INVALID_REQUEST,
                        message = "Origin is not allowed for the local MCP endpoint",
                    ))
                    out.flush()
                    return
                }

                if (!BearerTokenAuthenticator.matches(
                        accessTokenProvider(),
                        request.headers["authorization"],
                    )
                ) {
                    Log.w(TAG, "Rejected unauthenticated localhost MCP request")
                    out.write(unauthorizedResponse())
                    out.flush()
                    return
                }

                val response = when {
                    request.uri != "/mcp" -> httpResponse(404, "Not Found", "Not found")
                    request.method == "DELETE" || request.method == "GET" ->
                        httpResponse(405, "Method Not Allowed", "Method not allowed in stateless mode")
                    request.method == "POST" -> handlePost(request.body, request.headers)
                    else -> httpResponse(405, "Method Not Allowed", "Method not allowed")
                }

                out.write(response)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
        }
    }

    private fun readHttpRequest(input: InputStream): ParsedHttpRequest? {
        val headerBytes = readHeaderBlock(input) ?: return null
        val headerText = String(headerBytes, Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty() || lines.first().isBlank()) {
            throw badRequest("Missing HTTP request line")
        }

        val requestParts = lines.first().split(" ", limit = 3)
        if (requestParts.size != 3 || !requestParts[2].startsWith("HTTP/")) {
            throw badRequest("Malformed HTTP request line")
        }

        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            if (line.startsWith(' ') || line.startsWith('\t')) {
                throw badRequest("Obsolete folded HTTP headers are not accepted")
            }
            val colon = line.indexOf(':')
            if (colon <= 0) throw badRequest("Malformed HTTP header")

            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (!HEADER_NAME.matches(name)) throw badRequest("Invalid HTTP header name")
            if (value.any { (it.code < 0x20 && it != '\t') || it.code == 0x7f }) {
                throw badRequest("Invalid control character in HTTP header value")
            }
            if (name in SINGLETON_HEADERS && headers.containsKey(name)) {
                throw badRequest("Duplicate $name header is not accepted")
            }
            headers[name] = value
        }

        if (headers.containsKey("transfer-encoding")) {
            throw badRequest("Transfer-Encoding is not supported; send Content-Length")
        }

        val contentLength = headers["content-length"]?.let {
            it.toLongOrNull() ?: throw badRequest("Invalid Content-Length")
        } ?: 0L
        if (contentLength < 0) throw badRequest("Negative Content-Length")
        if (contentLength > MAX_REQUEST_BODY_BYTES) {
            throw HttpParseException(
                statusCode = 413,
                statusText = "Payload Too Large",
                errorCode = JsonRpcError.INVALID_REQUEST,
                message = "Request body exceeds $MAX_REQUEST_BODY_BYTES bytes",
            )
        }

        val bodyBytes = ByteArray(contentLength.toInt())
        var offset = 0
        while (offset < bodyBytes.size) {
            val read = input.read(bodyBytes, offset, bodyBytes.size - offset)
            if (read < 0) throw badRequest("Truncated HTTP request body")
            if (read == 0) continue
            offset += read
        }

        val body = decodeUtf8(bodyBytes)
        return ParsedHttpRequest(
            method = requestParts[0],
            uri = requestParts[1],
            headers = headers,
            body = body,
        )
    }

    private fun readHeaderBlock(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        var matched = 0

        while (true) {
            val value = input.read()
            if (value < 0) {
                if (output.size() == 0) return null
                throw badRequest("Truncated HTTP headers")
            }
            output.write(value)
            if (output.size() > MAX_HEADER_BYTES) {
                throw HttpParseException(
                    statusCode = 431,
                    statusText = "Request Header Fields Too Large",
                    errorCode = JsonRpcError.INVALID_REQUEST,
                    message = "HTTP headers exceed $MAX_HEADER_BYTES bytes",
                )
            }

            val byte = value.toByte()
            matched = if (byte == terminator[matched]) {
                matched + 1
            } else if (byte == terminator[0]) {
                1
            } else {
                0
            }
            if (matched == terminator.size) break
        }

        val raw = output.toByteArray()
        return raw.copyOf(raw.size - terminator.size)
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            throw badRequest("Request body is not valid UTF-8")
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
            val validation = McpHttpRequestValidator.validate(request, headers)
            validation.failure?.let { failure ->
                return validationFailure(request, failure)
            }

            if (request.id == null) {
                try {
                    engine.dispatcher.dispatch(request)
                } catch (e: Exception) {
                    Log.w(TAG, "Notification dispatch failed (non-fatal)", e)
                }
                return httpResponse(202, "Accepted", "")
            }

            val response = try {
                engine.dispatcher.dispatch(request)
            } catch (e: Exception) {
                Log.e(TAG, "Dispatch failed for ${request.method}", e)
                JsonRpcResponse(
                    id = request.id,
                    error = JsonRpcError(
                        code = JsonRpcError.INTERNAL_ERROR,
                        message = e.message ?: "Internal error",
                    ),
                )
            }

            if (response == null) return httpResponse(202, "Accepted", "")

            val responseJson = json.encodeToString(JsonRpcResponse.serializer(), response)
            val statusCode = when {
                validation.modern && response.error?.code == JsonRpcError.METHOD_NOT_FOUND -> 404
                validation.modern && response.error?.code == JsonRpcError.MISSING_REQUIRED_CLIENT_CAPABILITY -> 400
                validation.modern && response.error?.code == JsonRpcError.UNSUPPORTED_PROTOCOL_VERSION -> 400
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
                sseResponse(responseJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing request", e)
            val modern = headers["mcp-protocol-version"] == MCP_MODERN_PROTOCOL_VERSION
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

    private fun validationFailure(
        request: JsonRpcRequest,
        failure: McpHttpRequestValidator.Failure,
    ): ByteArray {
        val response = JsonRpcResponse(
            id = request.id,
            error = JsonRpcError(
                code = failure.errorCode,
                message = failure.message,
                data = failure.data,
            ),
        )
        return jsonResponse(
            failure.statusCode,
            failure.statusText,
            json.encodeToString(JsonRpcResponse.serializer(), response),
        )
    }

    private fun badRequest(message: String) = HttpParseException(
        statusCode = 400,
        statusText = "Bad Request",
        errorCode = JsonRpcError.INVALID_REQUEST,
        message = message,
    )

    private fun unauthorizedResponse(): ByteArray {
        val response = JsonRpcResponse(
            error = JsonRpcError(
                code = JsonRpcError.INVALID_REQUEST,
                message = "Authentication required for local MCP access",
            ),
            id = null,
        )
        return httpResponse(
            statusCode = 401,
            statusText = "Unauthorized",
            body = json.encodeToString(JsonRpcResponse.serializer(), response),
            contentType = "application/json",
            extraHeaders = mapOf("www-authenticate" to "Bearer realm=\"llm-intentions-local\""),
        )
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
        contentType: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            if (contentType != null && body.isNotEmpty()) {
                append("content-type: $contentType\r\n")
            }
            for ((name, value) in extraHeaders) {
                append("$name: $value\r\n")
            }
            append("content-length: ${bodyBytes.size}\r\n")
            append("connection: close\r\n")
            append("\r\n")
        }
        return headers.toByteArray(Charsets.ISO_8859_1) + bodyBytes
    }

    /** Legacy SSE framing retained only for initialize-era client compatibility. */
    private fun sseResponse(jsonBody: String): ByteArray {
        val ssePayload = "event: message\ndata: $jsonBody\n\n"
        val bodyBytes = ssePayload.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("content-type: text/event-stream\r\n")
            append("cache-control: no-cache\r\n")
            append("connection: close\r\n")
            append("content-length: ${bodyBytes.size}\r\n")
            append("\r\n")
        }
        return headers.toByteArray(Charsets.ISO_8859_1) + bodyBytes
    }

    companion object {
        private const val TAG = "LLM-Http"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_REQUEST_BODY_BYTES = 1_048_576L

        private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        private val SINGLETON_HEADERS = setOf(
            "authorization",
            "content-length",
            "transfer-encoding",
            "origin",
            "mcp-protocol-version",
            "mcp-method",
            "mcp-name",
        )
    }
}
