// The Library's state machine (ADR-0003, ADR-0005): the pure reducer, its internal events and its
// effects. It sends play Commands to Playback through Playback's :api only (ADR-0004). Held to the
// feature-core mutation floor in config/mutation/thresholds.properties.
plugins {
    id("geode.feature-core")
}

dependencies {
    implementation(project(":feature:playback:api"))
}
