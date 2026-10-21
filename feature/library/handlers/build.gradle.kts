// The Library's handlers (ADR-0005): they scan the phone's music through MediaStore into the
// Library's own Room database (ADR-0004), keep the favourites, edits and excluded folders, read and
// write M3U files, and send Commands to Playback through its :api only.
plugins {
    id("geode.feature-handlers")
    id("com.google.devtools.ksp")
}

android {
    testOptions {
        unitTests {
            // The database tests run under Robolectric.
            isIncludeAndroidResources = true
        }
    }
}

// Robolectric's Android 16 environment sets up ApplicationSharedMemory through
// jdk.internal.access.SharedSecrets, which JDK 21 does not export to unnamed modules (the same
// flags geode.screenshots passes to the screenshot tests).
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
}

dependencies {
    implementation(project(":feature:playback:api"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
