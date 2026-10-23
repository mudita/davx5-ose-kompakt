/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync.adapter

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.provider.CalendarContract
import at.bitfire.davdroid.R
import at.bitfire.davdroid.TEST_ACCOUNT_TYPE
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.sync.SyncConditions
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.util.logging.Logger

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class KompaktSyncAdapterRequestFilterTest {

    // Unit tests don't merge Android resources, so the account types can't resolve for real.
    private val context = spyk(RuntimeEnvironment.getApplication() as Context) {
        every { getString(R.string.account_type_address_book) } returns "at.bitfire.davdroid.address_book.mudita"
    }

    private val account = Account("someone@gmail.com", TEST_ACCOUNT_TYPE)

    private val accountSettingsFactory = mockk<AccountSettings.Factory> {
        every { create(any(), any()) } returns mockk()
    }

    // Unmet conditions end the request right after the filter, before any worker is enqueued or awaited.
    private val syncConditionsFactory = mockk<SyncConditions.Factory> {
        every { create(any()) } returns mockk { every { wifiConditionsMet() } returns false }
    }

    private val syncWorkerManager = mockk<SyncWorkerManager>(relaxed = true)

    private val syncAdapter = SyncAdapterImpl(
        accountSettingsFactory = accountSettingsFactory,
        collectionRepository = mockk(),
        serviceRepository = mockk(),
        context = context,
        logger = Logger.getLogger(javaClass.name),
        syncConditionsFactory = syncConditionsFactory,
        syncFrameworkIntegration = Lazy { mockk(relaxed = true) },
        syncWorkerManager = syncWorkerManager
    )

    private fun performSync(extras: Bundle) =
        syncAdapter.onPerformSync(account, extras, CalendarContract.AUTHORITY, mockk(), SyncResult())

    @Test
    fun requestWithoutUploadOrManual_isIgnored() {
        performSync(Bundle())

        verify(exactly = 0) { accountSettingsFactory.create(any(), any()) }
        verify(exactly = 0) { syncWorkerManager.enqueueOneTime(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun requestWithLocalChanges_isHandled() {
        performSync(Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true) })

        verify { accountSettingsFactory.create(account, any()) }
    }

    @Test
    fun manualRequest_isHandled() {
        performSync(Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true) })

        verify { accountSettingsFactory.create(account, any()) }
    }

}
