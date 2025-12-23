plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.projectivy.plugin.wallpaperprovider.bingwallpaper"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    // Projectivy provides runtime APIs
}
