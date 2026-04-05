package com.androclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun execute(input: Map<String, Any>): String {
        val action = input["action"] as? String ?: return "Missing action (find, open, share, list, info)"

        return when (action.lowercase()) {
            "find", "search" -> findFiles(input)
            "open" -> openFile(input)
            "share", "send" -> shareFile(input)
            "list" -> listDirectory(input)
            "info" -> fileInfo(input)
            "recent" -> recentFiles(input)
            else -> "Unknown file action: $action. Use: find, open, share, list, info, recent"
        }
    }

    private fun findFiles(input: Map<String, Any>): String {
        val query = input["query"] as? String ?: return "Missing search query"
        val fileType = input["file_type"] as? String // "image", "video", "audio", "document", "pdf", etc.
        val maxResults = (input["max_results"] as? Number)?.toInt() ?: 20

        val results = mutableListOf<FileResult>()

        // Search via MediaStore for media files
        if (fileType in listOf("image", "photo", "picture", null)) {
            results.addAll(searchMediaStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, query, "image"))
        }
        if (fileType in listOf("video", null)) {
            results.addAll(searchMediaStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, query, "video"))
        }
        if (fileType in listOf("audio", "music", "song", null)) {
            results.addAll(searchMediaStore(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, query, "audio"))
        }

        // Search common file directories
        if (fileType in listOf("document", "pdf", "file", null)) {
            val searchDirs = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            )
            for (dir in searchDirs) {
                if (dir.exists()) {
                    searchDirectory(dir, query, fileType, results, maxDepth = 3)
                }
            }
        }

        // Deduplicate and limit
        val unique = results.distinctBy { it.path }.take(maxResults)

        return if (unique.isEmpty()) {
            "No files found matching \"$query\"" + (fileType?.let { " (type: $it)" } ?: "")
        } else {
            "Found ${unique.size} file(s):\n" + unique.joinToString("\n") { file ->
                "- ${file.name} (${file.type}, ${formatSize(file.size)}) — ${file.path}"
            }
        }
    }

    private fun searchMediaStore(uri: Uri, query: String, type: String): List<FileResult> {
        val results = mutableListOf<FileResult>()
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        try {
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            cursor?.use {
                while (it.moveToNext() && results.size < 15) {
                    val name = it.getString(0) ?: continue
                    val path = it.getString(1) ?: continue
                    val size = it.getLong(2)
                    results.add(FileResult(name, path, type, size))
                }
            }
        } catch (_: Exception) {}

        return results
    }

    private fun searchDirectory(dir: File, query: String, fileType: String?, results: MutableList<FileResult>, maxDepth: Int, depth: Int = 0) {
        if (depth > maxDepth || results.size > 30) return
        val files = dir.listFiles() ?: return

        for (file in files) {
            if (file.isDirectory) {
                searchDirectory(file, query, fileType, results, maxDepth, depth + 1)
            } else {
                val nameMatch = file.name.lowercase().contains(query.lowercase())
                val typeMatch = fileType == null || matchesFileType(file.name, fileType)
                if (nameMatch && typeMatch) {
                    val ext = file.extension.lowercase()
                    val type = categorizeExtension(ext)
                    results.add(FileResult(file.name, file.absolutePath, type, file.length()))
                }
            }
        }
    }

    private fun matchesFileType(fileName: String, type: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (type.lowercase()) {
            "pdf" -> ext == "pdf"
            "document", "doc" -> ext in listOf("pdf", "doc", "docx", "txt", "rtf", "odt", "xls", "xlsx", "ppt", "pptx", "csv")
            "image", "photo", "picture" -> ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
            "video" -> ext in listOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv")
            "audio", "music" -> ext in listOf("mp3", "aac", "flac", "wav", "ogg", "m4a", "wma")
            "apk" -> ext == "apk"
            "zip", "archive" -> ext in listOf("zip", "rar", "7z", "tar", "gz")
            else -> true
        }
    }

    private fun categorizeExtension(ext: String): String = when (ext) {
        in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic") -> "image"
        in listOf("mp4", "mkv", "avi", "mov", "webm", "3gp") -> "video"
        in listOf("mp3", "aac", "flac", "wav", "ogg", "m4a") -> "audio"
        in listOf("pdf", "doc", "docx", "txt", "rtf", "odt") -> "document"
        in listOf("xls", "xlsx", "csv") -> "spreadsheet"
        in listOf("zip", "rar", "7z", "tar", "gz") -> "archive"
        "apk" -> "app"
        else -> "file"
    }

    private fun openFile(input: Map<String, Any>): String {
        val path = input["path"] as? String ?: return "Missing file path"
        val file = File(path)
        if (!file.exists()) return "File not found: $path"

        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mimeType = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            "Opened ${file.name}"
        } catch (e: Exception) {
            "Failed to open file: ${e.message}"
        }
    }

    private fun shareFile(input: Map<String, Any>): String {
        val path = input["path"] as? String ?: return "Missing file path"
        val targetApp = input["target_app"] as? String
        val file = File(path)
        if (!file.exists()) return "File not found: $path"

        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mimeType = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (targetApp != null) {
                    `package` = targetApp
                }
            }

            if (targetApp != null) {
                context.startActivity(shareIntent)
                "Sharing ${file.name} via $targetApp"
            } else {
                val chooser = Intent.createChooser(shareIntent, "Share ${file.name}").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                "Opened share dialog for ${file.name}"
            }
        } catch (e: Exception) {
            "Failed to share file: ${e.message}"
        }
    }

    private fun listDirectory(input: Map<String, Any>): String {
        val dirPath = input["directory"] as? String
        val dir = if (dirPath != null) {
            File(dirPath)
        } else {
            // Default: list common directories
            return listCommonDirectories()
        }

        if (!dir.exists()) return "Directory not found: $dirPath"
        if (!dir.isDirectory) return "$dirPath is not a directory"

        val files = dir.listFiles()?.sortedByDescending { it.lastModified() }?.take(30)
            ?: return "Cannot read directory"

        return "Contents of ${dir.name}/ (${files.size} items):\n" +
                files.joinToString("\n") { f ->
                    val icon = if (f.isDirectory) "\uD83D\uDCC1" else "\uD83D\uDCC4"
                    val size = if (f.isFile) " (${formatSize(f.length())})" else ""
                    "$icon ${f.name}$size"
                }
    }

    private fun listCommonDirectories(): String {
        val dirs = listOf(
            "Downloads" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Documents" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "Pictures" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "DCIM" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Music" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "Movies" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        )

        return "Storage directories:\n" + dirs.joinToString("\n") { (name, dir) ->
            val count = dir.listFiles()?.size ?: 0
            "\uD83D\uDCC1 $name — ${dir.absolutePath} ($count items)"
        }
    }

    private fun fileInfo(input: Map<String, Any>): String {
        val path = input["path"] as? String ?: return "Missing file path"
        val file = File(path)
        if (!file.exists()) return "File not found: $path"

        val ext = file.extension.lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "unknown"

        return """
File: ${file.name}
Path: ${file.absolutePath}
Size: ${formatSize(file.length())}
Type: $mimeType
Category: ${categorizeExtension(ext)}
Last modified: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(file.lastModified())}
Readable: ${file.canRead()}
        """.trim()
    }

    private fun recentFiles(input: Map<String, Any>): String {
        val fileType = input["file_type"] as? String
        val limit = (input["limit"] as? Number)?.toInt() ?: 15
        val results = mutableListOf<FileResult>()

        // Query MediaStore sorted by date
        val queries = mutableListOf<Pair<Uri, String>>()
        if (fileType in listOf("image", "photo", null)) {
            queries.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "image")
        }
        if (fileType in listOf("video", null)) {
            queries.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video")
        }
        if (fileType in listOf("audio", "music", null)) {
            queries.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "audio")
        }

        for ((uri, type) in queries) {
            try {
                val projection = arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED
                )
                val cursor = context.contentResolver.query(
                    uri, projection, null, null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )
                cursor?.use {
                    while (it.moveToNext() && results.size < limit) {
                        results.add(
                            FileResult(
                                name = it.getString(0) ?: "unknown",
                                path = it.getString(1) ?: "",
                                type = type,
                                size = it.getLong(2)
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // Also check Downloads folder for recent docs
        if (fileType in listOf("document", "pdf", "file", null)) {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloads.listFiles()?.sortedByDescending { it.lastModified() }?.take(10)?.forEach { f ->
                results.add(FileResult(f.name, f.absolutePath, categorizeExtension(f.extension), f.length()))
            }
        }

        val sorted = results.sortedByDescending { File(it.path).lastModified() }.take(limit)

        return if (sorted.isEmpty()) {
            "No recent files found."
        } else {
            "Recent files:\n" + sorted.joinToString("\n") { f ->
                "- ${f.name} (${f.type}, ${formatSize(f.size)}) — ${f.path}"
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private data class FileResult(val name: String, val path: String, val type: String, val size: Long)
}
