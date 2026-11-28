package com.amwolfstein.sistrum.library.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.tags.TagEdit
import com.amwolfstein.sistrum.library.tags.TagWriteResult
import com.amwolfstein.sistrum.library.tags.TagWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists a selected lyric next to the audio (`<stem>.lrc`) or inside its
 * tags (`LYRICS`). Both paths keep the original audio payload; embedding goes
 * through the staged, journaled [TagWriter] rather than rewriting the SAF file
 * in place.
 */
class LyricsFileWriter(
    private val context: Context,
    private val database: LibraryDatabase = LibraryDatabase.get(context),
) {
    private val dao = database.libraryDao()
    private val tagWriter = TagWriter(context, dao)

    suspend fun saveAsLrc(trackId: String, content: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val item = dao.itemById(trackId) ?: error("Unknown track")
            val source = dao.source(item.sourceId) ?: error("Unknown library folder")
            val tree = Uri.parse(source.treeUri)
            val baseName = sidecarBaseName(item) ?: error("This track has no file name")
            val relativeDir = item.displayPath.orEmpty().substringBeforeLast('/', "")
            val parent = folderDocumentId(tree, relativeDir) ?: error("Cannot reach the track folder")
            if (!hasWriteGrant(tree)) error("The folder is read-only")

            val targetName = "$baseName.lrc"
            val temporaryName = "$targetName.writing"
            // Write a temporary document first so a failure cannot destroy the previous lyrics.
            val temporary = DocumentsContract.createDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, parent),
                "text/plain",
                temporaryName,
            ) ?: error("Could not create the lyrics file")
            try {
                context.contentResolver.openOutputStream(temporary, "wt")?.use { stream ->
                    stream.write(content.toByteArray(Charsets.UTF_8))
                } ?: error("Could not write the lyrics file")
            } catch (failure: Exception) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, temporary) }
                throw failure
            }
            existingChild(tree, parent, targetName)?.let { documentId ->
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, documentUri(tree, documentId)) }
            }
            val renamed = runCatching {
                DocumentsContract.renameDocument(context.contentResolver, temporary, targetName)
            }.getOrNull()
            (renamed ?: temporary).toString()
        }
    }

    suspend fun embed(trackId: String, content: String): Result<String> = withContext(Dispatchers.IO) {
        val item = dao.itemById(trackId) ?: return@withContext Result.failure(IllegalArgumentException("Unknown track"))
        when (val result = tagWriter.write(item, TagEdit(lyrics = content))) {
            is TagWriteResult.Success -> Result.success(item.fileUri)
            is TagWriteResult.Failure -> Result.failure(IllegalStateException(result.reason))
        }
    }

    private fun baseName(item: LibraryItemEntity): String? =
        item.displayPath?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }

    /** CUE segments share one audio file, so each segment needs its own sidecar name. */
    private fun sidecarBaseName(item: LibraryItemEntity): String? {
        val base = baseName(item) ?: return null
        if (item.cueKey == null) return base
        val title = item.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).trim()
        return if (title.isBlank()) "$base - ${item.trackNumberInCue ?: "track"}" else "$base - $title"
    }

    private fun hasWriteGrant(tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    private fun folderDocumentId(tree: Uri, relativeDir: String): String? {
        var documentId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        relativeDir.split('/').filter { it.isNotBlank() }.forEach { segment ->
            val child = children(tree, documentId).firstOrNull { it.second.equals(segment, true) }
                ?: return null
            documentId = child.first
        }
        return documentId
    }

    private fun existingChild(tree: Uri, parentId: String, name: String): String? =
        children(tree, parentId).firstOrNull { it.second.equals(name, ignoreCase = true) }?.first

    private fun documentUri(tree: Uri, documentId: String) =
        DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

    private fun children(tree: Uri, parentId: String): List<Pair<String, String>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0) to (cursor.getString(1) ?: ""))
                }
            }
        }.getOrNull().orEmpty()
    }
}
