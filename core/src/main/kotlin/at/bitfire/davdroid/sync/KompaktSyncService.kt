/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.db.ServiceType
import at.bitfire.davdroid.network.KompaktOAuthGoogle
import net.openid.appauth.AuthState

enum class KompaktSyncService(
    val dataType: SyncDataType,
    @ServiceType val serviceType: String,
    val scope: String
) {

    CALENDAR(
        dataType = SyncDataType.EVENTS,
        serviceType = Service.TYPE_CALDAV,
        scope = KompaktOAuthGoogle.SCOPE_CALENDAR
    ),

    CONTACTS(
        dataType = SyncDataType.CONTACTS,
        serviceType = Service.TYPE_CARDDAV,
        scope = KompaktOAuthGoogle.SCOPE_CONTACTS
    )

}

// An unproven grant answers false: syncing one anyway reaches Google as a 403, which deletes the home
// set and the collections under it.
internal fun KompaktSyncService.isConsented(authState: AuthState?): Boolean =
    authState?.scopeSet?.contains(scope) == true
