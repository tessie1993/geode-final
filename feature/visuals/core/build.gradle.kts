// The Visuals feature's state machine (ADR-0003, ADR-0005): the pure reducer, its internal events and
// its effects, the Style list made from Customization's manifests, and the saved library's format.
// It asks Customization for a Style through Customization's :api only. Held to the feature-core
// mutation floor in config/mutation/thresholds.properties.
plugins {
    id("geode.feature-core")
}

dependencies {
    implementation(project(":feature:customization:api"))
}
