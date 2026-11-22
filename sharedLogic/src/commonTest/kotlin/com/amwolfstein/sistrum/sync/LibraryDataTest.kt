package com.amwolfstein.sistrum.sync

import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryDataTest {
    @Test
    fun decodesDesktopManifestWithNullOptionalCollections() {
        val manifest = LibraryJson.json.decodeFromString(
            LibraryPlanManifest.serializer(),
            """{"version":1,"plan_id":"plan","revision":"${"b".repeat(64)}","scope":{},"tracks":[],"playlists":null,"lyrics":{},"analysis":{},"assets":[]}""",
        )

        assertEquals(emptyList(), manifest.playlists.orEmpty())
        assertEquals(false, manifest.libraryAnalysisEnabled)
    }

    @Test
    fun roundTripsLibraryAnalysisFlag() {
        val manifest = LibraryPlanManifest(1, "plan", "b".repeat(64), buildJsonObject { }, lyrics = buildJsonObject { }, analysis = buildJsonObject { }, libraryAnalysisEnabled = true)
        assertEquals(true, LibraryJson.json.decodeFromString(LibraryPlanManifest.serializer(), LibraryJson.json.encodeToString(LibraryPlanManifest.serializer(), manifest)).libraryAnalysisEnabled)
    }
}
