plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.animix.android"
    compileSdk = 37

    defaultConfig {
        // Same id as AniMix 2.x, so a release signed with the same key installs as an update.
        applicationId = "com.example.animix"
        minSdk = 26
        targetSdk = 36
        versionCode = 3000
        versionName = "3.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Debug builds install next to the released app instead of clashing with it.
            applicationIdSuffix = ".v3dev"
        }
        release {
            isMinifyEnabled = false
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
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.ktor.client.mock)
}
