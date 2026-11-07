import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.animix.android"
    compileSdk = 37

    defaultConfig {
        // Its own id: AniMix 2.x (com.example.animix) was signed with a debug key that no longer
        // exists, so New Horizons installs next to it and updates only itself. Never change this
        // id or the release key below, or installed copies stop receiving updates.
        applicationId = "app.animix.newhorizons"
        minSdk = 26
        targetSdk = 36
        // versionCode only grows: 3000 + minor * 100 + patch of the "1.minor.patch re" line.
        versionCode = 3202
        versionName = "1.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key lives outside the repository: %USERPROFILE%\.animixelease.properties
    // (or ANIMIX_RELEASE_PROPERTIES) names release.jks and its passwords. Keep both backed up.
    val releaseProperties = (System.getenv("ANIMIX_RELEASE_PROPERTIES")?.let(::File)
        ?: File(System.getProperty("user.home"), ".animix/release.properties"))
        .takeIf { it.isFile }?.let { file -> Properties().apply { file.inputStream().use(::load) } }
    signingConfigs {
        if (releaseProperties != null) create("release") {
            storeFile = File(releaseProperties.getProperty("storeFile"))
            storePassword = releaseProperties.getProperty("storePassword")
            keyAlias = releaseProperties.getProperty("keyAlias")
            keyPassword = releaseProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // Debug builds install next to the released app instead of clashing with it.
            applicationIdSuffix = ".v3dev"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures { compose = true }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.layout.buildDirectory.dir("test-media").get().asFile)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/versions/9/previous-compilation-data.bin")
    }
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.ktor.client.mock)
}
