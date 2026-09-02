/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import at.bitfire.davdroid.repository.KompaktTimeFormatRepository
import at.bitfire.davdroid.servicedetection.RefreshCollectionsWorker
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.sync.KompaktInitDefaults
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.sync.KompaktStartSyncUseCase
import at.bitfire.davdroid.sync.KompaktSyncWork
import at.bitfire.davdroid.sync.KompaktStorage
import at.bitfire.davdroid.sync.SyncConditions
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
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
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
    private val kompaktAccountSettings: KompaktAccountSettings,
    private val accountRepository: AccountRepository,
    private val accountSettingsFactory: AccountSettings.Factory,
    private val initDefaults: KompaktInitDefaults,
    private val serviceRepository: DavServiceRepository,
    private val timeFormatRepository: KompaktTimeFormatRepository,
    private val syncConditionsFactory: SyncConditions.Factory,
    private val switches: KompaktServiceSwitch,
    private val lastSyncSource: KompaktServiceLastSync,
    private val startSyncUseCase: KompaktStartSyncUseCase,
    private val accountProgress: AccountProgressUseCase,
    private val syncWork: KompaktSyncWork,
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

    private val email: String = account.name


    // per-service switch

    // Seeded synchronously so the first frame is right and the switch does not flicker on entry.
    private fun switchOf(service: KompaktSyncService): StateFlow<KompaktSyncSwitch> =
        switches.observe(account, service)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), readSwitch(service))

    private fun readSwitch(service: KompaktSyncService): KompaktSyncSwitch =
        try {
            switches.read(account, service)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't read the sync switch for $account", e)
            KompaktSyncSwitch.Off
        }


    // last synchronization time

    private val dateTimeFormat = combine(
        timeFormatRepository.is24HourFormat,
        broadcastReceiverFlow(context, IntentFilter(Intent.ACTION_LOCALE_CHANGED), immediate = true)
    ) { _, _ ->
        lastSyncFormatter(DateFormat.is24HourFormat(context), ZoneId.systemDefault())
    }

    private fun lastSyncOf(service: KompaktSyncService): Flow<Reported<String?>> =
        combine(lastSyncSource.observe(account, service), dateTimeFormat) { reported, format ->
            when (reported) {
                Reported.Pending -> Reported.Pending
                is Reported.Value ->
                    Reported.Value(reported.value?.let { format.format(Instant.ofEpochMilli(it)) })
            }
        }


    // manual sync lifecycle (loading indicator / success snackbar / error dialog)

    /**
     * `true` if this finished sync recorded an authentication error (HTTP 401 / token revoked).
     * Parses the [SyncResult] that BaseSyncWorker serializes into its output under the "syncresult"
     * key; on any other failure or an unparseable output we fall back to a generic failure.
     */
    private fun WorkInfo.hadAuthError(): Boolean =
        outputData.getString("syncresult")
            ?.let { Regex("numAuthExceptions=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?.let { it > 0 } == true

    // Reported at the source: the first query is asynchronous, so "not yet known" must be
    // distinguishable from "not syncing" — wrapping a fabricated `false` afterwards would not be.
    // Pending counts as syncing, or a just-tapped sync shows nothing until its worker starts.
    private fun syncingOf(service: KompaktSyncService): Flow<Reported<Boolean>> =
        accountProgress(
            account,
            serviceRepository.getServiceFlow(account.name, service.serviceType),
            listOf(service.dataType)
        )
            .map<AccountProgress, Reported<Boolean>> { Reported.Value(it != AccountProgress.Idle) }
            .distinctUntilChanged()

    private val _syncFailed = MutableStateFlow(false)

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

    private val _showNoInternet = MutableStateFlow(false)

    /**
     * `true` while the device is critically low on storage (system threshold; see [KompaktStorage]). Like the
     * re-auth flag this is a *persistent* condition surfaced immediately on screen entry and re-checked on
     * resume, so the "Your storage is full" message stays visible until space frees. Storage state is queried
     * live, so no extra persistence is needed.
     */
    private val _showOutOfStorage = MutableStateFlow(KompaktStorage.isStorageLow(context))

    /** Re-check live free storage (call on screen entry / resume). */
    fun refreshStorageState() {
        _showOutOfStorage.value = KompaktStorage.isStorageLow(context)
    }

    // needsReauth (persistent, account-global; KEY_NEEDS_REAUTH) is written only by the sync worker
    // (HTTP 401 / clean sync) and the re-auth flow, both through KompaktAccountSettings — so this
    // follows the key, including for background and periodic syncs.
    private val needsReauth: StateFlow<Boolean> =
        kompaktAccountSettings.observeReauthNeeded(account)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), readNeedsReauth())

    private fun readNeedsReauth(): Boolean =
        try {
            kompaktAccountSettings.getReauthNeeded(account)
        } catch (_: Exception) {
            false
        }

    // Blank the screen while redirecting into the OAuth flow so it and the "Account not linked" dialog
    // don't flash. Released on the re-auth result — not when needsReauth clears — so a cancelled re-auth
    // returns to content instead of a permanent blank.
    enum class ReauthPhase { SHOW_CONTENT, PENDING_LAUNCH, AWAITING_RESULT }

    private val _reauthPhase = MutableStateFlow(
        if (initialReauth && needsReauth.value) ReauthPhase.PENDING_LAUNCH else ReauthPhase.SHOW_CONTENT
    )

    fun onReauthLaunchStarted() {
        if (_reauthPhase.value == ReauthPhase.PENDING_LAUNCH) {
            _reauthPhase.value = ReauthPhase.AWAITING_RESULT
        }
    }

    fun onReauthResult() {
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
                        _syncFailed.value = false
                        _trackedSync.compareAndSet(info.id, null)
                    }
                    WorkInfo.State.FAILED -> {
                        _syncFailed.value = !info.hadAuthError()
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
            combine(_trackedSync, networkAvailable) { tracked, online -> tracked != null && !online }
                .distinctUntilChanged()
                .collectLatest { offlineWhileSyncing ->
                    if (!offlineWhileSyncing)
                        return@collectLatest
                    delay(OFFLINE_GRACE_MS.milliseconds)     // let a short network blip recover on its own
                    _trackedSync.value = null   // stop tracking first so the cancel isn't reported as a result
                    syncWork.cancel(account, KompaktSyncService.CALENDAR)
                    _showNoInternet.value = true
                }
        }
    }


    // the single state channel

    // Calendar only: attributing a failure to one service needs a persisted per-service result, which
    // non-terminal periodic work does not retain.
    private fun failedOf(service: KompaktSyncService): Flow<Boolean> = when (service) {
        KompaktSyncService.CALENDAR -> _syncFailed
        KompaktSyncService.CONTACTS -> flowOf(false)
    }

    private val serviceStates: Map<KompaktSyncService, Flow<KompaktServiceSyncState>> =
        KompaktSyncService.entries.associateWith { service ->
            combine(
                switchOf(service),
                syncingOf(service),
                lastSyncOf(service),
                failedOf(service),
                ::serviceSyncState
            )
        }

    private val dialog: Flow<KompaktLinkedAccountDialog?> = combine(
        needsReauth,
        _showOutOfStorage,
        _showNoInternet,
        _syncFailed,
        ::linkedAccountDialog
    )

    val state: StateFlow<KompaktLinkedAccountState> = combine(
        serviceStates.getValue(KompaktSyncService.CALENDAR),
        serviceStates.getValue(KompaktSyncService.CONTACTS),
        dialog,
        _reauthPhase
    ) { calendar, contacts, dialog, phase ->
        KompaktLinkedAccountState(
            email = email,
            calendar = calendar,
            contacts = contacts,
            dialog = dialog,
            reauthPhase = phase
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState())

    // Every input must carry an immediate first value or combine emits nothing at all, so this seed is
    // read synchronously rather than defaulted.
    private fun initialState() = KompaktLinkedAccountState(
        email = email,
        calendar = KompaktServiceSyncState(readSwitch(KompaktSyncService.CALENDAR), KompaktSyncStatus.Resolving),
        contacts = KompaktServiceSyncState(readSwitch(KompaktSyncService.CONTACTS), KompaktSyncStatus.Resolving),
        dialog = linkedAccountDialog(
            authError = readNeedsReauth(),
            outOfStorage = KompaktStorage.isStorageLow(context),
            noInternet = false,
            syncFailed = false
        ),
        reauthPhase = _reauthPhase.value
    )


    // initialization defaults

    init {
        // Apply each service's defaults (once, after discovery) so automatic sync triggers — e.g. the
        // calendar's REQUEST_SYNC on re-entry — actually sync instead of no-op'ing over an empty
        // selection. The first sync right after linking is intentionally left to the "Sync now / Later"
        // modal, so we do NOT enqueue a sync here.
        for (service in KompaktSyncService.entries)
            viewModelScope.launch(ioDispatcher) {
                if (initDefaults.appliedVersion(account, service) >= KompaktInitDefaults.DEFAULTS_VERSION)
                    return@launch
                serviceRepository.getServiceFlow(account.name, service.serviceType).filterNotNull().collectLatest { serviceRow ->
                    if (initDefaults.appliedVersion(account, service) >= KompaktInitDefaults.DEFAULTS_VERSION)
                        return@collectLatest
                    val outcome = initDefaults.maybeApply(account, service, serviceRow.id)
                    if (outcome == KompaktInitDefaults.Outcome.NOT_READY) {
                        RefreshCollectionsWorker
                            .existsFlow(context, RefreshCollectionsWorker.workerName(serviceRow.id), WorkInfo.State.SUCCEEDED)
                            .first { succeeded -> succeeded }
                        initDefaults.maybeApply(account, service, serviceRow.id)
                    }
                }
            }
    }

    // actions

    fun setServiceSync(service: KompaktSyncService, enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            // Stop tracking first, so cancelling the run is not reported as its failure.
            if (!enabled)
                _trackedSync.value = null
            try {
                switches.setEnabled(account, service, enabled)
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Couldn't set the sync switch for $account", e)
                return@launch
            }
            if (enabled)
                startSync(listOf(service))
        }
    }

    // The auth error is deliberately not cleared: KEY_NEEDS_REAUTH is cleared only by a successful
    // re-auth, which is why its sheet has every dismiss path locked.
    fun consumeDialog() {
        _showNoInternet.value = false
        _syncFailed.value = false
        _showOutOfStorage.value = false
    }

    fun syncNow() {
        viewModelScope.launch(ioDispatcher) { startSync(KompaktSyncService.entries) }
    }

    private suspend fun startSync(requested: Collection<KompaktSyncService>) {
        // manual syncs skip the worker's constraints, so guard here: critically low storage → show the
        // "Your storage is full" message, don't start a sync that would only fail
        if (KompaktStorage.isStorageLow(context)) {
            _showOutOfStorage.value = true
            return
        }

        // no internet → show message, don't start a sync that would only fail
        val accountSettings = accountSettingsFactory.create(account)
        if (!syncConditionsFactory.create(accountSettings).internetAvailable()) {
            _showNoInternet.value = true
            return
        }
        // track the specific EVENTS run this triggers, so its result is reported for this sync alone
        _trackedSync.value = startSyncUseCase(account, requested)[KompaktSyncService.CALENDAR]
    }

    fun unlink() {
        viewModelScope.launch {
            accountRepository.delete(account.name)
        }
    }

}
