/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.Account
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import at.bitfire.davdroid.R
import at.bitfire.davdroid.sync.KompaktSyncService
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
         * If set alongside [EXTRA_ADD_CONSENT_SERVICE_TYPE], the activity applies [EXTRA_ADD_CONSENT_SERVICE_TYPE]'s
         * missing consent to the named, already-linked account, instead of linking a new account or
         * re-authorizing the whole account. Every scope is requested, not just the missing one — see
         * [at.bitfire.davdroid.network.KompaktOAuthGoogle.signIn].
         */
        const val EXTRA_ADD_CONSENT_ACCOUNT_NAME = "addConsentAccountName"

        /**
         * [at.bitfire.davdroid.db.Service.TYPE_CALDAV] or [at.bitfire.davdroid.db.Service.TYPE_CARDDAV]
         * ([KompaktSyncService.serviceType]) — see [EXTRA_ADD_CONSENT_ACCOUNT_NAME]. Any other value is
         * treated as absent, the same as when the extra is missing.
         */
        const val EXTRA_ADD_CONSENT_SERVICE_TYPE = "addConsentServiceType"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val addConsentAccountName = intent.getStringExtra(EXTRA_ADD_CONSENT_ACCOUNT_NAME)
        val addConsentService = intent.getStringExtra(EXTRA_ADD_CONSENT_SERVICE_TYPE)
            ?.let { serviceType -> KompaktSyncService.entries.find { it.serviceType == serviceType } }
        val reauthAccountName = intent.getStringExtra(EXTRA_REAUTH_ACCOUNT_NAME)

        // Exactly one of the two add-consent extras set is a malformed intent: reject it rather than
        // falling through to a full sign-in below, which — unlike this — can replace the account
        // already linked on a device that holds exactly one.
        if ((addConsentAccountName == null) != (addConsentService == null)) {
            finish()
            return
        }

        setContent {
            when {
                addConsentAccountName != null && addConsentService != null -> {
                    val account = Account(addConsentAccountName, getString(R.string.account_type))
                    KompaktAddConsentScreen(
                        account = account,
                        service = addConsentService,
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
