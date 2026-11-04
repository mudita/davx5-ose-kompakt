/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.log

import android.content.Context
import at.bitfire.davdroid.R

/**
 * Kompakt: switch for app logging, set per build type.
 *
 * Logging is enabled in debug and QA builds and completely disabled in the production release
 * build (MOK-8236), because log records may contain OAuth tokens, account e-mail addresses,
 * event details and contact data. The value comes from the `kompakt_logging_enabled` bool resource:
 * `false` by default in core (fail-safe), set explicitly for every build type in
 * `app-ose/build.gradle.kts`.
 */
object KompaktLogging {

    fun isEnabled(context: Context): Boolean =
        context.resources.getBoolean(R.bool.kompakt_logging_enabled)

}
