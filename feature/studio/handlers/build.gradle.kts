// Studio's handlers (ADR-0005): the Projects, their videos and the render queue in Studio's own Room
// database (ADR-0004), the save folder and the Renders' settings followed through Settings' :api, the
// ports `:app` fills to freeze a Render's look and make its video (#338) and to record the live Visual
// (#317), the Edit pipeline on Media3 (#318), the files of deleted videos, and the old Studio's videos
// for the switch-over (#17) to import once.
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
    implementation(project(":feature:settings:api"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.documentfile)
    implementation(libs.kotlinx.coroutines.core)
    // The Edit pipeline (#318): Transformer, its effects and GL Transitions between clips.
    implementation(libs.media3.common)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    // The real org.json for the Renders' format: the android.jar stub only returns defaults in unit tests.
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
