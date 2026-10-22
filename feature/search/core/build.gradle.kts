// The Search feature's state machine (ADR-0003, ADR-0005): the pure reducer, its internal events and
// its effects, and today's matching. It reads the Library's songs and Playback's playlists, and sends
// picks to the Library, Playback and Customization, through their :api only. Held to the feature-core
// mutation floor in config/mutation/thresholds.properties.
plugins {
    id("geode.feature-core")
}

dependencies {
    implementation(project(":feature:library:api"))
    implementation(project(":feature:playback:api"))
    implementation(project(":feature:customization:api"))
}
