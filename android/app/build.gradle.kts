plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mxff.ai"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mxff.ai"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.0.0"
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug key so the release APK installs without extra setup. Replace with your own keystore for Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
