/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * [KompaktStorage] behind an injectable seam, so a caller that refuses to sync on low storage can be
 * tested without a [Context].
 */
class KompaktStorageAvailability @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun isLow(): Boolean = KompaktStorage.isStorageLow(context)

}
