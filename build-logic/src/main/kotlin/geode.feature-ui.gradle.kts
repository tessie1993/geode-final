// A feature's :ui (ADR-0005): stateless screens XScreen(model, onAction) with their previews and
// Roborazzi screenshots (geode.screenshots). An Android library with Compose that depends on its own
// :api and on :design (the App UI's tokens, theme and common components, added here so no feature
// has to), never on its :core or :handlers, nor on another feature.
plugins {
    id("geode.android-library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("geode.screenshots")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val feature = path.substringBeforeLast(':')

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "dev.geode" + path.replace(':', '.').replace('-', '_')
    buildFeatures {
        compose = true
    }
}

dependencies {
    "implementation"(project("$feature:api"))
    "implementation"(project(":design"))
    "implementation"(platform(catalog.findLibrary("compose-bom").get()))
    "implementation"(catalog.findLibrary("compose-ui").get())
    "implementation"(catalog.findLibrary("compose-material3").get())
    "implementation"(catalog.findLibrary("compose-ui-tooling-preview").get())
    "debugImplementation"(catalog.findLibrary("compose-ui-tooling").get())
}
