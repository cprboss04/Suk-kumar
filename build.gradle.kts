plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.agentpilot"
    compileSdk = 35                       // Android 15

    defaultConfig {
        applicationId = "com.agentpilot"
        minSdk = 30                       // Android 11+ (ACTION_IME_ENTER, insets API)
        targetSdk = 35                    // Android 15
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// Koi external dependency nahi: sirf Android framework + Kotlin stdlib (build fail hone ke chance kam)
