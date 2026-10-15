rootProject.name = "AniMix"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":shared")
include(":desktopApp")

// The Android app is only part of the build when an Android SDK is present,
// so the desktop app can be built on machines without one.
val androidSdkPresent = run {
    val local = file("local.properties").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
    val sdk = local ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    sdk != null && file(sdk.replace("\\\\", "\\")).exists()
}
if (androidSdkPresent) include(":androidApp")
gradle.extra["androidSdkPresent"] = androidSdkPresent
