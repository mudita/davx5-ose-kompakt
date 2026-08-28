/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.text.format.DateFormat
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import at.bitfire.davdroid.di.qualifier.IoDispatcher
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.repository.DavSyncStatsRepository
import at.bitfire.davdroid.repository.KompaktTimeFormatRepository
import at.bitfire.davdroid.servicedetection.RefreshCollectionsWorker
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.sync.KompaktInitDefaults
import at.bitfire.davdroid.sync.KompaktStorage
import at.bitfire.davdroid.ui.KompaktAuthState
import at.bitfire.davdroid.sync.SyncConditions
import at.bitfire.davdroid.sync.SyncDataType
import at.bitfire.davdroid.sync.worker.OneTimeSyncWorker
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import at.bitfire.davdroid.util.broadcastReceiverFlow
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration.Companion.milliseconds

/**
 * ViewModel for the Kompakt "Linked Account" detail screen.
 *
 * Drives the single-account screen: shows the account email, the calendar auto-sync toggle and the
 * last synchronization time, and offers actions to toggle auto-sync, sync now and unlink the account.
 *
 * It also applies the Kompakt initialization defaults exactly once per account: after collection
 * discovery completes, the primary Google calendar is selected for synchronization and automatic sync
 * is enabled by default (the user can turn it off via the toggle).
 */
