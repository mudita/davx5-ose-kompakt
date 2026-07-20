/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.network

import java.io.IOException
import kotlin.math.abs

/**
 * Thrown by [OAuthInterceptor] when a sync request fails with HTTP 401 while the device clock differs from
 * the server clock (read from the response `Date` header) by more than [CLOCK_SKEW_THRESHOLD_MS]. A wrong
 * device clock is exactly what makes AppAuth reject the refreshed Google ID token locally, which leaves the
 * request unauthenticated.
 *
 * Distinguishes a wrong device clock from a genuine authentication failure (a 401 with a correct clock):
 * [at.bitfire.davdroid.sync.SyncManager] classifies it into [at.bitfire.davdroid.sync.SyncResult.numClockSkewErrors]
 * so the UI shows the generic "Account sync failed" dialog instead of the (useless) re-authorization flow.
 * Extends [IOException] so it can be thrown from an OkHttp interceptor and propagate through the sync stack.
 */
class KompaktClockSkewException(message: String? = null, cause: Throwable? = null) : IOException(message, cause) {

    companion object {

        /**
         * Maximum tolerated difference between the device clock and the server clock (from the HTTP `Date`
         * header) before a 401 is treated as device-clock skew rather than a genuine auth failure. Set to
         * AppAuth's own ID-token `iat` tolerance (its hardcoded `TEN_MINUTES_IN_SECONDS`): a clock-skew 401
         * only occurs because the device clock is already >10 min off (that's what makes AppAuth reject the
         * refreshed ID token), so a 401 within this bound is a correctly-clocked device → a real auth failure
         * → re-auth.
         */
        const val CLOCK_SKEW_THRESHOLD_MS = 10 * 60 * 1000L

        /** True if the device and server clocks differ by more than [CLOCK_SKEW_THRESHOLD_MS]. */
        fun isClockSkewed(deviceTimeMillis: Long, serverTimeMillis: Long): Boolean =
            abs(deviceTimeMillis - serverTimeMillis) > CLOCK_SKEW_THRESHOLD_MS

    }

}
