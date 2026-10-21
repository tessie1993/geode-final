pluginManagement {
    includeBuild("build-logic")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// Resolves Java toolchains from a public index so a machine that lacks the JDK this build asks
// for downloads it instead of failing. Both the daemon JVM criteria in
// gradle/gradle-daemon-jvm.properties and the compile toolchain in geode.kotlin-common depend on
// this being here: updateDaemonJvm refuses to write its per-platform download URLs without it.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "geode"
include(":app")

// The App UI's design tokens, theme and common components (ADR-0005), never anything of the Visual.
include(":design")

include(
    ":engine:audio-core",
    ":engine:scenes",
    ":engine:audio-android",
)

// The feature architecture (ADR-0005). Each feature adds :feature:<name>:api, :core, :handlers and
// :ui, built with the geode.feature-* convention plugins in build-logic.
include(
    ":arch:kernel",
    ":arch:audio",
)

include(
    ":feature:capture:api",
    ":feature:capture:core",
    ":feature:capture:handlers",
    ":feature:capture:ui",
)

// Customization (#39): the model and its reducer. Its :handlers and :ui come with later tickets.
include(
    ":feature:customization:api",
    ":feature:customization:core",
)
