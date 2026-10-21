// :design (ADR-0005): the App UI's design tokens, theme and common components, never anything of the
// Visual. An Android library with Compose, set up the same way as a feature's :ui (geode.feature-ui),
// which depends on it. It depends on no other module of this build: no feature and no :arch.
plugins {
    id("geode.android-library")
    id("org.jetbrains.kotlin.plugin.compose")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "dev.geode.design"
    buildFeatures {
        compose = true
    }
}

dependencies {
    // The tokens and components are Compose types (Color, Typography, Shapes, Modifier), so whoever
    // uses :design gets Compose UI and Material 3 with it.
    "api"(platform(catalog.findLibrary("compose-bom").get()))
    "api"(catalog.findLibrary("compose-ui").get())
    "api"(catalog.findLibrary("compose-material3").get())
    "implementation"(catalog.findLibrary("compose-ui-tooling-preview").get())
    "debugImplementation"(catalog.findLibrary("compose-ui-tooling").get())
}
