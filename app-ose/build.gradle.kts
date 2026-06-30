/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

import davx5.buildlogic.KompaktAppVersion
import tasks.KompaktDeployTask

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.mikepenz.aboutLibraries.android)
    id("davx5.common-buildconfig")
}

val kompaktAppName = "davx"

android {
    defaultConfig {
        applicationId = "at.bitfire.davdroid.mudita"

        // Kompakt product version (see KompaktAppVersion); CI overrides the code via VERSION_CODE env.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: KompaktAppVersion.CODE
        versionName = KompaktAppVersion.NAME

        base.archivesName = "$kompaktAppName-$versionName"

        /* Android prevents having two apps installed with the same provider authority name. In that case,
        Google Play just shows a generic "Can't install DAVx5" message. So we derive the authority names
        from the package ID, so that the build variants (and clones) have their own authority names and
        can be installed beside DAVx5. */
        val webdavAuthority = "${applicationId}.provider.webdav"
        val debugInfoAuthority = "${applicationId}.provider.debuginfo"
        manifestPlaceholders["webdavAuthority"] = webdavAuthority
        manifestPlaceholders["debugInfoAuthority"] = debugInfoAuthority
        /* Override the default string values from the core library (core/src/main/res/values/strings.xml)
        so that code using getString(R.string.webdav_authority) etc. gets the correct authority. */
        resValue("string", "webdav_authority", webdavAuthority)
        resValue("string", "authority_debug_provider", debugInfoAuthority)

        // Currently no instrumentation tests for app-ose, so no testInstrumentationRunner
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    // Java namespace for our classes (not to be confused with Android package ID)
    namespace = "com.davx5.ose"

    flavorDimensions += "distribution"
    productFlavors {
        create("ose") {
            dimension = "distribution"
        }
    }

    androidResources {
        generateLocaleConfig = true
    }

    @Suppress("UnstableApiUsage")
    testOptions {
        managedDevices {
            localDevices {
                create("virtual") {
                    device = "Pixel 3"
                    // TBD: API level 35 and higher causes network tests to fail sometimes, see https://github.com/bitfireAT/davx5-ose/issues/1525
                    // Suspected reason: https://developer.android.com/about/versions/15/behavior-changes-all#background-network-access
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "system-debug"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules-release.pro")

            isShrinkResources = true

            signingConfig = signingConfigs.getByName("debug")
        }
        create("qa") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    // include core subproject (manages its own dependencies itself, however from same version catalog)
    implementation(project(":core"))

    // Kotlin / Android
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines)
    coreLibraryDesugaring(libs.android.desugaring)

    // Hilt
    implementation(libs.hilt.android.base)
    ksp(libs.androidx.hilt.compiler)
    ksp(libs.hilt.android.compiler)

    // support libs
    implementation(libs.androidx.core)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.lifecycle.viewmodel.base)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.base)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.toolingPreview)

    // own libraries
    implementation(libs.bitfire.cert4android)

    // third-party libs
    implementation(libs.guava)
    implementation(libs.okhttp.base)
    implementation(libs.openid.appauth)
}

tasks.register<KompaktDeployTask>("uploadApkToNexus") {
    appName = kompaktAppName
    tagPrefix = (project.findProperty("tagPrefix") as String?) ?: "development"
    versionName = android.defaultConfig.versionName ?: ""
    nexusUrl = (project.findProperty("nexusUrl") as String?) ?: ""
    nexusUsername = (project.findProperty("nexusUsername") as String?) ?: ""
    nexusPassword = (project.findProperty("nexusPassword") as String?) ?: ""
}

tasks.register("checkVersion") {
    doFirst {
        val current = android.defaultConfig.versionName
        val ref = System.getenv("GITHUB_REF") ?: throw GradleException("GITHUB_REF not set")
        val match = Regex("(release|development|qa)\\.(\\d+\\.\\d+\\.\\d+(-\\w*)?)")
            .find(ref.removePrefix("refs/tags/"))
            ?: throw GradleException("The git tag does not follow the required 'type.x.y.z' pattern.")
        val tagVersion = match.groupValues[2]
        if (current != tagVersion) {
            throw GradleException("Build version ($current) does not match tag version ($tagVersion).")
        }
    }
}