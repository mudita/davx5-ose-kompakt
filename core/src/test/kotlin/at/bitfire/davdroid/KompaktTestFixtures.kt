/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid

import android.accounts.Account
import io.mockk.every
import io.mockk.mockk
import net.openid.appauth.AuthState

const val TEST_ACCOUNT_NAME = "user@example.com"
const val TEST_ACCOUNT_TYPE = "bitfire.at.davdroid.mudita"

/**
 * An [Account] that reports a name and a type.
 *
 * Neither a real Account nor a plain mock will do here: the unit-test android.jar stubs the
 * constructor and `equals`, so a real one cannot be built, while `name` and `type` are final fields
 * rather than getters, which MockK cannot stub. The mock supplies the reference-based equality that
 * argument matching needs, and the fields are written into it directly.
 */
fun mockAccount(
    name: String = TEST_ACCOUNT_NAME,
    type: String = TEST_ACCOUNT_TYPE
): Account = mockk<Account>().apply {
    writeField("name", name)
    writeField("type", type)
}

private fun Account.writeField(field: String, value: String) {
    Account::class.java.getDeclaredField(field)
        .apply { isAccessible = true }
        .set(this, value)
}

/**
 * An [AuthState] granting [scopes].
 *
 * Mocked, not built: AuthState's constructors parse android.net.Uri, which a plain JVM test lacks.
 */
fun mockAuthState(vararg scopes: String): AuthState = authState(scopes.toSet())

/** An [AuthState] whose token response echoed no scope at all — not the same as granting none. */
fun mockAuthStateWithoutScopes(): AuthState = authState(null)

private fun authState(scopes: Set<String>?) = mockk<AuthState> {
    every { scopeSet } returns scopes
}
