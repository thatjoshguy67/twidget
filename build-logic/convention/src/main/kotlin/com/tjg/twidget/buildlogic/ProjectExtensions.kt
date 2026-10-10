package com.tjg.twidget.buildlogic

import org.gradle.api.Project
import org.gradle.api.provider.Provider

internal fun Project.propertyOrEnv(
    gradleProperty: String,
    environmentVariable: String,
    default: String = "",
): Provider<String> =
    providers.gradleProperty(gradleProperty).orElse(providers.environmentVariable(environmentVariable)).orElse(default)
