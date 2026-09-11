/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import androidx.work.WorkInfo
import androidx.work.WorkManager
import at.bitfire.davdroid.settings.KompaktAccountSettings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

sealed interface KompaktAttemptResult {
    data object BlockedNoStorage : KompaktAttemptResult
    data object BlockedNoNetwork : KompaktAttemptResult
    data object NoneEligible : KompaktAttemptResult
    data object AlreadySyncing : KompaktAttemptResult
    data object Succeeded : KompaktAttemptResult
    data object AuthFailed : KompaktAttemptResult
    data class Failed(val retry: Set<KompaktSyncService>) : KompaktAttemptResult
    data class Interrupted(val reason: KompaktInterruption) : KompaktAttemptResult
}

enum class KompaktInterruption { NoNetwork }

/**
 * Waits for the runs a screen has started and turns them into one verdict. Whether a sync may start at
 * all is [KompaktStartSyncUseCase]'s answer, mapped here; what this owns is the ids, the watch that
 * gives up when the network goes, and the outcome.
 *
 * The unit is the set of runs this component has started, not a single call. A second [run] while an
 * earlier one is waiting is never refused: it contributes its runs to the same set, both callers await
 * the same drain, and both receive the same verdict.
 *
 * Deliberately **not** [javax.inject.Singleton]: the sharing that set needs is between concurrent calls
 * from one screen — a Synchronize tap and a Try again from its dialog — and the screen's own ViewModel
 * already scopes that. Process-wide, the set would outlive the screen, and an id left in it by an
 * abandoned attempt would go on feeding the verdict of attempts made minutes later.
 *
 * **Must not be called on the main thread**, because [KompaktStartSyncUseCase] must not be.
 */
class KompaktSyncAttempt @Inject constructor(
    private val startSync: KompaktStartSyncUseCase,
    private val outcomes: KompaktServiceSyncOutcome,
    private val accountSettings: KompaktAccountSettings,
    private val network: KompaktNetworkAvailability,
    private val syncWork: KompaktSyncWork,
    private val workManager: WorkManager
) {

    // Only the ids: whether one is still in flight is read from WorkManager, never bookkept here.
    private val started = MutableStateFlow<Map<KompaktSyncService, UUID>>(emptyMap())

    suspend fun run(
        account: Account,
        services: Collection<KompaktSyncService>
    ): KompaktAttemptResult {
        when (val start = startSync(account, services, awaitDiscovery = true)) {
            KompaktSyncStartResult.NoStorage -> return KompaktAttemptResult.BlockedNoStorage
            KompaktSyncStartResult.NoNetwork -> return KompaktAttemptResult.BlockedNoNetwork
            KompaktSyncStartResult.NoneEligible -> return KompaktAttemptResult.NoneEligible
            // Nothing of ours to add — but if another call is mid-drain we report its verdict rather
            // than refusing.
            KompaktSyncStartResult.AlreadySyncing -> Unit
            is KompaktSyncStartResult.Started -> started.update { it + start.runs }
        }

        if (started.value.isEmpty()) {
            return KompaktAttemptResult.AlreadySyncing
        }

        var interrupted = false
        val endedAs = mutableMapOf<KompaktSyncService, WorkInfo.State?>()
        try {
            coroutineScope {
                val watch = launch {
                    network.observe()
                        .distinctUntilChanged()
                        .collectLatest { online ->
                            if (!online) {
                                // collectLatest, so coming back online inside the grace cancels this and a
                                // short blip costs nothing.
                                delay(OFFLINE_GRACE_MS.milliseconds)
                                interrupted = true
                                val inFlight = started.getAndClear()
                                // One-time work names only: cancelling periodic work would stop automatic
                                // sync for good.
                                for (service in inFlight.keys) {
                                    syncWork.cancel(account, service)
                                }
                            }
                        }
                }

                // Released as each one finishes, so what is left is only ever still in flight -- and a
                // service a retry re-adds is picked up again rather than skipped as already answered. Only
                // if the id is still ours: a newer one belongs to whoever added it.
                while (true) {
                    val (service, id) = started.value.entries.firstOrNull() ?: break
                    endedAs[service] = awaitFinished(id)
                    started.update { if (it[service] == id) it - service else it }
                }

                watch.cancel()
            }
        } finally {
            // Nothing in flight means nothing tracked, whatever the loop above managed to do -- so an
            // abandoned attempt cannot leave an id behind for a later verdict to read.
            started.value = emptyMap()
        }

        if (interrupted) {
            return KompaktAttemptResult.Interrupted(KompaktInterruption.NoNetwork)
        }
        if (accountSettings.getReauthNeeded(account)) {
            return KompaktAttemptResult.AuthFailed
        }

        // A cancelled run wrote nothing -- switching a service off cancels it, and the isStopped guard
        // in the worker skips the write -- so whatever row it left is from an earlier attempt and must
        // not be reported as this one's result.
        val failed = endedAs.keys
            .filter { service -> endedAs[service] != WorkInfo.State.CANCELLED }
            .filter { service -> outcomes.get(account, service)?.succeeded == false }
            .toSet()
        return if (failed.isEmpty()) {
            KompaktAttemptResult.Succeeded
        } else {
            KompaktAttemptResult.Failed(failed)
        }
    }

    // Null means WorkManager no longer knows the id — pruned, or replaced by APPEND_OR_REPLACE — which
    // is as finished as a terminal state. Result.retry() returns a run to ENQUEUED, so a retry correctly
    // does not count as finished.
    private suspend fun awaitFinished(id: UUID): WorkInfo.State? =
        workManager.getWorkInfoByIdFlow(id)
            .first { info -> info == null || info.state.isFinished }
            ?.state

    private fun MutableStateFlow<Map<KompaktSyncService, UUID>>.getAndClear(): Map<KompaktSyncService, UUID> {
        var taken: Map<KompaktSyncService, UUID> = emptyMap()
        update { current ->
            taken = current
            emptyMap()
        }
        return taken
    }

    companion object {
        const val OFFLINE_GRACE_MS = 2_000L
    }

}
