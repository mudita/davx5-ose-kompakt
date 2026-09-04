/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.repository

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.OnAccountsUpdateListener
import android.content.Context
import androidx.annotation.WorkerThread
import at.bitfire.davdroid.R
import at.bitfire.davdroid.db.HomeSet
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.db.ServiceType
import at.bitfire.davdroid.di.qualifier.DefaultDispatcher
import at.bitfire.davdroid.resource.LocalAddressBookStore
import at.bitfire.davdroid.resource.LocalCalendarStore
import at.bitfire.davdroid.servicedetection.DavResourceFinder
import at.bitfire.davdroid.servicedetection.RefreshCollectionsWorker
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.settings.Credentials
import at.bitfire.davdroid.sync.AutomaticSyncManager
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.sync.SyncDataType
import at.bitfire.davdroid.sync.TasksAppManager
import at.bitfire.davdroid.sync.account.AccountsCleanupWorker
import at.bitfire.davdroid.sync.account.InvalidAccountException
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import at.bitfire.synctools.util.AndroidAccountUtils
import at.bitfire.synctools.vcard.GroupMethod
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

/**
 * Repository for managing CalDAV/CardDAV accounts.
 *
 * *Note:* This class is not related to address book accounts, which are managed by
 * [at.bitfire.davdroid.resource.LocalAddressBook].
 */
