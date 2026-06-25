/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Log
import at.bitfire.davdroid.BuildConfig

/**
 * Kompakt: notifies other (same-signed) apps that an account's OAuth re-authorization state has
 * changed. See [KompaktAuthState] for the contract.
 *
 * Push is delivered two ways so consumers can pick whichever fits:
 *  - a [KompaktAuthState.ACTION_AUTH_STATE_CHANGED] broadcast, sender-enforced with
 *    [KompaktAuthState.PERMISSION] (best consumed via a runtime-registered receiver);
 *  - a [android.content.ContentResolver.notifyChange] on [KompaktAuthState.CONTENT_URI] (best
 *    consumed via a [android.database.ContentObserver], which is not subject to the Android 8+
 *    implicit-broadcast limitations for manifest receivers).
 */
object KompaktAuthStateBroadcaster {

    fun notifyAuthStateChanged(context: Context, account: Account, needsReauth: Boolean) {
        if (BuildConfig.DEBUG)
            Log.i(KompaktAuthState.ACTION_AUTH_STATE_CHANGED, "Notifying auth state change: account=${account.name}, needsReauth=$needsReauth")

        val intent = Intent(KompaktAuthState.ACTION_AUTH_STATE_CHANGED).apply {
            putExtra(KompaktAuthState.EXTRA_ACCOUNT_NAME, account.name)
            putExtra(KompaktAuthState.EXTRA_NEEDS_REAUTH, if (needsReauth) 1 else 0)
        }
        context.sendBroadcast(intent, KompaktAuthState.PERMISSION)

        context.contentResolver.notifyChange(KompaktAuthState.CONTENT_URI, null)
    }

}
