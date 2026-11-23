package com.amwolfstein.sistrum.sync

import android.content.Context

/**
 * Process-lifetime holder for the local Room library store.
 */
internal object LocalLibraryRuntime {
    private lateinit var store: AndroidLibrarySyncStore
    private lateinit var appContext: Context
    private lateinit var database: SyncDatabase

    @Synchronized
    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        database = SyncDatabase.create(appContext)
        store = AndroidLibrarySyncStore(database, appContext.filesDir)
    }

    internal fun libraryStore(): AndroidLibrarySyncStore = store

    internal fun database(): SyncDatabase = database
}
