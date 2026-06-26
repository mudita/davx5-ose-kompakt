package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.util.Locale
import javax.inject.Inject

abstract class KompaktDeployTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {

    @Input var versionName: String = ""
    @Input var appName: String = ""
    @Input var nexusUrl: String = ""
    @Input var nexusUsername: String = ""
    @Input var nexusPassword: String = ""

    @Input
    var tagPrefix: String = ""
        get() = field.lowercase(Locale.getDefault())

    private val buildType: String
        get() = if (tagPrefix == "development") "debug" else tagPrefix

    @TaskAction
    fun upload() {
        if (nexusUrl.isBlank() || nexusUsername.isBlank() || nexusPassword.isBlank()) {
            throw RuntimeException("Nexus credentials are not set")
        }

        val apkDir = project.layout.projectDirectory
            .dir("build/outputs/apk/ose/$buildType").asFile

        val apkFile = apkDir
            .listFiles { file -> file.extension == "apk" }
            ?.maxByOrNull { it.lastModified() }
            ?: throw RuntimeException("APK file does not exist in: ${apkDir.absolutePath}")

        val targetUrl = "${nexusUrl.trimEnd('/')}/kompakt-$appName/$tagPrefix/$versionName"

        execOperations.exec {
            commandLine(
                "curl", "-v", "--fail-with-body",
                "-u", "$nexusUsername:$nexusPassword",
                "--upload-file", apkFile.absolutePath,
                "$targetUrl/${apkFile.name}"
            )
        }
    }
}
