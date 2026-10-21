// A pure JVM module of the feature architecture (ADR-0005): :arch:kernel, :arch:audio, and through
// geode.feature-api / geode.feature-core every feature's :api and :core. No Android, Kover coverage,
// every mutant run (geode.mutation-jvm) and held to its floor in config/mutation/thresholds.properties,
// and test fixtures that other modules' tests can use.
plugins {
    id("geode.jvm-library")
    `java-library`
    `java-test-fixtures`
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(catalog.findLibrary("kotlinx-coroutines-test").get())
}
