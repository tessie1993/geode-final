plugins {
    id("geode.android-library")
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.geode.presentation"
    buildFeatures.compose = true
}
dependencies {
    implementation(project(":presentation-state"))
    implementation(project(":app-runtime"))
    implementation(project(":engine:scenes"))
    implementation(project(":engine:audio-android"))
    implementation(project(":app-resources"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.documentfile)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
}
