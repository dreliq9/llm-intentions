package com.llmintentions.files.dev

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.androidmcp.core.protocol.LatencyClass
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import com.androidmcp.core.registry.textTool
import com.androidmcp.core.registry.toolMetadata
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Shared tool registration logic used by both FilesDevToolService (for LLM intents)
 * and FilesDevActivity (for in-process UI execution).
 */
object FilesDevToolRegistrar {

    fun register(registry: ToolRegistry, ctx: Context) {
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
        registry.textTool(
            name = "fs_read",
            description = "Read a text file from any path on the device",
            params = jsonSchema {
                string("path", "Absolute file path")
                integer("max_bytes", "Max bytes to read (default 1MB)", required = false)
            },
            metadata = toolMetadata {
                destructive = false
                idempotent = true
                latencyClass = LatencyClass.FAST
                permission("MANAGE_EXTERNAL_STORAGE")
                failureMode(
                    exceptionType = "FileNotFoundException",
                    hint = "Check the path exists; use an absolute path.",
                )
                failureMode(
                    pattern = "Permission denied",
                    hint = "Grant 'All files access' to LLM File Tools (Dev) in Settings > Apps > Permissions.",
                )
                example(intent = "Read a text note from the device Downloads folder.") { args ->
                    args["path"] = "/sdcard/Download/note.txt"
                }
            },
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
        registry.textTool(
            name = "fs_write",
            description = "Write text to any path on the device",
            params = jsonSchema {
                string("path", "Absolute file path")
                string("content", "Text content to write")
                boolean("append", "Append instead of overwrite (default false)", required = false)
            },
            metadata = toolMetadata {
                destructive = true
                idempotent = true
                latencyClass = LatencyClass.FAST
                permission("MANAGE_EXTERNAL_STORAGE")
                failureMode(
                    pattern = "Permission denied|operation not permitted",
                    hint = "Grant 'All files access' to LLM File Tools (Dev) in System Settings > Apps > LLM File Tools (Dev) > Permissions.",
                )
                failureMode(
                    pattern = "No space left|ENOSPC",
                    hint = "Device storage is full — free space and retry.",
                )
                failureMode(
                    exceptionType = "FileNotFoundException",
                    hint = "Parent directory doesn't exist. Use an absolute path whose parent already exists (e.g., /sdcard/Download/...).",
                )
                example(intent = "Write a text note to the device Downloads folder.") { args ->
                    args["path"] = "/sdcard/Download/note.txt"
                    args["content"] = "hello"
                }
            },
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
        registry.textTool(
            name = "fs_delete",
            description = "Delete a file or empty directory",
            params = jsonSchema { string("path", "Absolute path to delete") },
            metadata = toolMetadata {
                destructive = true
                idempotent = true
                latencyClass = LatencyClass.FAST
                permission("MANAGE_EXTERNAL_STORAGE")
                failureMode(
                    pattern = "Permission denied",
                    hint = "Grant 'All files access' to LLM File Tools (Dev) in System Settings > Apps > LLM File Tools (Dev) > Permissions.",
                )
                failureMode(
                    exceptionType = "FileNotFoundException",
                    hint = "File is already gone — no-op.",
                )
            },
        ) { args ->
            val path = args["path"]?.jsonPrimitive?.content ?: ""
            val file = File(path)
            if (!file.exists()) return@textTool "Not found: $path"
            if (file.isDirectory && (file.listFiles()?.isNotEmpty() == true))
                return@textTool "Directory not empty: $path (use fs_delete_recursive)"
            if (file.delete()) "Deleted: $path" else "Failed to delete: $path"
        }

        // --- fs_delete_recursive ---
        registry.textTool("fs_delete_recursive", "Recursively delete a directory and all its contents",
            jsonSchema { string("path", "Absolute path to directory") }
        ) { args ->
            val path = args["path"]?.jsonPrimitive?.content ?: ""
            val dir = File(path)
            if (!dir.exists()) return@textTool "Not found: $path"
            if (!dir.isDirectory) return@textTool "Not a directory: $path (use fs_delete for files)"
            val count = countFiles(dir)
            if (dir.deleteRecursively()) "Deleted: $path ($count items removed)"
            else "Partial delete of $path — some files may remain"
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
        registry.textTool(
            name = "fs_tree",
            description = "Show directory tree (like the tree command)",
            params = jsonSchema {
                string("path", "Root directory")
                integer("depth", "Max depth (default 3)", required = false)
            },
            metadata = toolMetadata {
                destructive = false
                idempotent = true
                latencyClass = LatencyClass.SLOW
                failureMode(
                    pattern = "Permission denied",
                    hint = "Enable 'All files access' for LLM File Tools (Dev) in System Settings > Apps > LLM File Tools (Dev) > Permissions.",
                )
                example(intent = "Show the directory tree of the Downloads folder.") { args ->
                    args["path"] = "/sdcard/Download"
                }
            },
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

        // --- fs_grep ---
        registry.textTool(
            name = "fs_grep",
            description = "Search file contents for a text pattern",
            params = jsonSchema {
                string("path", "Directory to search in")
                string("pattern", "Text to search for (case-insensitive)")
                string("glob", "Filename filter, e.g. *.txt, *.json (default: all files)", required = false)
                integer("max_results", "Max matching files (default 20)", required = false)
                integer("context_lines", "Lines of context around each match (default 1)", required = false)
            },
            metadata = toolMetadata {
                destructive = false
                idempotent = true
                latencyClass = LatencyClass.SLOW
                failureMode(
                    pattern = "Permission denied",
                    hint = "Enable 'All files access' for LLM File Tools (Dev) in System Settings > Apps > LLM File Tools (Dev) > Permissions.",
                )
                example(intent = "Search for the word 'hello' in files under Downloads.") { args ->
                    args["path"] = "/sdcard/Download"
                    args["pattern"] = "hello"
                }
            },
        ) { args ->
            val root = File(args["path"]?.jsonPrimitive?.content ?: "/sdcard")
            val pattern = args["pattern"]?.jsonPrimitive?.content ?: ""
            val glob = args["glob"]?.jsonPrimitive?.contentOrNull
            val maxResults = args["max_results"]?.jsonPrimitive?.intOrNull ?: 20
            val contextLines = args["context_lines"]?.jsonPrimitive?.intOrNull ?: 1

            if (!root.exists()) return@textTool "Path not found: ${root.path}"
            if (pattern.isEmpty()) return@textTool "Pattern required"

            val matches = mutableListOf<JsonObject>()
            val regex = Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE)

            fun searchFile(file: File) {
                if (matches.size >= maxResults) return
                if (file.length() > 2_000_000) return
                try {
                    val lines = file.readLines()
                    val hitLines = mutableListOf<JsonObject>()
                    lines.forEachIndexed { idx, line ->
                        if (regex.containsMatchIn(line)) {
                            val start = maxOf(0, idx - contextLines)
                            val end = minOf(lines.size - 1, idx + contextLines)
                            val snippet = (start..end).joinToString("\n") { i ->
                                "${i + 1}: ${lines[i]}"
                            }
                            hitLines.add(buildJsonObject {
                                put("line", idx + 1)
                                put("snippet", snippet)
                            })
                        }
                    }
                    if (hitLines.isNotEmpty()) {
                        matches.add(buildJsonObject {
                            put("file", file.absolutePath)
                            put("hits", JsonArray(hitLines))
                        })
                    }
                } catch (_: Exception) { }
            }

            fun walk(dir: File) {
                if (matches.size >= maxResults) return
                val children = dir.listFiles() ?: return
                for (f in children) {
                    if (matches.size >= maxResults) return
                    if (f.isDirectory && !f.name.startsWith(".")) {
                        walk(f)
                    } else if (f.isFile) {
                        if (glob != null && !f.name.matches(globToRegex(glob))) continue
                        searchFile(f)
                    }
                }
            }
            walk(root)

            buildJsonObject {
                put("pattern", pattern)
                put("root", root.absolutePath)
                put("matching_files", matches.size)
                put("results", JsonArray(matches))
            }.toString()
        }

        // --- fs_storage_info ---
        registry.textTool("fs_storage_info", "Show storage space and mount points",
            jsonSchema {}
        ) { _ ->
            val internal = Environment.getDataDirectory()
            val external = Environment.getExternalStorageDirectory()
            fun spaceInfo(dir: File): JsonObject {
                val stat = android.os.StatFs(dir.absolutePath)
                val total = stat.totalBytes
                val free = stat.availableBytes
                return buildJsonObject {
                    put("path", dir.absolutePath)
                    put("total_gb", String.format("%.1f", total / 1e9))
                    put("free_gb", String.format("%.1f", free / 1e9))
                    put("used_pct", String.format("%.0f", (total - free) * 100.0 / total))
                }
            }
            buildJsonObject {
                put("internal", spaceInfo(internal))
                put("external", spaceInfo(external))
            }.toString()
        }

        // --- download_file ---
        registry.textTool(
            name = "download_file",
            description = "Download a file from a URL to Downloads folder",
            params = jsonSchema {
                string("url", "URL to download")
                string("filename", "Save as filename", required = false)
            },
            metadata = toolMetadata {
                destructive = true
                idempotent = false
                latencyClass = LatencyClass.VERY_SLOW
                permission("INTERNET")
                permission("MANAGE_EXTERNAL_STORAGE")
                failureMode(
                    exceptionType = "UnknownHostException",
                    hint = "Check device internet connection.",
                )
                failureMode(
                    exceptionType = "SocketTimeoutException",
                    hint = "Server too slow; retry or use a different URL.",
                )
                failureMode(
                    pattern = "HTTP 4",
                    hint = "Server rejected the request — check URL.",
                )
                example(intent = "Download a file from a public URL to the device.") { args ->
                    args["url"] = "https://example.com/file.txt"
                }
            },
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

        // --- downloads_list ---
        registry.textTool("downloads_list", "List recent downloads",
            jsonSchema { integer("limit", "Max results (default 20)", required = false) }
        ) { args ->
            val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                queryMediaStore(ctx, MediaStore.Downloads.EXTERNAL_CONTENT_URI, limit)
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val files = dir.listFiles()?.sortedByDescending { it.lastModified() }?.take(limit)?.map { f ->
                    buildJsonObject {
                        put("name", f.name)
                        put("size_bytes", f.length())
                        put("modified", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(f.lastModified())))
                    }
                } ?: emptyList()
                JsonArray(files).toString()
            }
        }

        // --- file_info ---
        registry.textTool("file_info", "Get metadata for a file by content URI",
            jsonSchema { string("uri", "Content URI (content://...)") }
        ) { args ->
            val uriStr = args["uri"]?.jsonPrimitive?.content ?: return@textTool "URI required"
            val uri = Uri.parse(uriStr)
            val cursor = ctx.contentResolver.query(uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED),
                null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    buildJsonObject {
                        put("name", it.getString(0) ?: "")
                        put("size_bytes", it.getLong(1))
                        put("mime_type", it.getString(2) ?: "")
                        put("modified", df.format(Date(it.getLong(3) * 1000)))
                    }.toString()
                } else "No metadata found for $uriStr"
            } ?: "Cannot query $uriStr"
        }

        // --- media_images ---
        registry.textTool("media_images", "List recent images via MediaStore",
            jsonSchema {
                integer("limit", "Max results (default 20)", required = false)
                string("search", "Filter by filename substring", required = false)
            }
        ) { args ->
            queryMediaStore(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                args["limit"]?.jsonPrimitive?.intOrNull ?: 20,
                args["search"]?.jsonPrimitive?.contentOrNull)
        }

        // --- media_videos ---
        registry.textTool("media_videos", "List recent videos via MediaStore",
            jsonSchema {
                integer("limit", "Max results (default 20)", required = false)
                string("search", "Filter by filename substring", required = false)
            }
        ) { args ->
            queryMediaStore(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                args["limit"]?.jsonPrimitive?.intOrNull ?: 20,
                args["search"]?.jsonPrimitive?.contentOrNull)
        }

        // --- media_audio ---
        registry.textTool("media_audio", "List audio files via MediaStore",
            jsonSchema {
                integer("limit", "Max results (default 20)", required = false)
                string("search", "Filter by filename substring", required = false)
            }
        ) { args ->
            queryMediaStore(ctx, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                args["limit"]?.jsonPrimitive?.intOrNull ?: 20,
                args["search"]?.jsonPrimitive?.contentOrNull)
        }
    }

    // --- Helper functions ---

    private fun queryMediaStore(ctx: Context, uri: Uri, limit: Int, search: String? = null): String {
        val selection = if (search != null) "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" else null
        val selectionArgs = if (search != null) arrayOf("%$search%") else null
        val cursor = ctx.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE),
            selection, selectionArgs,
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
                    put("uri", "$uri/${it.getLong(0)}")
                })
            }
        }
        return JsonArray(files).toString()
    }

    private fun countFiles(dir: File): Int {
        var count = 0
        dir.walkTopDown().forEach { count++ }
        return count - 1
    }

    private fun globToRegex(glob: String): Regex {
        val pattern = buildString {
            append("^")
            for (ch in glob) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append(".")
                    '.' -> append("\\.")
                    else -> append(ch)
                }
            }
            append("$")
        }
        return Regex(pattern, RegexOption.IGNORE_CASE)
    }
}
