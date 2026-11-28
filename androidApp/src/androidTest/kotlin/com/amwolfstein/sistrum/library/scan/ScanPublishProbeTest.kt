package com.amwolfstein.sistrum.library.scan

import androidx.test.core.app.ApplicationProvider
import com.amwolfstein.sistrum.library.LibraryRepository
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** One-shot probe: republish the latest completed scan run and print what happens. */
class ScanPublishProbeTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun republishLatestRun() = runBlocking {
        val database = LibraryDatabase.get(context)
        val dao = database.libraryDao()
        val source = dao.source("source_9f738cd031ffeb2e") ?: dao.sources().firstOrNull()
        println("PROBE sources=${dao.sources().map { it.id }}")
        if (source == null) {
            println("PROBE no source")
            return@runBlocking
        }
        val run = dao.latestCompletedScanRun(source.id) ?: dao.latestScanRun(source.id)
        println("PROBE run=${run?.id} state=${run?.state}")
        if (run == null) return@runBlocking
        println("PROBE before items=${dao.itemCount()} entries=${dao.scanEntries(run.id).size}")
        val result = runCatching {
            LibraryRepository(context).publish(source, run.id, complete = true)
        }
        println("PROBE result=$result items=${dao.itemCount()}")
        result.exceptionOrNull()?.printStackTrace()
    }
}
