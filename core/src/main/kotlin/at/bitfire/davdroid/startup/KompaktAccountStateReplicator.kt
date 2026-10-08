/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.startup

import at.bitfire.davdroid.di.qualifier.ApplicationScope
import at.bitfire.davdroid.di.qualifier.DefaultDispatcher
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.startup.StartupPlugin.Companion.PRIORITY_DEFAULT
import at.bitfire.davdroid.ui.KompaktAccountState
import at.bitfire.davdroid.ui.KompaktAccountStatePublisher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Kompakt: notifies [KompaktAccountState] observers whenever a column it publishes changes, so no
 * writer has to remember to.
 */
class KompaktAccountStateReplicator @Inject constructor(
    private val accountRepository: AccountRepository,
    private val kompaktAccountSettings: KompaktAccountSettings,
    private val publisher: KompaktAccountStatePublisher,
    @ApplicationScope private val scope: CoroutineScope,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : StartupPlugin {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onAppCreate() {
        scope.launch {
            accountRepository.getAllFlow()
                .distinctUntilChanged()
                .flatMapLatest { accounts ->
                    accounts
                        .flatMap { account ->
                            listOf(
                                kompaktAccountSettings.observeReauthNeeded(account, emitInitial = false),
                                kompaktAccountSettings.observeNewContactsConsentShown(account, emitInitial = false)
                            )
                        }
                        .merge()
                }
                .flowOn(defaultDispatcher)
                .collect {
                    publisher.publish()
                }
        }
    }

    override fun priority() = PRIORITY_DEFAULT

    override suspend fun onAppCreateAsync() {
    }

    override fun priorityAsync() = PRIORITY_DEFAULT

}
