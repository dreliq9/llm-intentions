package com.llmintentions.files

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

class FilesToolService : ToolAppService() {

    override fun onCreateTools(registry: ToolRegistry) {
        val ctx = applicationContext

        // --- app_files_list ---
        registry.textTool("app_files_list", "List files in the app's private storage directory",
            jsonSchema { string("path", "Subdirectory path (default: root)", required = false) }
        ) { args ->
            val subPath = args["path"]?.jsonPrimitive?.contentOrNull ?: ""
            val dir = if (subPath.isEmpty()) ctx.filesDir else File(ctx.filesDir, subPath)
            if (!dir.exists()) return@textTool "Directory not found: $subPath"
            val files = dir.listFiles()?.map { f ->
                buildJsonObject {
                    put("name", f.name)
                    put("type", if (f.isDirectory) "directory" else "file")
                    put("size_bytes", f.length())
                    put("modified", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(f.lastModified())))
                }
            } ?: emptyList()
            buildJsonObject {
                put("path", dir.absolutePath)
                put("count", files.size)
                put("files", JsonArray(files))
            }.toString()
        }

        // --- app_file_read ---
        registry.textTool("app_file_read", "Read a text file from app private storage",
            jsonSchema { string("path", "File path relative to app storage") }
        ) { args ->
            val path = args["path"]?.jsonPrimitive?.content ?: ""
            val file = File(ctx.filesDir, path).canonicalFile
            if (!file.path.startsWith(ctx.filesDir.canonicalPath + File.separator) && file != ctx.filesDir.canonicalFile)
                return@textTool "Invalid path: outside app storage"
            if (!file.exists()) return@textTool "File not found: $path"
            if (file.length() > 1_000_000) return@textTool "File too large (${file.length()} bytes, max 1MB)"
            file.readText()
        }

        // --- app_file_write ---
        registry.textTool("app_file_write", "Write text to a file in app private storage",
            jsonSchema {
                string("path", "File path relative to app storage")
                string("content", "Text content to write")
                boolean("append", "Append instead of overwrite (default false)", required = false)
            }
        ) { args ->
            val path = args["path"]?.jsonPrimitive?.content ?: ""
            val content = args["content"]?.jsonPrimitive?.content ?: ""
            val append = args["append"]?.jsonPrimitive?.booleanOrNull ?: false
            val file = File(ctx.filesDir, path).canonicalFile
            if (!file.path.startsWith(ctx.filesDir.canonicalPath + File.separator))
                return@textTool "Invalid path: outside app storage"
            file.parentFile?.mkdirs()
            if (append) file.appendText(content) else file.writeText(content)
            "Written ${content.length} chars to $path"
        }

        // --- app_file_delete ---
        registry.textTool("app_file_delete", "Delete a file from app private storage",
            jsonSchema { string("path", "File path relative to app storage") }
        ) { args ->
            val path = args["path"]?.jsonPrimitive?.content ?: ""
            val file = File(ctx.filesDir, path).canonicalFile
            if (!file.path.startsWith(ctx.filesDir.canonicalPath + File.separator))
                return@textTool "Invalid path: outside app storage"
            if (!file.exists()) return@textTool "File not found: $path"
            val deleted = file.delete()
            if (deleted) "Deleted: $path" else "Failed to delete: $path"
        }

        // --- media_images ---
        registry.textTool("media_images", "List recent images on the device",
            jsonSchema {
                integer("limit", "Max results (default 20)", required = false)
                string("search", "Search by filename", required = false)
            }
        ) { args ->
            queryMediaStore(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                args["limit"]?.jsonPrimitive?.intOrNull ?: 20,
                args["search"]?.jsonPrimitive?.contentOrNull)
        }

        // --- media_videos ---
        registry.textTool("media_videos", "List recent videos on the device",
            jsonSchema {
                integer("limit", "Max results (default 20)", required = false)
                string("search", "Search by filename", required = false)
            }
        ) { args ->
            queryMediaStore(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                args["limit"]?.jsonPrimitive?.intOrNull ?: 20,
                args["search"]?.jsonPrimitive?.contentOrNull)
        }

        // --- media_audio ---
        registry.textTool("media_audio", "List audio files on the device",
            jsonSchema {
                integer("limit", "Max results (default 20)", required = false)
                string("search", "Search by filename", required = false)
            }
        ) { args ->
            queryMediaStore(ctx, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                args["limit"]?.jsonPrimitive?.intOrNull ?: 20,
                args["search"]?.jsonPrimitive?.contentOrNull)
        }

        // --- downloads_list ---
        registry.textTool("downloads_list", "List recent downloads",
            jsonSchema { integer("limit", "Max results (default 20)", required = false) }
        ) { args ->
            val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                queryMediaStore(ctx, MediaStore.Downloads.EXTERNAL_CONTENT_URI, limit, null)
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

        // --- download_file ---
        registry.textTool("download_file", "Download a file from a URL",
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
                    buildJsonObject {
                        put("name", it.getString(0) ?: "")
                        put("size_bytes", it.getLong(1))
                        put("mime_type", it.getString(2) ?: "")
                        put("modified", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it.getLong(3) * 1000)))
                    }.toString()
                } else "No metadata found for $uriStr"
            } ?: "Cannot query $uriStr"
        }
    }

    private fun queryMediaStore(ctx: Context, uri: Uri, limit: Int, search: String?): String {
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
}
