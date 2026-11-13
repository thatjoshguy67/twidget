import com.tjg.twidget.buildlogic.loadReleaseKey

plugins {
    alias(libs.plugins.twidget.android.application)
}

val releaseKey = loadReleaseKey()

android {
    namespace = "com.tjg.twidget"

    buildFeatures {
        buildConfig = true
    }

    // Widgets choose their language independently of the app/device locale.
    // Every Play install therefore needs every supported translation offline.
    bundle {
        language {
            enableSplit = false
        }
    }

    // Original font is a test-only comparison fixture; it is never packaged in the app.
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("design-assets"))

    testBuildType = providers.gradleProperty("auditTestBuildType").orElse("debug").get()

    defaultConfig {
        applicationId = "com.tjg.twidget"
        // A platform-only runner exercises the actual shrunk app without keeping
        // AndroidX test dependencies that AGP otherwise deduplicates against it.
        testInstrumentationRunner = if (testBuildType == "audit") {
            "com.tjg.twidget.core.MinifiedSmokeRunner"
        } else "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Debug builds always use this development certificate, including CI.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKey != null) {
            create("release") {
                storeFile = releaseKey.storeFile
                storePassword = releaseKey.storePassword
                keyAlias = releaseKey.keyAlias
                keyPassword = releaseKey.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Null without a release key, so release builds are unsigned.
            signingConfig = signingConfigs.findByName("release")
        }
        // Minified, independently installed build for shrinker/device checks. Never published.
        create("audit") {
            initWith(getByName("release"))
            applicationIdSuffix = ".audit"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        create("beta") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
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
    // SESL9 Core and Fragment include their Kotlin extensions.
    exclude(group = "sesl.androidx.core", module = "core-ktx")
    exclude(group = "androidx.fragment", module = "fragment-ktx")
    exclude(group = "androidx.preference", module = "preference")
    exclude(group = "androidx.recyclerview", module = "recyclerview")
    exclude(group = "androidx.swiperefreshlayout", module = "swiperefreshlayout")
    exclude(group = "androidx.slidingpanelayout", module = "slidingpanelayout")
    exclude(group = "com.google.android.material", module = "material")
}

dependencies {
    constraints {
        // SESL's annotation dependency and ML Kit's runtime must use the split annotation layout.
        implementation("androidx.compose.runtime:runtime-android:1.9.0")
    }

    implementation(libs.oneui.design)
    implementation(libs.lottie)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.work.runtime)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.oneui.icons)
    implementation(libs.bundles.sesl9)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
