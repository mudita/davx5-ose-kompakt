/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import android.accounts.Account
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import at.bitfire.davdroid.db.KompaktSyncOutcome
import at.bitfire.davdroid.di.qualifier.IoDispatcher
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.servicedetection.RefreshCollectionsWorker
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.sync.KompaktAttemptResult
import at.bitfire.davdroid.sync.KompaktInitDefaults
import at.bitfire.davdroid.sync.KompaktInterruption
import at.bitfire.davdroid.sync.KompaktServiceProvisioning
import at.bitfire.davdroid.sync.KompaktServiceSyncOutcome
import at.bitfire.davdroid.sync.KompaktStorage
import at.bitfire.davdroid.sync.KompaktSyncAttempt
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.util.combine
import at.bitfire.davdroid.util.dateformat.KompaktLastSyncFormatSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger

/**
 * ViewModel for the Kompakt "Linked Account" detail screen.
 *
 * Drives the single-account screen: shows the account email, a sync toggle and last synchronization
 * time per service, and offers actions to toggle a service, sync now and unlink the account.
 *
 * It also applies the Kompakt initialization defaults exactly once per account and service: after
 * collection discovery completes, the service's collections are selected for synchronization and
 * automatic sync is enabled by default (the user can turn it off via the toggle).
 */
