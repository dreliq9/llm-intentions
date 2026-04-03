package com.llmintentions.files.dev

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import com.androidmcp.intent.ToolAppService
import com.androidmcp.intent.textTool
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dev version with MANAGE_EXTERNAL_STORAGE — full filesystem access.
 * Namespace: "fs" (distinguishes from the sandboxed "files" app)
 */
class FilesDevToolService : ToolAppService() {

    override fun onCreateTools(registry: ToolRegistry) {
        val ctx = applicationContext

        // --- fs_list ---
        registry.textTool("fs_list", "List files and directories at any path on the device",
            jsonSchema {
                string("path", "Absolute path (e.g., /sdcard, /sdcard/Download, /storage/emulated/0)")
                boolean("hidden", "Include hidden files (default false)", required = false)
            }
        ) { args ->
            val path = args["path"]?.jsonPrimitive?.content ?: "/sdcard"
            val showHidden = args["hidden"]?.jsonPrimitive?.booleanOrNull ?: false
            val dir = File(path)
            if (!dir.exists()) return@textTool "Path not found: $path"
            if (!dir.isDirectory) return@textTool "Not a directory: $path"
            if (!dir.canRead()) return@textTool "Permission denied: $path"

            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val entries = dir.listFiles()
                ?.filter { showHidden || !it.name.startsWith(".") }
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                ?.map { f ->
                    buildJsonObject {
                        put("name", f.name)
                        put("type", if (f.isDirectory) "dir" else "file")
                        put("size", f.length())
                        put("modified", df.format(Date(f.lastModified())))
                        if (f.isDirectory) put("children", f.listFiles()?.size ?: 0)
                    }
                } ?: emptyList()

            buildJsonObject {
                put("path", dir.absolutePath)
                put("count", entries.size)
                put("entries", JsonArray(entries))
            }.toString()
        }

        // --- fs_read ---
        registry.textTool("fs_read", "Read a text file from any path on the device",
            jsonSchema {
                string("path", "Absolute file path")
                integer("max_bytes", "Max bytes to read (default 1MB)", required = false)
            }
        ) { args ->
            val path = args["path"]?.jsonPrimitive?.content ?: ""
            val maxBytes = args["max_bytes"]?.jsonPrimitive?.longOrNull ?: 1_000_000L
            val file = File(path)
            if (!file.exists()) return@textTool "File not found: $path"
            if (!file.canRead()) return@textTool "Permission denied: $path"
            if (file.isDirectory) return@textTool "Is a directory: $path"
            if (file.length() > maxBytes) return@textTool "File too large (${file.length()} bytes, max ${maxBytes}). Use max_bytes to increase."
            file.readText()
        }

        // --- fs_read_bytes ---
        registry.textTool("fs_read_bytes", "Read binary file as base64 from any path",
            jsonSchema {
                string("path", "Absolute file path")
                integer("max_bytes", "Max bytes to read (default 100KB)", required = false)
            }
        ) { args ->
            val path = args["path"]?.jsonPrimitive?.content ?: ""
            val maxBytes = args["max_bytes"]?.jsonPrimitive?.longOrNull ?: 100_000L
            val file = File(path)
            if (!file.exists()) return@textTool "File not found: $path"
            if (!file.canRead()) return@textTool "Permission denied: $path"
            if (file.length() > maxBytes) return@textTool "File too large (${file.length()} bytes, max ${maxBytes})"
            val bytes = file.readBytes()
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }

        // --- fs_write ---
        registry.textTool("fs_write", "Write text to any path on the device",
            jsonSchema {
                string("path", "Absolute file path")
                string("content", "Text content to write")
                boolean("append", "Append instead of overwrite (default false)", required = false)
            }
        ) { args ->
            val path = args["path"]?.jsonPrimitive?.content ?: ""
            val content = args["content"]?.jsonPrimitive?.content ?: ""
            val append = args["append"]?.jsonPrimitive?.booleanOrNull ?: false
            val file = File(path)
            file.parentFile?.mkdirs()
            if (append) file.appendText(content) else file.writeText(content)
            "Written ${content.length} chars to $path"
        }

        // --- fs_delete ---
        registry.textTool("fs_delete", "Delete a file or empty directory",
            jsonSchema { string("path", "Absolute path to delete") }
        ) { args ->
            val path = args["path"]?.jsonPrimitive?.content ?: ""
            val file = File(path)
            if (!file.exists()) return@textTool "Not found: $path"
            if (file.isDirectory && (file.listFiles()?.isNotEmpty() == true))
                return@textTool "Directory not empty: $path (use fs_delete_recursive)"
            if (file.delete()) "Deleted: $path" else "Failed to delete: $path"
        }

        // --- fs_move ---
        registry.textTool("fs_move", "Move or rename a file or directory",
            jsonSchema {
                string("from", "Source path")
                string("to", "Destination path")
            }
        ) { args ->
            val from = File(args["from"]?.jsonPrimitive?.content ?: "")
            val to = File(args["to"]?.jsonPrimitive?.content ?: "")
            if (!from.exists()) return@textTool "Source not found: ${from.path}"
            to.parentFile?.mkdirs()
            if (from.renameTo(to)) "Moved: ${from.path} → ${to.path}"
            else "Failed to move (cross-filesystem? try copy+delete)"
        }

        // --- fs_copy ---
        registry.textTool("fs_copy", "Copy a file",
            jsonSchema {
                string("from", "Source file path")
                string("to", "Destination file path")
            }
        ) { args ->
            val from = File(args["from"]?.jsonPrimitive?.content ?: "")
            val to = File(args["to"]?.jsonPrimitive?.content ?: "")
            if (!from.exists()) return@textTool "Source not found: ${from.path}"
            if (!from.isFile) return@textTool "Not a file: ${from.path}"
            to.parentFile?.mkdirs()
            from.copyTo(to, overwrite = true)
            "Copied: ${from.path} → ${to.path} (${to.length()} bytes)"
        }

        // --- fs_mkdir ---
        registry.textTool("fs_mkdir", "Create a directory (including parents)",
            jsonSchema { string("path", "Directory path to create") }
        ) { args ->
            val path = args["path"]?.jsonPrimitive?.content ?: ""
            val dir = File(path)
            if (dir.exists()) return@textTool "Already exists: $path"
            if (dir.mkdirs()) "Created: $path" else "Failed to create: $path"
        }

        // --- fs_find ---
        registry.textTool("fs_find", "Search for files by name pattern",
            jsonSchema {
                string("path", "Directory to search in")
                string("pattern", "Filename pattern (case-insensitive substring match)")
                integer("max_results", "Max results (default 50)", required = false)
                boolean("recursive", "Search subdirectories (default true)", required = false)
            }
        ) { args ->
            val root = File(args["path"]?.jsonPrimitive?.content ?: "/sdcard")
            val pattern = args["pattern"]?.jsonPrimitive?.content?.lowercase() ?: ""
            val maxResults = args["max_results"]?.jsonPrimitive?.intOrNull ?: 50
            val recursive = args["recursive"]?.jsonPrimitive?.booleanOrNull ?: true

            if (!root.exists()) return@textTool "Path not found: ${root.path}"

            val results = mutableListOf<String>()
            fun search(dir: File) {
                if (results.size >= maxResults) return
                val files = dir.listFiles() ?: return
                for (f in files) {
                    if (results.size >= maxResults) return
                    if (f.name.lowercase().contains(pattern)) {
                        results.add(f.absolutePath)
                    }
                    if (recursive && f.isDirectory && !f.name.startsWith(".")) {
                        search(f)
                    }
                }
            }
            search(root)

            buildJsonObject {
                put("pattern", pattern)
                put("root", root.absolutePath)
                put("count", results.size)
                put("results", JsonArray(results.map { JsonPrimitive(it) }))
            }.toString()
        }

        // --- fs_stat ---
        registry.textTool("fs_stat", "Get detailed info about a file or directory",
            jsonSchema { string("path", "Absolute path") }
        ) { args ->
            val path = args["path"]?.jsonPrimitive?.content ?: ""
            val file = File(path)
            if (!file.exists()) return@textTool "Not found: $path"
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            buildJsonObject {
                put("path", file.absolutePath)
                put("type", if (file.isDirectory) "directory" else "file")
                put("size", file.length())
                put("modified", df.format(Date(file.lastModified())))
                put("readable", file.canRead())
                put("writable", file.canWrite())
                put("executable", file.canExecute())
                put("hidden", file.isHidden)
                if (file.isDirectory) {
                    put("children", file.listFiles()?.size ?: 0)
                }
            }.toString()
        }

        // --- fs_tree ---
        registry.textTool("fs_tree", "Show directory tree (like the tree command)",
            jsonSchema {
                string("path", "Root directory")
                integer("depth", "Max depth (default 3)", required = false)
            }
        ) { args ->
            val root = File(args["path"]?.jsonPrimitive?.content ?: "/sdcard")
            val maxDepth = args["depth"]?.jsonPrimitive?.intOrNull ?: 3
            if (!root.exists()) return@textTool "Not found: ${root.path}"

            val sb = StringBuilder()
            sb.appendLine(root.absolutePath)
            fun tree(dir: File, prefix: String, depth: Int) {
                if (depth >= maxDepth) return
                val children = dir.listFiles()
                    ?.filter { !it.name.startsWith(".") }
                    ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name }) ?: return
                children.forEachIndexed { i, f ->
                    val isLast = i == children.lastIndex
                    val connector = if (isLast) "└── " else "├── "
                    val suffix = if (f.isDirectory) "/" else ""
                    sb.appendLine("$prefix$connector${f.name}$suffix")
                    if (f.isDirectory) {
                        tree(f, prefix + if (isLast) "    " else "│   ", depth + 1)
                    }
                }
            }
            tree(root, "", 0)
            sb.toString()
        }

        // --- download_file (same as regular version) ---
        registry.textTool("download_file", "Download a file from a URL to Downloads folder",
            jsonSchema {
                string("url", "URL to download")
                string("filename", "Save as filename", required = false)
            }
        ) { args ->
            val url = args["url"]?.jsonPrimitive?.content ?: return@textTool "URL required"
            val filename = args["filename"]?.jsonPrimitive?.contentOrNull
                ?: Uri.parse(url).lastPathSegment ?: "download"
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(filename)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            }
            val id = dm.enqueue(request)
            "Download started: $filename (id=$id)"
        }

        // --- media_images (same as regular) ---
        registry.textTool("media_images", "List recent images via MediaStore",
            jsonSchema { integer("limit", "Max results (default 20)", required = false) }
        ) { args ->
            queryMediaStore(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                args["limit"]?.jsonPrimitive?.intOrNull ?: 20)
        }

        // --- media_videos ---
        registry.textTool("media_videos", "List recent videos via MediaStore",
            jsonSchema { integer("limit", "Max results (default 20)", required = false) }
        ) { args ->
            queryMediaStore(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                args["limit"]?.jsonPrimitive?.intOrNull ?: 20)
        }

        // --- media_audio ---
        registry.textTool("media_audio", "List audio files via MediaStore",
            jsonSchema { integer("limit", "Max results (default 20)", required = false) }
        ) { args ->
            queryMediaStore(ctx, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                args["limit"]?.jsonPrimitive?.intOrNull ?: 20)
        }
    }

    private fun queryMediaStore(ctx: Context, uri: Uri, limit: Int): String {
        val cursor = ctx.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE),
            null, null,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )
        val files = mutableListOf<JsonObject>()
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        cursor?.use {
            while (it.moveToNext() && files.size < limit) {
                files.add(buildJsonObject {
                    put("id", it.getLong(0))
                    put("name", it.getString(1) ?: "")
                    put("size_bytes", it.getLong(2))
                    put("modified", df.format(Date(it.getLong(3) * 1000)))
                    put("mime_type", it.getString(4) ?: "")
                })
            }
        }
        return JsonArray(files).toString()
    }
}
