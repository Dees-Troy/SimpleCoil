// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    dependencies {
        // AGP compiles Kotlin itself (built-in Kotlin); this only raises the Kotlin compiler
        // above the version AGP bundles.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
