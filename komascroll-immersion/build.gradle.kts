plugins {
    id("mihon.library")
    kotlin("android")
}

android {
    namespace = "komascroll.immersion"
}

dependencies {
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
