plugins {
    kotlin("js")
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "movieSelector.js"
            }
            testTask { enabled = false } // browser tests need Chrome/Karma; the pure-logic tests run on Node
        }
        nodejs()
        binaries.executable()
    }
}

dependencies {
    testImplementation(kotlin("test"))
}
