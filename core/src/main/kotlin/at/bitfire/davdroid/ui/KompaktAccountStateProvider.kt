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
import at.bitfire.davdroid.settings.KompaktAccountSettings
import at.bitfire.davdroid.settings.KompaktAccountSettingsImpl
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.util.logging.Logger

/**
 * Kompakt: exposes [KompaktAccountState] to other (same-signed) apps. Permissions are enforced by the
 * manifest's `android:readPermission` and `android:writePermission`.
 *
 * [update] goes through [KompaktAccountSettings] rather than [AccountManager] because only that write
 * reaches its in-process observers; an open linked-account screen would otherwise keep offering
 * Contacts after another app already has.
 */
class KompaktAccountStateProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface KompaktAccountStateProviderEntryPoint {
        fun kompaktAccountSettings(): KompaktAccountSettings
        fun logger(): Logger
    }

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

        val cursor = MatrixCursor(
            arrayOf(
                KompaktAccountState.COLUMN_ID,
                KompaktAccountState.COLUMN_ACCOUNT_NAME,
                KompaktAccountState.COLUMN_ACCOUNT_TYPE,
                KompaktAccountState.COLUMN_NEEDS_REAUTH,
                KompaktAccountState.COLUMN_NEW_CONTACTS_CONSENT_SHOWN
            )
        )

        accountManager.getAccountsByType(context.getString(R.string.account_type)).forEachIndexed { index, account ->
            fun flag(key: String) = if (accountManager.getUserData(account, key) == "1") 1 else 0
            cursor.addRow(
                listOf(
                    index.toLong(),
                    account.name,
                    account.type,
                    flag(AccountSettings.KEY_NEEDS_REAUTH),
                    flag(KompaktAccountSettingsImpl.KEY_NEW_CONTACTS_CONSENT_SHOWN)
                )
            )
        }

        cursor.setNotificationUri(context.contentResolver, KompaktAccountState.CONTENT_URI)
        return cursor
    }

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.dir/vnd.${KompaktAccountState.AUTHORITY}.account_state"

    /**
     * Accepts exactly [KompaktAccountState.COLUMN_NEW_CONTACTS_CONSENT_SHOWN] = 1, for every linked
     * account; [selection] is ignored because there is only ever one.
     */
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val context = context!!
        val entryPoint = EntryPointAccessors.fromApplication(context, KompaktAccountStateProviderEntryPoint::class.java)

        if (values?.size() != 1 || values.getAsInteger(KompaktAccountState.COLUMN_NEW_CONTACTS_CONSENT_SHOWN) != 1) {
            entryPoint.logger().warning("Ignoring account state update other than ${KompaktAccountState.COLUMN_NEW_CONTACTS_CONSENT_SHOWN}=1: $values")
            return 0
        }

        val accounts = AccountManager.get(context).getAccountsByType(context.getString(R.string.account_type))
        val settings = entryPoint.kompaktAccountSettings()
        runBlocking {
            for (account in accounts)
                settings.setNewContactsConsentShown(account)
        }
        return accounts.size
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

}
