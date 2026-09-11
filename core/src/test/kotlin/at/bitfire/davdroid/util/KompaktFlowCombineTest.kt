/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class KompaktFlowCombineTest {

    // Nine distinct types so a swapped array index fails the test, not just the type checker.
    @Test
    fun `combines nine flows into their own typed parameters, in order`() = runTest {
        val result = combine(
            MutableStateFlow(1),
            MutableStateFlow("two"),
            MutableStateFlow(3L),
            MutableStateFlow(4.0),
            MutableStateFlow(true),
            MutableStateFlow('f'),
            MutableStateFlow(listOf("g")),
            MutableStateFlow(8.toByte()),
            MutableStateFlow(9.toShort())
        ) { a, b, c, d, e, f, g, h, i -> "$a-$b-$c-$d-$e-$f-$g-$h-$i" }.first()

        assertEquals("1-two-3-4.0-true-f-[g]-8-9", result)
    }

}
