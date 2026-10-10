package com.tjg.twidget.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project

/** AGP 9's built-in Kotlin takes its JVM target from compileOptions, so no Kotlin options are set. */
internal fun Project.configureKotlinAndroid(
    commonExtension: CommonExtension,
) {
    commonExtension.apply {
        compileSdk {
            version = release(37) {
                minorApiLevel = 2
            }
        }

        defaultConfig.apply {
            minSdk { version = release(26) }
        }

        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}
