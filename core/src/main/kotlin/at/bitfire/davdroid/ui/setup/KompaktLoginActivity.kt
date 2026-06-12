/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val (initialLoginType, skipLoginTypePage) = loginTypesProvider.intentToInitialLoginType(intent)

        setContent {
            KompaktLoginScreen(
                initialLoginType = initialLoginType,
                skipLoginTypePage = skipLoginTypePage,
                initialLoginInfo = LoginActivity.loginInfoFromIntent(intent),
                onNavUp = { onSupportNavigateUp() },
                onFinish = { newAccount ->
                    if (newAccount != null)
                        setResult(RESULT_OK)
                    finish()
                }
            )
        }
    }

}
