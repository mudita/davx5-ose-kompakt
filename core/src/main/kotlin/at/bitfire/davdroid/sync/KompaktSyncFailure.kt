/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

enum class KompaktSyncFailure {
    AuthExpired,
    ClockSkew,
    DeviceError,
    ServerProblem,
    NetworkProblem,
    Unknown
}
