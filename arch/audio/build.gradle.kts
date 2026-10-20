// The audio adapter's interface and its announcements (ADR-0004, ADR-0005): pure Kotlin. The real
// adapter lives in :app, over the native pipeline; the fake is in this module's test fixtures.
plugins {
    id("geode.arch-jvm")
}

dependencies {
    api(project(":engine:audio-core"))
    api(libs.kotlinx.coroutines.core)
}
