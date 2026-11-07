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
        versionCode = 1
        versionName = "1.0.0"
        // Web app loaded inside the native shell. Change to your own published URL.
        buildConfigField("String", "WEB_URL", "\"https://mxff-ai1.lovable.app\"")
        // Optional shared secret for /api/mxff-ask. Must equal MXFF_APP_TOKEN on the server. Leave empty to disable.
        buildConfigField("String", "APP_TOKEN", "\"\"")
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
