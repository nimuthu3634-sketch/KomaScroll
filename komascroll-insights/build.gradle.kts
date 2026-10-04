plugins {
    id("mihon.library")
    kotlin("android")
}

android {
    namespace = "komascroll.insights"
}

dependencies {
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
