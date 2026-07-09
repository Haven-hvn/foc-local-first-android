plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "cloud.filecoin.foc.cache"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
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
    // HTTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines / Flow
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // SQLite for cache metadata (LRU + TTL + quota accounting)
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("androidx.sqlite:sqlite-framework:2.4.0")

    // Optional JSON for PieceRef persistence
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
