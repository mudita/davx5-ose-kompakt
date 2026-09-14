/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.account

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * The one dialog the linked-account screen is showing, and the only thing that decides which.
 *
 * Events and conditions are kept apart because they expire differently. An **event** — a sync
 * verdict, a confirmation the user asked for — happened once, so one that arrives outranked is
 * dropped rather than queued. A **condition** — needing re-auth, owing the Contacts consent offer —
 * is true until its source says otherwise, so it keeps its place and takes the slot as soon as
 * nothing outranks it. Raising a condition on its edges instead would lose it whenever it was
 * outranked at the moment it became true, and its source would never say so again.
 *
 * Deliberately unscoped: this is one screen's mutable state, so every injection point gets its own.
 */
class KompaktLinkedAccountDialogSlot @Inject constructor() {

    private data class State(
        val raised: KompaktLinkedAccountDialog? = null,
        val conditions: Set<KompaktLinkedAccountDialog> = emptySet()
    ) {
        val showing: KompaktLinkedAccountDialog?
            get() = (conditions + listOfNotNull(raised)).minByOrNull(::rank)
    }

    private val _state = MutableStateFlow(State())

    val dialog: Flow<KompaktLinkedAccountDialog?> =
        _state.map { it.showing }.distinctUntilChanged()

    /** Shows [dialog] unless something already showing outranks it. Equal rank replaces, so the newest payload wins. */
    fun raise(dialog: KompaktLinkedAccountDialog) {
        // An event is retired by being dismissed or outranked, and a non-dismissible one can be neither.
        require(dialog.dismissible) { "$dialog is not dismissible, so it can only hold as a condition" }
        _state.update { state ->
            val showing = state.showing
            if (showing == null || rank(dialog) <= rank(showing))
                state.copy(raised = dialog)
            else
                state
        }
    }

    /** Retires whatever is showing, unless it is not [KompaktLinkedAccountDialog.dismissible]. */
    fun dismiss() {
        _state.update { state ->
            val showing = state.showing
            if (showing == null || !showing.dismissible)
                state
            else
                State(
                    raised = state.raised.takeIf { it != showing },
                    conditions = state.conditions - showing
                )
        }
    }

    /**
     * Reports whether a condition holds. Dismissing it retires it here too, without waiting for the source.
     *
     * A condition becoming true drops an event it outranks, rather than covering it: an event that loses
     * is dropped whichever of the two arrived first, so it cannot reappear when the condition clears.
     */
    fun condition(dialog: KompaktLinkedAccountDialog, active: Boolean) {
        _state.update { state ->
            if (!active)
                state.copy(conditions = state.conditions - dialog)
            else
                State(
                    raised = state.raised?.takeIf { rank(it) < rank(dialog) },
                    conditions = state.conditions + dialog
                )
        }
    }

}
