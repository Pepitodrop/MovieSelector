import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

// Release signing secrets never live in the repo. They come from environment variables
// (MOVIE_SELECTOR_KEYSTORE / _KEYSTORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD) or from a local
// properties file outside the checkout (default ~/.config/movie-selector/signing/signing.properties,
// override with MOVIE_SELECTOR_SIGNING_PROPERTIES). Without either, the release build is unsigned.
val signingProps = Properties().apply {
    val path = System.getenv("MOVIE_SELECTOR_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.config/movie-selector/signing/signing.properties"
    file(path).takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: signingProps.getProperty(prop)
val releaseStoreFile = signingValue("MOVIE_SELECTOR_KEYSTORE", "storeFile")
val releaseSigningConfigured = releaseStoreFile != null && file(releaseStoreFile).isFile &&
    signingValue("MOVIE_SELECTOR_KEYSTORE_PASSWORD", "storePassword") != null &&
    signingValue("MOVIE_SELECTOR_KEY_ALIAS", "keyAlias") != null

android {
    namespace = "com.luisbenedikt.movieselector"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.luisbenedikt.movieselector"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Production traffic goes through the GamePage launcher's same-origin proxy.
        // Override with -PapiBaseUrl=http://10.0.2.2:8080/api for local emulator testing
        // against a backend running on the host machine.
        buildConfigField("String", "API_BASE_URL", "\"${project.findProperty("apiBaseUrl") ?: "https://game.luisbenedikt.de/play/movie-selector/api"}\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = signingValue("MOVIE_SELECTOR_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("MOVIE_SELECTOR_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("MOVIE_SELECTOR_KEY_PASSWORD", "keyPassword")
                    ?: signingValue("MOVIE_SELECTOR_KEYSTORE_PASSWORD", "storePassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes.add("META-INF/*.md")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.ktor:ktor-client-core:2.3.13")
    implementation("io.ktor:ktor-client-cio:2.3.13")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.13")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock:2.3.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
