/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import at.bitfire.davdroid.sync.KompaktConnectivity
import at.bitfire.davdroid.sync.KompaktOfflineCause
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class KompaktLinkAccountModelTest {

    private val causes = MutableSharedFlow<KompaktOfflineCause?>(replay = 1)

    // viewModelScope is Dispatchers.Main, which a JVM test has to supply before stateIn collects.
    @Before
    fun setUpMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDownMainDispatcher() = Dispatchers.resetMain()

    private fun model(): KompaktLinkAccountModel {
        val connectivity = mockk<KompaktConnectivity>()
        every { connectivity.observe() } returns causes
        return KompaktLinkAccountModel(connectivity)
    }

    @Test
    fun `linking is not blocked before the answer arrives`() = runTest(UnconfinedTestDispatcher()) {
        // The seed matters: blocking by default would refuse the tap that opens the flow, and the user
        // would be told they are offline while they are not.
        assertNull(model().offlineCause.value)
    }

    @Test
    fun `the switch is what blocks linking`() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        backgroundScope.launch { model.offlineCause.collect {} }

        causes.emit(KompaktOfflineCause.OfflinePlus)

        assertEquals(KompaktOfflineCause.OfflinePlus, model.offlineCause.value)
    }

    @Test
    fun `a connection that recovers unblocks linking again`() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        backgroundScope.launch { model.offlineCause.collect {} }

        causes.emit(KompaktOfflineCause.NoNetwork)
        assertEquals(KompaktOfflineCause.NoNetwork, model.offlineCause.value)

        causes.emit(null)
        assertNull(model.offlineCause.value)
    }

}
