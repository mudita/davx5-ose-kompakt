/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

interface KompaktAccountStatePublisher {

    fun publish()

}

class KompaktAccountStateNotifyPublisher @Inject constructor(
    @ApplicationContext private val context: Context
) : KompaktAccountStatePublisher {

    override fun publish() {
        context.contentResolver.notifyChange(KompaktAccountState.CONTENT_URI, null)
    }

}

@Module
@InstallIn(SingletonComponent::class)
interface KompaktAccountStatePublisherModule {

    @Binds
    fun kompaktAccountStatePublisher(impl: KompaktAccountStateNotifyPublisher): KompaktAccountStatePublisher

}
