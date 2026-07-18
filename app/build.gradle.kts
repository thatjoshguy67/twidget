import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing config. Local production credentials live outside the
// checkout by default, under ~/.config/twidget/keystore.properties. CI uses
// RELEASE_* environment variables. Absent either, release builds stay
// unsigned and debug builds keep using the checked-in debug key.
val signingPropertiesFile = providers.gradleProperty("twidgetSigningProperties")
    .orNull
    ?.let { rootProject.file(it) }
    ?: File(System.getProperty("user.home"), ".config/twidget/keystore.properties")
val keystoreProperties = Properties().apply {
    signingPropertiesFile.takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey) ?: System.getenv(envKey)
val releaseStoreFile: String? = signingValue("storeFile", "RELEASE_STORE_FILE")
val signDebugWithRelease = providers.gradleProperty("signDebugWithRelease")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: false
require(!signDebugWithRelease || releaseStoreFile != null) {
    "-PsignDebugWithRelease=true requires the release signing credentials"
}

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

// Debug builds use the commit distance from the base-version change so every
// main build remains identifiable. Beta releases have their own sequence,
// supplied by the pre-release workflow, and reset to 1 for each base version.
val debugNumber = providers.gradleProperty("prereleaseNumber").orNull?.toIntOrNull()
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
val betaNumber = providers.gradleProperty("betaNumber").orNull?.toIntOrNull() ?: 1
val bufferOAuthClientId = providers.gradleProperty("bufferOAuthClientId").orNull
    ?: System.getenv("BUFFER_OAUTH_CLIENT_ID")
    ?: ""
require(debugNumber > 0) { "prereleaseNumber must be greater than zero" }
require(betaNumber > 0) { "betaNumber must be greater than zero" }

android {
    namespace = "com.tjg.twidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tjg.twidget"
        minSdk = 26
        targetSdk = 35
        versionCode = versionMajor * 1_000_000 + versionMinor * 1_000 + versionPatch
        versionName = baseVersionName
        resValue("string", "buffer_oauth_client_id", bufferOAuthClientId)
        resValue(
            "string",
            "buffer_oauth_redirect_uri",
            "https://thatjoshguy67.github.io/twidget/oauth/buffer/",
        )
    }

    signingConfigs {
        // Default for contributors and pull requests. Trusted builds can opt
        // into the production certificate with -PsignDebugWithRelease=true so
        // they install over beta/stable builds without changing the debug
        // version suffix.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = File(releaseStoreFile).let { path ->
                    if (path.isAbsolute) path else signingPropertiesFile.parentFile.resolve(path)
                }
                storePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug.$debugNumber"
            if (signDebugWithRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
        create("beta") {
            initWith(getByName("release"))
            versionNameSuffix = "-beta.$betaNumber"
            matchingFallbacks += listOf("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    testImplementation("org.json:json:20251224")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
