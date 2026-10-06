/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.net.Uri
import androidx.core.net.toUri

/**
 * Kompakt: contract for exposing per-account state to other (same-signed) apps through
 * [KompaktAccountStateProvider]. Supersedes [KompaktAuthState], which stays until its consumers move.
 *
 * See `docs/app-integration.md` for the consumer-side contract.
 */
object KompaktAccountState {

    const val AUTHORITY = "at.bitfire.davdroid.mudita.kompakt.accountstate"

    val CONTENT_URI: Uri = "content://$AUTHORITY/account_state".toUri()

    const val READ_PERMISSION = "at.bitfire.davdroid.mudita.permission.READ_ACCOUNT_STATE"

    const val WRITE_PERMISSION = "at.bitfire.davdroid.mudita.permission.WRITE_ACCOUNT_STATE"

    const val COLUMN_ID = "_id"
    const val COLUMN_ACCOUNT_NAME = "account_name"
    const val COLUMN_ACCOUNT_TYPE = "account_type"

    /** 1 if the account's OAuth token is invalid and needs re-authorization, 0 otherwise. */
    const val COLUMN_NEEDS_REAUTH = "needs_reauth"

    /**
     * 1 once the one-time "Enable contact sync?" offer has been shown — by this app or another — or was
     * never owed, 0 while it is still due. The only writable column, and only to 1.
     */
    const val COLUMN_NEW_CONTACTS_CONSENT_SHOWN = "new_contacts_consent_shown"

}
