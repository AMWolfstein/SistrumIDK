package com.amwolfstein.sistrum.metadata

import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.sync.EmbeddedTagReader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class Mp3ReplacementTest {
    @Test fun realMp3ReadsUpdatedMetadataAndTimedLyrics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = instrumentation.targetContext.cacheDir
        val source = File.createTempFile("source-", ".mp3", directory)
        val target = File.createTempFile("target-", ".tmp", directory)
        try {
            instrumentation.context.assets.open("analysis/mono.mp3").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val lyrics = "[00:01.00]كلمات\n[00:02.00]Second line"
            buildMp3Replacement(source, target, Mp3TagUpdate("عنوان", "Artist", "Album", lyrics))
            assertEquals(lyrics, EmbeddedTagReader.embeddedLyricsText(target.path))
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(target.path)
                assertEquals("عنوان", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                assertEquals("Artist", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
                assertEquals("Album", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            } finally {
                reader.release()
            }
        } finally {
            source.delete()
            target.delete()
        }
    }
}
