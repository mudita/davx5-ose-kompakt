/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package com.davx5.ose

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.work.Configuration
import at.bitfire.davdroid.CoreApp
import at.bitfire.davdroid.log.KompaktLogging
import dagger.hilt.android.HiltAndroidApp

/**
 * Actual implementation of Application, used for Hilt. Delegates to [CoreApp].
 */
@HiltAndroidApp
class App: CoreApp(), Configuration.Provider {

    /**
     * Kompakt (MOK-8236): WorkManager logs directly to logcat (incl. work tags, which contain the account
     * name/e-mail), so its logger must follow [KompaktLogging] like LogManager does for java.util.logging.
     *
     * [Configuration.Builder.setMinimumLoggingLevel] can't be used for that: WorkManager's logger is set-once
     * and falls back to a DEBUG logger on first use, and WorkManager already logs while creating its
     * schedulers — before it applies the configured level. So the logger is installed here, before anything
     * can touch WorkManager.
     */
    @SuppressLint("RestrictedApi")      // androidx.work.Logger is @RestrictTo(LIBRARY_GROUP)
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        androidx.work.Logger.setLogger(
            androidx.work.Logger.LogcatLogger(if (KompaktLogging.isEnabled(base)) Log.INFO else Log.ASSERT)
        )
    }

    /**
     * Required for Hilt/WorkManager integration, see:
     * https://developer.android.com/training/dependency-injection/hilt-jetpack#workmanager
     *
     * This requires to remove the androidx.work.WorkManagerInitializer from App Startup
     * in the AndroidManifest, see:
     * https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration#remove-default
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

}
