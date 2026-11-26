package com.amwolfstein.sistrum.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class DeezerArtworkTest {
    private lateinit var base: Context
    private lateinit var isolated: Context
    private lateinit var files: File
    private lateinit var prefsName: String

    @Before
    fun setup() {
        base = ApplicationProvider.getApplicationContext()
        prefsName = "deezer-test-${UUID.randomUUID()}"
        files = File(base.cacheDir, prefsName).apply { mkdirs() }
        isolated = object : ContextWrapper(base) {
            override fun getFilesDir(): File = files
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                base.getSharedPreferences(prefsName, mode)
        }
    }

    @After
    fun cleanup() {
        base.deleteSharedPreferences(prefsName)
        files.deleteRecursively()
    }

    @Test
    fun defaultsOffAndRefreshesCachedImageWhenEnabled() = runBlocking {
        var calls = 0
        var color = android.graphics.Color.RED
        val service = DeezerArtwork(isolated) { url, _ ->
            calls++
            if (url.startsWith("https://api.deezer.com/")) response()
            else image(color)
        }
        val artist = LibraryArtist(id = "a", name = "Artist")
        assertFalse(service.enabled.value)
        service.refresh(listOf(artist))
        assertEquals(0, calls)
        service.setEnabled(true)
        service.refresh(listOf(artist))
        val oldPath = service.paths.value.getValue("a")
        color = android.graphics.Color.BLUE
        service.refresh(listOf(artist))
        val newPath = service.paths.value.getValue("a")
        assertNotEquals(oldPath, newPath)
        assertFalse(File(files, oldPath).exists())
        service.setEnabled(false)
        val callsBeforeDisabledRefresh = calls
        service.refresh(listOf(artist))
        assertEquals(callsBeforeDisabledRefresh, calls)
        val restarted = DeezerArtwork(isolated)
        assertFalse(restarted.enabled.value)
        assertEquals(newPath, restarted.paths.value.getValue("a"))
    }

    @Test
    fun offThenOnInvalidatesAnInFlightResponse() = runBlocking {
        lateinit var service: DeezerArtwork
        var toggleDuringImage = false
        service = DeezerArtwork(isolated) { url, _ ->
            if (url.startsWith("https://api.deezer.com/")) response()
            else {
                if (toggleDuringImage) {
                    service.setEnabled(false)
                    service.setEnabled(true)
                }
                image(if (toggleDuringImage) android.graphics.Color.BLUE else android.graphics.Color.RED)
            }
        }
        val artist = LibraryArtist(id = "a", name = "Artist")
        service.setEnabled(true)
        service.refresh(listOf(artist))
        val oldPath = service.paths.value.getValue("a")
        toggleDuringImage = true
        service.refresh(listOf(artist))
        assertEquals(oldPath, service.paths.value.getValue("a"))
    }

    @Test
    fun ambiguousMatchesAndNetworkFailuresLeaveExistingImageAlone() = runBlocking {
        var result = "match"
        var images = 0
        val service = DeezerArtwork(isolated) { url, _ ->
            if (url.startsWith("https://api.deezer.com/")) {
                when (result) {
                    "match" -> response()
                    "ambiguous" -> """{"data":[{"name":"Artist","picture_medium":"https://e-cdns-images.dzcdn.net/a"},{"name":"Artist","picture_medium":"https://e-cdns-images.dzcdn.net/b"}]}""".toByteArray()
                    else -> null
                }
            } else {
                images++
                image(android.graphics.Color.RED)
            }
        }
        val artist = LibraryArtist(id = "a", name = "Artist")
        service.setEnabled(true)
        service.refresh(listOf(artist))
        val oldPath = service.paths.value.getValue("a")
        result = "ambiguous"
        service.refresh(listOf(artist))
        result = "offline"
        service.refresh(listOf(artist))
        assertEquals(1, images)
        assertEquals(oldPath, service.paths.value.getValue("a"))
    }

    private fun response(): ByteArray =
        """{"data":[{"name":"Artist","picture_medium":"https://e-cdns-images.dzcdn.net/artist.jpg"}]}""".toByteArray()

    private fun image(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
