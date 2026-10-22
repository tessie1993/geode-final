// Home's stateless screens (ADR-0003, ADR-0005): the Home tab, a placeholder until its Hero scene
// (#110), and the Element-styled bar of places, with their previews and Roborazzi screenshots in each
// Element. Built alongside the old shell and wired into the app at the switch-over (#17). It depends on
// Home's :api only.
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
