// Diagnostics' handlers (ADR-0005): they gather and write the diagnostics file, open the share sheet
// with it, and forward the engine's stats to the developer overlay.
plugins {
    id("geode.feature-handlers")
}

dependencies {
    // FileProvider: the file leaves the app through a content uri, never through any network.
    implementation(libs.androidx.core)
    testImplementation(testFixtures(project(":feature:diagnostics:core")))
}
