package com.amwolfstein.sistrum.analysis

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A permit is held until native work has actually closed, including after
 * coroutine cancellation. This bounds handles, running work and queued work.
 */
internal class BoundedAnalysisDispatcher {
    private val permits = Semaphore(2)
    private val executor = ThreadPoolExecutor(
        2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2),
    ) { runnable -> Thread(runnable, "sistrum-library-analysis").apply { isDaemon = true } }

    suspend fun <Resource, Result> run(
        create: () -> Resource,
        execute: (Resource) -> Result,
        cancel: (Resource) -> Unit,
        close: (Resource) -> Unit,
    ): Result {
        permits.acquire()
        return suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) {
                permits.release()
                return@suspendCancellableCoroutine
            }
            val resource = try {
                create()
            } catch (error: Throwable) {
                permits.release()
                continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { runCatching { cancel(resource) } }
            try {
                executor.execute {
                    try {
                        if (!continuation.isActive) return@execute
                        val result = execute(resource)
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        try {
                            close(resource)
                        } finally {
                            permits.release()
                        }
                    }
                }
            } catch (error: Throwable) {
                try {
                    close(resource)
                } finally {
                    permits.release()
                }
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }
}
