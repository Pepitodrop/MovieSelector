plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":piet-core"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
