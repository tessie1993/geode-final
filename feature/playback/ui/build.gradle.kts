// Playback's stateless screens (ADR-0003, ADR-0005): the player and the mini-player, with their
// previews.
plugins {
    id("geode.feature-ui")
}

dependencies {
    implementation(libs.compose.material.icons.extended)
    implementation(libs.lifecycle.runtime.compose)
}
