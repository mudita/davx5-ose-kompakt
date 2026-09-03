/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KompaktServiceSyncStateTest {

    private val settled = Reported.Value(false)
    private val notSyncing = Reported.Value(false)
    private val syncing = Reported.Value(true)
    private val noLastSync = Reported.Value<String?>(null)
    private val lastSync = Reported.Value<String?>("Today 11:30")


    // kompaktSyncSwitch

    @Test
    fun consentMissingOutranksTheStoredInterval() {
        // A scope granted during a re-auth brings no service, no discovery and no interval with it, so
        // the interval decides between On and Off and consent only vetoes.
        assertEquals(KompaktSyncSwitch.ConsentMissing, kompaktSyncSwitch(consented = false, on = true))
        assertEquals(KompaktSyncSwitch.ConsentMissing, kompaktSyncSwitch(consented = false, on = false))
    }

    @Test
    fun theDerivationNeverProducesResolving() {
        // Resolving is a seed for the flow, not an outcome of the rule: every derived position is a
        // real one, so a settled switch can never fall back to "not read yet".
        val derived = listOf(true, false).flatMap { consented ->
            listOf(true, false).map { on -> kompaktSyncSwitch(consented, on) }
        }
        assertTrue(derived.none { it == KompaktSyncSwitch.Resolving })
    }

    @Test
    fun theIntervalDecidesOnceConsentIsGranted() {
        assertEquals(KompaktSyncSwitch.On, kompaktSyncSwitch(consented = true, on = true))
        assertEquals(KompaktSyncSwitch.Off, kompaktSyncSwitch(consented = true, on = false))
    }


    // serviceSyncState: the status

    @Test
    fun resolvingUntilBothSourcesHaveReported() {
        assertEquals(
            KompaktSyncStatus.Resolving,
            statusOf(syncing = Reported.Pending, lastSync = lastSync)
        )
        assertEquals(
            KompaktSyncStatus.Resolving,
            statusOf(syncing = notSyncing, lastSync = Reported.Pending)
        )
    }

    @Test
    fun aRunInProgressOutranksAPastFailure() {
        // Otherwise the row would report the failure the retry is busy clearing.
        assertEquals(
            KompaktSyncStatus.Syncing,
            statusOf(syncing = syncing, lastSync = lastSync, failed = true)
        )
    }

    @Test
    fun aFailureKeepsTheTimeTheServiceAlreadyEarned() {
        assertEquals(
            KompaktSyncStatus.Failed("Today 11:30"),
            statusOf(syncing = notSyncing, lastSync = lastSync, failed = true)
        )
    }

    @Test
    fun aFailureBeforeAnySuccessCarriesNoTime() {
        assertEquals(
            KompaktSyncStatus.Failed(null),
            statusOf(syncing = notSyncing, lastSync = noLastSync, failed = true)
        )
    }

    @Test
    fun syncedOnceThereIsATimeAndNoFailure() {
        assertEquals(
            KompaktSyncStatus.Synced("Today 11:30"),
            statusOf(syncing = notSyncing, lastSync = lastSync)
        )
    }

    @Test
    fun neverSyncedWhenNothingHasEverSucceeded() {
        assertEquals(
            KompaktSyncStatus.NeverSynced,
            statusOf(syncing = notSyncing, lastSync = noLastSync)
        )
    }

    @Test
    fun theStatusIgnoresTheSwitch() {
        // A switched-off service keeps the time it earned; hiding it is the cell's decision, and
        // discarding it here would lose it for the moment the user switches back on.
        val off = serviceSyncState(KompaktSyncSwitch.Off, notSyncing, lastSync, failed = false)
        val consentMissing =
            serviceSyncState(KompaktSyncSwitch.ConsentMissing, notSyncing, lastSync, failed = false)

        assertEquals(KompaktSyncStatus.Synced("Today 11:30"), off.status)
        assertEquals(KompaktSyncStatus.Synced("Today 11:30"), consentMissing.status)
    }


    // isLoading

    @Test
    fun loadingUntilTheSwitchHasBeenRead() {
        val state = serviceSyncState(KompaktSyncSwitch.Resolving, notSyncing, lastSync, failed = false)

        assertTrue(state.isLoading)
    }

    @Test
    fun loadingUntilTheStatusHasBeenRead() {
        val state = serviceSyncState(KompaktSyncSwitch.On, Reported.Pending, lastSync, failed = false)

        assertTrue(state.isLoading)
    }

    @Test
    fun settledOnceBothAreKnown() {
        val state = serviceSyncState(KompaktSyncSwitch.On, notSyncing, lastSync, failed = false)

        assertFalse(state.isLoading)
    }

    private fun statusOf(
        syncing: Reported<Boolean> = settled,
        lastSync: Reported<String?>,
        failed: Boolean = false
    ) = serviceSyncState(KompaktSyncSwitch.On, syncing, lastSync, failed).status

}
