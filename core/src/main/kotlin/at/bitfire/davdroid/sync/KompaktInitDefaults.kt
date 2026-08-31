/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import androidx.work.WorkInfo
import at.bitfire.davdroid.BuildConfig
import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.repository.DavCollectionRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.servicedetection.RefreshCollectionsWorker
import at.bitfire.davdroid.settings.AccountSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Selects the account's primary Google calendar for sync (and, on first setup, enables auto-sync),
 * at most once per [DEFAULTS_VERSION]. Every Kompakt auto-sync entry point runs this first: a DAVx5
 * sync only processes collections with `sync=true`, so an auto-sync fired before selection would sync
 * nothing. [Singleton] so the [mutex] serializes concurrent callers.
 */
@Singleton
class KompaktInitDefaults @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountSettingsFactory: AccountSettings.Factory,
    private val collectionRepository: DavCollectionRepository,
    private val serviceRepository: DavServiceRepository,
    private val logger: Logger
) {

    companion object {
        /** "Auto synchronization" interval: 15 min in debug, once a day in release. */
        val AUTO_SYNC_INTERVAL_SECONDS = if (BuildConfig.DEBUG) 15 * 60L else 24 * 60 * 60L

        const val KEY_DEFAULTS_APPLIED = "kompakt_defaults_applied"

        /** Bump to re-run primary-calendar selection once on existing accounts. */
        const val DEFAULTS_VERSION = 2

        /** Max time to wait for collection discovery before giving up. */
        const val DISCOVERY_WAIT_MS = 30_000L
    }

    enum class Outcome {
        /** Applied now — the primary calendar was (re)selected for this version. */
        APPLIED,
        /** Already applied for the current version. */
        ALREADY_APPLIED,
        /** Primary calendar not identifiable yet; the caller may retry after discovery. */
        NOT_READY
    }

    private val mutex = Mutex()

    /** Applied defaults version for [account] (0 = none / first setup). */
    fun appliedVersion(account: Account): Int =
        try {
            AccountManager.get(context).getUserData(account, KEY_DEFAULTS_APPLIED)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            DEFAULTS_VERSION   // account gone → treat as up to date so we stop retrying
        }

    fun markApplied(account: Account) {
        try {
            AccountManager.get(context).setUserData(account, KEY_DEFAULTS_APPLIED, DEFAULTS_VERSION.toString())
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't mark Kompakt defaults applied for $account", e)
        }
    }

    /**
     * Ensures the primary calendar is selected. When [awaitDiscovery] is true, waits up to
     * [DISCOVERY_WAIT_MS] for collection discovery if the calendars aren't available yet; callers on a
     * tight time budget (a BroadcastReceiver) pass false to attempt once without blocking. A fast no-op
     * once applied.
     */
    suspend fun ensureApplied(account: Account, awaitDiscovery: Boolean = true): Outcome {
        if (appliedVersion(account) >= DEFAULTS_VERSION)
            return Outcome.ALREADY_APPLIED
        val service = serviceRepository.getByAccountAndType(account.name, Service.TYPE_CALDAV)
            ?: return Outcome.NOT_READY
        maybeApply(account, service.id).let { if (it != Outcome.NOT_READY) return it }
        if (!awaitDiscovery)
            return Outcome.NOT_READY
        withTimeoutOrNull(DISCOVERY_WAIT_MS.milliseconds) {
            RefreshCollectionsWorker
                .existsFlow(context, RefreshCollectionsWorker.workerName(service.id), WorkInfo.State.SUCCEEDED)
                .first { succeeded -> succeeded }
        }
        return maybeApply(account, service.id)
    }

    /**
     * Selects only the primary calendar for sync and, on the first setup (version 0), enables auto-sync
     * (a version bump does not, to respect the user's toggle choice). Returns [Outcome.NOT_READY] if the
     * primary isn't identifiable yet so the caller can retry after discovery.
     */
    suspend fun maybeApply(account: Account, serviceId: Long): Outcome = mutex.withLock {
        val version = appliedVersion(account)
        if (version >= DEFAULTS_VERSION)
            return Outcome.ALREADY_APPLIED
        val calendars = collectionRepository.getByService(serviceId)
            .filter { it.type == Collection.TYPE_CALENDAR }
        if (calendars.isEmpty())
            return Outcome.NOT_READY
        val primaryId = findPrimaryCalendarId(account, calendars) ?: return Outcome.NOT_READY

        for (calendar in calendars) {
            val shouldSync = calendar.id == primaryId
            if (calendar.sync != shouldSync)
                collectionRepository.setSync(calendar.id, shouldSync)
        }

        if (version == 0) {
            try {
                accountSettingsFactory.create(account).setSyncInterval(SyncDataType.EVENTS, AUTO_SYNC_INTERVAL_SECONDS)
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Couldn't set automatic sync for $account", e)
            }
        }
        markApplied(account)
        Outcome.APPLIED
    }

    // Creating the CardDAV service makes upstream fall back to its four-hour DEFAULT_SYNC_INTERVAL for
    // contacts; the Kompakt interval has to replace it before that periodic worker outlives setup.
    fun applyContactsSyncInterval(account: Account) {
        try {
            accountSettingsFactory.create(account)
                .setSyncInterval(SyncDataType.CONTACTS, AUTO_SYNC_INTERVAL_SECONDS)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't set contacts sync interval for $account", e)
        }
    }

    suspend fun findPrimaryCalendarId(account: Account, serviceId: Long): Long? =
        findPrimaryCalendarId(
            account,
            collectionRepository.getByService(serviceId).filter { it.type == Collection.TYPE_CALENDAR }
        )

    /**
     * The primary Google calendar's CalDAV URL contains the account email (usually `%40`-encoded) and its
     * display name equals the email. Returns `null` rather than guessing, to avoid locking in a secondary
     * calendar.
     */
    fun findPrimaryCalendarId(account: Account, calendars: List<Collection>): Long? {
        val email = account.name.lowercase()
        val emailEncoded = email.replace("@", "%40")

        calendars.firstOrNull { collection ->
            val url = collection.url.toString().lowercase()
            url.contains(email) || url.contains(emailEncoded)
        }?.let { return it.id }

        calendars.firstOrNull { collection ->
            collection.displayName?.lowercase() == email
        }?.let { return it.id }

        return null
    }

}
