package com.amwolfstein.sistrum.analysis

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onEach

/** Emits at a bounded cadence even while analysis results arrive continuously. */
internal fun <T> Flow<T>.coalesceAnalysisChanges(intervalMillis: Long = 1_000): Flow<T> =
    conflate().onEach { delay(intervalMillis) }
