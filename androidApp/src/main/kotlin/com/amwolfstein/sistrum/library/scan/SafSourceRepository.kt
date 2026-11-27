package com.amwolfstein.sistrum.library.scan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.amwolfstein.sistrum.library.LibraryRepository
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SafSourceRepository(
    private val context: Context,
    private val database: LibraryDatabase = LibraryDatabase.get(context),
) {
    private val dao = database.libraryDao()

    fun observeSources(): Flow<List<LibrarySourceEntity>> = dao.observeSources()

    suspend fun sources(): List<LibrarySourceEntity> = dao.sources()

    suspend fun add(uri: Uri): LibrarySourceEntity = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, read or write) }
            .recoverCatching { resolver.takePersistableUriPermission(uri, read) }
            .getOrThrow()
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val volumeId = volumeIdOf(documentId)
        val name = displayName(uri, documentId)
        val available = hasReadGrant(uri)
        val existing = dao.source(sourceId(uri))
        val entity = if (existing != null) {
            existing.copy(
                displayName = name,
                volumeId = volumeId,
                isRemovable = isRemovableVolume(volumeId),
                enabled = true,
                available = available,
            )
        } else {
            LibrarySourceEntity(
                id = sourceId(uri),
                treeUri = uri.toString(),
                displayName = name,
                volumeId = volumeId,
                isRemovable = isRemovableVolume(volumeId),
                enabled = true,
                available = available,
                createdAt = System.currentTimeMillis(),
            )
        }
        dao.upsertSource(entity)
        return@withContext entity
    }

    suspend fun remove(source: LibrarySourceEntity) = withContext(Dispatchers.IO) {
        LibraryRepository(context, database).removeSource(source)
        val uri = runCatching { Uri.parse(source.treeUri) }.getOrNull()
        if (uri != null) {
            val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            var flags = 0
            if (grant?.isReadPermission == true) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (grant?.isWritePermission == true) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (flags != 0) runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
        }
    }

    suspend fun setEnabled(source: LibrarySourceEntity, enabled: Boolean) {
        dao.setSourceEnabled(source.id, enabled)
    }

    suspend fun refreshAvailability() = withContext(Dispatchers.IO) {
        dao.sources().forEach { source ->
            val uri = runCatching { Uri.parse(source.treeUri) }.getOrNull() ?: return@forEach
            val probe = if (!hasReadGrant(uri)) false else runCatching { canListRoot(uri) }.getOrNull()
            if (probe != null) dao.setSourceAvailability(source.id, probe)
        }
    }

    private fun hasReadGrant(uri: Uri): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission } ||
            context.checkUriPermission(
                DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)),
                android.os.Process.myPid(),
                android.os.Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun canListRoot(uri: Uri): Boolean? {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) return null
            return true
        }
        return null
    }

    private fun displayName(uri: Uri, documentId: String): String {
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
        runCatching {
            context.contentResolver.query(
                document,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return documentId.substringAfterLast('/').substringAfterLast(':').ifBlank { "Music" }
    }

    companion object {
        fun volumeIdOf(documentId: String): String? = documentId.substringBefore(':', missingDelimiterValue = "").ifBlank { null }

        fun isRemovableVolume(volumeId: String?): Boolean =
            volumeId != null && volumeId !in setOf("primary", "home", "raw", "downloads")

        fun sourceId(uri: Uri): String = sourceId(uri.toString())

        fun sourceId(value: String): String {
            var hash = -0x340d631b7bdddcdbL
            value.toByteArray().forEach { byte ->
                hash = hash xor (byte.toLong() and 0xff)
                hash *= 0x100000001b3L
            }
            return "source_" + java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
        }
    }
}
