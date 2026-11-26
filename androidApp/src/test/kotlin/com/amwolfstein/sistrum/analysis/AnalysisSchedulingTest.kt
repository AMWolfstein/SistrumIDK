package com.amwolfstein.sistrum.analysis

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisSchedulingTest {
    @Test
    fun cancelledWorkersKeepPermitsUntilNativeWorkActuallyCloses() = runBlocking {
        val dispatcher = BoundedAnalysisDispatcher()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val created = AtomicInteger()
        val open = AtomicInteger()
        val maxOpen = AtomicInteger()

        suspend fun work(): Int = dispatcher.run(
            create = { created.incrementAndGet() },
            execute = { id ->
                val active = open.incrementAndGet()
                maxOpen.updateAndGet { previous -> maxOf(previous, active) }
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                open.decrementAndGet()
                id
            },
            cancel = { },
            close = { },
        )

        val first = async(Dispatchers.Default) { work() }
        val second = async(Dispatchers.Default) { work() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        first.cancelAndJoin()
        second.cancelAndJoin()
        repeat(20) {
            val queued = launch(Dispatchers.Default) { work() }
            delay(2)
            queued.cancelAndJoin()
        }
        val later = async(Dispatchers.Default) { work() }
        delay(100)
        assertEquals(2, created.get())
        release.countDown()
        assertEquals(3, later.await())
        assertTrue(maxOpen.get() <= 2)
    }

    @Test
    fun continuousChangesAreDeliveredWithoutWaitingForAQuietPeriod() = runBlocking {
        val events = MutableSharedFlow<Int>(extraBufferCapacity = 64)
        val seen = mutableListOf<Int>()
        val collector = launch {
            events.coalesceAnalysisChanges(intervalMillis = 20).collect { seen += it }
        }
        yield()
        repeat(30) { index ->
            events.emit(index)
            delay(5)
        }
        assertTrue("updates while producer is active: ${seen.size}", seen.size >= 3)
        collector.cancelAndJoin()
    }
}
