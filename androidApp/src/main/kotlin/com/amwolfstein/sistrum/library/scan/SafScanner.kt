package com.amwolfstein.sistrum.library.scan

import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Breadth-first SAF scanner. It is deliberately free of Android framework calls so the traversal,
 * CUE expansion, exclusion and checkpoint rules can be tested on the host.
 *
 * Guarantees:
 * - deletion decisions are never made here; the scanner only enumerates and checkpoints
 * - a checkpoint makes a rerun after process death resume rather than restart
 * - overlapping roots and resumed runs never emit the same document key twice
 */
class SafScanner(
    private val reader: ScanTreeReader,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun scan(
        request: ScanRequest,
        checkpoint: ScanCheckpoint,
        control: ScanControl = ScanControl(),
    ): ScanResult {
        val entries = mutableListOf<ScanEntry>()
        val covers = linkedMapOf<String, FolderCover>()
        val errors = mutableListOf<ScanErrorRecord>()
        val seenKeys = request.priorDocumentKeys.toMutableSet()
        val seenFolders = mutableSetOf<String>()
        var scannedFiles = request.alreadyScanned
        var totalFiles = request.alreadyScanned
        var rootFailed = false

        suspend fun recordError(documentKey: String?, operation: String, message: String) {
            val error = ScanErrorRecord(documentKey, operation, message, now())
            errors += error
            checkpoint.recordError(error)
        }

        fun exclusionFor(relativePath: String): Boolean =
            request.exclusions.any { exclusion ->
                if (exclusion.isFolder) {
                    relativePath == exclusion.relativePath ||
                        relativePath.startsWith(exclusion.relativePath.trimEnd('/') + "/")
                } else {
                    relativePath == exclusion.relativePath
                }
            }

        val queue = ArrayDeque<Folder>()
        queue.add(Folder(request.rootDocumentId, ""))

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            control.throwIfCancelled()
            control.awaitIfPaused()

            val folder = queue.removeFirst()
            if (!seenFolders.add(folder.path)) continue
            val documents = try {
                reader.children(request.treeUri, folder.documentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recordError(
                    documentKeyFor(request.treeUri, folder.documentId),
                    "list",
                    failure.message ?: failure::class.java.simpleName,
                )
                if (folder.path.isEmpty()) {
                    rootFailed = true
                    break
                }
                continue
            }

            val ordered = documents.sortedBy { it.name.lowercase(Locale.ROOT) }
            val childFolders = mutableListOf<Folder>()
            val files = mutableListOf<Pair<ScanDocument, String>>()

            ordered.forEach { document ->
                val relativePath = if (folder.path.isEmpty()) document.name else "${folder.path}/${document.name}"
                if (exclusionFor(relativePath)) return@forEach
                if (document.isDirectory) {
                    childFolders += Folder(document.documentId, relativePath)
                } else {
                    files += document to relativePath
                }
            }

            SafFolderCovers.pick(folder.path, ordered)?.let { (_, cover) ->
                covers.putIfAbsent(folder.path, cover)
            }

            val cueDocuments = files.filter { (document, _) -> AudioFormats.isCue(document.name) }
            val cueReferencedNames = mutableSetOf<String>()

            cueDocuments.forEach { (document, relativePath) ->
                val cueKey = documentKeyOf(document)
                // The audio bytes may be untouched while the sheet's titles, offsets or
                // performers changed; every segment then needs a metadata refresh.
                val cueVersion = request.priorVersions[cueKey]
                val cueChanged = cueVersion == null ||
                    cueVersion.modTime != document.lastModified ||
                    cueVersion.size != document.size
                val expanded = try {
                    val text = reader.readText(document)
                    val sheet = SafCueParser.parse(text)
                    val audioFileIndexes = sheet.tracks.filter { it.isAudio }.map { it.fileIndex }.toSet()
                    audioFileIndexes.forEach { index ->
                        sheet.files.getOrNull(index)?.let {
                            cueReferencedNames += it.name.lowercase(Locale.ROOT)
                        }
                    }
                    expandCue(
                        cue = sheet,
                        cueDocument = document,
                        cueRelativePath = relativePath,
                        folderDocuments = files.map { it.first },
                        treeUri = request.treeUri,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    recordError(cueKey, "cue", failure.message ?: failure::class.java.simpleName)
                    emptyList()
                }.map { entry ->
                    val previous = request.priorVersions[entry.documentKey]
                    entry.copy(
                        changed = cueChanged ||
                            previous == null ||
                            previous.modTime != entry.modTime ||
                            previous.size != entry.size,
                    )
                }
                expanded.forEach { entry ->
                    if (entry.documentKey in seenKeys || checkpoint.alreadySeen(entry.documentKey)) return@forEach
                    seenKeys += entry.documentKey
                    totalFiles += 1
                    entries += entry
                    checkpoint.record(entry)
                    scannedFiles += 1
                }
            }

            files.filter { (document, _) ->
                AudioFormats.isAudio(document.name) &&
                    document.name.lowercase(Locale.ROOT) !in cueReferencedNames
            }.forEach { (document, relativePath) ->
                val key = documentKeyOf(document)
                if (key in seenKeys || checkpoint.alreadySeen(key)) return@forEach
                seenKeys += key
                totalFiles += 1
                val entry = ScanEntry(
                    documentKey = key,
                    fileUri = document.uri,
                    relativePath = relativePath,
                    name = document.name,
                    modTime = document.lastModified,
                    size = document.size,
                    changed = isChanged(document, request.priorVersions),
                )
                entries += entry
                checkpoint.record(entry)
                scannedFiles += 1
            }

            checkpoint.reportProgress(scannedFiles, totalFiles, folder.path.ifEmpty { "/" })
            queue.addAll(childFolders)
        }

        val state = when {
            control.isCancelled -> ScanState.CANCELLED
            rootFailed -> ScanState.FAILED
            errors.isNotEmpty() -> ScanState.COMPLETED_WITH_ERRORS
            else -> ScanState.COMPLETED
        }
        return ScanResult(
            runId = request.runId,
            state = state,
            entries = entries,
            covers = covers.values.toList(),
            errors = errors,
            scannedFiles = scannedFiles,
            complete = !rootFailed && errors.isEmpty(),
        )
    }

    private fun isChanged(document: ScanDocument, prior: Map<String, ScanFileVersion>): Boolean {
        val previous = prior[documentKeyOf(document)] ?: return true
        return previous.modTime != document.lastModified || previous.size != document.size
    }

    private fun expandCue(
        cue: CueSheet,
        cueDocument: ScanDocument,
        cueRelativePath: String,
        folderDocuments: List<ScanDocument>,
        treeUri: String,
    ): List<ScanEntry> {
        val byName = folderDocuments.associateBy { it.name.lowercase(Locale.ROOT) }
        val cueKey = documentKeyOf(cueDocument)
        return cue.tracks.filter { it.isAudio }.mapNotNull { track ->
            val file = cue.files.getOrNull(track.fileIndex) ?: return@mapNotNull null
            val audio = byName[file.name.lowercase(Locale.ROOT)] ?: return@mapNotNull null
            val suffix = cueRelativePath.substringAfterLast('/')
            ScanEntry(
                documentKey = "$cueKey#${track.fileIndex}#${file.name}#${track.number}",
                fileUri = audio.uri,
                relativePath = cueRelativePath,
                name = track.title ?: "$suffix #${track.number}",
                modTime = audio.lastModified,
                size = audio.size,
                cueKey = cueKey,
                cueTrackNumber = track.number,
                cueStartMs = track.startMs,
                cueEndMs = track.endMs,
                cueTitle = track.title,
                cuePerformer = track.performer,
            )
        }
    }

    private data class Folder(val documentId: String, val path: String)
}
