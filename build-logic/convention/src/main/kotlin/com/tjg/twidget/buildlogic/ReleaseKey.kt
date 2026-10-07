package com.tjg.twidget.buildlogic

import org.gradle.api.Project
import java.io.File
import java.util.Properties

class ReleaseKey internal constructor(
    val storeFile: File,
    val storePassword: String?,
    val keyAlias: String?,
    val keyPassword: String?,
)

/**
 * Loads the release key from the signing properties file, then from `RELEASE_*` environment
 * variables. Returns null when neither provides a store file.
 */
fun Project.loadReleaseKey(): ReleaseKey? {
    val propertiesFile = signingPropertiesFile()
    val properties = Properties().apply {
        propertiesFile.takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }
    fun value(key: String, environmentVariable: String): String? =
        properties.getProperty(key) ?: providers.environmentVariable(environmentVariable).orNull

    val storeFile = File(value("storeFile", "RELEASE_STORE_FILE") ?: return null)
    return ReleaseKey(
        storeFile = if (storeFile.isAbsolute) storeFile else propertiesFile.parentFile.resolve(storeFile),
        storePassword = value("storePassword", "RELEASE_STORE_PASSWORD"),
        keyAlias = value("keyAlias", "RELEASE_KEY_ALIAS"),
        keyPassword = value("keyPassword", "RELEASE_KEY_PASSWORD"),
    )
}

fun Project.shouldSignDebugWithRelease(releaseKey: ReleaseKey?): Boolean {
    val signDebugWithRelease = providers.gradleProperty("signDebugWithRelease").orNull?.toBooleanStrictOrNull() ?: false
    require(!signDebugWithRelease || releaseKey != null) {
        "-PsignDebugWithRelease=true requires the release signing credentials"
    }
    return signDebugWithRelease
}

private fun Project.signingPropertiesFile(): File =
    providers.gradleProperty("twidgetSigningProperties").orNull?.let { rootProject.file(it) }
        ?: File(System.getProperty("user.home"), ".config/twidget/keystore.properties")
