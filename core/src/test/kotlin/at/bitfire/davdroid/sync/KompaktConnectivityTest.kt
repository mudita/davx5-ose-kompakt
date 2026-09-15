/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class KompaktConnectivityTest {

    // Replay, not StateFlow: a fake that deduped would hide whether observe() does.
    private val offlinePlusOn = MutableSharedFlow<Boolean>(replay = 1)
    private val online = MutableSharedFlow<Boolean>(replay = 1)

    private lateinit var connectivity: KompaktConnectivity

    @Before
    fun setUp() {
        val offlinePlus = mockk<KompaktOfflinePlus>()
        every { offlinePlus.observe() } returns offlinePlusOn
        val network = mockk<KompaktNetworkAvailability>()
        every { network.observe() } returns online

        connectivity = KompaktConnectivity(offlinePlus, network)
    }

    /** Collects for the life of [block] and asserts what it saw, in order. */
    private fun expectCauses(
        vararg expected: KompaktOfflineCause?,
        block: suspend () -> Unit
    ) = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<KompaktOfflineCause?>()
        // Unconfined, so backgroundScope actually runs: a standard dispatcher would collect nothing.
        val job = backgroundScope.launch { connectivity.observe().toList(seen) }
        block()
        job.cancel()
        assertEquals(expected.toList(), seen)
    }

    @Test
    fun `a usable connection is no cause at all`() {
        expectCauses(null) {
            offlinePlusOn.emit(false)
            online.emit(true)
        }
    }

    @Test
    fun `a connection that went by itself is the symptom`() {
        expectCauses(null, KompaktOfflineCause.NoNetwork) {
            offlinePlusOn.emit(false)
            online.emit(true)
            online.emit(false)
        }
    }

    @Test
    fun `the switch outranks the network it took down with it`() {
        expectCauses(KompaktOfflineCause.OfflinePlus) {
            offlinePlusOn.emit(true)
            online.emit(false)
        }
    }

    // The radios go a moment after Offline+ turns on, so both orders happen and must answer the same.
    @Test
    fun `the switch outranks the network whichever lands first`() {
        expectCauses(null, KompaktOfflineCause.NoNetwork, KompaktOfflineCause.OfflinePlus) {
            offlinePlusOn.emit(false)
            online.emit(true)
            online.emit(false)
            offlinePlusOn.emit(true)
        }
    }

    @Test
    fun `lifting the switch with the radios still down leaves the symptom`() {
        expectCauses(KompaktOfflineCause.OfflinePlus, KompaktOfflineCause.NoNetwork) {
            offlinePlusOn.emit(true)
            online.emit(false)
            offlinePlusOn.emit(false)
        }
    }

    @Test
    fun `an unchanged answer is not repeated`() {
        expectCauses(KompaktOfflineCause.NoNetwork) {
            offlinePlusOn.emit(false)
            online.emit(false)
            offlinePlusOn.emit(false)
            online.emit(false)
        }
    }

}
