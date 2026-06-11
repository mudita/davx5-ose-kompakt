/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package com.davx5.ose.di

import at.bitfire.davdroid.ui.setup.KompaktLoginTypesProvider
import at.bitfire.davdroid.ui.setup.LoginTypesProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface LoginTypesProviderModule {

    @Binds
    fun loginTypesProvider(impl: KompaktLoginTypesProvider): LoginTypesProvider

}
