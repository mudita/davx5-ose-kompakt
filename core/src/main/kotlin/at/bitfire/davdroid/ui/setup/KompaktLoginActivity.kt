/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.accounts.Account
import android.content.Intent
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
         * Result extra: what the re-authorization did to each service's Google consent, as a [Bundle]
         * of [KompaktSyncService] name to [KompaktConsentState] name. Absent when that could not be
         * determined. Nothing stores it, so a caller that ignores the result data loses the only
         * chance to act on the change.
         *
         * Read it with [consentChangeFrom] rather than by hand — this is the only place the encoding
         * is written, and it should stay the only place it is read.
         */
        const val EXTRA_CONSENT_CHANGE = "consentChange"

        private const val EXTRA_SWITCHED_FROM_ACCOUNT = "switchedFromAccount"

        /**
         * Encodes a re-authorization outcome for [android.app.Activity.setResult]. `null` whenever
         * there is nothing for the caller to act on, which every outcome can be.
         */
        fun reauthResultIntent(result: KompaktReauthResult): Intent? = when (result) {
            is KompaktReauthResult.Refreshed ->
                result.consent?.let { Intent().putExtra(EXTRA_CONSENT_CHANGE, consentChangeBundle(it)) }

            is KompaktReauthResult.Switched ->
                result.removedAccount?.let { Intent().putExtra(EXTRA_SWITCHED_FROM_ACCOUNT, it) }

            KompaktReauthResult.Cancelled -> null
        }

        /**
         * The account a re-authorization switched away from, named only when it was actually removed.
         * A caller waits for the named account to leave the accounts flow, and one that is still
         * there never will.
         */
        fun switchedFromAccount(data: Intent?): String? =
            data?.getStringExtra(EXTRA_SWITCHED_FROM_ACCOUNT)

        /** The consent change a re-authorization carried, or `null` if it carried none. */
        fun consentChangeFrom(data: Intent?): Map<KompaktSyncService, KompaktConsentState>? {
            val bundle = data?.getBundleExtra(EXTRA_CONSENT_CHANGE) ?: return null
            return bundle.keySet().mapNotNull { key ->
                val service = KompaktSyncService.entries.find { it.name == key }
                val state = KompaktConsentState.entries.find { it.name == bundle.getString(key) }
                // A name this build doesn't know is dropped rather than failing the whole result: the
                // sender may be a newer version that learned another service or another state.
                if (service != null && state != null) service to state else null
            }.toMap()
        }

        private fun consentChangeBundle(consent: Map<KompaktSyncService, KompaktConsentState>) =
            Bundle().apply {
                for ((service, state) in consent)
                    putString(service.name, state.name)
            }

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
                        onFinish = { result ->
                            setResult(
                                if (result == KompaktReauthResult.Cancelled) RESULT_CANCELED else RESULT_OK,
                                reauthResultIntent(result)
                            )
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
