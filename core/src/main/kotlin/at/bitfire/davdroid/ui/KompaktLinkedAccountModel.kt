/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.accounts.Account
import android.accounts.AccountManager
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
import at.bitfire.davdroid.BuildConfig
import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.di.qualifier.IoDispatcher
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.repository.DavCollectionRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.repository.DavSyncStatsRepository
import at.bitfire.davdroid.repository.KompaktTimeFormatRepository
import at.bitfire.davdroid.servicedetection.RefreshCollectionsWorker
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.sync.KompaktStorage
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
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
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
    private val accountSettingsFactory: AccountSettings.Factory,
    private val collectionRepository: DavCollectionRepository,
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
        fun create(account: Account): KompaktLinkedAccountModel
    }

    companion object {
        /**
         * Sync interval (seconds) that the "Auto synchronization" toggle enables.
         *
         * In debug builds this is shortened to 15 minutes to make testing easier; release builds
         * use the production default of once a day (24 h).
         */
        val AUTO_SYNC_INTERVAL_SECONDS = if (BuildConfig.DEBUG) 15 * 60L else 24 * 60 * 60L

        /** AccountManager userData key holding the Kompakt init-defaults version applied for this account */
        const val KEY_DEFAULTS_APPLIED = "kompakt_defaults_applied"

        /**
         * Current version of the Kompakt init defaults. Bump to re-run the (idempotent) primary-calendar
         * selection once on existing accounts — e.g. to correct accounts where an older version locked in
         * a wrong calendar. Auto-sync default is only ever applied on the very first setup (version 0).
         */
        const val DEFAULTS_VERSION = 2

        /** Max time a user-initiated "Sync now" waits for collection discovery before giving up. */
        const val DISCOVERY_WAIT_MS = 30_000L

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
                    .map { withContext(ioDispatcher) { findPrimaryCalendarId(service.id) } }
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

    /** Only watch for a result after the user explicitly triggered a sync (not background syncs). */
    private var armed = false

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

    /**
     * `true` when the account's OAuth token is invalid and needs re-authorization. This is a
     * *persistent* condition (stored in [AccountSettings.KEY_NEEDS_REAUTH]) so the "Account not linked" dialog
     * keeps showing across screen re-entries, aborted re-auth attempts and app restarts, until
     * re-auth (or a successful sync) clears it.
     */
    private val _needsReauth = MutableStateFlow(readNeedsReauth())
    val needsReauth: StateFlow<Boolean> = _needsReauth.asStateFlow()

    private fun readNeedsReauth(): Boolean =
        try {
            AccountManager.get(context).getUserData(account, AccountSettings.KEY_NEEDS_REAUTH) == "1"
        } catch (_: Exception) {
            false
        }

    private fun setNeedsReauthState(value: Boolean) {
        _needsReauth.value = value
        try {
            AccountManager.get(context).setUserData(account, AccountSettings.KEY_NEEDS_REAUTH, if (value) "1" else null)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't persist needsReauth for $account", e)
        }
    }

    /** Re-read the persisted re-auth flag (call after returning from the re-auth flow). */
    fun reloadNeedsReauth() {
        _needsReauth.value = readNeedsReauth()
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
        // Watch the EVENTS worker lifecycle. The persistent "needs re-auth" flag is updated on every
        // observed sync (so background/auto syncs count too); the transient Success/Failure result is
        // only surfaced for syncs the user started via syncNow() (armed). The flow also contains the
        // previous finished sync's WorkInfo, so we only react after seeing the sync go active first.
        viewModelScope.launch {
            var sawActive = false
            eventsSyncWorkInfos.collect { infos ->
                if (infos.isSyncActive()) {
                    sawActive = true
                    return@collect
                }
                if (!sawActive)
                    return@collect
                sawActive = false

                val failed = infos.filter { it.state == WorkInfo.State.FAILED }
                val authError = failed.any { it.hadAuthError() }
                val succeeded = infos.any { it.state == WorkInfo.State.SUCCEEDED }

                // persistent: token bad / good again (a canceled worker is neither)
                if (authError)
                    setNeedsReauthState(true)
                else if (succeeded)
                    setNeedsReauthState(false)

                // transient user-facing result (auth errors are shown via their own message)
                if (armed) {
                    _syncResult.value = when {
                        succeeded -> SyncResult.Success
                        authError -> null               // → "Account not linked"
                        failed.isNotEmpty() -> SyncResult.Failure
                        else -> null    // e.g. cancelled because connectivity was lost mid-sync
                    }
                    armed = false
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
                    if (!offlineWhileSyncing || !armed)
                        return@collectLatest
                    delay(OFFLINE_GRACE_MS.milliseconds)     // let a short network blip recover on its own
                    armed = false               // disarm first so the cancel isn't reported as a result
                    WorkManager.getInstance(context)
                        .cancelUniqueWork(OneTimeSyncWorker.workerName(account, SyncDataType.EVENTS))
                    _showNoInternet.value = true
                }
        }
    }


    // initialization defaults

    private val defaultsMutex = Mutex()

    init {
        // seed the toggle state from the persisted sync interval
        viewModelScope.launch(ioDispatcher) {
            try {
                val interval = accountSettingsFactory.create(account).getSyncInterval(SyncDataType.EVENTS)
                _autoSyncEnabled.value = interval == AUTO_SYNC_INTERVAL_SECONDS
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Couldn't read sync interval for $account", e)
            }
        }

        // apply Kompakt init defaults (once per defaults version), after collection discovery
        viewModelScope.launch(ioDispatcher) {
            if (appliedDefaultsVersion() >= DEFAULTS_VERSION)
                return@launch
            serviceRepository.getCalDavServiceFlow(account.name).filterNotNull().collectLatest { service ->
                if (appliedDefaultsVersion() >= DEFAULTS_VERSION)
                    return@collectLatest
                // collections may already be present (e.g. relaunch right after login)
                if (maybeApplyDefaults(service.id))
                    return@collectLatest
                // otherwise wait until the refresh worker succeeds, then apply
                RefreshCollectionsWorker
                    .existsFlow(context, RefreshCollectionsWorker.workerName(service.id), WorkInfo.State.SUCCEEDED)
                    .first { succeeded -> succeeded }
                maybeApplyDefaults(service.id)
            }
        }
    }

    /** Version of the Kompakt init defaults already applied to this account (0 = none / first setup). */
    private fun appliedDefaultsVersion(): Int =
        try {
            AccountManager.get(context).getUserData(account, KEY_DEFAULTS_APPLIED)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            // account may no longer exist → treat as up-to-date so we don't keep trying
            DEFAULTS_VERSION
        }

    private fun markDefaultsApplied() {
        try {
            AccountManager.get(context).setUserData(account, KEY_DEFAULTS_APPLIED, DEFAULTS_VERSION.toString())
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't mark Kompakt defaults applied for $account", e)
        }
    }

    /**
     * Applies the Kompakt initialization defaults, after collection discovery: selects **only** the
     * user's primary Google calendar for synchronization. On the very first setup it also enables
     * automatic sync by default (later runs — e.g. a defaults-version bump that re-corrects the
     * selection — do not touch the auto-sync setting, to respect the user's toggle choice).
     *
     * Runs at most once per [DEFAULTS_VERSION]. Returns `true` if defaults are applied (or already
     * up to date), `false` if the primary calendar can't be identified yet and the caller should retry.
     */
    private suspend fun maybeApplyDefaults(serviceId: Long): Boolean = defaultsMutex.withLock {
        val version = appliedDefaultsVersion()
        if (version >= DEFAULTS_VERSION)
            return true
        val calendars = collectionRepository.getByService(serviceId)
            .filter { it.type == Collection.TYPE_CALENDAR }
        if (calendars.isEmpty())
            return false
        // Don't guess: if the primary isn't identifiable yet (e.g. not discovered), wait and retry
        // rather than locking in a wrong calendar.
        val primaryId = findPrimaryCalendarId(calendars) ?: return false

        // select only the primary calendar for sync
        for (calendar in calendars) {
            val shouldSync = calendar.id == primaryId
            if (calendar.sync != shouldSync)
                collectionRepository.setSync(calendar.id, shouldSync)
        }

        // enable automatic sync by default — only on the very first setup
        if (version == 0) {
            try {
                accountSettingsFactory.create(account).setSyncInterval(SyncDataType.EVENTS, AUTO_SYNC_INTERVAL_SECONDS)
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Couldn't set automatic sync for $account", e)
            }
            _autoSyncEnabled.value = true
        }
        markDefaultsApplied()
        true
    }

    /**
     * Identifies the user's **primary** Google calendar among the discovered calendars. Google's primary
     * calendar is the one whose CalDAV collection id is the account email, so its URL contains the email
     * (the "@" is usually `%40`-encoded); its display name also equals the email. Returns `null` if the
     * primary can't be confidently identified — we deliberately do **not** fall back to an arbitrary
     * calendar, to avoid selecting a secondary/added one (e.g. a test calendar).
     */
    /** Loads the account's calendar collections and identifies the primary one. */
    private suspend fun findPrimaryCalendarId(serviceId: Long): Long? =
        findPrimaryCalendarId(
            collectionRepository.getByService(serviceId).filter { it.type == Collection.TYPE_CALENDAR }
        )

    private fun findPrimaryCalendarId(calendars: List<Collection>): Long? {
        val email = account.name.lowercase()
        val emailEncoded = email.replace("@", "%40")

        calendars.firstOrNull { collection ->
            val url = collection.url.toString().lowercase()
            url.contains(email) || url.contains(emailEncoded)
        }?.let { return it.id }

        calendars.firstOrNull { collection ->
            collection.displayName?.lowercase() == email
        }?.let { return it.id }

        return null
    }


    // actions

    fun setAutoSync(enabled: Boolean) {
        _autoSyncEnabled.value = enabled    // optimistic
        viewModelScope.launch(ioDispatcher) {
            try {
                accountSettingsFactory.create(account).setSyncInterval(
                    SyncDataType.EVENTS,
                    if (enabled) AUTO_SYNC_INTERVAL_SECONDS else null
                )
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Couldn't set sync interval for $account", e)
            }
            // an explicit user choice counts as "defaults applied" so the init routine won't override it
            markDefaultsApplied()
        }
    }

    fun syncNow() {
        viewModelScope.launch(ioDispatcher) {
            // Make sure the primary calendar is selected for sync before enqueuing. The init defaults
            // do this asynchronously, but right after linking the user may tap "Sync now" before that
            // ran — and a sync only processes collections with sync=true, so it would sync nothing.
            ensureDefaultsApplied()

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
            armed = true            // watch the resulting sync for success/failure feedback
            syncWorkerManager.enqueueOneTimeAllAuthorities(account, manual = true)
        }
    }

    /**
     * Ensures the primary calendar is selected for sync before a user-initiated sync. Right after
     * linking the calendars may not be discovered yet, so if the first attempt can't identify the
     * primary, wait (bounded) for collection discovery to finish and try again — otherwise a quick
     * "Sync now" tap would enqueue a sync with nothing selected and sync nothing.
     */
    private suspend fun ensureDefaultsApplied() {
        if (appliedDefaultsVersion() >= DEFAULTS_VERSION)
            return
        val service = serviceRepository.getByAccountAndType(account.name, Service.TYPE_CALDAV) ?: return
        if (maybeApplyDefaults(service.id))
            return
        // primary not discovered yet → wait for the refresh worker to finish, then apply
        withTimeoutOrNull(DISCOVERY_WAIT_MS.milliseconds) {
            RefreshCollectionsWorker
                .existsFlow(context, RefreshCollectionsWorker.workerName(service.id), WorkInfo.State.SUCCEEDED)
                .first { succeeded -> succeeded }
        }
        maybeApplyDefaults(service.id)
    }

    fun unlink() {
        viewModelScope.launch {
            accountRepository.delete(account.name)
        }
    }

}
