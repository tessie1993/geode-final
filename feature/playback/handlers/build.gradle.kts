// Playback's handlers (ADR-0005): they drive the audio adapter, turn its announcements into events,
// and keep the playlists, the play history and the saved session in Playback's own Room database
// (ADR-0004, ADR-0005).
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

dependencies {
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
