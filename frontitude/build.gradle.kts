plugins {
    alias(libs.plugins.android.library)
    id("davx5.common-buildconfig")
}

android {
    namespace = "com.mudita.frontitude"
}

dependencies {
    coreLibraryDesugaring(libs.android.desugaring)
}

tasks.register<Exec>("moveFrontitudeFiles") {
    commandLine("python3", "move_frontitude_files.py")
    doLast {
        println("Python script executed successfully!")
    }
}
