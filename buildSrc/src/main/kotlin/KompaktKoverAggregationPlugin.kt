import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Aggregates unit-test line coverage for the modules Kompakt owns.
 */
class KompaktKoverAggregationPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlinx.kover")

        extensions.configure<KoverProjectExtension> {
            merge {
                // :synctools is upstream code with its own workflow (test-synctools.yml).
                val upstreamModule = project(":synctools")
                subprojects { it != upstreamModule && it.hasKotlinSources() }

                createVariant("main") {
                    add("debug", optional = true)       // :core, an Android library
                    add("oseDebug", optional = true)    // :app-ose, product flavour "ose"
                }
            }

            reports {
                filters {
                    excludes {
                        annotatedBy(
                            "*Generated*",
                            "*Composable*",
                            "*Preview*",
                            // Reaches the Hilt modules that sit outside the di packages below —
                            // AppDatabase, SyncValidator, LoginValidator, SyncAdapterImpl,
                            // Android7DirtyVerifier — and keeps holding when one of them moves.
                            "dagger.Module",
                        )
                        packages(
                            // Dependency-injection wiring.
                            "at.bitfire.davdroid.di",
                            "com.davx5.ose.di",
                            "dagger.hilt",
                            "hilt_aggregated_deps",

                            // Compose UI.
                            "at.bitfire.davdroid.ui.composable",
                            "at.bitfire.davdroid.ui.icon",
                            "at.bitfire.davdroid.ui.widget",

                            // Upstream screens a Kompakt user has no route to.
                            "at.bitfire.davdroid.ui.about",
                            "at.bitfire.davdroid.ui.intro",
                            "at.bitfire.davdroid.ui.push",
                            "at.bitfire.davdroid.ui.webdav",
                            "com.davx5.ose.ui",

                            "at.bitfire.davdroid.resource",

                            // Run once per upgrade, against a database state no unit test builds.
                            "at.bitfire.davdroid.db.migration",
                            "at.bitfire.davdroid.settings.migration",
                        )
                        inheritedFrom(
                            "android.accounts.AbstractAccountAuthenticator",
                            "android.app.Application",
                            "android.app.Service",
                            "android.content.AbstractThreadedSyncAdapter",
                            "android.content.BroadcastReceiver",
                            "android.content.ContentProvider",
                            "android.provider.DocumentsProvider",
                            "androidx.work.CoroutineWorker",
                            "androidx.work.Worker",
                            "at.bitfire.davdroid.startup.StartupPlugin",
                            "at.bitfire.davdroid.sync.adapter.SyncAdapterService",
                            "org.unifiedpush.android.connector.PushService",
                        )
                        classes(
                            // Each name is listed twice: once exact, once with '$*' for the nested
                            // and lambda classes the compiler puts inside it. A trailing bare '*'
                            // would cover both, but it also swallows anything merely starting with
                            // the same name — '*_Impl*' would drop a hand-written Foo_Impl2, and the
                            // report gives no hint that it happened.
                            "*.BuildConfig",
                            "*.ComposableSingletons\$*",
                            "*.Hilt_*",
                            "*.R", "*.R\$*",
                            "*_Factory", "*_Factory\$*",
                            "*_GeneratedInjector", "*_GeneratedInjector\$*",
                            // Deliberately unanchored: Dagger appends to this marker rather
                            // than ending on it, as in Foo_HiltModules_KeyModule_ProvideFactory.
                            "*_HiltModules*",
                            "*_Impl", "*_Impl\$*",
                            "*_MembersInjector", "*_MembersInjector\$*",

                            "*ScreenKt", "*ScreenKt\$*",
                            "*Actions", "*Actions\$*",
                            "at.bitfire.davdroid.CoreApp", "at.bitfire.davdroid.CoreApp\$*",
                            "at.bitfire.davdroid.ui.*Activity", "at.bitfire.davdroid.ui.*Activity\$*",
                            "at.bitfire.davdroid.ui.DebugInfoGenerator", "at.bitfire.davdroid.ui.DebugInfoGenerator\$*",
                            "at.bitfire.davdroid.ui.OseTheme", "at.bitfire.davdroid.ui.OseTheme\$*",
                            "com.davx5.ose.App", "com.davx5.ose.App\$*",

                            // Framework-constructed classes whose own entry is already in
                            // inheritedFrom above, so only the nested and lambda half is left to
                            // name. StartupPlugin is the interface itself — its companion holds the
                            // priority constants and nothing else does.
                            "at.bitfire.davdroid.push.UnifiedPushService\$*",
                            "at.bitfire.davdroid.servicedetection.RefreshCollectionsWorker\$*",
                            "at.bitfire.davdroid.startup.StartupPlugin\$*",
                            "at.bitfire.davdroid.startup.TasksAppWatcher\$*",
                            "at.bitfire.davdroid.sync.account.AccountsCleanupWorker\$*",
                            "at.bitfire.davdroid.sync.adapter.SyncAdapterImpl\$*",
                            "at.bitfire.davdroid.sync.worker.BaseSyncWorker\$*",
                            "at.bitfire.davdroid.sync.worker.OneTimeSyncWorker\$*",
                            "at.bitfire.davdroid.sync.worker.PeriodicSyncWorker\$*",
                            "at.bitfire.davdroid.ui.KompaktSyncRequestReceiver\$*",
                        )
                    }
                }
            }
        }
    }

    private fun Project.hasKotlinSources(): Boolean =
        file("src/main/kotlin").exists()
}
