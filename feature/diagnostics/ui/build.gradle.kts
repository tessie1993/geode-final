// Diagnostics' stateless screens (ADR-0005): the Settings section with "Share diagnostics" and the
// version that unlocks the developer options, and the developer overlay. App UI only.
plugins {
    id("geode.feature-ui")
}

android {
    testOptions {
        unitTests {
            // The screenshot tests render under Robolectric, which reads the module's real strings.
            isIncludeAndroidResources = true
        }
    }
}