class AccountRepository @Inject constructor(
    private val accountSettingsFactory: AccountSettings.Factory,
    private val automaticSyncManager: Lazy<AutomaticSyncManager>,
    @ApplicationContext private val context: Context,
    private val collectionRepository: DavCollectionRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val homeSetRepository: DavHomeSetRepository,
    private val localCalendarStore: Lazy<LocalCalendarStore>,
    private val localAddressBookStore: Lazy<LocalAddressBookStore>,
    private val logger: Logger,
    private val serviceRepository: DavServiceRepository,
    private val syncWorkerManager: Lazy<SyncWorkerManager>,
    private val tasksAppManager: Lazy<TasksAppManager>
) {

    private val accountType = context.getString(R.string.account_type)
    private val accountManager = AccountManager.get(context)

    /**
     * Creates a new account with discovered services and enables periodic syncs with
     * default sync interval times.
     *
     * @param accountName   name of the account
     * @param credentials   server credentials
     * @param config        discovered server capabilities for syncable authorities
     * @param groupMethod   whether CardDAV contact groups are separate VCards or as contact categories
     *
     * @return account if account creation was successful; null otherwise (for instance because an account with this name already exists)
     */
    @WorkerThread
    fun createBlocking(
        accountName: String,
        credentials: Credentials?,
        config: DavResourceFinder.Configuration,
        groupMethod: GroupMethod,
        preconfigurationUrl: String?,
    ): Account? {
        val account = fromName(accountName)

        // create Android account
        val userData = AccountSettings.initialUserData(credentials, preconfigurationUrl)
        logger.log(Level.INFO, "Creating Android account with initial config", arrayOf(account, userData))

        if (!AndroidAccountUtils.createAccount(context, account, userData, credentials?.password))
            return null

        // add entries for account to database
        logger.log(Level.INFO, "Writing account configuration to database", config)
        try {
            if (config.cardDAV != null) {
                // insert CardDAV service
                val id = insertService(accountName, Service.TYPE_CARDDAV, config.cardDAV)

                // set initial CardDAV account settings and set sync intervals (enables automatic sync)
                val accountSettings = accountSettingsFactory.create(account)
                accountSettings.setGroupMethod(groupMethod)

                // start CardDAV service detection (refresh collections)
                RefreshCollectionsWorker.enqueue(context, id)
            }

            if (config.calDAV != null) {
                // insert CalDAV service
                val id = insertService(accountName, Service.TYPE_CALDAV, config.calDAV)

                // start CalDAV service detection (refresh collections)
                RefreshCollectionsWorker.enqueue(context, id)
            }

            // set up automatic sync (processes inserted services)
            automaticSyncManager.get().updateAutomaticSync(account)

        } catch(e: InvalidAccountException) {
            logger.log(Level.SEVERE, "Couldn't access account settings", e)
            return null
        }
        return account
    }

    /**
     * Adds one service to an account that **already exists** — the counterpart to [createBlocking],
     * which only creates a brand-new account. Used to grant a Calendar or Contacts consent the account
     * didn't have at link time.
     *
     * The caller is responsible for confirming no [Service] row of this [service]'s type already exists
     * first (e.g. via [DavServiceRepository.getByAccountAndType]) — like the `insertOrReplace` it's built
     * on, a second call for a type that already has a row replaces it, which is never the intended use
     * here.
     */
    @WorkerThread
    fun addServiceBlocking(accountName: String, service: KompaktSyncService, info: DavResourceFinder.Configuration.ServiceInfo): Long {
        val id = insertService(accountName, service.serviceType, info)

        if (service == KompaktSyncService.CONTACTS)
            accountSettingsFactory.create(fromName(accountName)).setGroupMethod(GroupMethod.GROUP_VCARDS)

        RefreshCollectionsWorker.enqueue(context, id)

        return id
    }

    suspend fun delete(accountName: String): Boolean = withContext(defaultDispatcher) {
        val account = fromName(accountName)
        // remove account directly (bypassing the authenticator, which is our own)
        try {
            // Delete synced calendars through the provider *before* removing the account, so the
            // provider notifies content observers. Removing the account alone lets the platform
            // CalendarProvider cascade-purge the rows silently (no notifyChange).
            try {
                val store = localCalendarStore.get()
                store.acquireContentProvider(throwOnMissingPermissions = false)?.use { client ->
                    store.getAll(account, client).forEach { calendar ->
                        store.delete(calendar)
                    }
                }
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Couldn't delete calendars for $accountName", e)
            }

            accountManager.removeAccountExplicitly(account)

            // delete address books (= address book accounts)
            serviceRepository.getByAccountAndType(accountName, Service.TYPE_CARDDAV)?.let { service ->
                collectionRepository.getByService(service.id).forEach { collection ->
                    localAddressBookStore.get().deleteByCollectionId(collection.id)
                }
            }

            // delete from database
            serviceRepository.deleteByAccount(accountName)

            true
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't remove account $accountName", e)
            false
        }
    }

    fun exists(accountName: String): Boolean =
        if (accountName.isEmpty())
            false
        else
            accountManager
                .getAccountsByType(accountType)
                .any { it.name == accountName }

    fun fromName(accountName: String) =
        Account(accountName, accountType)

    fun getAll(): Array<Account> = accountManager.getAccountsByType(accountType)

    fun getAllFlow() = callbackFlow<Set<Account>> {
        val listener = OnAccountsUpdateListener { accounts ->
            trySend(accounts.filter { it.type == accountType }.toSet())
        }
        withContext(defaultDispatcher) {  // causes disk I/O
            accountManager.addOnAccountsUpdatedListener(listener, null, true)
        }

        awaitClose {
            accountManager.removeOnAccountsUpdatedListener(listener)
        }
    }

    /**
     * Renames an account.
     *
     * **Note**: It is highly advised to re-sync the account after renaming in order to restore
     * a consistent state.
     *
     * @param oldName current name of the account
     * @param newName new name the account shall be re named to
     *
     * @throws InvalidAccountException if the account does not exist
     * @throws IllegalArgumentException if the new account name already exists
     * @throws Exception (or sub-classes) on other errors
     */
    suspend fun rename(oldName: String, newName: String): Unit = withContext(defaultDispatcher) {
        val oldAccount = fromName(oldName)
        val newAccount = fromName(newName)

        // check whether new account name already exists
        if (accountManager.getAccountsByType(context.getString(R.string.account_type)).contains(newAccount))
            throw IllegalArgumentException("Account with name \"$newName\" already exists")

        // rename account
        try {
            /* https://github.com/bitfireAT/davx5/issues/135
            Lock accounts cleanup so that the AccountsCleanupWorker doesn't run while we rename the account
            because this can cause problems when:
            1. The account is renamed.
            2. The AccountsCleanupWorker is called BEFORE the services table is updated.
               → AccountsCleanupWorker removes the "orphaned" services because they belong to the old account which doesn't exist anymore
            3. Now the services would be renamed, but they're not here anymore. */
            AccountsCleanupWorker.lockAccountsCleanup()

            // rename account (also moves AccountSettings)
            val future = accountManager.renameAccount(oldAccount, newName, null, null)

            // wait for operation to complete (blocks calling thread)
            val newNameFromApi: Account = future.result
            if (newNameFromApi.name != newName)
                throw IllegalStateException("renameAccount returned ${newNameFromApi.name} instead of $newName")

            // account renamed, cancel maybe running synchronization of old account
            syncWorkerManager.get().cancelAllWork(oldAccount)

            // disable periodic syncs for old account
            for (dataType in SyncDataType.entries)
                syncWorkerManager.get().disablePeriodic(oldAccount, dataType)

            // update account name references in database
            serviceRepository.renameAccount(oldName, newName)

            try {
                // update address books
                localAddressBookStore.get().updateAccount(oldAccount, newAccount, null)
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Couldn't change address books to renamed account", e)
            }

            try {
                // update calendar events
                val store = localCalendarStore.get()
                store.acquireContentProvider(true)?.use { client ->
                    store.updateAccount(oldAccount, newAccount, client)
                }
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Couldn't change calendars to renamed account", e)
            }

            try {
                // update account_name of local tasks
                val store = tasksAppManager.get().getDataStore()
                store?.acquireContentProvider(true)?.use { client ->
                    store.updateAccount(oldAccount, newAccount, client)
                }
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Couldn't change task lists to renamed account", e)
            }

            // update automatic sync
            automaticSyncManager.get().updateAutomaticSync(newAccount)
        } finally {
            // release AccountsCleanupWorker mutex at the end of this async coroutine
            AccountsCleanupWorker.unlockAccountsCleanup()
        }
    }


    // helpers

    private fun insertService(accountName: String, @ServiceType type: String, info: DavResourceFinder.Configuration.ServiceInfo): Long {
        // insert service
        val service = Service(0, accountName, type, info.principal)
        val serviceId = serviceRepository.insertOrReplaceBlocking(service)

        // insert home sets
        for (homeSet in info.homeSets)
            homeSetRepository.insertOrUpdateByUrlBlocking(HomeSet(0, serviceId, true, homeSet))

        // insert collections
        for (collection in info.collections.values) {
            collectionRepository.insertOrUpdateByUrl(collection.copy(serviceId = serviceId))
        }

        return serviceId
    }

}