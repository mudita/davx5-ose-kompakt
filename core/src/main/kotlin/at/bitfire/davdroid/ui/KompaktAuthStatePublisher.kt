/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Log
import at.bitfire.davdroid.BuildConfig
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/**
 * The push half of the [KompaktAuthState] contract: announces one account's re-auth state.
 *
 * Separate from the collector that decides *when* to announce, so that decision can be exercised
 * without a broadcast, an [Intent] or a [android.net.Uri] — none of which work in a JVM unit test.
 */
interface KompaktAuthStatePublisher {

    fun publish(account: Account, needsReauth: Boolean)

}

class KompaktAuthStateBroadcastPublisher @Inject constructor(
    @ApplicationContext private val context: Context
) : KompaktAuthStatePublisher {

    override fun publish(account: Account, needsReauth: Boolean) {
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

@Module
@InstallIn(SingletonComponent::class)
interface KompaktAuthStatePublisherModule {

    @Binds
    fun kompaktAuthStatePublisher(impl: KompaktAuthStateBroadcastPublisher): KompaktAuthStatePublisher

}
