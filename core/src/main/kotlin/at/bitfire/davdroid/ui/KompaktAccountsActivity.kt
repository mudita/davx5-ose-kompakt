/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import at.bitfire.davdroid.ui.setup.LoginActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class KompaktAccountsActivity : AppCompatActivity() {

    @Inject
    lateinit var accountsDrawerHandler: AccountsDrawerHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val syncAccounts = intent.action == Intent.ACTION_SYNC

        setContent {
            KompaktAccountsScreen(
                initialSyncAccounts = syncAccounts,
                accountsDrawerHandler = accountsDrawerHandler,
                onAddAccount = {
                    startActivity(Intent(this, LoginActivity::class.java))
                },
                onBack = ::finish
            )
        }
    }

}
