package de.hendrikfrahmann.giteaplugin.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.job
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/*
 * Small replacements for collaboration-tools' `childScope`/`mapScoped`: those are internal or experimental and changed
 * between 261 and 263, so we don't link against them.
 */

/** A supervisor scope that is cancelled with this one. */
fun CoroutineScope.childScope(context: CoroutineContext = EmptyCoroutineContext): CoroutineScope =
  CoroutineScope(coroutineContext + context + SupervisorJob(coroutineContext.job))

/**
 * Maps each value to an object that lives in its own scope; that scope is cancelled when the next value arrives
 * or collection stops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T, R> Flow<T>.mapScoped(mapper: CoroutineScope.(T) -> R): Flow<R> = transformLatest { value ->
  coroutineScope {
    val scope = childScope()
    try {
      emit(scope.mapper(value))
      awaitCancellation()
    }
    finally {
      scope.cancel()
    }
  }
}
