/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import javax.inject.Inject

class KompaktLoginTypesProvider @Inject constructor() : LoginTypesProvider {

    override val defaultLoginType: LoginType = KompaktGoogleLogin

    override val maybeNonInteractive: Boolean = true

    override fun intentToInitialLoginType(intent: Intent): LoginTypesProvider.LoginAction =
        LoginTypesProvider.LoginAction(KompaktGoogleLogin, skipLoginTypePage = true)

    @Composable
    override fun LoginTypePage(
        snackbarHostState: SnackbarHostState,
        selectedLoginType: LoginType,
        onSelectLoginType: (LoginType) -> Unit,
        setInitialLoginInfo: (LoginInfo) -> Unit,
        onContinue: () -> Unit
    ) {
        // Never shown — skipLoginTypePage is always true
    }

}
