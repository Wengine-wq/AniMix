import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna.platform)
}

compose.desktop {
    application {
        mainClass = "app.animix.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "AniMix"
            packageVersion = "3.0.0"
            description = "AniMix 3.0 New Horizons"
            vendor = "AniMix"
            windows {
                iconFile.set(project.file("icons/animix.ico"))
                menuGroup = "AniMix"
                shortcut = true
                upgradeUuid = "6f1c3f5e-7a2b-4f0e-9a51-3d7c2f8b9e10"
            }
        }
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}
