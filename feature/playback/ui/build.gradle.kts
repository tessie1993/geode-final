// Playback's stateless screens (ADR-0003, ADR-0005): the player and the mini-player, with their
// previews and Roborazzi screenshots.
plugins {
    id("geode.feature-ui")
}

android {
    testOptions {
        unitTests {
            // The screenshots render the module's own strings under Robolectric.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.compose.material.icons.extended)
    implementation(libs.lifecycle.runtime.compose)
}
