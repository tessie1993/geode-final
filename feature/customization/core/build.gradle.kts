// Customization's state machine and pure reducer (ADR-0005), the built-in Style manifests and the
// one-time upgrade of old presets. Pure JVM; its own :api and :arch:kernel.
// Held to the feature-core mutation floor (config/mutation/thresholds.properties).
plugins {
    id("geode.feature-core")
}
