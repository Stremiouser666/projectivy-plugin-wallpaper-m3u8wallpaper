plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.projectivy.plugin.wallpaper.bingwallpaper"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Projectivy launcher library (replace with correct version if needed)
    implementation("tv.projectivy:launcher:1.0.0")

    // ExoPlayer for HLS playback
    implementation("com.google.android.exoplayer:exoplayer:2.20.0")

    // Kotlin standard library
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.10")
}
