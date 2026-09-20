plugins {
    kotlin("js")
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "movieSelector.js"
            }
            binaries.executable()
        }
    }
}
