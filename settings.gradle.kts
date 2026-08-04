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

// Optional local oneui-design source — fork at https://github.com/KingOwen2006/oneui-design
// Submodule: git submodule update --init libs/oneui-design
val localOneUiDesignDir = file("libs/oneui-design")
val usePublishedOneUiDesign = providers.gradleProperty("usePublishedOneUiDesign")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: false

if (!usePublishedOneUiDesign && localOneUiDesignDir.isDirectory) {
    val oneuiGithubProps = File(localOneUiDesignDir, "github.properties")
    if (!oneuiGithubProps.isFile && githubPropertiesFile.isFile) {
        githubPropertiesFile.copyTo(oneuiGithubProps, overwrite = true)
    }
    includeBuild(localOneUiDesignDir) {
        dependencySubstitution {
            substitute(module("io.github.tribalfs:oneui-design"))
                .using(project(":oneui-design"))
        }
    }
}
