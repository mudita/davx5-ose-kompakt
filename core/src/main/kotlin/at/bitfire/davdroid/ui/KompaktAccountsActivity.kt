/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import at.bitfire.davdroid.ui.account.KompaktLinkedAccountModel.EntryFlow
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class KompaktAccountsActivity : AppCompatActivity() {

    @Inject
    lateinit var accountsDrawerHandler: AccountsDrawerHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only on a genuine first creation, so a restore never starts the flow a second time.
        val entryFlow = when (intent.action) {
            ACTION_REAUTH -> EntryFlow.REAUTH
            ACTION_ADD_CONTACTS_CONSENT -> EntryFlow.ADD_CONTACTS_CONSENT
            else -> null
        }.takeIf { savedInstanceState == null }
        // Launched from another app (e.g. the calendar) to onboard the user. When no account exists
        // yet, the link screen shows a "Skip" button instead of the usual title + back arrow.
        val onboarding = intent.action == ACTION_ONBOARDING

        setContent {
            KompaktAccountsScreen(
                entryFlow = entryFlow,
                // The caller asked for one thing and didn't get it, so it gets the user back at once.
                onEntryAbandoned = {
                    setResult(RESULT_CANCELED)
                    finish()
                },
                onBack = ::finish,
                onboarding = onboarding,
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

        const val ACTION_REAUTH = "at.bitfire.davdroid.mudita.action.REAUTH"

        const val ACTION_ADD_CONTACTS_CONSENT = "at.bitfire.davdroid.mudita.action.ADD_CONTACTS_CONSENT"
    }

}
