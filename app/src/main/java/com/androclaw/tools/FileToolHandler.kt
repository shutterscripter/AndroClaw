package com.androclaw.tools

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.androclaw.utils.PermissionHelper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: PermissionHelper
) {

    suspend fun execute(input: Map<String, Any>): String {
        // Check All Files Access first (needed for non-media files like PDFs, docs on Android 11+)
        if (!permissionHelper.hasAllFilesAccess()) {
            return "All Files Access is required to search and read files (especially PDFs and documents). Please open AndroClaw's main app → Settings and grant 'All Files Access', or go to Android Settings → Apps → AndroClaw → Permissions → Files and media → Allow management of all files."
        }

        // Request media permissions — but don't crash if the bridge isn't available
        try {
            val permError = permissionHelper.ensurePermissionsForTool(context, "file_manager")
            if (permError != null) return permError
        } catch (_: Exception) {
            // If permission request fails (e.g. from overlay), check directly
            if (!permissionHelper.hasStoragePermission(context)) {
                return "Storage permission not granted. Please open the main app to grant file access."
            }
        }

        val action = input["action"] as? String ?: return "Missing action (find, open, share, list, info, read, tree, grep, glob)"

        return try {
            when (action.lowercase()) {
                "find", "search" -> findFiles(input)
                "open" -> openFile(input)
                "share", "send" -> shareFile(input)
                "list" -> listDirectory(input)
                "info" -> fileInfo(input)
                "recent" -> recentFiles(input)
                "read" -> readFileContent(input)
                "tree" -> directoryTree(input)
                "grep", "search_content" -> grepFiles(input)
                "glob" -> globFiles(input)
                else -> "Unknown file action: $action. Use: find, open, share, list, info, recent, read, tree, grep, glob"
            }
        } catch (e: Exception) {
            "File operation failed: ${e.message}"
        }
    }

    private fun findFiles(input: Map<String, Any>): String {
        val query = input["query"] as? String ?: input["name"] as? String ?: return "Missing search query"
        val fileType = input["file_type"] as? String
        val maxResults = (input["max_results"] as? Number)?.toInt() ?: 25

        val results = mutableListOf<FileResult>()

        // ── Strategy 1: Search ALL files in MediaStore.Files (no MIME restriction) ──
        results.addAll(searchAllMediaStoreFiles(query, fileType, maxResults))

        // ── Strategy 2: Search common directories directly (catches files MediaStore missed) ──
        val dirsToSearch = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStorageDirectory() // root of external storage
        )
        for (dir in dirsToSearch) {
            try {
                if (dir.exists() && dir.canRead()) {
                    searchDirectorySafe(dir, query, fileType, results, maxDepth = 4)
                }
            } catch (_: Exception) {}
        }

        // ── Strategy 3: If still no results, try fuzzy search (split query into words) ──
        if (results.isEmpty()) {
            results.addAll(searchAllMediaStoreFilesFuzzy(query, fileType, maxResults))
        }

        // Deduplicate by path first, then by name
        val unique = results
            .distinctBy { it.path.ifBlank { it.name.lowercase() } }
            .sortedByDescending { it.modified }
            .take(maxResults)

        return if (unique.isEmpty()) {
            "No files found matching \"$query\"" + (fileType?.let { " (type: $it)" } ?: "") +
                ".\n\nTip: Try a shorter or different search term. The file might also be in app-specific storage that requires the app itself to access."
        } else {
            "Found ${unique.size} file(s) matching \"$query\":\n" + unique.joinToString("\n") { file ->
                val pathInfo = if (file.path.isNotBlank()) "\n  Path: ${file.path}" else ""
                "- ${file.name} (${file.type}, ${formatSize(file.size)})$pathInfo"
            }
        }
    }

    /**
     * Search MediaStore.Files for ANY file type — no MIME restriction.
     * This is the primary search that should find PDFs, docs, and everything else.
     */
    @Suppress("DEPRECATION")
    private fun searchAllMediaStoreFiles(query: String, fileType: String?, maxResults: Int): List<FileResult> {
        val results = mutableListOf<FileResult>()
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATA  // Full path (deprecated but still works)
            )

            val selectionParts = mutableListOf<String>()
            val selectionArgs = mutableListOf<String>()

            // Name match
            selectionParts.add("${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
            selectionArgs.add("%$query%")

            // Only filter MIME if a specific type was requested
            if (fileType != null) {
                val mimeFilter = getMimeFilterForType(fileType)
                if (mimeFilter != null) {
                    if (mimeFilter.size == 1) {
                        selectionParts.add("${MediaStore.MediaColumns.MIME_TYPE} LIKE ?")
                        selectionArgs.add(mimeFilter.first())
                    } else {
                        val placeholders = mimeFilter.joinToString(",") { "?" }
                        selectionParts.add("${MediaStore.MediaColumns.MIME_TYPE} IN ($placeholders)")
                        selectionArgs.addAll(mimeFilter)
                    }
                }
            }

            // Exclude directories (size > 0)
            selectionParts.add("${MediaStore.MediaColumns.SIZE} > 0")

            val selection = selectionParts.joinToString(" AND ")
            val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

            context.contentResolver.query(uri, projection, selection, selectionArgs.toTypedArray(), sortOrder)?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                while (cursor.moveToNext() && results.size < maxResults) {
                    val name = cursor.getString(nameCol) ?: continue
                    val size = cursor.getLong(sizeCol)
                    val mime = cursor.getString(mimeCol) ?: ""
                    val modified = cursor.getLong(dateCol)
                    val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""

                    // Skip if type filter requested and doesn't match by extension
                    if (fileType != null && !matchesFileType(name, fileType)) continue

                    results.add(FileResult(
                        name = name,
                        type = categorizeByMime(mime, name),
                        size = size,
                        path = path,
                        modified = modified
                    ))
                }
            }
        } catch (_: Exception) {}
        return results
    }

    /**
     * Fuzzy search: split query into words and match files containing ALL words.
     * "my resume" matches "My_Resume_2024.pdf"
     */
    @Suppress("DEPRECATION")
    private fun searchAllMediaStoreFilesFuzzy(query: String, fileType: String?, maxResults: Int): List<FileResult> {
        val words = query.lowercase().split(Regex("[\\s_\\-.,]+")).filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()

        val results = mutableListOf<FileResult>()
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATA
            )

            // Build WHERE clause: name LIKE %word1% AND name LIKE %word2% ...
            val selectionParts = mutableListOf<String>()
            val selectionArgs = mutableListOf<String>()

            for (word in words) {
                selectionParts.add("${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
                selectionArgs.add("%$word%")
            }

            selectionParts.add("${MediaStore.MediaColumns.SIZE} > 0")

            val selection = selectionParts.joinToString(" AND ")
            val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

            context.contentResolver.query(uri, projection, selection, selectionArgs.toTypedArray(), sortOrder)?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                while (cursor.moveToNext() && results.size < maxResults) {
                    val name = cursor.getString(nameCol) ?: continue
                    val size = cursor.getLong(sizeCol)
                    val mime = cursor.getString(mimeCol) ?: ""
                    val modified = cursor.getLong(dateCol)
                    val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""

                    if (fileType != null && !matchesFileType(name, fileType)) continue

                    results.add(FileResult(
                        name = name,
                        type = categorizeByMime(mime, name),
                        size = size,
                        path = path,
                        modified = modified
                    ))
                }
            }
        } catch (_: Exception) {}
        return results
    }

    /**
     * Get MIME type patterns for a file type filter.
     * Returns null to skip MIME filtering (search all).
     */
    private fun getMimeFilterForType(type: String): List<String>? = when (type.lowercase()) {
        "pdf" -> listOf("application/pdf")
        "image", "photo", "picture" -> listOf("image/%")
        "video" -> listOf("video/%")
        "audio", "music", "song" -> listOf("audio/%")
        "document", "doc" -> listOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/csv",
            "application/rtf"
        )
        "apk" -> listOf("application/vnd.android.package-archive")
        "zip", "archive" -> listOf(
            "application/zip", "application/x-rar-compressed",
            "application/x-7z-compressed", "application/gzip"
        )
        else -> null // Don't filter — search everything
    }

    private fun categorizeByMime(mime: String, fileName: String): String {
        return when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"
            mime == "application/pdf" -> "PDF"
            mime.contains("document") || mime.contains("msword") || mime == "text/plain" -> "document"
            mime.contains("spreadsheet") || mime.contains("excel") || mime == "text/csv" -> "spreadsheet"
            mime.contains("presentation") || mime.contains("powerpoint") -> "presentation"
            mime.contains("zip") || mime.contains("rar") || mime.contains("7z") || mime.contains("gzip") -> "archive"
            mime == "application/vnd.android.package-archive" -> "app"
            else -> categorizeByName(fileName)
        }
    }

    /**
     * Safe directory search — catches all exceptions, skips unreadable dirs.
     * Searches deeper and with fuzzy matching.
     */
    private fun searchDirectorySafe(
        dir: File, query: String, fileType: String?,
        results: MutableList<FileResult>, maxDepth: Int, depth: Int = 0
    ) {
        if (depth > maxDepth || results.size > 60) return
        val files = try { dir.listFiles() } catch (_: Exception) { null } ?: return

        val queryLower = query.lowercase()
        val queryWords = queryLower.split(Regex("[\\s_\\-.,]+")).filter { it.length >= 2 }

        for (file in files) {
            try {
                if (file.isDirectory && depth < maxDepth && !file.name.startsWith(".")) {
                    // Skip Android system directories that are unlikely to have user files
                    if (file.name !in setOf("Android", "cache", ".thumbnails", ".trash")) {
                        searchDirectorySafe(file, query, fileType, results, maxDepth, depth + 1)
                    }
                } else if (file.isFile) {
                    val nameLower = file.name.lowercase()
                    // Match: exact substring OR all query words present in filename
                    val nameMatch = nameLower.contains(queryLower) ||
                        (queryWords.size > 1 && queryWords.all { nameLower.contains(it) })
                    val typeMatch = fileType == null || matchesFileType(file.name, fileType)
                    if (nameMatch && typeMatch) {
                        results.add(FileResult(
                            name = file.name,
                            type = categorizeByName(file.name),
                            size = file.length(),
                            path = file.absolutePath,
                            modified = file.lastModified() / 1000
                        ))
                    }
                }
            } catch (_: Exception) {}
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

    private fun categorizeByName(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic") -> "image"
            in listOf("mp4", "mkv", "avi", "mov", "webm", "3gp") -> "video"
            in listOf("mp3", "aac", "flac", "wav", "ogg", "m4a") -> "audio"
            "pdf" -> "PDF"
            in listOf("doc", "docx", "txt", "rtf", "odt") -> "document"
            in listOf("xls", "xlsx", "csv") -> "spreadsheet"
            in listOf("ppt", "pptx") -> "presentation"
            in listOf("zip", "rar", "7z", "tar", "gz") -> "archive"
            "apk" -> "app"
            else -> "file"
        }
    }

    private fun openFile(input: Map<String, Any>): String {
        val path = input["path"] as? String
        val name = input["name"] as? String ?: input["query"] as? String

        // If a full path is given, try to open directly
        if (path != null) {
            val file = File(path)
            if (file.exists()) {
                return openFileByPath(file)
            }
        }

        // If a filename is given, find it via MediaStore and open
        if (name != null) {
            return openFileByName(name)
        }

        return "Please provide a file path or name to open."
    }

    private fun openFileByPath(file: File): String {
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

    private fun openFileByName(name: String): String {
        // Search MediaStore for the file
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$name%")

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: "*/*"
                    val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                    val contentUri = android.content.ContentUris.withAppendedId(uri, id)

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, mime)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                    return "Opened $displayName"
                }
            }
        } catch (e: Exception) {
            return "Failed to open file: ${e.message}"
        }

        return "File \"$name\" not found."
    }

    private fun shareFile(input: Map<String, Any>): String {
        val path = input["path"] as? String
        val name = input["name"] as? String ?: input["query"] as? String
        val targetApp = input["target_app"] as? String

        // Find the file's content URI
        val contentUri: Uri
        val displayName: String
        val mimeType: String

        if (path != null) {
            val file = File(path)
            if (!file.exists()) return "File not found: $path"
            contentUri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) {
                return "Cannot share this file: ${e.message}"
            }
            displayName = file.name
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        } else if (name != null) {
            // Find via MediaStore
            val found = findFileUri(name) ?: return "File \"$name\" not found."
            contentUri = found.first
            displayName = found.second
            mimeType = found.third
        } else {
            return "Please provide a file path or name to share."
        }

        return try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (targetApp != null) `package` = targetApp
            }
            if (targetApp != null) {
                context.startActivity(shareIntent)
                "Sharing $displayName via $targetApp"
            } else {
                val chooser = Intent.createChooser(shareIntent, "Share $displayName").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                "Opened share dialog for $displayName"
            }
        } catch (e: Exception) {
            "Failed to share file: ${e.message}"
        }
    }

    private fun findFileUri(name: String): Triple<Uri, String, String>? {
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$name%")

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: name
                    val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: "*/*"
                    val contentUri = android.content.ContentUris.withAppendedId(uri, id)
                    return Triple(contentUri, displayName, mime)
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun listDirectory(input: Map<String, Any>): String {
        val dirPath = input["directory"] as? String
        if (dirPath == null) return listCommonDirectories()

        val dir = File(dirPath)
        if (!dir.exists()) return "Directory not found: $dirPath"
        if (!dir.isDirectory) return "$dirPath is not a directory"

        val files = try { dir.listFiles() } catch (_: Exception) { null }
            ?: return "Cannot read directory (no permission)"

        val sorted = files.sortedByDescending { it.lastModified() }.take(30)
        return "Contents of ${dir.name}/ (${sorted.size} items):\n" +
                sorted.joinToString("\n") { f ->
                    val icon = if (f.isDirectory) "[DIR]" else "[FILE]"
                    val size = if (f.isFile) " (${formatSize(f.length())})" else ""
                    "$icon ${f.name}$size"
                }
    }

    private fun listCommonDirectories(): String {
        data class DirInfo(val name: String, val path: String, val count: Int)
        val dirs = listOf(
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_MOVIES
        ).mapNotNull { dirType ->
            try {
                val dir = Environment.getExternalStoragePublicDirectory(dirType)
                if (dir.exists() && dir.canRead()) {
                    val count = try { dir.listFiles()?.size ?: 0 } catch (_: Exception) { 0 }
                    DirInfo(dirType, dir.absolutePath, count)
                } else null
            } catch (_: Exception) { null }
        }

        return if (dirs.isEmpty()) {
            "Cannot access storage directories."
        } else {
            "Storage directories:\n" + dirs.joinToString("\n") { d ->
                "[DIR] ${d.name} — ${d.path} (${d.count} items)"
            }
        }
    }

    private fun fileInfo(input: Map<String, Any>): String {
        val name = input["name"] as? String ?: input["path"] as? String ?: return "Missing file name or path"

        // Try as path first
        val file = File(name)
        if (file.exists() && file.isFile) {
            val ext = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "unknown"
            return "File: ${file.name}\nPath: ${file.absolutePath}\nSize: ${formatSize(file.length())}\nType: $mimeType\nCategory: ${categorizeByName(file.name)}"
        }

        // Search MediaStore
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            context.contentResolver.query(uri, projection, selection, arrayOf("%$name%"), null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayName = cursor.getString(0) ?: name
                    val size = cursor.getLong(1)
                    val mime = cursor.getString(2) ?: "unknown"
                    val modified = cursor.getLong(3) * 1000
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(modified)
                    return "File: $displayName\nSize: ${formatSize(size)}\nType: $mime\nCategory: ${categorizeByName(displayName)}\nModified: $date"
                }
            }
        } catch (_: Exception) {}

        return "File \"$name\" not found."
    }

    @Suppress("DEPRECATION")
    private fun recentFiles(input: Map<String, Any>): String {
        val fileType = input["file_type"] as? String
        val limit = (input["limit"] as? Number)?.toInt() ?: 20
        val results = mutableListOf<FileResult>()

        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATA
            )

            val selectionParts = mutableListOf("${MediaStore.MediaColumns.SIZE} > 0")
            val selectionArgs = mutableListOf<String>()

            if (fileType != null) {
                val mimeFilter = getMimeFilterForType(fileType)
                if (mimeFilter != null) {
                    if (mimeFilter.size == 1 && mimeFilter.first().contains("%")) {
                        selectionParts.add("${MediaStore.MediaColumns.MIME_TYPE} LIKE ?")
                        selectionArgs.add(mimeFilter.first())
                    } else if (mimeFilter.size == 1) {
                        selectionParts.add("${MediaStore.MediaColumns.MIME_TYPE} = ?")
                        selectionArgs.add(mimeFilter.first())
                    } else {
                        val placeholders = mimeFilter.joinToString(",") { "?" }
                        selectionParts.add("${MediaStore.MediaColumns.MIME_TYPE} IN ($placeholders)")
                        selectionArgs.addAll(mimeFilter)
                    }
                }
            }

            val selection = selectionParts.joinToString(" AND ")

            context.contentResolver.query(
                uri, projection, selection, selectionArgs.toTypedArray(),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                while (cursor.moveToNext() && results.size < limit) {
                    val name = cursor.getString(nameCol) ?: continue
                    val size = cursor.getLong(sizeCol)
                    val mime = cursor.getString(mimeCol) ?: ""
                    val modified = cursor.getLong(dateCol)
                    val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""

                    if (fileType != null && !matchesFileType(name, fileType)) continue

                    results.add(FileResult(name, categorizeByMime(mime, name), size, path, modified))
                }
            }
        } catch (_: Exception) {}

        val unique = results.distinctBy { it.name.lowercase() }.take(limit)
        return if (unique.isEmpty()) {
            "No recent files found."
        } else {
            "Recent files:\n" + unique.joinToString("\n") { f ->
                val pathInfo = if (f.path.isNotBlank()) "\n  Path: ${f.path}" else ""
                "- ${f.name} (${f.type}, ${formatSize(f.size)})$pathInfo"
            }
        }
    }

    // ── Read file content ──────────────────────────────────────────────

    private fun readFileContent(input: Map<String, Any>): String {
        val path = input["path"] as? String
            ?: input["name"] as? String
            ?: return "Missing file path or name. Provide 'path' to read a file."

        val offset = (input["offset"] as? Number)?.toInt() ?: 0
        val limit = (input["limit"] as? Number)?.toInt() ?: 200
        val maxBytes = 512_000L // 500 KB safety cap

        // Resolve by path first
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            // Try common directories
            val candidates = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                Environment.getExternalStorageDirectory()
            )
            val found = candidates.firstNotNullOfOrNull { dir ->
                val f = File(dir, path)
                if (f.exists() && f.isFile) f else null
            } ?: return "File not found: $path"
            return readTextFile(found, offset, limit, maxBytes)
        }
        return readTextFile(file, offset, limit, maxBytes)
    }

    private fun readTextFile(file: File, offset: Int, limit: Int, maxBytes: Long): String {
        val ext = file.extension.lowercase()

        // Handle PDFs with text extraction
        if (ext == "pdf") {
            return readPdfFile(file, offset, limit)
        }

        if (file.length() > maxBytes) {
            return "File too large to read inline (${formatSize(file.length())}). Max ${formatSize(maxBytes)}."
        }

        val binaryExts = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
            "mp4", "mkv", "avi", "mov", "webm", "3gp",
            "mp3", "aac", "flac", "wav", "ogg", "m4a",
            "zip", "rar", "7z", "tar", "gz", "apk", "dex", "so",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx"
        )
        if (ext in binaryExts) {
            return "Binary file (${categorizeByName(file.name)}, ${formatSize(file.length())}). Cannot display content inline. Use 'open' or 'share' action instead."
        }

        return try {
            val lines = file.readLines(Charsets.UTF_8)
            val total = lines.size
            val sliced = lines.drop(offset).take(limit)
            val numbered = sliced.mapIndexed { i, line ->
                "%4d  %s".format(offset + i + 1, line)
            }
            val header = "File: ${file.name} (${formatSize(file.length())}, $total lines)"
            val range = "Showing lines ${offset + 1}–${offset + sliced.size} of $total"
            val truncNote = if (offset + sliced.size < total) "\n… (${total - offset - sliced.size} more lines, use offset=${offset + sliced.size})" else ""
            "$header\n$range\n${numbered.joinToString("\n")}$truncNote"
        } catch (e: Exception) {
            "Failed to read file: ${e.message}"
        }
    }

    private fun readPdfFile(file: File, offset: Int, limit: Int): String {
        // 50 MB cap for PDFs
        if (file.length() > 50_000_000L) {
            return "PDF too large (${formatSize(file.length())}). Max 50 MB for text extraction."
        }

        return try {
            PDFBoxResourceLoader.init(context)
            PDDocument.load(file).use { document ->
                val totalPages = document.numberOfPages
                val stripper = PDFTextStripper()

                // Extract text from all pages (or a range for very large docs)
                val maxPages = 100
                if (totalPages > maxPages) {
                    stripper.startPage = 1
                    stripper.endPage = maxPages
                }
                val fullText = stripper.getText(document)

                if (fullText.isBlank()) {
                    return "PDF: ${file.name} ($totalPages pages, ${formatSize(file.length())})\nThis PDF contains no extractable text (likely scanned images). Use 'take_screenshot' after opening it for visual analysis."
                }

                val lines = fullText.lines()
                val total = lines.size
                val sliced = lines.drop(offset).take(limit)
                val numbered = sliced.mapIndexed { i, line ->
                    "%4d  %s".format(offset + i + 1, line)
                }
                val header = "PDF: ${file.name} ($totalPages pages, ${formatSize(file.length())}, $total text lines)"
                val range = "Showing lines ${offset + 1}–${offset + sliced.size} of $total"
                val truncNote = if (offset + sliced.size < total) "\n… (${total - offset - sliced.size} more lines, use offset=${offset + sliced.size})" else ""
                val pageNote = if (totalPages > maxPages) "\n(Text extracted from first $maxPages of $totalPages pages)" else ""
                "$header\n$range\n${numbered.joinToString("\n")}$truncNote$pageNote"
            }
        } catch (e: Exception) {
            "Failed to read PDF: ${e.message}. Try using 'open' action to view it in a PDF reader app."
        }
    }

    // ── Directory tree ────────────────────────────────────────────────

    private fun directoryTree(input: Map<String, Any>): String {
        val dirPath = input["directory"] as? String ?: input["path"] as? String
        val maxDepth = (input["max_depth"] as? Number)?.toInt() ?: 3
        val showHidden = input["show_hidden"] as? Boolean ?: false
        val maxItems = 200

        val dir = if (dirPath != null) {
            File(dirPath)
        } else {
            Environment.getExternalStorageDirectory()
        }

        if (!dir.exists()) return "Directory not found: ${dir.absolutePath}"
        if (!dir.isDirectory) return "Not a directory: ${dir.absolutePath}"

        val sb = StringBuilder()
        sb.appendLine("${dir.name}/")
        var count = 0
        buildTree(dir, "", maxDepth, 0, showHidden, sb, count = { count++ }, maxItems)

        if (count >= maxItems) {
            sb.appendLine("… (truncated at $maxItems entries, use max_depth to limit)")
        }
        return sb.toString().trimEnd()
    }

    private fun buildTree(
        dir: File, prefix: String, maxDepth: Int, depth: Int,
        showHidden: Boolean, sb: StringBuilder, count: () -> Unit, maxItems: Int
    ) {
        if (depth >= maxDepth) return
        val files = try { dir.listFiles() } catch (_: Exception) { null } ?: return
        val filtered = files
            .filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        var itemCount = 0
        for ((i, file) in filtered.withIndex()) {
            count()
            itemCount++
            if (itemCount > maxItems) return

            val isLast = i == filtered.lastIndex
            val connector = if (isLast) "└── " else "├── "
            val childPrefix = if (isLast) "    " else "│   "

            if (file.isDirectory) {
                sb.appendLine("$prefix$connector${file.name}/")
                buildTree(file, "$prefix$childPrefix", maxDepth, depth + 1, showHidden, sb, count, maxItems)
            } else {
                sb.appendLine("$prefix$connector${file.name} (${formatSize(file.length())})")
            }
        }
    }

    // ── Grep — search inside file contents ────────────────────────────

    private fun grepFiles(input: Map<String, Any>): String {
        val pattern = input["pattern"] as? String
            ?: input["query"] as? String
            ?: return "Missing 'pattern' — the text or regex to search for."

        val dirPath = input["directory"] as? String ?: input["path"] as? String
        val fileType = input["file_type"] as? String
        val maxResults = (input["max_results"] as? Number)?.toInt() ?: 30
        val caseSensitive = input["case_sensitive"] as? Boolean ?: false
        val contextLines = (input["context_lines"] as? Number)?.toInt() ?: 0

        val dir = if (dirPath != null) File(dirPath) else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }
        if (!dir.exists()) return "Directory not found: ${dir.absolutePath}"

        val regex = try {
            if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            val escaped = Regex.escape(pattern)
            if (caseSensitive) Regex(escaped) else Regex(escaped, RegexOption.IGNORE_CASE)
        }

        val textExtensions = setOf(
            "txt", "md", "json", "xml", "csv", "log", "html", "htm",
            "css", "js", "ts", "kt", "java", "py", "rb", "sh", "yml",
            "yaml", "toml", "ini", "cfg", "conf", "properties", "gradle",
            "sql", "c", "cpp", "h", "hpp", "rs", "go", "swift", "dart"
        )

        data class GrepMatch(val file: String, val line: Int, val text: String, val context: List<String>)
        val matches = mutableListOf<GrepMatch>()

        fun searchDir(d: File, depth: Int) {
            if (depth > 5 || matches.size >= maxResults) return
            val entries = try { d.listFiles() } catch (_: Exception) { null } ?: return
            for (entry in entries) {
                if (matches.size >= maxResults) return
                if (entry.isDirectory) {
                    if (!entry.name.startsWith(".") && entry.name !in setOf("node_modules", "build", "cache")) {
                        searchDir(entry, depth + 1)
                    }
                } else if (entry.isFile && entry.length() < 256_000) {
                    val ext = entry.extension.lowercase()
                    val typeMatch = fileType == null || matchesFileType(entry.name, fileType)
                    if (ext in textExtensions && typeMatch) {
                        try {
                            val lines = entry.readLines(Charsets.UTF_8)
                            for ((idx, line) in lines.withIndex()) {
                                if (matches.size >= maxResults) break
                                if (regex.containsMatchIn(line)) {
                                    val ctx = if (contextLines > 0) {
                                        val start = maxOf(0, idx - contextLines)
                                        val end = minOf(lines.size, idx + contextLines + 1)
                                        lines.subList(start, end)
                                    } else emptyList()
                                    matches.add(GrepMatch(entry.absolutePath, idx + 1, line.trim(), ctx))
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        searchDir(dir, 0)

        return if (matches.isEmpty()) {
            "No matches for \"$pattern\" in ${dir.absolutePath}"
        } else {
            val header = "Found ${matches.size} match(es) for \"$pattern\":\n"
            header + matches.joinToString("\n\n") { m ->
                val loc = "${m.file}:${m.line}"
                if (m.context.isNotEmpty()) {
                    "$loc\n${m.context.joinToString("\n") { "  $it" }}"
                } else {
                    "$loc: ${m.text}"
                }
            }
        }
    }

    // ── Glob — find files by pattern ──────────────────────────────────

    private fun globFiles(input: Map<String, Any>): String {
        val pattern = input["pattern"] as? String
            ?: return "Missing 'pattern' — e.g. '*.pdf', '**/*.kt', 'report*'"

        val dirPath = input["directory"] as? String ?: input["path"] as? String
        val maxResults = (input["max_results"] as? Number)?.toInt() ?: 30

        val dir = if (dirPath != null) File(dirPath) else {
            Environment.getExternalStorageDirectory()
        }
        if (!dir.exists()) return "Directory not found: ${dir.absolutePath}"

        val isRecursive = pattern.contains("**") || pattern.contains("/")

        // Convert glob to regex
        val regexPattern = buildString {
            var i = 0
            while (i < pattern.length) {
                when (pattern[i]) {
                    '*' -> {
                        if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                            append(".*")
                            i += 2
                            if (i < pattern.length && pattern[i] == '/') i++ // skip separator after **
                            continue
                        } else {
                            append("[^/]*")
                        }
                    }
                    '?' -> append("[^/]")
                    '.' -> append("\\.")
                    '{' -> append("(")
                    '}' -> append(")")
                    ',' -> append("|")
                    else -> append(pattern[i])
                }
                i++
            }
        }
        val regex = try {
            Regex(regexPattern, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            return "Invalid glob pattern: $pattern"
        }

        val matches = mutableListOf<Pair<String, Long>>() // path, size
        val maxDepth = if (isRecursive) 8 else 1

        fun searchDir(d: File, depth: Int, relativePath: String) {
            if (depth > maxDepth || matches.size >= maxResults) return
            val entries = try { d.listFiles() } catch (_: Exception) { null } ?: return
            for (entry in entries.sortedBy { it.name.lowercase() }) {
                if (matches.size >= maxResults) return
                val rel = if (relativePath.isEmpty()) entry.name else "$relativePath/${entry.name}"
                if (entry.isDirectory && !entry.name.startsWith(".")) {
                    searchDir(entry, depth + 1, rel)
                } else if (entry.isFile) {
                    if (regex.matches(rel) || regex.matches(entry.name)) {
                        matches.add(entry.absolutePath to entry.length())
                    }
                }
            }
        }

        searchDir(dir, 0, "")

        return if (matches.isEmpty()) {
            "No files matching \"$pattern\" in ${dir.absolutePath}"
        } else {
            "Found ${matches.size} file(s) matching \"$pattern\":\n" +
                matches.joinToString("\n") { (path, size) ->
                    "- $path (${formatSize(size)})"
                }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private data class FileResult(
        val name: String,
        val type: String,
        val size: Long,
        val path: String = "",
        val modified: Long = 0
    )
}
