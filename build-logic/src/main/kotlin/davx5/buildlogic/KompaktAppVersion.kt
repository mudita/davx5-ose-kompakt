/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package davx5.buildlogic

/**
 * Version information for the Mudita Kompakt build of DAVx5.
 *
 * This is independent of the upstream [AppVersion] (which tracks the DAVx5 OSE release the
 * `kompakt` branch is rebased on). The Kompakt app ships under its own applicationId and product
 * version. In CI the [CODE] is overridden by the `VERSION_CODE` env var (see app-ose build script).
 */
object KompaktAppVersion {

    const val CODE: Int = 1
    const val NAME: String = "1.0.0-SNAPSHOT"

}
