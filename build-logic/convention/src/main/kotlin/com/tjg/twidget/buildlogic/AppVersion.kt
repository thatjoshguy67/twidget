package com.tjg.twidget.buildlogic

import org.gradle.api.Project
import java.util.Properties

/**
 * Each semantic version reserves 100 ordered Play Store version-code slots: beta 80-97, trusted
 * debug 98 and stable 99. A permanent 100-code offset moves betas above the 1.3.0 stable code
 * (100300099) already uploaded to Play. Keep it so beta < debug < stable and upgrades to the
 * next version stay ordered.
 */
internal class AppVersion private constructor(
    val versionName: String,
    private val versionCodeBase: Int,
    private val debugNumber: Int,
    private val betaNumber: Int,
) {
    val stableVersionCode: Int get() = versionCodeBase + 99

    fun versionNameFor(buildType: String?): String = when (buildType) {
        "debug" -> "$versionName-debug.$debugNumber"
        "beta" -> "$versionName-beta.$betaNumber"
        else -> versionName
    }

    fun versionCodeFor(buildType: String?): Int = when (buildType) {
        "debug" -> versionCodeBase + 98
        "beta" -> versionCodeBase + 79 + betaNumber
        else -> stableVersionCode
    }

    companion object {
        fun load(project: Project): AppVersion = with(project) {
            val versionProperties = Properties().apply {
                rootProject.file("version.properties").inputStream().use { load(it) }
            }
            val versionName = versionProperties.getProperty("versionName")
                ?.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
                ?: error("version.properties must contain a semantic version such as versionName=1.0.0")
            val (major, minor, patch) = versionName.split('.').map(String::toInt)
            require(minor < 1_000 && patch < 1_000) {
                "Android version codes require version minor and patch components below 1000"
            }

            // Debug version names count the commits since the base version changed, so
            // every build stays identifiable. Their version code takes a fixed slot above
            // every beta, so trusted debug APKs can replace betas. The pre-release workflow
            // supplies the beta number, which restarts at 1 for each base version.
            val debugNumber = intProperty("prereleaseNumber") ?: commitsSinceVersionChange()
            val betaNumber = intProperty("betaNumber") ?: 1
            require(debugNumber > 0) { "prereleaseNumber must be greater than zero" }
            require(betaNumber > 0) { "betaNumber must be greater than zero" }
            require(betaNumber <= 18) {
                "Beta build number $betaNumber exceeds this version's Play Store slot range; bump versionName"
            }

            // Play rejects version codes above 2,100,000,000.
            val versionCodeBase = major * 100_000_000 + minor * 100_000 + patch * 100 + 100
            require(major in 0..20 && versionCodeBase + 99 <= 2_100_000_000) {
                "versionName $versionName cannot be represented as a Play Store version code"
            }
            AppVersion(versionName, versionCodeBase, debugNumber, betaNumber)
        }

        private fun Project.intProperty(name: String): Int? = providers.gradleProperty(name).orNull?.let { value ->
            value.toIntOrNull() ?: error("-P$name must be a whole number, got \"$value\"")
        }

        private fun Project.commitsSinceVersionChange(): Int {
            val versionFileStatus = git("status", "--porcelain", "--", "version.properties")
            val versionCommit = git("log", "-1", "--format=%H", "--", "version.properties")
            if (versionFileStatus.output.isNotBlank() || versionCommit.exitCode != 0 || versionCommit.output.isBlank()) {
                return 1
            }
            return git("rev-list", "--count", "${versionCommit.output}..HEAD")
                .output.toIntOrNull()?.plus(1) ?: 1
        }

        // providers.exec lets the configuration cache track the output.
        private fun Project.git(vararg args: String): CommandResult = runCatching {
            val execution = providers.exec {
                commandLine("git", *args)
                workingDir = rootProject.projectDir
                isIgnoreExitValue = true
            }
            CommandResult(execution.result.get().exitValue, execution.standardOutput.asText.get().trim())
        }.getOrElse { CommandResult(-1, "") }

        private data class CommandResult(val exitCode: Int, val output: String)
    }
}
