// Studio's handlers (ADR-0005): the Projects and their videos in Studio's own Room database
// (ADR-0004, shared with the #22 render queue), the old Studio's videos for the "Unsorted" Project,
// and the files and folder permissions of deleted Projects.
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

// Robolectric's Android 16 environment needs these on JDK 21 (as in :feature:library:handlers).
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
    implementation(libs.documentfile)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
