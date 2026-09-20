plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("js") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    id("io.ktor.plugin") version "2.3.13" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
