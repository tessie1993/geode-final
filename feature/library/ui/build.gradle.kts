// The Library's stateless screens (ADR-0003, ADR-0005): browsing, search, sort and filter, song
// info editing and playlists, with their previews and Roborazzi screenshots. Built alongside the old
// Library screen and wired into the app at the switch-over (#17). The per-theme layouts are #74.
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
    implementation(libs.activity.compose)
}
