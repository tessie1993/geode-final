// Customization's handlers (ADR-0005): they hand each changed Customization to the engine as one
// snapshot (ADR-0002, ADR-0004), save and load presets as versioned JSON files in the folder chosen in
// Settings > Default paths, and convert the presets an older Geode saved, once, keeping the originals.
plugins {
    id("geode.feature-handlers")
}

dependencies {
    // The preset folder and the mirror folder: Settings' Default paths.
    implementation(project(":feature:settings:api"))
    // A preset folder the person picked is a document tree.
    implementation(libs.documentfile)
    // The real org.json: the android.jar stub only returns defaults in unit tests.
    testImplementation(libs.org.json)
}
