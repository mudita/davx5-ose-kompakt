/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.Account
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.di.qualifier.IoDispatcher
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.servicedetection.RefreshCollectionsWorker
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.sync.KompaktInitDefaults
import at.bitfire.davdroid.sync.SyncDataType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Waits for the newly linked account's setup to complete (collection discovery for every service the
 * account has) before the Kompakt login flow navigates to the Linked Account screen. This way the
 * "Account linked / Sync now" dialog only appears once the collections are discovered, so tapping
 * "Sync now" works immediately instead of after a delay.
 */
@HiltViewModel
class KompaktLoginFinalizeModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountSettingsFactory: AccountSettings.Factory,
    private val serviceRepository: DavServiceRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger
) : ViewModel() {

    companion object {
        /** Max time to wait for collection discovery before navigating anyway (failsafe). */
        private const val DISCOVERY_WAIT_MS = 30_000L
    }

    private val _ready = MutableStateFlow(false)
    /** `true` once setup is finished (or the failsafe timeout elapsed) and the flow may navigate on. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun awaitSetup(account: Account) {
        if (_ready.value)
            return
        viewModelScope.launch(ioDispatcher) {
            val services = listOf(Service.TYPE_CALDAV, Service.TYPE_CARDDAV)
                .mapNotNull { type -> serviceRepository.getByAccountAndType(account.name, type) }

            if (services.any { service -> service.type == Service.TYPE_CARDDAV })
                applyContactsSyncInterval(account)

            if (services.isNotEmpty())
                withTimeoutOrNull(DISCOVERY_WAIT_MS.milliseconds) {
                    for (service in services)
                        RefreshCollectionsWorker
                            .existsFlow(context, RefreshCollectionsWorker.workerName(service.id), WorkInfo.State.SUCCEEDED)
                            .first { succeeded -> succeeded }
                }
            _ready.value = true
        }
    }

    // Creating the CardDAV service makes upstream fall back to its four-hour DEFAULT_SYNC_INTERVAL for
    // contacts; the Kompakt interval has to replace it before that periodic worker outlives setup.
    private fun applyContactsSyncInterval(account: Account) {
        try {
            accountSettingsFactory.create(account)
                .setSyncInterval(SyncDataType.CONTACTS, KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't set contacts sync interval for $account", e)
        }
    }

}
