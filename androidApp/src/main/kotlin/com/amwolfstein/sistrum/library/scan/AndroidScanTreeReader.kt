package com.amwolfstein.sistrum.library.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Reads SAF trees through DocumentsContract. Providers may answer with EXTRA_LOADING while they are
 * still fetching children and may fail transiently; both cases are retried with a bounded budget.
 */
class AndroidScanTreeReader(
    private val context: Context,
    private val retries: Int = 2,
    private val retryDelayMs: Long = 250L,
) : ScanTreeReader {

    override suspend fun children(treeUri: String, documentId: String): List<ScanDocument> =
        withContext(Dispatchers.IO) {
            val tree = Uri.parse(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            var attempt = 0
            while (true) {
                try {
                    val collected = linkedMapOf<String, ScanDocument>()
                    var loading = true
                    var passes = 0
                    while (loading && passes < 4) {
                        loading = false
                        context.contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
                            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                            while (cursor.moveToNext()) {
                                val id = cursor.getString(idIndex) ?: continue
                                val name = cursor.getString(nameIndex) ?: id.substringAfterLast('/')
                                collected[id] = ScanDocument(
                                    documentId = id,
                                    name = name,
                                    mimeType = cursor.getString(mimeIndex),
                                    size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                                    lastModified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else 0L,
                                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                                )
                            }
                            loading = cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)
                        }
                        if (loading) {
                            passes += 1
                            delay(retryDelayMs)
                        }
                    }
                    return@withContext collected.values.toList()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (attempt >= retries) throw failure
                    attempt += 1
                    delay(retryDelayMs * attempt)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            emptyList()
        }

    override suspend fun readText(document: ScanDocument): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(Uri.parse(document.uri))?.use { stream ->
            val limit = MAX_TEXT_BYTES
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var total = 0
            while (total < limit) {
                val read = stream.read(chunk, 0, minOf(chunk.size, limit - total))
                if (read < 0) break
                buffer.write(chunk, 0, read)
                total += read
            }
            buffer.toByteArray().toString(Charsets.UTF_8)
        } ?: throw IllegalStateException("Cannot open ${document.uri}")
    }

    companion object {
        private const val MAX_TEXT_BYTES = 2 * 1024 * 1024

        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
