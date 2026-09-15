/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.bitfire.davdroid.sync.KompaktConnectivity
import at.bitfire.davdroid.sync.KompaktOfflineCause
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Why linking an account cannot be started, for the screen that offers it.
 *
 * The linked-account screen learns this from the verdict of a sync it tried; linking has no such
 * attempt to read, because the whole flow is an OAuth page that would only fail to load. So the
 * question is asked before the flow is launched rather than after it fails.
 */
@HiltViewModel
class KompaktLinkAccountModel @Inject constructor(
    connectivity: KompaktConnectivity
) : ViewModel() {

    val offlineCause: StateFlow<KompaktOfflineCause?> =
        connectivity.observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

}
