package com.amwolfstein.sistrum.player

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegDescriptorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var audio: File? = null

    @After
    fun tearDown() {
        audio?.delete()
    }

    @Test
    fun decodesFromAnOpenDescriptorTheSameWayAsFromAPath() {
        val file = File(context.cacheDir, "descriptor-fixture.mp3")
        audio = file
        InstrumentationRegistry.getInstrumentation().context.assets.open("taglib/fixture.mp3").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val decoder = FfmpegDecoder()
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                decoder.prepare(descriptor)
            }
            assertTrue(decoder.durationMs() > 0)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun preloadsFromAnOpenDescriptor() {
        val file = File(context.cacheDir, "descriptor-preload.mp3")
        audio = file
        InstrumentationRegistry.getInstrumentation().context.assets.open("taglib/fixture.mp3").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val decoder = FfmpegDecoder()
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                decoder.prepare(descriptor)
            }
            val loaded = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                decoder.preload(descriptor)
            }
            assertTrue(loaded)
            assertTrue(decoder.preloadedDurationMs() > 0)
        } finally {
            decoder.close()
        }
    }
}
