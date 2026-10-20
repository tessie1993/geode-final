// The kernel every feature store is built from (ADR-0005): pure Kotlin, held to the feature-core
// mutation floor. Its test fixtures (a fake clock, an in-place runtime) are for any module's tests.
plugins {
    id("geode.arch-jvm")
}

dependencies {
    api(project(":engine:audio-core"))
    api(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
    // The sample feature in this module's tests talks to the audio adapter's fake.
    testImplementation(project(":arch:audio"))
    testImplementation(testFixtures(project(":arch:audio")))
}
