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

// Playback (#34): play, pause, seek and next as one state machine over the audio adapter, with its
// playlists and play history in its own Room database.
include(
    ":feature:playback:api",
    ":feature:playback:core",
    ":feature:playback:handlers",
    ":feature:playback:ui",
)

include(
    ":feature:capture:api",
    ":feature:capture:core",
    ":feature:capture:handlers",
    ":feature:capture:ui",
)

// Settings (#38): today's settings on one store, in DataStore (ADR-0004).
include(
    ":feature:settings:api",
    ":feature:settings:core",
    ":feature:settings:handlers",
    ":feature:settings:ui",
)

// Customization (#39): the model and its reducer; its handlers (#142), which hand it to the engine and
// keep the preset files; its screens (#42), the basic and Advanced views.
include(
    ":feature:customization:api",
    ":feature:customization:core",
    ":feature:customization:handlers",
    ":feature:customization:ui",
)

// Library (#37): browse, search, sort and filter the phone's music, with favourites, song info edits,
// excluded folders and M3U playlists. Plays everything through Playback's :api.
include(
    ":feature:library:api",
    ":feature:library:core",
    ":feature:library:handlers",
    ":feature:library:ui",
)

// Diagnostics (#62): the "Share diagnostics" file and the hidden developer overlay. Built alongside
// the old code and not yet wired into :app (ADR-0003).
include(
    ":feature:diagnostics:api",
    ":feature:diagnostics:core",
    ":feature:diagnostics:handlers",
    ":feature:diagnostics:ui",
)
