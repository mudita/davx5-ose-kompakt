/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import at.bitfire.davdroid.R
import at.bitfire.davdroid.TEST_ACCOUNT_TYPE
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.settings.KompaktAccountSettingsImpl
import dagger.hilt.android.EntryPointAccessors
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.util.logging.Logger

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktAccountStateProviderTest {

    // Unit tests don't merge Android resources, so R.string.account_type can't resolve for real.
    private val context = spyk(RuntimeEnvironment.getApplication() as Context) {
        every { getString(R.string.account_type) } returns TEST_ACCOUNT_TYPE
    }
    private val accountManager = AccountManager.get(context)
    private val account = Account("someone@gmail.com", TEST_ACCOUNT_TYPE)

    private val settings = mockk<KompaktAccountSettings>(relaxed = true)

    private lateinit var provider: KompaktAccountStateProvider

    @Before
    fun setUp() {
        accountManager.addAccountExplicitly(account, null, null)

        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(any(), KompaktAccountStateProvider.KompaktAccountStateProviderEntryPoint::class.java)
        } returns mockk {
            every { kompaktAccountSettings() } returns settings
            every { logger() } returns Logger.getGlobal()
        }

        provider = KompaktAccountStateProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = KompaktAccountState.AUTHORITY })
    }

    @After
    fun tearDown() {
        unmockkStatic(EntryPointAccessors::class)
    }

    private fun column(name: String): Int =
        provider.query(KompaktAccountState.CONTENT_URI, null, null, null, null).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            cursor.getInt(cursor.getColumnIndexOrThrow(name))
        }

    private fun update(values: ContentValues) =
        provider.update(KompaktAccountState.CONTENT_URI, values, null, null)


    @Test
    fun `an account never offered Contacts reads not shown`() {
        assertEquals(0, column(KompaktAccountState.COLUMN_NEW_CONTACTS_CONSENT_SHOWN))
    }

    @Test
    fun `the stored flag reads shown`() {
        accountManager.setUserData(account, KompaktAccountSettingsImpl.KEY_NEW_CONTACTS_CONSENT_SHOWN, "1")
        assertEquals(1, column(KompaktAccountState.COLUMN_NEW_CONTACTS_CONSENT_SHOWN))
    }

    @Test
    fun `needs_reauth reads the stored flag`() {
        assertEquals(0, column(KompaktAccountState.COLUMN_NEEDS_REAUTH))
        accountManager.setUserData(account, AccountSettings.KEY_NEEDS_REAUTH, "1")
        assertEquals(1, column(KompaktAccountState.COLUMN_NEEDS_REAUTH))
    }

    @Test
    fun `writing shown marks it through the settings`() {
        assertEquals(1, update(ContentValues().apply { put(KompaktAccountState.COLUMN_NEW_CONTACTS_CONSENT_SHOWN, 1) }))
        coVerify(exactly = 1) { settings.setNewContactsConsentShown(account) }
    }

    @Test
    fun `the offer cannot be re-armed`() {
        assertEquals(0, update(ContentValues().apply { put(KompaktAccountState.COLUMN_NEW_CONTACTS_CONSENT_SHOWN, 0) }))
        coVerify(exactly = 0) { settings.setNewContactsConsentShown(any()) }
    }

    @Test
    fun `no other column is writable`() {
        assertEquals(0, update(ContentValues().apply { put(KompaktAccountState.COLUMN_NEEDS_REAUTH, 0) }))
        assertEquals(
            0,
            update(ContentValues().apply {
                put(KompaktAccountState.COLUMN_NEW_CONTACTS_CONSENT_SHOWN, 1)
                put(KompaktAccountState.COLUMN_NEEDS_REAUTH, 0)
            })
        )
        coVerify(exactly = 0) { settings.setNewContactsConsentShown(any()) }
    }

}
