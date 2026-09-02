/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.settings

import net.openid.appauth.AuthState
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

// Robolectric because AppAuth serializes through org.json, which a plain JVM test only has as a stub
// that throws — under which the "unparseable" case below would pass without parsing anything.
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)      // required because main project uses Conscrypt, but unit tests do not
class KompaktAccountSettingsAuthStateTest {

    @Test
    fun parsesWhatTheStoreActuallyHolds() {
        // Round-tripped rather than hand-written, so the test cannot pass against a JSON shape AppAuth
        // does not produce. The empty constructor is used because the others need a real Uri.
        assertNotNull(authStateOf(AuthState().jsonSerializeString()))
    }

    @Test
    fun absentAuthStateIsNull() {
        assertNull(authStateOf(null))
    }

    @Test
    fun unparseableAuthStateIsNullRatherThanAThrow() {
        assertNull(authStateOf("not json"))
    }

}
