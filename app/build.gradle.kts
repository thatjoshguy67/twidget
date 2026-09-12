import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

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

// Debug builds use the commit distance from the base-version change in their
// version name so every build remains identifiable. Their version code uses a
// fixed slot above every beta, allowing trusted debug APKs to replace betas.
// Beta releases have their own sequence, supplied by the pre-release workflow,
// and reset to 1 for each base version.
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
val cloudinaryCloudName = providers.gradleProperty("cloudinaryCloudName").orNull
    ?: System.getenv("CLOUDINARY_CLOUD_NAME")
    ?: ""
val cloudinaryUploadPreset = providers.gradleProperty("cloudinaryUploadPreset").orNull
    ?: System.getenv("CLOUDINARY_UPLOAD_PRESET")
    ?: ""
require(debugNumber > 0) { "prereleaseNumber must be greater than zero" }
require(betaNumber > 0) { "betaNumber must be greater than zero" }
require(betaNumber <= 19) {
    "Beta build number $betaNumber exceeds this version's Play Store slot range; bump versionName"
}

// Reserve 100 monotonically ordered Play Store version-code slots for each
// semantic version: beta 80-98, trusted debug 98, and stable 99. The layout
// stays below Play's 2,100,000,000 ceiling through version 20.999.999.
val versionCodeBase =
    versionMajor * 100_000_000 + versionMinor * 100_000 + versionPatch * 100
val stableVersionCode = versionCodeBase + 99
require(versionMajor in 0..20 && stableVersionCode <= 2_100_000_000) {
    "versionName $baseVersionName cannot be represented as a Play Store version code"
}

android {
    namespace = "com.tjg.twidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tjg.twidget"
        minSdk = 26
        targetSdk = 36
        versionCode = stableVersionCode
        versionName = baseVersionName
        resValue("string", "buffer_oauth_client_id", bufferOAuthClientId)
        resValue("string", "cloudinary_cloud_name", cloudinaryCloudName)
        resValue("string", "cloudinary_upload_preset", cloudinaryUploadPreset)
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

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

abstract class GenerateDebugChangelog : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val changelogFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        changelogFile.get().asFile.copyTo(directory.resolve("upcoming-changelog.md"), overwrite = true)
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val versionCode = when (variant.buildType) {
            "debug" -> versionCodeBase + 98
            "beta" -> versionCodeBase + 79 + betaNumber
            else -> stableVersionCode
        }
        variant.outputs.forEach { output ->
            output.versionCode.set(versionCode)
        }
        if (variant.buildType == "debug") {
            val changelog = tasks.register<GenerateDebugChangelog>("generateDebugChangelog") {
                changelogFile.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
                outputDirectory.set(layout.buildDirectory.dir("generated/debugChangelog/assets"))
            }
            variant.sources.assets?.addGeneratedSourceDirectory(changelog, GenerateDebugChangelog::outputDirectory)
        }
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
    // Play Services pulls stock Fragment, but One UI Design supplies the SESL
    // implementation under the same AndroidX package names.
    exclude(group = "androidx.fragment", module = "fragment")
    exclude(group = "androidx.slidingpanelayout", module = "slidingpanelayout")
    exclude(group = "com.google.android.material", module = "material")
}

dependencies {
    implementation("io.github.tribalfs:oneui-design:0.9.13+oneui8")
    implementation("com.airbnb.android:lottie:6.6.2")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.github.oneuiproject:icons:1.1.0")
    implementation("sesl.androidx.swiperefreshlayout:swiperefreshlayout:1.2.0-alpha01+1.0.0-sesl8+rev0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20251224")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