@HiltViewModel(assistedFactory = KompaktLinkedAccountModel.Factory::class)
class KompaktLinkedAccountModel @AssistedInject constructor(
    @Assisted val account: Account,
    @Assisted private val initialReauth: Boolean,
    @ApplicationContext private val context: Context,
    private val kompaktAccountSettings: KompaktAccountSettings,
    private val accountRepository: AccountRepository,
    private val initDefaults: KompaktInitDefaults,
    private val serviceRepository: DavServiceRepository,
    private val provisioning: KompaktServiceProvisioning,
    private val lastSyncFormat: KompaktLastSyncFormatSource,
    private val switches: KompaktServiceSwitch,
    private val lastSyncSource: KompaktServiceLastSync,
    private val outcomeSource: KompaktServiceSyncOutcome,
    private val syncAttempt: KompaktSyncAttempt,
    private val accountProgress: KompaktAccountProgressUseCase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(account: Account, initialReauth: Boolean): KompaktLinkedAccountModel
    }

    // Blank the screen while redirecting into the OAuth flow so it and the "Account not linked" dialog
    // don't flash. Released on the re-auth result — not when needsReauth clears — so a cancelled re-auth
    // returns to content instead of a permanent blank.
    enum class ReauthPhase { SHOW_CONTENT, PENDING_LAUNCH, AWAITING_RESULT }


    // state

    private val email: String = account.name

    // What the aggregated failure modal offers to retry; null while no modal is raised.
    private val _syncFailed = MutableStateFlow<Set<KompaktSyncService>?>(null)

    // One service's stored cause, raised by tapping its alert icon.
    private val _explainSyncFailure = MutableStateFlow<KompaktLinkedAccountDialog.ExplainSyncFailure?>(null)

    private val _showNoInternet = MutableStateFlow(false)

    // Raised only by a request that found nothing to sync, so unlike the environment flags it never
    // holds on screen entry.
    private val _showSyncOff = MutableStateFlow(false)

    /**
     * `true` while the device is critically low on storage (system threshold; see [KompaktStorage]). Like the
     * re-auth flag this is a *persistent* condition surfaced immediately on screen entry and re-checked on
     * resume, so the "Your storage is full" message stays visible until space frees. Storage state is queried
     * live, so no extra persistence is needed.
     */
    private val _showOutOfStorage = MutableStateFlow(KompaktStorage.isStorageLow(context))

    // needsReauth (persistent, account-global; KEY_NEEDS_REAUTH) is written only by the sync worker
    // (HTTP 401 / clean sync) and the re-auth flow, both through KompaktAccountSettings — so this
    // follows the key, including for background and periodic syncs.
    private val needsReauth: StateFlow<Boolean> =
        kompaktAccountSettings.observeReauthNeeded(account)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), readNeedsReauth())

    private val _reauthPhase = MutableStateFlow(
        if (initialReauth && needsReauth.value) ReauthPhase.PENDING_LAUNCH else ReauthPhase.SHOW_CONTENT
    )

    private val serviceStates: Map<KompaktSyncService, Flow<KompaktServiceSyncState>> =
        KompaktSyncService.entries.associateWith { service ->
            combine(
                switchOf(service),
                syncingOf(service),
                lastSyncOf(service),
                outcomeOf(service),
                ::serviceSyncState
            )
        }

    private val showNewContactsConsent: Flow<Boolean> = combine(
        serviceStates.getValue(KompaktSyncService.CONTACTS),
        kompaktAccountSettings.observeNewContactsConsentShown(account)
    ) { contacts, shown -> newContactsConsentVisible(contacts.switch, shown) }

    private val _requestConsent = MutableStateFlow<KompaktSyncService?>(null)
    private val _confirmDisable = MutableStateFlow<KompaktSyncService?>(null)

    private val dialog: Flow<KompaktLinkedAccountDialog?> = combine(
        needsReauth,
        _showOutOfStorage,
        _showNoInternet,
        _syncFailed,
        _explainSyncFailure,
        showNewContactsConsent,
        _requestConsent,
        _confirmDisable,
        _showSyncOff,
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


    init {
        // Apply each service's defaults (once, after discovery) so automatic sync triggers — e.g. the
        // calendar's REQUEST_SYNC on re-entry — actually sync instead of no-op'ing over an empty
        // selection. The first sync right after linking is intentionally left to the "Sync now / Later"
        // modal, so we do NOT enqueue a sync here.
        for (service in KompaktSyncService.entries)
            viewModelScope.launch(ioDispatcher) {
                serviceRepository.getServiceFlow(account.name, service.serviceType)
                    .filterNotNull()
                    .collectLatest { serviceRow ->
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

    /** Re-check live free storage (call on screen entry / resume). */
    fun refreshStorageState() {
        _showOutOfStorage.value = KompaktStorage.isStorageLow(context)
    }

    fun newContactsConsentShown() {
        viewModelScope.launch(ioDispatcher) {
            try {
                kompaktAccountSettings.setNewContactsConsentShown(account)
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Couldn't mark the new Contacts consent shown for $account", e)
            }
        }
    }

    fun onReauthLaunchStarted() {
        if (_reauthPhase.value == ReauthPhase.PENDING_LAUNCH) {
            _reauthPhase.value = ReauthPhase.AWAITING_RESULT
        }
    }

    fun onReauthResult() {
        _reauthPhase.value = ReauthPhase.SHOW_CONTENT
    }

    /**
     * The one entry point a toggle tap calls: decides whether the tap needs consent first, can apply
     * directly, or needs to confirm a disable, rather than the screen re-deciding it from rendered state.
     */
    fun onRequestServiceToggle(service: KompaktSyncService, enabled: Boolean) {
        when {
            enabled && readSwitch(service) == KompaktSyncSwitch.ConsentMissing -> requestConsent(service)
            enabled -> setServiceSync(service, true)
            else -> requestDisable(service)
        }
    }

    /** Switching a service off asks first; the answer arrives via [confirmDisable] or [consumeDialog]. */
    private fun requestDisable(service: KompaktSyncService) {
        _confirmDisable.value = service
    }

    fun confirmDisable() {
        val service = _confirmDisable.value ?: return
        _confirmDisable.value = null
        setServiceSync(service, false)
    }

    private fun setServiceSync(service: KompaktSyncService, enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            // The cell renders ConsentMissing exactly like Off, so its switch reports an enable.
            // Persisting one would arm the periodic worker for a service Google answers 403 for, and
            // that path never passes the eligibility filter — granting consent is the caller's job,
            // via the dialog KompaktLinkedAccountScreen shows before this is ever called with
            // enabled == true for a ConsentMissing service. Re-read rather than trusting the rendered
            // position, which may be minutes old.
            if (enabled && readSwitch(service) == KompaktSyncSwitch.ConsentMissing)
                return@launch

            // A plain re-auth requests every scope, so consent for this service can already exist with
            // no row behind it, and the switch reads Off rather than ConsentMissing for exactly that
            // state. The row has to exist before the interval is written: setEnabled writes it, and
            // AutomaticSyncManager.updateAutomaticSync arms the periodic worker and the content trigger
            // only for a service that already has one.
            if (enabled && !provisioning.ensureRow(account, service)) {
                logger.warning("Couldn't find a $service for $account; leaving the switch off")
                return@launch
            }

            // Switching off cancels both the periodic schedule (through the interval) and any in-flight
            // one-time run. The attempt excludes a run that ended CANCELLED from its verdict, so nothing
            // has to be untracked here first.
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

    fun syncNow() {
        viewModelScope.launch(ioDispatcher) { startSync(KompaktSyncService.entries) }
    }

    /** Re-syncs only what failed, through the same guards an ordinary request passes. */
    fun retry(services: Set<KompaktSyncService>) {
        consumeDialog()
        viewModelScope.launch(ioDispatcher) { startSync(services.toList()) }
    }

    /** Opens the stored cause for one service. Silent if that row is not currently a failure. */
    fun explainSyncFailure(service: KompaktSyncService) {
        val status = when (service) {
            KompaktSyncService.CALENDAR -> state.value.calendar.status
            KompaktSyncService.CONTACTS -> state.value.contacts.status
        }
        _explainSyncFailure.value = (status as? KompaktSyncStatus.Failed)
            ?.let { KompaktLinkedAccountDialog.ExplainSyncFailure(service, it.cause) }
    }

    private fun requestConsent(service: KompaktSyncService) {
        _requestConsent.value = service
    }

    // The auth error is deliberately not cleared: KEY_NEEDS_REAUTH is cleared only by a successful
    // re-auth, which is why its sheet has every dismiss path locked.
    fun consumeDialog() {
        _showNoInternet.value = false
        _showSyncOff.value = false
        _syncFailed.value = null
        _explainSyncFailure.value = null
        _showOutOfStorage.value = false
        _requestConsent.value = null
        _confirmDisable.value = null
    }

    fun unlink() {
        viewModelScope.launch(ioDispatcher) {
            accountRepository.delete(account.name)
        }
    }


    // state sources, one question each

    // Starts at Resolving rather than reading the stored settings synchronously: that read parses the
    // saved authorization, which is not work for the main thread.
    private fun switchOf(service: KompaktSyncService): Flow<KompaktSyncSwitch> =
        switches.observe(account, service)
            .flowOn(ioDispatcher)
            .onStart { emit(KompaktSyncSwitch.Resolving) }

    // Reported at the source: the first query is asynchronous, so "not yet known" must stay
    // distinguishable from "not syncing", and combine needs every input to carry a first value.
    private fun syncingOf(service: KompaktSyncService): Flow<Reported<Boolean>> =
        accountProgress(account, service.dataType)
            .map<Boolean, Reported<Boolean>> { Reported.Value(it) }
            .onStart { emit(Reported.Pending) }
            .distinctUntilChanged()

    private fun lastSyncOf(service: KompaktSyncService): Flow<Reported<String?>> =
        combine(
            lastSyncSource.observe(account, service),
            lastSyncFormat.formatter
        ) { reported, formatter ->
            when (reported) {
                Reported.Pending -> Reported.Pending
                is Reported.Value -> Reported.Value(reported.value?.let(formatter::format))
            }
        }

    // The store, not the tracked run: this is the only source that survives process death and that a
    // periodic run can write at all, so it is what makes the row honest for both services.
    private fun outcomeOf(service: KompaktSyncService): Flow<Reported<KompaktSyncOutcome?>> =
        outcomeSource.observe(account, service)
            .flowOn(ioDispatcher)


    // synchronous seeds and helpers

    // Every input must carry an immediate first value or combine emits nothing at all, so these seeds
    // are read synchronously rather than defaulted.
    private fun initialState() = KompaktLinkedAccountState(
        email = email,
        calendar = KompaktServiceSyncState(KompaktSyncSwitch.Resolving, KompaktSyncStatus.Resolving),
        contacts = KompaktServiceSyncState(KompaktSyncSwitch.Resolving, KompaktSyncStatus.Resolving),
        dialog = linkedAccountDialog(
            authError = readNeedsReauth(),
            outOfStorage = KompaktStorage.isStorageLow(context),
            noInternet = false,
            syncFailed = null,
            newContactsConsent = false,
            requestConsent = null,
            confirmDisable = null,
            syncOff = false
        ),
        reauthPhase = _reauthPhase.value
    )

    // Consent-but-no-row is only reachable from a re-auth, never from this screen's own dialog --
    // KompaktAddConsentModel.apply already runs discovery before that path can persist a grant -- so
    // discovery here is the exception, not the common case: getByAccountAndType short-circuits it on
    // every ordinary toggle.
    private fun readSwitch(service: KompaktSyncService): KompaktSyncSwitch =
        try {
            switches.read(account, service)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't read the sync switch for $account", e)
            KompaktSyncSwitch.Off
        }

    private fun readNeedsReauth(): Boolean =
        try {
            kompaktAccountSettings.getReauthNeeded(account)
        } catch (_: Exception) {
            false
        }

    // Every "why nothing synced" answer comes from one return value: the guards, the runs and the
    // offline watch all live in KompaktSyncAttempt now.
    private suspend fun startSync(requested: Collection<KompaktSyncService>) {
        when (val result = syncAttempt.run(account, requested)) {
            KompaktAttemptResult.BlockedNoStorage -> _showOutOfStorage.value = true
            KompaktAttemptResult.BlockedNoNetwork -> _showNoInternet.value = true
            KompaktAttemptResult.NoneEligible -> _showSyncOff.value = true
            is KompaktAttemptResult.Failed -> _syncFailed.value = result.retry
            // The connection went while the sync was running: name the cause, and deliberately not the
            // generic failure — a sibling that failed in the same moment failed *because* of this.
            is KompaktAttemptResult.Interrupted -> when (result.reason) {
                KompaktInterruption.NoNetwork -> _showNoInternet.value = true
            }
            // AuthFailed defers to the re-auth dialog, which outranks everything; AlreadySyncing
            // already shows a spinner on the row.
            KompaktAttemptResult.AlreadySyncing,
            KompaktAttemptResult.AuthFailed,
            KompaktAttemptResult.Succeeded -> Unit
        }
    }

}
