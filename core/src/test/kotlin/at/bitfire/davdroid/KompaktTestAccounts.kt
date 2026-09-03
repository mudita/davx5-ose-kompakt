/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid

import android.accounts.Account
import io.mockk.mockk

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
