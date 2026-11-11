plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

allprojects {
    group = "io.github.haven-hvn"
    version = "0.4.5"
}

subprojects {
    plugins.withId("signing") {
        extensions.configure<org.gradle.plugins.signing.SigningExtension> {
            useGpgCmd()
        }
    }
}