@HiltViewModel(assistedFactory = KompaktLinkedAccountModel.Factory::class)
class KompaktLinkedAccountModel @AssistedInject constructor(
    @Assisted val account: Account,
    @Assisted private val initialReauth: Boolean,
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
    private val accountSettingsFactory: AccountSettings.Factory,
    private val initDefaults: KompaktInitDefaults,
    private val serviceRepository: DavServiceRepository,
    private val syncStatsRepository: DavSyncStatsRepository,
    private val timeFormatRepository: KompaktTimeFormatRepository,
    private val syncConditionsFactory: SyncConditions.Factory,
    private val syncWorkerManager: SyncWorkerManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(account: Account, initialReauth: Boolean): KompaktLinkedAccountModel
    }

    companion object {
        /** Grace period before treating "offline while syncing" as a lost connection (ignores brief blips). */
        const val OFFLINE_GRACE_MS = 2_000L
    }

    val email: String = account.name


    // auto-sync toggle (mirrored in memory; this screen is the only writer of the EVENTS interval)

    private val _autoSyncEnabled = MutableStateFlow(false)
    val autoSyncEnabled: StateFlow<Boolean> = _autoSyncEnabled.asStateFlow()


    // primary calendar + last synchronization time

    @OptIn(ExperimentalCoroutinesApi::class)
    private val primaryCollectionId: StateFlow<Long?> =
        serviceRepository.getCalDavServiceFlow(account.name).flatMapLatest { service ->
            if (service == null)
                flowOf(null)
            else
                // recompute when the refresh worker transitions (e.g. discovery just finished)
                RefreshCollectionsWorker
                    .existsFlow(context, RefreshCollectionsWorker.workerName(service.id))
                    .map { withContext(ioDispatcher) { initDefaults.findPrimaryCalendarId(account, service.id) } }
                    .distinctUntilChanged()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val dateTimeFormat = combine(
        timeFormatRepository.is24HourFormat,
        broadcastReceiverFlow(context, IntentFilter(Intent.ACTION_LOCALE_CHANGED), immediate = true)
    ) { _, _ ->
        lastSyncFormatter(DateFormat.is24HourFormat(context), ZoneId.systemDefault())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val lastSyncFormatted: StateFlow<String?> = combine(
        primaryCollectionId.flatMapLatest { id ->
            if (id == null)
                flowOf(null)
            else
                syncStatsRepository.getLastSyncedFlow(id).map { stats ->
                    stats.firstOrNull { it.dataType == SyncDataType.EVENTS.name }?.lastSynced
                }
        },
        dateTimeFormat
    ) { epochMillis, format ->
        epochMillis?.let { format.format(Instant.ofEpochMilli(it)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)


    // manual sync lifecycle (loading indicator / success snackbar / error dialog)

    enum class SyncResult { Success, Failure }

    /** WorkInfos of the calendar (EVENTS) one-time sync worker for this account. */
    private val eventsSyncWorkInfos =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(
            OneTimeSyncWorker.workerName(account, SyncDataType.EVENTS)
        )

    private fun List<WorkInfo>.isSyncActive() = any { workInfo ->
        workInfo.state == WorkInfo.State.RUNNING ||
            workInfo.state == WorkInfo.State.ENQUEUED ||
            workInfo.state == WorkInfo.State.BLOCKED
    }

    /**
     * `true` if this finished sync recorded an authentication error (HTTP 401 / token revoked).
     * Parses the [SyncResult] that BaseSyncWorker serializes into its output under the "syncresult"
     * key; on any other failure or an unparseable output we fall back to a generic failure.
     */
    private fun WorkInfo.hadAuthError(): Boolean =
        outputData.getString("syncresult")
            ?.let { Regex("numAuthExceptions=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?.let { it > 0 } == true

    /** `true` while a calendar sync is enqueued/running (drives the "Loading data…" indicator). */
    val syncing: StateFlow<Boolean> = eventsSyncWorkInfos
        .map { it.isSyncActive() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _syncResult = MutableStateFlow<SyncResult?>(null)
    /** Result of the latest *user-initiated* sync, or `null` once consumed by the UI. */
    val syncResult: StateFlow<SyncResult?> = _syncResult.asStateFlow()

    // Id of the specific EVENTS run the user triggered via syncNow() (or the pending run it coalesced into).
    // The result is read from this one run — not the aggregate unique-work list, which retains prior finished
    // runs (APPEND_OR_REPLACE) and would misreport a stale SUCCEEDED as the current result.
    private val _trackedSync = MutableStateFlow<UUID?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val trackedSyncInfo: Flow<WorkInfo?> =
        _trackedSync.flatMapLatest { id ->
            if (id == null) flowOf(null)
            else WorkManager.getInstance(context).getWorkInfoByIdFlow(id)
        }

    fun consumeSyncResult() {
        _syncResult.value = null
    }

    private val _showNoInternet = MutableStateFlow(false)
    /** `true` when the user tapped "Synchronize now" without internet; drives the "No internet" message. */
    val showNoInternet: StateFlow<Boolean> = _showNoInternet.asStateFlow()

    fun consumeNoInternet() {
        _showNoInternet.value = false
    }

    /**
     * `true` while the device is critically low on storage (system threshold; see [KompaktStorage]). Like the
     * re-auth flag this is a *persistent* condition surfaced immediately on screen entry and re-checked on
     * resume, so the "Your storage is full" message stays visible until space frees. Storage state is queried
     * live, so no extra persistence is needed.
     */
    private val _showOutOfStorage = MutableStateFlow(KompaktStorage.isStorageLow(context))
    val showOutOfStorage: StateFlow<Boolean> = _showOutOfStorage.asStateFlow()

    fun consumeOutOfStorage() {
        _showOutOfStorage.value = false
    }

    /** Re-check live free storage (call on screen entry / resume). */
    fun refreshStorageState() {
        _showOutOfStorage.value = KompaktStorage.isStorageLow(context)
    }

    // needsReauth (persistent, account-global; KEY_NEEDS_REAUTH) is written only by the sync worker
    // (HTTP 401 / clean sync) and the re-auth flow — this ViewModel only reads it, re-reading via a
    // ContentObserver for worker-driven transitions (incl. background/periodic), refreshNeedsReauth
    // (resume / re-auth result), and (re)subscription.
    private val reauthRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val authStateChanges = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        context.contentResolver.registerContentObserver(KompaktAuthState.CONTENT_URI, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    val needsReauth: StateFlow<Boolean> =
        merge(authStateChanges, reauthRefresh)
            .onStart { emit(Unit) }
            .map { withContext(ioDispatcher) { readNeedsReauth() } }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), readNeedsReauth())

    private fun readNeedsReauth(): Boolean =
        try {
            AccountManager.get(context).getUserData(account, AccountSettings.KEY_NEEDS_REAUTH) == "1"
        } catch (_: Exception) {
            false
        }

    /** Force an immediate re-read of the persisted flag (call on resume / after the re-auth flow). */
    fun refreshNeedsReauth() {
        reauthRefresh.tryEmit(Unit)
    }

    // Blank the screen while redirecting into the OAuth flow so it and the "Account not linked" dialog
    // don't flash. Released on the re-auth result — not when needsReauth clears — so a cancelled re-auth
    // returns to content instead of a permanent blank.
    enum class ReauthPhase { SHOW_CONTENT, PENDING_LAUNCH, AWAITING_RESULT }

    private val _reauthPhase = MutableStateFlow(
        if (initialReauth && needsReauth.value) ReauthPhase.PENDING_LAUNCH else ReauthPhase.SHOW_CONTENT
    )
    val reauthPhase: StateFlow<ReauthPhase> = _reauthPhase.asStateFlow()

    fun onReauthLaunchStarted() {
        if (_reauthPhase.value == ReauthPhase.PENDING_LAUNCH) {
            _reauthPhase.value = ReauthPhase.AWAITING_RESULT
        }
    }

    fun onReauthResult() {
        refreshNeedsReauth()
        _reauthPhase.value = ReauthPhase.SHOW_CONTENT
    }

    /** Emits whether a usable (validated) network connection is currently available. */
    private val networkAvailable = callbackFlow {
        val connectivityManager = context.getSystemService<ConnectivityManager>()!!
        val callback = object : ConnectivityManager.NetworkCallback() {
            val networks = hashSetOf<Network>()
            override fun onAvailable(network: Network) { networks += network; trySend(networks.isNotEmpty()) }
            override fun onLost(network: Network) { networks -= network; trySend(networks.isNotEmpty()) }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }

    init {
        // Surface the transient Success/Failure result for the exact run the user triggered via syncNow(),
        // observed by its work id. Auth failures surface via the re-auth dialog, so they show no toast here.
        viewModelScope.launch {
            trackedSyncInfo.collect { info ->
                when (info?.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        _syncResult.value = SyncResult.Success
                        _trackedSync.compareAndSet(info.id, null)
                    }
                    WorkInfo.State.FAILED -> {
                        _syncResult.value = if (info.hadAuthError()) null else SyncResult.Failure
                        _trackedSync.compareAndSet(info.id, null)
                    }
                    WorkInfo.State.CANCELLED ->
                        _trackedSync.compareAndSet(info.id, null)
                    else -> { /* null / ENQUEUED / RUNNING / BLOCKED: still in progress */ }
                }
            }
        }

        // If connectivity is lost while a user-initiated sync is in progress, the manual worker parks on
        // its network constraint (retrying) and the "Loading data…" loader would otherwise hang forever.
        // Cancel it and show the "No internet connection" message instead. Debounced to ignore brief blips.
        viewModelScope.launch {
            combine(syncing, networkAvailable) { active, online -> active && !online }
                .distinctUntilChanged()
                .collectLatest { offlineWhileSyncing ->
                    if (!offlineWhileSyncing || _trackedSync.value == null)
                        return@collectLatest
                    delay(OFFLINE_GRACE_MS.milliseconds)     // let a short network blip recover on its own
                    _trackedSync.value = null   // stop tracking first so the cancel isn't reported as a result
                    WorkManager.getInstance(context)
                        .cancelUniqueWork(OneTimeSyncWorker.workerName(account, SyncDataType.EVENTS))
                    _showNoInternet.value = true
                }
        }
    }


    // initialization defaults

    init {
        // seed the toggle state from the persisted sync interval
        viewModelScope.launch(ioDispatcher) {
            refreshAutoSyncToggle()
        }

        // Select the primary calendar (once, after discovery) so automatic sync triggers — e.g. the
        // calendar's REQUEST_SYNC on re-entry — actually sync instead of no-op'ing over an empty
        // selection. The first sync right after linking is intentionally left to the "Sync now / Later"
        // modal, so we do NOT enqueue a sync here.
        viewModelScope.launch(ioDispatcher) {
            if (initDefaults.appliedVersion(account) >= KompaktInitDefaults.DEFAULTS_VERSION)
                return@launch
            serviceRepository.getCalDavServiceFlow(account.name).filterNotNull().collectLatest { service ->
                if (initDefaults.appliedVersion(account) >= KompaktInitDefaults.DEFAULTS_VERSION)
                    return@collectLatest
                var outcome = initDefaults.maybeApply(account, service.id)
                if (outcome == KompaktInitDefaults.Outcome.NOT_READY) {
                    RefreshCollectionsWorker
                        .existsFlow(context, RefreshCollectionsWorker.workerName(service.id), WorkInfo.State.SUCCEEDED)
                        .first { succeeded -> succeeded }
                    outcome = initDefaults.maybeApply(account, service.id)
                }
                if (outcome == KompaktInitDefaults.Outcome.APPLIED)
                    refreshAutoSyncToggle()   // reflect the auto-sync default enabled on first setup
            }
        }
    }

    /** Re-reads the persisted EVENTS sync interval and updates the [autoSyncEnabled] toggle state. */
    private suspend fun refreshAutoSyncToggle() {
        try {
            val interval = accountSettingsFactory.create(account).getSyncInterval(SyncDataType.EVENTS)
            _autoSyncEnabled.value = interval == KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't read sync interval for $account", e)
        }
    }

    // actions

    fun setAutoSync(enabled: Boolean) {
        _autoSyncEnabled.value = enabled    // optimistic
        viewModelScope.launch(ioDispatcher) {
            try {
                accountSettingsFactory.create(account).setSyncInterval(
                    SyncDataType.EVENTS,
                    if (enabled) KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS else null
                )
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Couldn't set sync interval for $account", e)
            }
            // an explicit user choice counts as "defaults applied" so the init routine won't override it
            initDefaults.markApplied(account)
        }
    }

    fun syncNow() {
        viewModelScope.launch(ioDispatcher) {
            // select the primary calendar before enqueuing, else a "Sync now" right after linking syncs nothing
            if (initDefaults.ensureApplied(account) == KompaktInitDefaults.Outcome.APPLIED)
                refreshAutoSyncToggle()

            // manual syncs skip the worker's constraints, so guard here: critically low storage → show the
            // "Your storage is full" message, don't start a sync that would only fail
            if (KompaktStorage.isStorageLow(context)) {
                _showOutOfStorage.value = true
                return@launch
            }

            // no internet → show message, don't start a sync that would only fail
            val accountSettings = accountSettingsFactory.create(account)
            if (!syncConditionsFactory.create(accountSettings).internetAvailable()) {
                _showNoInternet.value = true
                return@launch
            }
            // track the specific EVENTS run this triggers, so its result is reported for this sync alone
            _trackedSync.value = syncWorkerManager.enqueueOneTimeAllAuthorities(account, manual = true)[SyncDataType.EVENTS]
        }
    }

    fun unlink() {
        viewModelScope.launch {
            accountRepository.delete(account.name)
        }
    }

}
