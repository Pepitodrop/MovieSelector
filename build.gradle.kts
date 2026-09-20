plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("js") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    kotlin("plugin.compose") version "2.1.0" apply false
    id("io.ktor.plugin") version "2.3.13" apply false
    id("com.android.application") version "8.7.3" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
