/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.accounts.AccountManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import at.bitfire.davdroid.R
import at.bitfire.davdroid.settings.AccountSettings

/**
 * Kompakt: read-only [ContentProvider] exposing the per-account OAuth re-authorization state to
 * other (same-signed) apps. See [KompaktAuthState] for the contract.
 *
 * Read access is enforced by the `android:readPermission` declared on the `<provider>` in the
 * manifest, so this class does not check permissions itself.
 *
 * This provider is created before the [android.app.Application], so it intentionally avoids Hilt
 * and reads the state directly from [AccountManager].
 */
class KompaktAuthStateProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val context = context!!
        val accountManager = AccountManager.get(context)
        val accountType = context.getString(R.string.account_type)

        val cursor = MatrixCursor(
            arrayOf(
                KompaktAuthState.COLUMN_ID,
                KompaktAuthState.COLUMN_ACCOUNT_NAME,
                KompaktAuthState.COLUMN_ACCOUNT_TYPE,
                KompaktAuthState.COLUMN_NEEDS_REAUTH
            )
        )

        accountManager.getAccountsByType(accountType).forEachIndexed { index, account ->
            val needsReauth =
                if (accountManager.getUserData(account, AccountSettings.KEY_NEEDS_REAUTH) == "1") 1 else 0
            cursor.addRow(listOf(index.toLong(), account.name, account.type, needsReauth))
        }

        cursor.setNotificationUri(context.contentResolver, KompaktAuthState.CONTENT_URI)
        return cursor
    }

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.dir/vnd.${KompaktAuthState.AUTHORITY}.auth_state"

    // Read-only provider: mutations are not supported.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

}
