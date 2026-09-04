/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import at.bitfire.davdroid.di.qualifier.IoDispatcher
import at.bitfire.davdroid.sync.KompaktSyncService
import at.bitfire.davdroid.sync.SyncDataType
import at.bitfire.synctools.util.setAndVerifyUserData
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The account settings Kompakt reads and writes, which announce their own changes.
 *
 * [AccountManager] notifies nobody when user data changes, so every write here announces itself on an
 * in-process channel and readers re-read. In-process is enough only because this app alone may write
 * its own accounts' user data and runs in a single process; declaring an `android:process` anywhere
 * would break it silently.
 *
 * The writes that [AccountSettings] owns delegate to it rather than storing the value directly,
 * because those calls do more than store — [AccountSettings.setSyncInterval] also reschedules the
 * periodic worker and the sync framework's content trigger.
 */
interface KompaktAccountSettings {

    fun getReauthNeeded(account: Account): Boolean

    suspend fun setReauthNeeded(account: Account, needed: Boolean)

    fun observeReauthNeeded(account: Account, emitInitial: Boolean = true): Flow<Boolean>

    fun getDefaultsAppliedVersion(account: Account, service: KompaktSyncService): Int?

    suspend fun setDefaultsApplied(account: Account, service: KompaktSyncService, version: Int)

    /** The marker written before it was split per service, which meant "calendar defaults applied". */
    fun getLegacyDefaultsAppliedVersion(account: Account): Int?

    /**
     * Whether this account has been offered Contacts consent — either because the dialog was shown
     * and answered, or because the account never needed it. Linking sets it up front, so only an
     * account linked before Contacts sync could be granted at all ever reads false.
     */
    fun getNewContactsConsentShown(account: Account): Boolean

    /** [getNewContactsConsentShown], re-read on every change. [emitInitial] as in [observeSyncInterval]. */
    fun observeNewContactsConsentShown(account: Account, emitInitial: Boolean = true): Flow<Boolean>

    suspend fun setNewContactsConsentShown(account: Account)

    /**
     * The stored sync interval in seconds, or `null` when nothing is stored.
     *
     * Deliberately unlike [AccountSettings.getSyncInterval], which substitutes a four-hour default for
     * an absent key and so cannot tell "never configured" from "configured to four hours".
     */
    fun getSyncInterval(account: Account, dataType: SyncDataType): Long?

    /**
     * [getSyncInterval], re-read on every change.
     *
     * [emitInitial] also delivers the value stored at subscription time, which is what a screen needs
     * for its first frame. Pass `false` where an emission is an event rather than state — a publisher
     * would otherwise announce a change once per subscription.
     */
    fun observeSyncInterval(account: Account, dataType: SyncDataType, emitInitial: Boolean = true): Flow<Long?>

    suspend fun setSyncInterval(account: Account, dataType: SyncDataType, seconds: Long?)

    /** The stored OAuth authorization, or `null` when none is stored. */
    fun getAuthState(account: Account): AuthState?

    /** [getAuthState], re-read on every change. [emitInitial] as in [observeSyncInterval]. */
    fun observeAuthState(account: Account, emitInitial: Boolean = true): Flow<AuthState?>

    suspend fun updateAuthState(account: Account, authState: AuthState)

}

@Singleton
class KompaktAccountSettingsImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val accountSettingsFactory: AccountSettings.Factory,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : KompaktAccountSettings {

    companion object {
        const val KEY_DEFAULTS_APPLIED = "kompakt_defaults_applied"

        const val KEY_NEW_CONTACTS_CONSENT_SHOWN = "kompakt_new_contacts_consent_shown"

        private const val FLAG_SET = "1"
    }

    private data class AccountKey(val account: Account, val key: String)

    private val accountManager = AccountManager.get(context)

    // Carries what changed, never the value, so the store stays the single source of truth.
    // DROP_OLDEST keeps tryEmit non-suspending, so a writer never waits on a collector.
    private val changed = MutableSharedFlow<AccountKey>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val writeLock = Mutex()

    override fun getReauthNeeded(account: Account) =
        get(account, AccountSettings.KEY_NEEDS_REAUTH) == FLAG_SET

    // Cleared to null rather than to "0", because KompaktAuthStateProvider publishes this key by
    // testing it against FLAG_SET, and other apps read that.
    override suspend fun setReauthNeeded(account: Account, needed: Boolean) =
        putRaw(account, AccountSettings.KEY_NEEDS_REAUTH, if (needed) FLAG_SET else null)

