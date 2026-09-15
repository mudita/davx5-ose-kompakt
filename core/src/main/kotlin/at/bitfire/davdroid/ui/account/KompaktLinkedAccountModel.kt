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
import at.bitfire.davdroid.sync.KompaktOfflineCause
import at.bitfire.davdroid.sync.KompaktServiceProvisioning
import at.bitfire.davdroid.sync.KompaktServiceSyncOutcome
import at.bitfire.davdroid.sync.KompaktSyncAttempt
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.sync.isConsented
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

/** Drives the Kompakt "Linked Account" screen, which shows the one linked account and its two services. */
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
    private val dialogSlot: KompaktLinkedAccountDialogSlot,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(account: Account, initialReauth: Boolean): KompaktLinkedAccountModel
    }

    // Blanks the screen while the OAuth flow is launched, so neither it nor the auth sheet flashes on the
    // way in. Released by the launcher result rather than by the flag clearing, so a cancelled re-auth
    // returns to content instead of a permanent blank.
    enum class ReauthPhase { SHOW_CONTENT, PENDING_LAUNCH, AWAITING_RESULT }


    // state

    private val email: String = account.name

    private val _reauthPhase = MutableStateFlow(
        if (initialReauth && readNeedsReauth()) ReauthPhase.PENDING_LAUNCH else ReauthPhase.SHOW_CONTENT
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

    val state: StateFlow<KompaktLinkedAccountState> = combine(
        serviceStates.getValue(KompaktSyncService.CALENDAR),
        serviceStates.getValue(KompaktSyncService.CONTACTS),
        dialogSlot.dialog,
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

        // These two are the slot's conditions: outranked on arrival, they keep their place.
        viewModelScope.launch {
            kompaktAccountSettings.observeReauthNeeded(account).collect { needed ->
                dialogSlot.condition(KompaktLinkedAccountDialog.AuthError, needed)
            }
        }
        // The switch alone, not the whole row: this collector outlives the screen, and the row also
        // carries a WorkManager flow, a database flow and the formatter's broadcast receiver.
        viewModelScope.launch {
            combine(
                switchOf(KompaktSyncService.CONTACTS),
                kompaktAccountSettings.observeNewContactsConsentShown(account)
            ) { switch, shown ->
                newContactsConsentVisible(switch, shown)
            }.distinctUntilChanged().collect { offer ->
                dialogSlot.condition(KompaktLinkedAccountDialog.NewContactsConsent, offer)
            }
        }
    }


    // actions

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

    /** Switching a service off asks first; the answer arrives via [confirmDisable] or [dismiss]. */
    private fun requestDisable(service: KompaktSyncService) {
        dialogSlot.raise(KompaktLinkedAccountDialog.ConfirmDisable(service))
    }

    /** [service] comes from the sheet that asked, so this acts on the one the user was shown. */
    fun confirmDisable(service: KompaktSyncService) {
        dismiss()
        setServiceSync(service, false)
    }

    private fun setServiceSync(service: KompaktSyncService, enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            // The cell renders ConsentMissing exactly like Off, so its switch reports an enable.
            // Persisting one would arm the periodic worker for a service Google answers 403 for.
            // Re-read rather than trust a rendered position that may be minutes old.
            if (enabled && readSwitch(service) == KompaktSyncSwitch.ConsentMissing)
                return@launch

            // A plain re-auth grants every scope, so consent can exist with no row behind it — the switch
            // reads Off rather than ConsentMissing for exactly that state. The row must exist first:
            // AutomaticSyncManager.updateAutomaticSync arms the worker only for a service that has one.
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
        dismiss()
        viewModelScope.launch(ioDispatcher) { startSync(services.toList()) }
    }

    /** Opens the stored cause for one service. Silent if that row is not currently a failure. */
    fun explainSyncFailure(service: KompaktSyncService) {
        val status = when (service) {
            KompaktSyncService.CALENDAR -> state.value.calendar.status
            KompaktSyncService.CONTACTS -> state.value.contacts.status
        }
        (status as? KompaktSyncStatus.Failed)?.let {
            dialogSlot.raise(KompaktLinkedAccountDialog.ExplainSyncFailure(service, it.cause))
        }
    }

    private fun requestConsent(service: KompaktSyncService) {
        dialogSlot.raise(KompaktLinkedAccountDialog.RequestConsent(service))
    }

    fun onAddConsentReturned(service: KompaktSyncService?) {
        if (service == null) return
        viewModelScope.launch(ioDispatcher) {
            if (service.isConsented(kompaktAccountSettings.getAuthState(account)))
                dialogSlot.raise(KompaktLinkedAccountDialog.ImportServiceNow(service))
        }
    }

    /** [service] comes from the sheet that asked, so this imports the one the user was shown. */
    fun importServiceNow(service: KompaktSyncService) {
        dismiss()
        viewModelScope.launch(ioDispatcher) { startSync(listOf(service)) }
    }

    /** Retires whatever is showing. The auth error declines, which is what keeps it up until re-auth. */
    fun dismiss() {
        dialogSlot.dismiss()
    }

    fun requestUnlink() {
        dialogSlot.raise(KompaktLinkedAccountDialog.ConfirmUnlink)
    }

    fun confirmUnlink() {
        dismiss()
        unlink()
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


    // helpers

    // combine emits nothing until every input has a value, so this stands in until the first real one.
    // The slot starts empty: its conditions arrive a frame later with their collectors, which is free
    // here because the screen withholds its rows until the switches resolve anyway.
    private fun initialState() = KompaktLinkedAccountState(
        email = email,
        calendar = KompaktServiceSyncState(KompaktSyncSwitch.Resolving, KompaktSyncStatus.Resolving),
        contacts = KompaktServiceSyncState(KompaktSyncSwitch.Resolving, KompaktSyncStatus.Resolving),
        dialog = null,
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
            KompaktAttemptResult.BlockedNoStorage ->
                dialogSlot.raise(KompaktLinkedAccountDialog.OutOfStorage)
            KompaktAttemptResult.BlockedOfflinePlus ->
                dialogSlot.raise(KompaktLinkedAccountDialog.OfflinePlus)
            KompaktAttemptResult.BlockedNoNetwork ->
                dialogSlot.raise(KompaktLinkedAccountDialog.NoInternet)
            KompaktAttemptResult.NoneEligible ->
                dialogSlot.raise(KompaktLinkedAccountDialog.SyncOff)
            is KompaktAttemptResult.Failed ->
                dialogSlot.raise(KompaktLinkedAccountDialog.SyncFailed(result.retry))
            // The connection went while the sync was running: name the cause, and deliberately not the
            // generic failure — a sibling that failed in the same moment failed *because* of this.
            is KompaktAttemptResult.Interrupted -> when (result.reason) {
                KompaktOfflineCause.OfflinePlus -> dialogSlot.raise(KompaktLinkedAccountDialog.OfflinePlus)
                KompaktOfflineCause.NoNetwork -> dialogSlot.raise(KompaktLinkedAccountDialog.NoInternet)
            }
            // AuthFailed defers to the re-auth dialog, which outranks everything; AlreadySyncing
            // already shows a spinner on the row.
            KompaktAttemptResult.AlreadySyncing,
            KompaktAttemptResult.AuthFailed,
            KompaktAttemptResult.Succeeded -> Unit
        }
    }

}
