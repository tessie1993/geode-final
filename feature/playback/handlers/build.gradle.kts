// Playback's handlers (ADR-0005): they drive the audio adapter, turn its announcements into events,
// and keep the playlists, the play history and the saved session in Playback's own Room database
// (ADR-0004, ADR-0005). They also publish Playback as the media session the notification, the lock
// screen and Bluetooth read, so music keeps playing with no screen in front of it (#36).
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
// jdk.internal.access.SharedSecrets, which JDK 21 does not export to unnamed modules.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
}

dependencies {
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.core)
    // The media session, the notification and the lock-screen controls (#36).
    implementation(libs.media3.common)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