    override fun observeReauthNeeded(account: Account, emitInitial: Boolean) =
        observe(account, AccountSettings.KEY_NEEDS_REAUTH, emitInitial).map { it == FLAG_SET }

    override fun getDefaultsAppliedVersion(account: Account, service: KompaktSyncService) =
        get(account, defaultsAppliedKey(service))?.toIntOrNull()

    override suspend fun setDefaultsApplied(account: Account, service: KompaktSyncService, version: Int) =
        putRaw(account, defaultsAppliedKey(service), version.toString())

    override fun getLegacyDefaultsAppliedVersion(account: Account) =
        get(account, KEY_DEFAULTS_APPLIED)?.toIntOrNull()

    override fun getNewContactsConsentShown(account: Account) =
        get(account, KEY_NEW_CONTACTS_CONSENT_SHOWN) == FLAG_SET

    override fun observeNewContactsConsentShown(account: Account, emitInitial: Boolean) =
        observe(account, KEY_NEW_CONTACTS_CONSENT_SHOWN, emitInitial).map { it == FLAG_SET }

    override suspend fun setNewContactsConsentShown(account: Account) =
        putRaw(account, KEY_NEW_CONTACTS_CONSENT_SHOWN, FLAG_SET)

    private fun defaultsAppliedKey(service: KompaktSyncService) =
        "${KEY_DEFAULTS_APPLIED}_${service.name.lowercase()}"

    override fun getSyncInterval(account: Account, dataType: SyncDataType) =
        get(account, intervalKey(dataType))?.toLongOrNull()

    override fun observeSyncInterval(account: Account, dataType: SyncDataType, emitInitial: Boolean) =
        observe(account, intervalKey(dataType), emitInitial).map { it?.toLongOrNull() }

    override suspend fun setSyncInterval(account: Account, dataType: SyncDataType, seconds: Long?) =
        write(account, intervalKey(dataType)) {
            accountSettingsFactory.create(account).setSyncInterval(dataType, seconds)
        }

    override fun getAuthState(account: Account) =
        authStateOf(get(account, AccountSettings.KEY_AUTH_STATE))

    override fun observeAuthState(account: Account, emitInitial: Boolean) =
        observe(account, AccountSettings.KEY_AUTH_STATE, emitInitial).map { authStateOf(it) }

    override suspend fun updateAuthState(account: Account, authState: AuthState) =
        write(account, AccountSettings.KEY_AUTH_STATE) {
            accountSettingsFactory.create(account).updateAuthState(authState)
        }


    private fun get(account: Account, key: String): String? =
        accountManager.getUserData(account, key)

    private suspend fun putRaw(account: Account, key: String, value: String?) =
        write(account, key) {
            accountManager.setAndVerifyUserData(account, key, value)
        }

    // Compares the stored value around [block] rather than predicting it, because what a delegated
    // AccountSettings write ends up storing is that method's business. Off the main thread because
    // AccountSettings refuses to be constructed there and setAndVerifyUserData sleeps between retries.
    private suspend fun write(account: Account, key: String, block: () -> Unit) {
        val moved = writeLock.withLock {
            val before = get(account, key)
            withContext(ioDispatcher) { block() }
            get(account, key) != before
        }
        if (moved)
            changed.tryEmit(AccountKey(account, key))
    }

    private fun observe(account: Account, key: String, emitInitial: Boolean): Flow<String?> {
        val announced = changed
            .filter { it.account == account && it.key == key }
            .map { }
        return (if (emitInitial) announced.onStart { emit(Unit) } else announced)
            .map { get(account, key) }
            .distinctUntilChanged()
    }

    // Duplicates the mapping inside AccountSettings.setSyncInterval, because write() has to know which
    // key that call lands on.
    private fun intervalKey(dataType: SyncDataType) = when (dataType) {
        SyncDataType.CONTACTS -> AccountSettings.KEY_SYNC_INTERVAL_ADDRESSBOOKS
        SyncDataType.EVENTS -> AccountSettings.KEY_SYNC_INTERVAL_CALENDARS
        SyncDataType.TASKS -> AccountSettings.KEY_SYNC_INTERVAL_TASKS
    }

}

// Null rather than a throw: the linked-account screen resolves this while composing.
internal fun authStateOf(json: String?): AuthState? =
    json?.let {
        try {
            AuthState.jsonDeserialize(it)
        } catch (_: Exception) {
            null
        }
    }

@Module
@InstallIn(SingletonComponent::class)
interface KompaktAccountSettingsModule {

    @Binds
    fun kompaktAccountSettings(impl: KompaktAccountSettingsImpl): KompaktAccountSettings

}
