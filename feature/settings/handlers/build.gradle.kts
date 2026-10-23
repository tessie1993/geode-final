// Settings' handlers (ADR-0005): they read and write the settings in DataStore (ADR-0004), and on the
// first run migrate them, unchanged, from today's SharedPreferences files.
plugins {
    id("geode.feature-handlers")
}

android {
    testOptions {
        unitTests {
            // The migration test loads real old preferences files under Robolectric.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.datastore.preferences)
    testImplementation(libs.robolectric)
}

// Robolectric's Android 16 environment needs these on JDK 21.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
}
