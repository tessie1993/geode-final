// A feature's :handlers (ADR-0005): the effect runner that carries out the reducer's effects (IO, the
// database, the audio adapter, the system) and reports results back as events. An Android library; it
// depends on its own :core and :api and on :arch:*, and may add another feature's :api to call its
// send or read its state, never that feature's :core, :handlers or :ui.
plugins {
    id("geode.android-library")
}

val feature = path.substringBeforeLast(':')

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "dev.geode" + path.replace(':', '.').replace('-', '_')
}

dependencies {
    "implementation"(project("$feature:core"))
    "implementation"(project("$feature:api"))
    "implementation"(project(":arch:kernel"))
    "implementation"(project(":arch:audio"))
    "testImplementation"(testFixtures(project(":arch:kernel")))
    "testImplementation"(testFixtures(project(":arch:audio")))
}
