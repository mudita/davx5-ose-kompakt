/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.Account
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import at.bitfire.davdroid.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Kompakt variant of [LoginActivity].
 *
 * Hosts the same [LoginScreen], but instead of navigating to the upstream `AccountActivity` when an
 * account is created, it simply returns [RESULT_OK] and finishes. This returns the user to
 * [at.bitfire.davdroid.ui.KompaktAccountsActivity], which then shows the "Account linked" modal on
 * top of [at.bitfire.davdroid.ui.KompaktLinkedAccountScreen].
 */
@AndroidEntryPoint
class KompaktLoginActivity @Inject constructor() : AppCompatActivity() {

    @Inject lateinit var loginTypesProvider: LoginTypesProvider

    companion object {
        /**
         * If set to an existing account name, the activity re-authorizes that account in place
         * (refreshes the OAuth token, keeping all local data) instead of linking a new account.
         */
        const val EXTRA_REAUTH_ACCOUNT_NAME = "reauthAccountName"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reauthAccountName = intent.getStringExtra(EXTRA_REAUTH_ACCOUNT_NAME)
        if (reauthAccountName != null) {
            val account = Account(reauthAccountName, getString(R.string.account_type))
            setContent {
                KompaktReauthScreen(
                    account = account,
                    onNavUp = { onBackPressedDispatcher.onBackPressed() },
                    onFinish = { switched ->
                        // RESULT_OK only when a new account was linked (the switch), mirroring the
                        // normal login flow; a same-account refresh leaves the default RESULT_CANCELED
                        if (switched)
                            setResult(RESULT_OK)
                        finish()
                    }
                )
            }
            return
        }

        val (initialLoginType, skipLoginTypePage) = loginTypesProvider.intentToInitialLoginType(intent)

        setContent {
            KompaktLoginScreen(
                initialLoginType = initialLoginType,
                skipLoginTypePage = skipLoginTypePage,
                initialLoginInfo = LoginActivity.loginInfoFromIntent(intent),
                onNavUp = { onBackPressedDispatcher.onBackPressed() },
                onFinish = { newAccount ->
                    if (newAccount != null)
                        setResult(RESULT_OK)
                    finish()
                }
            )
        }
    }

}
