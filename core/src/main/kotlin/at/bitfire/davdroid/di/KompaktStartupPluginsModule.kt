/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.di

import at.bitfire.davdroid.startup.KompaktAuthStateReplicator
import at.bitfire.davdroid.startup.StartupPlugin
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

// A separate module rather than an entry in upstream's StartupPluginsModule: Hilt merges
// multibindings across modules, so this contributes to the same set without editing that file.
@Module
@InstallIn(SingletonComponent::class)
interface KompaktStartupPluginsModule {

    @Binds
    @IntoSet
    fun kompaktAuthStateReplicator(impl: KompaktAuthStateReplicator): StartupPlugin

}
