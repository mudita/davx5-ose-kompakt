/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import android.content.Context
import androidx.work.WorkInfo
import at.bitfire.davdroid.BuildConfig
import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.repository.DavCollectionRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.servicedetection.RefreshCollectionsWorker
import at.bitfire.davdroid.settings.KompaktAccountSettings
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
 * Selects a service's collections for sync, and enables auto-sync for it unless the user already has a
 * stored preference, at most once per service per [DEFAULTS_VERSION]. Every Kompakt auto-sync entry
 * point runs this first: a DAVx5 sync only processes collections with `sync=true`, so an auto-sync
 * fired before selection would sync nothing. [Singleton] so the [mutex] serializes concurrent callers.
 */
@Singleton
class KompaktInitDefaults @Inject constructor(
    @ApplicationContext private val context: Context,
    private val kompaktAccountSettings: KompaktAccountSettings,
    private val collectionRepository: DavCollectionRepository,
    private val serviceRepository: DavServiceRepository,
    private val logger: Logger
) {

    companion object {
        /** "Auto synchronization" interval: 15 min in debug, once a day in release. */
        val AUTO_SYNC_INTERVAL_SECONDS = if (BuildConfig.DEBUG) 15 * 60L else 24 * 60 * 60L

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

    /** Applied defaults version for [account] and [service] (0 = none / first setup). */
    fun appliedVersion(account: Account, service: KompaktSyncService): Int =
        try {
            appliedVersionOf(
                perService = kompaktAccountSettings.getDefaultsAppliedVersion(account, service),
                legacy = kompaktAccountSettings.getLegacyDefaultsAppliedVersion(account),
                service = service
            )
        } catch (_: Exception) {
            DEFAULTS_VERSION   // account gone → treat as up to date so we stop retrying
        }

    suspend fun markApplied(account: Account, service: KompaktSyncService) {
        try {
            kompaktAccountSettings.setDefaultsApplied(account, service, DEFAULTS_VERSION)
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
    suspend fun ensureApplied(
        account: Account,
        service: KompaktSyncService,
        awaitDiscovery: Boolean = true
    ): Outcome {
        if (appliedVersion(account, service) >= DEFAULTS_VERSION)
            return Outcome.ALREADY_APPLIED
        val serviceRow = serviceRepository.getByAccountAndType(account.name, service.serviceType)
            ?: return Outcome.NOT_READY
        maybeApply(account, service, serviceRow.id).let { if (it != Outcome.NOT_READY) return it }
        if (!awaitDiscovery)
            return Outcome.NOT_READY
        withTimeoutOrNull(DISCOVERY_WAIT_MS.milliseconds) {
            RefreshCollectionsWorker
                .existsFlow(context, RefreshCollectionsWorker.workerName(serviceRow.id), WorkInfo.State.SUCCEEDED)
                .first { succeeded -> succeeded }
        }
        return maybeApply(account, service, serviceRow.id)
    }

    /**
     * Selects the service's collections and writes the Kompakt sync interval, the latter only when no
     * interval is stored — so a user who switched the service off before discovery finished keeps that
     * choice, and a [DEFAULTS_VERSION] bump re-selects without re-enabling. Returns [Outcome.NOT_READY]
     * if the selection cannot be made yet, so the caller can retry after discovery.
     */
    suspend fun maybeApply(
        account: Account,
        service: KompaktSyncService,
        serviceId: Long
    ): Outcome = mutex.withLock {
        val version = appliedVersion(account, service)
        if (version >= DEFAULTS_VERSION)
            return Outcome.ALREADY_APPLIED

        when (service) {
            KompaktSyncService.CALENDAR -> {
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
            }

            // Selects nothing yet, so contacts sync processes no collections. Selecting an address
            // book is the remaining work, and it must not land first: selection is what creates the
            // local address book, and the syncer deletes local collections -- with their pending
            // changes -- on any run where the database set comes back empty, which a 403 from a
            // narrowed scope produces.
            KompaktSyncService.CONTACTS -> { /* interval only */ }
        }

        // Only when nothing is stored, not when the marker is 0: a user who switched the service off
        // before discovery finished has stored the manual sentinel, and overwriting it here would turn
        // their choice back on and re-arm the periodic worker.
        try {
            if (kompaktAccountSettings.getSyncInterval(account, service.dataType) == null)
                kompaktAccountSettings.setSyncInterval(account, service.dataType, AUTO_SYNC_INTERVAL_SECONDS)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't set automatic sync for $account", e)
        }
        markApplied(account, service)
        Outcome.APPLIED
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

// The pre-split marker meant "calendar defaults applied", so it counts for CALENDAR and nothing else.
internal fun appliedVersionOf(perService: Int?, legacy: Int?, service: KompaktSyncService): Int =
    perService
        ?: legacy?.takeIf { service == KompaktSyncService.CALENDAR }
        ?: 0
