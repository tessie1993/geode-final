plugins {
    id("geode.android-library")
}

android {
    namespace = "dev.geode.engine.live"
}

// The live audio engine: the native player, its sink tap and DSP, and the microphone Live input. It owns
// the sound path beneath Playback; :app only wires it.
dependencies {
    api(project(":engine:audio-android"))
    api(project(":engine:scenes"))
    api(libs.media3.exoplayer)
    api(libs.media3.common)
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutines.android)
}
