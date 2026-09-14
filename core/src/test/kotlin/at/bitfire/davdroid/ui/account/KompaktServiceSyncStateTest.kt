/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import at.bitfire.davdroid.db.KompaktSyncOutcome
import at.bitfire.davdroid.sync.KompaktServiceSyncOutcome
import at.bitfire.davdroid.sync.KompaktSyncFailure
import at.bitfire.davdroid.sync.KompaktSyncService
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
    private val noOutcome = Reported.Value<KompaktSyncOutcome?>(null)
    private val succeeded = Reported.Value<KompaktSyncOutcome?>(outcomeRow(succeeded = true))
    private val failed = Reported.Value<KompaktSyncOutcome?>(
        outcomeRow(succeeded = false, cause = KompaktSyncFailure.ServerProblem)
    )


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
            statusOf(syncing = syncing, lastSync = lastSync, outcome = failed)
        )
    }

    @Test
    fun aFailureKeepsTheTimeTheServiceAlreadyEarned() {
        assertEquals(
            KompaktSyncStatus.Failed("Today 11:30", KompaktSyncFailure.ServerProblem),
            statusOf(syncing = notSyncing, lastSync = lastSync, outcome = failed)
        )
    }

    @Test
    fun aFailureBeforeAnySuccessCarriesNoTime() {
        assertEquals(
            KompaktSyncStatus.Failed(null, KompaktSyncFailure.ServerProblem),
            statusOf(syncing = notSyncing, lastSync = noLastSync, outcome = failed)
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
    fun resolvingUntilTheOutcomeHasBeenRead() {
        // The outcome is a Room query, so it settles after the rest. Seeding it as "not failed" would
        // paint the success tick and then repaint to the alert icon a frame later.
        assertEquals(
            KompaktSyncStatus.Resolving,
            statusOf(syncing = notSyncing, lastSync = lastSync, outcome = Reported.Pending)
        )
    }

    @Test
    fun noOutcomeRowMeansNotFailed() {
        // Deliberately indistinguishable from a success: an account that predates the table keeps its
        // last-sync time until the next attempt records one.
        assertEquals(
            KompaktSyncStatus.Synced("Today 11:30"),
            statusOf(syncing = notSyncing, lastSync = lastSync, outcome = noOutcome)
        )
    }

    @Test
    fun aSucceededOutcomeIsNotAFailure() {
        assertEquals(
            KompaktSyncStatus.Synced("Today 11:30"),
            statusOf(syncing = notSyncing, lastSync = lastSync, outcome = succeeded)
        )
    }

    @Test
    fun anUnknownStoredCauseStillRendersAsAFailure() {
        // A cause written by an older build, or a renamed constant, must not make the failure vanish.
        val stale = Reported.Value<KompaktSyncOutcome?>(
            outcomeRow(succeeded = false).copy(cause = "SomethingElse")
        )

        assertEquals(
            KompaktSyncStatus.Failed("Today 11:30", KompaktSyncFailure.Unknown),
            statusOf(syncing = notSyncing, lastSync = lastSync, outcome = stale)
        )
    }

    @Test
    fun theStatusIgnoresTheSwitch() {
        // A switched-off service keeps the time it earned; hiding it is the cell's decision, and
        // discarding it here would lose it for the moment the user switches back on.
        val off = serviceSyncState(KompaktSyncSwitch.Off, notSyncing, lastSync, noOutcome)
        val consentMissing =
            serviceSyncState(KompaktSyncSwitch.ConsentMissing, notSyncing, lastSync, noOutcome)

        assertEquals(KompaktSyncStatus.Synced("Today 11:30"), off.status)
        assertEquals(KompaktSyncStatus.Synced("Today 11:30"), consentMissing.status)
    }


    // isLoading

    @Test
    fun loadingUntilTheSwitchHasBeenRead() {
        val state = serviceSyncState(KompaktSyncSwitch.Resolving, notSyncing, lastSync, noOutcome)

        assertTrue(state.isLoading)
    }

    @Test
    fun loadingUntilTheStatusHasBeenRead() {
        val state = serviceSyncState(KompaktSyncSwitch.On, Reported.Pending, lastSync, noOutcome)

        assertTrue(state.isLoading)
    }

    @Test
    fun settledOnceBothAreKnown() {
        val state = serviceSyncState(KompaktSyncSwitch.On, notSyncing, lastSync, noOutcome)

        assertFalse(state.isLoading)
    }

    private fun statusOf(
        syncing: Reported<Boolean> = settled,
        lastSync: Reported<String?>,
        outcome: Reported<KompaktSyncOutcome?> = noOutcome
    ) = serviceSyncState(KompaktSyncSwitch.On, syncing, lastSync, outcome).status

    private fun outcomeRow(succeeded: Boolean, cause: KompaktSyncFailure? = null) =
        KompaktSyncOutcome(
            serviceId = 7,
            dataType = KompaktSyncService.CALENDAR.dataType.name,
            at = 1_000L,
            succeeded = succeeded,
            cause = cause?.name,
            trigger = KompaktServiceSyncOutcome.TRIGGER_MANUAL,
            detail = null
        )

}
