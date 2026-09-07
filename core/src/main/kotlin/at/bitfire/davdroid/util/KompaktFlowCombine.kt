/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Returns a [Flow] whose values are generated with [transform] function by combining the most
 * recently emitted values by each flow.
 *
 * kotlinx.coroutines only provides this for up to five flows; this is the same pattern one call
 * further, built on its own untyped vararg [combine] rather than any internal API.
 */
@Suppress("UNCHECKED_CAST")
fun <T1, T2, T3, T4, T5, T6, T7, R> combine(
    flow1: Flow<T1>,
    flow2: Flow<T2>,
    flow3: Flow<T3>,
    flow4: Flow<T4>,
    flow5: Flow<T5>,
    flow6: Flow<T6>,
    flow7: Flow<T7>,
    transform: suspend (T1, T2, T3, T4, T5, T6, T7) -> R
): Flow<R> = combine(flow1, flow2, flow3, flow4, flow5, flow6, flow7) { args: Array<*> ->
    transform(
        args[0] as T1,
        args[1] as T2,
        args[2] as T3,
        args[3] as T4,
        args[4] as T5,
        args[5] as T6,
        args[6] as T7
    )
}
