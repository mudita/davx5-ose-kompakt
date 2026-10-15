/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class KompaktEinkDisplayModeTest {

    class FakeMeinkManager {
        val calls = mutableListOf<Pair<Context, Int>>()

        @Suppress("unused")
        fun setDisplayMode(context: Context, mode: Int) {
            calls += context to mode
        }
    }

    class ThrowingMeinkManager {
        @Suppress("unused")
        fun setDisplayMode(context: Context, mode: Int): Unit = throw RuntimeException("system server died")
    }

    private fun contextWithMeink(service: Any?) = mockk<Context> {
        every { getSystemService("meink") } returns service
    }

    @Test
    fun `the mode is sent as the KompaktOS RefreshMode ordinal`() {
        assertEquals(
            listOf("AUTO", "FAST", "FAST_DITHER", "TEXT", "QUALITY"),
            KompaktEinkDisplayMode.entries.map { it.name }
        )
        val manager = FakeMeinkManager()
        val context = contextWithMeink(manager)

        KompaktEinkDisplayMode.FAST_DITHER.apply(context)

        assertEquals(1, manager.calls.size)
        assertSame(context, manager.calls.single().first)
        assertEquals(2, manager.calls.single().second)
    }

    @Test
    fun `nothing happens on a device without the meink service`() {
        KompaktEinkDisplayMode.FAST_DITHER.apply(contextWithMeink(null))
    }

    @Test
    fun `a failing service does not crash the caller`() {
        KompaktEinkDisplayMode.FAST_DITHER.apply(contextWithMeink(ThrowingMeinkManager()))
    }

    @Test
    fun `a service without setDisplayMode does not crash the caller`() {
        KompaktEinkDisplayMode.FAST_DITHER.apply(contextWithMeink(Any()))
    }

}
