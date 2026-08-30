plugins {
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

allprojects {
    group = "io.github.haven-hvn"
    version = "0.1.0"
}

subprojects {
    plugins.withId("signing") {
        extensions.configure<org.gradle.plugins.signing.SigningExtension> {
            useGpgCmd()
        }
    }
}
