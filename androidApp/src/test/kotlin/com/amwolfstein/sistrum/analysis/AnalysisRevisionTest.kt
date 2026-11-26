package com.amwolfstein.sistrum.analysis

import com.amwolfstein.sistrum.sync.identityHash
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisRevisionTest {
    @Test
    fun matchesScannerPathSizeAndSecondsAndChangesWithFile() {
        val file = File.createTempFile("analysis-revision-", ".tmp")
        try {
            file.writeText("audio")
            assertEquals(true, file.setLastModified(1_700_000_000_456L))
            val expected = identityHash("${file.absolutePath}|5|1700000000")
            assertEquals(expected, localAnalysisFileRevision(file))
            file.appendText("changed")
            assertNotEquals(expected, localAnalysisFileRevision(file))
        } finally {
            file.delete()
        }
        assertNull(localAnalysisFileRevision(file))
    }
}
