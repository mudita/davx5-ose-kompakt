/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import at.bitfire.davdroid.settings.KompaktAccountSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface KompaktServiceToggle {

    fun isOn(account: Account, service: KompaktSyncService): Boolean

    fun observe(account: Account, service: KompaktSyncService): Flow<Boolean>

    suspend fun set(account: Account, service: KompaktSyncService, on: Boolean)

}

@Singleton
class KompaktServiceToggleImpl @Inject constructor(
    private val accountSettings: KompaktAccountSettings
) : KompaktServiceToggle {

    override fun isOn(account: Account, service: KompaktSyncService) =
        toggleOn(accountSettings.getSyncInterval(account, service.dataType))

    override fun observe(account: Account, service: KompaktSyncService) =
        accountSettings.observeSyncInterval(account, service.dataType).map { toggleOn(it) }

    // Through the seam, not a raw write: setSyncInterval also reschedules the periodic worker and the
    // content trigger.
    override suspend fun set(account: Account, service: KompaktSyncService, on: Boolean) =
        accountSettings.setSyncInterval(
            account,
            service.dataType,
            if (on) KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS else null
        )

}

internal fun toggleOn(intervalSeconds: Long?): Boolean =
    intervalSeconds == KompaktInitDefaults.AUTO_SYNC_INTERVAL_SECONDS

@Module
@InstallIn(SingletonComponent::class)
interface KompaktServiceToggleModule {

    @Binds
    fun kompaktServiceToggle(impl: KompaktServiceToggleImpl): KompaktServiceToggle

}
