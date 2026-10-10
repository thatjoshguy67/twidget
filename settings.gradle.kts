import java.io.File
import java.util.Properties

// Credentials for the tribalfs/oneui-design GitHub Packages repo. Local
// credentials live outside the checkout under ~/.config/twidget. CI falls
// back to environment variables.
val githubPropertiesFile = File(
    System.getProperty("user.home"),
    ".config/twidget/github.properties",
)
val githubProperties = Properties().apply {
    githubPropertiesFile.takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val ghPackagesUser: String =
    githubProperties.getProperty("ghUsername") ?: System.getenv("GH_PACKAGES_USER") ?: ""
val ghPackagesToken: String =
    githubProperties.getProperty("ghAccessToken") ?: System.getenv("GH_PACKAGES_TOKEN") ?: ""

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // Wildcard serves every tribalfs package (oneui-design plus the
        // sesl.androidx.* / sesl.com.google.* forks it depends on).
        maven("https://maven.pkg.github.com/tribalfs/*") {
            credentials {
                username = ghPackagesUser
                password = ghPackagesToken
            }
        }
    }
}

rootProject.name = "Twidget"
include(":app")
