/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.network

import at.bitfire.davdroid.db.Service
import net.openid.appauth.AuthState

// The granted scope set is the only trustworthy answer to "did the user consent to this service?".
// A missing service row means either a refused scope or a failed discovery, and telling a user to
// grant a permission they already granted is worse than telling them nothing.
object KompaktGrantedServices {

    fun fromScopes(scopes: Set<String>?): Set<String> = buildSet {
        if (scopes == null)
            return@buildSet
        if (KompaktOAuthGoogle.SCOPE_CALENDAR in scopes)
            add(Service.TYPE_CALDAV)
        if (KompaktOAuthGoogle.SCOPE_CONTACTS in scopes)
            add(Service.TYPE_CARDDAV)
    }

    fun fromAuthState(authState: AuthState?): Set<String> =
        fromScopes(authState?.scopeSet)

}
