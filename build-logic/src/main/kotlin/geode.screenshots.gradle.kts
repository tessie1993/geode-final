// Screenshot tests of the App UI (never the Visual, which the shader golden frames cover). Each
// test renders composables under Robolectric and Roborazzi compares them with the goldens
// committed under src/test/screenshots. CI verifies them; see docs/ci.md for re-recording.
plugins {
    id("io.github.takahirom.roborazzi")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Robolectric's native graphics draw through the host's software pipeline; hardware pixel copy is
// what Roborazzi needs to read the frame back.
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")
    // Robolectric's Android 16 environment sets up ApplicationSharedMemory through
    // jdk.internal.access.SharedSecrets, which JDK 21 does not export to unnamed modules.
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
}

dependencies {
    "testImplementation"(platform(catalog.findLibrary("compose-bom").get()))
    "testImplementation"(catalog.findLibrary("compose-ui-test-junit4").get())
    "testImplementation"(catalog.findLibrary("robolectric").get())
    "testImplementation"(catalog.findLibrary("roborazzi").get())
    "testImplementation"(catalog.findLibrary("roborazzi-compose").get())
    "debugImplementation"(platform(catalog.findLibrary("compose-bom").get()))
    "debugImplementation"(catalog.findLibrary("compose-ui-test-manifest").get())
}
