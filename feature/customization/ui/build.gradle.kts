// Customization's stateless screens (ADR-0003, ADR-0005): the Customize panel with its basic view
// (the 6 Macros) and its Advanced view (Reactions, Modulators, Camera, the Shared core, Extras and
// Macros), with their previews and Roborazzi screenshots. Built alongside the old Customize tabs and
// wired into the app at the switch-over (#17). It depends on Customization's :api only.
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
