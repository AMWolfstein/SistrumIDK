package com.amwolfstein.sistrum.sync

import android.content.Context
import com.amwolfstein.sistrum.analysis.LocalAnalysisPreferences
import com.amwolfstein.sistrum.analysis.LocalAnalysisController

/**
 * Process-lifetime holder for the local Room library store.
 */
internal object LocalLibraryRuntime {
    private lateinit var store: AndroidLibrarySyncStore
    private lateinit var appContext: Context
    internal lateinit var analysisController: LocalAnalysisController
        private set
    internal lateinit var analysisPreferences: LocalAnalysisPreferences
        private set
    internal lateinit var deezerArtwork: DeezerArtwork
        private set

    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        deezerArtwork = DeezerArtwork(appContext)
        analysisPreferences = LocalAnalysisPreferences(appContext)
        store = AndroidLibrarySyncStore(SyncDatabase.create(appContext), appContext.filesDir, deezerArtwork, analysisPreferences)
        analysisController = LocalAnalysisController(store, analysisPreferences)
        analysisController.start()
    }

    internal fun libraryStore(): AndroidLibrarySyncStore = store
}
