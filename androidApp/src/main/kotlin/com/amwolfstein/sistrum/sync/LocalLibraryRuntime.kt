package com.amwolfstein.sistrum.sync

import android.content.Context

/**
 * Process-lifetime holder for the local Room library store.
 */
internal object LocalLibraryRuntime {
    private lateinit var store: AndroidLibrarySyncStore
    private lateinit var appContext: Context
    private lateinit var database: SyncDatabase

    /** Room may create the file before the playback DataStore's first read. */
    @Volatile internal var libraryDatabaseExistedAtStart: Boolean? = null
        private set

    @Synchronized
    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        libraryDatabaseExistedAtStart = appContext.getDatabasePath("library-sync.db").exists()
        database = SyncDatabase.create(appContext)
        store = AndroidLibrarySyncStore(database, appContext.filesDir)
    }

    internal fun libraryStore(): AndroidLibrarySyncStore = store

    internal fun database(): SyncDatabase = database
}
