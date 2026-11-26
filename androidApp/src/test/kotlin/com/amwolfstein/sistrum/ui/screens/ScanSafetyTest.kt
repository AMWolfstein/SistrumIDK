package com.amwolfstein.sistrum.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanSafetyTest {
    @Test
    fun findsPrimaryAndRemovableVolumeRoots() {
        assertEquals("/storage/emulated/0", externalStorageRoot("/storage/emulated/0/Music/a.mp3"))
        assertEquals("/storage/1234-5678", externalStorageRoot("/storage/1234-5678/Music/a.mp3"))
        assertEquals(null, externalStorageRoot("/other/a.mp3"))
    }

}
