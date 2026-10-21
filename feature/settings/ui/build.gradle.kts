// Settings' screens (ADR-0005): the stateless SettingsScreen(model, onAction), its previews and their
// Roborazzi screenshots. It depends on Settings' :api only.
plugins {
    id("geode.feature-ui")
}

android {
    testOptions {
        unitTests {
            // The screenshots render this module's own strings under Robolectric.
            isIncludeAndroidResources = true
        }
    }
}
