package com.amwolfstein.sistrum.player

import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisBridgeTest {
    @Test
    fun aubioAndFftwAreStaticallyLinkedIntoThePlayer() {
        assertTrue(AnalysisBridge.nativeTempoProbe())
    }
}
