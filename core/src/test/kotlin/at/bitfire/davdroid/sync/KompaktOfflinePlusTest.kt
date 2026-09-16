/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.content.ContentResolver
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktOfflinePlusTest {

    private val context = RuntimeEnvironment.getApplication()
    private val resolver: ContentResolver = context.contentResolver

    private fun offlinePlus() = KompaktOfflinePlus(context, UnconfinedTestDispatcher())

    // Robolectric's looper is paused, so a broadcast sits in the queue until it is idled.
    private fun broadcast(action: String) {
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun putState(value: Int) =
        Settings.Global.putInt(resolver, KompaktOfflinePlusSwitch.STATE_KEY, value)


    // isOn

    @Test
    fun `zero means Offline plus is off`() {
        putState(0)

        assertFalse(KompaktOfflinePlusSwitch.isOn(resolver))
    }

    @Test
    fun `one means Offline plus is on`() {
        putState(1)

        assertTrue(KompaktOfflinePlusSwitch.isOn(resolver))
    }

    // The device contract is the string itself, not the constant's name: KompaktOS writes this row, and
    // nothing else in the build would fail if it were mistyped.
    @Test
    fun `the key is the row KompaktOS writes`() {
        assertEquals("HWSwitch_lock", KompaktOfflinePlusSwitch.STATE_KEY)
    }

    @Test
    fun `hardware with no switch writes no key, and reads as Offline plus off`() {
        // The key only exists on KompaktOS; on anything else the read must answer rather than raise,
        // because the app runs on other hardware during development.
        assertFalse(KompaktOfflinePlusSwitch.isOn(resolver))
    }


    // stateOf

    @Test
    fun `each broadcast names one state`() {
        assertEquals(true, KompaktOfflinePlusSwitch.stateOf(KompaktOfflinePlusSwitch.ACTION_ON))
        assertEquals(false, KompaktOfflinePlusSwitch.stateOf(KompaktOfflinePlusSwitch.ACTION_OFF))
    }

    @Test
    fun `an unrelated broadcast names none`() {
        // The receiver is registered EXPORTED, so anything may reach it.
        assertNull(KompaktOfflinePlusSwitch.stateOf("android.intent.action.AIRPLANE_MODE"))
        assertNull(KompaktOfflinePlusSwitch.stateOf(null))
    }


    // observe

    // The seed is what makes the whole feature work: the broadcasts report only a *change*, so without
    // it a collector that starts with Offline+ already on learns nothing, and the combine it feeds never
    // produces a first value at all — leaving Link account silently unguarded.
    @Test
    fun `the current state is reported before any broadcast arrives`() = runTest(UnconfinedTestDispatcher()) {
        putState(1)

        assertTrue(offlinePlus().observe().first())
    }

    @Test
    fun `a seed of off is reported too, rather than nothing`() = runTest(UnconfinedTestDispatcher()) {
        putState(0)

        assertFalse(offlinePlus().observe().first())
    }

    @Test
    fun `each broadcast moves the reported state`() = runTest(UnconfinedTestDispatcher()) {
        putState(0)

        val seen = async { offlinePlus().observe().take(3).toList() }
        broadcast(KompaktOfflinePlusSwitch.ACTION_ON)
        broadcast(KompaktOfflinePlusSwitch.ACTION_OFF)

        assertEquals(listOf(false, true, false), seen.await())
    }

    @Test
    fun `an unrelated broadcast is not reported as a change`() = runTest(UnconfinedTestDispatcher()) {
        putState(0)

        val seen = async { offlinePlus().observe().take(2).toList() }
        broadcast("android.intent.action.AIRPLANE_MODE")
        broadcast(KompaktOfflinePlusSwitch.ACTION_ON)

        assertEquals(listOf(false, true), seen.await())
    }

}
