/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.resource

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.Context
import at.bitfire.davdroid.R
import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.settings.SettingsManager
import at.bitfire.davdroid.sync.account.InvalidAccountException
import at.bitfire.synctools.util.AndroidAccountUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.logging.Logger

private const val ACCOUNT_TYPE = "test.account.type"

// LocalAddressBookStore.create() used to create the address-book Android account before checking
// whether the main account still existed, so a sync racing an unlink could resurrect an address book
// right after AccountRepository.delete() removed it. This locks in the fix: the account check (via
// AccountSettings, which throws InvalidAccountException for a removed account) now runs first.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LocalAddressBookStoreCreateTest {

    private val context = spyk(RuntimeEnvironment.getApplication() as Context) {
        every { getString(R.string.account_type) } returns ACCOUNT_TYPE
    }
    private val logger = Logger.getGlobal()

    private val accountSettingsFactory = mockk<AccountSettings.Factory>()
    private val serviceRepository = mockk<DavServiceRepository>()

    private val store = LocalAddressBookStore(
        accountSettingsFactory = accountSettingsFactory,
        context = context,
        localAddressBookFactory = mockk<LocalAddressBook.Factory>(),
        logger = logger,
        serviceRepository = serviceRepository,
        settings = mockk<SettingsManager>()
    )

    private val account = Account("user@example.com", ACCOUNT_TYPE)
    private val service = mockk<Service> { every { accountName } returns account.name }
    private val collection = mockk<Collection> { every { id } returns 99L; every { serviceId } returns 42L }

    @Before
    fun setUp() {
        mockkObject(AndroidAccountUtils)
        every { serviceRepository.getBlocking(42L) } returns service
    }

    @After
    fun tearDown() {
        unmockkObject(AndroidAccountUtils)
    }

    @Test
    fun `create throws InvalidAccountException and never creates an address-book account when the main account is gone`() {
        every { accountSettingsFactory.create(account) } throws InvalidAccountException(account)

        assertThrows(InvalidAccountException::class.java) {
            store.create(mockk<ContentProviderClient>(relaxed = true), collection)
        }

        verify(exactly = 0) { AndroidAccountUtils.createAccount(any(), any(), any()) }
    }

}
