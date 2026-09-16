/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import at.bitfire.davdroid.sync.KompaktSyncService

/** What one authorization did to one service's consent. */
enum class KompaktConsentState {
    /** Granted before and after. */
    KEPT,

    /** Granted only by this authorization. */
    GRANTED,

    /** Granted before this authorization and not by it. */
    REVOKED,

    /** Granted neither before nor by this authorization. */
    ABSENT
}

/** Every service's state, so a caller reads the whole change rather than one side of it. */
fun consentDiff(
    previouslyGranted: Set<String>,
    granted: Set<String>
): Map<KompaktSyncService, KompaktConsentState> =
    KompaktSyncService.entries.associateWith { service ->
        val before = service.serviceType in previouslyGranted
        val after = service.serviceType in granted
        when {
            before && after -> KompaktConsentState.KEPT
            after -> KompaktConsentState.GRANTED
            before -> KompaktConsentState.REVOKED
            else -> KompaktConsentState.ABSENT
        }
    }

/**
 * The service this authorization revoked, or `null` if none. Never more than one: the caller reaches
 * a diff only for a non-empty grant, so whichever service is not revoked is kept or granted.
 */
fun withdrawnService(diff: Map<KompaktSyncService, KompaktConsentState>): KompaktSyncService? =
    diff.entries.firstOrNull { it.value == KompaktConsentState.REVOKED }?.key
