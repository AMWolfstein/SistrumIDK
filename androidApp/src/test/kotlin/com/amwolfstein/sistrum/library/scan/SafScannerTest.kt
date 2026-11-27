package com.amwolfstein.sistrum.library.scan

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafScannerTest {

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic"

    @Test
    fun scansBreadthFirstAndFiltersAudioExtensions() = runTest {
        val reader = FakeReader(
            folders = mapOf(
                "root" to listOf(
                    doc("a1", "Alpha.MP3", size = 10, modified = 5),
                    doc("readme", "readme.txt"),
                    doc("album", "Album", directory = true),
                ),
                "album" to listOf(
                    doc("b1", "Beta.FLAC", size = 20, modified = 6),
                    doc("b2", "notes.md"),
                ),
            ),
        )
        val checkpoint = MemoryCheckpoint()
        val result = SafScanner(reader) { 1000L }.scan(
            ScanRequest("run", "src", tree, "root"),
            checkpoint,
        )

        assertEquals(ScanState.COMPLETED, result.state)
        assertEquals(listOf("a1", "b1"), result.entries.map { it.documentIdOf() })
        assertEquals(listOf("a1", "b1"), checkpoint.entries.map { it.documentKey.substringAfterLast(':') })
        assertEquals(2, result.scannedFiles)
        assertEquals(listOf("/", "Album"), checkpoint.progressPaths)
    }

    @Test
    fun exclusionsSkipFilesAndWholeFolders() = runTest {
        val reader = FakeReader(
            folders = mapOf(
                "root" to listOf(
                    doc("keep", "Keep.mp3"),
                    doc("skip", "Skip.mp3"),
                    doc("private", "Private", directory = true),
                    doc("live", "Live", directory = true),
                ),
                "private" to listOf(doc("secret", "Secret.mp3")),
                "live" to listOf(doc("one", "One.mp3")),
            ),
        )
        val exclusions = listOf(
            ScanExclusion("Skip.mp3", ScanExclusion.KIND_FILE),
            ScanExclusion("Private", ScanExclusion.KIND_FOLDER),
        )
        val result = SafScanner(reader).scan(
            ScanRequest("run", "src", tree, "root", exclusions = exclusions),
            MemoryCheckpoint(),
        )
        assertEquals(listOf("keep", "one"), result.entries.map { it.documentIdOf() })
    }

    @Test
    fun cueSheetExpandsToVirtualTracksWithBoundaries() = runTest {
        val cue = """
            PERFORMER "Album Artist"
            TITLE "The Album"
            FILE "album.flac" WAVE
              TRACK 01 AUDIO
                TITLE "First"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Second"
                INDEX 01 03:20:00
              TRACK 03 AUDIO
                TITLE "Third"
                INDEX 01 07:00:37
        """.trimIndent()
        val reader = FakeReader(
            folders = mapOf(
                "root" to listOf(
                    doc("albumfile", "album.flac", size = 100),
                    doc("cuefile", "album.cue"),
                ),
            ),
            texts = mapOf("cuefile" to cue),
        )
        val result = SafScanner(reader).scan(ScanRequest("run", "src", tree, "root"), MemoryCheckpoint())

        assertEquals(3, result.entries.size)
        assertFalse(result.entries.any { it.documentKey.endsWith("album.cue") && it.cueKey == null })
        val first = result.entries[0]
        assertEquals("First", first.cueTitle)
        assertEquals("Album Artist", first.cuePerformer)
        assertEquals(0L, first.cueStartMs)
        assertEquals(200_000L, first.cueEndMs)
        assertEquals(200_000L, result.entries[1].cueStartMs)
        assertEquals(420_493L, result.entries[2].cueStartMs)
        assertNull(result.entries[2].cueEndMs)
        assertTrue(result.entries.all { it.fileUri.endsWith("albumfile") })
    }

    @Test
    fun cueWithMissingAudioFileRecordsNoVirtualTracks() = runTest {
        val reader = FakeReader(
            folders = mapOf(
                "root" to listOf(doc("cuefile", "missing.cue")),
            ),
            texts = mapOf("cuefile" to "FILE \"gone.flac\" WAVE\n TRACK 01 AUDIO\n INDEX 01 00:00:00"),
        )
        val result = SafScanner(reader).scan(ScanRequest("run", "src", tree, "root"), MemoryCheckpoint())
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun resumeSkipsDocumentsAlreadyCheckpointedForTheRun() = runTest {
        val reader = FakeReader(
            folders = mapOf(
                "root" to listOf(doc("a", "A.mp3"), doc("b", "B.mp3")),
            ),
        )
        val checkpoint = MemoryCheckpoint(initial = setOf("com.android.externalstorage.documents:a"))
        val result = SafScanner(reader).scan(ScanRequest("run", "src", tree, "root"), checkpoint)
        assertEquals(listOf("b"), result.entries.map { it.documentIdOf() })
        assertEquals(listOf("b"), checkpoint.entries.map { it.documentKey.substringAfterLast(':') })
    }

    @Test
    fun overlappingRootsDoNotEmitTheSameDocumentTwice() = runTest {
        val reader = FakeReader(
            folders = mapOf(
                "root" to listOf(doc("shared", "Shared.mp3"), doc("sub", "Sub", directory = true)),
                "sub" to listOf(doc("shared", "Shared.mp3", size = 8)),
            ),
        )
        val result = SafScanner(reader).scan(ScanRequest("run", "src", tree, "root"), MemoryCheckpoint())
        assertEquals(1, result.entries.size)
    }

    @Test
    fun picksBestFolderCover() = runTest {
        val reader = FakeReader(
            folders = mapOf(
                "root" to listOf(
                    doc("back", "back.jpg"),
                    doc("art", "albumart.png"),
                    doc("cover", "cover.jpg"),
                    doc("song", "song.mp3"),
                ),
            ),
        )
        val result = SafScanner(reader).scan(ScanRequest("run", "src", tree, "root"), MemoryCheckpoint())
        assertEquals(1, result.covers.size)
        assertEquals("cover.jpg", result.covers.single().name)
    }

    @Test(expected = ScanCancelledException::class)
    fun cancellationStopsTraversal() = runTest {
        val reader = FakeReader(folders = mapOf("root" to listOf(doc("a", "A.mp3"))))
        val control = ScanControl().apply { cancel() }
        SafScanner(reader).scan(ScanRequest("run", "src", tree, "root"), MemoryCheckpoint(), control)
    }

    @Test
    fun changedFlagComparesModTimeAndSizeAgainstThePriorRun() = runTest {
        val reader = FakeReader(
            folders = mapOf(
                "root" to listOf(
                    doc("same", "Same.mp3", size = 10, modified = 100),
                    doc("edited", "Edited.mp3", size = 10, modified = 200),
                    doc("new", "New.mp3", size = 5, modified = 1),
                ),
            ),
        )
        val prior = mapOf(
            "com.android.externalstorage.documents:same" to ScanFileVersion(100, 10),
            "com.android.externalstorage.documents:edited" to ScanFileVersion(100, 10),
        )
        val result = SafScanner(reader).scan(
            ScanRequest("run", "src", tree, "root", priorVersions = prior),
            MemoryCheckpoint(),
        )
        val changed = result.entries.associate { it.documentIdOf() to it.changed }
        assertFalse(changed.getValue("same"))
        assertTrue(changed.getValue("edited"))
        assertTrue(changed.getValue("new"))
    }

    @Test
    fun partialEnumerationIsReportedAsIncomplete() = runTest {
        val reader = FakeReader(
            folders = mapOf(
                "root" to listOf(doc("ok", "OK.mp3"), doc("bad", "Bad", directory = true)),
            ),
            failures = setOf("bad"),
        )
        val result = SafScanner(reader).scan(ScanRequest("run", "src", tree, "root"), MemoryCheckpoint())
        assertEquals(ScanState.COMPLETED_WITH_ERRORS, result.state)
        assertFalse(result.complete)
    }

    @Test
    fun rootFailureMarksTheRunFailed() = runTest {
        val reader = FakeReader(folders = emptyMap(), failures = setOf("root"))
        val result = SafScanner(reader).scan(ScanRequest("run", "src", tree, "root"), MemoryCheckpoint())
        assertEquals(ScanState.FAILED, result.state)
        assertFalse(result.complete)
    }

    @Test
    fun pauseStopsAtTheNextFolderAndResumeContinues() = runTest {
        val reader = FakeReader(
            folders = mapOf(
                "root" to listOf(doc("one", "One.mp3"), doc("sub", "Sub", directory = true)),
                "sub" to listOf(doc("two", "Two.mp3")),
            ),
        )
        val control = ScanControl()
        val checkpoint = object : ScanCheckpoint by MemoryCheckpoint() {
            var paused = false
            override suspend fun reportProgress(scannedFiles: Int, totalFiles: Int, currentPath: String?) {
                if (!paused) {
                    paused = true
                    control.pause()
                }
            }
        }
        val scan = async { SafScanner(reader).scan(ScanRequest("run", "src", tree, "root"), checkpoint, control) }
        advanceUntilIdle()
        assertFalse(scan.isCompleted)
        control.resume()
        advanceUntilIdle()
        assertTrue(scan.isCompleted)
        assertEquals(2, scan.await().entries.size)
    }

    @Test
    fun dataOnlyCueKeepsTheBackingAudioVisible() = runTest {
        val reader = FakeReader(
            folders = mapOf(
                "root" to listOf(doc("audio", "album.flac"), doc("cuefile", "album.cue")),
            ),
            texts = mapOf("cuefile" to "FILE \"album.flac\" WAVE\n REM no tracks\n"),
        )
        val result = SafScanner(reader).scan(ScanRequest("run", "src", tree, "root"), MemoryCheckpoint())
        assertEquals(1, result.entries.size)
        assertEquals("audio", result.entries.single().documentIdOf())
    }

    @Test
    fun listingFailureIsRecordedAndOtherFoldersContinue() = runTest {
        val reader = FakeReader(
            folders = mapOf("root" to listOf(doc("ok", "OK.mp3"), doc("bad", "Bad", directory = true))),
            failures = setOf("bad"),
        )
        val result = SafScanner(reader).scan(ScanRequest("run", "src", tree, "root"), MemoryCheckpoint())
        assertEquals(listOf("ok"), result.entries.map { it.documentIdOf() })
        assertEquals(1, result.errors.size)
        assertEquals("list", result.errors.single().operation)
        assertNotNull(result.errors.single().documentKey)
    }

    private fun doc(
        id: String,
        name: String,
        directory: Boolean = false,
        size: Long? = null,
        modified: Long = 0,
    ) = ScanDocument(
        documentId = id,
        name = name,
        mimeType = if (directory) ScanMime.DIRECTORY else "audio/mpeg",
        size = size,
        lastModified = modified,
        uri = "content://com.android.externalstorage.documents/document/$id",
    )

    private fun ScanEntry.documentIdOf(): String = documentKey.substringAfterLast(':')

    private class FakeReader(
        private val folders: Map<String, List<ScanDocument>>,
        private val texts: Map<String, String> = emptyMap(),
        private val failures: Set<String> = emptySet(),
    ) : ScanTreeReader {
        override suspend fun children(treeUri: String, documentId: String): List<ScanDocument> {
            if (documentId in failures) throw IllegalStateException("provider error")
            return folders[documentId].orEmpty()
        }

        override suspend fun readText(document: ScanDocument): String =
            texts[document.documentId] ?: throw IllegalStateException("no text")
    }

    private class MemoryCheckpoint(initial: Set<String> = emptySet()) : ScanCheckpoint {
        val seen = initial.toMutableSet()
        val entries = mutableListOf<ScanEntry>()
        val errors = mutableListOf<ScanErrorRecord>()
        val progressPaths = mutableListOf<String>()

        override suspend fun alreadySeen(documentKey: String): Boolean = documentKey in seen

        override suspend fun record(entry: ScanEntry) {
            if (seen.add(entry.documentKey)) entries += entry
        }

        override suspend fun recordError(error: ScanErrorRecord) {
            errors += error
        }

        override suspend fun reportProgress(scannedFiles: Int, totalFiles: Int, currentPath: String?) {
            currentPath?.let { progressPaths += it }
        }
    }
}
