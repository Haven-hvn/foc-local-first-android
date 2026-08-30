import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.maven.publish)
}

android {
    namespace = "cloud.filecoin.foc.cache"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

afterEvaluate {
    tasks.named("javaDocReleaseGeneration").configure { enabled = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(group.toString(), project.name, version.toString())

    pom {
        name.set(project.name)
        description.set("A Kotlin/Android library that gives you the local-first, retrieve-from-network-on-miss UX from the Logos storage module, retargeted at Filecoin Onchain Cloud content uploaded via synapse-sdk.")
        inceptionYear.set("2026")
        url.set("https://github.com/Haven-hvn/foc-local-first-android")
        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("haven")
                name.set("Haven")
                url.set("https://github.com/Haven-hvn")
            }
        }
        scm {
            url.set("https://github.com/Haven-hvn/foc-local-first-android")
            connection.set("scm:git:git://github.com/Haven-hvn/foc-local-first-android.git")
            developerConnection.set("scm:git:ssh://github.com/Haven-hvn/foc-local-first-android.git")
        }
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
    testImplementation("androidx.test:core:1.7.0")
}
