// A feature's :ui (ADR-0005): stateless screens XScreen(model, onAction) with their previews, and
// Robolectric tests of its routes driven through a Compose test rule. An Android library with
// Compose that depends on its own :api and on :design (the App UI's tokens, theme and common
// components, added here so no feature has to), never on its :core or :handlers, nor on another
// feature.
plugins {
    id("geode.android-library")
    id("org.jetbrains.kotlin.plugin.compose")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val feature = path.substringBeforeLast(':')

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "dev.geode" + path.replace(':', '.').replace('-', '_')
    buildFeatures {
        compose = true
    }
    // Robolectric needs the merged manifest and resources: the route tests start the test activity
    // from compose-ui-test-manifest and the screens read their own strings.
    testOptions {
        unitTests {
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
    "implementation"(project("$feature:api"))
    "implementation"(project(":design"))
    "implementation"(platform(catalog.findLibrary("compose-bom").get()))
    "implementation"(catalog.findLibrary("compose-ui").get())
    "implementation"(catalog.findLibrary("compose-material3").get())
    "implementation"(catalog.findLibrary("compose-ui-tooling-preview").get())
    "debugImplementation"(catalog.findLibrary("compose-ui-tooling").get())
    "testImplementation"(platform(catalog.findLibrary("compose-bom").get()))
    "testImplementation"(catalog.findLibrary("compose-ui-test-junit4").get())
    "testImplementation"(catalog.findLibrary("robolectric").get())
    "debugImplementation"(catalog.findLibrary("compose-ui-test-manifest").get())
}
