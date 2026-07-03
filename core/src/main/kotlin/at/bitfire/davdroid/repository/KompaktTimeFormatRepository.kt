/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.repository

import android.content.Context
import android.database.ContentObserver
import android.provider.Settings
import android.text.format.DateFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/**
 * Exposes the device's 12/24-hour time-format setting as a reactive [Flow], backed by a
 * [ContentObserver] on [Settings.System.TIME_12_24] (the same mechanism the framework's `TextClock`
 * uses for the format). Keeps the system-settings observer out of the ViewModel.
 */
class KompaktTimeFormatRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * The current "use 24-hour format" flag, re-emitted whenever the system setting changes.
     *
     * Cold flow: the observer is registered per collector and unregistered when collection stops.
     */
    val is24HourFormat: Flow<Boolean> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(DateFormat.is24HourFormat(context))
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.TIME_12_24), false, observer
        )
        trySend(DateFormat.is24HourFormat(context))     // emit the current value up front
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()

}
