import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.tjg.twidget.buildlogic.AppVersion
import com.tjg.twidget.buildlogic.GenerateDebugChangelog
import com.tjg.twidget.buildlogic.GenerateSamsungThemeMetadata
import com.tjg.twidget.buildlogic.configureFlavors
import com.tjg.twidget.buildlogic.configureKotlinAndroid
import com.tjg.twidget.buildlogic.configureResValues
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.application")
            val appVersion = AppVersion.load(this)

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                configureFlavors(this)
                defaultConfig {
                    targetSdk { version = release(37) }
                    versionCode = appVersion.stableVersionCode
                    versionName = appVersion.versionName
                }
            }
            configureResValues()
            extensions.configure<ApplicationAndroidComponentsExtension> {
                onVariants { variant ->
                    variant.outputs.forEach { output ->
                        output.versionName.set(appVersion.versionNameFor(variant.buildType))
                        output.versionCode.set(appVersion.versionCodeFor(variant.buildType))
                    }
                    addSamsungThemeMetadata(variant)
                    if (variant.buildType == "debug") {
                        addDebugChangelog(variant)
                    }
                }
            }
        }
    }
}

/** Samsung requires the installed package name. */
private fun Project.addSamsungThemeMetadata(variant: ApplicationVariant) {
    val task = tasks.register<GenerateSamsungThemeMetadata>(variant.taskName("SamsungThemeMetadata")) {
        templateFile.set(layout.projectDirectory.file("src/main/theme/meta_998_sesl_app.xml"))
        applicationId.set(variant.applicationId)
        outputDirectory.set(layout.buildDirectory.dir("generated/${variant.name}SamsungThemeMetadata/res"))
    }
    variant.sources.res?.addGeneratedSourceDirectory(task, GenerateSamsungThemeMetadata::outputDirectory)
}

private fun Project.addDebugChangelog(variant: ApplicationVariant) {
    val task = tasks.register<GenerateDebugChangelog>(variant.taskName("Changelog")) {
        changelogFile.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
        outputDirectory.set(layout.buildDirectory.dir("generated/${variant.name}Changelog/assets"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(task, GenerateDebugChangelog::outputDirectory)
}

private fun ApplicationVariant.taskName(output: String): String =
    "generate${name.replaceFirstChar(Char::uppercaseChar)}$output"