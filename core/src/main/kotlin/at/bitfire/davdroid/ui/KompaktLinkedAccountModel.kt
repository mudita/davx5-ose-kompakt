/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.di.qualifier.IoDispatcher
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.repository.DavCollectionRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.repository.DavSyncStatsRepository
import at.bitfire.davdroid.servicedetection.RefreshCollectionsWorker
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.sync.SyncConditions
import at.bitfire.davdroid.sync.SyncDataType
import at.bitfire.davdroid.sync.worker.OneTimeSyncWorker
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.logging.Level
import java.util.logging.Logger

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
        /** sync interval (seconds) that the "Auto synchronization" toggle enables: once a day */
        const val AUTO_SYNC_INTERVAL_SECONDS = 60L

        /** AccountManager userData flag marking that Kompakt init defaults have been applied for this account */
        const val KEY_DEFAULTS_APPLIED = "kompakt_defaults_applied"
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val lastSyncFormatted: StateFlow<String?> = primaryCollectionId.flatMapLatest { id ->
        if (id == null)
            flowOf(null)
        else
            syncStatsRepository.getLastSyncedFlow(id).map { stats ->
                stats.firstOrNull { it.dataType == SyncDataType.EVENTS.name }
                    ?.lastSynced
                    ?.let(::formatLastSync)
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val dateTimeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm").withZone(ZoneId.systemDefault())

    private fun formatLastSync(epochMillis: Long): String =
        dateTimeFormat.format(Instant.ofEpochMilli(epochMillis))


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
                val success = failed.isEmpty()

                // persistent: token bad / good again
                if (authError)
                    setNeedsReauthState(true)
                else if (success)
                    setNeedsReauthState(false)

                // transient user-facing result (auth errors are shown via the persistent needsReauth dialog)
                if (armed) {
                    _syncResult.value = when {
                        success -> SyncResult.Success
                        authError -> null
                        else -> SyncResult.Failure
                    }
                    armed = false
                }
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

        // apply Kompakt init defaults once, after collection discovery has produced calendars
        viewModelScope.launch(ioDispatcher) {
            if (defaultsApplied())
                return@launch
            serviceRepository.getCalDavServiceFlow(account.name).filterNotNull().collectLatest { service ->
                if (defaultsApplied())
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

    private fun defaultsApplied(): Boolean =
        try {
            AccountManager.get(context).getUserData(account, KEY_DEFAULTS_APPLIED) == "1"
        } catch (_: Exception) {
            // account may no longer exist
            true
        }

    private fun markDefaultsApplied() {
        try {
            AccountManager.get(context).setUserData(account, KEY_DEFAULTS_APPLIED, "1")
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't mark Kompakt defaults applied for $account", e)
        }
    }

    /**
     * Applies the Kompakt initialization defaults **once** per account, after collection discovery:
     * selects the user's primary Google calendar for synchronization (and makes sure no other calendar
     * is selected, so the initial state is exactly that single calendar) and enables automatic sync.
     * The selection is not touched again afterwards.
     *
     * Returns `true` if the defaults are now applied (or were already), `false` if there are no
     * calendars yet and the caller should retry later.
     */
    private suspend fun maybeApplyDefaults(serviceId: Long): Boolean = defaultsMutex.withLock {
        if (defaultsApplied())
            return true
        val calendars = collectionRepository.getByService(serviceId)
            .filter { it.type == Collection.TYPE_CALENDAR }
        if (calendars.isEmpty())
            return false
        val primaryId = findPrimaryCalendarId(serviceId) ?: return false

        // select only the primary calendar for sync
        for (calendar in calendars) {
            val shouldSync = calendar.id == primaryId
            if (calendar.sync != shouldSync)
                collectionRepository.setSync(calendar.id, shouldSync)
        }

        // enable automatic sync by default
        try {
            accountSettingsFactory.create(account).setSyncInterval(SyncDataType.EVENTS, AUTO_SYNC_INTERVAL_SECONDS)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't set automatic sync for $account", e)
        }
        _autoSyncEnabled.value = true
        markDefaultsApplied()
        true
    }

    /**
     * Finds the user's primary Google calendar among the discovered calendar collections.
     * Google's primary calendar URL contains the account email (the "@" may be `%40`-encoded).
     * Falls back to a calendar whose display name matches the email, then to the first event-capable
     * calendar.
     */
    private suspend fun findPrimaryCalendarId(serviceId: Long): Long? {
        val calendars = collectionRepository.getByService(serviceId)
            .filter { it.type == Collection.TYPE_CALENDAR }
        if (calendars.isEmpty())
            return null

        val email = account.name.lowercase()
        val emailEncoded = email.replace("@", "%40")
        calendars.firstOrNull { collection ->
            val url = collection.url.toString().lowercase()
            url.contains(email) || url.contains(emailEncoded)
        }?.let { return it.id }

        calendars.firstOrNull { collection ->
            collection.displayName?.lowercase()?.let { it == email || it.contains(email) } == true
        }?.let { return it.id }

        calendars.firstOrNull { it.supportsVEVENT != false }?.let { return it.id }
        return calendars.first().id
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

            // manual syncs skip the worker's network checks, so guard here: no internet → show message,
            // don't start a sync that would only fail
            val accountSettings = accountSettingsFactory.create(account)
            if (!syncConditionsFactory.create(accountSettings).internetAvailable()) {
                _showNoInternet.value = true
                return@launch
            }
            armed = true            // watch the resulting sync for success/failure feedback
            syncWorkerManager.enqueueOneTimeAllAuthorities(account, manual = true)
        }
    }

    /** Applies the Kompakt init defaults now (selecting the primary calendar) if they haven't been yet. */
    private suspend fun ensureDefaultsApplied() {
        if (defaultsApplied())
            return
        val service = serviceRepository.getByAccountAndType(account.name, Service.TYPE_CALDAV) ?: return
        maybeApplyDefaults(service.id)
    }

    fun unlink() {
        viewModelScope.launch {
            accountRepository.delete(account.name)
        }
    }

}
