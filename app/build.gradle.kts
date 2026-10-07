import com.tjg.twidget.buildlogic.loadReleaseKey
import com.tjg.twidget.buildlogic.shouldSignDebugWithRelease

plugins {
    alias(libs.plugins.twidget.android.application)
}

val releaseKey = loadReleaseKey()
val signDebugWithRelease = shouldSignDebugWithRelease(releaseKey)

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

    defaultConfig {
        applicationId = "com.tjg.twidget"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Default for contributors and pull requests. Trusted builds can opt
        // into the production certificate with -PsignDebugWithRelease=true so
        // they install over beta/stable builds without changing the debug
        // version suffix.
        getByName("debug") {
            storeFile = file("debug.keystore")
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
            if (signDebugWithRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation(libs.oneui.design)
    implementation(libs.lottie)
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
