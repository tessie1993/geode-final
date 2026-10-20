// A feature's :api (ADR-0005): its public interface, the state the outside may read, its Commands and
// its events. Pure JVM, and it may depend on :arch:kernel only. Other features depend on this module
// and on nothing else of the feature.
plugins {
    id("geode.arch-jvm")
    id("geode.api-dump")
}

dependencies {
    "api"(project(":arch:kernel"))
}
