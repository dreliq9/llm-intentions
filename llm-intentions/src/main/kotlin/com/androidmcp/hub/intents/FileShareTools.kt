package com.androidmcp.hub.intents

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import kotlinx.serialization.json.*
import java.io.File
import android.util.Base64

/**
 * MCP tools for sharing files (images, PDFs, etc.) via Android's share sheet.
 *
 * Two modes:
 * - android.share_file: share a file already on the device by path
 * - android.share_content: share base64-encoded data (small files only, ~1MB limit)
 *
 * Both use FileProvider to create content:// URIs with temporary read permission.
 */
class FileShareTools(private val context: Context) {

    companion object {
        const val AUTHORITY = "com.androidmcp.hub.fileprovider"
        const val CACHE_DIR = "mcp_share"
        // Android IPC Bundle practical limit. The actual Binder limit is 1MB
        // for the entire transaction, and base64 inflates by ~33%, so cap at 700KB
        // of decoded data to stay safe.
        const val MAX_BASE64_BYTES = 700_000
    }

    fun registerAll(registry: ToolRegistry) {
        registerShareFile(registry)
        registerShareContent(registry)
    }

    /**
     * Option A: Share a file that's already on the device.
     * Claude Code in Termux can write files to shared storage, then call this.
     */
    private fun registerShareFile(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "android.share_file",
                description = "Share a file from the device via Android share sheet. " +
                    "Provide the absolute path to the file. Works with any file type " +
                    "(images, PDFs, documents, etc.).",
                inputSchema = jsonSchema {
                    string("path", "Absolute path to the file (e.g., /sdcard/Download/report.pdf)")
                    string("mime_type", "MIME type (e.g., image/png, application/pdf). " +
                        "If omitted, guessed from file extension.", required = false)
                    string("title", "Title for the share chooser", required = false)
                }
            ),
            handler = { args ->
                val path = args["path"]?.jsonPrimitive?.content
                    ?: return@McpToolDef ToolCallResult(
                        content = listOf(ContentBlock.text("Missing required parameter: path")),
                        isError = true
                    )

                val file = File(path)
                if (!file.exists()) {
                    return@McpToolDef ToolCallResult(
                        content = listOf(ContentBlock.text("File not found: $path")),
                        isError = true
                    )
                }
                if (!file.canRead()) {
                    return@McpToolDef ToolCallResult(
                        content = listOf(ContentBlock.text("Cannot read file: $path (permission denied)")),
                        isError = true
                    )
                }

                val mimeType = args["mime_type"]?.jsonPrimitive?.contentOrNull
                    ?: guessMimeType(file.name)
                    ?: "application/octet-stream"
                val title = args["title"]?.jsonPrimitive?.contentOrNull ?: "Share"

                val uri = try {
                    FileProvider.getUriForFile(context, AUTHORITY, file)
                } catch (e: IllegalArgumentException) {
                    return@McpToolDef ToolCallResult(
                        content = listOf(ContentBlock.text(
                            "Cannot share file: path is outside FileProvider's configured directories. " +
                            "File must be in shared storage (e.g., /sdcard/Download/)."
                        )),
                        isError = true
                    )
                }

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(
                    Intent.createChooser(intent, title)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )

                ToolCallResult(content = listOf(ContentBlock.text(
                    "Share sheet opened for: ${file.name} ($mimeType, ${formatSize(file.length())})"
                )))
            }
        ))
    }

    /**
     * Option B: Share base64-encoded content.
     * Hub decodes it to a temp file and shares via FileProvider.
     * Limited to ~700KB decoded (Binder transaction limit).
     */
    private fun registerShareContent(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "android.share_content",
                description = "Share base64-encoded content via Android share sheet. " +
                    "For small files only (~700KB max after decoding). " +
                    "For larger files, write to storage first and use android.share_file instead.",
                inputSchema = jsonSchema {
                    string("data", "Base64-encoded file content")
                    string("filename", "Filename with extension (e.g., chart.png, report.pdf)")
                    string("mime_type", "MIME type (e.g., image/png, application/pdf). " +
                        "If omitted, guessed from filename.", required = false)
                    string("title", "Title for the share chooser", required = false)
                }
            ),
            handler = { args ->
                val data = args["data"]?.jsonPrimitive?.content
                    ?: return@McpToolDef ToolCallResult(
                        content = listOf(ContentBlock.text("Missing required parameter: data")),
                        isError = true
                    )
                val filename = args["filename"]?.jsonPrimitive?.content
                    ?: return@McpToolDef ToolCallResult(
                        content = listOf(ContentBlock.text("Missing required parameter: filename")),
                        isError = true
                    )

                val decoded = try {
                    Base64.decode(data, Base64.DEFAULT)
                } catch (e: Exception) {
                    return@McpToolDef ToolCallResult(
                        content = listOf(ContentBlock.text("Invalid base64 data: ${e.message}")),
                        isError = true
                    )
                }

                if (decoded.size > MAX_BASE64_BYTES) {
                    return@McpToolDef ToolCallResult(
                        content = listOf(ContentBlock.text(
                            "Data too large: ${formatSize(decoded.size.toLong())} " +
                            "(max ${formatSize(MAX_BASE64_BYTES.toLong())}). " +
                            "Write to storage and use android.share_file instead."
                        )),
                        isError = true
                    )
                }

                // Write to cache dir
                val cacheDir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
                val tempFile = File(cacheDir, filename)
                tempFile.writeBytes(decoded)

                val mimeType = args["mime_type"]?.jsonPrimitive?.contentOrNull
                    ?: guessMimeType(filename)
                    ?: "application/octet-stream"
                val title = args["title"]?.jsonPrimitive?.contentOrNull ?: "Share"

                val uri = FileProvider.getUriForFile(context, AUTHORITY, tempFile)

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(
                    Intent.createChooser(intent, title)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )

                ToolCallResult(content = listOf(ContentBlock.text(
                    "Share sheet opened for: $filename ($mimeType, ${formatSize(decoded.size.toLong())})"
                )))
            }
        ))
    }

    private fun guessMimeType(filename: String): String? {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    }
}
