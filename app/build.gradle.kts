import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing config. Locally: read from keystore.properties (gitignored).
// On CI: fall back to RELEASE_* environment variables. Absent either, release
// builds stay unsigned so debug builds and contributors are unaffected.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey) ?: System.getenv(envKey)
val releaseStoreFile: String? = signingValue("storeFile", "RELEASE_STORE_FILE")

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val baseVersionName = versionProperties.getProperty("versionName")
    ?.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
    ?: error("version.properties must contain a semantic version such as versionName=1.0.0")
val (versionMajor, versionMinor, versionPatch) = baseVersionName.split('.').map(String::toInt)
require(versionMinor < 1_000 && versionPatch < 1_000) {
    "Android version codes require version minor and patch components below 1000"
}

data class CommandResult(val exitCode: Int, val output: String)

fun git(vararg args: String): CommandResult = runCatching {
    val process = ProcessBuilder(listOf("git", *args))
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    CommandResult(process.waitFor(), process.inputStream.bufferedReader().use { it.readText().trim() })
}.getOrElse { CommandResult(-1, "") }

// A clean build's prerelease number is one plus the commit distance from the
// commit that set the base version. Changing version.properties resets it to 1.
// An explicit Gradle property remains available for non-Git build environments.
val prereleaseNumber = providers.gradleProperty("prereleaseNumber").orNull?.toIntOrNull()
    ?: run {
        val versionFileStatus = git("status", "--porcelain", "--", "version.properties")
        val versionCommit = git("log", "-1", "--format=%H", "--", "version.properties")
        if (versionFileStatus.output.isNotBlank() || versionCommit.exitCode != 0 || versionCommit.output.isBlank()) {
            1
        } else {
            git("rev-list", "--count", "${versionCommit.output}..HEAD")
                .output.toIntOrNull()?.plus(1) ?: 1
        }
    }
require(prereleaseNumber > 0) { "prereleaseNumber must be greater than zero" }

android {
    namespace = "com.tjg.twidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tjg.twidget"
        minSdk = 26
        targetSdk = 35
        versionCode = versionMajor * 1_000_000 + versionMinor * 1_000 + versionPatch
        versionName = baseVersionName
    }

    signingConfigs {
        // Checked-in debug keystore so every debug build — any machine, CI
        // included — shares a signature and installs update in place.
        // Debug-only: standard android/androiddebugkey credentials, no
        // security value.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug.$prereleaseNumber"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        create("beta") {
            initWith(getByName("release"))
            versionNameSuffix = "-beta.$prereleaseNumber"
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

configurations.configureEach {
    exclude(group = "androidx.core", module = "core")
    exclude(group = "androidx.core", module = "core-ktx")
    exclude(group = "androidx.customview", module = "customview")
    exclude(group = "androidx.coordinatorlayout", module = "coordinatorlayout")
    exclude(group = "androidx.drawerlayout", module = "drawerlayout")
    exclude(group = "androidx.viewpager2", module = "viewpager2")
    exclude(group = "androidx.viewpager", module = "viewpager")
    exclude(group = "androidx.appcompat", module = "appcompat")
    exclude(group = "androidx.slidingpanelayout", module = "slidingpanelayout")
    exclude(group = "com.google.android.material", module = "material")
}

dependencies {
    implementation("io.github.tribalfs:oneui-design:0.9.13+oneui8")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("io.github.oneuiproject:icons:1.1.0")
    implementation("sesl.androidx.swiperefreshlayout:swiperefreshlayout:1.2.0-alpha01+1.0.0-sesl8+rev0")
    testImplementation("junit:junit:4.13.2")
}
