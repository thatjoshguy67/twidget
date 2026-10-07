package com.tjg.twidget.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.ResValue
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure

/**
 * Adds the Buffer and Cloudinary settings as string resources. None are secret: Buffer uses PKCE
 * without a client secret, and Cloudinary an unsigned upload preset.
 */
internal fun Project.configureResValues() {
    extensions.configure<ApplicationExtension> {
        buildFeatures.resValues = true
    }
    extensions.configure<ApplicationAndroidComponentsExtension> {
        onVariants { variant ->
            variant.putString(
                name = "buffer_oauth_client_id",
                value = propertyOrEnv(
                    gradleProperty = "bufferOAuthClientId",
                    environmentVariable = "BUFFER_OAUTH_CLIENT_ID",
                ),
            )
            variant.putString(
                name = "buffer_oauth_redirect_uri",
                value = propertyOrEnv(
                    gradleProperty = "bufferOAuthRedirectUri",
                    environmentVariable = "BUFFER_OAUTH_REDIRECT_URI",
                    default = DEFAULT_BUFFER_OAUTH_REDIRECT_URI,
                ),
            )
            variant.putString(
                name = "cloudinary_cloud_name",
                value = propertyOrEnv(
                    gradleProperty = "cloudinaryCloudName",
                    environmentVariable = "CLOUDINARY_CLOUD_NAME",
                ),
            )
            variant.putString(
                name = "cloudinary_upload_preset",
                value = propertyOrEnv(
                    gradleProperty = "cloudinaryUploadPreset",
                    environmentVariable = "CLOUDINARY_UPLOAD_PRESET",
                ),
            )
        }
    }
}

private const val DEFAULT_BUFFER_OAUTH_REDIRECT_URI = "https://thatjoshguy67.github.io/twidget/oauth/buffer/"

private fun ApplicationVariant.putString(name: String, value: Provider<String>) {
    resValues.put(makeResValueKey("string", name), value.map { ResValue(it) })
}
