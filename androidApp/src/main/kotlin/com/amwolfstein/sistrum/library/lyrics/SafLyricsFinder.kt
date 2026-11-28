package com.amwolfstein.sistrum.library.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalLyrics(val content: String, val source: String, val synced: Boolean)

/**
 * Finds lyrics next to a SAF document without copying the library:
 * sibling `.lrc`/`.txt`, then a configured subfolder, then the dedicated lyrics folder.
 */
class SafLyricsFinder(private val context: Context) {
    suspend fun find(
        item: LibraryItemEntity,
        source: LibrarySourceEntity,
        subfolderEnabled: Boolean,
        subfolderName: String,
        dedicatedFolderUri: String?,
    ): LocalLyrics? = withContext(Dispatchers.IO) {
        val baseName = item.displayPath?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: item.title.takeIf { it.isNotBlank() }
            ?: return@withContext null
        val treeUri = runCatching { Uri.parse(source.treeUri) }.getOrNull() ?: return@withContext null
        val relativeDir = item.displayPath.orEmpty().substringBeforeLast('/', "")
        val parent = folderDocumentId(treeUri, relativeDir) ?: return@withContext null

        readCandidate(treeUri, parent, baseName, "lrc")?.let { return@withContext it }
        readCandidate(treeUri, parent, baseName, "txt")?.let { return@withContext it }

        if (subfolderEnabled && subfolderName.isNotBlank()) {
            val subfolder = childFolder(treeUri, parent, subfolderName)
            if (subfolder != null) {
                readCandidate(treeUri, subfolder, baseName, "lrc")?.let { return@withContext it }
                readCandidate(treeUri, subfolder, baseName, "txt")?.let { return@withContext it }
            }
        }

        if (!dedicatedFolderUri.isNullOrBlank()) {
            findInDedicatedFolder(Uri.parse(dedicatedFolderUri), baseName)?.let { return@withContext it }
        }
        null
    }

    private fun folderDocumentId(treeUri: Uri, relativeDir: String): String? {
        var documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        relativeDir.split('/').filter { it.isNotBlank() }.forEach { segment ->
            documentId = childFolder(treeUri, documentId, segment) ?: return null
        }
        return documentId
    }

    private fun childFolder(treeUri: Uri, parentId: String, name: String): String? =
        listChildren(treeUri, parentId).firstOrNull { child ->
            child.mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                child.name.equals(name, ignoreCase = true)
        }?.documentId

    private fun readCandidate(
        treeUri: Uri,
        folderId: String,
        baseName: String,
        extension: String,
    ): LocalLyrics? {
        val child = listChildren(treeUri, folderId).firstOrNull { entry ->
            entry.name.equals("$baseName.$extension", ignoreCase = true)
        } ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId)
        val content = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return LocalLyrics(content, extension, synced = extension == "lrc" && SYNcedPattern.containsMatchIn(content))
    }

    /** Breadth-first search of the dedicated folder, bounded so a huge tree cannot stall playback. */
    private fun findInDedicatedFolder(treeUri: Uri, baseName: String): LocalLyrics? {
        val root = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val queue = ArrayDeque(listOf(root to 0))
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_DEDICATED_ENTRIES) {
            val (folderId, depth) = queue.removeFirst()
            val children = listChildren(treeUri, folderId)
            visited += children.size
            children.filter { it.mime == DocumentsContract.Document.MIME_TYPE_DIR && depth < MAX_DEPTH }
                .forEach { queue += it.documentId to depth + 1 }
            listOf("lrc", "txt").forEach { extension ->
                val match = children.firstOrNull { child ->
                    child.mime != DocumentsContract.Document.MIME_TYPE_DIR &&
                        child.name.equals("$baseName.$extension", ignoreCase = true)
                } ?: return@forEach
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, match.documentId)
                val content = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                return LocalLyrics(content, extension, synced = extension == "lrc")
            }
        }
        return null
    }

    private data class Child(val documentId: String, val name: String, val mime: String?)

    private fun listChildren(treeUri: Uri, documentId: String): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                buildList {
                    while (cursor.moveToNext()) {
                        add(Child(cursor.getString(idIndex), cursor.getString(nameIndex), cursor.getString(mimeIndex)))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    private companion object {
        const val MAX_DEDICATED_ENTRIES = 2000
        const val MAX_DEPTH = 4
        val SYNcedPattern = Regex("\\[\\d{1,2}:\\d{2}(\\.\\d{1,3})?]")
    }
}
