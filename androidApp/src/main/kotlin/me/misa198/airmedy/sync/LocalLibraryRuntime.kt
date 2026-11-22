package me.misa198.airmedy.sync

import android.content.Context

/**
 * Process-lifetime holder for the local Room library store.
 */
internal object LocalLibraryRuntime {
    private lateinit var store: AndroidLibrarySyncStore
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        store = AndroidLibrarySyncStore(SyncDatabase.create(appContext), appContext.filesDir)
    }

    internal fun libraryStore(): AndroidLibrarySyncStore = store
}
