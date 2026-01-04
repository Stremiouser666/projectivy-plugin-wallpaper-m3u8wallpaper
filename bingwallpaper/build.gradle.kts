plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.projectivy.plugin.wallpaperprovider.bingwallpaper"
    compileSdk = 34

    defaultConfig {
        applicationId = "tv.projectivy.plugin.wallpaperprovider.bingwallpaper"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        
        buildConfigField("String", "VERSION_NAME", "\"1.0.0\"")
    }
    
    buildFeatures {
        buildConfig = true
        aidl = true
    }
    
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    // Projectivy API
    implementation(project(":api"))
    
    // AndroidX Media3 (ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    
    // AndroidX Core libraries
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.leanback:leanback-preference:1.0.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    
    // Material Design
    implementation("com.google.android.material:material:1.11.0")
    
    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")
}