/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class KompaktAccountsActivity : AppCompatActivity() {

    @Inject
    lateinit var accountsDrawerHandler: AccountsDrawerHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val syncAccounts = intent.action == Intent.ACTION_SYNC
        // Launched from another app (e.g. the calendar) to onboard the user. When no account exists
        // yet, the link screen shows a "Skip" button instead of the usual title + back arrow.
        val onboarding = intent.action == ACTION_ONBOARDING

        setContent {
            KompaktAccountsScreen(
                initialSyncAccounts = syncAccounts,
                onboarding = onboarding,
                onBack = ::finish,
                onSkip = {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            )
        }
    }

    companion object {
        /** Intent action used by other apps to launch the account screen in onboarding mode. */
        const val ACTION_ONBOARDING = "at.bitfire.davdroid.mudita.action.ONBOARDING"
    }

}
