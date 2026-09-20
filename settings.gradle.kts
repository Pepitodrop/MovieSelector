rootProject.name = "movie-selector"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":piet-core")
include(":game-engine")
include(":backend")
include(":web")

// The Android module needs a real Android SDK. Keep the JVM/JS build green in
// environments (like CI for the other modules) that don't have one installed.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()
if (hasAndroidSdk) {
    include(":android")
}
