plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.ultrahdrwallpaper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ultrahdrwallpaper"
        minSdk = 34
        targetSdk = 35
        versionCode = 3
        versionName = "0.3-pq-experimental"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
