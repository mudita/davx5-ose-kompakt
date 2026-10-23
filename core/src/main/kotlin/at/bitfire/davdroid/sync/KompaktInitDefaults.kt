/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.accounts.Account
import at.bitfire.davdroid.BuildConfig
import at.bitfire.davdroid.db.Collection
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.repository.DavCollectionRepository
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.settings.KompaktAccountSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects a service's collections for sync, and enables auto-sync for it unless the user already has a
 * stored preference, at most once per service per [DEFAULTS_VERSION]. Every Kompakt sync start and
 * the linked account screen run this, after fetching the collections it selects from: a DAVx5 sync
 * only processes collections with `sync=true`, so a sync fired before selection would sync nothing.
 * [Singleton] so the [mutex] serializes concurrent callers.
 */
@Singleton
class KompaktInitDefaults @Inject constructor(
    private val kompaktAccountSettings: KompaktAccountSettings,
    private val collectionRepository: DavCollectionRepository,
    private val serviceRepository: DavServiceRepository,
    private val logger: Logger
) {

    companion object {
        /** "Auto synchronization" interval: 15 min in debug, once a day in release. */
        val AUTO_SYNC_INTERVAL_SECONDS = if (BuildConfig.DEBUG) 15 * 60L else 24 * 60 * 60L

        /** Bump to re-run collection selection once on existing accounts. */
        const val DEFAULTS_VERSION = 2
    }

    enum class Outcome {
        /** Applied now — the service's collections were (re)selected for this version. */
        APPLIED,
        /** Already applied for the current version. */
        ALREADY_APPLIED,
        /** Nothing to select yet: the service has no collections, or no identifiable primary calendar. */
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

    /**
     * Whether this service's defaults have been written yet. Until they have, a switch reading "off"
     * only means no default has been applied — not that the user chose off.
     */
    fun isApplied(account: Account, service: KompaktSyncService): Boolean =
        appliedVersion(account, service) >= DEFAULTS_VERSION

    suspend fun markApplied(account: Account, service: KompaktSyncService) {
        try {
            kompaktAccountSettings.setDefaultsApplied(account, service, DEFAULTS_VERSION)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't mark Kompakt defaults applied for $account", e)
        }
    }

    /**
     * Ensures the service's collections are selected, from whatever collections it has now: fetching
     * them is [KompaktCollectionDiscovery.ensure]'s, which callers run first. A fast no-op once applied.
     */
    suspend fun ensureApplied(account: Account, service: KompaktSyncService): Outcome {
        if (appliedVersion(account, service) >= DEFAULTS_VERSION)
            return Outcome.ALREADY_APPLIED
        val serviceRow = serviceRepository.getByAccountAndType(account.name, service.serviceType)
            ?: return Outcome.NOT_READY
        return maybeApply(account, service, serviceRow.id)
    }

    /**
     * Selects the service's collections and writes the Kompakt sync interval, the latter only when no
     * interval is stored — so a user who switched the service off before discovery finished keeps that
     * choice, and a [DEFAULTS_VERSION] bump re-selects without re-enabling. Returns [Outcome.NOT_READY]
     * when there is nothing to select yet.
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

            // Every discovered address book rather than a chosen one: Google exposes a single
            // `.../lists/default/` per account, so unlike calendars there is no primary to identify
            // and nothing to leave deselected.
            KompaktSyncService.CONTACTS -> {
                val addressBooks = collectionRepository.getByService(serviceId)
                    .filter { it.type == Collection.TYPE_ADDRESSBOOK }
                if (addressBooks.isEmpty())
                    return Outcome.NOT_READY

                for (addressBook in addressBooks)
                    if (!addressBook.sync)
                        collectionRepository.setSync(addressBook.id, true)
            }
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
