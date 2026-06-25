/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.net.Uri
import androidx.core.net.toUri

/**
 * Kompakt: contract for exposing the OAuth authentication ("token") state of DAVx5 accounts to
 * other apps on the device.
 *
 * The state is published two ways, both guarded by the [PERMISSION] signature permission (only
 * apps signed with the same key can read it):
 *
 *  - **Pull / source of truth** — [KompaktAuthStateProvider], a read-only [android.content.ContentProvider]
 *    at [CONTENT_URI]. Query it any time to get the current re-authorization state per account.
 *  - **Push** — when the state of an account changes, DAVx5 emits the [ACTION_AUTH_STATE_CHANGED]
 *    broadcast (sender-enforced with [PERMISSION]) and calls
 *    [android.content.ContentResolver.notifyChange] on [CONTENT_URI], so consumers can also observe
 *    via a [android.database.ContentObserver].
 *
 * See `docs/kompakt-integration.md` for the consumer-side contract.
 */
object KompaktAuthState {

    /** Authority of [KompaktAuthStateProvider]. Must match the `<provider>` entry in the manifest. */
    const val AUTHORITY = "at.bitfire.davdroid.kompakt.authstate"

    /** Content URI to query the per-account auth state and to observe for changes. */
    val CONTENT_URI: Uri = "content://$AUTHORITY/auth_state".toUri()

    /** Signature-level permission required to read the provider and to receive the broadcast. */
    const val PERMISSION = "com.davx5.ose.permission.READ_AUTH_STATE"

    /** Broadcast action sent when an account's auth state changes. */
    const val ACTION_AUTH_STATE_CHANGED = "com.davx5.ose.action.AUTH_STATE_CHANGED"

    /** Broadcast extra: name of the affected account (String). */
    const val EXTRA_ACCOUNT_NAME = "account_name"

    /** Broadcast extra: 1 if the account now needs re-authorization, 0 otherwise (Int). */
    const val EXTRA_NEEDS_REAUTH = "needs_reauth"

    // Cursor columns returned by [KompaktAuthStateProvider.query].
    const val COLUMN_ID = "_id"
    const val COLUMN_ACCOUNT_NAME = "account_name"
    const val COLUMN_ACCOUNT_TYPE = "account_type"

    /** 1 if the account's OAuth token is invalid and needs re-authorization, 0 otherwise. */
    const val COLUMN_NEEDS_REAUTH = "needs_reauth"

}
