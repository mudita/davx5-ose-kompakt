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
 * Applies the Kompakt initialization defaults for a linked account and, crucially, guarantees the
 * account's primary calendar is selected for synchronization **before** an automatic sync is enqueued.
 *
 * A DAVx5 sync only processes collections whose `sync` flag is `true`. On a freshly linked account no
 * collection is selected until these defaults run (after collection discovery), so any auto-sync fired
 * before then is an empty no-op — it reports success but syncs nothing. This class is the single place
 * that selects the primary calendar; every Kompakt auto-sync entry point ([KompaktSyncRequestReceiver],
 * the "Linked Account" screen) calls [ensureApplied]/[maybeApply] first so a real sync always follows
 * (SHP-1070).
 *
 * The defaults are applied at most once per [DEFAULTS_VERSION] per account: only the user's primary
 * Google calendar is selected for sync, and on the very first setup automatic sync is enabled by default.
 * Being a [Singleton], the [mutex] serializes concurrent apply attempts coming from different entry points.
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
        /**
         * Sync interval (seconds) that the "Auto synchronization" toggle enables.
         *
         * In debug builds this is shortened to 15 minutes to make testing easier; release builds
         * use the production default of once a day (24 h).
         */
        val AUTO_SYNC_INTERVAL_SECONDS = if (BuildConfig.DEBUG) 15 * 60L else 24 * 60 * 60L

        /** AccountManager userData key holding the Kompakt init-defaults version applied for this account */
        const val KEY_DEFAULTS_APPLIED = "kompakt_defaults_applied"

        /**
         * Current version of the Kompakt init defaults. Bump to re-run the (idempotent) primary-calendar
         * selection once on existing accounts — e.g. to correct accounts where an older version locked in
         * a wrong calendar. Auto-sync default is only ever applied on the very first setup (version 0).
         */
        const val DEFAULTS_VERSION = 2

        /** Max time a caller waits for collection discovery before giving up. */
        const val DISCOVERY_WAIT_MS = 30_000L
    }

    /** Outcome of an attempt to apply the Kompakt init defaults for an account. */
    enum class Outcome {
        /** Defaults were applied now — the primary calendar was (re)selected for this version. */
        APPLIED,
        /** Defaults were already applied for the current version; nothing to do. */
        ALREADY_APPLIED,
        /** The primary calendar can't be identified yet (discovery incomplete); the caller may retry later. */
        NOT_READY
    }

    // Shared across all callers (Singleton) so a receiver-triggered apply and a ViewModel-triggered apply
    // for the same account don't race each other.
    private val mutex = Mutex()

    /** Version of the Kompakt init defaults already applied to [account] (0 = none / first setup). */
    fun appliedVersion(account: Account): Int =
        try {
            AccountManager.get(context).getUserData(account, KEY_DEFAULTS_APPLIED)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            // account may no longer exist → treat as up-to-date so we don't keep trying
            DEFAULTS_VERSION
        }

    /**
     * Marks the defaults as applied at the current [DEFAULTS_VERSION]. Also used to record an explicit
     * user choice (e.g. toggling auto-sync) so the init routine won't later override it.
     */
    fun markApplied(account: Account) {
        try {
            AccountManager.get(context).setUserData(account, KEY_DEFAULTS_APPLIED, DEFAULTS_VERSION.toString())
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't mark Kompakt defaults applied for $account", e)
        }
    }

    /**
     * Ensures the primary calendar is selected for sync, waiting (bounded by [DISCOVERY_WAIT_MS]) for
     * collection discovery to finish if the calendars aren't available yet. Intended for auto-sync entry
     * points that must select a calendar right after linking before enqueuing — otherwise the enqueued
     * sync would process nothing.
     *
     * Safe to call repeatedly and from multiple entry points; it is a fast no-op once defaults are applied.
     */
    suspend fun ensureApplied(account: Account): Outcome {
        if (appliedVersion(account) >= DEFAULTS_VERSION)
            return Outcome.ALREADY_APPLIED
        val service = serviceRepository.getByAccountAndType(account.name, Service.TYPE_CALDAV)
            ?: return Outcome.NOT_READY
        // collections may already be present (e.g. a sync request shortly after discovery)
        maybeApply(account, service.id).let { if (it != Outcome.NOT_READY) return it }
        // otherwise wait until the refresh worker succeeds, then apply
        withTimeoutOrNull(DISCOVERY_WAIT_MS.milliseconds) {
            RefreshCollectionsWorker
                .existsFlow(context, RefreshCollectionsWorker.workerName(service.id), WorkInfo.State.SUCCEEDED)
                .first { succeeded -> succeeded }
        }
        return maybeApply(account, service.id)
    }

    /**
     * Applies the Kompakt initialization defaults, after collection discovery: selects **only** the
     * user's primary Google calendar for synchronization. On the very first setup it also enables
     * automatic sync by default (later runs — e.g. a defaults-version bump that re-corrects the
     * selection — do not touch the auto-sync setting, to respect the user's toggle choice).
     *
     * Runs at most once per [DEFAULTS_VERSION]. Returns [Outcome.APPLIED] if the selection was applied
     * now, [Outcome.ALREADY_APPLIED] if already up to date, or [Outcome.NOT_READY] if the primary
     * calendar can't be identified yet and the caller should retry.
     */
    suspend fun maybeApply(account: Account, serviceId: Long): Outcome = mutex.withLock {
        val version = appliedVersion(account)
        if (version >= DEFAULTS_VERSION)
            return Outcome.ALREADY_APPLIED
        val calendars = collectionRepository.getByService(serviceId)
            .filter { it.type == Collection.TYPE_CALENDAR }
        if (calendars.isEmpty())
            return Outcome.NOT_READY
        // Don't guess: if the primary isn't identifiable yet (e.g. not discovered), wait and retry
        // rather than locking in a wrong calendar.
        val primaryId = findPrimaryCalendarId(account, calendars) ?: return Outcome.NOT_READY

        // select only the primary calendar for sync
        for (calendar in calendars) {
            val shouldSync = calendar.id == primaryId
            if (calendar.sync != shouldSync)
                collectionRepository.setSync(calendar.id, shouldSync)
        }

        // enable automatic sync by default — only on the very first setup
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

    /** Loads the account's calendar collections and identifies the primary one. */
    suspend fun findPrimaryCalendarId(account: Account, serviceId: Long): Long? =
        findPrimaryCalendarId(
            account,
            collectionRepository.getByService(serviceId).filter { it.type == Collection.TYPE_CALENDAR }
        )

    /**
     * Identifies the user's **primary** Google calendar among the discovered calendars. Google's primary
     * calendar is the one whose CalDAV collection id is the account email, so its URL contains the email
     * (the "@" is usually `%40`-encoded); its display name also equals the email. Returns `null` if the
     * primary can't be confidently identified — we deliberately do **not** fall back to an arbitrary
     * calendar, to avoid selecting a secondary/added one (e.g. a test calendar).
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
