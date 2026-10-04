plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "komascroll.translate.engine"
}

dependencies {
    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services")
    implementation(kotlinx.serialization.json)
    implementation(libs.okhttp.core)

    // ML Kit text recognition v2 through Google Play services: models are downloaded on demand
    // instead of being bundled (keeps the APK ~30 MB smaller). On-device translation downloads its
    // language models at runtime as well.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.1")
    implementation("com.google.android.gms:play-services-base:18.5.0")
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
