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
 * top of [at.bitfire.davdroid.ui.account.KompaktLinkedAccountScreen].
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

        /**
         * If set alongside [EXTRA_ADD_CONSENT_SERVICE_TYPE], the activity requests only that missing
         * service's consent for the named, already-linked account, instead of linking a new account or
         * re-authorizing the whole account.
         */
        const val EXTRA_ADD_CONSENT_ACCOUNT_NAME = "addConsentAccountName"

        /** [at.bitfire.davdroid.db.Service.TYPE_CALDAV] or [at.bitfire.davdroid.db.Service.TYPE_CARDDAV] — see [EXTRA_ADD_CONSENT_ACCOUNT_NAME]. */
        const val EXTRA_ADD_CONSENT_SERVICE_TYPE = "addConsentServiceType"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val addConsentAccountName = intent.getStringExtra(EXTRA_ADD_CONSENT_ACCOUNT_NAME)
        val addConsentServiceType = intent.getStringExtra(EXTRA_ADD_CONSENT_SERVICE_TYPE)
        val reauthAccountName = intent.getStringExtra(EXTRA_REAUTH_ACCOUNT_NAME)

        setContent {
            when {
                addConsentAccountName != null && addConsentServiceType != null -> {
                    val account = Account(addConsentAccountName, getString(R.string.account_type))
                    KompaktAddConsentScreen(
                        account = account,
                        serviceType = addConsentServiceType,
                        onNavUp = { onBackPressedDispatcher.onBackPressed() },
                        onFinish = { finish() }     // RESULT_CANCELED (default) either way — the caller
                                                     // re-reads state reactively, it doesn't need a result code
                    )
                }

                reauthAccountName != null -> {
                    val account = Account(reauthAccountName, getString(R.string.account_type))
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

                else -> {
                    val (initialLoginType, skipLoginTypePage) = loginTypesProvider.intentToInitialLoginType(intent)
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
    }

}
