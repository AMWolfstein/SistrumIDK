package com.amwolfstein.sistrum.library.scan

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** A single entry returned by a SAF directory listing. */
data class ScanDocument(
    val documentId: String,
    val name: String,
    val mimeType: String?,
    val size: Long?,
    val lastModified: Long,
    val uri: String,
) {
    val isDirectory: Boolean
        get() = mimeType == ScanMime.DIRECTORY
}

object ScanMime {
    const val DIRECTORY = "vnd.android.document/directory"
}

/** A scanned audio file or a virtual track expanded from a CUE sheet. */
data class ScanEntry(
    val documentKey: String,
    val fileUri: String,
    val relativePath: String,
    val name: String,
    val modTime: Long,
    val size: Long?,
    val cueKey: String? = null,
    val cueTrackNumber: Int? = null,
    val cueStartMs: Long? = null,
    val cueEndMs: Long? = null,
    val cueTitle: String? = null,
    val cuePerformer: String? = null,
    val changed: Boolean = false,
)

data class ScanFileVersion(val modTime: Long, val size: Long?)

data class ScanErrorRecord(
    val documentKey: String?,
    val operation: String,
    val message: String,
    val occurredAt: Long,
)

data class FolderCover(
    val folderPath: String,
    val documentKey: String,
    val fileUri: String,
    val name: String,
)

enum class ScanState { IDLE, RUNNING, PAUSED, CANCELLING, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED, FAILED }

data class ScanProgress(
    val runId: String,
    val sourceId: String,
    val state: ScanState,
    val totalFiles: Int = 0,
    val scannedFiles: Int = 0,
    val errorCount: Int = 0,
    val currentPath: String? = null,
)

data class ScanResult(
    val runId: String,
    val state: ScanState,
    val entries: List<ScanEntry>,
    val covers: List<FolderCover>,
    val errors: List<ScanErrorRecord>,
    val scannedFiles: Int,
    val complete: Boolean,
)

data class ScanRequest(
    val runId: String,
    val sourceId: String,
    val treeUri: String,
    val rootDocumentId: String,
    val priorDocumentKeys: Set<String> = emptySet(),
    val priorVersions: Map<String, ScanFileVersion> = emptyMap(),
    val alreadyScanned: Int = 0,
    val exclusions: List<ScanExclusion> = emptyList(),
)

data class ScanExclusion(val relativePath: String, val kind: String) {
    val isFolder: Boolean get() = kind == KIND_FOLDER

    companion object {
        const val KIND_FOLDER = "FOLDER"
        const val KIND_FILE = "FILE"
    }
}

/** Lists children of a SAF document. Implementations may retry transient provider failures. */
interface ScanTreeReader {
    suspend fun children(treeUri: String, documentId: String): List<ScanDocument>

    /** Reads a small text document (CUE sheets). */
    suspend fun readText(document: ScanDocument): String
}

/** Receives incremental scan state so a run can survive process death. */
interface ScanCheckpoint {
    suspend fun alreadySeen(documentKey: String): Boolean
    suspend fun record(entry: ScanEntry)
    suspend fun recordError(error: ScanErrorRecord)
    suspend fun reportProgress(scannedFiles: Int, totalFiles: Int, currentPath: String?)
}

/** Pause/cancel coordination for a running scan. */
class ScanControl {
    @Volatile
    var isCancelled: Boolean = false
        private set

    private val pauseGate = MutableStateFlow(false)

    val isPaused: Boolean get() = pauseGate.value

    fun pause() {
        pauseGate.value = true
    }

    fun resume() {
        pauseGate.value = false
    }

    fun cancel() {
        isCancelled = true
        pauseGate.value = false
    }

    suspend fun awaitIfPaused() {
        if (pauseGate.value) {
            pauseGate.first { !it }
        }
    }

    fun throwIfCancelled() {
        if (isCancelled) throw ScanCancelledException()
    }
}

class ScanCancelledException : Exception("Scan cancelled")

object AudioFormats {
    val extensions: Set<String> = setOf(
        "mp3", "flac", "m4a", "mp4", "aac", "ogg", "oga", "opus", "wav", "wave",
        "aif", "aiff", "aifc", "ape", "wv", "dsf", "dff", "wma", "mka", "alac",
    )

    val cueExtension = "cue"

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isAudio(name: String): Boolean = extensionOf(name) in extensions

    fun isCue(name: String): Boolean = extensionOf(name) == cueExtension
}
