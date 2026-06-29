/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import at.bitfire.davdroid.repository.AccountRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.logging.Logger
import javax.inject.Inject

/**
 * Kompakt: lets another (same-signed) app log the user out, i.e. remove the linked account(s)
 * from the device. Guarded by the signature permission com.davx5.ose.permission.LOGOUT.
 * Mirrors KompaktSyncRequestReceiver; see docs/kompakt-integration.md.
 */
@AndroidEntryPoint
class KompaktLogoutRequestReceiver : BroadcastReceiver() {

    @Inject
    lateinit var accountRepository: AccountRepository

    private val logger = Logger.getLogger(javaClass.name)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOGOUT)
            return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                for (account in accountRepository.getAll())
                    accountRepository.delete(account.name)
            } catch (e: Exception) {
                logger.warning("Kompakt logout request failed: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOGOUT = "com.davx5.ose.action.LOGOUT"
    }
}
