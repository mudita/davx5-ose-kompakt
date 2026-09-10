/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderClient
import android.os.DeadObjectException
import android.provider.ContactsContract
import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.resource.LocalAddressBook
import at.bitfire.davdroid.resource.LocalAddressBookStore
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.synctools.storage.contacts.AddressContract.asSyncAdapter
import at.bitfire.synctools.util.setAndVerifyUserData
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import at.bitfire.davdroid.sync.account.InvalidAccountException
import at.bitfire.synctools.storage.LocalStorageException
import java.util.logging.Level

/**
 * Sync logic for address books
 */
class AddressBookSyncer @AssistedInject constructor(
    @Assisted account: Account,
    @Assisted resync: ResyncType?,
    @Assisted val syncFrameworkUpload: Boolean,
    @Assisted syncResult: SyncResult,
    addressBookStore: LocalAddressBookStore,
    private val accountSettingsFactory: AccountSettings.Factory,
    private val contactsSyncManagerFactory: ContactsSyncManager.Factory
): Syncer<LocalAddressBookStore, LocalAddressBook>(account, resync, syncResult) {

    @AssistedFactory
    interface Factory {
        fun create(
            account: Account,
            resyncType: ResyncType?,
            syncFrameworkUpload: Boolean,
            syncResult: SyncResult
        ): AddressBookSyncer
    }

    override val dataStore = addressBookStore

    override val serviceType: String
        get() = Service.TYPE_CARDDAV


    override fun getDbSyncCollections(serviceId: Long): List<Collection> =
        collectionRepository.getByServiceAndSync(serviceId)

    override fun syncCollection(provider: ContentProviderClient, localCollection: LocalAddressBook, remoteCollection: Collection) {
        logger.info("Synchronizing address book: ${localCollection.addressBookAccount.name}")
        syncAddressBook(
            account = account,
            addressBook = localCollection,
            provideHttpClient = { httpClient },
            provider = provider,
            syncResult = syncResult,
            collection = remoteCollection
        )
    }

    /**
     * Synchronizes an address book
     *
     * @param addressBook       local address book
     * @param provideHttpClient returns HTTP client on demand
     * @param provider          content provider to access android contacts
     * @param syncResult        stores hard and soft sync errors
     * @param collection        the database collection associated with this address book
     */
    private fun syncAddressBook(
        account: Account,
        addressBook: LocalAddressBook,
        provideHttpClient: () -> OkHttpClient,
        provider: ContentProviderClient,
        syncResult: SyncResult,
        collection: Collection
    ) {
        try {
            // handle group method change
            val accountSettings = accountSettingsFactory.create(account)
            val groupMethod = accountSettings.getGroupMethod().name

            val accountManager = AccountManager.get(context)
            accountManager.getUserData(addressBook.addressBookAccount, PREVIOUS_GROUP_METHOD)?.let { previousGroupMethod ->
                if (previousGroupMethod != groupMethod) {
                    logger.info("Group method changed, deleting all local contacts/groups")

                    // delete all local contacts and groups so that they will be downloaded again
                    provider.delete(ContactsContract.RawContacts.CONTENT_URI.asSyncAdapter(addressBook.addressBookAccount), null, null)
                    provider.delete(ContactsContract.Groups.CONTENT_URI.asSyncAdapter(addressBook.addressBookAccount), null, null)

                    // reset sync state
                    addressBook.syncState = null
                }
            }
            accountManager.setAndVerifyUserData(addressBook.addressBookAccount, PREVIOUS_GROUP_METHOD, groupMethod)

            val syncManager = contactsSyncManagerFactory.contactsSyncManager(
                account,
                provideHttpClient(),
                syncResult,
                provider,
                addressBook,
                collection,
                resync,
                syncFrameworkUpload
            )
            runBlocking {
                syncManager.performSync()
            }

        } catch(e: Exception) {
            // Same arms as Syncer.invoke, so a contacts failure is counted like a calendar one. Without
            // them this catch reported success for anything thrown by the preamble above, and for the
            // exceptions SyncManager deliberately re-throws.
            when {
                e is LocalStorageException && e.cause is DeadObjectException -> {
                    logger.log(Level.WARNING, "Received DeadObjectException, treating as soft error", e)
                    syncResult.numDeadObjectExceptions++
                }

                e is InvalidAccountException ->
                    logger.log(Level.WARNING, "Account was removed during synchronization", e)

                else -> {
                    logger.log(Level.SEVERE, "Couldn't sync contacts", e)
                    syncResult.numUnclassifiedErrors++
                }
            }
        }

        logger.info("Contacts sync complete")
    }


    companion object {

        const val PREVIOUS_GROUP_METHOD = "previous_group_method"

    }

}