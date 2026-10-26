// A feature's :core (ADR-0005): its state machine and pure reducer, internal events and effects as
// data. Pure JVM; it depends on its own :api and :arch:kernel, and may add other features' :api.
// Held to the feature-core mutation floor: add `:feature:<name>:core=80` to
// config/mutation/thresholds.properties and the module to the Kotlin jobs in config/mutation/modules.json.
plugins {
    id("geode.arch-jvm")
}

val feature = path.substringBeforeLast(':')

dependencies {
    "api"(project("$feature:api"))
    "api"(project(":arch:kernel"))
    "testImplementation"(testFixtures(project(":arch:kernel")))
}
