package com.llmintentions.files.dev

data class ParamDef(
    val name: String,
    val description: String,
    val type: ParamType,
    val required: Boolean = true
)

enum class ParamType { STRING, INTEGER, BOOLEAN }

data class ToolDef(
    val name: String,
    val description: String,
    val params: List<ParamDef>
) {
    companion object {
        fun allTools(): List<ToolDef> = listOf(
            ToolDef("fs_list", "List files and directories at any path on the device", listOf(
                ParamDef("path", "Absolute path (e.g., /sdcard)", ParamType.STRING),
                ParamDef("hidden", "Include hidden files", ParamType.BOOLEAN, required = false)
            )),
            ToolDef("fs_read", "Read a text file from any path on the device", listOf(
                ParamDef("path", "Absolute file path", ParamType.STRING),
                ParamDef("max_bytes", "Max bytes to read (default 1MB)", ParamType.INTEGER, required = false)
            )),
            ToolDef("fs_read_bytes", "Read binary file as base64 from any path", listOf(
                ParamDef("path", "Absolute file path", ParamType.STRING),
                ParamDef("max_bytes", "Max bytes to read (default 100KB)", ParamType.INTEGER, required = false)
            )),
            ToolDef("fs_write", "Write text to any path on the device", listOf(
                ParamDef("path", "Absolute file path", ParamType.STRING),
                ParamDef("content", "Text content to write", ParamType.STRING),
                ParamDef("append", "Append instead of overwrite", ParamType.BOOLEAN, required = false)
            )),
            ToolDef("fs_delete", "Delete a file or empty directory", listOf(
                ParamDef("path", "Absolute path to delete", ParamType.STRING)
            )),
            ToolDef("fs_delete_recursive", "Recursively delete a directory and all its contents", listOf(
                ParamDef("path", "Absolute path to directory", ParamType.STRING)
            )),
            ToolDef("fs_move", "Move or rename a file or directory", listOf(
                ParamDef("from", "Source path", ParamType.STRING),
                ParamDef("to", "Destination path", ParamType.STRING)
            )),
            ToolDef("fs_copy", "Copy a file", listOf(
                ParamDef("from", "Source file path", ParamType.STRING),
                ParamDef("to", "Destination file path", ParamType.STRING)
            )),
            ToolDef("fs_mkdir", "Create a directory (including parents)", listOf(
                ParamDef("path", "Directory path to create", ParamType.STRING)
            )),
            ToolDef("fs_find", "Search for files by name pattern", listOf(
                ParamDef("path", "Directory to search in", ParamType.STRING),
                ParamDef("pattern", "Filename pattern (case-insensitive substring)", ParamType.STRING),
                ParamDef("max_results", "Max results (default 50)", ParamType.INTEGER, required = false),
                ParamDef("recursive", "Search subdirectories (default true)", ParamType.BOOLEAN, required = false)
            )),
            ToolDef("fs_stat", "Get detailed info about a file or directory", listOf(
                ParamDef("path", "Absolute path", ParamType.STRING)
            )),
            ToolDef("fs_tree", "Show directory tree (like the tree command)", listOf(
                ParamDef("path", "Root directory", ParamType.STRING),
                ParamDef("depth", "Max depth (default 3)", ParamType.INTEGER, required = false)
            )),
            ToolDef("fs_grep", "Search file contents for a text pattern", listOf(
                ParamDef("path", "Directory to search in", ParamType.STRING),
                ParamDef("pattern", "Text to search for (case-insensitive)", ParamType.STRING),
                ParamDef("glob", "Filename filter, e.g. *.txt, *.json", ParamType.STRING, required = false),
                ParamDef("max_results", "Max matching files (default 20)", ParamType.INTEGER, required = false),
                ParamDef("context_lines", "Lines of context around each match (default 1)", ParamType.INTEGER, required = false)
            )),
            ToolDef("fs_storage_info", "Show storage space and mount points", emptyList()),
            ToolDef("download_file", "Download a file from a URL to Downloads folder", listOf(
                ParamDef("url", "URL to download", ParamType.STRING),
                ParamDef("filename", "Save as filename", ParamType.STRING, required = false)
            )),
            ToolDef("downloads_list", "List recent downloads", listOf(
                ParamDef("limit", "Max results (default 20)", ParamType.INTEGER, required = false)
            )),
            ToolDef("file_info", "Get metadata for a file by content URI", listOf(
                ParamDef("uri", "Content URI (content://...)", ParamType.STRING)
            )),
            ToolDef("media_images", "List recent images via MediaStore", listOf(
                ParamDef("limit", "Max results (default 20)", ParamType.INTEGER, required = false),
                ParamDef("search", "Filter by filename substring", ParamType.STRING, required = false)
            )),
            ToolDef("media_videos", "List recent videos via MediaStore", listOf(
                ParamDef("limit", "Max results (default 20)", ParamType.INTEGER, required = false),
                ParamDef("search", "Filter by filename substring", ParamType.STRING, required = false)
            )),
            ToolDef("media_audio", "List audio files via MediaStore", listOf(
                ParamDef("limit", "Max results (default 20)", ParamType.INTEGER, required = false),
                ParamDef("search", "Filter by filename substring", ParamType.STRING, required = false)
            ))
        )
    }
}
